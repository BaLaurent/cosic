package cosic.dough

/*
 * The numeric helpers of dough (dough-synth@0.4.1, dough.h, built for wasm with -DCLANGWASM): the C library that is replaced by a few approximations, and the helpers every
 * stage shares. Everything is `Float` arithmetic like the C code; where the C code mixes in a literal without an `f` suffix (`0.5`, `2.0`) the whole subexpression is a `Double`
 * and is rounded to `Float` on assignment, which the code below spells out with `.toDouble()` / `.toFloat()` so the results are bit for bit those of the wasm.
 */

/** `fPI`: `(float)M_PI`. */
@JvmField
internal val FPI: Float = Math.PI.toFloat()

internal fun floorf(x: Float): Float = Math.floor(x.toDouble()).toFloat()

internal fun fabsf(x: Float): Float = Math.abs(x)

internal fun sqrtf(x: Float): Float = Math.sqrt(x.toDouble()).toFloat()

/** The CLANGWASM `roundf`: `floorf(x + 0.5f)`. */
internal fun roundf(x: Float): Float = floorf(x + 0.5f)

/** The CLANGWASM `fminf` is a ternary, so a NaN in either argument gives `y`. */
internal fun fminf(x: Float, y: Float): Float = if (x < y) x else y

internal fun fmaxf(x: Float, y: Float): Float = if (x > y) x else y

internal fun clamp(value: Float, min: Float, max: Float): Float {
    if (value < min) return min
    if (value > max) return max
    return value
}

/** `nan_fallback`: [value] unless it is NaN. */
internal fun nanFallback(value: Float, fallback: Float): Float = if (value.isNaN()) fallback else value

/** Flush to zero: [x] inside `(-limit, limit)` is 0. */
internal fun ftz(x: Float, limit: Float): Float = if (x < limit && x > -limit) 0.0f else x

// ---- C string conversions (the CLANGWASM build has its own `atoi` and `atof`) ---------------------------------------------------------------

/**
 * The `atoi` of dough.h: an optional `-` and digits, and **0** as soon as anything else is met (`"3.5"` is 0, `"12abc"` is 0, `"+3"` is 0). Overflow wraps like the wasm's `i32`.
 */
internal fun cAtoi(s: String): Int {
    var i = 0
    var negative = false
    if (s.isNotEmpty() && s[0] == '-') {
        negative = true
        i++
    }
    var acc = 0
    while (i < s.length) {
        acc *= 10
        val digit = s[i] - '0'
        if (digit < 0 || 9 < digit) return 0
        acc += digit
        i++
    }
    return if (negative) -acc else acc
}

/**
 * The `atof` of dough.h: a naive scanner (the fraction is summed with a multiplier that is multiplied by 0.1 for every digit, the exponent by repeated multiplication), so
 * `"0.1"` is not always the double nearest to 0.1. The result is rounded to `float` by whoever stores it, and that is where the differences end.
 */
internal fun cAtof(str: String): Double {
    var p = 0
    val n = str.length
    fun at(i: Int): Char = if (i < n) str[i] else 0.toChar()
    fun isDigit(c: Char): Boolean = c in '0'..'9'
    var sign = 1.0
    if (at(p) == '-') {
        sign = -1.0
        p++
    }
    var integerEnd = p
    while (at(integerEnd) != 0.toChar() && isDigit(at(integerEnd))) integerEnd++
    var integerPart = 0.0
    while (p < integerEnd) {
        integerPart *= 10.0
        val digit = at(p) - '0'
        if (digit < 0 || 9 < digit) break
        integerPart += digit.toDouble()
        p++
    }
    var stop = at(p)
    if (stop == 0.toChar()) return sign * integerPart
    var significand = integerPart
    if (stop == '.') {
        p++
        var fractionalMultiplier = 1.0
        while (isDigit(at(p))) {
            fractionalMultiplier *= 0.1
            val digit = at(p) - '0'
            significand += fractionalMultiplier * digit.toDouble()
            p++
        }
    }
    stop = at(p)
    if (stop == 0.toChar()) return sign * significand
    var exponent = 0
    if (stop == 'e' || stop == 'E') exponent = cAtoi(str.substring(p + 1))
    var value = significand
    while (exponent > 0) {
        value *= 10.0
        --exponent
    }
    while (exponent < 0) {
        value *= 0.1
        ++exponent
    }
    return sign * value
}

// ---- approximations -------------------------------------------------------------------------------------------------------------------

private const val F32_BIAS = 0x7f
private const val F32_EXP_SHIFT = 23

/** `our_log2f`: the exponent as the integer part, the significand bent by a rational function. */
internal fun ourLog2f(x: Float): Float {
    val xx0 = x.toRawBits()
    val xx1 = Float.fromBits((xx0 and ((1 shl F32_EXP_SHIFT) - 1)) or ((F32_BIAS - 1) shl F32_EXP_SHIFT))
    var y = xx0.toFloat()
    y *= 1.0f / (1 shl 23).toFloat()
    return y - 124.22544637f - 1.498030302f * xx1 - 1.72587999f / (0.3520887068f + xx1)
}

internal fun ourExp2f(x: Float): Float {
    val xf = floorf(x)
    val ystep = Float.fromBits((127 + xf.toInt()) shl 23)
    val x1 = x - xf
    val xt = x1 - 0.5f
    val c0 = 1.4142135623730951f
    val c1 = 0.9802581434685472f
    val c2 = 0.3397315841830749f
    val c3 = 0.07849466324122069f
    val c4 = 0.013602088628663625f
    val ytaylor = c0 + xt * (c1 + xt * (c2 + xt * (c3 + xt * c4)))
    val m0 = 0.9999443187818418f
    val m1 = 1.0000312541780023f
    return ystep * ytaylor * (m0 + (m1 - m0) * x1)
}

internal fun ourPowf(x: Float, y: Float): Float {
    if (x < 0.0f) return Float.NaN
    if (x == 0.0f) return 0.0f
    if (y == 0.0f) return if (x >= 0.0f) 1.0f else -1.0f
    return ourExp2f(y * ourLog2f(x))
}

internal fun ourExpm1f(x: Float): Float {
    val log2OfE = 1.4426950408889634f
    return ourExp2f(x * log2OfE) - 1.0f
}

/** `pow10`: 10 to the power of [x], through `our_exp2f`. */
internal fun pow10f(x: Float): Float {
    val log2Of10 = 3.321928094887362f
    return ourExp2f(x * log2Of10)
}

/** Wraps [x0] into the range of -pi to pi. */
private fun modpi(x0: Float): Float {
    var x = x0
    x += FPI
    x *= (0.5f / FPI)
    x -= floorf(x)
    x *= (2.0f * FPI)
    x -= FPI
    return x
}

/** A sine made of a parabola. */
internal fun parSinf(x0: Float): Float {
    val x = modpi(x0)
    return 0.40528473456935094f * x * (FPI - fabsf(x))
}

internal fun parCosf(x: Float): Float = parSinf(x + (0.5f * FPI))

/** `our_sinf`: a Padé approximant, on an argument folded into one quarter wave. */
internal fun ourSinf(x0: Float): Float {
    val x = (4.0f * fabsf(x0 * (.5f / FPI) - floorf(x0 * (.5f / FPI) + (3.0f / 4.0f)) + (1.0f / 4.0f)) - 1.0f) * (FPI / 2.0f)
    val c0 = 1.0f
    val c1 = 1.0f
    val c2 = (445.0f / 12122.0f)
    val c3 = -(2363.0f / 18183.0f)
    val c4 = (601.0f / 872784.0f)
    val c5 = (12671.0f / 4363920.0f)
    val c6 = (121.0f / 16662240.0f)
    val xx = x * x
    val num = x * (c1 + xx * (c3 + xx * c5))
    val denom = c0 + xx * (c2 + xx * (c4 + xx * c6))
    return num / denom
}

/** `lerp` of the envelopes: linear for exponent 0, ease-in for a positive one, ease-out for a negative one. */
internal fun lerp(x: Float, y0: Float, y1: Float, exponent: Float): Float {
    if (x <= 0f) return y0
    if (x >= 1f) return y1
    val curvedX = if (exponent == 0f) x else if (exponent > 0f) ourPowf(x, exponent) else 1f - ourPowf(1f - x, -exponent)
    return y0 + (y1 - y0) * curvedX
}

/** `midi2freq`: a MIDI note number as a frequency, through the approximation of `exp2`. */
internal fun midi2freq(midi: Float): Float = (ourExp2f(((midi.toDouble() - 69.0) / 12.0).toFloat()).toDouble() * 440.0).toFloat()
