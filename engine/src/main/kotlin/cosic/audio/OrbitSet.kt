package cosic.audio

/**
 * All orbits of the mixer, created on the first voice that uses them (superdoughoutput.mjs `getOrbit`: an orbit keeps the output
 * channels it was created with). The [Mixer] calls [beginBlock], lets the voices add to their orbits, and then [render].
 *
 * Stub of Task 3.1: orbits only sum. Task 3.9 (orbit buses) implements the effects, ducking and the background reverb generation on
 * [executor].
 */
internal class OrbitSet(private val sampleRate: Int, @Suppress("unused") private val executor: IrExecutor) {
    private val orbits = arrayOfNulls<Orbit>(MAX_ORBITS)
    private var count = 0

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
        val orbit = Orbit(number, mapOutputChannels(channels, number, multi), sampleRate)
        orbits[count++] = orbit
        return orbit
    }

    /**
     * Starts the ducking of the orbits [targets] at frame [startFrame] (`duck`, superdoughoutput.mjs:200-219): every array is indexed per target,
     * a missing entry falls back to element 0. [nowFrame] is the engine's current frame. A target that does not exist is skipped.
     */
    @Suppress("UNUSED_PARAMETER")
    fun duck(targets: DoubleArray, nowFrame: Long, startFrame: Long, onset: DoubleArray, attack: DoubleArray, depth: DoubleArray) {}

    /** Starts a block: empties the sums of every orbit. */
    fun beginBlock(n: Int) {
        for (i in 0 until count) orbits[i]!!.beginBlock(n)
    }

    /**
     * Finishes a block: lets every orbit add its effects, sums the orbits into the master output (overwriting
     * `out[2 * outFrame until 2 * (outFrame + n)]`) with the per-frame [stopGain] applied, and clamps it.
     */
    fun render(out: FloatArray, outFrame: Int, n: Int, @Suppress("UNUSED_PARAMETER") ctx: BlockContext, stopGain: FloatArray) {
        MasterOutput.mix(orbits, count, out, outFrame, n, stopGain)
    }

    /** Empties every orbit's tails; the orbits themselves stay. */
    fun clear() {
        for (i in 0 until count) orbits[i]!!.clear()
    }
}
