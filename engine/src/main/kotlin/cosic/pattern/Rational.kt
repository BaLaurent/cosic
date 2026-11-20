package cosic.pattern

import java.math.BigDecimal
import java.math.BigInteger
import java.util.concurrent.atomic.AtomicReferenceArray

/**
 * An exact fraction, always normalised: `gcd(num, den) == 1` and `den > 0`. This is Strudel's `Fraction`
 * (fraction.js 5.2.1), which has no overflow: arithmetic runs on `Long`s and, when a result does not fit, on
 * `BigInteger`s, going back to `Long`s as soon as a result fits again. There is no public numerator or denominator;
 * read a fraction with [show], [toDouble], [floor], [signum], [isZero] and [isInteger].
 *
 * Build values with `Rational(num, den)`, [of] or the constants.
 */
internal class Rational private constructor(
    private val n: Long,
    private val d: Long,
    private val bn: BigInteger?,
    private val bd: BigInteger?,
) : Comparable<Rational> {

    // Invariant: bn == null means the value is n/d with d > 0, lowest terms and n != Long.MIN_VALUE; otherwise the
    // value is bn/bd in lowest terms and does not fit that form.

    private fun numBig(): BigInteger = bn ?: BigInteger.valueOf(n)

    private fun denBig(): BigInteger = bd ?: BigInteger.valueOf(d)

    operator fun plus(o: Rational): Rational {
        if (bn == null && o.bn == null) {
            try {
                if (d == o.d) return reduced(Math.addExact(n, o.n), d)
                val g = gcd(d, o.d)
                val nn = Math.addExact(Math.multiplyExact(n, o.d / g), Math.multiplyExact(o.n, d / g))
                return reduced(nn, Math.multiplyExact(d / g, o.d))
            } catch (_: ArithmeticException) {
                // too big for a Long: below
            }
        }
        return ofBig(numBig() * o.denBig() + o.numBig() * denBig(), denBig() * o.denBig())
    }

    operator fun minus(o: Rational): Rational {
        if (bn == null && o.bn == null) {
            try {
                if (d == o.d) return reduced(Math.subtractExact(n, o.n), d)
                val g = gcd(d, o.d)
                val nn = Math.subtractExact(Math.multiplyExact(n, o.d / g), Math.multiplyExact(o.n, d / g))
                return reduced(nn, Math.multiplyExact(d / g, o.d))
            } catch (_: ArithmeticException) {
                // too big for a Long: below
            }
        }
        return ofBig(numBig() * o.denBig() - o.numBig() * denBig(), denBig() * o.denBig())
    }

    operator fun times(o: Rational): Rational {
        if (bn == null && o.bn == null) {
            if (n == 0L || o.n == 0L) return ZERO
            try {
                // Cross-reduce first: the product is then already in lowest terms.
                val g1 = gcd(Math.abs(n), o.d)
                val g2 = gcd(Math.abs(o.n), d)
                return small(Math.multiplyExact(n / g1, o.n / g2), Math.multiplyExact(d / g2, o.d / g1))
            } catch (_: ArithmeticException) {
                // too big for a Long: below
            }
        }
        return ofBig(numBig() * o.numBig(), denBig() * o.denBig())
    }

    /** Throws `Division by Zero` for a zero divisor, like fraction.js. */
    operator fun div(o: Rational): Rational {
        if (o.isZero()) throw CosicError("Division by Zero")
        return this * o.inverse()
    }

    operator fun unaryMinus(): Rational = if (bn == null) small(-n, d) else Rational(0, 1, bn.negate(), bd)

    fun abs(): Rational = if (signum() < 0) -this else this

    private fun inverse(): Rational =
        if (bn == null) (if (n < 0) small(-d, -n) else small(d, n)) else ofBig(bd!!, bn)

    /**
     * The remainder of the division by [o], truncated like JavaScript's `%`: it takes the sign of the dividend
     * (`-1/4 mod 1` is `-1/4`) and ignores the sign of the divisor. A zero divisor is `Division by Zero`.
     */
    fun mod(o: Rational): Rational {
        if (o.isZero()) throw CosicError("Division by Zero")
        if (bn == null && o.bn == null) {
            try {
                val num = Math.multiplyExact(o.d, n) % Math.multiplyExact(Math.abs(o.n), d)
                return reduced(num, Math.multiplyExact(o.d, d))
            } catch (_: ArithmeticException) {
                // too big for a Long: below
            }
        }
        val num = (o.denBig() * numBig()).remainder(o.numBig().abs() * denBig())
        return ofBig(num, o.denBig() * denBig())
    }

    /** The start of the cycle this time is in (`floor`), as a fraction. */
    fun sam(): Rational {
        if (bn == null) return if (d == 1L) this else small(Math.floorDiv(n, d), 1L)
        return ofBig(floorBig(), BigInteger.ONE)
    }

    /** The start of the next cycle. */
    fun nextSam(): Rational = sam() + ONE

    /** The position inside the cycle, in `[0, 1)`. */
    fun cyclePos(): Rational {
        if (bn == null) return if (d == 1L) ZERO else small(Math.floorMod(n, d), d)
        return this - sam()
    }

    /** The greatest integer not above this value: the cycle number. Throws [CosicError] beyond the range of a `Long`. */
    fun floor(): Long {
        if (bn == null) return Math.floorDiv(n, d)
        val q = floorBig()
        if (!fits(q)) throw CosicError("number out of range")
        return q.toLong()
    }

    private fun floorBig(): BigInteger {
        val qr = numBig().divideAndRemainder(denBig())
        return if (qr[1].signum() < 0) qr[0] - BigInteger.ONE else qr[0]
    }

    /** `this.lt(o) ? this : o`, which is what fraction.js does for `min`. */
    fun min(o: Rational): Rational = if (this < o) this else o

    /** `this.gt(o) ? this : o`. */
    fun max(o: Rational): Rational = if (this > o) this else o

    /** The fractional least common multiple, `lcm(a/b, c/d) = lcm(a, c) / gcd(b, d)`; the lcm with 0 is 0 (fraction.js 5.2.1). */
    fun lcm(o: Rational): Rational {
        val a = numBig().abs()
        val c = o.numBig().abs()
        if (a.signum() == 0 && c.signum() == 0) return ZERO
        return ofBig(c * a, c.gcd(a) * o.denBig().gcd(denBig()))
    }

    /** The fractional greatest common divisor, `gcd(a/b, c/d) = gcd(a, c) / lcm(b, d)`. */
    fun gcd(o: Rational): Rational {
        val a = numBig().abs()
        val c = o.numBig().abs()
        return ofBig(c.gcd(a) * o.denBig().gcd(denBig()), o.denBig() * denBig())
    }

    fun signum(): Int = if (bn == null) java.lang.Long.signum(n) else bn.signum()

    fun isZero(): Boolean = bn == null && n == 0L

    fun isInteger(): Boolean = bn == null && d == 1L || bd == BigInteger.ONE

    /** Fraction.js' `valueOf`: the numerator as a double divided by the denominator as a double. */
    fun toDouble(): Double = if (bn == null) n.toDouble() / d.toDouble() else bn.toDouble() / bd!!.toDouble()

    override fun compareTo(other: Rational): Int {
        if (bn == null && other.bn == null) {
            if (d == other.d) return n.compareTo(other.n)
            try {
                return Math.multiplyExact(n, other.d).compareTo(Math.multiplyExact(other.n, d))
            } catch (_: ArithmeticException) {
                // too big for a Long: below
            }
        }
        return (numBig() * other.denBig()).compareTo(other.numBig() * denBig())
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Rational) return false
        return if (bn == null) other.bn == null && n == other.n && d == other.d else bn == other.bn && bd == other.bd
    }

    override fun hashCode(): Int = if (bn == null) 31 * n.hashCode() + d.hashCode() else 31 * bn.hashCode() + bd.hashCode()

    /** Fraction.js' `show` as Strudel prints it: always `numerator/denominator`, never a bare integer (`0/1`, `-1/2`, `3/1`). */
    fun show(): String = if (bn == null) "$n/$d" else "$bn/$bd"

    override fun toString(): String = show()

    /** `String(fraction)`: the decimal expansion, with the repeating digits in parentheses (`1/3` is `0.(3)`). */
    fun toJsString(): String {
        var num = numBig().abs()
        val den = denBig()
        val cycleLength = cycleLength(den)
        val out = StringBuilder(if (signum() < 0) "-" else "")
        out.append(num.divide(den))
        num = num.remainder(den) * TEN
        if (num.signum() != 0) out.append('.')
        fun digit() {
            out.append(num.divide(den))
            num = num.remainder(den) * TEN
        }
        if (cycleLength > 0) {
            repeat(cycleStart(den, cycleLength)) { digit() }
            out.append('(')
            repeat(cycleLength) { digit() }
            out.append(')')
        } else {
            var left = DECIMAL_PLACES
            while (num.signum() != 0 && left-- > 0) digit()
        }
        return out.toString()
    }

    companion object {
        val ZERO = Rational(0, 1, null, null)
        val ONE = Rational(1, 1, null, null)

        private val TEN = BigInteger.TEN
        private const val DECIMAL_PLACES = 15
        private const val MAX_CYCLE_LENGTH = 2000
        private val MAX_SMALL = BigInteger.valueOf(Long.MAX_VALUE)
        private val MIN_SMALL = BigInteger.valueOf(-Long.MAX_VALUE)

        /** `num/den` in lowest terms. A zero [den] is `Division by Zero`. */
        operator fun invoke(num: Long, den: Long = 1L): Rational = try {
            reduced(num, den)
        } catch (_: ArithmeticException) {
            ofBig(BigInteger.valueOf(num), BigInteger.valueOf(den))
        }

        /** An already normalised small fraction. Throws [ArithmeticException] for the one `Long` that has no negation. */
        private fun small(num: Long, den: Long): Rational {
            if (num == Long.MIN_VALUE) throw ArithmeticException("long overflow")
            return Rational(num, den, null, null)
        }

        /** `num/den` for [den] != 0, reduced; throws [ArithmeticException] when the result needs more than a `Long`. */
        private fun reduced(num: Long, den: Long): Rational {
            if (den == 0L) throw CosicError("Division by Zero")
            if (num == Long.MIN_VALUE || den == Long.MIN_VALUE) throw ArithmeticException("long overflow")
            if (den == 1L) return if (num == 0L) ZERO else small(num, 1L)
            var nn = num
            var dd = den
            if (dd < 0) {
                nn = -nn
                dd = -dd
            }
            val g = gcd(Math.abs(nn), dd)
            return if (g == 1L) small(nn, dd) else small(nn / g, dd / g)
        }

        private fun fits(x: BigInteger): Boolean = x <= MAX_SMALL && x >= MIN_SMALL

        /** `num/den` for any integers, reduced, demoted to a small fraction when it fits. */
        private fun ofBig(num: BigInteger, den: BigInteger): Rational {
            if (den.signum() == 0) throw CosicError("Division by Zero")
            var nn = num
            var dd = den
            if (dd.signum() < 0) {
                nn = nn.negate()
                dd = dd.negate()
            }
            val g = nn.gcd(dd)
            if (g != BigInteger.ONE) {
                nn /= g
                dd /= g
            }
            return if (fits(nn) && fits(dd)) small(nn.toLong(), dd.toLong()) else Rational(0, 1, nn, dd)
        }

        private fun gcd(a: Long, b: Long): Long {
            var x = a
            var y = b
            while (y != 0L) {
                val t = x % y
                x = y
                y = t
            }
            return x
        }

        /**
         * Strudel's `Fraction(x)`: a fraction stays as it is, a number becomes the nearest fraction found by [of],
         * `null` and `undefined` are 0, a string is parsed (`"0.5"`, `"3/4"`, `"1.(3)"`), a list is `[numerator, denominator]`.
         * Anything else (booleans, NaN, maps) is `Invalid argument`.
         */
        fun of(x: Any?): Rational = when (x) {
            is Rational -> x
            is Double -> of(x)
            is Int -> invoke(x.toLong())
            is Long -> invoke(x)
            is Number -> of(x.toDouble())
            null, Undefined -> ZERO
            is String -> parse(x)
            is List<*> -> fromList(x)
            else -> throw CosicError("Invalid argument")
        }

        private const val MEMO_SIZE = 1024
        private class Memo(val bits: Long, val value: Rational)

        private val memo = AtomicReferenceArray<Memo?>(MEMO_SIZE)

        /** Powers of ten that a double represents exactly. */
        private val POW10 = DoubleArray(23).also { var p = 1.0; for (i in it.indices) { it[i] = p; p *= 10.0 } }

        /**
         * `Fraction(number)`: whole numbers are exact; any other number is turned into the closest fraction by walking
         * the Farey sequence up to a denominator of 10 000 000, exactly as fraction.js does (`0.1` is `1/10`, `0.12345`
         * is `2469/20000`, `1e-8` is `0/1`). The walk can take millions of steps, so results are memoised.
         */
        fun of(x: Double): Rational {
            if (x.isNaN()) throw CosicError("Invalid argument")
            val bits = x.toRawBits()
            val slot = ((bits * -7046029254386353131L) ushr 54).toInt()
            memo.get(slot)?.let { if (it.bits == bits) return it.value }
            val value = farey(x)
            memo.set(slot, Memo(bits, value))
            return value
        }

        private fun farey(x: Double): Rational {
            var p = x
            var negative = false
            if (p < 0.0) {
                negative = true
                p = -p
            }
            if (p.isInfinite()) throw CosicError("Invalid argument")
            val num: BigInteger
            val den: BigInteger
            if (p % 1.0 == 0.0) {
                num = BigDecimal(p).toBigIntegerExact()
                den = BigInteger.ONE
            } else {
                // p > 0 and not a whole number
                var z = 1.0
                val limit = 10_000_000.0
                var a = 0.0
                var b = 1.0
                var c = 1.0
                var dd = 1.0
                if (p >= 1.0) {
                    z = POW10[Math.floor(1 + StrictMath.log10(p)).toInt()]
                    p /= z
                }
                var fn = 0.0
                var fd = 1.0
                while (b <= limit && dd <= limit) {
                    val m = (a + c) / (b + dd)
                    if (p == m) {
                        if (b + dd <= limit) {
                            fn = a + c
                            fd = b + dd
                        } else if (dd > b) {
                            fn = c
                            fd = dd
                        } else {
                            fn = a
                            fd = b
                        }
                        break
                    } else {
                        if (p > m) {
                            a += c
                            b += dd
                        } else {
                            c += a
                            dd += b
                        }
                        if (b > limit) {
                            fn = c
                            fd = dd
                        } else {
                            fn = a
                            fd = b
                        }
                    }
                }
                num = BigInteger.valueOf(fn.toLong()) * BigDecimal(z).toBigIntegerExact()
                den = BigInteger.valueOf(fd.toLong())
            }
            return ofBig(if (negative) num.negate() else num, den)
        }

        private fun fromList(list: List<*>): Rational {
            if (list.isEmpty()) throw CosicError("Invalid argument")
            val num = bigIntOf(list[0])
            val den = if (list.size > 1) bigIntOf(list[1]) else BigInteger.ONE
            return ofBig(num, den)
        }

        /** `BigInt(value)` for the values a fraction list can hold. */
        private fun bigIntOf(v: Any?): BigInteger = when {
            v is Double && v % 1.0 == 0.0 && v.isFinite() -> BigDecimal(v).toBigIntegerExact()
            v is Number && v !is Double && v !is Float -> BigInteger.valueOf(v.toLong())
            v is String && v.isNotBlank() && v.trim().all { it.isDigit() } -> BigInteger(v.trim())
            v is Boolean -> if (v) BigInteger.ONE else BigInteger.ZERO
            else -> throw CosicError("Invalid argument")
        }

        /** The string forms of fraction.js: `"1234"`, `"0.5"`, `".5"`, `"1.(3)"`, `"1.'3'"`, `"3/4"`, `"3:4"`, `"1 3/4"`, optionally signed, with `_` ignored. */
        private fun parse(text: String): Rational {
            val tokens = Regex("\\d+|.").findAll(text.replace("_", "")).map { it.value }.toList()
            if (tokens.isEmpty()) throw CosicError("Invalid argument")
            fun token(i: Int): String? = tokens.getOrNull(i)
            fun big(i: Int, sign: BigInteger): BigInteger {
                val t = token(i) ?: throw CosicError("Invalid argument")
                if (t.isNotEmpty() && !t.all { it in '0'..'9' }) {
                    // BigInt(" ") is 0n; any other non-digit token is an error.
                    if (t.isBlank()) return BigInteger.ZERO
                    throw CosicError("Invalid argument")
                }
                return BigInteger(t) * sign
            }
            var i = 0
            var sign = BigInteger.ONE
            var v = BigInteger.ZERO
            var w = BigInteger.ZERO
            var x = BigInteger.ZERO
            var y = BigInteger.ONE
            var z = BigInteger.ONE
            if (token(i) == "-") {
                sign = BigInteger.ONE.negate()
                i++
            } else if (token(i) == "+") {
                i++
            }
            if (tokens.size == i + 1) {
                w = big(i++, sign)
            } else if (token(i + 1) == "." || token(i) == ".") {
                if (token(i) != ".") v = big(i++, sign)
                i++
                if (i + 1 == tokens.size || token(i + 1) == "(" && token(i + 3) == ")" || token(i + 1) == "'" && token(i + 3) == "'") {
                    w = big(i, sign)
                    y = TEN.pow(token(i)!!.length)
                    i++
                }
                if (token(i) == "(" && token(i + 2) == ")" || token(i) == "'" && token(i + 2) == "'") {
                    x = big(i + 1, sign)
                    z = TEN.pow(token(i + 1)!!.length) - BigInteger.ONE
                    i += 3
                }
            } else if (token(i + 1) == "/" || token(i + 1) == ":") {
                w = big(i, sign)
                y = big(i + 2, BigInteger.ONE)
                i += 3
            } else if (token(i + 3) == "/" && token(i + 1) == " ") {
                v = big(i, sign)
                w = big(i + 2, sign)
                y = big(i + 4, BigInteger.ONE)
                i += 5
            }
            if (tokens.size > i) throw CosicError("Invalid argument")
            val den = y * z
            return ofBig(x + den * v + z * w, den)
        }

        /** Strudel's `lcm(...fractions)`: `undefined` entries are ignored and an empty list has no lcm (null). */
        fun lcmOf(xs: List<Rational?>): Rational? {
            val ys = xs.filterNotNull()
            if (ys.isEmpty()) return null
            var acc = ys.last()
            for (i in 0 until ys.size - 1) acc = acc.lcm(ys[i])
            return acc
        }

        /** Strudel's `gcd(...fractions)`: the fold starts from 1, so the result is at most 1; no entries is null. */
        fun gcdOf(xs: List<Rational?>): Rational? {
            val ys = xs.filterNotNull()
            if (ys.isEmpty()) return null
            return ys.fold(ONE) { acc, r -> acc.gcd(r) }
        }

        private fun cycleLength(den: BigInteger): Int {
            var dd = den
            val two = BigInteger.TWO
            val five = BigInteger.valueOf(5)
            while (dd.mod(two).signum() == 0) dd /= two
            while (dd.mod(five).signum() == 0) dd /= five
            if (dd == BigInteger.ONE) return 0
            var rem = TEN.mod(dd)
            var t = 1
            while (rem != BigInteger.ONE) {
                rem = (rem * TEN).mod(dd)
                if (t > MAX_CYCLE_LENGTH) return 0
                t++
            }
            return t
        }

        private fun cycleStart(den: BigInteger, length: Int): Int {
            var rem1 = BigInteger.ONE
            var rem2 = TEN.modPow(BigInteger.valueOf(length.toLong()), den)
            for (t in 0 until 300) {
                if (rem1 == rem2) return t
                rem1 = (rem1 * TEN).mod(den)
                rem2 = (rem2 * TEN).mod(den)
            }
            return 0
        }
    }
}
