package cosic.audio

/** What a voice's amplitude envelope is for: they differ in their default ADSR and, for samples, in what ends the gate. */
internal enum class AmpKind { SYNTH, SAMPLE, BUS }

/** Defaults of `getADSRValues` for a waveform synth or noise (synth.mjs:47-51, 411-415). Samples have none, buses are like synths with sustain 1. */
private val SYNTH_ADSR = doubleArrayOf(0.001, 0.05, 0.6, 0.01)
private val BUS_ADSR = doubleArrayOf(0.001, 0.05, 1.0, 0.01)

/** The amplitude envelope that superdough's sources put right after the oscillator or the buffer (synth.mjs:54-71, sampler.mjs:335-359). */
internal object AmpEnvelope {
    /**
     * Schedules the envelope of a voice that starts at [t] on [env], which multiplies the source, and returns when the source ends
     * (`envEnd`): the time after the release when the source node stops.
     *
     * The ADSR is `getADSRValues` of the four controls (NaN: not set) with the defaults of the [kind]. The gate lasts [durationSec], the
     * hap's duration including `clip`, except for a sample that has neither `clip`, `loop` nor `release` set: its gate is the length of
     * the slice it plays, [sliceSec]. The envelope itself is linear from 0 to 1 (`getParamADSR` with min 0, max 1).
     */
    fun schedule(
        env: AParam, kind: AmpKind, attack: Double, decay: Double, sustain: Double, release: Double,
        t: Double, durationSec: Double, sliceSec: Double, clipSet: Boolean, loopSet: Boolean, releaseSet: Boolean,
    ): Double {
        val defaults = when (kind) {
            AmpKind.SYNTH -> SYNTH_ADSR
            AmpKind.BUS -> BUS_ADSR
            AmpKind.SAMPLE -> null
        }
        val a = adsrComponent(0, attack, decay, sustain, release, defaults)
        val d = adsrComponent(1, attack, decay, sustain, release, defaults)
        val s = adsrComponent(2, attack, decay, sustain, release, defaults)
        val r = adsrComponent(3, attack, decay, sustain, release, defaults)
        val duration = if (kind == AmpKind.SAMPLE && !clipSet && !loopSet && !releaseSet) sliceSec else durationSec
        val holdEnd = t + duration
        getParamADSR(env, a, d, s, r, 0.0, 1.0, t, holdEnd, false)
        return holdEnd + r + 0.01
    }
}
