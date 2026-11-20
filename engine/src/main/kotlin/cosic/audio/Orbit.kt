package cosic.audio

import cosic.SampleData

/**
 * Runs background jobs of the audio engine (generating a reverb impulse response); `submit` must return at once. The real-time engine
 * hands in a single worker thread, offline rendering and tests [INLINE].
 */
internal fun interface IrExecutor {
    fun submit(job: Runnable)

    companion object {
        /** Runs the job on the calling thread. */
        val INLINE = IrExecutor { it.run() }
    }
}

/**
 * What a voice asks of its orbit's reverb (`getReverb(roomsize, roomfade, roomlp, roomdim, roomIR, irspeed, irbegin)`, superdough.mjs:959-977). A NaN
 * number or a null name is "not set". One instance lives in each voice and is refilled at every start.
 */
internal class ReverbParams {
    var duration = Double.NaN
    var fade = Double.NaN
    var lp = Double.NaN
    var dim = Double.NaN
    var irName: String? = null
    var irIndex = 1.0
    var irSample: SampleData? = null
    var irspeed = Double.NaN
    var irbegin = Double.NaN
}

/**
 * An orbit: the effect bus voices of the same `orbit` number mix into (superdoughoutput.mjs). Voices add their direct sound ([mixDry]) and
 * their delay and reverb sends ([sendDelay], [sendReverb]) to it; at the end of a block the orbit adds its own effects and is summed
 * into the master output.
 *
 * Stub of Task 3.1: only the direct path exists, the sends and the settings are dropped. Task 3.9 (orbit buses) implements delay,
 * reverb, DJF filter, ducking and the rest.
 */
internal class Orbit(val number: Double, val channels: IntArray, @Suppress("UNUSED_PARAMETER") sampleRate: Int) {
    /** The stereo sum of everything that reaches the orbit in the current block. */
    val summing = StereoBuffer(BLOCK_FRAMES)

    /** Starts a block: empties the sums. */
    fun beginBlock(n: Int) {
        summing.clear(n)
    }

    /** Adds the voice's block, scaled by the `dry` gain [g], to the orbit; a mono block reaches both channels. */
    fun mixDry(b: AudioBlock, g: AParam, ctx: BlockContext) {
        val n = ctx.n
        val right = if (b.channels == 2) b.r else b.l
        if (g.isConstant) {
            val gain = g.constantValue.toFloat()
            for (i in 0 until n) {
                summing.left[i] += b.l[i] * gain
                summing.right[i] += right[i] * gain
            }
            return
        }
        val gains = ctx.borrow()
        g.fill(gains, ctx)
        for (i in 0 until n) {
            summing.left[i] += b.l[i] * gains[i]
            summing.right[i] += right[i] * gains[i]
        }
        ctx.release()
    }

    /** Adds the voice's block, scaled by the `delay` send level [send], to the delay. */
    @Suppress("UNUSED_PARAMETER")
    fun sendDelay(b: AudioBlock, send: AParam, ctx: BlockContext) {}

    /** Adds the voice's block, scaled by the `room` send level [send], to the reverb. */
    @Suppress("UNUSED_PARAMETER")
    fun sendReverb(b: AudioBlock, send: AParam, ctx: BlockContext) {}

    /** `getDelay(delayTime, feedback, t)`: creates the orbit's delay on first use and sets its time and feedback from time [atTime] on. */
    @Suppress("UNUSED_PARAMETER")
    fun configureDelay(delayTimeSec: Double, feedback: Double, atTime: Double) {}

    /** `getReverb(...)`: creates the orbit's reverb on first use and regenerates its impulse response when [r] asks for another room. */
    @Suppress("UNUSED_PARAMETER")
    fun configureReverb(r: ReverbParams, atTime: Double) {}

    /** `getDjf(value, t)`: sets the orbit's DJF filter from time [atTime] on. */
    @Suppress("UNUSED_PARAMETER")
    fun setDjf(v: Double, atTime: Double) {}

    /** Ducks the orbit's output from frame [tFrame] on: down to `1 - sqrt(depth)` over [onset] seconds, back up over [attack]. */
    @Suppress("UNUSED_PARAMETER")
    fun duck(tFrame: Long, onset: Double, attack: Double, depth: Double) {}

    /** Registers the orbit's parameters that modulators may target (`delay.delayTime`, `delay.feedback`, `djf.value`) with the voice's main registry. */
    @Suppress("UNUSED_PARAMETER")
    fun registerParams(reg: NodeRegistry) {}

    /** Empties every tail (delay lines, reverb, filter state) and resets the output gain. */
    fun clear() {}
}
