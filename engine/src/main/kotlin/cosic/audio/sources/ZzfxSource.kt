package cosic.audio.sources

/**
 * A ZZFX sound (`zzfx` and `z_*` of zzfx.mjs): the buffer of [ZzfxGenerator], played once from the start of the event, mono, with no envelope or any other control of its own
 * (the attack, decay, sustain and release the controls name are inputs of the generator, and the voice's gain and effects come after). The source is finished at the last
 * sample of the buffer, which is a function of the parameters, not of the duration of the event.
 *
 * The buffer is not computed ahead: each block asks the generator for its samples, so the cost is spread over the sound and only an echo (`zdelay`) allocates, once, when
 * the source is made. [soundName] is the registered name (see [zzfxParams]); the random start frequency (`zrand`) is drawn from the seed of [spec].
 */
internal class ZzfxSource(spec: SourceSpec, soundName: String) : BlockSource() {
    private val generator = ZzfxGenerator(spec.sampleRate, SourceRng(spec.seed), zzfxParams(soundName, spec.controls, spec.durationSec))

    override val channels: Int get() = 1

    override fun start(startFrame: Long) {
        frame = startFrame
        endFrame = startFrame + generator.frames
    }

    override fun renderBlock(left: FloatArray, right: FloatArray, offset: Int, m: Int) {
        generator.fill(left, offset, m)
    }
}
