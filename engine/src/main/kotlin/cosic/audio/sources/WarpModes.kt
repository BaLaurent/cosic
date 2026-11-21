package cosic.audio.sources

/*
 * The phase warps of the wavetable oscillator (`_warpPhase` of `wavetable-oscillator-processor`, superdough worklets.mjs:1213-1331) and the
 * hash and noise helpers they use (worklets.mjs:1118-1158). Pure functions of a phase in [0, 1) and the warp amount in [0, 1], computed in doubles
 * as the worklet does.
 */

private const val TWO_PI = 2.0 * Math.PI

/** `x | 0`: truncation to an integer. The values warped here are small, so no 32-bit wrap-around is involved. */
private fun ffloor(x: Double): Double = x.toInt().toDouble()

/** `ffloor(x + 0.5)`. */
private fun fround(x: Double): Double = ffloor(x + 0.5)

/** `x - ffloor(x)`: the truncating fraction (negative for a negative `x`). */
private fun ffrac(x: Double): Double = x - ffloor(x)

/** `x - Math.floor(x)`. */
private fun frac(x: Double): Double = x - Math.floor(x)

private fun clamp(x: Double, lo: Double, hi: Double): Double = Math.min(Math.max(x, lo), hi)

private fun mirror(x: Double): Double = 1.0 - Math.abs(2.0 * x - 1.0)

/** The hash and noise helpers of the warps (worklets.mjs:1118-1158). */
internal object WarpHash {
    /** `hash32` (worklets.mjs:1118): 32-bit integer hash; the result is the unsigned value of the 32 bits. */
    internal fun hash32(input: Int): Long {
        var u = input
        u = u + 0x7ed55d16 + (u shl 12)
        u = u xor 0xc761c23c.toInt() xor (u ushr 19)
        u = u + 0x165667b1 + (u shl 5)
        u = (u + 0xd3a2646c.toInt()) xor (u shl 9)
        u = u + 0xfd7046c5.toInt() + (u shl 3)
        u = u xor 0xb55a4f09.toInt() xor (u ushr 16)
        return u.toLong() and 0xFFFFFFFFL
    }

    /** `hash01(i) = (hash32(i) >>> 8) / 2^24`: in `[0, 1)`. */
    internal fun hash01(i: Int): Double = (hash32(i) ushr 8).toDouble() / 16777216.0

    /** `noise(x)` (worklets.mjs:1142): value noise, the hash of the integer points interpolated linearly. */
    internal fun valueNoise(x: Double): Double {
        val fl = Math.floor(x)
        val f = x - fl
        val i = fl.toInt()
        val a = hash01(i)
        val b = hash01(i + 1)
        return a + (b - a) * f
    }

    /** `brownian(x, oct)` (worklets.mjs:1149): [octaves] octaves of [valueNoise], weights `0.5^(o+1)`, scaled to `[-1, 1]`. */
    internal fun brownian(x: Double, octaves: Int = 4): Double {
        var amp = 0.5
        var sum = 0.0
        var norm = 0.0
        var freq = 1.0
        for (o in 0 until octaves) {
            sum += amp * valueNoise(x * freq)
            norm += amp
            amp *= 0.5
            freq *= 2.0
        }
        return (sum / norm) * 2.0 - 1.0
    }

    /** `bitReverse(i, n)` (worklets.mjs:1129): the lowest [n] bits of [i], in reverse order. */
    internal fun bitReverse(i: Int, n: Int): Int {
        var r = 0
        var v = i
        for (b in 0 until n) {
            r = (r shl 1) or (v and 1)
            v = v ushr 1
        }
        return r
    }
}

private fun isPrime(n: Double): Boolean {
    if (n < 2.0) return false
    if (n % 2.0 == 0.0) return n == 2.0
    var d = 3.0
    while (d * d <= n) {
        if (n % d == 0.0) return false
        d += 2.0
    }
    return true
}

/** The warp modes of the wavetable oscillator, by number (`Warpmode` of wavetable.mjs:17-40). */
internal object WarpModes {
    const val NONE = 0.0
    const val FLIP = 21.0

    /** The names of the modes and their numbers, in the order of `Warpmode`; the global of that name is made from it. */
    internal val NAMES = mapOf(
        "NONE" to 0.0, "ASYM" to 1.0, "MIRROR" to 2.0, "BENDP" to 3.0, "BENDM" to 4.0, "BENDMP" to 5.0, "SYNC" to 6.0, "QUANT" to 7.0,
        "FOLD" to 8.0, "PWM" to 9.0, "ORBIT" to 10.0, "SPIN" to 11.0, "CHAOS" to 12.0, "PRIMES" to 13.0, "BINARY" to 14.0,
        "BROWNIAN" to 15.0, "RECIPROCAL" to 16.0, "WORMHOLE" to 17.0, "LOGISTIC" to 18.0, "SIGMOID" to 19.0, "FRACTAL" to 20.0, "FLIP" to 21.0,
    )

    /**
     * The mode a `warpmode` control asks for: a name (any case) is looked up and an unknown one is [NONE]; a number is the mode as it is (one that
     * is no mode warps nothing); anything else, including no control, is [NONE].
     */
    fun fromControl(v: Any?): Double = when (v) {
        is String -> NAMES[v.uppercase()] ?: NONE
        is Number -> v.toDouble()
        else -> NONE
    }

    /** The warped phase for [phase] in `[0, 1)`, warp [amount] in `[0, 1]` and [mode] (see the table of the spec). */
    fun warp(phase: Double, amount: Double, mode: Double): Double {
        val amt = amount
        val m = mode.toInt()
        if (m.toDouble() != mode) return phase
        return when (m) {
            1 -> asym(phase, amt)
            2 -> mirror(asym(phase, amt))
            3 -> Math.pow(phase, 1.0 + 3.0 * amt)
            4 -> Math.pow(phase, 1.0 / (1.0 + 3.0 * amt))
            5 -> if (amt < 0.5) warp(phase, 1.0 - 2.0 * amt, 3.0) else warp(phase, 2.0 * amt - 1.0, 2.0)
            6 -> (phase * Math.pow(16.0, amt * amt)) % 1.0
            7 -> {
                val n = toBitsN(amt, 2.0, 12.0)
                ffloor(phase * n) / n
            }
            8 -> {
                val k = 1.0 + Math.max(1.0, fround(7.0 * amt))
                Math.abs(ffrac(k * phase) - 0.5) * 2.0
            }
            9 -> {
                val w = clamp(0.5 + 0.49 * (2.0 * amt - 1.0), 0.0, 1.0)
                if (phase < w) (phase / w) * 0.5 else 0.5 + ((phase - w) / (1.0 - w)) * 0.5
            }
            10 -> frac(phase + 0.5 * amt * Math.sin(TWO_PI * 3.0 * phase))
            11 -> {
                val n = toBitsN(amt, 1.0, 6.0)
                frac(phase + 0.5 * amt * Math.sin(TWO_PI * n * phase))
            }
            12 -> {
                val r = 3.7 + 0.3 * amt
                clamp((1.0 - amt) * phase + amt * (r * phase * (1.0 - phase)), 0.0, 1.0)
            }
            13 -> {
                var n = toBitsN(amt, 3.0, 12.0)
                while (!isPrime(n)) n += 1.0
                ffloor(phase * n) / n
            }
            14 -> {
                val b = fround(toBitsB(amt, 3.0, 12.0)).toInt()
                val n = 1 shl b
                val idx = ffloor(phase * n).toInt()
                WarpHash.bitReverse(idx, b).toDouble() / n
            }
            15 -> frac(phase + 0.25 * amt * WarpHash.brownian(64.0 * phase, 4))
            16 -> {
                val g = 2.0 + 4.0 * amt
                val den = phase + (1.0 - phase) * g
                clamp(if (den > 1e-12) phase * g / den else 0.0, 0.0, 1.0)
            }
            17 -> {
                val gap = clamp(0.8 * amt, 0.0, 1.0)
                val a = 0.5 * (1.0 - gap)
                val b = 0.5 * (1.0 + gap)
                if (phase < a) (phase / a) * 0.5 else if (phase > b) 0.5 * (1.0 + (phase - b) / (1.0 - b)) else 0.5
            }
            18 -> {
                var x = phase
                val r = 3.6 + 0.4 * amt
                val iters = (1.0 + fround(2.0 * amt)).toInt()
                for (i in 0 until iters) x = r * x * (1.0 - x)
                clamp(x, 0.0, 1.0)
            }
            19 -> {
                val k = 1.0 + 10.0 * amt
                val y = 1.0 / (1.0 + Math.exp(-k * (phase - 0.5)))
                val y0 = 1.0 / (1.0 + Math.exp(0.5 * k))
                val y1 = 1.0 / (1.0 + Math.exp(-0.5 * k))
                (y - y0) / (y1 - y0)
            }
            20 -> frac(phase + 0.5 * Math.sin(TWO_PI * phase) * amt)
            else -> phase
        }
    }

    private fun asym(phase: Double, amt: Double): Double {
        val a = 0.01 + 0.99 * amt
        return if (phase < a) (0.5 * phase) / a else 0.5 + (0.5 * (phase - a)) / (1.0 - a)
    }

    /** `_toBits(amt, min, max).b`. */
    private fun toBitsB(amt: Double, min: Double, max: Double): Double = max + (min - max) * amt

    /** `_toBits(amt, min, max).n`: `fround(2^b)`. */
    private fun toBitsN(amt: Double, min: Double, max: Double): Double = fround(Math.pow(2.0, toBitsB(amt, min, max)))
}
