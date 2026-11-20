package cosic.audio

/**
 * Stage 12, `distort` with its nine algorithms (worklets.mjs:429-463, helpers.mjs:509-599).
 *
 * Stub of Task 3.1: never in the chain. Task 3.8 implements it.
 */
internal class DistortStage(@Suppress("UNUSED_PARAMETER") sampleRate: Int) : ChainStage {
    override fun configure(p: ParamSet, timing: VoiceTiming, isMain: Boolean): Boolean = false

    override fun process(b: AudioBlock, ctx: BlockContext) {}

    override fun reset() {}
}
