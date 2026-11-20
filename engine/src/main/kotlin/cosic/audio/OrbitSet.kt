package cosic.audio

import cosic.pattern.logger

/**
 * All orbits of the mixer, created on the first voice that uses them (superdoughoutput.mjs `getOrbit`: an orbit keeps the output
 * channels it was created with). The [Mixer] calls [beginBlock], lets the voices add to their orbits, and then [render], which lets every orbit finish its block
 * (delay, reverb, DJF filter, output gain) and sums the orbits into the master output.
 */
internal class OrbitSet(private val sampleRate: Int, private val executor: IrExecutor) {
    private val orbits = arrayOfNulls<Orbit>(MAX_ORBITS)
    private var count = 0

    /** The `duck` targets that were reported missing already, so that a kick pattern does not repeat the message every beat. */
    private val missingReported = DoubleArray(MISSING_REPORTS)
    private var missingCount = 0

    /** The orbit numbered [number] if it exists already, else null. Never allocates. */
    fun existing(number: Double): Orbit? {
        for (i in 0 until count) if (orbits[i]!!.number == number) return orbits[i]
        return null
    }

    /**
     * The orbit numbered [number], created if need be with the output [channels] (one-based, null for the default) or, when [multi] (multi-channel
     * orbits) is on, the pair of its own. Null when [MAX_ORBITS] orbits exist already. Creating an orbit allocates; once it exists, asking
     * for it ([existing]) does not.
     */
    fun orbit(number: Double, channels: IntArray?, multi: Boolean): Orbit? {
        existing(number)?.let { return it }
        if (count == MAX_ORBITS) return null
        val orbit = Orbit(number, mapOutputChannels(channels, number, multi), sampleRate, executor)
        orbits[count++] = orbit
        return orbit
    }

    /**
     * Starts the ducking of the orbits [targets] at frame [startFrame] (`duck` of superdoughoutput.mjs:200-219): every array is indexed per target,
     * a missing entry falls back to element 0, the attack is at least 2 ms. [nowFrame] is the engine's current frame. A target that does not exist is skipped (the
     * first time one is, it is reported).
     */
    fun duck(targets: DoubleArray, nowFrame: Long, startFrame: Long, onset: DoubleArray, attack: DoubleArray, depth: DoubleArray) {
        for (i in targets.indices) {
            val orbit = existing(targets[i])
            if (orbit == null) {
                reportMissing(targets[i])
                continue
            }
            val onsetValue = element(onset, i, 0.0)
            val attackValue = Math.max(element(attack, i, DEFAULT_ATTACK), MIN_ATTACK)
            val depthValue = element(depth, i, 1.0)
            orbit.duck(nowFrame, startFrame, onsetValue, attackValue, depthValue)
        }
    }

    /** `array[i] ?? array[0]` of the original, and [default] when the list is empty (`duckonset: []`). */
    private fun element(array: DoubleArray, i: Int, default: Double): Double = when {
        i < array.size -> array[i]
        array.isNotEmpty() -> array[0]
        else -> default
    }

    private fun reportMissing(target: Double) {
        for (i in 0 until missingCount) if (missingReported[i] == target) return
        if (missingCount < MISSING_REPORTS) missingReported[missingCount++] = target
        logger("duck target orbit ${if (target == Math.rint(target) && Math.abs(target) < 1e15) target.toLong().toString() else target.toString()} does not exist", "error", null)
    }

    /** Starts a block: empties the sums of every orbit. */
    fun beginBlock(n: Int) {
        for (i in 0 until count) orbits[i]!!.beginBlock(n)
    }

    /**
     * Finishes a block: lets every orbit add its effects, sums the orbits into the master output (overwriting
     * `out[2 * outFrame until 2 * (outFrame + n)]`) with the per-frame [stopGain] applied, and clamps it.
     */
    fun render(out: FloatArray, outFrame: Int, n: Int, ctx: BlockContext, stopGain: FloatArray) {
        for (i in 0 until count) orbits[i]!!.finishBlock(ctx)
        MasterOutput.mix(orbits, count, out, outFrame, n, stopGain)
    }

    /** Empties every orbit's tails; the orbits themselves stay. */
    fun clear() {
        for (i in 0 until count) orbits[i]!!.clear()
    }

    private companion object {
        const val MISSING_REPORTS = 8
        const val MIN_ATTACK = 0.002
        const val DEFAULT_ATTACK = 0.1
    }
}
