package cosic.pattern

import cosic.script.catalogue.Catalogue
import cosic.script.evaluateProgram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `chop`, `striate`, `loopAt`, `loopAtCps`, `slice`, `splice`, `fit` and `scrub` against the lines Strudel prints for them (the
 * `@example`s of pattern.mjs and controls.mjs, their snapshots, and what Strudel's own runtime says for the other calls, see
 * parity/extra-snippets/2.4.json), and the JavaScript they are written in: what happens to a value that is not a control map, to
 * a number that is not whole, to a control that is already set.
 */
class SamplesSpecTest {
    init {
        Catalogue.installAll()
    }

    private fun sound(vararg entries: Pair<String, Any?>): Pattern = pure(linkedMapOf(*entries))

    private fun lines(code: String, cycles: Long = 1): List<String> = evaluateProgram(code).pattern.lines(0, cycles)

    /** The first cycle of [code] asked for at the tempo [cps], as the scheduler asks. */
    private fun linesAt(code: String, cps: Double): List<String> =
        evaluateProgram(code).pattern.sortHapsByPart().queryArc(Rational.ZERO, Rational.ONE, mapOf("_cps" to cps)).map { it.show(true) }

    // ---- chop ----------------------------------------------------------------------------------------------------------

    @Test
    fun chopCutsAnEventIntoPartsOfTheSample() {
        assertEquals(
            listOf("[ 0/1 → 1/2 | s:bd begin:0 end:0.5 ]", "[ 1/2 → 1/1 | s:bd begin:0.5 end:1 ]"),
            SampleFunctions.chop(2.0, sound("s" to "bd")).firstLines(),
        )
    }

    @Test
    fun chopCutsThePartOfTheSampleThatIsAlreadySelected() {
        assertEquals(
            listOf("[ 0/1 → 1/2 | s:bd begin:0.5 end:0.75 ]", "[ 1/2 → 1/1 | s:bd begin:0.75 end:1 ]"),
            SampleFunctions.chop(2.0, sound("s" to "bd", "begin" to 0.5, "end" to 1.0)).firstLines(),
        )
        assertEquals(
            listOf("[ 0/1 → 1/2 | s:bd begin:0.25 end:0.5 ]", "[ 1/2 → 1/1 | s:bd begin:0.5 end:0.75 ]"),
            SampleFunctions.chop(2.0, sound("s" to "bd", "begin" to 0.25, "end" to 0.75)).firstLines(),
        )
    }

    @Test
    fun chopOverwritesABeginThatHasNoEnd() {
        assertEquals(
            listOf("[ 0/1 → 1/2 | s:x begin:0 end:0.5 ]", "[ 1/2 → 1/1 | s:x begin:0.5 end:1 ]"),
            SampleFunctions.chop(2.0, sound("s" to "x", "begin" to 0.5)).firstLines(),
        )
        // the key keeps the place it had: `end` was there first
        assertEquals(
            listOf("[ 0/1 → 1/2 | s:x end:0.5 begin:0 ]", "[ 1/2 → 1/1 | s:x end:1 begin:0.5 ]"),
            SampleFunctions.chop(2.0, sound("s" to "x", "end" to 0.5)).firstLines(),
        )
    }

    @Test
    fun chopSlicesDivideByTheNumberAsItIs() {
        // `Array.from({ length: 1.5 })` has one entry, but the entry is 0/1.5 to 1/1.5
        assertEquals(listOf("[ 0/1 → 1/1 | s:bd begin:0 end:0.6666666666666666 ]"), SampleFunctions.chop(1.5, sound("s" to "bd")).firstLines())
        assertEquals(emptyList(), SampleFunctions.chop(0.0, sound("s" to "bd")).firstLines())
        assertEquals(emptyList(), SampleFunctions.chop(-3.0, sound("s" to "bd")).firstLines())
    }

    @Test
    fun chopMultipliesTheSteps() {
        assertEquals(Rational(4), SampleFunctions.chop(4.0, sound("s" to "bd")).steps)
        assertEquals(Rational(6), SampleFunctions.chop(3.0, fastcat(sound("s" to "a"), sound("s" to "b"))).steps)
        Steps.enabled = false
        try {
            assertEquals(null, SampleFunctions.chop(4.0, sound("s" to "bd")).steps)
        } finally {
            Steps.enabled = true
        }
    }

    @Test
    fun chopOfAnEventThatIsNoControlMapIsATypeError() {
        val failure = assertFailsWith<CosicError> { SampleFunctions.chop(2.0, pure("bd")).firstCycle() }
        assertEquals("Cannot use 'in' operator to search for 'begin' in bd", failure.message)
        assertFailsWith<CosicError> { SampleFunctions.chop(2.0, pure(3.0)).firstCycle() }
    }

    @Test
    fun chopRefusesAMillionSlices() {
        assertEquals(TOO_DENSE, assertFailsWith<CosicError> { SampleFunctions.chop(1e6, sound("s" to "bd")) }.message)
    }

    @Test
    fun chopChargesTheBudgetOfTheQueryBeforeItBuildsThePiecesOfEveryEvent() {
        // 10 000 events, each cut into 10 000 pieces again: `fmap` builds the pattern of every event before the join has seen one, so the charge
        // is made as each is built and the query is refused after a few of them, where building them all (10^8 pieces) runs out of memory
        val doubled = SampleFunctions.chop(10_000.0, SampleFunctions.chop(10_000.0, sound("s" to "bd")))
        assertEquals(TOO_DENSE, assertFailsWith<CosicError> { withQueryBudget { doubled.firstCycle() } }.message)
        // outside of a budget nothing is limited, a modest chop is not
        assertEquals(8, SampleFunctions.chop(2.0, SampleFunctions.chop(4.0, sound("s" to "bd"))).firstCycle().size)
    }

    @Test
    fun chopThroughAProgram() {
        assertEquals(
            listOf(
                "[ 0/1 → 1/2 | s:rhodes begin:0.75 end:1 speed:0.25 unit:c ]",
                "[ 1/2 → 1/1 | s:rhodes begin:0.5 end:0.75 speed:0.25 unit:c ]",
            ),
            lines("s(\"rhodes\").chop(4).rev().loopAt(2)", 1),
        )
    }

    @Test
    fun chopTakesAPatternOfCounts() {
        assertEquals(
            listOf(
                "[ 0/1 → 1/4 | s:bd begin:0 end:0.25 ]", "[ 1/4 → 1/2 | s:bd begin:0.25 end:0.5 ]",
                "[ 1/2 → 3/4 | s:bd begin:0.5 end:0.75 ]", "[ 3/4 → 1/1 | s:bd begin:0.75 end:1 ]",
                "[ 1/1 → 3/2 | s:bd begin:0 end:0.5 ]", "[ 3/2 → 2/1 | s:bd begin:0.5 end:1 ]",
            ),
            lines("s(\"bd\").chop(\"<4 2>\")", 2),
        )
    }

    @Test
    fun plainChopTakesTheNumberAsItIs() {
        assertEquals(listOf("[ 0/1 → 1/2 | s:bd begin:0 end:0.5 ]", "[ 1/2 → 1/1 | s:bd begin:0.5 end:1 ]"), lines("s(\"bd\")._chop(2)"))
    }

    // ---- striate -------------------------------------------------------------------------------------------------------

    @Test
    fun striatePlaysTheFirstPartOfEveryEventThenTheSecond() {
        val all = lines("s(\"numbers:0 numbers:1 numbers:2\").striate(6).slow(3)", 4)
        assertEquals("[ 0/1 → 1/6 | s:numbers n:0 begin:0 end:0.16666666666666666 ]", all.first())
        assertTrue("[ 5/2 → 8/3 | s:numbers n:0 begin:0.8333333333333334 end:1 ]" in all, all.toString())
        assertEquals("[ 23/6 → 4/1 | s:numbers n:2 begin:0.16666666666666666 end:0.3333333333333333 ]", all.last())
    }

    @Test
    fun striateTakesTheEventsOfTheNextCyclesAsItsParts() {
        // output cycle m uses the cycles m*n+i of the pattern
        assertEquals(listOf("[ 0/1 → 1/2 | s:a begin:0 end:0.5 ]", "[ 1/2 → 1/1 | s:b begin:0.5 end:1 ]"), lines("s(\"<a b>\").striate(2)"))
    }

    @Test
    fun striateOverwritesBeginAndEnd() {
        assertEquals(
            listOf("[ 0/1 → 1/2 | s:bd begin:0 end:0.5 ]", "[ 1/2 → 1/1 | s:bd begin:0.5 end:1 ]"),
            lines("s(\"bd\").begin(0.25).end(0.75).striate(2)").map { it },
        )
    }

    @Test
    fun striateMultipliesTheSteps() {
        assertEquals(Rational(2), SampleFunctions.striate(2.0, sound("s" to "bd")).steps)
        assertEquals(emptyList(), SampleFunctions.striate(0.0, sound("s" to "bd")).firstLines())
    }

    // ---- loopAt and loopAtCps ------------------------------------------------------------------------------------------

    @Test
    fun loopAtSlowsTheSampleDownToFillTheCycles() {
        assertEquals(
            listOf("[ 0/1 → 2/1 | s:rhodes speed:0.25 unit:c ]", "[ 2/1 → 4/1 | s:rhodes speed:0.25 unit:c ]"),
            lines("s(\"rhodes\").loopAt(2)", 4),
        )
    }

    @Test
    fun loopAtReadsTheTempoOfTheQuery() {
        assertEquals(listOf("[ (0/1 → 1/1) ⇝ 2/1 | s:rhodes speed:0.5 unit:c ]"), linesAt("s(\"rhodes\").loopAt(2)", 1.0))
        assertEquals(listOf("[ (0/1 → 1/1) ⇝ 4/1 | s:rhodes speed:0.125 unit:c ]"), linesAt("s(\"rhodes\").loopAt(4)", 0.5))
    }

    @Test
    fun loopAtDividesTheSteps() {
        val pat = sound("s" to "bd").setSteps(Rational(4))
        assertEquals(Rational(2), SampleFunctions.loopAt(2.0, pat).steps)
        assertEquals(null, SampleFunctions.loopAt(2.0, sound("s" to "bd").setSteps(null)).steps)
    }

    @Test
    fun loopAtByZeroFailsWhenThePatternHasSteps() {
        assertEquals("Division by Zero", assertFailsWith<CosicError> { SampleFunctions.loopAt(0.0, sound("s" to "bd")) }.message)
    }

    @Test
    fun loopAtCpsUsesTheTempoItIsGiven() {
        assertEquals("[ (0/1 → 1/1) ⇝ 4/1 | s:rhodes speed:0.375 unit:c cps:1.5 ]", lines("s(\"rhodes\").loopAtCps(4,1.5).cps(1.5)", 4).first())
        assertEquals(listOf("[ 0/1 → 1/1 | s:bd speed:1 unit:c ]"), lines("s(\"bd\").loopatcps(1, 1)"))
        // no tempo is the tempo 0.5
        assertEquals(
            listOf("[ 0/1 → 1/1 | s:bd speed:0.5 unit:c ]"),
            SampleFunctions.loopAtCps(1.0, sound("s" to "bd")).firstLines(),
        )
    }

    // ---- slice ---------------------------------------------------------------------------------------------------------

    @Test
    fun sliceSelectsASliceOfTheSampleForEveryEventOfThePatternOfIndices() {
        assertEquals(
            listOf("[ 0/1 → 1/2 | begin:0 end:0.25 _slices:4 s:bd ]", "[ 1/2 → 1/1 | begin:0.5 end:0.75 _slices:4 s:bd ]"),
            lines("s(\"bd\").slice(4, \"0 2\")"),
        )
    }

    @Test
    fun sliceWithAListOfPointsEndsAtTheNextPoint() {
        val all = lines("s(\"breaks125\").fit().slice([0,.25,.5,.75], \"0 1 1 <2 3>\")", 2)
        assertEquals("[ 0/1 → 1/4 | begin:0 end:0.25 _slices:[0 0.25 0.5 0.75] s:breaks125 speed:1 unit:c ]", all.first())
        // the last slice has no point after it: no `end`
        assertEquals("[ 7/4 → 2/1 | begin:0.75 _slices:[0 0.25 0.5 0.75] s:breaks125 speed:1 unit:c ]", all.last())
    }

    @Test
    fun sliceKeepsWhatTheSampleHasAlreadySet() {
        assertEquals("[ 0/1 → 1/2 | begin:0.1 end:0.25 _slices:4 s:bd ]", lines("s(\"bd\").begin(0.1).slice(4, \"0 2\")").first())
    }

    @Test
    fun sliceOfAPlainValueIsASound() {
        assertEquals(
            listOf("[ 0/1 → 1/1 | begin:0 end:0.25 _slices:4 s:bd ]"),
            SampleFunctions.slice(pure(4.0), pure(0.0), pure("bd")).firstLines(),
        )
    }

    @Test
    fun theStructureOfSliceIsThatOfThePatternOfIndices() {
        assertEquals(Rational(2), SampleFunctions.slice(pure(4.0), fastcat(0.0, 2.0), sound("s" to "bd")).steps)
        assertEquals(
            listOf("[ 0/1 → 1/3 | begin:0 end:0.25 _slices:4 s:bd ]", "[ 1/3 → 2/3 | begin:0.25 end:0.5 _slices:4 s:bd ]", "[ 2/3 → 1/1 | begin:0.5 end:0.75 _slices:4 s:bd ]"),
            lines("s(\"bd\").slice(4, \"0 1 2\")"),
        )
    }

    @Test
    fun theSlicesCanBePatterned() {
        // the number of slices is joined from the inside: the structure is that of the indices
        assertEquals(
            listOf(
                "[ 0/1 → 1/4 | begin:0 end:0.25 _slices:4 s:bd ]", "[ 1/4 → 1/2 | begin:0.25 end:0.5 _slices:4 s:bd ]",
                "[ 1/2 → 3/4 | begin:0.25 end:0.375 _slices:8 s:bd ]", "[ 3/4 → 1/1 | begin:0.375 end:0.5 _slices:8 s:bd ]",
            ),
            lines("s(\"bd\").slice(\"4 8\", \"0 1 2 3\")"),
        )
    }

    @Test
    fun aPlainSliceCallNeedsPatterns() {
        val failure = assertFailsWith<CosicError> { evaluateProgram("s(\"bd\")._slice(4, \"0 2\")") }
        assertEquals("npat.innerBind is not a function", failure.message)
    }

    // ---- splice --------------------------------------------------------------------------------------------------------

    @Test
    fun spliceSpeedsASliceUpOrDownToTakeAsLongAsItsStep() {
        val all = lines("s(\"breaks165\").splice(8, \"0 1 [2 3 0]@2 3 0@2 7\")", 1)
        assertEquals("[ 0/1 → 1/8 | speed:1 unit:c begin:0 end:0.125 _slices:8 s:breaks165 ]", all[0])
        assertEquals("[ 1/4 → 1/3 | speed:1.5 unit:c begin:0.25 end:0.375 _slices:8 s:breaks165 ]", all[2])
        assertEquals("[ 5/12 → 1/2 | speed:1.5 unit:c begin:0 end:0.125 _slices:8 s:breaks165 ]", all[4])
    }

    @Test
    fun spliceKeepsASpeedOrUnitThatIsAlreadySet() {
        assertEquals(
            listOf("[ 0/1 → 1/2 | speed:2 unit:c begin:0 end:0.5 _slices:2 s:x ]", "[ 1/2 → 1/1 | speed:2 unit:c begin:0.5 end:1 _slices:2 s:x ]"),
            lines("s(\"x\").speed(2).splice(2, \"0 1\")"),
        )
        assertEquals("[ 0/1 → 1/2 | speed:1 unit:r begin:0 end:0.5 _slices:2 s:x ]", lines("s(\"x\").unit(\"r\").splice(2, \"0 1\")").first())
    }

    @Test
    fun spliceReadsTheTempoOfTheQuery() {
        assertEquals("[ 0/1 → 1/2 | speed:1 unit:c begin:0 end:0.5 _slices:2 s:x ]", linesAt("s(\"x\").splice(2, \"0 1\")", 1.0)[0])
        assertEquals("[ 0/1 → 1/2 | speed:2 unit:c begin:0 end:0.5 _slices:2 s:x ]", linesAt("s(\"x\").splice(2, \"0 1\")", 2.0)[0])
        // 0 is no tempo: the default is 1
        assertEquals("[ 0/1 → 1/2 | speed:1 unit:c begin:0 end:0.5 _slices:2 s:x ]", linesAt("s(\"x\").splice(2, \"0 1\")", 0.0)[0])
    }

    @Test
    fun spliceOfAListOfPointsHasNoSpeed() {
        // `cps / [0, .5, 1]` is NaN
        assertEquals("[ 0/1 → 1/2 | speed:null unit:c begin:0 end:0.5 _slices:[0 0.5 1] s:bd ]", lines("s(\"bd\").splice([0, 0.5, 1], \"0 1\")").first())
    }

    @Test
    fun aPlainSpliceCallPatternifiesItsArguments() {
        assertEquals(lines("s(\"bd\").splice(pure(4), \"0 2\")"), lines("s(\"bd\")._splice(4, \"0 2\")"))
    }

    // ---- fit -----------------------------------------------------------------------------------------------------------

    @Test
    fun fitMakesTheSampleTakeAsLongAsItsEvent() {
        assertEquals(listOf("[ 0/1 → 2/1 | s:rhodes speed:0.5 unit:c ]", "[ 2/1 → 4/1 | s:rhodes speed:0.5 unit:c ]"), lines("s(\"rhodes/2\").fit()", 4))
    }

    @Test
    fun fitCountsOnlyThePartOfTheSampleTheEventPlays() {
        assertEquals(
            listOf("[ 0/1 → 1/2 | s:bd begin:0.25 end:0.75 speed:1 unit:c ]", "[ 1/2 → 1/1 | s:sd begin:0.25 end:0.75 speed:1 unit:c ]"),
            lines("s(\"bd sd\").begin(0.25).end(0.75).fit()"),
        )
        // a `begin` without an `end` plays to the end of the sample
        assertEquals("[ 0/1 → 1/1 | s:bd begin:0.5 speed:0.5 unit:c ]", lines("s(\"bd\").begin(0.5).fit()").first())
        assertEquals("[ 0/1 → 1/1 | s:bd end:0.5 speed:0.5 unit:c ]", lines("s(\"bd\").end(0.5).fit()").first())
    }

    @Test
    fun fitReadsTheTempoOfTheQuery() {
        assertEquals("[ 0/1 → 1/1 | s:bd speed:2 unit:c ]", linesAt("s(\"bd\").fit()", 2.0).first())
    }

    @Test
    fun fitOfAnEventThatIsNoControlMapIsATypeError() {
        assertEquals(
            "Cannot use 'in' operator to search for 'end' in bd",
            assertFailsWith<CosicError> { SampleFunctions.fit(pure("bd")).firstCycle() }.message,
        )
    }

    // ---- scrub ---------------------------------------------------------------------------------------------------------

    @Test
    fun scrubPlaysFromThePositionAtTheSpeedGiven() {
        assertEquals("[ 0/1 → 1/1 | s:bd begin:0.25 speed:2 clip:1 ]", lines("s(\"bd\").scrub(\"0.25:2\")").first())
        assertEquals("[ 0/1 → 1/1 | s:bd begin:0.5 speed:1 clip:1 ]", lines("s(\"bd\").scrub(0.5)").first())
    }

    @Test
    fun scrubMultipliesASpeedThatIsAlreadySet() {
        assertEquals("[ 0/1 → 1/1 | s:bd speed:6 begin:0.25 clip:1 ]", lines("s(\"bd\").speed(2).scrub(\"0.25:3\")").first())
    }
}
