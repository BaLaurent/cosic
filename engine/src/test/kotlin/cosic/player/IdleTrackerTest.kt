package cosic.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IdleTrackerTest {
    private val sampleRate = 48_000
    private val block = 240 // 200 blocks per second, so the thresholds below fall exactly on block ends
    private val tracker = IdleTracker(sampleRate)
    private var frame = 0L

    /** Feeds [seconds] of identical blocks; returns the frame at which the tracker first reported going idle. */
    private fun feed(seconds: Double, playing: Boolean = false, voices: Boolean = false): Long? {
        var wentIdle: Long? = null
        repeat(Math.round(seconds * sampleRate / block).toInt()) {
            frame += block
            if (tracker.blockRendered(frame, playing, voices) && wentIdle == null) wentIdle = frame
        }
        return wentIdle
    }

    private val twoSeconds = 2L * sampleRate

    @Test
    fun startsIdle() {
        assertTrue(tracker.idle)
    }

    @Test
    fun staysAwakeWhileAVoiceSounds() {
        assertNull(feed(10.0, voices = true))
        assertFalse(tracker.idle)
    }

    @Test
    fun staysAwakeWhilePlayingEvenInSilence() {
        feed(1.0, voices = true)
        assertNull(feed(10.0, playing = true))
        assertFalse(tracker.idle)
    }

    @Test
    fun goesIdleTwoSecondsAfterTheLastVoiceOnceItStoppedPlaying() {
        feed(1.0, playing = true, voices = true)
        val lastVoice = frame
        assertEquals(lastVoice + twoSeconds, feed(5.0))
        assertTrue(tracker.idle)
    }

    @Test
    fun stoppingLongAfterTheLastVoiceEndedGoesIdleAtOnce() {
        feed(1.0, voices = true)
        assertNull(feed(3.0, playing = true))
        assertTrue(feed(0.005) != null)
        assertTrue(tracker.idle)
    }

    @Test
    fun aVoiceWakesItAtOnce() {
        feed(1.0, voices = true)
        feed(3.0)
        assertTrue(tracker.idle)
        feed(0.005, voices = true)
        assertFalse(tracker.idle)
    }

    @Test
    fun aVoiceRestartsTheTwoSeconds() {
        feed(1.0, voices = true)
        assertNull(feed(1.9))
        feed(0.005, voices = true)
        val lastVoice = frame
        assertEquals(lastVoice + twoSeconds, feed(5.0))
    }

    @Test
    fun reportsTheTransitionToIdleOnlyOnce() {
        feed(1.0, voices = true)
        var reports = 0
        repeat(2000) {
            frame += block
            if (tracker.blockRendered(frame, playing = false, voices = false)) reports++
        }
        assertEquals(1, reports)
    }

    @Test
    fun reportsTheTransitionAgainAfterItWasWoken() {
        feed(1.0, voices = true)
        assertTrue(feed(3.0) != null)
        feed(1.0, voices = true)
        assertTrue(feed(3.0) != null)
    }
}
