package cosic

import cosic.audio.SR
import cosic.audio.channel
import cosic.audio.constantSample
import cosic.audio.estimateFrequency
import cosic.audio.peak
import cosic.audio.rms
import cosic.script.reference
import java.lang.management.ManagementFactory
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val CYCLE = 96_000 // frames per cycle at cps 0.5 and 48 kHz
private const val BLOCK = 256

/** The engine facade: evaluate, play, stop, preview, the published snapshot, and when it is idle. */
class EngineTest {
    private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    /** [report] is a success at the default tempo with exactly [warnings]. */
    private fun assertOk(warnings: List<String>, report: EvalReport) {
        val ok = assertIs<EvalReport.Ok>(report)
        assertEquals(0.5, ok.cps)
        assertEquals(warnings, ok.warnings)
    }
    // ------------------------------------------------------------------------------ evaluate

    @Test
    fun evaluateAcceptsAProgramAndReportsItsTempo() {
        val engine = engineOf()
        assertOk(emptyList(), engine.evaluate("""s("bd sd")"""))
        assertEquals(1.0, engine.evaluateOk("setcpm(60)\n\$: s(\"bd\")").cps)
    }

    @Test
    fun evaluateReportsAnErrorWithItsRangeLineAndColumn() {
        val report = assertIs<EvalReport.Failed>(engineOf().evaluate("foo(1)"))
        assertEquals(EvalReport.Failed("foo is not defined", SourceRange(0, 3), 1, 1), report)
    }

    @Test
    fun lineAndColumnAreOneBasedFromTheStartOfTheLine() {
        val report = assertIs<EvalReport.Failed>(engineOf().evaluate("s(\"bd\")\n\n  foo(1)"))
        assertEquals(SourceRange(11, 14), report.range)
        assertEquals(3, report.line)
        assertEquals(3, report.column)
    }

    @Test
    fun anErrorFoundWhileQueryingTheProgramIsRejectedWithItsRangeToo() {
        val report = assertIs<EvalReport.Failed>(engineOf().evaluate("""cat(s("sine"), note("c").add("zz"))"""))
        assertContains(report.message, "zz")
        assertNotNull(report.range)
        assertEquals(1, report.line)
    }

    @Test
    fun anExceptionThatIsNotACosicErrorIsReportedAsAnErrorWhilePlaying() {
        val broken = object : SoundLibrary {
            override fun bank(name: String): BankInfo? = throw IllegalStateException("the library broke")

            override fun sample(name: String, index: Int): SampleData? = null
        }
        val report = assertIs<EvalReport.Failed>(engineOf(broken).evaluate("""s("kit")"""))
        assertEquals("Error while playing: the library broke", report.message)
        assertNull(report.range)
        assertEquals(0, report.line)
        assertEquals(0, report.column)
    }

    @Test
    fun samplesExplainsThatItIsNotSupported() {
        val report = assertIs<EvalReport.Failed>(engineOf().evaluate("samples('x')"))
        assertEquals("samples() isn't supported yet \u2014 import a sample folder from the Sounds tab of the editor panel instead", report.message)
    }

    @Test
    fun aRejectedProgramLeavesTheOneThatIsPlayingAlone() {
        val engine = engineOf()
        engine.evaluateOk("""s("bd*4")""")
        engine.play()
        engine.renderFrames(CYCLE)
        assertIs<EvalReport.Failed>(engine.evaluate("""cat(s("sine"), note("c").add("zz"))"""))
        val starts = engine.highlightStarts(2 * CYCLE)
        assertTrue(starts.size >= 8, "the kicks go on: $starts")
        assertNull(engine.snapshot.runtimeError)
    }

    @Test
    fun aProgramThatSplitsEventsIntoMillionsOfPiecesIsRejectedWithoutExhaustingMemory() {
        for (code in listOf(
            """s("bd").ply(10000).ply(10000)""",
            """s("bd").fast(10000).fast(10000)""",
            """s("bd").chop(10000).chop(10000)""",
        )) {
            val before = threads.currentThreadAllocatedBytes
            val report = assertIs<EvalReport.Failed>(engineOf().evaluate(code), code)
            val allocated = threads.currentThreadAllocatedBytes - before
            assertEquals("Pattern too dense", report.message, code)
            assertTrue(allocated < 100_000_000, "refusing $code built $allocated bytes of events first")
        }
    }

    @Test
    fun denseButReasonableProgramsAreStillAccepted() {
        for (code in listOf(
            """s("hh*64")""",
            """s("bd").ply(8).fast(4)""",
            """s("hh*64").ply(4).fast(2).room(0.2)""",
            """s("bd*16, hh*64, cp*8").every(2, x => x.fast(2)).jux(rev)""",
        )) {
            assertIs<EvalReport.Ok>(engineOf().evaluate(code), code)
        }
    }

    @Test
    fun unknownSoundsAreWarnedAboutNotRejected() {
        val engine = engineOf()
        assertOk(listOf("Unknown sound 'nope'"), engine.evaluate("""s("nope*2, sine")"""))
        engine.play()
        engine.renderFrames(CYCLE)
        assertEquals(listOf("Unknown sound 'nope'"), engine.snapshot.warnings)
    }

    @Test
    fun aSuccessfulEvaluateClearsTheWarningsOfTheProgramBefore() {
        val engine = engineOf()
        engine.evaluateOk("""s("nope")""")
        engine.play()
        engine.renderFrames(CYCLE)
        assertEquals(1, engine.snapshot.warnings.size)
        engine.evaluateOk("""s("sine")""")
        engine.renderFrames(BLOCK)
        assertEquals(emptyList(), engine.snapshot.warnings)
    }

    @Test
    fun aDrumMachineIsChosenWithTheBankControlAndPlays() {
        val engine = engineOf()
        assertOk(emptyList(), engine.evaluate("""s("bd sd").bank("cosic808")"""))
        engine.play()
        assertTrue(rms(engine.renderFrames(CYCLE)) > 0.01, "the machine's kick and snare are heard")
    }

    @Test
    fun theBankControlSelectsWhichDrumMachinePlays() {
        fun heard(code: String): FloatArray = engineOf().run {
            evaluateOk(code)
            play()
            renderFrames(CYCLE / 2)
        }
        val bare = heard("""s("bd")""")
        val machine808 = heard("""s("bd").bank("cosic808")""")
        val machine909 = heard("""s("bd").bank("cosic909")""")
        assertFalse(bare.contentEquals(machine808), "the bank control changes the kick")
        assertFalse(bare.contentEquals(machine909), "the bank control changes the kick")
        assertFalse(machine808.contentEquals(machine909), "each machine has its own kick")
        assertContentEquals(machine808, heard("""s("cosic808_bd")"""), "naming the sound in full is the same as the bank control")
    }

    @Test
    fun anUnknownSoundOfADrumMachineIsWarnedAboutByItsFullName() {
        val engine = engineOf()
        assertOk(listOf("Unknown sound 'cosic808_xx'"), engine.evaluate("""s("xx bd").bank("cosic808")"""))
        engine.play()
        engine.renderFrames(CYCLE)
        assertEquals(listOf("Unknown sound 'cosic808_xx'"), engine.snapshot.warnings)
    }

    @Test
    fun theBankReferenceExamplePlaysABuiltInDrumMachine() {
        val example = reference().first { it.name == "bank" }.example
        assertOk(emptyList(), engineOf().evaluate(example))
    }

    @Test
    fun evaluateAsksTheLibraryToLoadTheSamplesItWillNeed() {
        val library = libraryWithBank("kit", listOf(SampleData(FloatArray(10), 44_100)))
        val engine = engineOf(library)
        engine.evaluateOk("""s("kit")""")
        assertContains(library.requested, "kit" to 0)
    }

    @Test
    fun theDryRunStartsAtTheCurrentCycleWhilePlaying() {
        val engine = engineOf()
        val code = """cat(s("sine"), s("sine"), s("sine"), s("nope"))"""
        assertEquals(emptyList(), engine.evaluateOk(code).warnings, "cycles 0 and 1")
        engine.play()
        engine.renderFrames(2 * CYCLE + CYCLE / 2) // the playhead is at cycle 2.47
        assertEquals(listOf("Unknown sound 'nope'"), engine.evaluateOk(code).warnings, "the playhead is in cycle 2, so cycles 2 and 3 are looked at")
    }

    // ------------------------------------------------------------------------ play, stop, snapshot

    @Test
    fun anEngineThatWasNeverStartedIsIdleAndSilent() {
        val engine = engineOf()
        val before = engine.snapshot
        assertTrue(before.idle)
        assertFalse(before.playing)
        assertEquals(0L, before.renderedFrames)
        assertEquals(SR, before.sampleRate)
        assertEquals(0f, peak(engine.renderFrames(4 * BLOCK)))
        assertEquals(4L * BLOCK, engine.snapshot.renderedFrames)
        assertTrue(engine.snapshot.idle)
    }

    @Test
    fun playStartsAtCycleZeroFiftyMillisecondsAfterTheFramesRenderedSoFar() {
        val engine = engineOf()
        engine.evaluateOk("""s("bd*4")""")
        engine.renderFrames(1000)
        engine.play()
        engine.renderFrames(BLOCK)
        val snapshot = engine.snapshot
        assertTrue(snapshot.playing)
        assertFalse(snapshot.idle)
        assertEquals(0.5, snapshot.cps)
        assertEquals(1000L + 2400, snapshot.anchorFrame)
        assertEquals(0.0, snapshot.anchorCycle)
        assertEquals(1.0, snapshot.cycleAt(1000L + 2400 + CYCLE), 1e-9)
    }

    @Test
    fun playWhileAlreadyPlayingIsIgnored() {
        val engine = engineOf()
        engine.evaluateOk("""s("bd*4")""")
        engine.play()
        engine.renderFrames(CYCLE / 2)
        engine.play()
        engine.renderFrames(BLOCK)
        assertEquals(2400L, engine.snapshot.anchorFrame)
    }

    @Test
    fun playAfterStopStartsOverAtCycleZero() {
        val engine = engineOf()
        engine.evaluateOk("""s("bd*4")""")
        engine.play()
        engine.renderFrames(CYCLE)
        engine.stop()
        engine.renderFrames(BLOCK)
        assertFalse(engine.snapshot.playing)
        val before = engine.snapshot.renderedFrames
        engine.play()
        engine.renderFrames(BLOCK)
        assertTrue(engine.snapshot.playing)
        assertEquals(before + 2400, engine.snapshot.anchorFrame)
        assertEquals(0.0, engine.snapshot.anchorCycle)
    }

    @Test
    fun evaluatingBeforePlayingSetsTheTempoOfThePlay() {
        val engine = engineOf()
        engine.evaluateOk("setcpm(60)\n\$: s(\"bd*4\")")
        engine.play()
        val starts = engine.highlightStarts(CYCLE)
        assertEquals(listOf(2400L, 14_400, 26_400, 38_400), starts.take(4), "a quarter cycle is 12 000 frames at cps 1")
    }

    @Test
    fun fourKicksPerCycleStartOnTheFramesOfTheClockGrid() {
        val engine = engineOf()
        engine.evaluateOk("""s("bd*4")""")
        engine.play()
        val starts = engine.highlightStarts(2 * CYCLE + 4800)
        assertEquals(listOf(2400L, 26_400, 50_400, 74_400, 98_400, 122_400, 146_400, 170_400), starts.take(8))
    }

    @Test
    fun aTempoChangeWhilePlayingKeepsContinuityAndChangesTheSpacingAfterwards() {
        val engine = engineOf()
        engine.evaluateOk("""s("bd*4")""")
        engine.play()
        val seen = LinkedHashSet<Long>()
        val collect = { e: CosicEngine -> e.snapshot.highlights.forEach { seen += it.startFrame } }
        engine.renderFrames(CYCLE + CYCLE / 4, afterBlock = collect)
        engine.evaluateOk("setcpm(60)\n\$: s(\"bd*4\")")
        engine.renderFrames(2 * CYCLE, afterBlock = collect)

        val gaps = seen.sorted().zipWithNext { a, b -> b - a }
        assertEquals(24_000L, gaps.first(), "the first onsets keep the old tempo")
        assertEquals(12_000L, gaps.last(), "later ones come at the new tempo")
        assertEquals(gaps.sortedDescending(), gaps, "the spacing only shrinks, once, across the change")
        assertTrue(gaps.all { it in 12_000L..24_000L }, "no event was moved: $gaps")
    }

    @Test
    fun aRuntimeErrorIsPublishedAndPlaybackGoesOn() {
        val engine = engineOf()
        // cycle 2 of this loop adds a word to a note, so the two cycles of the dry run are fine
        engine.evaluateOk("""cat(s("sine"), s("sine"), note("c").add("zz"))""")
        engine.play()
        val starts = engine.highlightStarts(4 * CYCLE)
        val error = assertNotNull(engine.snapshot.runtimeError)
        assertTrue(error.startsWith("Runtime error: "), error)
        assertContains(error, "zz")
        assertTrue(2400L + 3 * CYCLE in starts, "cycle 3 plays again: $starts")
        assertTrue(2400L + 2 * CYCLE !in starts)
    }

    @Test
    fun aCycleThatTurnsTooDenseAfterTheDryRunIsSkippedAndReportedAndPlaybackGoesOn() {
        val engine = engineOf()
        // the dry run looks at cycles 0 and 1 only; cycle 2 would split one event into millions of pieces
        engine.evaluateOk("""cat(s("sine"), s("sine"), s("sine").ply(10000).ply(10000))""")
        engine.play()
        val starts = engine.highlightStarts(4 * CYCLE)
        assertEquals("Runtime error: Pattern too dense", engine.snapshot.runtimeError)
        assertTrue(2400L + 3 * CYCLE in starts, "cycle 3 plays again: $starts")
        assertTrue(2400L + 2 * CYCLE !in starts)
    }

    @Test
    fun highlightsCarryTheSourceRangesAndExpireAfterOneSecond() {
        val engine = engineOf()
        engine.evaluateOk("""s("bd sd")""")
        engine.play()
        engine.renderFrames(CYCLE / 2)
        val first = engine.snapshot.highlights.first()
        assertEquals(2400L, first.startFrame)
        assertEquals(2400L + CYCLE / 2, first.endFrame)
        assertEquals(listOf(SourceRange(3, 5)), first.ranges)

        engine.renderFrames(5 * CYCLE)
        val now = engine.snapshot.renderedFrames
        assertTrue(engine.snapshot.highlights.all { it.endFrame > now - SR }, "nothing that ended more than a second ago")
        assertTrue(engine.snapshot.highlights.isNotEmpty())
    }

    @Test
    fun theOutputIsSilentFiftyMillisecondsAfterStop() {
        val engine = engineOf()
        engine.evaluateOk("""s("bd*4")""")
        engine.play()
        engine.renderFrames(2 * CYCLE)
        engine.stop()
        val out = engine.renderFrames(CYCLE / 2)
        assertTrue(peak(out, 0, 480 * 2) > 0f, "the voices fade out over 20 ms instead of cutting off")
        assertEquals(0f, peak(out, 2400 * 2), "silent from 50 ms on")
        assertFalse(engine.snapshot.playing)
        assertEquals(0, engine.snapshot.highlights.size, "nothing is highlighted any more")
    }

    @Test
    fun stoppingSilencesTheDelayAndReverbTailsToo() {
        for (code in listOf(
            """s("bd*4").room(1).size(1)""",
            """s("sd").delay(1).delaytime(4).delayfeedback(0.95)""",
        )) {
            val engine = engineOf()
            engine.evaluateOk(code)
            engine.play()
            engine.renderFrames(SR / 8) // the first voice is still sounding
            engine.stop()
            val out = engine.renderFrames(6 * SR) // outlasts the four second delay
            assertTrue(peak(out, 0, 480 * 2) > 0f, "$code: the voices fade out over 20 ms instead of cutting off")
            assertTrue(peak(out, 950 * 2, 960 * 2) < 0.01f, "$code: everything has faded out by the time the buses are emptied")
            assertEquals(0f, peak(out, 2400 * 2), "$code: silent from 50 ms on, no echo or tail comes back")
        }
    }

    @Test
    fun cutKeepsAtMostOneOpenHatActive() {
        val engine = engineOf()
        engine.evaluateOk("""s("oh*8").cut(1)""")
        engine.play()
        var most = 0
        engine.renderFrames(2 * CYCLE) { most = maxOf(most, it.snapshot.activeVoices) }
        assertEquals(1, most)
    }

    // ------------------------------------------------------------------------------- preview

    @Test
    fun previewSoundsASynthAtTheRequestedNoteWithoutStartingPlayback() {
        val engine = engineOf()
        engine.preview("sine", 0, 69.0)
        val out = engine.renderFrames(CYCLE / 4)
        assertEquals(440.0, estimateFrequency(channel(out, 0), 2400, 20_000), 1.0)
        assertFalse(engine.snapshot.playing)
        assertFalse(engine.snapshot.idle)
    }

    @Test
    fun previewPlaysADrumAndABankSample() {
        val engine = engineOf(libraryWithBank("kit", listOf(constantSample(0.05, 0.5f))))
        engine.preview("bd")
        assertTrue(peak(engine.renderFrames(2400)) > 0.1f, "drum")
        engine.renderFrames(CYCLE) // let the kick end
        engine.preview("kit", 0)
        val out = engine.renderFrames(1200)
        assertEquals(0.4 * 0.7071, peak(out, 480 * 2).toDouble(), 0.01, "a constant 0.5 sample, centred, times 0.8")
    }

    @Test
    fun previewOfAnUnknownSoundOrAnUnloadedSampleIsSilent() {
        val library = FakeLibrary(banks = mapOf("kit" to BankInfo("kit", 1, null)))
        val engine = engineOf(library)
        engine.preview("nope")
        engine.preview("kit")
        assertEquals(0f, peak(engine.renderFrames(4800)))
        assertContains(library.requested, "kit" to 0)
    }

    @Test
    fun aPreviewWaitsForASampleThatIsStillBeingDecoded() {
        val library = SlowLibrary(readyOnRequest = 40) // asked once by preview() and then once per block: ready in block 38
        val engine = engineOf(library)
        engine.preview("kit")
        val out = engine.renderFrames(SR / 2)
        assertEquals(0f, peak(out, 0, 38 * BLOCK * 2), "nothing to play while the sample is not there")
        assertTrue(peak(out, 38 * BLOCK * 2, (38 * BLOCK + 480) * 2) > 0.1f, "it sounds as soon as the sample is there")
    }

    @Test
    fun aPreviewGivesUpOnASampleThatDoesNotComeWithinAboutASecond() {
        val library = SlowLibrary(readyOnRequest = 1000)
        val engine = engineOf(library)
        engine.preview("kit")
        assertEquals(0f, peak(engine.renderFrames(5 * SR)))
        assertTrue(library.requests in 150..230, "it asks once per block for a second, then stops: ${library.requests} requests")
    }

    @Test
    fun aNewPreviewReplacesOneThatIsStillWaiting() {
        val library = FakeLibrary(banks = mapOf("kit" to BankInfo("kit", 1, null)))
        val engine = engineOf(library)
        engine.preview("kit")
        engine.preview("bd")
        assertTrue(peak(engine.renderFrames(2400)) > 0.1f, "the kick sounds")
        assertEquals(1, library.requested.count { it == "kit" to 0 }, "only preview() asked, the replaced one was never retried")
    }

    @Test
    fun stopCancelsAPreviewThatIsStillWaiting() {
        val library = SlowLibrary(readyOnRequest = 20)
        val engine = engineOf(library)
        engine.preview("kit")
        engine.renderFrames(BLOCK)
        engine.stop()
        assertEquals(0f, peak(engine.renderFrames(SR / 2)), "it does not sound after the stop")
        assertTrue(library.requests < 20, "and it is not asked for any more: ${library.requests}")
    }

    // --------------------------------------------------------------------------------- idle

    @Test
    fun theEngineGoesIdleTwoSecondsAfterTheLastVoiceHasEndedWhateverTheEffects() {
        for (code in listOf(
            """s("bd*4")""",
            """s("sd").room(1).size(1)""",
            """s("sd").delay(1).delaytime(4).delayfeedback(0.95)""",
        )) {
            val engine = engineOf()
            engine.evaluateOk(code)
            engine.play()
            engine.renderFrames(SR / 8) // the first voice is still sounding
            engine.stop()
            var lastSound = 0L
            var idleAt = -1L
            val buffer = FloatArray(BLOCK * 2)
            while (idleAt < 0 && engine.snapshot.renderedFrames < 20L * SR) {
                engine.render(buffer, BLOCK)
                val now = engine.snapshot.renderedFrames
                if (peak(buffer) > 0f) lastSound = now
                if (engine.snapshot.idle) idleAt = now
            }
            assertTrue(idleAt > 0, "$code never went idle")
            assertTrue(idleAt - lastSound in 2L * SR..2L * SR + BLOCK, "$code: idle ${idleAt - lastSound} frames after the last sound")
        }
    }

    @Test
    fun stoppingLongAfterTheLastVoiceEndedGoesIdleAtOnce() {
        val engine = engineOf()
        engine.evaluateOk("""s("sd").room(1).slow(100)""") // one snare, over after 0.35 s, then silence
        engine.play()
        engine.renderFrames(3 * SR)
        assertFalse(engine.snapshot.idle, "still playing, if only silence")
        engine.stop()
        engine.renderFrames(BLOCK)
        assertTrue(engine.snapshot.idle, "it is not playing, and no voice has sounded for more than two seconds")
    }

    // ------------------------------------------------------------------------------- misc

    @Test
    fun theSoundListsForTheSoundsScreen() {
        assertEquals(listOf("sine", "sawtooth", "square", "triangle", "white", "pink", "brown"), CosicEngine.SYNTHS)
        assertEquals(listOf("bd", "sd", "hh", "oh", "cp", "rim", "lt", "mt", "ht", "cr"), CosicEngine.DRUMS)
        assertEquals(listOf("cosic808", "cosic909"), CosicEngine.DRUM_MACHINES.keys.toList())
        assertEquals(listOf("bd", "sd", "hh", "oh", "cp", "rim", "lt", "mt", "ht", "cr", "cb", "cl"), CosicEngine.DRUM_MACHINES["cosic808"])
        assertEquals(listOf("bd", "sd", "hh", "oh", "cp", "rim", "lt", "mt", "ht", "cr", "rd"), CosicEngine.DRUM_MACHINES["cosic909"])
    }

    @Test
    fun theWholeDrumKitAndEveryMachineAreRenderedInTheBackgroundOnceTheEngineExists() {
        val engine = engineOf()
        val expected = (CosicEngine.DRUMS.size + CosicEngine.DRUM_MACHINES.values.sumOf { it.size }) * 4 // four variants of every sound
        val deadline = System.nanoTime() + 10_000_000_000L
        while (engine.drums.renderedCount < expected && System.nanoTime() < deadline) Thread.sleep(5)
        assertEquals(expected, engine.drums.renderedCount)
    }

    @Test
    fun evaluatingFromAnotherThreadWhileRenderingIsSafe() {
        val engine = engineOf()
        engine.evaluateOk("""s("bd*4")""")
        engine.play()
        val failure = AtomicReference<Throwable>()
        val renderer = thread {
            try {
                engine.renderFrames(30 * SR)
            } catch (t: Throwable) {
                failure.set(t)
            }
        }
        repeat(300) {
            engine.evaluateOk(if (it % 2 == 0) """s("hh*8")""" else """s("bd*4").room(0.3)""")
            if (it % 50 == 0) engine.preview("sd")
        }
        renderer.join()
        assertNull(failure.get())
        assertEquals(30L * SR, engine.snapshot.renderedFrames)
    }

    @Test
    fun everyReferenceExampleIsAcceptedByTheEngineAndPlaysWithoutARuntimeError() {
        for (entry in reference()) {
            val engine = engineOf()
            val ok = engine.evaluate(entry.example)
            assertIs<EvalReport.Ok>(ok, "${entry.name}: ${entry.example} -> $ok")
            engine.play()
            engine.renderFrames((4 / ok.cps * SR).toInt())
            assertNull(engine.snapshot.runtimeError, "${entry.name}: ${entry.example}")
        }
    }
}
