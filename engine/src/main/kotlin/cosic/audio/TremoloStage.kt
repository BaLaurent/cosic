package cosic.audio

/**
 * Stage 13, tremolo: a gain whose value follows an LFO (superdough.mjs:818-849). Its LFO is a [LfoOsc].
 *
 * Stub of Task 3.1: never in the chain. Task 3.8 implements it.
 */
internal class TremoloStage(@Suppress("UNUSED_PARAMETER") sampleRate: Int) : ChainStage {
    override fun configure(p: ParamSet, timing: VoiceTiming, isMain: Boolean): Boolean = false

    override fun process(b: AudioBlock, ctx: BlockContext) {}

    override fun reset() {}
}
