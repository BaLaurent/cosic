package cosic

import cosic.audio.peak
import cosic.io.Prebake
import cosic.io.SettingsSink
import cosic.io.ShareLink
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val CYCLE = 96_000 // frames per cycle at cps 0.5 and 48 kHz

private fun waitFor(what: String, millis: Long = 5000, condition: () -> Boolean) {
    val deadline = System.nanoTime() + millis * 1_000_000
    while (!condition()) {
        check(System.nanoTime() < deadline) { "timed out waiting for $what" }
        Thread.sleep(5)
    }
}

/** What a program can change in the editor (`theme`, `fontFamily`, `fontSize`), and the prebake script that runs before a session's first program. */
class SettingsAndPrebakeTest {
    private class Recorder : SettingsSink {
        val calls = CopyOnWriteArrayList<Pair<String, Any?>>()

        override fun set(key: String, value: Any?) {
            calls += key to value
        }
    }

    /** Plays [code] for a cycle and returns what reached the sink, the first call of each setting. */
    private fun settingsOf(code: String): List<Pair<String, Any?>> {
        val engine = engineOf()
        val recorder = Recorder()
        engine.outputs.settings = recorder
        engine.evaluateOk(code)
        engine.play()
        engine.renderFrames(CYCLE / 2)
        waitFor("a setting") { recorder.calls.isNotEmpty() }
        engine.outputs.shutdown()
        return recorder.calls.toList()
    }

    // ---- theme, fontFamily, fontSize --------------------------------------------------------------------------------------

    @Test
    fun aSettingReachesTheSinkWhenItsHapIsScheduled() {
        assertEquals("fontSize" to 24.0, settingsOf("""s("sine").fontSize(24)""").first())
        assertEquals("theme" to "monokai", settingsOf("""s("sine").theme('monokai')""").first())
        assertEquals("fontFamily" to "Fira Code", settingsOf("""s("sine").fontFamily('Fira Code')""").first())
    }

    @Test
    fun aMiniNotationValueIsTheValueOfTheSetting() {
        assertEquals("theme" to "monokai", settingsOf("""s("sine").theme("monokai")""").first())
    }

    @Test
    fun aListIsJoinedWithSpacesAsTheReplDoes() {
        assertEquals("theme" to "a b", settingsOf("""s("sine").theme("a:b")""").first())
        assertEquals("fontFamily" to "a 1 b", settingsOf("""s("sine").fontFamily(['a', 1, 'b'])""").first())
    }

    @Test
    fun theGlobalFormTakesThePatternLast() {
        assertEquals("fontSize" to 30.0, settingsOf("""fontSize(30, s("sine"))""").first())
        assertEquals("fontSize" to 31.0, settingsOf("""s("sine").apply(fontSize(31))""").first())
    }

    @Test
    fun aPatternedSettingChangesWithTheCycle() {
        val engine = engineOf()
        val recorder = Recorder()
        engine.outputs.settings = recorder
        engine.evaluateOk("""s("sine").fontSize("<14 18>")""")
        engine.play()
        engine.renderFrames(CYCLE + 1000)
        waitFor("two cycles") { recorder.calls.size >= 2 }
        engine.outputs.shutdown()
        assertEquals(listOf(14.0, 18.0), recorder.calls.map { it.second }.take(2))
    }

    @Test
    fun theHapsStillSound() {
        val engine = engineOf()
        engine.outputs.settings = Recorder()
        engine.evaluateOk("""note(60).s("sine").fontSize(20)""")
        engine.play()
        assertTrue(peak(engine.renderFrames(CYCLE)) > 0.01f, "the hook is not dominant")
        engine.outputs.shutdown()
    }

    @Test
    fun withoutASinkNothingHappens() {
        val engine = engineOf()
        engine.evaluateOk("""s("sine").theme('monokai')""")
        engine.play()
        engine.renderFrames(CYCLE / 2)
        engine.outputs.shutdown()
        assertEquals(listOf("[eval] code updated", "[cyclist] start"), engine.console.entries.map { it.message })
    }

    @Test
    fun everyEngineSendsToItsOwnSink() {
        val one = engineOf()
        val other = engineOf()
        val first = Recorder()
        val second = Recorder()
        one.outputs.settings = first
        other.outputs.settings = second
        one.evaluateOk("""s("sine").fontSize(11)""")
        other.evaluateOk("""s("sine").fontSize(22)""")
        one.play()
        one.renderFrames(CYCLE / 2)
        waitFor("the first engine") { first.calls.isNotEmpty() }
        other.play()
        other.renderFrames(CYCLE / 2)
        waitFor("the second engine") { second.calls.isNotEmpty() }
        one.outputs.shutdown()
        other.outputs.shutdown()
        assertEquals(setOf(11.0), first.calls.map { it.second }.toSet())
        assertEquals(setOf(22.0), second.calls.map { it.second }.toSet())
    }

    // ---- the prebake script -----------------------------------------------------------------------------------------------

    @Test
    fun theDefaultScriptDefinesRatchet() {
        val engine = engineOf()
        assertEquals(Prebake.DEFAULT, engine.prebake)
        assertIs<EvalReport.Ok>(engine.evaluate("""s("bd*4").ratchet()"""))
        assertEquals(listOf("[eval] code updated"), engine.console.entries.map { it.message })
    }

    @Test
    fun withoutAScriptRatchetIsNotThere() {
        val engine = engineOf()
        engine.prebake = null
        val report = engine.evaluate("""s("bd*4").ratchet()""")
        assertIs<EvalReport.Failed>(report)
        assertContains(report.message, "ratchet")
    }

    @Test
    fun aScriptOfTheUsersDefinesWhatEveryProgramUses() {
        val engine = engineOf()
        engine.prebake = "register('double', (pat) => pat.fast(2))"
        assertIs<EvalReport.Ok>(engine.evaluate("""s("bd").double()"""))
        assertIs<EvalReport.Ok>(engine.evaluate("""s("hh").double()"""), "and in the programs after it")
    }

    @Test
    fun theScriptRunsOnceForASession() {
        val engine = engineOf()
        engine.prebake = "console.log('prebaked')"
        engine.evaluateOk("""s("bd")""")
        engine.evaluateOk("""s("bd")""")
        assertEquals(1, engine.console.entries.count { it.message == "prebaked" })
    }

    @Test
    fun aNewSessionRunsItAgainAndSoDoesANewScript() {
        val engine = engineOf()
        engine.prebake = "console.log('prebaked')"
        engine.evaluateOk("""s("bd")""")
        engine.resetSession()
        engine.evaluateOk("""s("bd")""")
        assertEquals(2, engine.console.entries.sumOf { if (it.message == "prebaked") it.count else 0 })
        engine.prebake = "console.log('changed')"
        engine.evaluateOk("""s("bd")""")
        assertContains(engine.console.entries.map { it.message }, "changed")
    }

    @Test
    fun aScriptThatFailsIsReportedAndTheProgramRunsAnyway() {
        val engine = engineOf()
        engine.prebake = "foo("
        assertIs<EvalReport.Ok>(engine.evaluate("""s("bd")"""))
        val lines = engine.console.entries
        assertTrue(lines.any { it.message.startsWith("[prebake] error: ") && it.type == "error" }, lines.toString())
    }

    @Test
    fun whatTheScriptLabelsIsNotPartOfTheProgram() {
        val engine = engineOf()
        engine.prebake = """$: s("hh")"""
        engine.evaluateOk("""note(60).s("sine")""")
        engine.play()
        assertTrue(peak(engine.renderFrames(CYCLE)) > 0.01f)
        engine.outputs.shutdown()
    }

    // ---- what the web REPL has ---------------------------------------------------------------------------------------------

    private val oracle by lazy {
        Json.parseToJsonElement(
            javaClass.getResourceAsStream("/share/oracle.json")?.bufferedReader()?.readText()
                ?: error("/share/oracle.json is missing: run `node tools/strudel-ref/share-oracle.mjs`"),
        ).jsonObject
    }

    @Test
    fun theDefaultScriptIsTheOneOfTheWebRepl() {
        assertEquals(oracle["prebake"]!!.jsonPrimitive.content, Prebake.DEFAULT)
    }

    @Test
    fun theLinkTheShareButtonMakesIsTheOneForTheScriptAndTheCode() {
        val code = "\$: s(\"bd*4\")"
        val hash = oracle["encode"]!!.jsonArray.map { it.jsonObject }
            .single { it["code"]?.jsonPrimitive?.content == "${Prebake.DEFAULT}\n$code" }["hash"]!!.jsonPrimitive.content
        assertEquals("https://strudel.cc/#$hash", ShareLink.forSharing(code, Prebake.DEFAULT))
    }
}
