package cosic.visual

import cosic.CosicEngine
import cosic.EvalReport
import cosic.audio.IrExecutor
import cosic.audio.SR
import cosic.evaluateOk
import cosic.renderFrames
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The analysers end to end: a program with `_scope()`/`.scope()` is played through the engine and the visuals draw the sound it made. */
class ScopeFeedTest {
    private fun engine() = CosicEngine(SR, cosic.FakeLibrary(), IrExecutor.INLINE)

    /** The y of every point of the (last) polyline the canvas was told to draw. */
    private fun lineYs(canvas: RecordingCanvas): List<Double> =
        canvas.calls.filter { it[0] == "call" && (it[1] == "lineTo" || it[1] == "moveTo") }.map { (it[2] as List<*>)[1] as Double }

    private fun paintScope(engine: CosicEngine, report: EvalReport.Ok): RecordingCanvas {
        val session = report.program.visuals as VisualSession
        val surface = session.surfaces.last()
        val cycle = engine.snapshot.cycleAt(engine.snapshot.renderedFrames)
        session.tick(0.0, true)
        session.tick(cycle, true)
        val canvas = RecordingCanvas(surface.width, surface.height)
        session.paint(surface, canvas)
        return canvas
    }

    @Test
    fun aScopeWidgetDrawsTheWaveOfTheSoundThatItsHapsMake() {
        val engine = engine()
        val report = engine.evaluateOk("""note("a4").s("sine")._scope()""")
        val id = report.program.widgets.single().widgetId
        engine.play()
        engine.renderFrames(SR / 2)
        assertTrue(engine.scopes.read(id, 256, FloatArray(256)), "the voice fed the tap of the widget id `$id`")
        val samples = FloatArray(4096)
        assertTrue(engine.scopes.read(id, samples.size, samples))
        assertTrue(samples.maxOf { Math.abs(it) } > 0.05f, "the tap holds the sine")

        val canvas = paintScope(engine, report)
        val ys = lineYs(canvas)
        assertTrue(ys.size > 100, "a waveform, not the two points of the flat line: ${ys.size} points")
        assertTrue(ys.max() - ys.min() > 1.0, "the line moves with the signal: ${ys.min()}..${ys.max()}")
    }

    @Test
    fun beforeAnySoundTheScopeDrawsTheFlatLine() {
        val engine = engine()
        val report = engine.evaluateOk("""note("a4").s("sine")._scope()""")
        // not played yet: no voice has fed the tap
        val canvas = paintScope(engine, report)
        val ys = lineYs(canvas)
        assertEquals(2, ys.size)
        assertEquals(ys[0], ys[1], 0.0)
        assertFalse(engine.scopes.read(report.program.widgets.single().widgetId, 8, FloatArray(8)))
    }

    @Test
    fun thePlainMethodsFeedTheTapOfTheNumberOne() {
        val engine = engine()
        val report = engine.evaluateOk("""note("a4").s("sine").scope()""")
        assertNotNull(report.program.visuals)
        engine.play()
        engine.renderFrames(SR / 2)
        assertTrue(engine.scopes.read("1", 256, FloatArray(256)))
        assertFalse(engine.scopes.read("2", 256, FloatArray(256)))
    }

    @Test
    fun aProgramWithoutAnalysersMakesNoTap() {
        val engine = engine()
        engine.evaluateOk("""note("a4").s("sine")""")
        engine.play()
        engine.renderFrames(SR / 2)
        assertFalse(engine.scopes.read("1", 8, FloatArray(8)))
    }
}
