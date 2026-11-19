package cosic.pattern

import kotlin.math.abs

/**
 * An exact fraction, always normalised: `gcd(num, den) == 1` and `den > 0`.
 *
 * Arithmetic is exact. Overflow throws [ArithmeticException] instead of wrapping, so a runaway pattern
 * fails loudly rather than playing garbage. Build values with `Rational(num, den)`, [of] or the constants.
 */
internal class Rational private constructor(val num: Long, val den: Long) : Comparable<Rational> {

    operator fun plus(other: Rational): Rational {
        if (den == other.den) return reduced(Math.addExact(num, other.num), den)
        val g = gcd(den, other.den)
        val d = Math.multiplyExact(den / g, other.den)
        val n = Math.addExact(Math.multiplyExact(num, other.den / g), Math.multiplyExact(other.num, den / g))
        return reduced(n, d)
    }

    operator fun minus(other: Rational): Rational = this + -other

    operator fun times(other: Rational): Rational {
        if (num == 0L || other.num == 0L) return ZERO
        // Cross-reduce first: the product is then already in lowest terms and overflows later.
        val g1 = gcd(magnitude(num), other.den)
        val g2 = gcd(magnitude(other.num), den)
        return Rational(
            Math.multiplyExact(num / g1, other.num / g2),
            Math.multiplyExact(den / g2, other.den / g1),
        )
    }

    operator fun div(other: Rational): Rational {
        if (other.num == 0L) throw ArithmeticException("Division by zero")
        return this * if (other.num < 0) Rational(Math.negateExact(other.den), -other.num) else Rational(other.den, other.num)
    }

    operator fun unaryMinus(): Rational = Rational(Math.negateExact(num), den)

    /** The cycle number: the greatest integer not above this value ("sam"). */
    fun floor(): Long = Math.floorDiv(num, den)

    /** The position inside the cycle, in `[0, 1)`. */
    fun cyclePos(): Rational = if (den == 1L) ZERO else Rational(Math.floorMod(num, den), den)

    fun toDouble(): Double = num.toDouble() / den.toDouble()

    override fun compareTo(other: Rational): Int =
        if (den == other.den) num.compareTo(other.num)
        else Math.multiplyExact(num, other.den).compareTo(Math.multiplyExact(other.num, den))

    override fun equals(other: Any?): Boolean = other is Rational && num == other.num && den == other.den

    override fun hashCode(): Int = 31 * num.hashCode() + den.hashCode()

    override fun toString(): String = if (den == 1L) "$num" else "$num/$den"

    companion object {
        val ZERO = Rational(0, 1)
        val ONE = Rational(1, 1)

        private const val MAX_DENOMINATOR = 10_000L

        operator fun invoke(num: Long, den: Long = 1L): Rational = reduced(num, den)

        private fun reduced(num: Long, den: Long): Rational {
            if (den == 0L) throw ArithmeticException("Division by zero")
            if (den == 1L) return if (num == 0L) ZERO else Rational(num, 1)
            val n = if (den < 0) Math.negateExact(num) else num
            val d = if (den < 0) Math.negateExact(den) else den
            val g = gcd(magnitude(n), d)
            return if (g == 1L) Rational(n, d) else Rational(n / g, d / g)
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

        private fun magnitude(x: Long): Long = if (x < 0) Math.negateExact(x) else x

        /**
         * The closest fraction with a denominator of at most 10 000, found with continued fractions
         * (stops early once the error is below 1e-9). Throws [IllegalArgumentException] for non-finite
         * or absurdly large input.
         */
        fun of(d: Double): Rational {
            require(d.isFinite() && abs(d) < 2.0e18) { "Invalid number" }
            val x = abs(d)
            var h0 = 0L
            var k0 = 1L
            var h1 = 1L
            var k1 = 0L
            var rest = x
            while (true) {
                val whole = Math.floor(rest)
                if (k1 > 0 && whole > MAX_DENOMINATOR) break
                val a = whole.toLong()
                val k2 = Math.addExact(Math.multiplyExact(a, k1), k0)
                if (k2 > MAX_DENOMINATOR) break
                val h2 = Math.addExact(Math.multiplyExact(a, h1), h0)
                h0 = h1
                k0 = k1
                h1 = h2
                k1 = k2
                val frac = rest - whole
                if (frac == 0.0 || abs(x - h1.toDouble() / k1.toDouble()) < 1e-9) break
                rest = 1.0 / frac
            }
            return Rational(if (d < 0) -h1 else h1, k1)
        }
    }
}
