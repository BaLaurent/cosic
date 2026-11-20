package com.octopusman.cosic.audio

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import cosic.CosicEngine
import cosic.EvalReport
import cosic.SampleBank
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/**
 * The whole path of a sound, on a device with a network: the default library is read from the CDN, a program plays a drum kit and an
 * instrument from it, their files are downloaded, decoded and mixed by the engine. Skipped without a network.
 */
@RunWith(AndroidJUnit4::class)
class NetworkLibraryTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var dir: File
    private lateinit var repository: SampleRepository

    @Before
    fun setUp() {
        assumeTrue("no network", reachable("strudel.b-cdn.net", 443))
        dir = File(context.cacheDir, "network-library-test").also { it.deleteRecursively(); it.mkdirs() }
        // A repository of its own over a fresh store: nothing is cached, everything comes from the network.
        val prefs = PreferenceDataStoreFactory.create { File(dir, "prefs.preferences_pb") }
        repository = SampleRepository(context, prefs)
    }

    @After
    fun tearDown() {
        if (this::dir.isInitialized) dir.deleteRecursively()
    }

    private fun reachable(host: String, port: Int) = try {
        Socket().use { it.connect(InetSocketAddress(host, port), 5_000); true }
    } catch (_: Exception) {
        false
    }

    private fun render(engine: CosicEngine, seconds: Double, rate: Int): Float {
        engine.play()
        val buffer = FloatArray(256 * 2)
        var loudest = 0f
        repeat((seconds * rate / 256).toInt()) {
            engine.render(buffer, 256)
            for (sample in buffer) loudest = maxOf(loudest, abs(sample))
        }
        return loudest
    }

    @Test
    fun theDefaultSoundsAreLoadedFromTheCdnAndAProgramPlaysThemFromTheNetwork() {
        assertTrue("library not ready", repository.awaitReady(60_000))
        val status = repository.status.value
        assertEquals("failed packs ${status.failed}", emptyList<String>(), status.failed)
        assertTrue((repository.sound("bd") as SampleBank).count >= 8)
        assertNotNull(repository.sound("RolandTR909_bd"))
        assertEquals(repository.sound("RolandTR909_bd"), repository.sound("tr909_bd"))
        assertEquals(29, (repository.sound("piano") as SampleBank).groups.size)

        val engine = CosicEngine(48_000, repository)
        val report = engine.evaluate("setcps(1)\n\$: s(\"bd*2, hh*4\").bank(\"RolandTR909\")\n\$: note(\"c3 e3\").s(\"piano\").release(.2)")
        assertTrue("$report", report is EvalReport.Ok)
        assertEquals(emptyList<String>(), (report as EvalReport.Ok).warnings)

        runBlocking { assertTrue("samples did not load", repository.awaitDownloads(60_000)) }
        val loudest = render(engine, 2.0, 48_000)

        assertEquals(null, engine.snapshot.runtimeError)
        assertTrue("silent: loudest sample $loudest", loudest > 0.05f)
        // the files are kept on the device: a second store finds them without the network
        assertTrue(File(context.cacheDir, "samples").list().orEmpty().isNotEmpty())
        assertTrue(File(context.filesDir, "sample-maps").list().orEmpty().size >= 7)
    }

    @Test
    fun aSampleMapFromGithubIsLoadedAndItsSoundsPlayFromTheNetwork() {
        assertTrue("library not ready", repository.awaitReady(60_000))
        val engine = CosicEngine(48_000, repository)

        // what `samples('github:tidalcycles/dirt-samples')` of a program does
        repository.loadSampleMap("github:tidalcycles/dirt-samples")
        val report = engine.evaluate("\$: s(\"bd*2, hh*4\")")
        assertTrue("$report", report is EvalReport.Ok)
        assertEquals(emptyList<String>(), (report as EvalReport.Ok).warnings)

        runBlocking { assertTrue("samples did not load", repository.awaitDownloads(60_000)) }
        val loudest = render(engine, 2.0, 48_000)
        assertTrue("silent: loudest sample $loudest", loudest > 0.05f)
        // the banks of the map are the user's, as the Sounds tab lists them
        assertEquals(false, repository.sound("bd")!!.prebake)
    }
}
