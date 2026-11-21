package cosic.script.catalogue

import cosic.audio.AudioSettings
import cosic.audio.MAX_POLYPHONY_LIMIT
import cosic.pattern.CosicError
import cosic.script.ReplSession
import cosic.script.shown
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The functions of a program that change the sound engine (superdough.mjs:33-86, 185-241, 272-319): what they write is the settings of the
 * session's host, and what they read comes back as a value of the program.
 */
class AudioCatalogueTest {
    private val session = ReplSession()
    private val audio: AudioSettings get() = session.host.effects.audio

    /** The haps of the program, one per line as `hap.show(true)` prints them. */
    private fun play(code: String): List<String> = shown(session.evaluate(code).pattern)

    @Test
    fun setMaxPolyphonyIsParseIntAndTheLimitOutlivesTheProgram() {
        session.evaluate("setMaxPolyphony(4)")
        assertEquals(4, audio.current.voiceLimit)
        session.evaluate("""s("bd")""")
        assertEquals(4, audio.current.voiceLimit, "superdough's module variable stays until it is set again")
        session.evaluate("setMaxPolyphony('12 voices')")
        assertEquals(12, audio.current.voiceLimit)
        session.evaluate("setMaxPolyphony()")
        assertTrue(audio.current.maxPolyphony.isNaN())
        assertEquals(MAX_POLYPHONY_LIMIT, audio.current.voiceLimit)
    }

    @Test
    fun theExampleOfTheDocumentationRuns() {
        // `@example setMaxPolyphony` and `@example setGainCurve` of superdough.mjs
        val haps = play("""
            setMaxPolyphony(4)
            n(irand(24).seg(8)).scale("C#3:minor").room(1).release(4).gain(0.5)
        """.trimIndent())
        assertEquals(8, haps.size)
        assertEquals(4, audio.current.voiceLimit)
        assertEquals(listOf("[ 0/1 → 1/4 | s:bd gain:0.5 ]", "[ 1/4 → 1/2 | s:bd gain:0.5 ]"), play("setGainCurve((x) => x * x)\ns(\"bd*4\").gain(0.5)").take(2))
        assertTrue(audio.current.hasCurve)
    }

    @Test
    fun setMultiChannelOrbitsReadsItsArgumentAsLooseEqualityWithTrue() {
        session.evaluate("setMultiChannelOrbits(true)")
        assertTrue(audio.current.multiChannelOrbits)
        session.evaluate("setMultiChannelOrbits(0)")
        assertFalse(audio.current.multiChannelOrbits)
        session.evaluate("setMultiChannelOrbits(1)")
        assertTrue(audio.current.multiChannelOrbits)
        session.evaluate("setMultiChannelOrbits()")
        assertFalse(audio.current.multiChannelOrbits)
    }

    @Test
    fun setGainCurveTakesAFunctionOfTheProgramAndApplyGainCurveRunsIt() {
        assertEquals(listOf("[ 0/1 → 1/1 | n:3 ]"), play("n(applyGainCurve(3))"), "without a curve a gain is itself")
        session.evaluate("setGainCurve((x) => x * x)")
        assertEquals(listOf("[ 0/1 → 1/1 | n:9 ]"), play("n(applyGainCurve(3))"))
        session.evaluate("setGainCurve(Math.sqrt)")
        assertEquals(listOf("[ 0/1 → 1/1 | n:3 ]"), play("n(applyGainCurve(9))"), "a native function is a function too")
        assertTrue(audio.current.hasCurve)
    }

    @Test
    fun aCurveThatIsNoFunctionIsAnErrorWhereItIsUsed() {
        session.evaluate("setGainCurve(5)")
        val error = assertFailsWith<CosicError> { session.evaluate("applyGainCurve(1)") }
        assertEquals("gainCurveFunc is not a function", error.message)
        assertNotNull(error.range, "the error is at the call")
    }

    @Test
    fun theDefaultValueFunctionsReadAndWriteTheLiveAndTheBaseValues() {
        assertEquals(listOf("[ 0/1 → 1/1 | n:0.8 ]"), play("n(getDefaultValue('gain'))"))
        session.evaluate("setDefaultValue('gain', 1)")
        assertEquals(listOf("[ 0/1 → 1/1 | n:1 ]"), play("n(getDefaultValue('gain'))"))
        session.evaluate("resetDefaultValues()")
        assertEquals(listOf("[ 0/1 → 1/1 | n:0.8 ]"), play("n(getDefaultValue('gain'))"))

        session.evaluate("setDefault('gain', 0.4)")
        assertEquals(listOf("[ 0/1 → 1/1 | n:0.8 ]"), play("n(getDefaultValue('gain'))"), "the live value follows the base only after a reset")
        assertEquals(listOf("[ 0/1 → 1/1 | n:0.4 ]"), play("resetDefaultValues()\nn(getDefaultValue('gain'))"))
        assertEquals(listOf("[ 0/1 → 1/1 | n:0.4 ]"), play("resetDefaults()\nn(getDefaultValue('gain'))"), "resetDefaults leaves the live values")
        assertEquals(listOf("[ 0/1 → 1/1 | n:0.8 ]"), play("resetDefaultValues()\nn(getDefaultValue('gain'))"))
    }

    @Test
    fun setDefaultValuesTakesAnObjectOrAnArray() {
        assertEquals(
            listOf("[ 0/1 → 1/1 | n:3 ]"),
            play("setDefaultValues({ a: 1, b: 2 })\nn(getDefaultValue('a') + getDefaultValue('b'))"),
        )
        assertEquals(
            listOf("[ 0/1 → 1/1 | n:7 ]"),
            play("setDefaultValues([5, 7])\nn(getDefaultValue('1'))"),
            "Object.keys of an array are its indices",
        )
        assertEquals("Cannot convert undefined or null to object", assertFailsWith<CosicError> { session.evaluate("setDefaultValues()") }.message)
    }

    @Test
    fun aDefaultThatIsAListComesBackAsAnArray() {
        assertEquals(listOf("[ 0/1 → 1/1 | n:2 ]"), play("n(getDefaultValue('channels')[1])"))
        assertEquals(listOf("[ 0/1 → 1/1 | s:triangle ]"), play("s(getDefaultValue('s'))"))
        assertEquals(listOf("[ 0/1 → 1/1 | n:3 ]"), play("n(getDefaultValue('nothing') ?? 3)"), "an unset key is undefined")
    }

    @Test
    fun setVersionDefaultsResetsTheLiveValuesAndSetsFanchorForOnePointZero() {
        session.evaluate("setDefaultValue('gain', 5)")
        assertEquals(listOf("[ 0/1 → 1/1 | n:0.5 ]"), play("setVersionDefaults('1.0')\nn(getDefaultValue('fanchor'))"))
        assertEquals(listOf("[ 0/1 → 1/1 | n:0.8 ]"), play("n(getDefaultValue('gain'))"))
        assertEquals(listOf("[ 0/1 → 1/1 | n:7 ]"), play("setVersionDefaults('2.0')\nn(getDefaultValue('fanchor') ?? 7)"))
    }

    @Test
    fun initAudioSetsThePolyphonyAndTheMultiChannelOrbits() {
        session.evaluate("initAudio({ maxPolyphony: 8, multiChannelOrbits: true })")
        assertEquals(8, audio.current.voiceLimit)
        assertTrue(audio.current.multiChannelOrbits)
        session.evaluate("initAudio({ maxPolyphony: 16 })")
        assertEquals(16, audio.current.voiceLimit)
        assertFalse(audio.current.multiChannelOrbits, "`multiChannelOrbits = false` is the default of the option")
        session.evaluate("initAudio()")
        assertTrue(audio.current.maxPolyphony.isNaN(), "an option that is missing is `setMaxPolyphony(undefined)`: nothing is ever stolen")
        assertEquals("Cannot destructure property 'disableWorklets' of 'options' as it is null.", assertFailsWith<CosicError> { session.evaluate("initAudio(null)") }.message)
    }

    @Test
    fun theConstantsAreTheOnesSuperdoughExports() {
        assertEquals(listOf("[ 0/1 → 1/1 | n:128 ]"), play("n(DEFAULT_MAX_POLYPHONY)"))
        // `evalScope` copies the values the variables have when it runs, so a program reads those whatever it sets
        assertEquals(listOf("[ 0/1 → 1/1 | n:128 ]"), play("setMaxPolyphony(4)\nn(maxPolyphony)"))
        assertEquals(listOf("[ 0/1 → 1/1 | n:0 ]"), play("setMultiChannelOrbits(true)\nn(multiChannelOrbits ? 1 : 0)"))
        assertEquals(listOf("[ 0/1 → 1/1 | s:pink ]"), play("s(noises[0])"))
        assertEquals(listOf("[ 0/1 → 1/1 | n:4 ]"), play("n(noises.length)"))
    }

    @Test
    fun warpmodeIsTheFrozenTableOfTheWavetableModes() {
        assertEquals(listOf("[ 0/1 → 1/1 | n:8 ]"), play("n(Warpmode.FOLD)"))
        assertEquals(listOf("[ 0/1 → 1/1 | n:21 ]"), play("n(Warpmode.FLIP)"))
        assertEquals(listOf("[ 0/1 → 1/1 | n:22 ]"), play("n(Object.keys(Warpmode).length)"))
        assertEquals(
            "Cannot assign to read only property 'NONE' of object '#<Object>'",
            assertFailsWith<CosicError> { session.evaluate("Warpmode.NONE = 3") }.message,
            "frozen: a program cannot change the table",
        )
        assertEquals(listOf("[ 0/1 → 1/1 | n:0 ]"), play("n(Warpmode.NONE)"))
        assertEquals(listOf("[ 0/1 → 1/1 | n:1 ]"), play("n(Object.isFrozen(Warpmode) ? 1 : 0)"))
    }

    @Test
    fun everySessionHasItsOwnSettings() {
        val other = ReplSession()
        session.evaluate("setMaxPolyphony(3)")
        assertEquals(128, other.host.effects.audio.current.voiceLimit)
    }
}
