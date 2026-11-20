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
    private val sampleRate = spec.sampleRate
    private val generator = ZzfxGenerator(spec.sampleRate, SourceRng(spec.seed), zzfxParams(soundName, spec.controls, spec.durationSec))

    override val channels: Int get() = 1

    override fun start(startFrame: Long) {
        frame = startFrame
        endFrame = startFrame + generator.frames
        endSeconds = endFrame.toDouble() / sampleRate
    }

    /** The node of a ZZFX sound has no gain to ramp (superdough.mjs steals with `node.gain` when there is one): a stolen one just stops 0.25 s later. */
    override fun steal(tNew: Double) {
        val stop = Math.round((tNew + STEAL_SECONDS) * sampleRate)
        if (stop < endFrame) {
            endFrame = stop
            endSeconds = tNew + STEAL_SECONDS
        }
    }

    override fun renderBlock(left: FloatArray, right: FloatArray, offset: Int, m: Int) {
        generator.fill(left, offset, m)
    }

    private companion object {
        const val STEAL_SECONDS = 0.25
    }
}
