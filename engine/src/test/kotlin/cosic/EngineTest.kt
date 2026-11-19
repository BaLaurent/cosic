package cosic

import cosic.audio.SR
import cosic.audio.channel
import cosic.audio.constantSample
import cosic.audio.estimateFrequency
import cosic.audio.peak
import cosic.script.reference
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContains
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
    // ------------------------------------------------------------------------------ evaluate

    @Test
    fun evaluateAcceptsAProgramAndReportsItsTempo() {
        val engine = engineOf()
        assertEquals(EvalReport.Ok(0.5, emptyList()), engine.evaluate("""s("bd sd")"""))
        assertEquals(1.0, engine.evaluateOk("setcpm(60)\n\$: s(\"bd\")").cps)
    }

    @Test
    fun evaluateReportsAnErrorWithItsRangeLineAndColumn() {
        val report = assertIs<EvalReport.Failed>(engineOf().evaluate("foo(1)"))
        assertEquals(EvalReport.Failed("Unknown function 'foo'", SourceRange(0, 6), 1, 1), report)
    }

    @Test
    fun lineAndColumnAreOneBasedFromTheStartOfTheLine() {
        val report = assertIs<EvalReport.Failed>(engineOf().evaluate("s(\"bd\")\n\n  foo(1)"))
        assertEquals(SourceRange(11, 17), report.range)
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
        val tooLarge = """s("sine").early("9000000000000000000")""" // the number is looked at only when the pattern is queried
        val report = assertIs<EvalReport.Failed>(engineOf().evaluate(tooLarge))
        assertEquals("Error while playing: Invalid number", report.message)
        assertNull(report.range)
        assertEquals(0, report.line)
        assertEquals(0, report.column)
    }

    @Test
    fun samplesExplainsThatItIsNotSupported() {
        val report = assertIs<EvalReport.Failed>(engineOf().evaluate("samples('x')"))
        assertEquals("samples() isn't supported yet \u2014 import sample folders from the Sounds screen instead", report.message)
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
    fun unknownSoundsAreWarnedAboutNotRejected() {
        val engine = engineOf()
        assertEquals(EvalReport.Ok(0.5, listOf("Unknown sound 'nope'")), engine.evaluate("""s("nope*2, sine")"""))
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
    fun stoppingDoesNotCutAnEffectTail() {
        val engine = engineOf()
        engine.evaluateOk("""s("bd*4").room(1).size(1)""")
        engine.play()
        engine.renderFrames(CYCLE)
        engine.stop()
        val out = engine.renderFrames(CYCLE / 2)
        assertTrue(peak(out, 2400 * 2) > 0.01f, "the reverb rings on after the voices have faded")
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

    // --------------------------------------------------------------------------------- idle

    @Test
    fun aPatternWithoutEffectsGoesIdleTwoSecondsAfterStop() {
        val engine = engineOf()
        engine.evaluateOk("""s("bd*4")""")
        engine.play()
        engine.renderFrames(2 * CYCLE)
        engine.stop()
        engine.renderFrames(BLOCK)
        val stopped = engine.snapshot.renderedFrames
        engine.renderFrames(2 * SR - 2 * BLOCK)
        assertFalse(engine.snapshot.idle, "less than two seconds since the last voice")
        engine.renderFrames(4 * BLOCK)
        assertTrue(engine.snapshot.idle)
        assertTrue(engine.snapshot.renderedFrames - stopped >= 2 * SR)
    }

    @Test
    fun anEchoOnItsWayRoundTheDelayKeepsTheEngineAwake() {
        val engine = engineOf()
        engine.evaluateOk("""s("sd").delay(1).delaytime(2).delayfeedback(0.6)""")
        engine.play()
        val stoppedAt = SR / 2
        engine.renderFrames(stoppedAt)
        engine.stop()
        val out = engine.renderFrames(7 * SR)
        assertFalse(engine.snapshot.idle, "the echoes are still coming")
        // the snare hits at frame 2400 and its echoes come every two seconds, the first one after the voice has ended
        fun echo(k: Int) = peak(out, (2400 + 2 * SR * k - stoppedAt) * 2, (2400 + 2 * SR * k - stoppedAt + 4800) * 2)
        assertTrue(echo(1) > 0.1f, "first echo ${echo(1)}")
        assertTrue(echo(2) > 0.05f, "second echo ${echo(2)}, which a two second grace would have cut off")
        assertTrue(echo(3) > 0.02f, "third echo ${echo(3)}")
    }

    @Test
    fun theEngineGoesIdleOnlyOnceTheTailHasDied() {
        val engine = engineOf()
        engine.evaluateOk("""s("sd").room(1).size(1)""")
        engine.play()
        engine.renderFrames(SR / 2)
        engine.stop()
        engine.renderFrames(SR / 2)

        val peaks = ArrayList<Float>() // per block
        var idleAt = -1
        val buffer = FloatArray(BLOCK * 2)
        for (block in 0 until 60 * SR / BLOCK) {
            engine.render(buffer, BLOCK)
            peaks.add(peak(buffer))
            if (engine.snapshot.idle) {
                idleAt = block
                break
            }
        }
        assertTrue(idleAt > 3 * SR / BLOCK, "a size 1 reverb outlasts the two second grace")
        val window = (4.1 * SR / BLOCK).toInt()
        assertTrue(peaks.subList(idleAt - window, idleAt).all { it < 1e-4f }, "nothing audible is cut off")
    }

    // ------------------------------------------------------------------------------- misc

    @Test
    fun theSoundListsForTheSoundsScreen() {
        assertEquals(listOf("sine", "sawtooth", "square", "triangle", "white", "pink", "brown"), CosicEngine.SYNTHS)
        assertEquals(listOf("bd", "sd", "hh", "oh", "cp", "rim", "lt", "mt", "ht", "cr"), CosicEngine.DRUMS)
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
