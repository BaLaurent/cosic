package cosic.audio

/**
 * Stage 17, the delay of an `FX(...)` element (superdough.mjs:886-902): `dry * (fx.dry ?? 1) + wet * delay`. Never part of the main chain.
 *
 * Stub of Task 3.1: never in the chain. Task 3.9 implements it, together with [InChainReverb].
 */
internal class InChainDelay(@Suppress("UNUSED_PARAMETER") sampleRate: Int) : ChainStage {
    override fun configure(p: ParamSet, timing: VoiceTiming, isMain: Boolean): Boolean = false

    override fun process(b: AudioBlock, ctx: BlockContext) {}

    override fun reset() {}
}

/** Stage 18, the reverb of an `FX(...)` element (superdough.mjs:903-936). Stub of Task 3.1; see [InChainDelay]. */
internal class InChainReverb(@Suppress("UNUSED_PARAMETER") sampleRate: Int) : ChainStage {
    override fun configure(p: ParamSet, timing: VoiceTiming, isMain: Boolean): Boolean = false

    override fun process(b: AudioBlock, ctx: BlockContext) {}

    override fun reset() {}
}
