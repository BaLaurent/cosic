package cosic.audio

/**
 * What the effect stages that follow a worklet (coarse, crush, shape, distort, tremolo's gain) or a Web Audio node (the compressor) share: those
 * read their AudioParams once per 128-frame render quantum, aligned to the absolute frame counter, while the [Mixer] hands a voice blocks of any
 * length and alignment. [forEachPiece] cuts a block at the quantum boundaries; [valueAt] reads a parameter the way a worklet does (the value at
 * the first frame of the quantum, as a float32).
 */
internal object StageQuanta {
    /**
     * Calls [piece] for every part `[from, to)` of the block of [ctx] that lies inside one render quantum, with the block index [quantumStart]
     * of that quantum's first frame. A block that starts in the middle of a quantum has a first piece whose quantum began before the block:
     * [quantumStart] is then negative (see [valueAt]).
     */
    inline fun forEachPiece(ctx: BlockContext, piece: (from: Int, to: Int, quantumStart: Int) -> Unit) {
        val n = ctx.n
        var from = 0
        while (from < n) {
            val phase = ((ctx.startFrame + from) and (QUANTUM - 1).toLong()).toInt()
            val to = Math.min(n, from + (QUANTUM - phase))
            piece(from, to, from - phase)
            from = to
        }
    }

    /**
     * The value of [param] at block index [quantumStart] (negative: before the block, in the quantum it started in), rounded to float32 like the
     * AudioParam values a worklet or a node is handed. Automation (ramps, envelopes) is evaluated at the quantum's own first frame, so the result does
     * not depend on where the blocks were cut. The output of modulators connected to the parameter only exists for the current block: a quantum that
     * began before the block reads them at the block's first frame, up to one quantum late.
     */
    fun valueAt(param: AParam, ctx: BlockContext, quantumStart: Int): Double =
        (if (param.isConstant) param.constantValue else if (param.hasInputs) param.sampleAt(ctx, Math.max(0, quantumStart)) else param.sampleAt(ctx, quantumStart))
            .toFloat().toDouble()

    /**
     * Replaces NaN and infinities in frames `[from, to)` of [io] by 0. The worklets that can produce them from hostile parameters (a crush of
     * 1e9, a negative shape) pass them on and a browser's mixer carries them along; here they must not reach an orbit's feedback loops.
     */
    fun finiteOrZero(io: FloatArray, from: Int, to: Int) {
        for (i in from until to) {
            val x = io[i]
            if (x - x != 0f) io[i] = 0f
        }
    }
}
