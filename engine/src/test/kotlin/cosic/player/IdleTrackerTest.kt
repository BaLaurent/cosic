package cosic.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IdleTrackerTest {
    private val sampleRate = 48_000
    private val block = 240 // 200 blocks per second, so the thresholds below fall exactly on block ends
    private val tracker = IdleTracker(sampleRate)
    private var frame = 0L

    /** Feeds [seconds] of identical blocks; returns the frame at which the tracker first reported going idle. */
    private fun feed(seconds: Double, busy: Boolean = false, peak: Float = 0f): Long? {
        var wentIdle: Long? = null
        repeat(Math.round(seconds * sampleRate / block).toInt()) {
            frame += block
            if (tracker.blockRendered(frame, busy, peak) && wentIdle == null) wentIdle = frame
        }
        return wentIdle
    }

    private val twoSeconds = 2L * sampleRate
    private val longestEchoPlusMargin = 196_800L // 4.1 s

    @Test
    fun startsIdle() {
        assertTrue(tracker.idle)
    }

    @Test
    fun staysAwakeWhileBusy() {
        assertNull(feed(10.0, busy = true, peak = 0.5f))
        assertFalse(tracker.idle)
    }

    @Test
    fun goesIdleTwoSecondsAfterTheLastBusyBlock() {
        feed(1.0, busy = true, peak = 0.5f)
        val lastBusy = frame
        assertEquals(lastBusy + twoSeconds, feed(5.0))
        assertTrue(tracker.idle)
    }

    @Test
    fun aBusyBlockWakesItAtOnce() {
        feed(1.0, busy = true)
        feed(3.0)
        assertTrue(tracker.idle)
        feed(0.005, busy = true)
        assertFalse(tracker.idle)
    }

    @Test
    fun reportsTheTransitionToIdleOnlyOnce() {
        feed(1.0, busy = true)
        var reports = 0
        repeat(2000) {
            frame += block
            if (tracker.blockRendered(frame, busy = false, peak = 0f)) reports++
        }
        assertEquals(1, reports)
    }

    @Test
    fun withoutEffectsAudibleOutputAfterTheLastVoiceDoesNotDelayIdle() {
        feed(1.0, busy = true, peak = 0.5f)
        val lastBusy = frame
        assertEquals(lastBusy + twoSeconds, feed(3.0, peak = 0.5f), "a voice's own fade-out is covered by the two seconds")
    }

    @Test
    fun anAudibleTailKeepsItAwakeAfterEffectsWereUsed() {
        tracker.effectsUsed()
        feed(1.0, busy = true, peak = 0.5f)
        assertNull(feed(5.0, peak = 0.01f), "the tail is still audible long after the two seconds")
        assertFalse(tracker.idle)
        val lastAudible = frame
        assertEquals(lastAudible + longestEchoPlusMargin, feed(6.0))
    }

    @Test
    fun aGapBetweenEchoesShorterThanTheLongestDelayDoesNotCloseIt() {
        tracker.effectsUsed()
        feed(1.0, busy = true, peak = 0.5f)
        repeat(5) {
            assertNull(feed(0.1, peak = 0.01f))
            assertNull(feed(3.5), "an echo is still on its way round the delay")
        }
        assertFalse(tracker.idle)
        val lastAudible = frame - 3_500 * sampleRate / 1000
        assertEquals(lastAudible + longestEchoPlusMargin, feed(10.0))
    }

    @Test
    fun outputBelowMinusEightyDecibelsCountsAsSilence() {
        tracker.effectsUsed()
        feed(1.0, busy = true, peak = 0.5f)
        val lastAudible = frame
        assertEquals(lastAudible + longestEchoPlusMargin, feed(10.0, peak = 5e-5f))
    }

    @Test
    fun theEffectsFlagIsClearedWhenItGoesIdleSoTheNextDryRunIsNotDelayed() {
        tracker.effectsUsed()
        feed(1.0, busy = true, peak = 0.5f)
        assertNotNull(feed(10.0))
        feed(1.0, busy = true, peak = 0.5f)
        val lastBusy = frame
        assertEquals(lastBusy + twoSeconds, feed(5.0))
    }
}
