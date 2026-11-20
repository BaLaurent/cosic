package com.octopusman.cosic.visual

import cosic.CosicEngine
import cosic.EvalReport
import cosic.NoSampleStore
import cosic.sound.RegistryLibrary
import cosic.visual.PaintCanvas
import cosic.visual.VisualSession
import cosic.visual.VisualSurface
import cosic.visual.WidgetType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A canvas that counts what is drawn on it. */
private class CountingCanvas(override val width: Double, override val height: Double) : PaintCanvas {
    var calls = 0
    var clears = 0
    override var fillStyle = "#000"
    override var strokeStyle = "#000"
    override var globalAlpha = 1.0
    override var lineWidth = 1.0
    override var lineCap = "butt"
    override var font = "10px sans-serif"
    override var textBaseline = "alphabetic"
    override fun clearRect(x: Double, y: Double, w: Double, h: Double) { clears++ }
    override fun fillRect(x: Double, y: Double, w: Double, h: Double) { calls++ }
    override fun strokeRect(x: Double, y: Double, w: Double, h: Double) { calls++ }
    override fun fillText(text: String, x: Double, y: Double, maxWidth: Double?) { calls++ }
    override fun measureText(text: String): Double = text.length * 6.0
    override fun beginPath() {}
    override fun moveTo(x: Double, y: Double) {}
    override fun lineTo(x: Double, y: Double) { calls++ }
    override fun arc(x: Double, y: Double, r: Double, start: Double, end: Double) { calls++ }
    override fun closePath() {}
    override fun stroke() { calls++ }
    override fun fill() { calls++ }
    override fun scrollLeft(px: Double) {}
}

class VisualFrameDriverTest {
    private fun sessionOf(code: String): VisualSession {
        val engine = CosicEngine(48_000, RegistryLibrary(NoSampleStore, emptySet()))
        val ok = engine.evaluate(code) as EvalReport.Ok
        return assertNotNull(ok.program.visuals as? VisualSession, "no visuals for $code")
    }

    private fun VisualSession.widgetSurface(): VisualSurface = surfaces.first { it.widget != null }

    private class Clock(var cycle: Double? = 0.0, var playing: Boolean = false)

    private fun driver(clock: Clock) = VisualFrameDriver({ clock.cycle }, { clock.playing })

    private val code = """note("c3 e3 g3 c4")._pianoroll()"""

    @Test
    fun theSurfacesAreTheBackgroundThenTheWidgetsAndTheWidgetCarriesItsId() {
        val session = sessionOf(code)
        assertTrue(session.surfaces[0].isBackground)
        val widget = session.widgetSurface()
        assertEquals(WidgetType.PIANOROLL, widget.widget?.type)
        assertEquals(widget.key, widget.widget?.id)
        assertEquals(500.0, widget.width)
        assertEquals(60.0, widget.height)
    }

    @Test
    fun aProgramThatIsNotPlayingGetsOneFrameSoTheWidgetIsNotEmpty() {
        val clock = Clock(cycle = 0.0, playing = false)
        val d = driver(clock)
        val session = sessionOf(code)
        d.show(session)
        val widget = session.widgetSurface()
        val before = session.versionOf(widget)
        assertTrue(d.frame(), "first frame draws")
        assertTrue(session.versionOf(widget) > before)
        assertFalse(d.settled)
        assertFalse(d.frame(), "then it stands still")
        assertTrue(d.settled)
        val canvas = CountingCanvas(500.0, 60.0)
        session.paint(widget, canvas)
        assertTrue(canvas.calls > 0, "the pianoroll shows its notes")
    }

    @Test
    fun aPlayingProgramChangesEveryFrameAndStoppingResetsTheBackgroundOnce() {
        val clock = Clock(cycle = 0.0, playing = true)
        val d = driver(clock)
        val session = sessionOf("""note("c3 e3")._pianoroll()""")
        d.show(session)
        val widget = session.widgetSurface()
        var last = session.versionOf(widget)
        for (i in 1..5) {
            clock.cycle = i * 0.1
            assertTrue(d.frame())
            val v = session.versionOf(widget)
            assertTrue(v > last, "version moves with the frame")
            last = v
        }
        assertFalse(d.settled)
        val bgBefore = session.versionOf(session.surfaces[0])
        clock.playing = false
        assertTrue(d.frame(), "stopping is a change: the background is cleared")
        assertTrue(session.versionOf(session.surfaces[0]) > bgBefore)
        val bg = CountingCanvas(300.0, 300.0)
        session.paint(session.surfaces[0], bg)
        assertEquals(1, bg.clears, "the cleared background is cleared at the next paint")
        assertFalse(d.frame())
        assertTrue(d.settled)
        // play again: it starts over and moves
        clock.playing = true
        clock.cycle = 3.0
        assertTrue(d.frame())
    }

    @Test
    fun noCycleMeansNoTick() {
        val clock = Clock(cycle = null, playing = true)
        val d = driver(clock)
        val session = sessionOf(code)
        d.show(session)
        assertFalse(d.frame())
        assertEquals(0L, session.versionOf(session.widgetSurface()))
        clock.cycle = 0.5
        assertTrue(d.frame())
    }

    @Test
    fun withoutASessionTheDriverIsSettled() {
        val d = driver(Clock())
        assertNull(d.session)
        assertFalse(d.frame())
        assertTrue(d.settled)
    }

    @Test
    fun showingTheSameSessionAgainChangesNothingAndANewOneTakesOver() {
        val clock = Clock(cycle = 0.0, playing = true)
        val d = driver(clock)
        val a = sessionOf(code)
        d.show(a)
        d.frame()
        d.show(a)
        assertTrue(d.session === a)
        val b = sessionOf(code)
        d.show(b)
        assertTrue(d.session === b)
        assertFalse(d.settled)
        clock.cycle = 0.1
        assertTrue(d.frame())
        assertTrue(b.versionOf(b.widgetSurface()) > 0)
        d.show(null)
        assertNull(d.session)
        assertFalse(d.frame())
    }

    @Test
    fun aBackgroundVisualIsReportedByTheSession() {
        assertTrue(sessionOf("""note("c3 e3").pianoroll()""").hasBackground)
        assertFalse(sessionOf(code).hasBackground)
    }

    @Test
    fun aPrintWidgetIsASurfaceAndItsLinesAreReadFromTheSession() {
        val session = sessionOf("""note("c3 e3")._print()""")
        val surface = session.widgetSurface()
        assertEquals(WidgetType.PRINT, surface.widget?.type)
        assertEquals(emptyList(), session.printLines(surface.key))
    }
}
