package cosic.audio

/**
 * Stage 8, the vowel filter: five parallel band-passes of a vowel's formants (vowel.mjs).
 *
 * Stub of Task 3.1: never in the chain. Task 3.7 implements it.
 */
internal class VowelStage(@Suppress("UNUSED_PARAMETER") sampleRate: Int) : ChainStage {
    override fun configure(p: ParamSet, timing: VoiceTiming, isMain: Boolean): Boolean = false

    override fun process(b: AudioBlock, ctx: BlockContext) {}

    override fun reset() {}
}
