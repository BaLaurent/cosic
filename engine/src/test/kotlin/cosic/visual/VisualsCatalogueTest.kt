package cosic.visual

import cosic.EvalReport
import cosic.engineOf
import cosic.evaluateOk
import cosic.pattern.Rational
import cosic.renderFrames
import cosic.audio.SR
import cosic.script.evaluateProgram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The visuals as a program asks for them: through the language and the engine. */
class VisualsCatalogueTest {
    private fun valuesOf(code: String): List<Any?> = evaluateProgram(code).pattern.queryArc(Rational.ZERO, Rational.ONE).map { it.value }

    @Test
    fun aProgramWithoutVisualsHasNoSession() {
        val engine = engineOf()
        assertNull(engine.evaluateOk("""s("bd")""").program.visuals)
    }

    @Test
    fun aWidgetCallMakesASessionWithTheSurfaceOfItsWidgetIdFromTheTranspiler() {
        val engine = engineOf()
        val ok = engine.evaluateOk("""s("bd*2")._scope()""")
        val session = ok.program.visuals as VisualSession
        val widget = ok.program.widgets.single()
        assertEquals("_scope", widget.type)
        assertEquals(listOf(VisualSurface.BACKGROUND_KEY, widget.widgetId), session.surfaces.map { it.key })
        val surface = session.surfaces.last()
        assertEquals(500.0 to 60.0, surface.width to surface.height)
        assertEquals(widget.to, surface.widget?.callEnd, "the call ends where the transpiler says it does")
        assertEquals(WidgetType.SCOPE, surface.widget?.type)
    }

    @Test
    fun severalWidgetsKeepTheirOrderAndGetTheirOwnIds() {
        val engine = engineOf()
        val code = "s(\"bd\")._pianoroll({ width: 300 })\ns(\"hh\")._scope()\ns(\"cp\")._scope()\ns(\"sd\")._print()"
        val ok = engine.evaluateOk(code)
        val session = ok.program.visuals as VisualSession
        assertEquals(ok.program.widgets.map { it.widgetId }, session.surfaces.drop(1).map { it.key })
        assertEquals(listOf("_widget__pianoroll_0", "_widget__scope_0", "_widget__scope_1", "_widget__print_0"), session.surfaces.drop(1).map { it.key })
        assertEquals(300.0, session.surfaces[1].width)
        assertEquals(600.0 to 100.0, session.surfaces.last().width to session.surfaces.last().height)
    }

    @Test
    fun backgroundVisualsNeedNoWidgetAndScopeAnalyzesWithTheIdOfTheLoop() {
        val engine = engineOf()
        val ok = engine.evaluateOk("""s("bd*2").pianoroll()""")
        val session = ok.program.visuals as VisualSession
        assertTrue(session.hasBackground)
        assertEquals(1, session.surfaces.size)
        assertEquals(listOf(mapOf("s" to "bd", "analyze" to 1.0), mapOf("s" to "bd", "analyze" to 1.0)), valuesOf("""s("bd*2").scope()"""))
        assertEquals(listOf(mapOf("s" to "bd", "analyze" to 1.0)), valuesOf("""s("bd").fscope()"""))
        assertEquals(listOf(mapOf("s" to "bd", "analyze" to "w")), valuesOf("""s("bd").spectrum({ id: "w" })"""))
    }

    @Test
    fun theValuesOfPatternsAreNotChangedByTheVisualsThatOnlyWatch() {
        for (call in listOf("pianoroll()", "punchcard()", "wordfall()", "spiral()", "pitchwheel()", "print()", "_pianoroll()", "_spiral()", "_pitchwheel()", "_print()")) {
            assertEquals(listOf(mapOf("s" to "bd")), valuesOf("""s("bd").$call"""), call)
        }
    }

    @Test
    fun allPianorollAndItsFunctionForm() {
        val engine = engineOf()
        val session = engine.evaluateOk("""$: s("bd*2")
all(pianoroll)""").program.visuals as VisualSession
        assertTrue(session.hasBackground)
        val withOptions = engine.evaluateOk("""$: s("bd*2")
all(pianoroll({ cycles: 8 }))""").program.visuals as VisualSession
        assertTrue(withOptions.hasBackground)
        assertTrue(valuesOf("""pianoroll(s("bd"))""").isNotEmpty())
    }

    @Test
    fun theSessionDrawsAfterTheEngineHasAttachedTheProgram() {
        val engine = engineOf()
        val ok = engine.evaluateOk("""s("bd*4")._pianoroll()
$: s("hh*2").punchcard()""")
        val session = ok.program.visuals as VisualSession
        engine.play()
        engine.renderFrames(SR / 2)
        for (t in listOf(0.0, 0.05, 0.1, 0.3)) session.tick(t, true)
        val widget = session.surfaces.last()
        val calls = RecordingCanvas(widget.width, widget.height).also { session.paint(widget, it) }.text()
        assertTrue(calls.any { it.startsWith("strokeRect") || it.startsWith("fillRect") }, calls.toString())
        val background = RecordingCanvas(400.0, 300.0).also { session.paint(session.surfaces.first(), it) }.text()
        assertTrue(background.any { it.startsWith("fillRect") }, "the punchcard of the whole program: $background")
    }

    @Test
    fun printWritesALineToTheConsoleForEveryHapThatIsScheduled() {
        val engine = engineOf()
        engine.evaluateOk("""s("bd sd").print()""")
        engine.play()
        engine.renderFrames(SR)
        val deadline = System.nanoTime() + 3_000_000_000L
        while (engine.console.entries.none { it.message.startsWith("[print(line:1)]") } && System.nanoTime() < deadline) Thread.sleep(10)
        val lines = engine.console.entries.map { it.message }.filter { it.startsWith("[print(line:1)]") }
        assertTrue(lines.isNotEmpty(), engine.console.entries.map { it.message }.toString())
        assertEquals("[print(line:1)] 0       : s:bd", lines.first())
    }

    @Test
    fun theCallsOfPrintAreNumberedInTheProgram() {
        val engine = engineOf()
        engine.evaluateOk("""$: s("bd").print()
$: s("hh").print()""")
        engine.play()
        engine.renderFrames(SR)
        val deadline = System.nanoTime() + 3_000_000_000L
        fun labels() = engine.console.entries.mapNotNull { Regex("""^\[print\(line:(\d+)\)\]""").find(it.message)?.groupValues?.get(1) }.toSet()
        while (labels().size < 2 && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals(setOf("1", "2"), labels())
    }

    @Test
    fun whatNeedsABrowserIsAcceptedAndSaysSo() {
        val engine = engineOf()
        val ok = engine.evaluateOk(
            """s("bd").draw(() => {})
$: s("hh").onPaint(() => {})
$: x(0.5).y(0.5).animate({})
const ctx = getDrawContext()
ctx.fillRect(0, 0, 1, 1)
backgroundImage("x.png")""",
        )
        assertNotNull(ok)
        val messages = engine.console.entries.map { it.message }
        for (name in listOf("draw", "onPaint", "animate", "getDrawContext", "backgroundImage")) {
            assertTrue(messages.any { it == "[draw] $name is not supported in Cosic" }, "$name: $messages")
        }
        assertEquals(emptyList(), valuesOf("""s("bd").animate()"""), "animate returns silence, as it does in Strudel")
    }

    @Test
    fun theControlsOfAnimateAreControlsAndRescaleScales() {
        assertEquals(listOf(mapOf("s" to "bd", "x" to 0.25, "y" to 0.5, "w" to 1.0, "h" to 2.0, "angle" to 3.0, "r" to 4.0, "fill" to "red")),
            valuesOf("""s("bd").x(0.25).y(0.5).w(1).h(2).angle(3).r(4).fill("red")"""))
        assertEquals(listOf(mapOf("x" to 0.5)), valuesOf("""x(0.5)"""))
        val failure = engineOf().evaluate("""s("bd").zoomIn(2)""")
        assertTrue(failure is EvalReport.Failed && failure.message == "pat.rescale(...).move is not a function", failure.toString())
    }

    @Test
    fun namesOfAnimateControlsStayFreeForTheVariablesOfAProgram() {
        val engine = engineOf()
        engine.evaluateOk("""const x = 5, w = 2, h = 3
let r = 1
function fill() { return 7 }
s("bd").gain(x * w * h * r + fill())""")
    }

    @Test
    fun anOptionsArgumentThatIsNotAnObjectIsTakenAsNoOptions() {
        val engine = engineOf()
        engine.evaluateOk("""s("bd")._scope(5)
$: s("hh").pianoroll(null)""")
    }
}
