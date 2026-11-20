package cosic.pattern

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The vectors of the kernel specification for `Rational` (Strudel's `Fraction`, fraction.js 5.2.1). */
class RationalSpecTest {
    @Test
    fun fareyConversionOfDoubles() {
        val vectors = listOf(
            0.5 to "1/2", 0.1 to "1/10", 0.3 to "3/10", 0.75 to "3/4", -0.75 to "-3/4", 1.5 to "3/2", 2.5 to "5/2",
            1.1 to "11/10", 0.12345 to "2469/20000", 1e-8 to "0/1", 0.0 to "0/1", -0.0 to "0/1", 4.0 to "4/1", 1e21 to "1000000000000000000000/1",
        )
        for ((x, text) in vectors) assertEquals(text, Rational.of(x).show(), "Rational.of($x)")
    }

    @Test
    fun conversionsAreMemoisedAndRepeatable() {
        val a = Rational.of(0.30000000000000004)
        assertEquals(a, Rational.of(0.30000000000000004))
        assertEquals("3/10", a.show())
    }

    @Test
    fun ofAnyValue() {
        assertSame(Rational.ONE, Rational.of(Rational.ONE))
        assertEquals(Rational.ZERO, Rational.of(null))
        assertEquals(Rational.ZERO, Rational.of(Undefined))
        assertEquals(Rational(3), Rational.of(3))
        assertEquals(Rational(3), Rational.of(3L))
        assertEquals(Rational(3, 4), Rational.of(listOf(3.0, 4.0)))
        for (bad in listOf<Any?>(true, mapOf("a" to 1.0), emptyList<Any?>(), "x", "1/0x")) {
            assertEquals("Invalid argument", assertFailsWith<CosicError> { Rational.of(bad) }.message, "$bad")
        }
    }

    @Test
    fun ofStrings() {
        val vectors = listOf(
            "1234" to "1234/1", "0.5" to "1/2", ".5" to "1/2", "-0.25" to "-1/4", "+3" to "3/1", "3/4" to "3/4", "3:4" to "3/4",
            "1.(3)" to "4/3", "1.'3'" to "4/3", "1 3/4" to "7/4", "1_000" to "1000/1", "-1 1/2" to "-3/2",
        )
        for ((text, shown) in vectors) assertEquals(shown, Rational.of(text).show(), "Rational.of(\"$text\")")
        assertEquals("Division by Zero", assertFailsWith<CosicError> { Rational.of("1/0") }.message)
    }

    @Test
    fun modIsTruncatedLikeJavaScript() {
        assertEquals("-1/4", Rational(-1, 4).mod(Rational(1)).show())
        assertEquals("1/4", Rational(5, 4).mod(Rational(1)).show())
        assertEquals("1/4", Rational(5, 4).mod(Rational(-1)).show())
        assertEquals("5/6", Rational(13, 3).mod(Rational(7, 8)).show())
        assertEquals("Division by Zero", assertFailsWith<CosicError> { Rational.ONE.mod(Rational.ZERO) }.message)
    }

    @Test
    fun lcmAndGcdOfFractions() {
        assertEquals("3/2", Rational(1, 2).lcm(Rational(3, 4)).show())
        assertEquals("0/1", Rational(0).lcm(Rational(2)).show())
        assertEquals("0/1", Rational(0).lcm(Rational(0)).show())
        assertEquals("1/56", Rational(5, 8).gcd(Rational(3, 7)).show())
        assertEquals("15/1", Rational(5, 8).lcm(Rational(3, 7)).show())
        assertEquals(Rational(6), Rational.lcmOf(listOf(null, Rational(2), Rational(3))))
        assertNull(Rational.lcmOf(emptyList()))
        assertNull(Rational.lcmOf(listOf(null)))
        assertEquals(Rational(1, 2), Rational.gcdOf(listOf(Rational(1, 2), Rational(3, 2))))
    }

    @Test
    fun arithmeticNeverOverflows() {
        assertEquals("27670116110564327421/1", (Rational(Long.MAX_VALUE) * Rational(3)).show())
        val big = Rational(Long.MAX_VALUE) + Rational(1)
        assertEquals("9223372036854775808/1", big.show())
        assertEquals(Rational(Long.MAX_VALUE), big - Rational(1), "a result that fits is a small fraction again")
        assertEquals(Rational(Long.MIN_VALUE), -big, "-2^63 has no negation as a Long")
        assertEquals(1.0, (big / big).toDouble())
        assertTrue(big > Rational(Long.MAX_VALUE) && -big < Rational(Long.MIN_VALUE + 1))
        assertEquals(Rational(1, 3) * Rational(3), Rational(1))
    }

    @Test
    fun floorBeyondALongThrows() {
        val big = Rational(Long.MAX_VALUE) * Rational(4)
        assertEquals("number out of range", assertFailsWith<CosicError> { big.floor() }.message)
        assertEquals("36893488147419103228/1", big.sam().show())
    }

    @Test
    fun samNextSamAndCyclePos() {
        assertEquals("3/1", Rational(7, 2).sam().show())
        assertEquals("4/1", Rational(7, 2).nextSam().show())
        assertEquals("1/2", Rational(7, 2).cyclePos().show())
        assertEquals("-1/1", Rational(-1, 2).sam().show())
        assertEquals("1/2", Rational(-1, 2).cyclePos().show())
        assertEquals("0/1", Rational(4).cyclePos().show())
    }

    @Test
    fun minAndMaxReturnTheOtherOperandWhenEqual() {
        val a = Rational(1, 2)
        val b = Rational(2, 4)
        assertSame(b, a.min(b))
        assertSame(b, a.max(b))
        assertSame(a, a.min(Rational(1)))
        assertSame(a, a.max(Rational(1, 3)))
    }

    @Test
    fun jsStringForm() {
        assertEquals("0.(3)", Rational(1, 3).toJsString())
        assertEquals("0.5", Rational(1, 2).toJsString())
        assertEquals("-1.75", Rational(-7, 4).toJsString())
        assertEquals("3", Rational(3).toJsString())
        assertEquals("0.1(6)", Rational(1, 6).toJsString())
    }

    @Test
    fun comparisonAcrossRepresentations() {
        val big = Rational(Long.MAX_VALUE) * Rational(5, 3)
        assertTrue(big > Rational(Long.MAX_VALUE) && Rational(Long.MAX_VALUE) < big)
        assertEquals(big, Rational(Long.MAX_VALUE) * Rational(5) / Rational(3))
        assertEquals(big.hashCode(), (Rational(Long.MAX_VALUE) * Rational(5) / Rational(3)).hashCode())
    }
}
