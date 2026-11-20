package cosic.audio

/*
 * Strudel's envelope helpers (packages/superdough/helpers.mjs:55-114 and 182-193): `getADSRValues` picks the defaults of an
 * envelope from which of its four values the pattern set, `getParamADSR` schedules the envelope on a parameter.
 */

private const val ENV_MIN = 0.001
private const val ENV_MAX = 1.0
private const val RELEASE_MIN = 0.01

/** The envelope of a sound that was given none: `[attack, decay, sustain, release]`. */
internal val DEFAULT_ADSR = doubleArrayOf(ENV_MIN, ENV_MIN, ENV_MAX, RELEASE_MIN)

/**
 * Component [index] (0 attack, 1 decay, 2 sustain, 3 release) of `getADSRValues([a, d, s, r], curve, defaults)`. A NaN stands
 * for the `undefined` of the original. When none of the four is set the result is [defaults] (or [DEFAULT_ADSR]); otherwise a
 * value that was not set counts as 0 and every one is raised to its minimum (0.001, the release 0.01), the sustain is capped
 * at 1, and a sustain that was not set is 1 when only the attack (or nothing but the release) was given and 0.001 when the decay was.
 */
internal fun adsrComponent(index: Int, a: Double, d: Double, s: Double, r: Double, defaults: DoubleArray? = null): Double {
    if (a.isNaN() && d.isNaN() && s.isNaN() && r.isNaN()) return (defaults ?: DEFAULT_ADSR)[index]
    return when (index) {
        0 -> Math.max(if (a.isNaN()) 0.0 else a, ENV_MIN)
        1 -> Math.max(if (d.isNaN()) 0.0 else d, ENV_MIN)
        2 -> {
            val sustain = if (!s.isNaN()) s else if ((!a.isNaN() && d.isNaN()) || (a.isNaN() && d.isNaN())) ENV_MAX else ENV_MIN
            Math.min(sustain, ENV_MAX)
        }
        else -> Math.max(if (r.isNaN()) 0.0 else r, RELEASE_MIN)
    }
}

/** `getADSRValues`: see [adsrComponent]. Allocates the result; the audio thread asks [adsrComponent] four times instead. */
internal fun getADSRValues(a: Double, d: Double, s: Double, r: Double, defaults: DoubleArray? = null): DoubleArray =
    DoubleArray(4) { adsrComponent(it, a, d, s, r, defaults) }

/**
 * `getParamADSR(param, attack, decay, sustain, release, min, max, begin, end, curve)`: schedules an envelope on [p] that
 * starts at [min] at time [begin], rises to [max] over [a] seconds, falls to `min + s (max - min)` over [d] seconds, holds that
 * until [end], and returns to [min] over [r] seconds. An envelope that is cut short by [end] (shorter than the attack, or than
 * attack plus decay) stops at the point of the straight line it was on. [exponential] selects exponential ramps (used for
 * frequencies) over linear ones; there a [min] or [max] of 0 becomes 0.001.
 *
 * NaN arguments for [a], [d], [s] and [r] count as 0, as `nanFallback` does.
 */
internal fun getParamADSR(
    p: AParam, a: Double, d: Double, s: Double, r: Double,
    min: Double, max: Double, begin: Double, end: Double, exponential: Boolean,
) {
    val attack = nanFallback(a, 0.0)
    val decay = nanFallback(d, 0.0)
    val sustain = nanFallback(s, 0.0)
    val release = nanFallback(r, 0.0)
    // `min = min === 0 ? 0.001 : min` (exponential ramps cannot start or end at 0)
    val low = if (exponential && min == 0.0) 0.001 else min
    val high = if (exponential && max == 0.0) 0.001 else max
    val sustainVal = low + sustain * (high - low)
    val duration = end - begin

    p.setValueAtTime(low, begin)
    if (attack > duration) {
        ramp(p, exponential, begin, envAt(duration, attack, decay, low, high, sustainVal, exponential), end)
    } else if (attack + decay > duration) {
        ramp(p, exponential, begin, envAt(attack, attack, decay, low, high, sustainVal, exponential), begin + attack)
        ramp(p, exponential, begin, envAt(duration, attack, decay, low, high, sustainVal, exponential), end)
    } else {
        ramp(p, exponential, begin, envAt(attack, attack, decay, low, high, sustainVal, exponential), begin + attack)
        ramp(p, exponential, begin, envAt(attack + decay, attack, decay, low, high, sustainVal, exponential), begin + attack + decay)
        p.setValueAtTime(sustainVal, end)
    }
    ramp(p, exponential, begin, low, end + release)
}

/** `envValAtTime` of the original: the straight-line value of the envelope [time] seconds after its start. */
private fun envAt(time: Double, attack: Double, decay: Double, low: Double, high: Double, sustainVal: Double, exponential: Boolean): Double {
    val v = if (attack > time) time * slope(low, high, 0.0, attack) + low else (time - attack) * slope(high, sustainVal, 0.0, decay) + high
    // `val || 0.001`: 0 and NaN both give 0.001
    return if (exponential && (v == 0.0 || v.isNaN())) 0.001 else v
}

private fun ramp(p: AParam, exponential: Boolean, now: Double, v: Double, t: Double) {
    if (exponential) p.exponentialRampToValueAtTime(v, t, now) else p.linearRampToValueAtTime(v, t, now)
}

private fun slope(y1: Double, y2: Double, x1: Double, x2: Double): Double {
    val denominator = x2 - x1
    return if (denominator == 0.0) 0.0 else (y2 - y1) / denominator
}
