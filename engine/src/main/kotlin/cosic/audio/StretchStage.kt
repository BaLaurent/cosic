package cosic.audio

/**
 * Stage 2, the phase vocoder behind `stretch` (worklets.mjs:595-748).
 *
 * Stub of Task 3.1: never in the chain. Task 3.10 implements it.
 */
internal class StretchStage(@Suppress("UNUSED_PARAMETER") sampleRate: Int) : ChainStage {
    override fun configure(p: ParamSet, timing: VoiceTiming, isMain: Boolean): Boolean = false

    override fun process(b: AudioBlock, ctx: BlockContext) {}

    override fun reset() {}
}
