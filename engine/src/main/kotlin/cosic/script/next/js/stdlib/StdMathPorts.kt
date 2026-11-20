package cosic.script.next.js.stdlib

import kotlin.math.abs
import kotlin.math.sqrt

/*
 * The functions of `Math` that Java's `StrictMath` (fdlibm 5.3) answers differently from V8 in the last bit, ported from
 * V8's src/base/ieee754.cc (fdlibm and FreeBSD's msun) so that a program computes the same numbers as it does in a browser:
 * `exp` (V8 answers `exp(1)` with E, the algorithm's last bit being wrong), `cosh` (which uses that `exp`), `log2`, `asinh`,
 * `acosh`, `atanh`, `cbrt`, `tanh`, `atan2` and `hypot`. Every other function of `Math` that V8 takes from fdlibm agrees
 * with `StrictMath` bit for bit (measured over 20000 inputs each, see StdlibMathTest).
 */

internal fun v8Exp(x: Double): Double = if (x == 1.0) Math.E else StrictMath.exp(x)

/** V8's `cosh`: fdlibm's, but through [v8Exp], so that `cosh(1)` is the number V8 prints. */
internal fun v8Cosh(x: Double): Double {
    val ix = highWord(x) and 0x7fffffff
    if (ix < 0x3FD62E43) {
        val t = StrictMath.expm1(abs(x))
        val w = 1.0 + t
        if (ix < 0x3C800000) return w
        return 1.0 + (t * t) / (w + w)
    }
    if (ix < 0x40360000) {
        val t = v8Exp(abs(x))
        return 0.5 * t + 0.5 / t
    }
    if (ix < 0x40862E42) return 0.5 * v8Exp(abs(x))
    if (abs(x) <= 710.4758600739439) {
        val w = v8Exp(0.5 * abs(x))
        val t = 0.5 * w
        return t * w
    }
    if (ix >= 0x7ff00000) return x * x
    return Double.POSITIVE_INFINITY
}

private fun highWord(x: Double): Int = (x.toRawBits() ushr 32).toInt()

private fun lowWord(x: Double): Int = x.toRawBits().toInt()

private fun withHighWord(x: Double, hi: Int): Double = Double.fromBits((hi.toLong() shl 32) or (x.toRawBits() and 0xFFFFFFFFL))

private fun fromWords(hi: Int, lo: Int): Double = Double.fromBits((hi.toLong() shl 32) or (lo.toLong() and 0xFFFFFFFFL))

// ---- log2 -------------------------------------------------------------------------------------------------------------

private const val LG1 = 6.666666666666735130e-01
private const val LG2 = 3.999999999940941908e-01
private const val LG3 = 2.857142874366239149e-01
private const val LG4 = 2.222219843214978396e-01
private const val LG5 = 1.818357216161805012e-01
private const val LG6 = 1.531383769920937332e-01
private const val LG7 = 1.479819860511658591e-01

/** FreeBSD's `k_log1p`: log(1+f) - f + f*f/2 for f in the reduced range, which `log2` builds on. */
private fun kLog1p(f: Double): Double {
    val s = f / (2.0 + f)
    val z = s * s
    val w = z * z
    val t1 = w * (LG2 + w * (LG4 + w * LG6))
    val t2 = z * (LG1 + w * (LG3 + w * (LG5 + w * LG7)))
    val r = t2 + t1
    val hfsq = 0.5 * f * f
    return s * (hfsq + r)
}

internal fun v8Log2(x0: Double): Double {
    val two54 = 1.80143985094819840000e+16
    val ivln2hi = 1.44269504072144627571e+00
    val ivln2lo = 1.67517131648865118353e-10
    var x = x0
    var hx = highWord(x)
    val lx = lowWord(x)
    var k = 0
    if (hx < 0x00100000) {
        if (((hx and 0x7fffffff) or lx) == 0) return Double.NEGATIVE_INFINITY
        if (hx < 0) return Double.NaN
        k -= 54
        x *= two54
        hx = highWord(x)
    }
    if (hx >= 0x7ff00000) return x + x
    if (hx == 0x3ff00000 && lx == 0) return 0.0
    k += (hx shr 20) - 1023
    hx = hx and 0x000fffff
    val i = (hx + 0x95f64) and 0x100000
    x = withHighWord(x, hx or (i xor 0x3ff00000))
    k += i shr 20
    val y = k.toDouble()
    val f = x - 1.0
    val hfsq = 0.5 * f * f
    val r = kLog1p(f)
    val hi = fromWords(highWord(f - hfsq), 0)
    val lo = (f - hi) - hfsq + r
    var valHi = hi * ivln2hi
    var valLo = (lo + hi) * ivln2lo + lo * ivln2hi
    val w = y + valHi
    valLo += (y - w) + valHi
    valHi = w
    return valLo + valHi
}

// ---- asinh, acosh, atanh -----------------------------------------------------------------------------------------------

private const val LN2 = 6.93147180559945286227e-01

internal fun v8Asinh(x: Double): Double {
    val hx = highWord(x)
    val ix = hx and 0x7fffffff
    if (ix >= 0x7ff00000) return x + x
    if (ix < 0x3e300000 && 1.0e300 + x > 1.0) return x
    val w: Double = when {
        ix > 0x41b00000 -> StrictMath.log(abs(x)) + LN2
        ix > 0x40000000 -> {
            val t = abs(x)
            StrictMath.log(2.0 * t + 1.0 / (sqrt(x * x + 1.0) + t))
        }
        else -> {
            val t = x * x
            StrictMath.log1p(abs(x) + t / (1.0 + sqrt(1.0 + t)))
        }
    }
    return if (hx > 0) w else -w
}

internal fun v8Acosh(x: Double): Double {
    val hx = highWord(x)
    return when {
        hx < 0x3ff00000 -> Double.NaN
        hx >= 0x41b00000 -> if (hx >= 0x7ff00000) x + x else StrictMath.log(x) + LN2
        ((hx - 0x3ff00000) or lowWord(x)) == 0 -> 0.0
        hx > 0x40000000 -> {
            val t = x * x
            StrictMath.log(2.0 * x - 1.0 / (x + sqrt(t - 1.0)))
        }
        else -> {
            val t = x - 1.0
            StrictMath.log1p(t + sqrt(2.0 * t + t * t))
        }
    }
}

internal fun v8Atanh(x0: Double): Double {
    var x = x0
    val hx = highWord(x)
    val lx = lowWord(x)
    val ix = hx and 0x7fffffff
    if ((ix or ((lx or -lx) ushr 31)) > 0x3ff00000) return Double.NaN
    if (ix == 0x3ff00000) return x / 0.0
    if (ix < 0x3e300000 && 1.0e300 + x > 0.0) return x
    x = withHighWord(x, ix)
    val t = if (ix < 0x3fe00000) {
        val d = x + x
        0.5 * StrictMath.log1p(d + d * x / (1.0 - x))
    } else {
        0.5 * StrictMath.log1p((x + x) / (1.0 - x))
    }
    return if (hx >= 0) t else -t
}

// ---- cbrt, tanh -------------------------------------------------------------------------------------------------------

/** FreeBSD's `cbrt` (error below 0.667 ulp), which is what V8 has; fdlibm 5.3's differs in about one result in twelve. */
internal fun v8Cbrt(x: Double): Double {
    val b1 = 715094163
    val b2 = 696219795
    val p0 = 1.87595182427177009643
    val p1 = -1.88497979543377169875
    val p2 = 1.621429720105354466140
    val p3 = -0.758397934778766047437
    val p4 = 0.145996192886612446982
    var hx = highWord(x)
    val low = lowWord(x)
    val sign = hx and Int.MIN_VALUE
    hx = hx xor sign
    if (hx >= 0x7ff00000) return x + x
    var t: Double
    if (hx < 0x00100000) {
        if ((hx or low) == 0) return x
        t = fromWords(0x43500000, 0)
        t *= x
        val high = highWord(t)
        t = fromWords(sign or ((high and 0x7fffffff) / 3 + b2), 0)
    } else {
        t = fromWords(sign or (hx / 3 + b1), 0)
    }
    var r = (t * t) * (t / x)
    t *= ((p0 + r * (p1 + r * p2)) + ((r * r) * r) * (p3 + r * p4))
    t = Double.fromBits((t.toRawBits() + 0x80000000L) and -0x40000000L)
    val s = t * t
    r = x / s
    val w = t + t
    r = (r - t) / (w + r)
    t += t * r
    return t
}

internal fun v8Tanh(x: Double): Double {
    val jx = highWord(x)
    val ix = jx and 0x7fffffff
    if (ix >= 0x7ff00000) return if (jx >= 0) 1.0 / x + 1.0 else 1.0 / x - 1.0
    val z: Double
    if (ix < 0x40360000) {
        if (ix < 0x3e300000 && 1.0e300 + x > 1.0) return x
        if (ix >= 0x3ff00000) {
            val t = StrictMath.expm1(2.0 * abs(x))
            z = 1.0 - 2.0 / (t + 2.0)
        } else {
            val t = StrictMath.expm1(-2.0 * abs(x))
            z = -t / (t + 2.0)
        }
    } else {
        z = 1.0
    }
    return if (jx >= 0) z else -z
}

// ---- atan2, hypot -------------------------------------------------------------------------------------------------------

/** FreeBSD's `atan2`: unlike fdlibm 5.3 (and so `StrictMath`), it answers a ratio beyond 2^60 as the quadrant's right angle. */
internal fun v8Atan2(y: Double, x: Double): Double {
    val piO4 = 7.8539816339744827900E-01
    val piO2 = 1.5707963267948965580E+00
    val pi = 3.1415926535897931160E+00
    val piLo = 1.2246467991473531772E-16
    val hx = highWord(x)
    val ix = hx and 0x7fffffff
    val lx = lowWord(x)
    val hy = highWord(y)
    val iy = hy and 0x7fffffff
    val ly = lowWord(y)
    if (((ix or ((lx or -lx) ushr 31)) > 0x7ff00000) || ((iy or ((ly or -ly) ushr 31)) > 0x7ff00000)) return x + y
    if (((hx - 0x3ff00000) or lx) == 0) return StrictMath.atan(y)
    var m = ((hy ushr 31) and 1) or ((hx ushr 30) and 2)
    if ((iy or ly) == 0) {
        return when (m) {
            0, 1 -> y
            2 -> pi
            else -> -pi
        }
    }
    if ((ix or lx) == 0) return if (hy < 0) -piO2 else piO2
    if (ix == 0x7ff00000) {
        return if (iy == 0x7ff00000) {
            when (m) {
                0 -> piO4
                1 -> -piO4
                2 -> 3.0 * piO4
                else -> -3.0 * piO4
            }
        } else {
            when (m) {
                0 -> 0.0
                1 -> -0.0
                2 -> pi
                else -> -pi
            }
        }
    }
    if (iy == 0x7ff00000) return if (hy < 0) -piO2 else piO2
    val k = (iy - ix) shr 20
    val z: Double
    if (k > 60) {
        z = piO2 + 0.5 * piLo
        m = m and 1
    } else if (hx < 0 && k < -60) {
        z = 0.0
    } else {
        z = StrictMath.atan(abs(y / x))
    }
    return when (m) {
        0 -> z
        1 -> -z
        2 -> pi - (z - piLo)
        else -> (z - piLo) - pi
    }
}

/** V8's `Math.hypot`: the values scaled by the largest, squared and added with Kahan's compensation, so that nothing overflows. */
internal fun v8Hypot(values: DoubleArray): Double {
    if (values.isEmpty()) return 0.0
    var max = 0.0
    var nan = false
    val abs = DoubleArray(values.size)
    for (i in values.indices) {
        val v = values[i]
        if (v.isNaN()) {
            nan = true
        } else {
            abs[i] = abs(v)
            if (abs[i] > max) max = abs[i]
        }
    }
    if (max == Double.POSITIVE_INFINITY) return max
    if (nan) return Double.NaN
    if (max == 0.0) return 0.0
    var sum = 0.0
    var compensation = 0.0
    for (v in abs) {
        val n = v / max
        val summand = n * n - compensation
        val preliminary = sum + summand
        compensation = (preliminary - sum) - summand
        sum = preliminary
    }
    return sqrt(sum) * max
}
