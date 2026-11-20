package cosic.audio.dsp

import cosic.audio.clamp

/**
 * The nine saturation curves of `distort` (superdough `helpers.mjs:509-599`), by index in `Object.keys` order, and the
 * `distort-processor` worklet that applies one of them (`worklets.mjs:429-463`).
 *
 * Every curve is `f(x, k)` with `k = expm1(distort)`, so `distort = 0` is `k = 0`. They are ported literally, down to the quirks:
 * the Chebyshev loop starts its sum with `x` (not `T0`) and adds the even terms only, and `diode`/`asym` divide by the slope at 0.
 */
internal object Distortion {
    val names: List<String> = listOf("scurve", "soft", "hard", "cubic", "diode", "asym", "fold", "sinefold", "chebyshev")

    /**
     * `getDistortionAlgorithm`'s index for a `distorttype`: a name is its position in [names] (an unknown name is 0, the worklet logs it),
     * a number wraps around the nine curves. Where the worklet would throw (a fractional or negative number, anything that is no
     * name or number) this answers like the nearest sensible input: numbers are truncated and wrapped with a positive modulo, anything else is 0.
     */
    fun algorithmIndex(type: Any?): Int = when (type) {
        is String -> names.indexOf(type).let { if (it < 0) 0 else it }
        is Number -> {
            val d = type.toDouble()
            if (d.isNaN() || d.isInfinite()) 0 else Math.floorMod(d.toLong(), names.size.toLong()).toInt()
        }
        else -> 0
    }

    /** Curve [index] (0..8; anything else is `scurve`) at input [x] with shape factor [k] = `expm1(distort)`. */
    fun apply(index: Int, x: Double, k: Double): Double = when (index) {
        1 -> soft(x, k)
        2 -> hard(x, k)
        3 -> cubic(x, k)
        4 -> diode(x, k, false)
        5 -> diode(x, k, true)
        6 -> fold(x, k)
        7 -> sineFold(x, k)
        8 -> chebyshev(x, k)
        else -> scurve(x, k)
    }

    internal fun scurve(x: Double, k: Double): Double = ((1 + k) * x) / (1 + k * Math.abs(x))

    internal fun soft(x: Double, k: Double): Double = Math.tanh(x * (1 + k))

    internal fun hard(x: Double, k: Double): Double = clamp((1 + k) * x, -1.0, 1.0)

    internal fun fold(x: Double, k: Double): Double {
        // Closed form folding for audio rate.
        val y = (1 + 0.5 * k) * x
        val window = mod(y + 1, 4.0)
        return 1 - Math.abs(window - 2)
    }

    internal fun sineFold(x: Double, k: Double): Double = Math.sin((Math.PI / 2) * fold(x, k))

    internal fun cubic(x: Double, k: Double): Double {
        val t = squash(Math.log1p(k))
        val cubic = (x - (t / 3) * x * x * x) / (1 - t / 3) // normalized to go from (-1, 1)
        return soft(cubic, k)
    }

    internal fun diode(x: Double, k: Double, asym: Boolean): Double {
        val g = 1 + 2 * k // gain
        val t = squash(Math.log1p(k))
        val bias = 0.07 * t
        val pos = soft(x + bias, 2 * k)
        val neg = soft(if (asym) bias else -x + bias, 2 * k)
        val y = pos - neg
        // Divided by the derivative at 0, so that the distortion is roughly the identity near 0.
        val sech = 1 / Math.cosh(g * bias)
        val sech2 = sech * sech
        val denominator = Math.max(1e-8, (if (asym) 1 else 2) * g * sech2)
        return soft(y / denominator, k)
    }

    internal fun chebyshev(x: Double, k: Double): Double {
        val kl = 10 * Math.log1p(k)
        var tnm1 = 1.0
        var tnm2 = x
        var y = 0.0
        for (i in 1 until 64) {
            if (i < 2) {
                // The initial conditions: the loop adds x for i = 1 and never T0.
                y += tnm2
                continue
            }
            val tn = 2 * x * tnm1 - tnm2 // https://en.wikipedia.org/wiki/Chebyshev_polynomials#Recurrence_definition
            tnm2 = tnm1
            tnm1 = tn
            if (i % 2 == 0) y += Math.min((1.3 * kl) / i, 2.0) * tn
        }
        // Soft clip
        return soft(y, kl / 20)
    }

    /** `[0, inf)` to `[0, 1)`. */
    private fun squash(x: Double): Double = x / (1 + x)

    /** `((n % m) + m) % m` with JavaScript's remainder. */
    private fun mod(n: Double, m: Double): Double = ((n % m) + m) % m
}

/**
 * The `distort-processor` worklet (`worklets.mjs:429-463`): `postgain * curve(x, expm1(distort))` for every sample, with `postgain` clamped to 0.001..1.
 * Stateless apart from the curve it was built with, so a stage can keep one instance and change [algorithmIndex] between voices.
 *
 * Both AudioParams are a-rate in the worklet (`pv(param, n)`, one value per sample when they move): [process] takes one value for the
 * whole range, [processModulated] one per frame (what `AParam.fill` produces).
 */
internal class DistortProcessor(var algorithmIndex: Int = 0) {
    /** Distorts frames `[from, to)` of [left] (and [right]) in place with constant [distort] (the `distort` AudioParam, 0 by default) and [postgain] (1 by default). */
    fun process(left: FloatArray, right: FloatArray?, from: Int, to: Int, distort: Double, postgain: Double) {
        if (to <= from) return
        val k = Math.expm1(distort)
        val gain = clamp(postgain, 0.001, 1.0)
        constant(left, from, to, k, gain)
        if (right != null) constant(right, from, to, k, gain)
    }

    /** Like [process], with `distort[n]` and `postgain[n]` for frame `n` (indexed like [left]). */
    fun processModulated(left: FloatArray, right: FloatArray?, from: Int, to: Int, distort: FloatArray, postgain: FloatArray) {
        if (to <= from) return
        modulated(left, from, to, distort, postgain)
        if (right != null) modulated(right, from, to, distort, postgain)
    }

    private fun constant(io: FloatArray, from: Int, to: Int, k: Double, gain: Double) {
        when (algorithmIndex) {
            1 -> for (n in from until to) io[n] = (gain * Distortion.soft(io[n].toDouble(), k)).toFloat()
            2 -> for (n in from until to) io[n] = (gain * Distortion.hard(io[n].toDouble(), k)).toFloat()
            3 -> for (n in from until to) io[n] = (gain * Distortion.cubic(io[n].toDouble(), k)).toFloat()
            4 -> for (n in from until to) io[n] = (gain * Distortion.diode(io[n].toDouble(), k, false)).toFloat()
            5 -> for (n in from until to) io[n] = (gain * Distortion.diode(io[n].toDouble(), k, true)).toFloat()
            6 -> for (n in from until to) io[n] = (gain * Distortion.fold(io[n].toDouble(), k)).toFloat()
            7 -> for (n in from until to) io[n] = (gain * Distortion.sineFold(io[n].toDouble(), k)).toFloat()
            8 -> for (n in from until to) io[n] = (gain * Distortion.chebyshev(io[n].toDouble(), k)).toFloat()
            else -> for (n in from until to) io[n] = (gain * Distortion.scurve(io[n].toDouble(), k)).toFloat()
        }
    }

    private fun modulated(io: FloatArray, from: Int, to: Int, distort: FloatArray, postgain: FloatArray) {
        for (n in from until to) {
            val gain = clamp(postgain[n].toDouble(), 0.001, 1.0)
            val k = Math.expm1(distort[n].toDouble())
            io[n] = (gain * Distortion.apply(algorithmIndex, io[n].toDouble(), k)).toFloat()
        }
    }
}
