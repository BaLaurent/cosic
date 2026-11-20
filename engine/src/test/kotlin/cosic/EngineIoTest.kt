package cosic

import cosic.audio.SR
import cosic.audio.peak
import cosic.pattern.Rational
import cosic.script.ReplSession
import cosic.script.SessionHost
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private const val CYCLE = 96_000 // frames per cycle at cps 0.5 and 48 kHz

private fun waitFor(what: String, millis: Long = 5000, condition: () -> Boolean) {
    val deadline = System.nanoTime() + millis * 1_000_000
    while (!condition()) {
        check(System.nanoTime() < deadline) { "timed out waiting for $what" }
        Thread.sleep(5)
    }
}

private fun CosicEngine.lines(): List<String> = console.entries.map { it.message }

/** What the engine shares with the app and with the programs: console, outputs, inputs, scopes, and the hooks of patterns running through them. */
class EngineIoTest {
    @Test
    fun evaluatingWritesItsOutcomeToTheConsole() {
        val engine = engineOf()
        engine.evaluateOk("""s("sine")""")
        assertEquals(listOf("[eval] code updated"), engine.lines())
        assertIs<EvalReport.Failed>(engine.evaluate("foo(1)"))
        assertEquals("[eval] error: foo is not defined", engine.lines().last())
        assertEquals("error", engine.console.entries.last().type)
    }

    @Test
    fun whatTheProgramLogsIsOnTheConsoleToo() {
        val engine = engineOf()
        engine.evaluateOk("""console.log('hello from the program'); s("sine")""")
        assertContains(engine.lines(), "hello from the program")
    }

    @Test
    fun playAndStopAreLoggedOncePerChange() {
        val engine = engineOf()
        engine.evaluateOk("""s("sine")""")
        engine.play()
        engine.play() // already started: not another line
        engine.stop()
        engine.stop()
        engine.play()
        assertEquals(listOf("[eval] code updated", "[cyclist] start", "[cyclist] stop", "[cyclist] start"), engine.lines())
    }

    @Test
    fun theOutputsGetTheClockOfTheEngine() {
        val engine = engineOf()
        assertSame(engine.clock, engine.outputs.clock)
        assertSame(engine.console, engine.outputs.console)
    }

    @Test
    fun theProgramInfoIsKeptAndAFailedProgramLeavesIt() {
        val engine = engineOf()
        assertNull(engine.program)
        val ok = engine.evaluateOk("""$: s("sine").gain(slider(0.5, 0, 1))""")
        assertSame(ok.program, engine.program)
        assertEquals(1, ok.program.sliders.size)
        assertEquals(emptySet(), ok.program.inputsUsed)
        assertNull(ok.program.visuals)
        assertEquals(emptyMap(), ok.program.metadata)
        assertIs<EvalReport.Failed>(engine.evaluate("foo(1)"))
        assertSame(ok.program, engine.program)
    }

    @Test
    fun theMetadataOfTheCodeIsReadByTheParserTheHostSet() {
        val engine = engineOf()
        engine.metadataParser = { code -> mapOf("title" to "len ${code.length}") }
        assertEquals(mapOf("title" to "len 9"), engine.evaluateOk("""s("sine")""").program.metadata)
        engine.metadataParser = { throw IllegalStateException("a broken parser") }
        assertEquals(emptyMap(), engine.evaluateOk("""s("sine")""").program.metadata)
    }

    @Test
    fun theSlidersOfAProgramLiveInTheInputsOfTheEngine() {
        val engine = engineOf()
        val ok = engine.evaluateOk("""$: s("sine").gain(slider(0.5, 0, 1))""")
        val id = ok.program.sliders.single().id
        assertEquals(0.5, engine.inputs.sliders.get(id), "the value written in the code")
        engine.inputs.sliders.set(id, 0.75)
        assertEquals(0.75, engine.inputs.sliders.get(id))
    }

    @Test
    fun aSliderIsReadWheneverItsPatternIsQueried() {
        val host = SessionHost()
        val program = ReplSession(host).evaluate("""$: s("sine").gain(slider(0.5, 0, 1))""")
        fun gain() = program.pattern.queryArc(Rational.ZERO, Rational.ONE).single().show(true)
        assertEquals("[ 0/1 → 1/1 | s:sine gain:0.5 ]", gain())
        host.effects.inputs.sliders.set(program.sliders.single().id, 0.75)
        assertEquals("[ 0/1 → 1/1 | s:sine gain:0.75 ]", gain())
    }

    @Test
    fun everyRenderedBlockEndsTheBlockOfTheScopeTaps() {
        val engine = engineOf()
        engine.scopes.add("a", floatArrayOf(1f, 2f), 0, 2)
        engine.render(FloatArray(4), 2)
        val out = FloatArray(2)
        assertTrue(engine.scopes.read("a", 2, out))
        assertEquals(listOf(1f, 2f), out.toList())
        engine.render(FloatArray(4), 2) // no voice made sound in this block: the tap takes zeros
        val both = FloatArray(4)
        assertTrue(engine.scopes.read("a", 4, both))
        assertEquals(listOf(1f, 2f, 0f, 0f), both.toList())
    }

    // ---------------------------------------------------------------------------------- playFrom

    @Test
    fun playStartsAfterTheStartDelayAndPlayFromWithoutOne() {
        val delayed = engineOf()
        delayed.evaluateOk("""s("sine*4")""")
        delayed.play()
        assertEquals(listOf(2400L, 26_400L), delayed.highlightStarts(30_000).take(2))

        val now = engineOf()
        now.evaluateOk("""s("sine*4")""")
        now.playFrom(0.0)
        assertEquals(listOf(0L, 24_000L), now.highlightStarts(30_000).take(2))
    }

    @Test
    fun playFromStartsInTheMiddleOfTheProgramAndSkipsWhatBeganBefore() {
        val engine = engineOf()
        engine.evaluateOk("""s("sine*4")""")
        engine.playFrom(1.5) // cycle 1 sounds from frame 0; the onset at 1.5 is the third of the four
        assertEquals(listOf(0L, 24_000L), engine.highlightStarts(30_000).take(2))

        val between = engineOf()
        between.evaluateOk("""s("sine*4")""")
        between.playFrom(1.6) // between two onsets: the next one is at 1.75, which is 0.15 cycles = 14400 frames away
        assertEquals(14_400L, between.highlightStarts(40_000).first())
    }

    @Test
    fun theFirstBlockAfterPlayFromSoundsAtOnce() {
        val engine = engineOf()
        engine.evaluateOk("""note(60).s("sine").release(0.01)""")
        engine.playFrom(0.0)
        assertTrue(peak(engine.renderFrames(1000)) > 0.01f, "sound within the first 1000 frames")

        val delayed = engineOf()
        delayed.evaluateOk("""note(60).s("sine").release(0.01)""")
        delayed.play()
        assertEquals(0f, peak(delayed.renderFrames(2000)), "the 50 ms of play() are silent")
    }

    @Test
    fun playFromAProgramThatIsPlayingStartsOverThere() {
        val engine = engineOf()
        engine.evaluateOk("""s("sine*4")""")
        engine.play()
        engine.renderFrames(CYCLE)
        engine.playFrom(2.0)
        val starts = engine.highlightStarts(30_000)
        assertEquals(CYCLE.toLong(), starts.first(), "the first onset of cycle 2 is at the first frame rendered after the call")
    }

    // --------------------------------------------------------------------------------------------- hooks

    @Test
    fun onScheduleRunsItsFunctionOnTheDispatcherWithTheTimeAndTheTempo() {
        val engine = engineOf()
        engine.evaluateOk("""s("sine*2").onSchedule((hap, t, cps) => { console.log('hap ' + hap.value.s + ' ' + t + ' ' + cps) })""")
        engine.play()
        engine.renderFrames(CYCLE + 1000)
        waitFor("the callbacks") { engine.lines().count { it.startsWith("hap ") } >= 2 }
        // the first is due 50 ms after the frames rendered so far, the second half a cycle (1 s) later
        assertEquals(listOf("hap sine 0.05 0.5", "hap sine 1.05 0.5"), engine.lines().filter { it.startsWith("hap ") }.take(2))
        engine.outputs.shutdown()
    }

    @Test
    fun aDominantHookSilencesTheHapsItIsAttachedToAndAnotherOneDoesNot() {
        val dominant = engineOf()
        dominant.evaluateOk("""note(60).s("sine").onSchedule(() => {})""")
        dominant.play()
        assertEquals(0f, peak(dominant.renderFrames(CYCLE)), "the default output is not made")

        val shared = engineOf()
        shared.evaluateOk("""note(60).s("sine").onSchedule(() => {}, false)""")
        shared.play()
        assertTrue(peak(shared.renderFrames(CYCLE)) > 0.01f)
        dominant.outputs.shutdown()
        shared.outputs.shutdown()
    }

    @Test
    fun aDominantHookDoesNotMakeTheProgramWarnAboutSoundsItNeverPlays() {
        val engine = engineOf()
        assertEquals(emptyList(), engine.evaluateOk("""s("nope").onSchedule(() => {})""").warnings)
        assertEquals(listOf("sound nope not found! Is it loaded?"), engine.evaluateOk("""s("nope")""").warnings)
        engine.outputs.oscTarget = true
        assertEquals(emptyList(), engine.evaluateOk("""s("nope")""").warnings, "while everything goes to OSC no sound is looked up")
    }

    @Test
    fun logWritesTheHapToTheConsoleWhenItIsScheduled() {
        val engine = engineOf()
        engine.evaluateOk("""s("sine").log()""")
        engine.play()
        engine.renderFrames(CYCLE / 4)
        waitFor("the log line") { engine.lines().any { it.startsWith("[hap] ") } }
        assertEquals("[hap] 0/1 → 1/1: s:sine", engine.lines().last { it.startsWith("[hap] ") })
        engine.outputs.shutdown()
    }

    @Test
    fun onTriggerTimeRunsTheFunctionWhenTheHapSoundsNotWhenItIsScheduled() {
        val engine = engineOf()
        // the clock says the first frame is heard 400 ms from now: a callback may not run before that
        val base = System.nanoTime() + 400_000_000
        engine.outputs.clock = FrameClock { frame -> base + frame * 1_000_000_000L / SR }
        engine.evaluateOk("""s("sine").onTriggerTime((hap) => { console.log('sounding ' + hap.value.s) })""")
        engine.play()
        engine.renderFrames(2000)
        Thread.sleep(150)
        assertTrue(engine.lines().none { it.startsWith("sounding") }, "150 ms into a 450 ms wait")
        waitFor("the function", millis = 3000) { engine.lines().any { it == "sounding sine" } }
        engine.outputs.shutdown()
    }

    @Test
    fun onTriggerGivesTheFunctionTheHapTheTimeTheTempoAndTheTargetTime() {
        val engine = engineOf()
        engine.evaluateOk("""s("sine").onTrigger((hap, now, cps, target) => { console.log('trigger ' + hap.value.s + ' ' + cps + ' ' + target) })""")
        assertEquals("onTrigger is deprecated.. use onSchedule(hap, time, cps) instead", engine.lines().first())
        assertEquals("warning", engine.console.entries.first().type)
        engine.play()
        engine.renderFrames(CYCLE / 4)
        waitFor("the callback") { engine.lines().any { it.startsWith("trigger ") } }
        assertEquals("trigger sine 0.5 0.05", engine.lines().last { it.startsWith("trigger ") })
        engine.outputs.shutdown()
    }

    @Test
    fun aFunctionThatThrowsIsAnErrorLineAndDoesNotStopThePlayback() {
        val engine = engineOf()
        engine.evaluateOk("""s("sine*4").onSchedule(() => { throw new Error('nope, not today') }, false)""")
        engine.play()
        engine.renderFrames(CYCLE / 2)
        waitFor("the error line") { engine.lines().any { it.startsWith("[getSchedule] error: ") } }
        assertContains(engine.lines().last { it.startsWith("[getSchedule] error: ") }, "nope, not today")
        assertNull(engine.snapshot.runtimeError)
        engine.outputs.shutdown()
    }
}
