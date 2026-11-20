package cosic.audio.sources

import cosic.audio.BLOCK_FRAMES
import cosic.pattern.Undefined
import cosic.pattern.jsToNumber

/**
 * The sounds `pink`, `white`, `brown` and `crackle` of superdough (synth.mjs:407-452): the looped 2 second noise buffer ([NoiseBuffers], started at its first frame by
 * every note), times a gain of 0.3, times the amplitude envelope with the defaults `[0.001, 0.05, 0.6, 0.01]`; the source stops at `t + duration + release + 0.01`.
 *
 * White, pink and brown are the same cached loop for every note. Crackle is built for the note, from `density` (the probability of a click is `density * 0.01` per
 * frame; 0.02 when the pattern sets none, which is `getNoiseOscillator`'s default) and the [SourceSpec.seed]. Mono, float32 arithmetic as in Web Audio.
 * [buffer] replaces the loop (for tests).
 */
internal class NoiseSource(spec: SourceSpec, type: NoiseType, buffer: FloatArray? = null) : BlockSource() {
    private val sampleRate = spec.sampleRate
    private val durationSec = if (spec.durationSec.isNaN()) 0.0 else spec.durationSec
    private val envelope = SourceEnvelope(sampleRate, spec.controls, 1.0)
    private val noise: LoopedNoise
    private val block = FloatArray(BLOCK_FRAMES)
    private val gains = FloatArray(BLOCK_FRAMES)

    override val channels: Int get() = 1

    init {
        val raw = spec.controls["density"]
        // `density = 0.02` is the default of an argument, which only `undefined` selects: any other value is read as a number
        val density = if (raw == null || raw === Undefined) NoiseBuffers.DEFAULT_DENSITY else jsToNumber(raw)
        noise = LoopedNoise(buffer ?: NoiseBuffers.get(type, sampleRate, density, spec.seed))
    }

    override fun start(startFrame: Long) {
        frame = startFrame
        endFrame = frameAtOrAfter(envelope.schedule(startFrame.toDouble() / sampleRate, durationSec), sampleRate)
        noise.reset()
    }

    override fun renderBlock(left: FloatArray, right: FloatArray, offset: Int, m: Int) {
        noise.render(block, m)
        envelope.fill(gains, frame, m)
        for (i in 0 until m) left[offset + i] = block[i] * SOURCE_GAIN * gains[i]
    }
}
