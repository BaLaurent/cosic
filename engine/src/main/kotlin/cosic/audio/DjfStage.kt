package cosic.audio

import cosic.audio.dsp.DjfFilter

private const val MAX_PARAM = 3.4e38

/**
 * The `djf-processor` an orbit gets when its first voice sets `djf` (`Orbit.getDjf`, superdoughoutput.mjs:41-51): inserted between the orbit's summing node and its output,
 * so the delay and the reverb pass through it as well, and kept for the life of the orbit. Its `value` AudioParam is the knob (default 0.5: untouched): every voice that
 * sets `djf` schedules its value at its own start time, so it "stays set until another event changes it". The filter is [DjfFilter]; the worklet reads `value` at
 * the first frame of every render quantum, so a block is cut at the quanta (of the absolute frame counter) and each piece uses the value from its quantum's start.
 */
internal class DjfStage(private val sampleRate: Int) {
    /** The `value` AudioParam. */
    val value = AParam(0.5, -MAX_PARAM, MAX_PARAM, 4)

    private val filter = DjfFilter(sampleRate)

    /** Forgets the filter's memory; the knob stays where it is. */
    fun reset() {
        filter.reset()
    }

    /** Filters the first [n] frames of [left] and [right] in place; [ctx] places the block in time. */
    fun process(left: FloatArray, right: FloatArray, n: Int, ctx: BlockContext) {
        val constant = value.isConstant
        val constantValue = value.constantValue
        var from = 0
        while (from < n) {
            val frame = ctx.startFrame + from
            val inQuantum = (frame and (QUANTUM - 1).toLong()).toInt()
            val to = Math.min(n, from + (QUANTUM - inQuantum))
            val knob = when {
                constant -> constantValue
                value.hasInputs -> value.sampleAt(ctx, from)
                else -> value.valueAt((frame - inQuantum).toDouble() / sampleRate)
            }
            filter.process(left, right, from, to, knob)
            from = to
        }
    }
}
