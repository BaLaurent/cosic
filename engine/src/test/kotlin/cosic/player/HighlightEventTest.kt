package cosic.player

import cosic.FakeLibrary
import cosic.SourceRange
import cosic.TestBank
import cosic.pattern.ScheduleHook
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private const val START = 2400L

/** What the editor is told to mark: every hap with source ranges, from its start to the end of its clipped length, with its colour and style. */
class HighlightEventTest {
    private fun scheduled(code: String, library: cosic.SoundLibrary = FakeLibrary(), make: (cosic.pattern.Pattern) -> cosic.pattern.Pattern = { it }): Scheduler {
        val scheduler = schedulerOf(make(patternOf(code)), manualBus(), library = library)
        scheduler.play(START)
        scheduler.schedule(START)
        return scheduler
    }

    @Test
    fun clipShortensTheMarkToTheClippedLengthOfTheHap() {
        val scheduler = scheduled("""s("sine sine").clip(0.5)""")
        val mark = scheduler.highlights.snapshot().single()
        assertEquals(START, mark.startFrame)
        assertEquals(START + 24_000, mark.endFrame, "a quarter of a cycle at 2 s per cycle is 24000 frames, not the half cycle of the whole")
        assertEquals(listOf(SourceRange(3, 7)), mark.ranges)
    }

    @Test
    fun withoutAnyControlTheMarkLastsAsLongAsTheWhole() {
        val scheduler = scheduled("""s("sine sine")""")
        val mark = scheduler.highlights.snapshot().single()
        assertEquals(START + 48_000, mark.endFrame)
    }

    @Test
    fun colorAndMarkcssAreCopiedFromTheControls() {
        val mark = scheduled("""s("sine").color("red").markcss('text-decoration:underline')""").highlights.snapshot().single()
        assertEquals("red", mark.color)
        assertEquals("text-decoration:underline", mark.markcss)
    }

    @Test
    fun withoutThoseControlsTheMarkHasNeither() {
        val mark = scheduled("""s("sine")""").highlights.snapshot().single()
        assertNull(mark.color)
        assertNull(mark.markcss)
    }

    @Test
    fun anEmptyMarkcssIsNoStyleAndAColorThatIsANumberIsItsText() {
        val mark = scheduled("""s("sine").color(1).markcss('')""").highlights.snapshot().single()
        assertEquals("1", mark.color)
        assertNull(mark.markcss)
    }

    @Test
    fun markcssIsAControlAndAGlobalFunction() {
        assertEquals("[ 0/1 → 1/1 | note:c markcss:a:b ]", patternOf("""note("c").markcss('a:b')""").queryArc(cosic.pattern.Rational.ZERO, cosic.pattern.Rational.ONE).single().show(true))
        assertEquals("[ 0/1 → 1/1 | markcss:a:b ]", patternOf("""markcss('a:b')""").queryArc(cosic.pattern.Rational.ZERO, cosic.pattern.Rational.ONE).single().show(true))
    }

    @Test
    fun aHapWithAnUnknownSoundIsMarkedAndWarnedAbout() {
        val scheduler = scheduled("""s("nope")""")
        assertEquals(1, scheduler.highlights.snapshot().size)
        assertEquals(listOf("sound nope not found! Is it loaded?"), scheduler.warnings)
        assertEquals(emptyList(), scheduler.queued())
    }

    @Test
    fun aHapWhoseSampleIsStillLoadingIsMarked() {
        val scheduler = scheduled("""s("kit")""", FakeLibrary(banks = mapOf("kit" to TestBank(1))))
        assertEquals(1, scheduler.highlights.snapshot().size)
        assertEquals(emptyList(), scheduler.queued())
    }

    @Test
    fun aHapTakenOverByADominantHookIsMarked() {
        val scheduler = scheduled("""s("sine")""") { it.onSchedule(ScheduleHook { }, true) }
        assertEquals(1, scheduler.highlights.snapshot().size)
        assertEquals(emptyList(), scheduler.queued())
    }

    @Test
    fun aHapThatSetsTheTempoIsMarkedUntilTheEndInTheNewTempo() {
        val scheduler = schedulerOf(patternOf("""seq(s("sine").cps(1), s("sine"))"""), manualBus(), rate = 1000)
        scheduler.play(0)
        scheduler.schedule(500)
        val marks = scheduler.highlights.snapshot()
        assertEquals(listOf(0L, 500L), marks.take(2).map { it.startFrame })
        assertEquals(listOf(500L, 1000L), marks.take(2).map { it.endFrame })
    }

    @Test
    fun hapsWithoutSourceRangesAreNotMarked() {
        val scheduler = scheduled("""note(run(4)).s('sine')""")
        assertEquals(emptyList(), scheduler.highlights.snapshot())
    }
}
