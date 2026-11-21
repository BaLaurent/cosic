package cosic

import cosic.audio.peak
import cosic.pattern.Undefined
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val HALF_SECOND = 24_000

/**
 * What a program can change about the sound engine, end to end: the settings of its functions (`setMaxPolyphony`, `setGainCurve`, the default
 * values) reach the mixer and the voices, live from one evaluation to the next like superdough's module variables, and a new session forgets them.
 */
class AudioSettingsEngineTest {
    private val sine = """note("c3").s("sine")"""

    /** The highest level of [frames] frames of [code]. */
    private fun peakOf(code: String, frames: Int = HALF_SECOND): Float {
        val engine = engineOf()
        engine.evaluateOk(code)
        engine.play()
        return peak(engine.renderFrames(frames))
    }

    // ---- polyphony and orbits --------------------------------------------------------------------------------------------------------

    @Test
    fun thePolyphonyAndTheChannelModeReachTheMixerAtTheNextBlock() {
        val engine = engineOf()
        assertEquals(128, engine.mixerPolyphony)
        assertFalse(engine.mixerMultiChannelOrbits)
        engine.evaluateOk("setMaxPolyphony(3)\nsetMultiChannelOrbits(true)")
        assertEquals(128, engine.mixerPolyphony, "the audio thread owns the mixer: it takes the change at its next block")
        engine.renderFrames(256)
        assertEquals(3, engine.mixerPolyphony)
        assertTrue(engine.mixerMultiChannelOrbits)
        engine.evaluateOk("setMaxPolyphony(100000)")
        engine.renderFrames(256)
        assertEquals(192, engine.mixerPolyphony, "what the pool can sound")
    }

    @Test
    fun theLimitStealsTheOldestVoices() {
        fun mostVoices(code: String): Int {
            val engine = engineOf()
            engine.evaluateOk(code)
            engine.play()
            var most = 0
            engine.renderFrames(HALF_SECOND + 6_000) { most = max(most, it.snapshot.activeVoices) }
            return most
        }
        val program = """note("c3 e3 g3 c4").s("sine").release(2).fast(8)"""
        val free = mostVoices(program)
        val limited = mostVoices("setMaxPolyphony(2)\n$program")
        assertTrue(free > 4, "with the default limit the long notes pile up: $free")
        assertTrue(limited in 1..2, "with a limit of two no more than two sound at once: $limited")
    }

    @Test
    fun noLimitMeansWhatThePoolCanHold() {
        val engine = engineOf()
        engine.evaluateOk("initAudio()")
        engine.renderFrames(256)
        assertEquals(192, engine.mixerPolyphony)
    }

    // ---- the gain curve ----------------------------------------------------------------------------------------------------------------

    @Test
    fun aGainCurveScalesEveryGain() {
        val plain = peakOf("$sine.gain(0.5)")
        assertTrue(plain > 0.01f)
        assertEquals(0.5, peakOf("setGainCurve((x) => x * x)\n$sine.gain(0.5)").toDouble() / plain, 1e-3, "0.5 becomes 0.25")
        assertEquals(0.8, peakOf("setGainCurve((x) => x * x)\n$sine").toDouble() / peakOf(sine), 1e-3, "the default gain 0.8 becomes 0.64")
    }

    @Test
    fun theCurveAppliesToTheVelocityToo() {
        val plain = peakOf("$sine.velocity(0.5)")
        // the gain 0.8 becomes 0.64 and the velocity 0.5 becomes 0.25: 0.16 against 0.4
        assertEquals(0.4, peakOf("setGainCurve((x) => x * x)\n$sine.velocity(0.5)").toDouble() / plain, 1e-3)
    }

    @Test
    fun aGainCurveOfAnEarlierProgramStaysUntilAnotherIsSet() {
        val plain = peakOf("$sine.gain(0.5)")
        fun after(vararg programs: String): Double {
            val engine = engineOf()
            for (program in programs) engine.evaluateOk(program)
            engine.play()
            return peak(engine.renderFrames(HALF_SECOND)).toDouble() / plain
        }
        assertEquals(0.5, after("setGainCurve((x) => x * x)", "$sine.gain(0.5)"), 1e-3, "the next program plays through the curve")
        assertEquals(1.0, after("setGainCurve((x) => x * x)", "setGainCurve((x) => x)\n$sine.gain(0.5)"), 1e-3, "until another curve replaces it")
    }

    @Test
    fun aCurveThatIsNoFunctionFailsTheEventsThatNeedIt() {
        val engine = engineOf()
        engine.evaluateOk("setGainCurve(5)\n$sine")
        engine.play()
        engine.renderFrames(HALF_SECOND)
        assertContains(assertNotNull(engine.snapshot.runtimeError), "gainCurveFunc is not a function")
    }

    // ---- the default values ---------------------------------------------------------------------------------------------------------------

    @Test
    fun setDefaultChangesWhatAnEventFallsBackToAndSetDefaultValueDoesNotLastTheEvaluation() {
        val plain = peakOf(sine)
        assertEquals(0.5, peakOf("setDefault('gain', 0.4)\n$sine").toDouble() / plain, 1e-3, "0.4 against 0.8")
        // as in the web REPL, every evaluation ends with setVersionDefaults, which puts the live values back to the base ones
        assertEquals(1.0, peakOf("setDefaultValue('gain', 0.4)\n$sine").toDouble() / plain, 1e-3)
    }

    @Test
    fun theVersionOfTheProgramDecidesWhatEveryEvaluationEndsWith() {
        val engine = engineOf()
        engine.evaluateOk("setDefaultValue('gain', 0.3)")
        assertEquals(0.8, engine.audioSettings.current.default("gain"))
        assertEquals(Undefined, engine.audioSettings.current.default("fanchor"))
        engine.evaluateOk("// @version 1.0\n$sine")
        assertEquals(0.5, engine.audioSettings.current.default("fanchor"))
        engine.evaluateOk(sine)
        assertEquals(Undefined, engine.audioSettings.current.default("fanchor"))
    }

    @Test
    fun anEventWithNoSoundNameTakesTheDefaultSound() {
        val engine = engineOf()
        engine.evaluateOk("setDefault('s', 'unknownsound')\nnote('c3')")
        val report = engine.evaluate("note('c3')")
        assertIs<EvalReport.Ok>(report)
        assertTrue(report.warnings.any { "unknownsound" in it }, "the sound it falls back to is the one that is looked up: ${report.warnings}")
    }

    // ---- sessions ------------------------------------------------------------------------------------------------------------------------

    @Test
    fun aFailingProgramKeepsWhatItSetBeforeItFailed() {
        val engine = engineOf()
        assertIs<EvalReport.Failed>(engine.evaluate("setMaxPolyphony(3)\nthrow new Error('no')"))
        assertEquals(3, engine.audioSettings.current.voiceLimit)
    }

    @Test
    fun aNewSessionStartsFromTheFactorySettings() {
        val engine = engineOf()
        engine.evaluateOk("setMaxPolyphony(3)\nsetMultiChannelOrbits(true)\nsetGainCurve((x) => x * x)\nsetDefault('gain', 1)")
        engine.renderFrames(256)
        assertEquals(3, engine.mixerPolyphony)
        assertFalse(engine.audioSettings.current.pristine)
        engine.resetSession()
        engine.renderFrames(256)
        assertTrue(engine.audioSettings.current.pristine)
        assertEquals(128, engine.mixerPolyphony)
        assertFalse(engine.mixerMultiChannelOrbits)
    }
}
