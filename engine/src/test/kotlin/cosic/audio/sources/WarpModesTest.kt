package cosic.audio.sources

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WarpModesTest {
    private fun warp(mode: Double, amount: Double, phase: Double) = WarpModes.warp(phase, amount, mode)

    private fun assertWarp(expected: Double, mode: Double, amount: Double, phase: Double, tolerance: Double = 1e-9) =
        assertEquals(expected, warp(mode, amount, phase), tolerance, "mode $mode amount $amount phase $phase")

    @Test
    fun theSpecVectorsOfEveryMode() {
        assertWarp(0.25, 1.0, 0.5, 0.2525)
        assertWarp(0.75, 1.0, 0.5, 0.7525)
        assertWarp(0.5, 2.0, 0.5, 0.2525)
        assertWarp(0.0625, 3.0, 1.0, 0.5)
        assertWarp(0.5, 4.0, 1.0, 0.0625)
        assertWarp(0.6, 6.0, 0.5, 0.3, 1e-12)
        assertWarp(0.296875, 7.0, 0.5, 0.3)
        assertWarp(0.5, 8.0, 0.5, 0.15, 1e-12)
        assertWarp(0.25, 9.0, 0.5, 0.25)
        assertWarp(0.75, 9.0, 0.5, 0.75)
        assertWarp(0.75, 10.0, 1.0, 0.25)
        assertWarp(0.246946, 11.0, 0.5, 0.1, 1e-6)
        assertWarp(0.75, 12.0, 1.0, 0.25)
        assertWarp(0.25, 17.0, 1.0, 0.05)
        assertWarp(0.5, 17.0, 1.0, 0.5)
        assertWarp(0.75, 17.0, 1.0, 0.95)
        assertWarp(0.9, 18.0, 0.0, 0.5)
        assertWarp(0.0, 19.0, 0.0, 0.0)
        assertWarp(0.5, 19.0, 0.0, 0.5)
        assertWarp(1.0, 19.0, 0.0, 1.0)
        assertWarp(0.66667, 16.0, 0.0, 0.5, 1e-5)
        assertWarp(0.75, 20.0, 1.0, 0.25)
        assertWarp(0.0078125, 14.0, 0.5, 0.25)
        assertWarp(90.0 / 181.0, 13.0, 0.5, 0.5)
    }

    @Test
    fun noneFlipAndNonModesLeaveThePhase() {
        for (mode in doubleArrayOf(0.0, 21.0, 22.0, 2.5, -1.0, Double.NaN)) assertEquals(0.3, warp(mode, 0.8, 0.3), 0.0, "mode $mode")
    }

    @Test
    fun theHashHelpersMatchTheWorklet() {
        val v = WavetableOracleVectors
        for ((k, input) in v.hashInputs.withIndex()) {
            assertEquals(v.hash32[k], WarpHash.hash32(input), "hash32($input)")
            assertEquals(v.hash01[k], WarpHash.hash01(input), 0.0, "hash01($input)")
        }
        assertEquals(0x6B4ED927L, WarpHash.hash32(0))
        for ((k, x) in v.noiseInputs.withIndex()) {
            assertEquals(v.noise[k], WarpHash.valueNoise(x), 1e-15, "noise($x)")
            assertEquals(v.brownian[k], WarpHash.brownian(x, 4), 1e-15, "brownian($x)")
        }
    }

    @Test
    fun everyModeMatchesTheWorkletOnAGrid() {
        val v = WavetableOracleVectors
        var k = 0
        for (mode in v.warpModes) for (amount in v.warpAmounts) for (phase in v.warpPhases) {
            assertEquals(v.warp[k++], warp(mode, amount, phase), 1e-12, "mode $mode amount $amount phase $phase")
        }
        assertEquals(v.warp.size, k)
    }

    @Test
    fun bitReverseReversesTheLowBits() {
        assertEquals(0b011, WarpHash.bitReverse(0b110, 3))
        assertEquals(0b0001, WarpHash.bitReverse(0b1000, 4))
        assertEquals(0, WarpHash.bitReverse(0, 5))
    }

    @Test
    fun nonePhaseStaysInUnitRangeForTheModesThatShouldKeepIt() {
        for (mode in 0..21) for (amount in listOf(0.0, 0.3, 1.0)) for (i in 0 until 100) {
            val w = warp(mode.toDouble(), amount, i / 100.0)
            assertTrue(w.isFinite(), "mode $mode amount $amount phase ${i / 100.0}: $w")
        }
    }

    @Test
    fun warpModeControlsAreNamesInAnyCaseOrNumbers() {
        assertEquals(1.0, WarpModes.fromControl("asym"))
        assertEquals(21.0, WarpModes.fromControl("Flip"))
        assertEquals(15.0, WarpModes.fromControl("BROWNIAN"))
        assertEquals(0.0, WarpModes.fromControl("nope"))
        assertEquals(0.0, WarpModes.fromControl(null))
        assertEquals(7.0, WarpModes.fromControl(7.0))
        assertEquals(2.5, WarpModes.fromControl(2.5))
    }
}
