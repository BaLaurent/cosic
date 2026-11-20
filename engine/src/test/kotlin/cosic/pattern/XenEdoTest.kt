package cosic.pattern

import cosic.script.evaluateProgram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `xen`, `withBase`, `ftranspose`, `tuning`, `tune` and `edoScale`: the vectors of @strudel/xen and @strudel/edo's own tests,
 * values computed with the original tunejs.js, and the errors a program can run into. Snippets compared with Strudel's output
 * are in parity/extra-snippets/2.9.json (ParityTest) and the documented examples in examples.json.
 */
class XenEdoTest {
    private fun lines(code: String, cycles: Long = 1): List<String> = evaluateProgram(code).pattern.lines(0, cycles)

    private fun values(code: String): List<Any?> = evaluateProgram(code).pattern.firstCycle().sortedBy { it.part.begin }.map { it.value }

    private fun freqs(code: String): List<Any?> = values(code).map { (it as Map<*, *>)["freq"] }

    // ---- the helpers ------------------------------------------------------------------------------------------------

    @Test
    fun edoIsTheListOfRatiosOfAnEqualDivision() {
        assertEquals(listOf(1.0, Math.pow(2.0, 1.0 / 3), Math.pow(2.0, 2.0 / 3)), Xen.edo("3edo"))
        assertEquals(31, Xen.edo("31edo").size)
        for (bad in listOf("0edo", "edo", "12", "12edox", "1.5edo", "12edo\n")) {
            assertEquals("not an edo scale: \"$bad\"", assertFailsWith<CosicError> { Xen.edo(bad) }.message, bad)
        }
    }

    @Test
    fun aScaleIsRatiosInBase220() {
        assertEquals(listOf(220.0, 330.0), Xen.getXenScale(listOf(1.0, 1.5)))
        assertEquals(Xen.edo("5edo").map { it * 220 }, Xen.getXenScale("5edo"))
        assertEquals(listOf(220.0, 220.0 * 16 / 15), Xen.getXenScale("12ji").take(2))
        assertEquals(listOf(330.0, 440.0), Xen.getXenScale(listOf(1.0, 1.5, 2.0), listOf(1, 2)))
        assertEquals("unknown scale name: \"nonsense\"", assertFailsWith<CosicError> { Xen.getXenScale("nonsense") }.message)
        assertEquals("scale.map is not a function", assertFailsWith<CosicError> { Xen.getXenScale(5.0) }.message)
    }

    @Test
    fun theListOfTuningsIsThereInFull() {
        val hexany = assertNotNull(Xen.tuning("hexany15"))
        assertEquals(6, hexany.size)
        assertEquals(hexany[0] * 1.25, hexany[1], 1e-9)
        assertNull(Xen.tuning("not a tuning"))
        // the first entry of the list
        assertEquals(listOf(261.6255653006, 302.72962012827, 350.29154279212, 405.32593044476, 469.00678383895, 523.2511306012), assertNotNull(Xen.tuning("05-19")).toList())
        // ratios 1, 1.25, 1.3333333333333461 (tune.js: 5 notes of the hexany), 1.5 and 1.6, in base 220
        val scale = Xen.getXenScale("hexany15")
        for ((expected, got) in listOf(1.0, 1.25, 1.3333333333333461, 1.5, 1.6).zip(scale)) assertEquals(expected * 220, got, 1e-9)
    }

    @Test
    fun xenOffsetWrapsIntoTheNextOctave() {
        val scale = listOf(220.0, 275.0, 330.0)
        assertEquals(220.0, Xen.xenOffset(scale, 0.0))
        assertEquals(330.0, Xen.xenOffset(scale, 2.0))
        assertEquals(440.0, Xen.xenOffset(scale, 3.0))
        assertEquals(165.0, Xen.xenOffset(scale, -1.0))
        assertEquals(275.0, Xen.xenOffset(scale, 1.0, 3.0), "the index is added to the offset")
        assertTrue(Xen.xenOffset(scale, 0.5).isNaN(), "a fraction of a step is no step")
        assertTrue(Xen.xenOffset(emptyList(), 1.0).isNaN())
    }

    @Test
    fun trimFreqKeepsTenDigits() {
        assertEquals(234.6666667, Xen.trimFreq(234.66666666666666))
        assertEquals(220.0, Xen.trimFreq(220.0))
        assertEquals(0.0, Xen.trimFreq(0.0))
        assertEquals(1234567891.0, Xen.trimFreq(1234567890.5), "a tie goes up")
        assertEquals(-1234567891.0, Xen.trimFreq(-1234567890.5), "and away from zero")
        assertTrue(Xen.trimFreq(Double.NaN).isNaN())
    }

    // ---- xen ------------------------------------------------------------------------------------------------------------

    @Test
    fun xenNamesTheScaleAndKeepsTheOtherControls() {
        assertEquals(listOf("[ 0/1 → 1/2 | freq:220 ]", "[ 1/2 → 1/1 | freq:290.2917404 ]"), lines("""i("0 2").xen("5edo")"""))
        assertEquals(listOf("[ 0/1 → 1/2 | s:bd freq:220 ]", "[ 1/2 → 1/1 | s:sd freq:242.899693 ]"), lines("""s("bd sd").i("0 1").xen("7edo")"""))
        // `i` leaves, `freq` joins at the end even when it was there before
        assertEquals(listOf("[ 0/1 → 1/1 | s:x freq:293.3333333 ]"), lines("""i("1").s("x").freq("5").xen("hexany23")"""))
    }

    @Test
    fun xenTakesAListOfRatios() {
        assertEquals(listOf("[ 0/1 → 1/3 | freq:220 ]", "[ 1/3 → 2/3 | freq:275 ]", "[ 2/3 → 1/1 | freq:660 ]"), lines("""i("0 1 5").xen([1, 5/4, 3/2])"""))
        assertEquals(listOf("[ 0/1 → 1/1 | freq:null ]"), lines("""i("0.5").xen([1, 3/2, 2])"""), "no step in between: NaN, which prints as null")
    }

    @Test
    fun xenTakesAListOfPatternsOfRatios() {
        // the position in the list comes from `i`, the ratio of "3:2" is 3 / 2
        assertEquals(
            listOf("[ 0/1 → 1/3 | freq:220 ]", "[ 1/3 → 2/3 | freq:275 ]", "[ 2/3 → 1/1 | freq:330 ]"),
            lines("""i("0 1 2").xen(["1:1", "5:4", "3:2"])"""),
        )
    }

    @Test
    fun xenFollowsAPatternOfScalesAndSetsTheEdoSize() {
        val haps = evaluateProgram("""i("0 1").xen("<5edo 31edo>")""").pattern
        assertEquals("5", haps.queryArc(Rational(0), Rational(1)).first().context.extra["edoSize"])
        assertEquals("31", haps.queryArc(Rational(1), Rational(2)).first().context.extra["edoSize"])
        assertNull(evaluateProgram("""i("0").xen("hexany15")""").pattern.queryArc(Rational(0), Rational(1)).first().context.extra["edoSize"])
    }

    @Test
    fun xenFansOutOverSeveralScalesAtOnce() {
        assertEquals(listOf("[ 0/1 → 1/1 | freq:220 ]", "[ 0/1 → 1/1 | freq:220 ]"), lines("""i("0").xen("[5edo 7edo]")"""))
        assertEquals(2, lines("""i("1").xen("[5edo 7edo]")""").size)
    }

    @Test
    fun xenFailsAsTheOriginalDoes() {
        assertEquals(
            "Expected hap to have control 'i' set, but received undefined, try wrapping input in i()",
            assertFailsWith<CosicError> { lines(""""0 1".xen("5edo")""") }.message,
        )
        assertEquals("unknown scale name: \"qux\"", assertFailsWith<CosicError> { lines("""i("0").xen("qux")""") }.message)
        assertEquals("Cannot read properties of undefined (reading 'value')", assertFailsWith<CosicError> { lines("""i("0").xen(silence)""") }.message)
        assertEquals("not an edo scale: \"3\"", assertFailsWith<CosicError> { lines("""edo('3')""") }.message)
        assertEquals("cannot parse as numeral: \"undefined\"", assertFailsWith<CosicError> { lines("""s("a").xen("5edo")""") }.message)
    }

    // ---- withBase, ftranspose, tuning ---------------------------------------------------------------------------------------

    @Test
    fun withBaseMovesTheBase() {
        assertEquals(listOf("[ 0/1 → 1/2 | freq:330 ]", "[ 1/2 → 1/1 | freq:495 ]"), lines("""freq("220 330").withBase(330)"""))
        assertEquals(listOf(300.0, 0.0), freqs("""freq("200 0").withBase("300:200")"""))
        assertEquals(listOf("[ 0/1 → 1/1 | freq:200 ]"), lines("""freq("100").withBase("100:50")"""))
        // a value that is no control map becomes one; a falsy frequency stays as it is
        assertEquals(listOf("[ 0/1 → 1/2 | freq:100 ]", "[ 1/2 → 1/1 | 0 ]"), lines(""""220 0".withBase(100)"""))
    }

    @Test
    fun ftransposeMovesBySteps() {
        assertEquals(listOf("[ 0/1 → 1/1 | freq:440 ]"), lines("""freq("220").ftranspose(12)"""))
        assertEquals(listOf("[ 0/1 → 1/1 | freq:440 ]"), lines("""freq("220").ftrans("19:19")"""))
        // the size comes from the scale named before, else it is 12
        assertEquals(listOf("[ 0/1 → 1/1 | freq:440 ]"), lines("""i("0").xen("31edo").ftrans(31)"""))
        assertEquals(listOf("[ 0/1 → 1/1 | freq:233.0818808 ]"), lines("""i("0").xen("5edo").ftrans("1:12")"""))
        assertEquals(listOf("[ 0/1 → 1/2 | 440 ]", "[ 1/2 → 1/1 | 880 ]"), lines(""""220 440".ftrans(12)"""))
    }

    @Test
    fun ftransposeRemembersTheFirstEdoSizeItFound() {
        // quirk of the original: `edoSize` is a variable of the call, so the first hap's scale decides for all the haps after it
        assertEquals(listOf("[ 0/1 → 1/1 | freq:440 ]", "[ 0/1 → 1/1 | freq:440 ]"), lines("""i("0").xen("[5edo 31edo]").ftrans(5)"""))
    }

    @Test
    fun tuningReadsAScaleFromTheValues() {
        assertEquals(listOf(1.0, 1.25, 1.5, 2.0), values(""""0 1 2 3".tuning([1, 1.25, 1.5])"""))
        assertEquals(listOf(0.75, 1.0), values(""""-1 0".tuning([1, 9/8, 5/4, 3/2])"""))
        assertEquals(listOf(4096.0), values(""""c3".tuning([1, 9/8, 5/4, 3/2])"""), "c3 is step 48")
        assertTrue((values(""""0".tuning(3)""").single() as Double).isNaN(), "no list, no ratio")
    }

    // ---- tune -------------------------------------------------------------------------------------------------------------

    @Test
    fun tuneIsTheRatioOfTheStepToTheTonic() {
        // values made with tunejs.js itself: new Tune(), loadScale('hexany15'), tonicize(1), note(i)
    val got = values("""i("0 1 2 5 6 -1 -7 2.5 7").tune("hexany15")""").map { it as Double }
        val expected = listOf(1.0, 1.25, 1.33333333333, 2.0, 2.5, 0.8, 0.375, Double.NaN, 2.66666666666)
        assertEquals(expected.size, got.size)
        // `i` is chosen per step of the pattern, but the order of the haps is the order of the steps
        for ((e, g) in expected.zip(got)) if (e.isNaN()) assertTrue(g.isNaN()) else assertEquals(e, g, 0.0)
    }

    @Test
    fun tuneTakesAListOfFrequencies() {
        val got = values("""i("0 1 4 5 -1").tune([261.6255653006, 302.72962012827, 350.29154279212, 405.32593044476, 469.00678383895, 523.2511306012])""")
        assertEquals(listOf(1.0, 1.15711023798, 1.79266419663, 2.0, 0.89633209831), got)
    }

    @Test
    fun tuneFailsOnWhatItCannotRead() {
        assertEquals(
            "not a valid tune.js scale name: \"nonsense\". See http://abbernie.github.io/tune/scales.html",
            assertFailsWith<CosicError> { lines("""i("0").tune("nonsense")""") }.message,
        )
        assertEquals(
            "Expected hap to have control 'i' set, but received undefined, try wrapping input in i()",
            assertFailsWith<CosicError> { lines(""""0".tune("hexany15")""") }.message,
        )
    }

    // ---- edoScale ---------------------------------------------------------------------------------------------------------

    @Test
    fun edoScaleOfTheMajorScaleIn12Edo() {
        val a3 = evaluateProgram("""n('0').edoScale(['A3','LLsLLLs',2,1])""").pattern.firstCycle().single().value as Map<*, *>
        assertEquals(220.0, a3["freq"])
        assertEquals(12.0, a3["edo"])
        assertEquals(1.0, a3["degree"])
        assertEquals("220.0000", a3["root"])
        assertEquals(listOf(0.0, 2.0, 4.0, 5.0, 7.0, 9.0, 11.0), a3["degreeIndexes"])
        assertEquals(listOf(null, "M2", "M3", "P4", "P5", "M6", "T7", "P8"), a3["intLabels"])
        assertEquals(setOf("degree", "degreeIndexes", "intLabels", "root", "freq", "edo"), a3.keys, "n is gone")

        val a4 = evaluateProgram("""n('7').edoScale(['A4','LLsLLLs',2,1])""").pattern.firstCycle().single().value as Map<*, *>
        assertEquals(880.0, a4["freq"])
    }

    @Test
    fun edoScaleMakesMidiNotesOfPlainNumbersAndKeepsNoteNames() {
        assertEquals(listOf(48.0, 50.0, 52.0), values(""""0 1 2".edoScale(['C','LLsLLLs',2,1])"""))
        assertEquals(listOf("c4"), values("""n('c4').edoScale(['C','LLsLLLs',2,1])"""))
    }

    @Test
    fun edoScaleReadsTheRootOctaveAndRoundsTheDegree() {
        // a root without an octave, or in octave 0, is in octave 3
        assertEquals("130.8128", rootOf("""n('0').edoScale(['C','LLsLLLs',2,1])"""))
        // ... but the pitch of C0 is the one of C0
        assertEquals("16.3516", rootOf("""n('0').edoScale(['C0','LLsLLLs',2,1])"""))
        assertEquals(16.352, freqOf("""n('0').edoScale(['C0','LLsLLLs',2,1])"""))
        // degrees above the seventh go on in the next octave
        assertEquals(listOf(130.813, 261.626, 293.665), listOf(0, 7, 8).map { freqOf("""n('$it').edoScale(['C','LLsLLLs',2,1])""") })
        // 0.4 rounds to 0, 1.5 to 2
        assertEquals(listOf(130.813, 164.814), listOf("0.4", "1.5").map { freqOf("""n($it).edoScale(['C','LLsLLLs',2,1])""") })
    }

    @Test
    fun edoScaleDegreesOutsideTheTableHaveNoFrequency() {
        val below = evaluateProgram("""n('-1').edoScale(['C','LLsLLLs',2,1])""").pattern.firstCycle().single().value as Map<*, *>
        assertTrue("freq" in below && below["freq"] === Undefined, "undefined, which JSON leaves out")
        assertEquals("[ 0/1 → 1/1 | degree:0 degreeIndexes:[0 2 4 5 7 9 11] intLabels:[null M2 M3 P4 P5 M6 T7 P8] root:130.8128 edo:12 ]", lines("""n('-1').edoScale(['C','LLsLLLs',2,1])""").single())
        val missingN = lines("""s('x').edoScale(['C','LLsLLLs',2,1])""").single()
        assertEquals("[ 0/1 → 1/1 | s:x degree:null degreeIndexes:[0 2 4 5 7 9 11] intLabels:[null M2 M3 P4 P5 M6 T7 P8] root:130.8128 edo:12 ]", missingN)
    }

    @Test
    fun edoScaleCountsTheStepsOfTheSequence() {
        // C:LLsLL:3:1 is 3+3+1+3+3 = 13 divisions with five degrees
        val v = evaluateProgram("""n('0').edoScale(['C','LLsLL',3,1])""").pattern.firstCycle().single().value as Map<*, *>
        assertEquals(13.0, v["edo"])
        assertEquals(listOf(0.0, 3.0, 6.0, 7.0, 10.0), v["degreeIndexes"])
        assertEquals(listOf(null, "s3", "n4", "t5", "d7", "P8"), v["intLabels"])
    }

    @Test
    fun edoScaleRemembersItsDefinitionInTheContext() {
        val hap = evaluateProgram("""n('0').edoScale(['C','LLsLLLs',2,1])""").pattern.queryArc(Rational(0), Rational(1)).single()
        assertEquals(listOf("C", "LLsLLLs", 2.0, 1.0), hap.context.extra["scaleDefinition"])
    }

    @Test
    fun edoScaleNeedsAList() {
        assertEquals("scaleDefinition.flat is not a function", assertFailsWith<CosicError> { lines("""n('0').edoScale('C')""") }.message)
        assertEquals("not a note: \"x\"", assertFailsWith<CosicError> { lines("""n('0').edoScale(['x','LLsLLLs',2,1])""") }.message)
    }

    private fun rootOf(code: String): String = (evaluateProgram(code).pattern.firstCycle().single().value as Map<*, *>)["root"] as String

    private fun freqOf(code: String): Double = (evaluateProgram(code).pattern.firstCycle().single().value as Map<*, *>)["freq"] as Double
}
