package cosic.audio

/**
 * Stage 3, the transient shaper (worklets.mjs:1417-1494).
 *
 * Stub of Task 3.1: never in the chain. Task 3.8 implements it.
 */
internal class TransientStage(@Suppress("UNUSED_PARAMETER") sampleRate: Int) : ChainStage {
    override fun configure(p: ParamSet, timing: VoiceTiming, isMain: Boolean): Boolean = false

    override fun process(b: AudioBlock, ctx: BlockContext) {}

    override fun reset() {}
}
