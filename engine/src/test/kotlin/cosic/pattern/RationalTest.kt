package cosic.pattern

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RationalTest {
    @Test
    fun normalisesByGcd() {
        val x = Rational(2, 4)
        assertEquals(1L, x.num)
        assertEquals(2L, x.den)
    }

    @Test
    fun keepsDenominatorPositive() {
        assertEquals(-1L, Rational(1, -2).num)
        assertEquals(2L, Rational(1, -2).den)
        assertEquals(1L, Rational(-1, -2).num)
        assertEquals(2L, Rational(-1, -2).den)
    }

    @Test
    fun zeroIsZeroOverOne() {
        val z = Rational(0, 5)
        assertEquals(0L, z.num)
        assertEquals(1L, z.den)
        assertEquals(Rational.ZERO, z)
    }

    @Test
    fun zeroDenominatorThrows() {
        assertFailsWith<ArithmeticException> { Rational(1, 0) }
        assertFailsWith<ArithmeticException> { Rational.ONE / Rational.ZERO }
    }

    @Test
    fun arithmetic() {
        assertEquals(Rational(5, 6), Rational(1, 2) + Rational(1, 3))
        assertEquals(Rational(1, 6), Rational(1, 2) - Rational(1, 3))
        assertEquals(Rational(1, 2), Rational(2, 3) * Rational(3, 4))
        assertEquals(Rational(2), Rational(1, 2) / Rational(1, 4))
        assertEquals(Rational(-1, 2), -Rational(1, 2))
    }

    @Test
    fun overflowThrows() {
        assertFailsWith<ArithmeticException> { Rational(Long.MAX_VALUE) + Rational(1) }
        assertFailsWith<ArithmeticException> { Rational(Long.MAX_VALUE) * Rational(2) }
        assertFailsWith<ArithmeticException> { Rational(1, Long.MAX_VALUE) * Rational(1, 3) }
    }

    @Test
    fun comparesByValue() {
        assertTrue(Rational(1, 3) < Rational(1, 2))
        assertTrue(Rational(-1, 2) < Rational(1, 3))
        assertTrue(Rational(7, 2) > Rational(3))
        assertEquals(0, Rational(2, 4).compareTo(Rational(1, 2)))
        assertEquals(Rational(1, 2), Rational(3, 6))
        assertEquals(Rational(1, 2).hashCode(), Rational(3, 6).hashCode())
    }

    @Test
    fun floorIsTheCycleNumber() {
        assertEquals(3L, Rational(7, 2).floor())
        assertEquals(-1L, Rational(-1, 2).floor())
        assertEquals(3L, Rational(3).floor())
        assertEquals(-2L, Rational(-3, 2).floor())
    }

    @Test
    fun cyclePosIsTheFractionalPart() {
        assertEquals(Rational(1, 2), Rational(7, 2).cyclePos())
        assertEquals(Rational(1, 2), Rational(-1, 2).cyclePos())
        assertEquals(Rational.ZERO, Rational(4).cyclePos())
    }

    @Test
    fun convertsToDouble() {
        assertEquals(0.25, Rational(1, 4).toDouble())
        assertEquals(-1.5, Rational(-3, 2).toDouble())
    }

    @Test
    fun constants() {
        assertEquals(Rational(0), Rational.ZERO)
        assertEquals(Rational(1), Rational.ONE)
    }

    @Test
    fun toStringIsCompact() {
        assertEquals("1/2", Rational(1, 2).toString())
        assertEquals("3", Rational(3).toString())
        assertEquals("-3/4", Rational(3, -4).toString())
    }

    @Test
    fun ofApproximatesDoubles() {
        assertEquals(Rational(1, 2), Rational.of(0.5))
        assertEquals(Rational(1, 10), Rational.of(0.1))
        assertEquals(Rational(1, 3), Rational.of(1.0 / 3.0))
        assertEquals(Rational(2, 3), Rational.of(2.0 / 3.0))
        assertEquals(Rational(-3, 4), Rational.of(-0.75))
        assertEquals(Rational(3), Rational.of(3.0))
        assertEquals(Rational(3, 2), Rational.of(1.5))
        assertEquals(Rational.ZERO, Rational.of(0.0))
    }

    @Test
    fun ofLimitsTheDenominatorTo10000() {
        val pi = Rational.of(Math.PI)
        assertEquals(Rational(355, 113), pi)
        assertTrue(Rational.of(0.123456789123).den <= 10_000)
    }

    @Test
    fun ofRejectsNonFiniteNumbers() {
        for (bad in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val e = assertFailsWith<IllegalArgumentException> { Rational.of(bad) }
            assertEquals("Invalid number", e.message)
        }
    }

    @Test
    fun ofRejectsNumbersTooLargeToBeFractions() {
        assertEquals("Invalid number", assertFailsWith<IllegalArgumentException> { Rational.of(1e19) }.message)
    }

    @Test
    fun ofKeepsIntegersAndSimpleFractionsExact() {
        assertEquals(Rational(1_000_000), Rational.of(1e6))
        assertEquals(Rational(1, 4), Rational.of(0.25))
        assertEquals(Rational(-5, 4), Rational.of(-1.25))
    }
}
