package cosic.player

import cosic.pattern.QueryContext
import cosic.pattern.Hap
import cosic.pattern.Pattern
import cosic.pattern.Rational
import cosic.pattern.TimeSpan
import cosic.pattern.stack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A pattern that has one note a live input made, whose whole starts at [at] cycles (the shape of what `midikeys` answers with). Like the
 * keyboard's pattern it answers no query of the scheduler: the note is for the trigger.
 */
private fun liveNote(at: Rational, key: String): Pattern = Pattern { state ->
    val whole = TimeSpan(at, at + Rational(1, 2))
    val part = whole.intersection(state.span)
    if (QueryContext.fromScheduler || part == null) emptyList() else listOf(Hap(whole, part, mapOf<String, Any?>("s" to "sine", "midikey" to key, "note" to 60.0)))
}

private fun Hap.isLive(): Boolean = (value as? Map<*, *>)?.get("midikey") != null

/** Playing what a live input just made, at once, between the events that are queued ahead (`_triggerKeyboard` of midi.mjs). */
class TriggerTest {
    private val latency = 480L // ten milliseconds at 48 kHz, which is 0.005 cycle at 0.5 cycles per second

    private fun playing(): Scheduler {
        // the program: a kick every half cycle, plus the note of the keyboard at 0.005 cycle
        val scheduler = schedulerOf(stack(patternOf("""s("sine*2")"""), liveNote(Rational(1, 200), "in_60")), manualBus())
        scheduler.play(0)
        scheduler.schedule(0)
        return scheduler
    }

    @Test
    fun theNoteOfTheInputSoundsAfterTheLatencyAndInItsPlaceInTheQueue() {
        val scheduler = playing()
        val played = scheduler.trigger(nowFrame = 1000, latencyFrames = latency, nowCycle = 0.0, cps = 0.5, match = Hap::isLive)
        assertTrue(played)
        val starts = scheduler.queued().map { it.startFrame }
        // the kick at 0 is queued, the note is 480 frames after "now" (frame 1000), and the next kick is half a cycle (48000 frames) away
        assertEquals(listOf(0L, 1480L), starts.take(2))
        assertEquals(starts.sorted(), starts, "the queue stays in order")
    }

    @Test
    fun theNoteIsPlayedOnceTheRestOfThePatternIsNotTouched() {
        val scheduler = playing()
        scheduler.trigger(1000, latency, 0.0, 0.5, Hap::isLive)
        val events = scheduler.queued()
        assertEquals(2, events.size, "the kick and the note, not a second kick")
    }

    @Test
    fun nothingThatMatchesIsFalse() {
        val scheduler = playing()
        assertFalse(scheduler.trigger(1000, latency, 0.0, 0.5) { false })
        assertEquals(1, scheduler.queued().size)
    }

    @Test
    fun anInstantWithoutTheNoteIsFalse() {
        val scheduler = playing()
        assertFalse(scheduler.trigger(1000, latency, 3.0, 0.5, Hap::isLive))
    }

    @Test
    fun nothingHappensWhileStopped() {
        val scheduler = schedulerOf(liveNote(Rational(1, 200), "in_60"), manualBus())
        assertFalse(scheduler.trigger(1000, latency, 0.0, 0.5, Hap::isLive))
        assertEquals(emptyList(), scheduler.queued())
    }

    @Test
    fun theNoteIsMarkedAndItsHooksRunLikeAnyHap() {
        val bus = manualBus()
        val seen = ArrayList<Long>()
        val pattern = liveNote(Rational(1, 200), "in_60").onSchedule({ seen.add(it.frame) }, true)
        val scheduler = schedulerOf(pattern, bus)
        scheduler.play(0)
        assertTrue(scheduler.trigger(2000, latency, 0.0, 0.5, Hap::isLive))
        bus.drain()
        assertEquals(listOf(2480L), seen)
        assertEquals(emptyList(), scheduler.queued(), "a dominant hook takes the sound away")
    }

    @Test
    fun theLatencyIsInSecondsOfTheTempoOfTheCallerNotTheEngine() {
        val scheduler = playing()
        // at cps 1 the same ten milliseconds are 0.01 cycle, so the note stored at 0.005 is no longer at that instant
        assertFalse(scheduler.trigger(1000, latency, 0.0, 1.0, Hap::isLive))
        assertEquals(1, scheduler.queued().size, "only the kick is queued")
    }
}
