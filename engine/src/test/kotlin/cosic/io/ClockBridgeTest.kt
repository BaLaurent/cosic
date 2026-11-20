package cosic.io

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val MS = 1_000_000L

/** The estimate of when engine frames are heard, smoothed as `ClockBridge` of superdough does. */
class ClockBridgeTest {
    @Test
    fun thereIsNoEstimateBeforeTheFirstObservation() {
        val bridge = ClockBridge()
        assertNull(bridge.nanoTimeAtFrame(0))
        assertNull(bridge.frameAtNanoTime(0))
        assertNull(bridge.offsetMillis)
    }

    @Test
    fun theOffsetIsSmoothedAndSnapsWhenItJumps() {
        val bridge = ClockBridge()
        // frame f of the engine is heard at f / 2 ms plus the offset; sample rate 2000 so that offsets can have halves
        bridge.observe(framePosition = 0, nanoTime = 100 * MS, sampleRate = 2000, frameOffset = 0)
        assertEquals(100.0, bridge.offsetMillis)

        // 100 ms later the track says the offset is 100.5: 100.5 + (100 - 100.5) * exp(-1e-4 * 100)
        bridge.observe(framePosition = 199, nanoTime = 200 * MS, sampleRate = 2000, frameOffset = 0)
        val smoothed = assertNotNull(bridge.offsetMillis)
        assertTrue(abs(smoothed - 100.0049751) < 1e-6, "smoothed offset $smoothed")

        // the system slept: more than 20 ms off, so the estimate follows at once
        bridge.observe(framePosition = 340, nanoTime = 300 * MS, sampleRate = 2000, frameOffset = 0)
        assertEquals(130.0, bridge.offsetMillis)
    }

    @Test
    fun theFrameOffsetOfTheTrackIsPartOfTheEngineFrame() {
        val bridge = ClockBridge()
        // the track's frame 100 is the engine's frame 1100, heard at 500 ms: engine frame 1100 is at 1100 ms + offset
        bridge.observe(framePosition = 100, nanoTime = 1600 * MS, sampleRate = 1000, frameOffset = 1000)
        assertEquals(500.0, bridge.offsetMillis)
        assertEquals(2000 * MS, bridge.nanoTimeAtFrame(1500), "frame 1500 is 400 frames after the one heard at 1600 ms")
    }

    @Test
    fun theTimeOfAFrameFollowsFromTheOffset() {
        val bridge = ClockBridge()
        bridge.observe(0, 100 * MS, 1000, 0)
        assertEquals(150 * MS, bridge.nanoTimeAtFrame(50))
        assertEquals(100 * MS, bridge.nanoTimeAtFrame(0))
        assertEquals(1100 * MS, bridge.nanoTimeAtFrame(1000))
    }

    @Test
    fun theFrameOfATimeIsTheInverse() {
        val bridge = ClockBridge()
        bridge.observe(0, 100 * MS, 1000, 0)
        assertEquals(50L, bridge.frameAtNanoTime(150 * MS))
        assertEquals(1000L, bridge.frameAtNanoTime(1100 * MS))
        assertEquals(-100L, bridge.frameAtNanoTime(0))
    }

    @Test
    fun aRoughEstimateStandsInUntilTheFirstTimeStamp() {
        val bridge = ClockBridge()
        // frame 1000 was handed to the track at 1 s; frames are heard 40 ms later
        bridge.beginSession(startNanos = 1000 * MS, engineFrameAtStart = 1000, sampleRate = 1000)
        assertEquals(1000 * MS + 500 * MS + 40 * MS, bridge.nanoTimeAtFrame(1500))
        assertEquals(1500L, bridge.frameAtNanoTime(1540 * MS))

        // the track reports it: the estimate takes over
        bridge.observe(framePosition = 0, nanoTime = 1100 * MS, sampleRate = 1000, frameOffset = 1000)
        assertEquals(100.0, bridge.offsetMillis)
        assertEquals(1100 * MS + 500 * MS, bridge.nanoTimeAtFrame(1500))
    }

    @Test
    fun aNewSessionForgetsTheEstimateOfTheOneBefore() {
        val bridge = ClockBridge()
        bridge.observe(0, 100 * MS, 1000, 0)
        bridge.beginSession(startNanos = 5000 * MS, engineFrameAtStart = 0, sampleRate = 1000, latencyMillis = 10.0)
        assertNull(bridge.offsetMillis)
        assertEquals(5000 * MS + 200 * MS + 10 * MS, bridge.nanoTimeAtFrame(200))
    }

    @Test
    fun resetForgetsEverything() {
        val bridge = ClockBridge()
        bridge.observe(0, 100 * MS, 1000, 0)
        bridge.beginSession(1000, 0, 1000)
        bridge.reset()
        assertNull(bridge.nanoTimeAtFrame(0))
    }

    @Test
    fun expDecayApproachesTheTarget() {
        assertEquals(20.0, expDecay(10.0, 20.0, 0.1, Double.POSITIVE_INFINITY), "after a long time the target is reached")
        assertEquals(10.0, expDecay(10.0, 20.0, 0.0, 99.0), "with no decay nothing moves")
        assertEquals(20.0 + (10.0 - 20.0) * Math.exp(-0.5), expDecay(10.0, 20.0, 0.1, 5.0))
    }
}
