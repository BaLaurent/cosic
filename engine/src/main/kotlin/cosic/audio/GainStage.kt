package cosic.audio

private const val MAX_PARAM = 3.4e38

/**
 * Stage 4 of the chain, the `GainNode` that starts every effect chain (superdough.mjs:673-676, 615-633): its value is
 * `gain * velocity`. `gain` defaults to 0.8 (not 1), `velocity` to 1, and the gain curve (`applyGainCurve`) is the identity, so the
 * default level of a voice is 0.8 for the main chain and for every `FX(...)` element alike. The gain is a parameter other
 * modulators can target (`gain.gain`).
 */
internal class GainStage(@Suppress("UNUSED_PARAMETER") sampleRate: Int) : ChainStage {
    val gain = AParam(1.0, -MAX_PARAM, MAX_PARAM)

    override fun configure(p: ParamSet, timing: VoiceTiming, isMain: Boolean): Boolean {
        gain.reset(p.or(Param.gain, DEFAULT_GAIN) * p.or(Param.velocity, 1.0))
        return true
    }

    override fun process(b: AudioBlock, ctx: BlockContext) {
        if (gain.isConstant) {
            b.mulBy(gain.constantValue.toFloat(), ctx.n)
            return
        }
        val gains = ctx.borrow()
        gain.fill(gains, ctx)
        b.mulBy(gains, ctx.n)
        ctx.release()
    }

    override fun reset() {
        gain.reset(1.0)
    }

    override fun registerParams(reg: NodeRegistry) {
        reg.register("gain", "gain", gain)
    }

    companion object {
        /** `getDefaultValue('gain')`. */
        const val DEFAULT_GAIN = 0.8
    }
}

/**
 * The end of the chain (superdough.mjs:946-949, 995-1002): the `post` gain, `postgain` (default 1), which delay, reverb, bus and analyser
 * sends are taken after, and the `dry` gain of the direct path to the orbit (default 1; the direct path is `post` itself when `dry` is
 * not set). Not part of a [StageGroup]: a voice has one.
 */
internal class PostStage {
    val post = AParam(1.0, -MAX_PARAM, MAX_PARAM)
    val dry = AParam(1.0, -MAX_PARAM, MAX_PARAM)

    /** True when the pattern set `dry`. */
    var dryUsed = false
        private set

    fun configure(p: ParamSet) {
        post.reset(p.or(Param.postgain, 1.0))
        dryUsed = p.has(Param.dry)
        dry.reset(p.or(Param.dry, 1.0))
    }

    /** Applies the post gain. */
    fun process(b: AudioBlock, ctx: BlockContext) {
        if (post.isConstant) {
            val g = post.constantValue.toFloat()
            if (g != 1f) b.mulBy(g, ctx.n)
            return
        }
        val gains = ctx.borrow()
        post.fill(gains, ctx)
        b.mulBy(gains, ctx.n)
        ctx.release()
    }
}
