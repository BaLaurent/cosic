package cosic.audio

/**
 * Stage 14, the `DynamicsCompressorNode` (helpers.mjs:163-176; Blink's dynamics_compressor.cc).
 *
 * Stub of Task 3.1: never in the chain. Task 3.8 implements it.
 */
internal class CompressorStage(@Suppress("UNUSED_PARAMETER") sampleRate: Int) : ChainStage {
    override fun configure(p: ParamSet, timing: VoiceTiming, isMain: Boolean): Boolean = false

    override fun process(b: AudioBlock, ctx: BlockContext) {}

    override fun reset() {}
}
