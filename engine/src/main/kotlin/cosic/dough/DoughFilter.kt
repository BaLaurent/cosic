package cosic.dough

/*
 * dough.h 866-1013: the biquad of the filters and of the phaser. The coefficients are worked out again for every sample (the cutoff may move with an envelope), with the
 * parabola sine and the exp2 approximation of DoughMath, from the Audio EQ Cookbook.
 */

internal const val BIQUAD_LOWPASS = 0
internal const val BIQUAD_HIGHPASS = 1
internal const val BIQUAD_BANDPASS = 2
internal const val BIQUAD_NOTCH = 3
internal const val BIQUAD_ALLPASS = 4
internal const val BIQUAD_PEAKING = 5
internal const val BIQUAD_LOWSHELF = 6
internal const val BIQUAD_HIGHSHELF = 7

internal class BiquadFilter {
    private var b0 = 1f
    private var b1 = 0f
    private var b2 = 0f
    private var a0 = 1f
    private var a1 = 0f
    private var a2 = 0f
    private var x1 = 0f
    private var x2 = 0f
    private var y1 = 0f
    private var y2 = 0f

    // the inputs the coefficients were last made from
    private var cachedType = -1
    private var cachedFreq = 0
    private var cachedQ = 0
    private var cachedGain = 0
    private var cachedSr = 0

    fun reset() {
        x1 = 0f
        x2 = 0f
        y1 = 0f
        y2 = 0f
        a0 = 1f
        a1 = 0f
        a2 = 0f
        b0 = 1f
        b1 = 0f
        b2 = 0f
        cachedType = -1
    }

    /**
     * `BiquadFilter_update`: filters [input] with the filter of [type] at [freq] hertz. Only the low-pass and the high-pass read [q0] in decibels (the Web Audio rule); the others
     * take it as a linear Q. [gain] is for the peaking and the shelves. [sr] is the engine's sample rate.
     */
    fun update(input: Float, type: Int, freq: Float, q0: Float, gain: Float, sr: Float): Float {
        // the coefficients are a function of these five numbers alone: a filter whose cutoff does not move (no envelope) works them out once
        if (type != cachedType || freq.toRawBits() != cachedFreq || q0.toRawBits() != cachedQ || gain.toRawBits() != cachedGain || sr.toRawBits() != cachedSr) {
            if (!computeCoefficients(type, freq, q0, gain, sr)) return input
            cachedType = type
            cachedFreq = freq.toRawBits()
            cachedQ = q0.toRawBits()
            cachedGain = gain.toRawBits()
            cachedSr = sr.toRawBits()
        }
        val output = b0 * input + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1
        x1 = input
        y2 = y1
        y1 = output
        return output
    }

    /** The first half of `BiquadFilter_update`: the coefficients of [type] (false for a type the engine does not know, which leaves the filter as it is). */
    private fun computeCoefficients(type: Int, freq: Float, q0: Float, gain: Float, sr: Float): Boolean {
        var q = q0
        val omega = (2.0 * FPI.toDouble() * freq.toDouble() / sr.toDouble()).toFloat()
        val sinOmega = parSinf(omega)
        if (type == BIQUAD_LOWPASS || type == BIQUAD_HIGHPASS) q = pow10f(q / 20.0f)
        val alpha = sinOmega / (2.0f * q)
        val cosOmega = parCosf(omega)
        val a: Float
        val sqrt2Aa: Float
        val am1w0: Float
        val ap1w0: Float
        when (type) {
            BIQUAD_LOWPASS -> {
                b1 = 1 - cosOmega
                b0 = b1 / 2.0f
                b2 = b0
                a0 = 1 + alpha
                a1 = -2 * cosOmega
                a2 = 1 - alpha
            }
            BIQUAD_HIGHPASS -> {
                b0 = (1 + cosOmega) / 2.0f
                b1 = -(1 + cosOmega)
                b2 = b0
                a0 = 1 + alpha
                a1 = -2 * cosOmega
                a2 = 1 - alpha
            }
            BIQUAD_BANDPASS -> {
                b0 = sinOmega / 2.0f
                b1 = 0f
                b2 = -b0
                a0 = 1 + alpha
                a1 = -2 * cosOmega
                a2 = 1 - alpha
            }
            BIQUAD_NOTCH -> {
                b0 = 1f
                b1 = -2 * cosOmega
                b2 = 1f
                a0 = 1 + alpha
                a1 = -2 * cosOmega
                a2 = 1 - alpha
            }
            BIQUAD_ALLPASS -> {
                b0 = 1 - alpha
                b1 = -2 * cosOmega
                b2 = 1 + alpha
                a0 = 1 + alpha
                a1 = -2 * cosOmega
                a2 = 1 - alpha
            }
            BIQUAD_PEAKING -> {
                a = pow10f((gain.toDouble() / 40.0).toFloat())
                b0 = 1 + alpha * a
                b1 = -2 * cosOmega
                b2 = 1 - alpha * a
                a0 = 1 + alpha / a
                a1 = -2 * cosOmega
                a2 = 1 - alpha / a
            }
            BIQUAD_LOWSHELF -> {
                a = pow10f((gain.toDouble() / 40.0).toFloat())
                sqrt2Aa = 2 * sqrtf(a) * alpha
                am1w0 = (a - 1) * cosOmega
                ap1w0 = (a + 1) * cosOmega
                b0 = a * ((a + 1) - am1w0 + sqrt2Aa)
                b1 = 2 * a * ((a - 1) - ap1w0)
                b2 = a * ((a + 1) - am1w0 - sqrt2Aa)
                a0 = (a + 1) + am1w0 + sqrt2Aa
                a1 = -2 * ((a - 1) + ap1w0)
                a2 = (a + 1) + am1w0 - sqrt2Aa
            }
            BIQUAD_HIGHSHELF -> {
                a = pow10f((gain.toDouble() / 40.0).toFloat())
                sqrt2Aa = 2 * sqrtf(a) * alpha
                am1w0 = (a - 1) * cosOmega
                ap1w0 = (a + 1) * cosOmega
                b0 = a * ((a + 1) + am1w0 + sqrt2Aa)
                b1 = -2 * a * ((a - 1) + ap1w0)
                b2 = a * ((a + 1) + am1w0 - sqrt2Aa)
                a0 = (a + 1) - am1w0 + sqrt2Aa
                a1 = 2 * ((a - 1) - ap1w0)
                a2 = (a + 1) - am1w0 - sqrt2Aa
            }
            else -> return false
        }
        b0 /= a0
        b1 /= a0
        b2 /= a0
        a1 /= a0
        a2 /= a0
        a0 = 1.0f
        return true
    }
}
