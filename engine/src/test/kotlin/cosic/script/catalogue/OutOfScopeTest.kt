package cosic.script.catalogue

import cosic.SourceRange
import cosic.parity.OUT_OF_SCOPE_ENTRY_POINT
import cosic.parity.ParityData
import cosic.parity.ParityWorld
import cosic.pattern.CosicError
import cosic.script.ReplSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Hydra and CSound are ruled out (decision 4 of the parity plan): their entry points are defined and report that they are not there, at the
 * call that was made. The oracle's own runtime mocks the CSound ones away; the parity harness repeats that, which `theParityHarness...` pins.
 */
class OutOfScopeTest {
    private fun errorOf(code: String): CosicError = assertFailsWith<CosicError> { ReplSession().evaluate(code) }

    private fun rangeText(code: String, error: CosicError): String {
        val range: SourceRange = assertNotNull(error.range, "the error says where it is: ${error.message}")
        return code.substring(range.start, range.end)
    }

    @Test
    fun everyEntryPointIsAnErrorAtItsCall() {
        for (name in OUT_OF_SCOPE_FUNCTIONS) {
            val code = "$name('x')"
            val error = errorOf(code)
            assertEquals("'$name' is not available in this app", error.message)
            assertEquals(code, rangeText(code, error), "$name: the range is the call")
        }
    }

    @Test
    fun theMethodsOfCsoundAreErrorsToo() {
        for (name in OUT_OF_SCOPE_METHODS) {
            val code = "note(\"c e\")\n  .$name('Bass')"
            val error = errorOf(code)
            assertEquals("'$name' is not available in this app", error.message)
            assertTrue(rangeText(code, error).endsWith(".$name('Bass')"), "$name: the range ends at the call: ${rangeText(code, error)}")
        }
    }

    @Test
    fun aTaggedTemplateIsACallToo() {
        val code = "await loadCsound`\ninstr CoolSynth\nendin`"
        val error = errorOf(code)
        assertEquals("'loadCsound' is not available in this app", error.message)
        assertNotNull(error.range)
    }

    @Test
    fun theEntryPointsOfHydraAndCsoundAreTheNamesThePlanListsAndTheirSpellings() {
        assertEquals(
            setOf("initHydra", "clearHydra", "H", "loadCsound", "loadCSound", "loadcsound", "loadOrc", "csound", "csoundm"),
            OUT_OF_SCOPE_FUNCTIONS.toSet(),
        )
        assertEquals(setOf("csound", "csoundm"), OUT_OF_SCOPE_METHODS.toSet())
    }

    @Test
    fun theErrorStopsTheProgramAtTheCallAndLeavesWhatCameBefore() {
        val session = ReplSession()
        session.evaluate("setcps(1)")
        assertFailsWith<CosicError> { session.evaluate("setcps(2)\nawait initHydra()\ns(\"bd\")") }
        assertEquals(2.0, session.host.cps, "the tempo set before the failing call stays, as after any error of a program")
    }

    @Test
    fun theTunesThatCallCsoundStopAtTheFirstCallOfIt() {
        val tunes = ParityData.cases.filter { it.id == "tunes:csoundDemo" || it.id == "tunes:loungeSponge" }
        assertEquals(2, tunes.size)
        for (tune in tunes) {
            val error = errorOf(tune.codes.single())
            val name = if (tune.name == "csoundDemo") "loadCsound" else "loadOrc"
            assertEquals("'$name' is not available in this app", error.message, tune.id)
        }
    }

    @Test
    fun theParityHarnessMocksThemAsTheOraclesRuntimeDoesSoThePatternsOfThoseTunesAreCompared() {
        // test/runtime.mjs makes `csound` an identity method and `loadCsound`, `loadOrc` and the like functions that do nothing
        for (id in listOf("tunes:csoundDemo", "tunes:loungeSponge")) {
            val tune = ParityData.cases.single { it.id == id }
            val pattern = ParityWorld.plain().run(tune.codes.single())
            assertNotNull(pattern, "$id evaluates in the parity world")
        }
        val known = cosic.parity.KnownGaps.entries.keys
        assertFalse("tunes:csoundDemo" in known || "tunes:loungeSponge" in known, "they pass, so they are not gaps")
    }

    @Test
    fun onlyTheTwoCsoundTunesOfTheParityDataCallAnEntryPoint() {
        val calling = ParityData.cases.filter { case -> case.codes.any { OUT_OF_SCOPE_ENTRY_POINT.containsMatchIn(it) } }.map { it.id }
        assertEquals(listOf("tunes:csoundDemo", "tunes:loungeSponge"), calling, "a new case that calls Hydra or CSound must be looked at: known-gaps.txt or a mock")
    }

    @Test
    fun noReferenceCardOffersThem() {
        // the editor's autocomplete offers the names Strudel documents that the engine defines: none of these has a card
        val text = javaClass.getResourceAsStream("/cosic/reference/docs.json")!!.bufferedReader().readText()
        val cards = Regex("\\{\"name\":\\s*\"([^\"]+)\"").findAll(text).map { it.groupValues[1] }.toSet()
        for (name in OUT_OF_SCOPE_FUNCTIONS) assertFalse(name in cards, "$name has a card")
    }
}
