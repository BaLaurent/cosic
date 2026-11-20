package cosic.visual

import cosic.io.ScopeTaps
import cosic.pattern.CosicError
import cosic.pattern.Pattern
import cosic.script.evaluateProgram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class VisualSessionTest {
    private fun pattern(code: String): Pattern = evaluateProgram(code).pattern

    private val bg = VisualSurface.BACKGROUND_KEY

    private fun paintCalls(session: VisualSession, surface: VisualSurface = session.surfaces.first()): List<String> =
        RecordingCanvas(200.0, 100.0).also { session.paint(surface, it) }.text()

    // ---- surfaces -------------------------------------------------------------------------------------------------------------------------------------

    @Test
    fun theBackgroundComesFirstAndTheWidgetsFollowInTheOrderTheirCallsEnd() {
        val session = VisualSession()
        val pat = pattern("""s("bd")""")
        session.widget(WidgetType.SCOPE, "_widget__scope_1", VisualOptions(), pat, 80)
        session.widget(WidgetType.PIANOROLL, "_widget__pianoroll_0", VisualOptions(), pat, 30)
        val surfaces = session.surfaces
        assertEquals(listOf(bg, "_widget__pianoroll_0", "_widget__scope_1"), surfaces.map { it.key })
        assertTrue(surfaces[0].isBackground)
        assertEquals(WidgetType.PIANOROLL, surfaces[1].widget?.type)
        assertEquals(0, surfaces[1].widget?.index)
        assertEquals(1, surfaces[2].widget?.index)
        assertEquals(30, surfaces[1].widget?.callEnd)
        assertEquals(session.surfaces[1], session.surfaces[1], "the same surface objects every time")
        assertEquals(listOf("_widget__pianoroll_0", "_widget__scope_1"), session.widgetSpecs.map { it.id })
    }

    @Test
    fun widgetsHaveTheSizesOfWidgetMjs() {
        fun size(type: WidgetType, options: Map<String, Any?> = emptyMap()): Pair<Double, Double> {
            val session = VisualSession()
            session.widget(type, "_widget_${type.method}_0", VisualOptions(options), pattern("""s("bd")"""), 1)
            val surface = session.surfaces.last()
            return surface.width to surface.height
        }
        assertEquals(500.0 to 60.0, size(WidgetType.PIANOROLL))
        assertEquals(500.0 to 60.0, size(WidgetType.PUNCHCARD))
        assertEquals(500.0 to 60.0, size(WidgetType.SCOPE))
        assertEquals(275.0 to 275.0, size(WidgetType.SPIRAL))
        assertEquals(200.0 to 200.0, size(WidgetType.PITCHWHEEL))
        assertEquals(200.0 to 200.0, size(WidgetType.SPECTRUM))
        assertEquals(600.0 to 100.0, size(WidgetType.PRINT))
        assertEquals(300.0 to 90.0, size(WidgetType.PIANOROLL, mapOf("width" to 300.0, "height" to 90.0)))
        assertEquals(100.0 to 100.0, size(WidgetType.SPIRAL, mapOf("size" to 100.0)))
        assertEquals(100.0 to 50.0, size(WidgetType.SPIRAL, mapOf("size" to 100.0, "height" to 50.0)))
        assertEquals(150.0 to 150.0, size(WidgetType.PITCHWHEEL, mapOf("size" to 150.0)))
        assertEquals(320.0 to 200.0, size(WidgetType.SPECTRUM, mapOf("width" to 320.0)))
        assertEquals(400.0 to 40.0, size(WidgetType.PRINT, mapOf("width" to 400.0, "height" to 40.0)))
    }

    @Test
    fun theWidgetMethodsReturnTheReceiverTaggedWithTheWidgetId() {
        val session = VisualSession()
        val haps = session.widget(WidgetType.PIANOROLL, "_widget__pianoroll_0", VisualOptions(), pattern("""s("bd")"""), 5)
            .queryArc(cosic.pattern.Rational.ZERO, cosic.pattern.Rational.ONE)
        assertEquals(1, haps.size)
        assertTrue(haps.single().hasTag("_widget__pianoroll_0"))
        val scoped = session.widget(WidgetType.SCOPE, "_widget__scope_0", VisualOptions(), pattern("""s("bd")"""), 9)
            .queryArc(cosic.pattern.Rational.ZERO, cosic.pattern.Rational.ONE)
        assertEquals(mapOf("s" to "bd", "analyze" to "_widget__scope_0"), scoped.single().value)
        assertTrue(scoped.single().hasTag("_widget__scope_0"))
    }

    // ---- frames ----------------------------------------------------------------------------------------------------------------------------------------

    @Test
    fun aProgramThatHasNotPlayedGetsOneFrameAndThenStandsStill() {
        val session = VisualSession()
        session.pianoroll(pattern("""note("c e g")"""), VisualOptions())
        val surface = session.surfaces.first()
        assertEquals(0L, session.versionOf(surface))
        assertTrue(session.tick(0.0, false))
        val version = session.versionOf(surface)
        assertTrue(version > 0)
        assertTrue(paintCalls(session).isNotEmpty(), "the first frame is drawn")
        assertFalse(session.tick(0.0, false), "nothing moves while the program does not play")
        assertEquals(version, session.versionOf(surface))
        assertTrue(session.tick(0.5, true))
        assertNotEquals(version, session.versionOf(surface))
    }

    @Test
    fun nothingIsDrawnBeforeTheFirstFrame() {
        val session = VisualSession()
        session.pianoroll(pattern("""note("c")"""), VisualOptions())
        assertEquals(emptyList(), paintCalls(session))
    }

    @Test
    fun theLoopsFollowThePlayhead() {
        val session = VisualSession()
        session.pianoroll(pattern("""s("bd*4")"""), VisualOptions(mapOf("id" to "p")))
        session.tick(0.0, true)
        val first = session.loopMemory("p").map { it.show(true) }
        assertTrue(first.isNotEmpty())
        session.tick(1.0, true)
        val later = session.loopMemory("p")
        assertTrue(later.any { it.whole!!.begin.toDouble() >= 2.0 }, "the haps ahead of the playhead are there")
        assertTrue(later.none { it.whole!!.begin.toDouble() < -1.0 })
    }

    @Test
    fun aPlayheadThatGoesBackBeginsAgain() {
        val session = VisualSession()
        session.pianoroll(pattern("""s("bd*4")"""), VisualOptions(mapOf("id" to "p")))
        session.tick(3.0, true)
        assertTrue(session.loopMemory("p").any { it.whole!!.begin.toDouble() >= 4.0 })
        session.tick(0.5, true)
        val memory = session.loopMemory("p")
        assertTrue(memory.none { it.whole!!.begin.toDouble() > 0.5 + 2.0 + 1e-9 }, "what was ahead of the old time is dropped: ${memory.map { it.show(true) }}")
        assertTrue(memory.any { it.whole!!.begin.toDouble() in 0.5..2.5 })
    }

    @Test
    fun resetClearsTheBackgroundOnceAndTheFramesBeginAgainWhenTheProgramPlaysAgain() {
        val session = VisualSession()
        session.pianoroll(pattern("""note("c e g")"""), VisualOptions())
        val surface = session.surfaces.first()
        session.tick(0.5, true)
        assertTrue(paintCalls(session).any { it.startsWith("strokeRect") || it.startsWith("fillRect") })
        session.reset()
        assertEquals(listOf("clearRect(0, 0, 200, 100)"), paintCalls(session))
        assertEquals(emptyList(), paintCalls(session), "after that nothing is drawn: the loops have forgotten their haps")
        assertFalse(session.tick(0.6, false))
        assertTrue(session.tick(0.6, true))
        assertTrue(paintCalls(session, surface).isNotEmpty())
    }

    @Test
    fun aNewSessionCanTakeOverThePastOfTheOneItReplaces() {
        val old = VisualSession()
        old.pianoroll(pattern("""s("bd*4")"""), VisualOptions(mapOf("id" to "p")))
        old.tick(0.0, true)
        old.tick(1.0, true)
        val before = old.loopMemory("p").filter { it.whole!!.begin.toDouble() < 1.0 }.map { it.show(true) }
        assertTrue(before.isNotEmpty())

        val fresh = VisualSession()
        fresh.pianoroll(pattern("""s("hh*8")"""), VisualOptions(mapOf("id" to "p")))
        fresh.continueFrom(old)
        fresh.tick(1.0, true)
        val kept = fresh.loopMemory("p").map { it.show(true) }
        assertTrue(before.all { it in kept }, "the haps that had started stay in view: $before in $kept")
        assertTrue(kept.any { "hh" in it }, "the new program's haps ahead are there")
        assertTrue(kept.none { "hh" in it && "0/1 →" in it && it.startsWith("[ 0/1") }, "but the old past is the old program's, not the new one's")
    }

    @Test
    fun aLaterLoopWithTheSameKeyReplacesTheEarlierOne() {
        val session = VisualSession()
        session.pianoroll(pattern("""s("bd")"""), VisualOptions())
        session.pianoroll(pattern("""s("hh")"""), VisualOptions())
        session.tick(0.0, true)
        assertTrue(session.loopMemory("1").all { "hh" in it.show(true) })
        assertEquals(1, session.surfaces.size)
        assertTrue(session.hasBackground)
    }

    @Test
    fun queriesThatFailAreReportedOnceAndDoNotStopTheFrames() {
        val messages = ArrayList<String>()
        val session = VisualSession { messages.add(it) }
        session.pianoroll(Pattern { throw CosicError("boom") }, VisualOptions())
        repeat(3) { session.tick(it.toDouble(), true) }
        assertEquals(listOf("[draw] error: boom"), messages)
    }

    @Test
    fun aVisualThatFailsWhileItDrawsIsReportedOnceAndDoesNotThrow() {
        val messages = ArrayList<String>()
        val session = VisualSession { messages.add(it) }
        session.pianoroll(cosic.pattern.pure(null), VisualOptions())
        session.tick(0.0, true)
        repeat(3) { paintCalls(session) }
        assertEquals(listOf("[draw] error: Cannot destructure property 'note' of 'value' as it is null."), messages)
    }

    // ---- painters (the drawer) -----------------------------------------------------------------------------------------------------------------------

    @Test
    fun aPunchcardPaintsEveryHapOfTheProgram() {
        val program = pattern("""stack(s("bd*2"), s("hh*4"))""")
        val session = VisualSession()
        val painted = session.punchcard(program, VisualOptions())
        session.attach(painted, ScopeTaps())
        assertTrue(session.hasBackground)
        assertEquals(emptyList(), paintCalls(session), "nothing before the first frame")
        session.tick(0.0, true) // the drawer's first frame only notes where the playhead is
        session.tick(0.1, true)
        val calls = paintCalls(session)
        assertTrue(calls.count { it.startsWith("strokeRect") || it.startsWith("fillRect") } >= 3, calls.toString())
        assertTrue(session.versionOf(session.surfaces.first()) > 0)
    }

    @Test
    fun aPainterWithAWidgetIdPaintsOnItsWidgetOnly() {
        val program = pattern("""s("bd*2")""")
        val session = VisualSession()
        val painted = session.widget(WidgetType.SPIRAL, "_widget__spiral_0", VisualOptions(), program, 20)
        session.attach(painted, ScopeTaps())
        session.tick(0.0, true)
        session.tick(0.1, true)
        val widget = session.surfaces.last()
        assertEquals(emptyList(), paintCalls(session, session.surfaces.first()))
        assertTrue(paintCalls(session, widget).isNotEmpty())
    }

    @Test
    fun theDrawerOfTheSessionCanBeFollowedPastTheFirstFrame() {
        val session = VisualSession()
        val painted = session.spiral(pattern("""s("bd*2")"""), VisualOptions())
        session.attach(painted, ScopeTaps())
        for (t in listOf(0.0, 0.05, 0.1, 0.5, 1.0)) session.tick(t, true)
        assertTrue(paintCalls(session).any { it == "lineWidth = 40" })
    }

    // ---- print ---------------------------------------------------------------------------------------------------------------------------------------

    @Test
    fun aPrintWidgetCollectsLinesAsHapsStart() {
        val session = VisualSession()
        session.widget(WidgetType.PRINT, "_widget__print_0", VisualOptions(), pattern("""s("bd sd")"""), 10)
        assertEquals(emptyList(), session.printLines("_widget__print_0"))
        session.tick(0.0, true)
        session.tick(0.1, true)
        session.tick(0.6, true)
        val lines = session.printLines("_widget__print_0")
        assertEquals("0.5     : s:sd", lines.last())
        assertEquals(emptyList(), session.printLines("_widget__print_1"))
    }
}
