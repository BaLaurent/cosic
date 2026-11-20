package cosic.audio.sources

import cosic.audio.AParam
import cosic.audio.BLOCK_FRAMES
import cosic.audio.getADSRValues
import cosic.audio.getParamADSR
import cosic.pattern.Undefined
import cosic.pattern.jsToString

/**
 * The pitch modulation that superdough puts on the `detune` AudioParam (in cents) of its oscillators: the vibrato of `getVibratoOscillator` and the pitch
 * envelope of `getPitchEnvelope` (helpers.mjs:340-379). Both add up; [add] writes the sum into [PitchBus.detuneCents].
 *
 * **Vibrato** is active when `vib > 0`: a sine oscillator of `vib` Hz, starting at phase 0, times `vibmod * 100` (`vibmod`, in semitones, defaults to 0.5). It is the
 * oscillator of the Web Audio `OscillatorNode` (a [PeriodicOscillator] on the sine wave), so a slow vibrato reads its table the way Chromium does.
 *
 * **The pitch envelope** is active when any of `pattack pdecay psustain prelease penv` is set: `getParamADSR(detune, a, d, s, r, min, max, begin, holdEnd, curve)` with
 * `penv` semitones (`nanFallback(penv, 1)`: 1 when it is not set), the envelope values of `getADSRValues` with the defaults `[0.2, 0.001, 1, 0.001]`, `panchor`
 * (defaulting to the sustain) saying how much of the swing lies below the note (`min = -cents * panchor`, `max = cents - cents * panchor`), and the ramp shape
 * `pcurve`: 0 is linear, 1 exponential, and any other value is no valid curve, which `getParamADSR` takes for its default, exponential.
 *
 * Time is relative to the start of the voice: the first frame [add] is asked for is time 0 (the note's `begin`), and the envelope's hold ends [holdSec]
 * seconds later. [add] is called once per block, with the same frames as the source renders, and must be called for every block from the start (also while the source
 * itself is still waiting for its worklet to begin), so that the frame count stays the source's.
 */
internal class PitchModulation private constructor(
    private val sampleRate: Int,
    private val vibrato: PeriodicOscillator?,
    private val vibratoHz: Double,
    private val vibratoGain: Float,
    private val envelope: AParam?,
) : PitchModulator {
    private val envelopeBlock = if (envelope != null) FloatArray(BLOCK_FRAMES) else null
    private val vibratoBlock = if (vibrato != null) FloatArray(BLOCK_FRAMES) else null
    private var frame = 0L

    /** Back to the start of the note: time 0 again, the vibrato at phase 0. */
    fun restart() {
        frame = 0L
        vibrato?.reset()
    }

    override fun add(bus: PitchBus, n: Int) {
        val cents = bus.detuneCents
        var done = 0
        while (done < n) {
            val m = Math.min(BLOCK_FRAMES, n - done)
            if (envelope != null && envelopeBlock != null) {
                envelope.timeline.fillBlock(envelopeBlock, frame, sampleRate, m, envelope.base)
                for (i in 0 until m) cents[done + i] += envelopeBlock[i].toDouble()
            }
            if (vibrato != null && vibratoBlock != null) {
                vibrato.render(vibratoBlock, m, vibratoHz, null)
                // a gain node multiplies float by float; the sum then lands in the parameter
                for (i in 0 until m) cents[done + i] += (vibratoBlock[i] * vibratoGain).toDouble()
            }
            frame += m
            done += m
        }
        bus.detuneActive = true
    }

    companion object {
        /**
         * The modulation of the event in [spec], or null when it has neither vibrato nor a pitch envelope. [holdSec] is when the envelope's hold ends, relative to the
         * start: the duration of the note for the stock oscillators, the supersaw and the pulse, and `min(clip * duration, duration)` for a wavetable.
         */
        fun create(spec: SourceSpec, holdSec: Double): PitchModulation? {
            val c = spec.controls
            val sampleRate = spec.sampleRate

            var vibrato: PeriodicOscillator? = null
            var vibratoHz = 0.0
            var vibratoGain = 0f
            val vib = c.number("vib")
            if (vib != null && vib > 0.0) {
                vibrato = PeriodicOscillator(PeriodicWave.basic(BasicWave.SINE, sampleRate))
                vibratoHz = asParam(vib)
                vibratoGain = ((c.number("vibmod") ?: DEFAULT_VIBRATO_SEMITONES) * 100.0).toFloat()
            }

            var envelope: AParam? = null
            if (ENVELOPE_KEYS.any { c.isSet(it) }) {
                val semitones = c.number("penv") ?: 1.0
                val curve = c["pcurve"].let { if (it == null || it === Undefined) "0" else jsToString(it) }
                val adsr = getADSRValues(
                    c.number("pattack") ?: Double.NaN, c.number("pdecay") ?: Double.NaN, c.number("psustain") ?: Double.NaN, c.number("prelease") ?: Double.NaN,
                    PITCH_ADSR,
                )
                val anchor = c.number("panchor") ?: adsr[2]
                val cents = semitones * 100.0
                val min = 0.0 - cents * anchor
                val max = cents - cents * anchor
                envelope = AParam(0.0, -DETUNE_LIMIT, DETUNE_LIMIT, 12)
                getParamADSR(envelope, adsr[0], adsr[1], adsr[2], adsr[3], min, max, 0.0, holdSec, curve != "0")
            }
            if (vibrato == null && envelope == null) return null
            return PitchModulation(sampleRate, vibrato, vibratoHz, vibratoGain, envelope)
        }

        private val ENVELOPE_KEYS = listOf("pattack", "pdecay", "psustain", "prelease", "penv")
        private val PITCH_ADSR = doubleArrayOf(0.2, 0.001, 1.0, 0.001)
        private const val DEFAULT_VIBRATO_SEMITONES = 0.5
        private const val DETUNE_LIMIT = 3.4028234663852886e38
    }
}
