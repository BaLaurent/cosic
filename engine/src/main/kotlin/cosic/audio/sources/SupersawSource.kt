package cosic.audio.sources

import cosic.audio.BLOCK_FRAMES
import cosic.audio.frac

/**
 * The semitone offset of each voice of a supersaw (`getDetuner(unison, detune)`, worklets.mjs:44-51): [detuneSemitones] spread evenly over the voices, centred on the
 * note. Of `ceil(voices)` voices; one voice (and a [voices] below 2, whose spread is not defined) is not detuned.
 */
internal fun supersawOffsets(voices: Double, detuneSemitones: Double): DoubleArray {
    val count = Math.ceil(voices).toInt().coerceAtLeast(1)
    if (voices < 2.0) return DoubleArray(count)
    val scale = detuneSemitones / (voices - 1.0)
    val center = detuneSemitones * 0.5
    return DoubleArray(count) { it * scale - center }
}

/**
 * The gains of voice [voiceIndex] on the left and the right (the stereo spread of the `supersaw-oscillator` worklet): a [panSpread] of 0 puts every voice in the middle, 1
 * alternates the voices hard left and hard right; the gains are `sqrt(1 - p)` and `sqrt(p)` for `p = panSpread / 2 + 1/2`, swapped for every odd voice.
 */
internal fun supersawPan(voiceIndex: Int, panSpread: Double): Pair<Double, Double> {
    val p = panSpread * 0.5 + 0.5
    val left = Math.sqrt(1.0 - p)
    val right = Math.sqrt(p)
    return if (voiceIndex % 2 == 0) left to right else right to left
}

/**
 * The sound `supersaw` (synth.mjs:153-217, the `supersaw-oscillator` worklet of worklets.mjs:466-578): `unison` (5) band-limited sawtooth voices, spread over `detune`
 * semitones (`n` when `detune` is not set, else 0.18) and over the stereo field by `spread` (0.6; no spread for a single voice), each at a random start phase, summed to
 * a stereo signal. Each voice is `2 phase - 1 - polyBlep(phase, dt)`. The envelope peaks at `0.3 / sqrt(voices)` with the defaults `[0.001, 0.05, 0.6, 0.01]`.
 *
 * The pitch inputs come from the [PitchBus]: [pitch] moves the `detune` parameter (cents, the whole note), [fm] the `frequency` (Hz). Like every source worklet the
 * node only runs on whole render quanta, from the first one that starts after `begin` until the first one that starts at or after `end` ([WorkletGate]); it
 * accumulates into float32 buffers, one voice after the other. The start phases come from [phase] (default: the source's generator, seeded by [SourceSpec.seed]), drawn
 * in voice order at the first frame that sounds, as the worklet does. Stereo.
 */
internal class SupersawSource(
    spec: SourceSpec,
    private val pitch: PitchModulator?,
    private val fm: FrequencyModulator?,
    phase: RandomSource? = null,
) : BlockSource() {
    private val sampleRate = spec.sampleRate
    private val invSampleRate = 1.0 / spec.sampleRate
    private val durationSec = if (spec.durationSec.isNaN()) 0.0 else spec.durationSec
    private val hz: Double
    private val voices: Double
    private val voiceCount: Int
    private val gainLeft: Double
    private val gainRight: Double
    private val ratios: DoubleArray
    private val envelope: SourceEnvelope
    private val gate = WorkletGate(sampleRate)
    private val bus: PitchBus? = if (pitch != null || fm != null) PitchBus(BLOCK_FRAMES) else null
    private val gains = FloatArray(BLOCK_FRAMES)
    private val startPhase: RandomSource
    private val phases: DoubleArray
    private var phasesDrawn = false

    override val channels: Int get() = 2

    init {
        val c = spec.controls
        val unison = clampValue(c.number("unison") ?: DEFAULT_UNISON, 1.0, 100.0)
        val spread = c.number("spread") ?: DEFAULT_SPREAD
        val detune = c.number("detune") ?: c.number("n") ?: DEFAULT_DETUNE
        hz = asParam(frequencyFromValue(c))
        // the worklet's parameters: float32 values
        voices = asParam(unison)
        voiceCount = Math.ceil(voices).toInt()
        val panSpread = asParam(if (unison > 1.0) clampValue(spread, 0.0, 1.0) else 0.0)
        val pan = supersawPan(0, panSpread)
        gainLeft = pan.first
        gainRight = pan.second
        ratios = supersawOffsets(voices, asParam(detune)).also { for (i in it.indices) it[i] = Math.pow(2.0, it[i] / 12.0) }
        envelope = SourceEnvelope(sampleRate, c, 0.3 * (1.0 / Math.sqrt(unison)))
        val rng = SourceRng(spec.seed)
        startPhase = phase ?: RandomSource { rng.nextDouble() }
        phases = DoubleArray(voiceCount)
    }

    override fun start(startFrame: Long) {
        frame = startFrame
        val end = envelope.schedule(startFrame.toDouble() / sampleRate, durationSec)
        endSeconds = end
        gate.open(startFrame.toDouble() / sampleRate, end, startFrame)
        endFrame = gate.to
        phasesDrawn = false
        (pitch as? PitchModulation)?.restart()
    }

    override fun steal(tNew: Double) = envelope.steal(tNew)

    override fun renderBlock(left: FloatArray, right: FloatArray, offset: Int, m: Int) {
        val bus = this.bus
        if (bus != null) {
            bus.clear(m)
            pitch?.add(bus, m)
            fm?.add(bus, m)
        }
        val from = (gate.from - frame).coerceIn(0L, m.toLong()).toInt()
        val to = (gate.to - frame).coerceIn(0L, m.toLong()).toInt()
        java.util.Arrays.fill(left, offset, offset + m, 0f)
        java.util.Arrays.fill(right, offset, offset + m, 0f)
        if (to > from) {
            if (!phasesDrawn) {
                for (n in 0 until voiceCount) phases[n] = startPhase.next()
                phasesDrawn = true
            }
            mix(left, right, offset, from, to, bus)
        }
        envelope.fill(gains, frame, m)
        for (i in 0 until m) {
            left[offset + i] *= gains[i]
            right[offset + i] *= gains[i]
        }
    }

    /** The worklet's `process` over frames `[from, to)` of the block: every voice in turn, into float32 buffers. */
    private fun mix(left: FloatArray, right: FloatArray, offset: Int, from: Int, to: Int, bus: PitchBus?) {
        val cents = if (bus != null && bus.detuneActive) bus.detuneCents else null
        val add = if (bus != null && bus.frequencyActive) bus.frequencyAddHz else null
        val baseHz = hz.toFloat()
        for (k in from until to) {
            var freq = if (add != null) (baseHz + add[k].toFloat()).toDouble() else hz
            if (cents != null) freq *= Math.pow(2.0, (cents[k].toFloat().toDouble() / 100.0) / 12.0)
            var gl = gainLeft
            var gr = gainRight
            val index = offset + k
            for (n in 0 until voiceCount) {
                val dt = frac(freq * ratios[n] * invSampleRate)
                val ph = phases[n]
                val v = sawBlep(ph, dt)
                left[index] = (left[index] + v * gl).toFloat()
                right[index] = (right[index] + v * gr).toFloat()
                var next = ph + dt
                if (next >= 1.0) next -= 1.0
                phases[n] = next
                val swap = gl
                gl = gr
                gr = swap
            }
        }
    }

    private companion object {
        const val DEFAULT_UNISON = 5.0
        const val DEFAULT_SPREAD = 0.6
        const val DEFAULT_DETUNE = 0.18

        /** `Math.min(Math.max(x, lo), hi)`. */
        fun clampValue(x: Double, lo: Double, hi: Double): Double = Math.min(Math.max(x, lo), hi)

        /** A sawtooth that is smoothed over the two samples next to its jump (worklets.mjs `waveshapes.sawblep`). */
        fun sawBlep(phase: Double, dt: Double): Double = (2.0 * phase - 1.0) - polyBlep(phase, dt)

        /** The correction of a band-limited step (worklets.mjs:56-71): non-zero within `dt` of either end of the cycle. */
        fun polyBlep(phase: Double, dtIn: Double): Double {
            val dt = Math.min(dtIn, 1.0 - dtIn)
            val invDt = 1.0 / dt
            if (phase < dt) {
                val p = phase * invDt
                return 2.0 * p - p * p - 1.0
            }
            if (phase > 1.0 - dt) {
                val p = (phase - 1.0) * invDt
                return p * p + 2.0 * p + 1.0
            }
            return 0.0
        }
    }
}
