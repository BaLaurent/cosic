package cosic.pattern

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SignalsTest {
    private fun valueAt(p: Pattern, t: Rational): Double = p.query(span(t, t)).single().value as Double

    private fun assertNear(expected: Double, actual: Double, message: String = "") =
        assertEquals(expected, actual, 1e-9, message)

    @Test
    fun signalHapsHaveNoWholeAndSpanTheQuery() {
        val hap = sine.query(span(r(1, 4), r(3, 4))).single()
        assertNull(hap.whole)
        assertEquals(span(r(1, 4), r(3, 4)), hap.part)
    }

    @Test
    fun signalsAreSampledAtTheQueryMidpoint() {
        assertNear(1.0, sine.query(span(r(0), r(1, 2))).single().value as Double)
        assertNear(0.5, sine.query(span(r(0), r(1))).single().value as Double)
    }

    @Test
    fun sineShape() {
        assertNear(0.5, valueAt(sine, r(0)))
        assertNear(1.0, valueAt(sine, r(1, 4)))
        assertNear(0.5, valueAt(sine, r(1, 2)))
        assertNear(0.0, valueAt(sine, r(3, 4)))
    }

    @Test
    fun cosineIsSineShiftedByAQuarterCycle() {
        assertNear(1.0, valueAt(cosine, r(0)))
        assertNear(0.5, valueAt(cosine, r(1, 4)))
        assertNear(0.0, valueAt(cosine, r(1, 2)))
    }

    @Test
    fun sawRisesAndIsawFalls() {
        assertNear(0.0, valueAt(saw, r(0)))
        assertNear(0.25, valueAt(saw, r(1, 4)))
        assertNear(0.75, valueAt(saw, r(3, 4)))
        assertNear(0.0, valueAt(saw, r(1)))
        assertNear(1.0, valueAt(isaw, r(0)))
        assertNear(0.75, valueAt(isaw, r(1, 4)))
        assertNear(0.25, valueAt(isaw, r(3, 4)))
    }

    @Test
    fun triangleShape() {
        assertNear(0.0, valueAt(tri, r(0)))
        assertNear(0.5, valueAt(tri, r(1, 4)))
        assertNear(1.0, valueAt(tri, r(1, 2)))
        assertNear(0.5, valueAt(tri, r(3, 4)))
    }

    @Test
    fun squareShape() {
        assertNear(0.0, valueAt(square, r(0)))
        assertNear(0.0, valueAt(square, r(1, 4)))
        assertNear(1.0, valueAt(square, r(1, 2)))
        assertNear(1.0, valueAt(square, r(3, 4)))
    }

    @Test
    fun timeRandMatchesTheReferenceValues() {
        assertEquals(0.8833108082136426, timeRand(r(0), 0))
        assertEquals(0.09998659737280235, timeRand(r(1, 2), 0))
        assertEquals(0.3200032599920952, timeRand(r(3), 0))
        assertEquals(0.28888348593139546, timeRand(r(1, 3), 7))
        assertEquals(0.43152799704850997, timeRand(r(0), 1))
        assertEquals(0.18929208816899945, timeRand(r(5), 1))
        assertEquals(0.9015462036136921, timeRand(r(1), 1000))
        assertEquals(0.36048264483096715, timeRand(r(-1, 2), 0))
    }

    @Test
    fun timeRandStaysInTheUnitInterval() {
        for (i in -500..500) {
            val v = timeRand(r(i.toLong(), 37), i % 5)
            assertTrue(v >= 0.0 && v < 1.0, "timeRand out of range: $v")
        }
    }

    @Test
    fun timeRandDependsOnSeedAndTime() {
        assertNotEquals(timeRand(r(1, 2), 0), timeRand(r(1, 2), 1))
        assertNotEquals(timeRand(r(1, 2), 0), timeRand(r(3, 4), 0))
        assertEquals(timeRand(r(1, 2), 3), timeRand(r(2, 4), 3))
    }

    @Test
    fun randIsTimeRandOverTime() {
        assertEquals(timeRand(r(3, 7), 0), valueAt(rand, r(3, 7)))
        assertEquals(valueAt(rand, r(3, 7)), valueAt(rand, r(3, 7)))
    }

    @Test
    fun randIsRoughlyUniform() {
        val n = 4000
        val values = (0 until n).map { valueAt(rand, r(it.toLong(), 1000)) }
        val mean = values.average()
        assertTrue(abs(mean - 0.5) < 0.03, "mean was $mean")
        assertTrue(values.all { it >= 0.0 && it < 1.0 })
        for (bucket in 0 until 4) {
            val share = values.count { it >= bucket / 4.0 && it < (bucket + 1) / 4.0 } / n.toDouble()
            assertTrue(abs(share - 0.25) < 0.04, "bucket $bucket share was $share")
        }
    }

    @Test
    fun irandProducesIntegersBelowN() {
        val seen = (0 until 400).map { valueAt(irand(8.0), r(it.toLong(), 97)) }
        assertTrue(seen.all { it == Math.floor(it) && it >= 0.0 && it < 8.0 })
        assertEquals((0..7).map { it.toDouble() }.toSet(), seen.toSet())
    }

    @Test
    fun perlinInterpolatesBetweenCycleRandoms() {
        assertEquals(timeRand(r(3), 0), valueAt(perlin, r(3)))
        val mid = valueAt(perlin, r(7, 2))
        assertNear((timeRand(r(3), 0) + timeRand(r(4), 0)) / 2, mid)
    }

    @Test
    fun perlinIsContinuous() {
        var previous = valueAt(perlin, r(0))
        for (i in 1..2000) {
            val v = valueAt(perlin, r(i.toLong(), 400))
            assertTrue(abs(v - previous) < 0.05, "jump of ${abs(v - previous)} at step $i")
            previous = v
        }
    }
}
