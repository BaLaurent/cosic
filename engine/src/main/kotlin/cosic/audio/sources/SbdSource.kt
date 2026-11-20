package cosic.audio.sources

import cosic.audio.AParam
import cosic.audio.BLOCK_FRAMES
import java.util.concurrent.ConcurrentHashMap

/**
 * The sound `sbd` (synth.mjs:84-151), a synthesised bass drum: a triangle oscillator whose pitch falls exponentially from `penv` semitones (36) above the note to the
 * note over `pdecay` (0.5 s), through a saturating wave shaper (`tanh(2x)` as a table of one entry per frame of a second, read by linear interpolation like the
 * `WaveShaperNode`'s), times an exponential decay that starts 20 ms after the note and ends `decay` (0.5 s) later, plus a burst of brown noise (level 1.2) that dies away
 * within 25 ms. The note is `note` (default 29, 43.65 Hz). The sum fades out over the last 10 ms before `end`, which is `decay + 0.01` after the start, or, when the
 * pattern sets `clip`, no later than `clip * duration` after it (the duration already holds `clip`, which therefore counts twice, as it does in Strudel).
 *
 * There is no ADSR, vibrato or FM: the amplitude control `decay` and `release` do not reach the envelope. Mono. [noiseBuffer] replaces the cached brown noise loop (for tests).
 */
internal class SbdSource(spec: SourceSpec, noiseBuffer: FloatArray? = null) : BlockSource() {
    private val sampleRate = spec.sampleRate
    private val durationSec = if (spec.durationSec.isNaN()) 0.0 else spec.durationSec
    private val hz: Double
    private val decay: Double
    private val pitchDecay: Double
    private val pitchCents: Double
    private val clip: Double?

    private val oscillator = PeriodicOscillator(PeriodicWave.basic(BasicWave.TRIANGLE, sampleRate))
    private val noise = LoopedNoise(noiseBuffer ?: NoiseBuffers.get(NoiseType.BROWN, sampleRate))
    private val curve = saturationCurve(sampleRate)
    private val bus = PitchBus(BLOCK_FRAMES)

    // the AudioParams the sound automates
    private val detune = AParam(0.0, -LIMIT, LIMIT, 8)
    private val ampGain = AParam(1.0, -LIMIT, LIMIT, 8)
    private val noiseGain = AParam(1.0, -LIMIT, LIMIT, 8)
    private val mixGain = AParam(1.0, -LIMIT, LIMIT, 8)

    private val block = FloatArray(BLOCK_FRAMES)
    private val noiseBlock = FloatArray(BLOCK_FRAMES)
    private val detuneBlock = FloatArray(BLOCK_FRAMES)
    private val ampBlock = FloatArray(BLOCK_FRAMES)
    private val noiseGainBlock = FloatArray(BLOCK_FRAMES)
    private val mixBlock = FloatArray(BLOCK_FRAMES)

    override val channels: Int get() = 1

    init {
        val c = spec.controls
        hz = asParam(frequencyFromValue(c, 29.0))
        decay = c.number("decay") ?: DEFAULT_DECAY
        pitchDecay = c.number("pdecay") ?: DEFAULT_PITCH_DECAY
        pitchCents = (c.number("penv") ?: DEFAULT_PITCH_SEMITONES) * 100.0
        clip = c.number("clip")
    }

    override fun start(startFrame: Long) {
        val t = startFrame.toDouble() / sampleRate
        frame = startFrame
        detune.reset(0.0)
        detune.setValueAtTime(pitchCents, 0.0)
        detune.setValueAtTime(pitchCents, t)
        detune.exponentialRampToValueAtTime(FLOOR, t + pitchDecay, 0.0)
        ampGain.reset(1.0)
        ampGain.setValueAtTime(1.0, t + ATTACK_HOLD)
        ampGain.exponentialRampToValueAtTime(FLOOR, t + ATTACK_HOLD + decay, 0.0)
        noiseGain.reset(1.0)
        noiseGain.setValueAtTime(NOISE_LEVEL, t)
        noiseGain.exponentialRampToValueAtTime(FLOOR, t + NOISE_DECAY, 0.0)
        val holdEnd = t + decay
        var end = holdEnd + 0.01
        val clip = this.clip
        if (clip != null) end = Math.min(t + clip * durationSec, end)
        mixGain.reset(1.0)
        // prevent clicking
        mixGain.setValueAtTime(1.0, end - 0.01)
        mixGain.linearRampToValueAtTime(0.0, end, 0.0)
        endFrame = frameAtOrAfter(end, sampleRate)
        oscillator.reset()
        noise.reset()
    }

    override fun renderBlock(left: FloatArray, right: FloatArray, offset: Int, m: Int) {
        detune.timeline.fillBlock(detuneBlock, frame, sampleRate, m, detune.base)
        bus.clear(m)
        for (i in 0 until m) bus.detuneCents[i] = detuneBlock[i].toDouble()
        bus.detuneActive = true
        oscillator.render(block, m, hz, bus)
        noise.render(noiseBlock, m)
        ampGain.timeline.fillBlock(ampBlock, frame, sampleRate, m, ampGain.base)
        noiseGain.timeline.fillBlock(noiseGainBlock, frame, sampleRate, m, noiseGain.base)
        mixGain.timeline.fillBlock(mixBlock, frame, sampleRate, m, mixGain.base)
        for (i in 0 until m) {
            val shaped = shape(block[i])
            left[offset + i] = (shaped * ampBlock[i] + noiseBlock[i] * noiseGainBlock[i]) * mixBlock[i]
        }
    }

    /** The `WaveShaperNode`: [curve] spans the input range -1..1 with `size` entries, read with linear interpolation, and holds its end values beyond. */
    private fun shape(x: Float): Float {
        val last = curve.size - 1
        val virtual = 0.5 * (x.toDouble() + 1.0) * last
        if (!(virtual >= 0.0)) return curve[0]
        if (virtual >= last) return curve[last]
        val index = virtual.toInt()
        val fraction = (virtual - index).toFloat()
        return (1f - fraction) * curve[index] + fraction * curve[index + 1]
    }

    private companion object {
        const val LIMIT = 3.4028234663852886e38
        const val DEFAULT_DECAY = 0.5
        const val DEFAULT_PITCH_DECAY = 0.5
        const val DEFAULT_PITCH_SEMITONES = 36.0
        const val ATTACK_HOLD = 0.02
        const val NOISE_LEVEL = 1.2
        const val NOISE_DECAY = 0.025
        const val FLOOR = 0.001

        private val curves = ConcurrentHashMap<Int, FloatArray>()

        /** `makeSaturationCurve(2, sampleRate)`: `tanh(2 x)` for `x = 2 i / n - 1`, one entry per frame of a second. Shared by the sources of a sample rate. */
        fun saturationCurve(sampleRate: Int): FloatArray = curves.computeIfAbsent(sampleRate) { n ->
            FloatArray(n) { i -> Math.tanh(((i * 2).toDouble() / n - 1.0) * 2.0).toFloat() }
        }
    }
}
