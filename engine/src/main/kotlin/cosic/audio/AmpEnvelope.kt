package cosic.audio

/**
 * The amplitude envelope that superdough's sampler puts right after the buffer (sampler.mjs:335-359). The built-in sounds (oscillators, noises, wavetables) make their own
 * inside their source, see `SourceEnvelope`.
 */
internal object AmpEnvelope {
    /**
     * Schedules the envelope of a sample voice that starts at [t] on [env], which multiplies the source, and returns when the source ends
     * (`envEnd`): the time after the release when the source node stops.
     *
     * The ADSR is `getADSRValues` of the four controls (NaN: not set), which a sample has no defaults for. The gate lasts [durationSec], the hap's duration including
     * `clip`, unless the sample has neither `clip`, `loop` nor `release` set (the three flags): then it is the length of the slice it plays, [sliceSec]. The envelope itself
     * is linear from 0 to 1 (`getParamADSR` with min 0, max 1).
     */
    fun schedule(
        env: AParam, attack: Double, decay: Double, sustain: Double, release: Double,
        t: Double, durationSec: Double, sliceSec: Double, clipSet: Boolean, loopSet: Boolean, releaseSet: Boolean,
    ): Double {
        val a = adsrComponent(0, attack, decay, sustain, release, null)
        val d = adsrComponent(1, attack, decay, sustain, release, null)
        val s = adsrComponent(2, attack, decay, sustain, release, null)
        val r = adsrComponent(3, attack, decay, sustain, release, null)
        val duration = if (!clipSet && !loopSet && !releaseSet) sliceSec else durationSec
        val holdEnd = t + duration
        getParamADSR(env, a, d, s, r, 0.0, 1.0, t, holdEnd, false)
        return holdEnd + r + 0.01
    }
}
