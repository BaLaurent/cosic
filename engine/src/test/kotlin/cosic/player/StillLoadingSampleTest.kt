package cosic.player

import cosic.EvalReport
import cosic.FakeLibrary
import cosic.TestBank
import cosic.engineOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * An event whose sample is still being fetched or decoded when it is due is skipped, and the program says so (Strudel's `[webaudio] skip hap: still loading`);
 * the dry run that follows an evaluation counts such events as loading (the export waits for them) but does not warn.
 */
class StillLoadingSampleTest {
    private val warning = "[webaudio] skip hap: still loading"

    /** A bank whose only sample is never decoded. */
    private fun library() = FakeLibrary(banks = mapOf("kit" to TestBank(1)))

    @Test
    fun anEventWhoseSampleIsStillLoadingWarnsWhilePlayingButNotInTheDryRun() {
        val engine = engineOf(library())
        val report = assertIs<EvalReport.Ok>(engine.evaluate("""s("kit")"""))
        assertEquals(emptyList(), report.warnings, "the dry run does not warn about a sample that is loading")
        assertEquals(1, engine.soundsLoading(0.0, 1.0), "but it counts it")
        engine.play()
        val block = FloatArray(256 * 2)
        repeat(1_000) { engine.render(block, 256) }
        assertTrue(warning in engine.snapshot.warnings, engine.snapshot.warnings.toString())
    }
}
