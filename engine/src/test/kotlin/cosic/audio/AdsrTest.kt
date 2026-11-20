package cosic.audio

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** `getADSRValues` and `getParamADSR` (helpers.mjs:55-114, 182-193) on AudioParam automation. */
class AdsrTest {
    private val n = Double.NaN

    private fun values(a: Double, d: Double, s: Double, r: Double, defaults: DoubleArray? = null) = getADSRValues(a, d, s, r, defaults)

    @Test
    fun envelopeDefaultsDependOnWhichValuesWereSet() {
        assertContentEquals(doubleArrayOf(0.001, 0.001, 1.0, 0.01), values(n, n, n, n))
        assertContentEquals(doubleArrayOf(0.1, 0.001, 1.0, 0.01), values(0.1, n, n, n))
        assertContentEquals(doubleArrayOf(0.001, 0.2, 0.001, 0.01), values(n, 0.2, n, n))
        assertContentEquals(doubleArrayOf(0.1, 0.2, 0.001, 0.01), values(0.1, 0.2, n, n))
        assertContentEquals(doubleArrayOf(0.001, 0.001, 0.5, 0.01), values(n, n, 0.5, n))
        assertContentEquals(doubleArrayOf(0.001, 0.001, 1.0, 0.5), values(n, n, n, 0.5))
        assertEquals(0.01, values(n, n, n, 0.001)[3], "the release has a minimum of 10 ms")
        assertEquals(1.0, values(n, n, 2.0, n)[2], "the sustain is capped at 1")
        assertContentEquals(doubleArrayOf(0.005, 0.14, 0.0, 0.1), values(n, n, n, n, doubleArrayOf(0.005, 0.14, 0.0, 0.1)))
        assertContentEquals(doubleArrayOf(0.001, 0.05, 0.6, 0.01), values(n, n, n, n, doubleArrayOf(0.001, 0.05, 0.6, 0.01)))
        assertContentEquals(doubleArrayOf(0.001, 0.001, 0.0, 0.01), values(0.0, 0.0, 0.0, 0.0), "(0, 0, 0, 0) is all set: sustain 0 is kept")
    }

    @Test
    fun theDefaultsAreIgnoredOnceAnyValueIsSet() {
        val defaults = doubleArrayOf(0.005, 0.14, 0.0, 0.1)
        assertContentEquals(doubleArrayOf(0.2, 0.001, 1.0, 0.01), values(0.2, n, n, n, defaults))
    }

    private fun linear(a: Double, d: Double, s: Double, r: Double, begin: Double, end: Double): AParam {
        val p = AParam(0.0, -3.4e38, 3.4e38)
        getParamADSR(p, a, d, s, r, 0.0, 1.0, begin, end, exponential = false)
        return p
    }

    @Test
    fun aLinearEnvelopeFollowsAttackDecaySustainAndRelease() {
        val p = linear(0.1, 0.2, 0.5, 0.3, begin = 1.0, end = 2.0)
        val expected = listOf(1.0 to 0.0, 1.05 to 0.5, 1.1 to 1.0, 1.2 to 0.75, 1.3 to 0.5, 1.9 to 0.5, 2.0 to 0.5, 2.15 to 0.25, 2.3 to 0.0, 3.0 to 0.0)
        for ((t, v) in expected) assertEquals(v, p.valueAt(t), 1e-9, "at $t")
        assertEquals(0.0, p.valueAt(0.5), "before the start the parameter keeps its base value")
    }

    @Test
    fun anEnvelopeCutShortByTheGateStopsOnTheLineItWasOn() {
        // gate shorter than the attack: 0.05 s of the 0.1 s attack, then the release from there
        val inAttack = linear(0.1, 0.2, 0.5, 0.3, begin = 1.0, end = 1.05)
        assertEquals(0.5, inAttack.valueAt(1.05), 1e-9)
        assertEquals(0.25, inAttack.valueAt(1.2), 1e-9)
        // gate ends 0.1 s into the decay
        val inDecay = linear(0.1, 0.2, 0.5, 0.3, begin = 1.0, end = 1.2)
        assertEquals(1.0, inDecay.valueAt(1.1), 1e-9)
        assertEquals(0.75, inDecay.valueAt(1.2), 1e-9)
        assertEquals(0.0, inDecay.valueAt(1.5), 1e-9)
    }

    @Test
    fun aZeroLengthGateStillGoesThroughItsRelease() {
        val p = linear(0.001, 0.001, 1.0, 0.01, begin = 1.0, end = 1.0)
        assertEquals(0.0, p.valueAt(1.0), 1e-9)
        assertEquals(0.0, p.valueAt(1.5), 1e-9)
    }

    private fun exponential(a: Double, d: Double, s: Double, r: Double, min: Double, max: Double, begin: Double, end: Double): AParam {
        val p = AParam(min, 0.0, 3.4e38)
        getParamADSR(p, a, d, s, r, min, max, begin, end, exponential = true)
        return p
    }

    @Test
    fun anExponentialFilterEnvelopeRisesToItsPeakAndDecaysThroughTheGeometricMean() {
        // lpf 300 with lpenv 4: from 300 up to 4800 over 5 ms, back to 300 over 140 ms
        val p = exponential(0.005, 0.14, 0.0, 0.1, 300.0, 4800.0, begin = 10.0, end = 11.0)
        assertEquals(300.0, p.valueAt(10.0), 1e-9)
        assertEquals(4800.0, p.valueAt(10.005), 1e-6)
        assertEquals(1200.0, p.valueAt(10.075), 0.5)
        assertEquals(300.0, p.valueAt(10.145), 1e-6)
        assertEquals(300.0, p.valueAt(10.6), 1e-6, "the sustain of 0 is the minimum")
        // anchor 1: from 18.75 to 300
        val low = exponential(0.005, 0.14, 0.0, 0.1, 18.75, 300.0, begin = 0.0, end = 1.0)
        assertEquals(300.0, low.valueAt(0.005), 1e-6)
        // a negative env swaps the two: the cutoff starts high, dips to the low value and comes back
        val negative = exponential(0.005, 0.14, 0.0, 0.1, 1200.0, 300.0, begin = 0.0, end = 1.0)
        assertEquals(1200.0, negative.valueAt(0.0), 1e-6)
        assertEquals(300.0, negative.valueAt(0.005), 1e-6)
        assertEquals(1200.0, negative.valueAt(0.145), 1e-6)
    }

    @Test
    fun exponentialRampsNeverStartOrEndAtZero() {
        val p = AParam(0.0, 0.0, 3.4e38)
        getParamADSR(p, 0.01, 0.01, 0.0, 0.01, 0.0, 0.0, 0.0, 1.0, exponential = true)
        assertEquals(0.001, p.valueAt(0.0), 1e-12, "min 0 becomes 0.001")
        assertEquals(0.001, p.valueAt(0.5), 1e-12)
    }

    @Test
    fun nanControlsCountAsZeroWhichMakesAZeroDecayHoldThePeak() {
        val p = AParam(0.0, -3.4e38, 3.4e38)
        getParamADSR(p, Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0.0, 1.0, 0.0, 1.0, exponential = false)
        // attack 0, decay 0, sustain 0, release 0: the slope of a zero-length decay is 0, so the envelope sits on its peak until the gate ends
        assertEquals(1.0, p.valueAt(0.5), 1e-9)
        assertEquals(0.0, p.valueAt(1.0), 1e-9)
    }
}
