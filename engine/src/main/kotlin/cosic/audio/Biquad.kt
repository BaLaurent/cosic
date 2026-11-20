package cosic.audio

/**
 * The shapes of Web Audio's `BiquadFilterNode` that superdough uses: the low-pass, high-pass and band-pass filters of its filter stages and
 * the notch of the phaser.
 */
internal enum class BiquadType { LOWPASS, HIGHPASS, BANDPASS, NOTCH }

/**
 * Web Audio's `BiquadFilterNode` as Chromium computes it (blink `platform/audio/biquad.cc` and `biquad_filter_handler.cc`): the same
 * design formulas and special cases, the same arithmetic.
 *
 * * **Design.** The frequency is normalised by the Nyquist frequency (`frequency / (sampleRate / 2)`), clamped to `[0, 1]`, and the
 *   cutoff angle is `theta = pi * normalised` (no pre-warping). The Q of the low-pass and high-pass is in **decibels** (`10^(Q / 20)`
 *   through a *float* `exp`, as `pow10` of biquad.cc does: `fdlibm::expf(x * ln10)`), the Q of the band-pass and the notch is linear and
 *   a negative one counts as 0. Coefficients are normalised by `a0` with one reciprocal (`b * (1 / a0)`), in double precision.
 * * **Special cases**, all of Chromium's: low-pass at 1 is the identity and at 0 silence; high-pass at 1 is silence and at 0 the identity;
 *   band-pass outside `(0, 1)` is silence and with Q = 0 the identity; notch outside `(0, 1)` is the identity and with Q = 0 silence.
 * * **Processing.** Direct form I in double arithmetic, with the *float* input and the float-**rounded** output as history
 *   (`float y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;` of `Biquad::Process`), per channel. The history is flushed to zero when it
 *   is denormal at the end of each call, as Chromium does at the end of a render quantum.
 *
 * Differences, all for robustness: a non-finite output (an unstable setting such as a hugely negative Q, or a NaN in the input) is written
 * as 0 and the history restarts from silence, so one bad value cannot poison an orbit's feedback loop; `Math.sin`/`Math.cos`/`Math.exp`
 * stand in for fdlibm's (a last-bit difference of the double coefficients, far below the float rounding of the output).
 *
 * One instance holds one set of coefficients and the memory of two channels. Nothing here allocates after construction.
 */
internal class Biquad(sampleRate: Int) {
    private val nyquist = 0.5 * sampleRate

    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0

    // The filter memory per channel: the last two inputs and the last two (rounded) outputs.
    private val x1 = FloatArray(2)
    private val x2 = FloatArray(2)
    private val y1 = FloatArray(2)
    private val y2 = FloatArray(2)

    /** Clears the filter memory (the coefficients stay). */
    fun reset() {
        x1.fill(0f)
        x2.fill(0f)
        y1.fill(0f)
        y2.fill(0f)
    }

    /**
     * Sets the coefficients for a filter of [type] at [computedFreqHz] (the frequency after detune, in Hz; clamped to `[0, nyquist]`) and
     * resonance [q]: **decibels** for [BiquadType.LOWPASS] and [BiquadType.HIGHPASS], linear for the others.
     */
    fun setCoefficients(type: BiquadType, computedFreqHz: Double, q: Double) {
        design(type, computedFreqHz / nyquist, q)
    }

    /**
     * [setCoefficients] for a frequency and a detune in cents, in the order and precision Chromium computes them (`NormalizeFrequency`): the
     * frequency is normalised first and multiplied by `exp2(detune / 1200)` after, only when the detune is not 0; both the division and the
     * `exp2` are float operations there (the detune is a float AudioParam), so the multiplier has float precision. Use it for a node whose
     * `detune` parameter moves (the phaser).
     */
    fun setCoefficients(type: BiquadType, frequencyHz: Double, detuneCents: Double, q: Double) {
        val normalised = frequencyHz / nyquist
        val detune = detuneCents.toFloat()
        val multiplier = Math.pow(2.0, (detune / 1200f).toDouble()).toFloat().toDouble()
        design(type, if (detune != 0f) normalised * multiplier else normalised, q)
    }

    /** Takes over the coefficients of [other] (a second filter in series with the same settings) without computing them again. */
    fun copyCoefficientsFrom(other: Biquad) {
        b0 = other.b0
        b1 = other.b1
        b2 = other.b2
        a1 = other.a1
        a2 = other.a2
    }

    /** Filters `buf[0 until n]` in place with the memory of [channel] (0 or 1). */
    fun process(buf: FloatArray, n: Int, channel: Int) = process(buf, 0, n, channel)

    /** Filters `buf[from until to]` in place with the memory of [channel] (0 or 1). */
    fun process(buf: FloatArray, from: Int, to: Int, channel: Int) {
        if (to <= from) return
        val b0 = b0
        val b1 = b1
        val b2 = b2
        val a1 = a1
        val a2 = a2
        var px1 = x1[channel]
        var px2 = x2[channel]
        var py1 = y1[channel]
        var py2 = y2[channel]
        for (k in from until to) {
            val x = buf[k]
            val y = (b0 * x + b1 * px1 + b2 * px2 - a1 * py1 - a2 * py2).toFloat()
            if (y - y != 0f) {
                // NaN or infinite: restart from silence instead of letting it circulate
                buf[k] = 0f
                px1 = 0f
                px2 = 0f
                py1 = 0f
                py2 = 0f
                continue
            }
            buf[k] = y
            px2 = px1
            px1 = x
            py2 = py1
            py1 = y
        }
        x1[channel] = flushDenormal(px1)
        x2[channel] = flushDenormal(px2)
        y1[channel] = flushDenormal(py1)
        y2[channel] = flushDenormal(py2)
    }

    /**
     * The gain of the current coefficients at [freqHz], linear: the z-transform at `exp(-j pi f / nyquist)` as
     * `Biquad::GetFrequencyResponse` evaluates it. NaN outside `[0, nyquist]`.
     */
    fun magnitude(freqHz: Double): Double {
        val normalised = freqHz / nyquist
        if (normalised < 0.0 || normalised > 1.0) return Double.NaN
        val omega = -Math.PI * normalised
        val zr = Math.cos(omega)
        val zi = Math.sin(omega)
        // numerator = b0 + (b1 + b2 z) z, denominator = 1 + (a1 + a2 z) z
        var tr = b1 + b2 * zr
        var ti = b2 * zi
        val nr = b0 + (tr * zr - ti * zi)
        val ni = tr * zi + ti * zr
        tr = a1 + a2 * zr
        ti = a2 * zi
        val dr = 1.0 + (tr * zr - ti * zi)
        val di = tr * zi + ti * zr
        return Math.hypot(nr, ni) / Math.hypot(dr, di)
    }

    /** [magnitude] in decibels (a gain of 0 is -1000 dB, as in Blink's `LinearToDecibels`). */
    fun magnitudeDb(freqHz: Double): Double = linToDb(magnitude(freqHz))

    private fun design(type: BiquadType, normalisedFrequency: Double, q: Double) {
        when (type) {
            BiquadType.LOWPASS -> lowpass(normalisedFrequency, q)
            BiquadType.HIGHPASS -> highpass(normalisedFrequency, q)
            BiquadType.BANDPASS -> bandpass(normalisedFrequency, q)
            BiquadType.NOTCH -> notch(normalisedFrequency, q)
        }
    }

    // SetLowpassParams
    private fun lowpass(frequency: Double, resonanceDb: Double) {
        val cutoff = clampUnit(frequency)
        if (cutoff == 1.0) {
            set(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
        } else if (cutoff > 0.0) {
            val resonance = pow10(resonanceDb / 20.0)
            val theta = Math.PI * cutoff
            val alpha = Math.sin(theta) / (2.0 * resonance)
            val cosw = Math.cos(theta)
            val beta = (1.0 - cosw) / 2.0
            set(beta, 2.0 * beta, beta, 1.0 + alpha, -2.0 * cosw, 1.0 - alpha)
        } else {
            set(0.0, 0.0, 0.0, 1.0, 0.0, 0.0)
        }
    }

    // SetHighpassParams
    private fun highpass(frequency: Double, resonanceDb: Double) {
        val cutoff = clampUnit(frequency)
        if (cutoff == 1.0) {
            set(0.0, 0.0, 0.0, 1.0, 0.0, 0.0)
        } else if (cutoff > 0.0) {
            val resonance = pow10(resonanceDb / 20.0)
            val theta = Math.PI * cutoff
            val alpha = Math.sin(theta) / (2.0 * resonance)
            val cosw = Math.cos(theta)
            val beta = (1.0 + cosw) / 2.0
            set(beta, -2.0 * beta, beta, 1.0 + alpha, -2.0 * cosw, 1.0 - alpha)
        } else {
            set(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
        }
    }

    // SetBandpassParams: no negative frequency or Q; outside (0, 1) the filter is silence, with Q = 0 the identity
    private fun bandpass(frequency: Double, qLinear: Double) {
        val f = Math.max(0.0, frequency)
        val q = Math.max(0.0, qLinear)
        if (f > 0.0 && f < 1.0) {
            if (q > 0.0) {
                val w0 = Math.PI * f
                val alpha = Math.sin(w0) / (2.0 * q)
                val k = Math.cos(w0)
                set(alpha, 0.0, -alpha, 1.0 + alpha, -2.0 * k, 1.0 - alpha)
            } else {
                set(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
            }
        } else {
            set(0.0, 0.0, 0.0, 1.0, 0.0, 0.0)
        }
    }

    // SetNotchParams: the frequency is clamped to [0, 1] and a frequency of 0 or 1 is the identity; with Q = 0 the filter is silence
    private fun notch(frequency: Double, qLinear: Double) {
        val f = clampUnit(frequency)
        val q = Math.max(0.0, qLinear)
        if (f > 0.0 && f < 1.0) {
            if (q > 0.0) {
                val w0 = Math.PI * f
                val alpha = Math.sin(w0) / (2.0 * q)
                val k = Math.cos(w0)
                set(1.0, -2.0 * k, 1.0, 1.0 + alpha, -2.0 * k, 1.0 - alpha)
            } else {
                set(0.0, 0.0, 0.0, 1.0, 0.0, 0.0)
            }
        } else {
            set(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
        }
    }

    /** `SetNormalizedCoefficients`: every coefficient times one reciprocal of `a0`. */
    private fun set(nb0: Double, nb1: Double, nb2: Double, a0: Double, na1: Double, na2: Double) {
        val inverse = 1.0 / a0
        b0 = nb0 * inverse
        b1 = nb1 * inverse
        b2 = nb2 * inverse
        a1 = na1 * inverse
        a2 = na2 * inverse
    }

    private companion object {
        const val LN10 = 2.302585092994046

        /** `ClampTo(x, 0.0, 1.0)`: NaN stays NaN (and then fails every comparison, which makes the filter silence). */
        fun clampUnit(x: Double): Double = if (x < 0.0) 0.0 else if (x > 1.0) 1.0 else x

        /**
         * `pow10` of biquad.cc: `fdlibm::expf(x * ln10)`, whose body is `(float) exp((double) (float) argument)`, so the result has float
         * precision.
         */
        fun pow10(x: Double): Double = Math.exp((x * LN10).toFloat().toDouble()).toFloat().toDouble()

        /** `DenormalDisabler::FlushDenormalFloatToZero`. */
        fun flushDenormal(x: Float): Float = if (Math.abs(x) < java.lang.Float.MIN_NORMAL) 0f else x
    }
}
