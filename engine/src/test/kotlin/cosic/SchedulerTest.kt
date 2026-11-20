package cosic

import cosic.audio.SR
import cosic.audio.VoiceSource
import cosic.audio.Waveform
import cosic.pattern.CosicError
import cosic.pattern.Pattern
import cosic.pattern.Rational
import cosic.pattern.TimeSpan
import cosic.pattern.pure
import cosic.player.Scheduler
import cosic.player.SoundEvent
import cosic.player.SoundResolver
import cosic.player.dryRun
import cosic.script.evaluateProgram
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val CYCLE = 96_000L // frames per cycle at cps 0.5 and 48 kHz
private const val START = 2400L // play() starts 50 ms after the frames already rendered

private fun scheduler(code: String, library: SoundLibrary = FakeLibrary()): Scheduler =
    Scheduler(SR, SoundResolver(SR, library)).also { it.load(code) }

private fun Scheduler.load(code: String) {
    val program = evaluateProgram(code)
    setProgram(program.pattern, program.cps, emptyList())
}

private fun Scheduler.drain(): List<SoundEvent> = generateSequence { takeDue(Long.MAX_VALUE) }.toList()

private fun SoundEvent.waveform(): Waveform = (voice.source as VoiceSource.Synth).waveform

/** Where events go in time: the frame each one starts at, for the plan's `s("bd*4")` examples and tempo changes. */
class SchedulerTest {
    // ------------------------------------------------------------------ the clock grid

    @Test
    fun fourKicksPerCycleStartOnTheFramesOfTheClockGrid() {
        val scheduler = scheduler("""s("bd*4")""")
        scheduler.play(START)
        scheduler.schedule(START + 2 * CYCLE)
        assertEquals(
            listOf(2400L, 26_400, 50_400, 74_400, 98_400, 122_400, 146_400, 170_400, 194_400),
            scheduler.drain().map { it.startFrame },
            "cycle 0, cycle 1 and the first onset of cycle 2, which still falls inside the look-ahead",
        )
    }

    @Test
    fun anEventLastsAsLongAsItsWholeInSeconds() {
        val scheduler = scheduler("""s("bd*4, hh/2")""")
        scheduler.play(START)
        scheduler.schedule(START)
        val durations = scheduler.drain().associate { (it.voice.source as VoiceSource.Sample).params.data to it.voice.durationSec }
        assertEquals(setOf(0.5, 4.0), durations.values.toSet(), "a quarter cycle and two cycles, at 2 s per cycle")
    }

    @Test
    fun eventsAreQueuedInStartOrderWithStackOrderBreakingTies() {
        val scheduler = scheduler("""stack(s("sine*4"), s("square*8"), s("triangle"))""")
        scheduler.play(START)
        scheduler.schedule(START + CYCLE)
        val events = scheduler.drain()
        assertEquals(events.map { it.startFrame }.sorted(), events.map { it.startFrame })
        assertEquals(
            listOf(Waveform.SINE, Waveform.SQUARE, Waveform.TRIANGLE),
            events.take(3).map { it.waveform() },
            "three events start together on the first frame, in the order of the stack",
        )
    }

    @Test
    fun anEventIsQueuedOnlyByTheChunkItStartsIn() {
        val scheduler = scheduler("""s("bd/2")""") // one event whose whole spans two cycles, seen by the chunks of cycle 0 and 1
        scheduler.play(START)
        scheduler.schedule(START + CYCLE)
        assertEquals(listOf(START), scheduler.drain().map { it.startFrame })
    }

    @Test
    fun patternsWithoutEventsQueueNothing() {
        for (code in listOf("silence", "sine", "hush()")) {
            val scheduler = scheduler(code)
            scheduler.play(START)
            scheduler.schedule(START + CYCLE)
            assertEquals(emptyList(), scheduler.drain(), code)
        }
    }

    @Test
    fun schedulingLooksOneTenthOfASecondAheadInWholeChunks() {
        val scheduler = scheduler("""s("bd*4")""")
        scheduler.play(0)
        scheduler.schedule(0)
        assertEquals(Rational(1, 16), scheduler.horizon, "chunk 0 starts before 0 + 4800 frames, chunk 1 at frame 6000 does not")
        scheduler.schedule(0)
        assertEquals(Rational(1, 16), scheduler.horizon, "nothing more is due until the block moves on")
        scheduler.schedule(2000)
        assertEquals(Rational(2, 16), scheduler.horizon)
    }

    @Test
    fun nothingIsQueuedWhileStopped() {
        val scheduler = scheduler("""s("bd*4")""")
        scheduler.schedule(CYCLE)
        assertEquals(Long.MAX_VALUE, scheduler.nextEventFrame)
        scheduler.play(START)
        scheduler.schedule(CYCLE)
        assertTrue(scheduler.nextEventFrame < Long.MAX_VALUE)
        scheduler.stop()
        assertEquals(Long.MAX_VALUE, scheduler.nextEventFrame, "stopping clears what was queued")
        assertEquals(Rational.ZERO, scheduler.horizon)
        scheduler.schedule(CYCLE)
        assertEquals(emptyList(), scheduler.drain())
    }

    @Test
    fun playStartsOverAtCycleZeroFromTheGivenFrame() {
        val scheduler = scheduler("""s("bd*4")""")
        scheduler.play(START)
        scheduler.schedule(CYCLE)
        scheduler.stop()
        scheduler.play(500_000)
        scheduler.schedule(500_000)
        assertEquals(500_000L, scheduler.drain().first().startFrame)
        assertEquals(500_000L, scheduler.anchor.frame)
        assertEquals(Rational.ZERO, scheduler.anchor.cycle)
    }

    @Test
    fun takeDueHandsOutEventsAtOrBeforeTheGivenFrameOnly() {
        val scheduler = scheduler("""s("bd*4")""")
        scheduler.play(START)
        scheduler.schedule(START + CYCLE)
        assertNull(scheduler.takeDue(START - 1))
        assertEquals(START, scheduler.takeDue(START)?.startFrame)
        assertEquals(26_400L, scheduler.nextEventFrame)
        assertNull(scheduler.takeDue(26_399))
        assertEquals(26_400L, scheduler.takeDue(30_000)?.startFrame, "a late event is still handed out")
    }

    // ------------------------------------------------------------- tempo and program changes

    @Test
    fun aTempoChangeOnAChunkBoundaryKeepsScheduledEventsAndRetimesTheRest() {
        val scheduler = scheduler("""s("bd*4")""")
        scheduler.play(0)
        scheduler.schedule(40_000)
        assertEquals(Rational(1, 2), scheduler.horizon)

        scheduler.load("setcps(1)\n\$: s(\"bd*4\")")
        scheduler.schedule(200_000)

        val frames = scheduler.drain().map { it.startFrame }
        assertEquals(listOf(0L, 24_000, 48_000, 60_000, 72_000, 84_000), frames.take(6))
        assertTrue(
            frames.drop(2).zipWithNext { a, b -> b - a }.all { it == 12_000L },
            "from the horizon on, a quarter cycle is 12 000 frames",
        )
    }

    @Test
    fun aTempoChangeInTheMiddleOfAStepKeepsTheTimeOfTheHorizon() {
        val scheduler = scheduler("""s("bd*4")""")
        scheduler.play(0)
        scheduler.schedule(30_000)
        assertEquals(Rational(6, 16), scheduler.horizon)
        val horizonFrame = scheduler.anchor.frameAt(scheduler.horizon)
        assertEquals(36_000L, horizonFrame)

        scheduler.load("setcps(1)\n\$: s(\"bd*4\")")
        assertEquals(horizonFrame, scheduler.anchor.frame, "the new anchor is the horizon, so queued events keep their frames")
        assertEquals(Rational(6, 16), scheduler.anchor.cycle)
        scheduler.schedule(100_000)

        val frames = scheduler.drain().map { it.startFrame }
        assertEquals(listOf(0L, 24_000, 42_000, 54_000, 66_000), frames.take(5))
    }

    @Test
    fun theSameTempoDoesNotMoveTheAnchor() {
        val scheduler = scheduler("""s("bd*4")""")
        scheduler.play(START)
        scheduler.schedule(50_000)
        val anchor = scheduler.anchor
        scheduler.load("""s("sd*4")""")
        assertTrue(anchor === scheduler.anchor)
    }

    @Test
    fun aNewProgramTakesOverAtTheHorizon() {
        val scheduler = scheduler("""s("sine*4")""")
        scheduler.play(0)
        scheduler.schedule(30_000)
        assertEquals(Rational(6, 16), scheduler.horizon)

        scheduler.load("""s("square*4")""")
        scheduler.schedule(200_000)

        val events = scheduler.drain()
        assertEquals(listOf(0L, 24_000, 48_000, 72_000), events.take(4).map { it.startFrame })
        assertEquals(
            listOf(Waveform.SINE, Waveform.SINE, Waveform.SQUARE, Waveform.SQUARE),
            events.take(4).map { it.waveform() },
            "events before cycle 6/16 were queued from the old program, the rest come from the new one",
        )
        assertTrue(events.drop(2).all { it.waveform() == Waveform.SQUARE })
    }

    @Test
    fun aProgramSetWhileStoppedSuppliesTheTempoOfTheNextPlay() {
        val scheduler = scheduler("""s("bd*4")""")
        scheduler.load("setcps(1)\n\$: s(\"bd*4\")")
        assertEquals(1.0, scheduler.cps)
        scheduler.play(1000)
        assertEquals(1.0, scheduler.anchor.cps)
        scheduler.schedule(1000)
        scheduler.schedule(60_000)
        assertEquals(listOf(1000L, 13_000, 25_000, 37_000, 49_000, 61_000), scheduler.drain().map { it.startFrame }.take(6))
    }

    // ------------------------------------------------------------------- errors and warnings

    @Test
    fun aChunkThatThrowsIsSkippedTheErrorIsRecordedAndSchedulingGoesOn() {
        // cycle 2 of the three-cycle loop adds a word that is not a note to a note: fine until that cycle is queried
        val scheduler = scheduler("""cat(s("sine"), s("sine"), note("c").add(note("zz")))""")
        scheduler.play(0)
        scheduler.schedule(4 * CYCLE)

        val starts = scheduler.drain().map { it.startFrame }
        assertEquals(listOf(0L, CYCLE, 3 * CYCLE, 4 * CYCLE), starts, "cycle 2 is missing, the others are there")
        val error = assertNotNull(scheduler.runtimeError)
        assertTrue(error.startsWith("Runtime error: "), error)
        assertContains(error, "zz")
    }

    @Test
    fun aNewProgramClearsTheRuntimeErrorAndReplacesTheWarnings() {
        val scheduler = scheduler("""cat(s("nope"), note("c").add(note("zz")))""")
        scheduler.play(0)
        scheduler.schedule(2 * CYCLE)
        assertNotNull(scheduler.runtimeError)
        assertEquals(listOf("sound nope not found! Is it loaded?"), scheduler.warnings)

        val program = evaluateProgram("""s("sine")""")
        scheduler.setProgram(program.pattern, program.cps, listOf("from the dry run"))
        assertNull(scheduler.runtimeError)
        assertEquals(listOf("from the dry run"), scheduler.warnings)
    }

    @Test
    fun playClearsTheRuntimeError() {
        val scheduler = scheduler("""note("c").add(note("zz"))""")
        scheduler.play(0)
        scheduler.schedule(0)
        assertNotNull(scheduler.runtimeError)
        scheduler.stop()
        assertNotNull(scheduler.runtimeError, "stopping does not hide what went wrong")
        scheduler.play(0)
        assertNull(scheduler.runtimeError)
    }

    @Test
    fun aChunkWithMoreThanTwoThousandEventsKeepsTwoThousandAndWarns() {
        val scheduler = scheduler("""s("sine*10000, square*10000, triangle*10000, saw*10000")""")
        scheduler.play(0)
        scheduler.schedule(0)
        assertEquals(2000, scheduler.drain().size)
        assertEquals(listOf("Pattern too dense"), scheduler.warnings)
    }

    @Test
    fun aCycleThatWouldBuildMillionsOfEventsIsSkippedAndTheErrorRecorded() {
        // cycles 0 and 1 are plain; cycle 2 splits its one event into millions of pieces
        val scheduler = scheduler("""cat(s("sine"), s("sine"), s("sine").ply(10000).ply(10000))""")
        scheduler.play(0)
        scheduler.schedule(4 * CYCLE)
        assertEquals(listOf(0L, CYCLE, 3 * CYCLE, 4 * CYCLE), scheduler.drain().map { it.startFrame }, "cycle 2 is missing")
        assertEquals("Runtime error: Pattern too dense", scheduler.runtimeError)
    }

    /** What the dry run throws, and what the scheduler records, when querying [pattern] fails on every chunk. */
    private fun queryFailureOf(pattern: Pattern): Pair<String?, String?> {
        val sounds = SoundResolver(SR, FakeLibrary())
        val thrown = assertFailsWith<CosicError> { dryRun(pattern, 0, sounds) }
        val scheduler = Scheduler(SR, sounds)
        scheduler.setProgram(pattern, 0.5, emptyList())
        scheduler.play(0)
        scheduler.schedule(0)
        return thrown.message to scheduler.runtimeError
    }

    @Test
    fun runningOutOfMemoryWhileQueryingIsReportedAsATooDensePattern() {
        val (thrown, recorded) = queryFailureOf(Pattern { throw OutOfMemoryError("Java heap space") })
        assertEquals("Pattern too dense", thrown)
        assertEquals("Runtime error: Pattern too dense", recorded)
    }

    @Test
    fun aPatternNestedTooDeeplyToQueryIsReportedInsteadOfCrashing() {
        var nested: Pattern = pure(mapOf("s" to "sine"))
        repeat(200_000) { nested = nested.withQueryTime { it } }
        val (thrown, recorded) = queryFailureOf(nested)
        assertEquals("The program nests or calls itself too deeply", thrown)
        assertEquals("Runtime error: The program nests or calls itself too deeply", recorded)
    }

    @Test
    fun aChunkWhoseQueryDoesMoreWorkThanTheBudgetAllowsIsRefused() {
        // cutting a span into 100 000 cycles costs next to nothing, but is far more than a chunk of playback needs
        val busy = Pattern {
            TimeSpan(Rational.ZERO, Rational(100_000)).spanCycles()
            emptyList()
        }
        val (thrown, recorded) = queryFailureOf(busy)
        assertEquals("Pattern too dense", thrown)
        assertEquals("Runtime error: Pattern too dense", recorded)
    }

    @Test
    fun anUnknownSoundIsSkippedAndWarnedAboutOnce() {
        val scheduler = scheduler("""s("nope*4, sine")""")
        scheduler.play(0)
        scheduler.schedule(3 * CYCLE)
        assertEquals(listOf("sound nope not found! Is it loaded?"), scheduler.warnings)
        assertTrue(scheduler.drain().all { it.waveform() == Waveform.SINE })
    }

    @Test
    fun aSampleThatIsStillLoadingIsSkippedWithoutAWarning() {
        val library = FakeLibrary(banks = mapOf("kit" to TestBank(1)))
        val scheduler = scheduler("""s("kit*4")""", library)
        scheduler.play(0)
        scheduler.schedule(CYCLE)
        assertEquals(emptyList(), scheduler.drain())
        assertEquals(emptyList(), scheduler.warnings)
        assertTrue(library.requested.isNotEmpty(), "it was asked for, which makes the library start decoding")
    }

    // ----------------------------------------------------------------------------- highlights

    @Test
    fun everyEventIsHighlightedWithItsSourceRangesFromStartToEnd() {
        val scheduler = scheduler("""s("bd sd")""")
        scheduler.play(START)
        scheduler.schedule(90_000) // the horizon ends exactly at cycle 1, so the second cycle's kick is not queued yet
        val highlights = scheduler.highlights.snapshot()
        assertEquals(listOf(START, 50_400L), highlights.map { it.startFrame })
        assertEquals(listOf(50_400L, 98_400L), highlights.map { it.endFrame })
        assertEquals(listOf(listOf(SourceRange(3, 5)), listOf(SourceRange(6, 8))), highlights.map { it.ranges })
    }

    @Test
    fun eventsWithoutAnySourceRangeAreNotHighlighted() {
        val scheduler = scheduler("""note(run(4)).s('sine')""")
        scheduler.play(START)
        scheduler.schedule(CYCLE)
        assertTrue(scheduler.drain().isNotEmpty())
        assertEquals(emptyList(), scheduler.highlights.snapshot())
    }

    // ------------------------------------------------------------------------------ dry run

    @Test
    fun theDryRunWarnsAboutUnknownSoundsInTheTwoCyclesFromTheGivenOne() {
        val program = evaluateProgram("""cat(s("sine"), s("nope"), s("alsonope"), s("sine"))""")
        val sounds = SoundResolver(SR, FakeLibrary())
        assertEquals(listOf("sound nope not found! Is it loaded?"), dryRun(program.pattern, 0, sounds), "cycles 0 and 1")
        assertEquals(listOf("sound nope not found! Is it loaded?", "sound alsonope not found! Is it loaded?"), dryRun(program.pattern, 1, sounds))
        assertEquals(listOf("sound alsonope not found! Is it loaded?"), dryRun(program.pattern, 2, sounds), "cycles 2 and 3")
        assertEquals(emptyList(), dryRun(program.pattern, 3, sounds), "cycles 3 and 4 are both `sine`")
    }

    @Test
    fun theDryRunAsksTheLibraryForTheSamplesThePatternWillUse() {
        val library = FakeLibrary(banks = mapOf("kit" to TestBank(3)))
        val program = evaluateProgram("""s("kit:1 kit:2")""")
        assertEquals(emptyList(), dryRun(program.pattern, 0, SoundResolver(SR, library)))
        assertEquals(setOf("kit" to 1, "kit" to 2), library.requested.toSet())
    }

    @Test
    fun theDryRunWarnsAboutDensePatternsLikeTheScheduler() {
        val program = evaluateProgram("""s("sine*10000, square*10000, triangle*10000, saw*10000")""")
        assertEquals(listOf("Pattern too dense"), dryRun(program.pattern, 0, SoundResolver(SR, FakeLibrary())))
    }

    @Test
    fun theDryRunThrowsWhatTheSchedulerWouldHaveRecorded() {
        val program = evaluateProgram("""cat(s("sine"), note("c").add(note("zz")))""")
        assertFailsWith<CosicError> { dryRun(program.pattern, 0, SoundResolver(SR, FakeLibrary())) }
    }

    @Test
    fun theDryRunDoesNotLookBeyondTwoCycles() {
        val program = evaluateProgram("""cat(s("sine"), s("sine"), note("c").add(note("zz")))""") // the bad cycle is cycle 2
        assertEquals(emptyList(), dryRun(program.pattern, 0, SoundResolver(SR, FakeLibrary())))
    }
}
