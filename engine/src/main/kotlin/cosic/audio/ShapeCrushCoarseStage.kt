package cosic.audio

/**
 * Stage 9, the `coarse` sample-and-hold (worklets.mjs:193-223).
 *
 * Stub of Task 3.1: never in the chain. Task 3.8 implements it, with [CrushStage] and [ShapeStage], on top of the kernels of Task 3.3.
 */
internal class CoarseStage(@Suppress("UNUSED_PARAMETER") sampleRate: Int) : ChainStage {
    override fun configure(p: ParamSet, timing: VoiceTiming, isMain: Boolean): Boolean = false

    override fun process(b: AudioBlock, ctx: BlockContext) {}

    override fun reset() {}
}

/** Stage 10, the bit crusher (worklets.mjs:225-257). Stub of Task 3.1; see [CoarseStage]. */
internal class CrushStage(@Suppress("UNUSED_PARAMETER") sampleRate: Int) : ChainStage {
    override fun configure(p: ParamSet, timing: VoiceTiming, isMain: Boolean): Boolean = false

    override fun process(b: AudioBlock, ctx: BlockContext) {}

    override fun reset() {}
}

/** Stage 11, the waveshaper (worklets.mjs:259-295). Stub of Task 3.1; see [CoarseStage]. */
internal class ShapeStage(@Suppress("UNUSED_PARAMETER") sampleRate: Int) : ChainStage {
    override fun configure(p: ParamSet, timing: VoiceTiming, isMain: Boolean): Boolean = false

    override fun process(b: AudioBlock, ctx: BlockContext) {}

    override fun reset() {}
}
