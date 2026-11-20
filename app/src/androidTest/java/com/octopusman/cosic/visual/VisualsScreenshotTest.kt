package com.octopusman.cosic.visual

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import com.octopusman.cosic.MainActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cosic.CosicEngine
import cosic.EvalReport
import cosic.NoSampleStore
import cosic.sound.RegistryLibrary
import cosic.visual.VisualSession
import java.io.File
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Evaluates a program with a pianoroll and a scope, drives the visuals and takes pictures: the bitmaps the canvas drew (`4.3-bitmaps-*.png`) and a screenshot of the
 * test activity (`4.3-screen.png`), both in the app's external files directory (`adb pull /sdcard/Android/data/com.octopusman.cosic/files/visual43`).
 */
@RunWith(AndroidJUnit4::class)
class VisualsScreenshotTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val out = File(context.getExternalFilesDir(null), "visual43").also { it.mkdirs() }

    private fun session(): Pair<CosicEngine, VisualSession> {
        val engine = CosicEngine(48_000, RegistryLibrary(NoSampleStore, emptySet()))
        val code = """
            ${'$'}: note("c3 e3 g3 c4 <e4 b3>*2")._pianoroll({ labels: 1 })
            ${'$'}: s("bd*2 hh*4")._scope()
            ${'$'}: note("c3 g3")._spiral()
        """.trimIndent()
        val ok = engine.evaluate(code)
        assertTrue(ok.toString(), ok is EvalReport.Ok)
        val session = (ok as EvalReport.Ok).program.visuals as? VisualSession
        assertNotNull(session)
        // a sound for the scope: two sines, as if the voices of that widget were playing
        val surface = session!!.surfaces.first { it.widget?.type == cosic.visual.WidgetType.SCOPE }
        val block = FloatArray(256)
        var n = 0
        repeat(128) {
            for (i in block.indices) {
                val t = (n + i) / 48_000.0
                block[i] = (0.5 * sin(2 * PI * 220.0 * t) + 0.25 * sin(2 * PI * 331.0 * t)).toFloat()
            }
            n += block.size
            engine.scopes.add(surface.key, block, 0, block.size)
            engine.scopes.endBlock(block.size)
        }
        return engine to session
    }

    @Test
    fun paintsEveryWidgetIntoAPngAndShowsThemInAnActivity() {
        val (_, session) = session()
        // the bitmaps the canvas draws, one per widget, at three moments of the playhead
        val density = context.resources.displayMetrics.density
        val painters = session.surfaces.filter { it.widget != null }.associateWith { SurfacePainter(session, it, it.width, it.height, density) }
        for (cycle in listOf(0.0, 0.25, 0.5, 0.75, 1.0, 1.5)) {
            assertTrue(session.tick(cycle, true))
            for (p in painters.values) p.refresh()
        }
        for ((surface, painter) in painters) {
            val file = File(out, "4.3-bitmaps-${surface.widget!!.type.method.trim('_')}.png")
            val bitmap = painter.image.asAndroidBitmap()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            var drawn = 0
            for (y in 0 until bitmap.height step 2) for (x in 0 until bitmap.width step 2) if ((bitmap.getPixel(x, y) ushr 24) != 0) drawn++
            assertTrue("${surface.key} has something on it", drawn > 20)
        }

        // the host driving the same kind of session, laid over the app's own activity (the test APK has no activity that runs in the app's process)
        val shownSession = session().second
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val intent = Intent(instrumentation.targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activity = instrumentation.startActivitySync(intent)
        try {
            instrumentation.runOnMainSync {
                val view = ComposeView(activity).apply { setContent { VisualsTestScreen(shownSession) } }
                activity.addContentView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
            Thread.sleep(2500) // the playhead moves: half a cycle per second
            val shot = instrumentation.uiAutomation.takeScreenshot()
            assertNotNull(shot)
            File(out, "4.3-screen.png").outputStream().use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            activity.finish()
        }
    }
}
