package cosic.player

import cosic.FakeLibrary
import cosic.audio.SR
import cosic.pattern.CosicError
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

private const val START = 2400L

/** A hap whose value is no object is that hap's own error: a line on the console, no sound, and everything else goes on (`getSchedule`). */
class NonObjectValueTest {
    private val bus = manualBus()

    private fun play(code: String): Scheduler {
        val scheduler = schedulerOf(patternOf(code), bus)
        scheduler.play(START)
        scheduler.schedule(START)
        bus.drain()
        return scheduler
    }

    @Test
    fun aBareValueIsOneErrorLineAndNoEvent() {
        val scheduler = play(""""c3"""")
        assertEquals(emptyList(), scheduler.queued())
        val line = bus.console.entries.single()
        assertEquals(
            "[getSchedule] error: expected hap.value to be an object, but got \"c3\". Hint: append .note() or .s() to the end",
            line.message,
        )
        assertEquals("error", line.type)
    }

    @Test
    fun aNumberIsTheSame() {
        play("""run(4)""")
        assertContains(bus.console.entries.single().message, "but got \"0\"")
    }

    @Test
    fun repeatsOfTheSameErrorAreOneLine() {
        play(""""c3*4"""")
        assertEquals(1, bus.console.entries.size)
    }

    @Test
    fun theOtherHapsOfThePatternAreStillPlayed() {
        val scheduler = play("""stack("c3", s("sine"))""")
        assertEquals(1, scheduler.queued().size)
        assertEquals(1, bus.console.entries.size)
    }

    @Test
    fun theErrorIsTheRuntimeErrorTheStatusLineShows() {
        val scheduler = play(""""c3"""")
        assertContains(assertNotNull(scheduler.runtimeError), "expected hap.value to be an object")
    }

    @Test
    fun aBareValueIsStillMarkedInTheEditor() {
        val scheduler = play(""""c3"""")
        assertEquals(1, scheduler.highlights.snapshot().size)
    }

    @Test
    fun theDryRunStillRejectsTheProgramSoTheUserSeesItAtOnce() {
        val error = assertFailsWith<CosicError> { dryRun(patternOf(""""c3""""), 0, SoundResolver(SR, FakeLibrary())) }
        assertContains(error.message, "expected hap.value to be an object")
    }
}
