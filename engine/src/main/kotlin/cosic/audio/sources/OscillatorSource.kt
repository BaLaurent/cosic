package cosic.audio.sources

import cosic.audio.BLOCK_FRAMES
import cosic.pattern.CosicError
import cosic.pattern.Undefined
import cosic.pattern.jsToNumber
import cosic.pattern.jsTruthy

/**
 * The sounds `triangle` (`tri`), `square` (`sqr`), `sawtooth` (`saw`), `sine` (`sin`), `user` and `one` of superdough (`registerSynthSounds`, synth.mjs:23-82 and
 * `getOscillator`, :503-567): a Web Audio oscillator, then a gain of 0.3, then the amplitude envelope.
 *
 * - **Which wave.** `partials` (or, when it is not set, `n`: a list of magnitudes or a count) selects an additive wave ([additiveWave]); without it the oscillator is
 *   the native one, [PeriodicWave.basic]. `sine` ignores partials and plays its own wave, `user` without partials falls back to the triangle, and `one` is the constant 1
 *   (a `ConstantSourceNode`, the carrier of modulation).
 * - **Pitch.** `frequencyFromValue` (default note 36, `note 0` counts as unset), then [pitch] (vibrato, pitch envelope: cents) and [fm] (Hz) from the [PitchBus].
 * - **`noise`.** When the control is truthy the output is `osc * wetFade(noise) + pink * wetFade(1 - noise)`, the pink noise being the cached loop played from its
 *   first frame; the control is not clamped.
 * - **Level and envelope.** Everything times 0.3 (a `GainNode`), times `getParamADSR(0..1)` with the defaults `[0.001, 0.05, 0.6, 0.01]` for an envelope the pattern
 *   leaves alone, held for [SourceSpec.durationSec] and released over `release`. The source stops 10 ms after that, at `t + duration + release + 0.01`.
 *
 * The arithmetic is float32, as Web Audio's: the output of the oscillator is rounded, then multiplied by the gain, then by the envelope. Mono.
 * [noiseBuffer] replaces the cached pink noise loop (for tests).
 */
internal class OscillatorSource(
    spec: SourceSpec,
    name: String,
    private val pitch: PitchModulator?,
    private val fm: FrequencyModulator?,
    noiseBuffer: FloatArray? = null,
) : BlockSource() {
    private val sampleRate = spec.sampleRate
    private val durationSec = if (spec.durationSec.isNaN()) 0.0 else spec.durationSec
    private val envelope = SourceEnvelope(sampleRate, spec.controls, 1.0)

    /** Null for `one`. */
    private val oscillator: PeriodicOscillator?
    private val hz: Double
    private val bus: PitchBus?

    private val noise: LoopedNoise?
    private val dryGain: Float
    private val wetGain: Float

    private val block = FloatArray(BLOCK_FRAMES)
    private val noiseBlock: FloatArray?
    private val gains = FloatArray(BLOCK_FRAMES)

    override val channels: Int get() = 1

    init {
        val c = spec.controls
        var type = canonicalWave(name)
        val partials = c["partials"].let { if (it == null || it === Undefined) c["n"] else it }.let { if (it === Undefined) null else it }
        if (type == "user" && !jsTruthy(partials)) type = "triangle"
        if (type == "one") {
            oscillator = null
            hz = 0.0
            bus = null
            noise = null
            noiseBlock = null
            dryGain = 1f
            wetGain = 0f
        } else {
            hz = asParam(frequencyFromValue(c))
            val stock = !jsTruthy(partials) || (partials is List<*> && partials.isEmpty()) || type == "sine"
            val wave = if (stock) {
                PeriodicWave.basic(basicWave(type), sampleRate)
            } else {
                additiveWave(if (type == "user") null else basicWave(type), partialsOf(partials), c.list("phases"), sampleRate, type == "user")
            }
            oscillator = PeriodicOscillator(wave)
            bus = if (pitch != null || fm != null) PitchBus(BLOCK_FRAMES) else null
            val mix = c["noise"]
            if (jsTruthy(mix)) {
                val amount = jsToNumber(mix)
                noise = LoopedNoise(noiseBuffer ?: NoiseBuffers.get(NoiseType.PINK, sampleRate))
                noiseBlock = FloatArray(BLOCK_FRAMES)
                dryGain = wetFade(amount).toFloat()
                wetGain = wetFade(1.0 - amount).toFloat()
            } else {
                noise = null
                noiseBlock = null
                dryGain = 1f
                wetGain = 0f
            }
        }
    }

    override fun start(startFrame: Long) {
        frame = startFrame
        endFrame = frameAtOrAfter(envelope.schedule(startFrame.toDouble() / sampleRate, durationSec), sampleRate)
        oscillator?.reset()
        noise?.reset()
        (pitch as? PitchModulation)?.restart()
    }

    override fun renderBlock(left: FloatArray, right: FloatArray, offset: Int, m: Int) {
        val block = this.block
        val oscillator = this.oscillator
        if (oscillator == null) {
            java.util.Arrays.fill(block, 0, m, 1f)
        } else {
            val bus = this.bus
            if (bus != null) {
                bus.clear(m)
                pitch?.add(bus, m)
                fm?.add(bus, m)
            }
            oscillator.render(block, m, hz, bus)
            val noise = this.noise
            if (noise != null) {
                val noiseBlock = this.noiseBlock!!
                noise.render(noiseBlock, m)
                val dry = dryGain
                val wet = wetGain
                for (i in 0 until m) block[i] = block[i] * dry + noiseBlock[i] * wet
            }
        }
        envelope.fill(gains, frame, m)
        for (i in 0 until m) left[offset + i] = block[i] * SOURCE_GAIN * gains[i]
    }

    private companion object {
        /** The registered name of a wave: `tri`, `sqr`, `saw` and `sin` are aliases (synth.mjs:25-30). */
        fun canonicalWave(name: String): String = when (name) {
            "triangle", "tri" -> "triangle"
            "square", "sqr" -> "square"
            "sawtooth", "saw" -> "sawtooth"
            "sine", "sin" -> "sine"
            "user", "one" -> name
            else -> throw CosicError("'$name' is not an oscillator sound")
        }

        fun basicWave(type: String): BasicWave = when (type) {
            "triangle" -> BasicWave.TRIANGLE
            "square" -> BasicWave.SQUARE
            "sawtooth" -> BasicWave.SAWTOOTH
            else -> BasicWave.SINE
        }

        /** The magnitudes of `waveformN(partials, ...)`: the list itself, or a count of 1s (`new Float32Array(count).fill(1)`; a negative count is a RangeError). */
        fun partialsOf(partials: Any?): DoubleArray {
            if (partials is List<*>) return DoubleArray(partials.size) { jsToNumber(partials[it]) }
            val count = jsToNumber(partials)
            val length = if (count.isNaN()) 0.0 else if (count < 0.0) -Math.floor(-count) else Math.floor(count)
            if (length < 0.0) throw CosicError("RangeError: Invalid typed array length: ${length.toLong()}")
            // more than size / 2 partials make no difference to a wave (the extra ones are dropped), and the cap keeps a huge count from allocating
            return DoubleArray(Math.min(length, MAX_COUNT).toInt()) { 1.0 }
        }

        const val MAX_COUNT = 16384.0
    }
}
