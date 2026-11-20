package cosic.audio

/**
 * Stage 16, the phaser: a notch filter whose detune follows an LFO (superdough.mjs:352-372, 871-885).
 *
 * Stub of Task 3.1: never in the chain. Task 3.8 implements it.
 */
internal class PhaserStage(@Suppress("UNUSED_PARAMETER") sampleRate: Int) : ChainStage {
    override fun configure(p: ParamSet, timing: VoiceTiming, isMain: Boolean): Boolean = false

    override fun process(b: AudioBlock, ctx: BlockContext) {}

    override fun reset() {}
}
