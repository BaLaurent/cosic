package cosic.player

import cosic.pattern.Rational
import kotlin.test.Test
import kotlin.test.assertEquals

class TempoAnchorTest {
    private val anchor = TempoAnchor(frame = 2400, cycle = Rational.ZERO, cps = 0.5, sampleRate = 48_000)

    @Test
    fun theAnchorCycleSoundsAtTheAnchorFrame() {
        assertEquals(2400L, anchor.frameAt(Rational.ZERO))
    }

    @Test
    fun aCycleLastsSampleRateOverCpsFrames() {
        assertEquals(2400L + 96_000, anchor.frameAt(Rational.ONE))
        assertEquals(2400L + 24_000, anchor.frameAt(Rational(1, 4)))
        assertEquals(2400L + 72_000, anchor.frameAt(Rational(3, 4)))
        assertEquals(2400L + 3 * 96_000, anchor.frameAt(Rational(3)))
    }

    @Test
    fun framesAreRoundedToTheNearest() {
        val odd = TempoAnchor(0, Rational.ZERO, cps = 0.7, sampleRate = 48_000) // 68 571.43 frames per cycle
        assertEquals(68_571L, odd.frameAt(Rational.ONE))
        assertEquals(34_286L, odd.frameAt(Rational(1, 2))) // 34 285.71
        assertEquals(137_143L, odd.frameAt(Rational(2))) // 137 142.86
    }

    @Test
    fun aLaterAnchorCountsFromItsOwnCycleAndFrame() {
        val later = TempoAnchor(frame = 50_000, cycle = Rational(3, 8), cps = 1.0, sampleRate = 48_000)
        assertEquals(50_000L, later.frameAt(Rational(3, 8)))
        assertEquals(98_000L, later.frameAt(Rational(11, 8)))
    }

    @Test
    fun cyclesBeforeTheAnchorMapToEarlierFrames() {
        assertEquals(2400L - 24_000, anchor.frameAt(Rational(-1, 4)))
    }
}
