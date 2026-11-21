package cosic.player

import cosic.EvalReport
import cosic.FakeStore
import cosic.TestBank
import cosic.audio.SR
import cosic.audio.channel
import cosic.engineOf
import cosic.render.OfflineRenderer
import cosic.sound.RegistryLibrary
import cosic.testBankEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A sample file that cannot be loaded (a host that is gone, a 404) is not one that is still loading: the sound is [SoundLookup.Failed], the program says so
 * (`[sampler] could not load "kit:0": ...`, as the web REPL's console does), and an export does not wait for it.
 */
class FailedSampleTest {
    private val reason = "HTTP 404 for https://example.test/kit/0.wav"

    /** A store whose file `kit/0` failed and whose file `kit/1` is for ever "being decoded" (null, and no reason). */
    private class DeadStore(private val reasons: Map<String, String>) : FakeStore() {
        override fun failureOf(url: String): String? = reasons[url]
    }

    private fun library() = RegistryLibrary(DeadStore(mapOf("kit/0" to reason)), emptySet()).also { it.register(testBankEntry("kit", TestBank(2))) }

    @Test
    fun aFileThatFailedIsFailedAndAFileThatIsLoadingStaysLoading() {
        val resolver = SoundResolver(SR, library())
        val failed = assertIs<SoundLookup.Failed>(resolver.resolve(mapOf("s" to "kit", "n" to 0.0), 1.0, 0.5))
        assertEquals("kit:0", failed.sound)
        assertEquals(reason, failed.reason)
        assertSame(SoundLookup.Loading, resolver.resolve(mapOf("s" to "kit", "n" to 1.0), 1.0, 0.5))
    }

    @Test
    fun aProgramWithAFailedSampleWarnsAndOnlyTheOtherOneCountsAsLoading() {
        val engine = engineOf(library())
        val report = assertIs<EvalReport.Ok>(engine.evaluate("""s("kit:0 kit:1")"""))
        assertEquals(listOf("[sampler] could not load \"kit:0\": $reason"), report.warnings)
        assertEquals(1, engine.soundsLoading(0.0, 1.0), "kit:1 is still loading, kit:0 is not")
    }

    @Test
    fun theWarningComesUpWhilePlayingToo() {
        val engine = engineOf(library())
        engine.evaluate("""s("kit:0")""")
        engine.play()
        val block = FloatArray(256 * 2)
        repeat(1_000) { engine.render(block, 256) }
        assertTrue("[sampler] could not load \"kit:0\": $reason" in engine.snapshot.warnings, engine.snapshot.warnings.toString())
    }

    @Test
    fun anExportSkipsAFailedSampleInsteadOfWaitingForItForAMinute() {
        val started = System.nanoTime()
        val out = OfflineRenderer.render("""s("kit:0")""", 0.0, 1.0, 48_000, library())
        assertTrue((System.nanoTime() - started) / 1_000_000 < 10_000, "it did not wait")
        assertTrue(channel(out, 0).all { it == 0f }, "the sample that cannot be loaded is silent")
    }
}
