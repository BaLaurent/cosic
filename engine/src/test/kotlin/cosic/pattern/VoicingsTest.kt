package cosic.pattern

import cosic.script.ReplSession
import cosic.script.SessionHost
import cosic.script.evaluateProgram
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Chords and voicings (tonleiter.mjs, voicings.mjs, chord-voicings) against `packages/tonal/test/tonleiter.test.mjs`, the
 * examples of the documentation and the rules of the original that the port keeps. What the port makes of every chord,
 * dictionary, anchor and mode is compared with Strudel's own code by [TonalGoldenTest] and the parity snippets of task 2.8.
 */
class VoicingsTest {
    private val added = ArrayList<String>()

    // the registry is global, and other tests (the parity cases) leave dictionaries and ranges in it
    @BeforeTest
    fun fresh() {
        Voicings.restore()
    }

    @AfterTest
    fun cleanUp() {
        added.clear()
        Voicings.restore()
    }

    private fun lines(code: String, cycles: Long = 1): List<String> = evaluateProgram(code).pattern.lines(0, cycles)

    /** The note of every hap of the program, in time order. */
    private fun notes(code: String, cycles: Long = 1): List<Any?> =
        evaluateProgram(code).pattern.sortHapsByPart().queryArc(Rational.ZERO, Rational(cycles)).map { (it.value as? Map<*, *>)?.get("note") ?: it.value }

    private fun dictionary(vararg entries: Pair<String, List<String>>): Map<String, Any?> = linkedMapOf(*entries)

    // ---- tonleiter.test.mjs ------------------------------------------------------------------------------------------------------------

    @Test
    fun tokenizeChordSplitsRootSymbolAndBass() {
        assertEquals(listOf("C", "m7", null), tokenizeChord("Cm7"))
        assertEquals(listOf("C#", "m7", null), tokenizeChord("C#m7"))
        assertEquals(listOf("Bb", "^7", null), tokenizeChord("Bb^7"))
        assertEquals(listOf("Bb", "^7", "F"), tokenizeChord("Bb^7/F"))
        assertEquals(listOf("C", "", null), tokenizeChord("C"))
        assertEquals(emptyList(), tokenizeChord("cm7"))
        assertEquals(emptyList(), tokenizeChord(""))
    }

    @Test
    fun midi2noteNamesWithFlatsOrSharps() {
        assertEquals("C4", midi2note(60.0, false))
        assertEquals("Db4", midi2note(61.0, false))
        assertEquals("C#4", midi2note(61.0, true))
        assertTrue((midi2note(-1.0, false) as Double).isNaN(), "a number below 0 has no pitch class, which JavaScript turns into NaN")
    }

    @Test
    fun scaleStepWrapsAroundAndKeepsTheOctave() {
        val notes = listOf<Any?>(60.0, 63.0, 67.0)
        for ((offset, midi) in listOf(0 to 60, 1 to 63, 2 to 67, 3 to 72, 4 to 75, -1 to 55, -2 to 51, -3 to 48, -4 to 43)) {
            assertEquals(midi.toDouble(), scaleStep(notes, offset.toDouble()), "offset $offset")
        }
        assertEquals(60.0 + 24, scaleStep(notes, 3.0, 2.0), "two octaves for each time round")
        assertEquals(63.0, scaleStep(listOf("C4", "Eb4", "G4"), 1.0), "names are read as notes")
    }

    @Test
    fun renderVoicingPlacesTheVoicingBelowTheAnchor() {
        val dictionary = dictionary("m7" to listOf("3 7 10 14", "10 14 15 19"))
        fun voicing(chord: String, anchor: String, vararg more: Pair<String, Any?>): List<Any?> {
            val options = more.toMap()
            return renderVoicing(
                chord, dictionary, options["offset"] ?: Undefined, options["n"] ?: Undefined, options["mode"] ?: "below", anchor, Undefined,
            )
        }
        assertEquals(listOf<Any?>("G3", "B3", "D4", "Gb4"), voicing("Em7", "Bb4"))
        assertEquals(listOf<Any?>("Eb4", "G4", "Bb4", "D5"), voicing("Cm7", "D5"))
        assertEquals(listOf<Any?>("Bb4", "D5", "Eb5", "G5"), voicing("Cm7", "G5"))
        assertEquals(listOf<Any?>("Bb4", "D5", "Eb5", "G5"), voicing("Cm7", "g5"))
        assertEquals(listOf<Any?>(70.0), voicing("Cm7", "g5", "n" to 0.0))
        assertEquals(listOf<Any?>(74.0), voicing("Cm7", "g5", "n" to 1.0))
        assertEquals(listOf<Any?>(82.0), voicing("Cm7", "g5", "n" to 4.0))
        assertEquals(listOf<Any?>("Eb5", "G5", "Bb5", "D6"), voicing("Cm7", "g5", "offset" to 1.0))
    }

    @Test
    fun renderVoicingModesAlignTheOtherEnd() {
        val triads = Voicings.entry("triads")!!["dictionary"]
        fun render(mode: String, anchor: String) = renderVoicing("C", triads, Undefined, Undefined, mode, anchor, Undefined)
        // `below`: the top note is at or under the anchor; `above`: the bottom note is at or over it; `duck` is `below` without the anchor itself
        assertEquals(listOf<Any?>("C4", "E4", "G4"), render("below", "g4"))
        assertEquals(listOf<Any?>("G3", "C4", "E4"), render("below", "e4"))
        assertEquals(listOf<Any?>("G4", "C5", "E5"), render("above", "g4"))
        assertEquals(listOf<Any?>("C4", "E4"), render("duck", "g4"))
        assertFailsWith<CosicError> { render("bogus", "g4") }
    }

    @Test
    fun renderVoicingFailsForAnUnknownChord() {
        val dictionary = dictionary("m7" to listOf("3 7 10 14"))
        assertFailsWith<CosicError> { renderVoicing("Cmaj7", dictionary, Undefined, Undefined, Undefined, Undefined, Undefined) }
        assertFailsWith<CosicError> { renderVoicing("nonsense", dictionary, Undefined, Undefined, Undefined, Undefined, Undefined) }
        assertFailsWith<CosicError> { renderVoicing(Undefined, dictionary, Undefined, Undefined, Undefined, Undefined, Undefined) }
    }

    @Test
    fun nearestNumberIndexPicksTheClosestAndPrefersTheFirstOrTheHigher() {
        val numbers = listOf(0.0, 2.0, 4.0, 5.0)
        assertEquals(0, nearestNumberIndex(0.0, numbers, false))
        assertEquals(0, nearestNumberIndex(1.0, numbers, false))
        assertEquals(1, nearestNumberIndex(1.0, numbers, true))
        assertEquals(1, nearestNumberIndex(2.0, numbers, false))
        assertEquals(1, nearestNumberIndex(3.0, numbers, false))
        assertEquals(2, nearestNumberIndex(3.0, numbers, true))
        assertEquals(2, nearestNumberIndex(4.0, numbers, false))
    }

    @Test
    fun stepInNamedScaleCountsFromTheDegreeNearestToTheAnchor() {
        val midi = { name: String -> noteToMidi(name) }
        assertEquals(midi("E3"), stepInNamedScale(1.0, "D major", Undefined))
        assertEquals(midi("G#3"), stepInNamedScale(2.0, "E major", Undefined))
        assertEquals(midi("E3"), stepInNamedScale(0.0, "D major", "E3"))
        assertEquals(midi("E4"), stepInNamedScale(0.0, "D major", "E4"))
        assertEquals(midi("D4"), stepInNamedScale(0.0, "D major", "Eb4"))
        assertEquals(midi("E4"), stepInNamedScale(0.0, "D major", "F4"))
        assertEquals(midi("F#4"), stepInNamedScale(0.0, "D major", "F#4"))
        assertEquals(midi("G4"), stepInNamedScale(0.0, "D major", "G4"))
        assertEquals(midi("F4"), stepInNamedScale(0.0, "F major", "F4"))
        assertEquals(midi("Bb4"), stepInNamedScale(0.0, "F major", "Bb4"))
        assertEquals(midi("C4"), stepInNamedScale(0.0, "F major", "C4"))
        assertEquals(midi("D4"), stepInNamedScale(1.0, "F major", "C4"))
        assertEquals(midi("D3"), stepInNamedScale(1.0, "F major", "C3"))
        assertEquals(midi("Eb4"), stepInNamedScale(1.0, "F minor", "D4"))
    }

    // ---- voicing ---------------------------------------------------------------------------------------------------------------------------

    @Test
    fun voicingOfTheDocumentation() {
        assertEquals(
            listOf("[ 0/1 → 1/4 | note:52 ]", "[ 1/4 → 1/2 | note:60 ]", "[ 1/2 → 3/4 | note:64 ]", "[ 3/4 → 1/1 | note:67 ]"),
            lines("n(\"0 1 2 3\").chord(\"<C Am F G>\").voicing()"),
        )
        assertEquals(
            listOf("[ 0/1 → 1/1 | note:A3 ]", "[ 0/1 → 1/1 | note:C4 ]", "[ 0/1 → 1/1 | note:E4 ]", "[ 0/1 → 1/1 | note:A4 ]", "[ 0/1 → 1/1 | note:C5 ]"),
            lines("chord(\"<Am C D F Am E Am E>\").voicing()"),
        )
    }

    @Test
    fun voicingKeepsTheOtherControlsAndDropsTheVoicingOnes() {
        assertEquals(
            listOf(
                "[ 0/1 → 1/1 | note:A3 s:piano room:0.5 ]", "[ 0/1 → 1/1 | note:C4 s:piano room:0.5 ]", "[ 0/1 → 1/1 | note:E4 s:piano room:0.5 ]",
                "[ 0/1 → 1/1 | note:A4 s:piano room:0.5 ]", "[ 0/1 → 1/1 | note:C5 s:piano room:0.5 ]",
            ),
            lines("pure({chord: 'Am', s: 'piano', room: 0.5}).voicing()"),
        )
        // the anchor, the mode, the offset, n and the octaves are not in the notes it makes
        assertEquals(
            listOf("[ 0/1 → 1/1 | note:E4 ]", "[ 0/1 → 1/1 | note:C5 ]", "[ 0/1 → 1/1 | note:E5 ]", "[ 0/1 → 1/1 | note:G5 ]"),
            lines("pure({chord: 'C', anchor: 'g4', mode: 'duck', offset: 1}).voicing()"),
        )
    }

    @Test
    fun aChordIsAStringOrAMap() {
        assertEquals(listOf("[ 0/1 → 1/1 | note:C3 ]", "[ 0/1 → 1/1 | note:E4 ]", "[ 0/1 → 1/1 | note:G4 ]", "[ 0/1 → 1/1 | note:B4 ]"), lines("chord('C^7').voicing()"))
        assertEquals(lines("chord('C^7').voicing()"), lines("pure('C^7').voicing()"))
    }

    @Test
    fun aChordThatCannotBeVoicedIsLoggedAndPlaysNothing() {
        val host = SessionHost()
        val pattern = ReplSession(host).evaluate("chord(\"<C Q>\").voicing()").pattern
        val haps = pattern.sortHapsByPart().queryArc(Rational.ZERO, Rational(2))
        assertTrue(haps.all { it.whole!!.begin == Rational.ZERO }, "only the chord of the first cycle plays")
        assertTrue(host.recentLines().any { "[voicing]: unknown chord \"Q\"" in it.message }, "${host.recentLines()}")
        assertEquals(emptyList(), lines("chord('C^7').mode('bogus').voicing()"))
        assertEquals(emptyList(), lines("chord('C^7').dict('bogus').voicing()"))
        assertEquals(emptyList(), lines("s('bd').voicing()"))
    }

    @Test
    fun anEntryOnlyGivesItsDictionaryToVoicing() {
        // the mode and the anchor of an entry are overridden by the ones of the hap (which have defaults), so they never apply
        registerEntry("snippetMode", linkedMapOf("dictionary" to Voicings.entry("triads")!!["dictionary"], "mode" to "above", "anchor" to "e2"))
        assertEquals(lines("chord('C').dict('triads').voicing()"), lines("chord('C').dict('snippetMode').voicing()"))
        assertEquals(lines("chord('C').dict('triads').anchor('g4').mode('above').voicing()"), lines("chord('C').dict('snippetMode').anchor('g4').mode('above').voicing()"))
    }

    private fun registerEntry(name: String, entry: Map<String, Any?>) {
        added.add(name)
        Voicings.put(name, entry)
    }

    // ---- the dictionaries ----------------------------------------------------------------------------------------------------------------

    @Test
    fun theRegistryHasTheDictionariesOfStrudel() {
        assertEquals(listOf("lefthand", "triads", "guidetones", "legacy", "ireal", "ireal-ext"), Voicings.names())
        assertEquals(setOf("dictionary", "range", "mode", "anchor"), Voicings.entry("lefthand")!!.keys)
        assertEquals(setOf("dictionary"), Voicings.entry("ireal")!!.keys)
        assertEquals(listOf("F3", "A4"), Voicings.entry("lefthand")!!["range"])
        assertEquals("above", Voicings.entry("guidetones")!!["mode"])
        assertNull(Voicings.entry("ireal")!!["range"])
        assertEquals("ireal", Voicings.defaultDictionary)
    }

    @Test
    fun irealSymbolsHaveTheirAliases() {
        @Suppress("UNCHECKED_CAST")
        val ireal = Voicings.entry("ireal")!!["dictionary"] as Map<String, Any?>
        // no symbol is major; `-` is `m`, `^` is `M` and `+` is `aug`, for the first one only
        assertEquals(ireal["^"], ireal[""])
        assertEquals(ireal["-7"], ireal["m7"])
        assertEquals(ireal["^7"], ireal["M7"])
        assertEquals(ireal["+"], ireal["aug"])
        assertEquals(ireal["-^7"], ireal["m^7"])
        assertEquals(ireal["-^7"], ireal["-M7"])
        assertFalse("mM7" in ireal)
        assertFalse("maj" in ireal)
        assertEquals(62, ireal.keys.count { it in loadedSymbols("simple") })
        assertEquals(85, ireal.size, "the 62 symbols of the file and 23 aliases")
    }

    private fun loadedSymbols(section: String): Set<String> {
        val symbols = LinkedHashSet<String>()
        var inSection = false
        for (line in tonalResource("ireal.txt")) {
            if (line.startsWith("[")) inSection = line == "[$section]" else if (inSection) symbols.add(line.substringBefore('\t'))
        }
        return symbols
    }

    @Test
    fun addVoicingsKeepsAnEntryWithTheDefaultRange() {
        val result = evaluateProgram("addVoicings('snippetA', {'': ['1P 3M 5P']}); pure('C')")
        added.add("snippetA")
        assertEquals(listOf("F3", "A4"), Voicings.entry("snippetA")!!["range"])
        assertEquals(setOf("dictionary", "range"), Voicings.entry("snippetA")!!.keys)
        assertEquals(1, result.pattern.queryArc(Rational.ZERO, Rational.ONE).size)
        evaluateProgram("addVoicings('snippetB', {'': ['1P 3M 5P']}, ['C2', 'C3']); 0")
        added.add("snippetB")
        assertEquals(listOf("C2", "C3"), Voicings.entry("snippetB")!!["range"])
    }

    @Test
    fun registerVoicingsKeepsTheOptions() {
        evaluateProgram("registerVoicings('snippetC', {'': ['1P 3M 5P']}, {range: ['C2', 'C3'], mode: 'above', anchor: 'c4'}); 0")
        added.add("snippetC")
        val entry = Voicings.entry("snippetC")!!
        assertEquals(listOf("dictionary", "range", "mode", "anchor"), entry.keys.toList())
        assertEquals("above", entry["mode"])
    }

    @Test
    fun setVoicingRangeChangesTheRangeAndForgetsTheModeAndAnchor() {
        registerEntry("snippetD", linkedMapOf("dictionary" to dictionary("" to listOf("1P 3M 5P")), "mode" to "above", "anchor" to "c4"))
        evaluateProgram("setVoicingRange('snippetD', ['C2', 'C4']); 0")
        assertEquals(listOf("dictionary", "range"), Voicings.entry("snippetD")!!.keys.toList())
        assertEquals(listOf("C2", "C4"), Voicings.entry("snippetD")!!["range"])
        assertFailsWith<CosicError> { evaluateProgram("setVoicingRange('snippetNowhere', ['C2', 'C4']); 0") }
    }

    @Test
    fun theDefaultDictionaryIsTheOneVoicingUsesWithoutDict() {
        val ireal = lines("chord('C^7').voicing()")
        evaluateProgram("setDefaultVoicings('triads'); 0")
        assertEquals("triads", Voicings.defaultDictionary)
        assertEquals(lines("chord('C^7').dict('triads').voicing()"), lines("chord('C^7').voicing()"))
        assertTrue(ireal != lines("chord('C^7').voicing()"))
        assertEquals("legacy", evaluateProgram("pure(setDefaultVoicings('legacy'))").pattern.firstCycle()[0].value)
        evaluateProgram("resetVoicings(); 0")
        assertEquals("ireal", Voicings.defaultDictionary)
        assertEquals(ireal, lines("chord('C^7').voicing()"))
    }

    @Test
    fun voicingAliasMakesASymbolAnotherNameInEachDictionaryGiven() {
        val result = evaluateProgram(
            """
            const a = {m7: ['3m 5P 7m 9M']};
            const b = {m7: ['3m 7m']};
            voicingAlias('m7', 'min7', [a, b]);
            pure([Object.keys(a).join(), Object.keys(b).join(), a.min7 === a.m7])
            """.trimIndent(),
        )
        assertEquals(listOf("m7,min7", "m7,min7", true), result.pattern.firstCycle()[0].value)
    }

    @Test
    fun theVoicingRegistryIsAViewOfTheRegistry() {
        fun js(code: String): Any? = evaluateProgram("pure($code)").pattern.firstCycle()[0].value
        assertEquals(listOf("F3", "A4"), js("voicingRegistry.lefthand.range"))
        assertEquals("above", js("voicingRegistry.guidetones.mode"))
        assertEquals(true, js("Object.keys(voicingRegistry).includes('ireal-ext')"))
        // a dictionary a program assigns is there for voicing
        evaluateProgram("voicingRegistry.snippetE = {dictionary: {'': ['1P 3M 5P']}}; 0")
        added.add("snippetE")
        assertEquals(
            listOf("[ 0/1 → 1/1 | note:C3 ]", "[ 0/1 → 1/1 | note:E3 ]", "[ 0/1 → 1/1 | note:G3 ]"),
            lines("chord('C').dict('snippetE').anchor('e4').voicing()"),
        )
        // and so is a symbol it adds to one that is there
        assertEquals(emptyList(), lines("chord('Cm').dict('snippetE').anchor('g4').voicing()"))
        evaluateProgram("voicingRegistry.snippetE.dictionary.m = ['1P 3m 5P']; 0")
        assertEquals(
            listOf("[ 0/1 → 1/1 | note:C4 ]", "[ 0/1 → 1/1 | note:Eb4 ]", "[ 0/1 → 1/1 | note:G4 ]"),
            lines("chord('Cm').dict('snippetE').anchor('g4').voicing()"),
        )
        assertEquals(listOf("", "m"), js("Object.keys(voicingRegistry.snippetE.dictionary)"))
        evaluateProgram("delete voicingRegistry.snippetE; 0")
        assertNull(Voicings.entry("snippetE"))
    }

    // ---- voicings (the deprecated function) and the last voicing -------------------------------------------------------------------------------

    @Test
    fun voicingsPicksTheVoicingsOfTheRangeWithTheSmoothestVoiceLeading() {
        val notes = notes("\"<C^7 A7 Dm7 G7>\".voicings('lefthand').note()", 4)
        assertEquals(
            listOf<Any?>("B3", "D4", "E4", "G4", "G3", "B3", "C#4", "F#4", "F3", "A3", "C4", "E4", "F3", "A3", "B3", "E4"),
            notes,
        )
    }

    @Test
    fun voicingsReadsTheRangeOfTheDictionary() {
        evaluateProgram("registerVoicings('snippetF', {'': ['1P 3M 5P']}, {range: ['C4', 'G4']}); 0")
        added.add("snippetF")
        assertEquals(listOf<Any?>("C4", "E4", "G4"), notes("\"C\".voicings('snippetF').note()"))
        evaluateProgram("setVoicingRange('snippetF', ['C5', 'G5']); 0")
        assertEquals(listOf<Any?>("C5", "E5", "G5"), notes("\"C\".voicings('snippetF').note()"))
    }

    @Test
    fun voicingsFailsForAnUnknownDictionary() {
        assertFailsWith<CosicError> { notes("\"C^7\".voicings('bogus').note()") }
    }

    @Test
    fun theLastVoicingStartsAgainWithEachProgramAndResetVoicings() {
        val program = "\"<C^7 A7 Dm7 G7>\".voicings('lefthand').note()"
        val first = notes(program, 4)
        // a program starts from the first voicing: the same program gives the same notes, though the last voicing of the first run was G7's
        assertEquals(first, notes(program, 4))
        val lefthand = Voicings.entry("lefthand")!!["dictionary"]
        val range = listOf<Any?>("F3", "A4")
        val firstOfAll = voicingsInRange("C^7", lefthand, range)[0]
        val a7 = Voicings.nextVoicing("A7", "lefthand")
        assertEquals(bestVoicing("C^7", lefthand, range, a7), Voicings.nextVoicing("C^7", "lefthand"), "the smoothest voicing after A7")
        Voicings.resetLast()
        assertEquals(firstOfAll, Voicings.nextVoicing("C^7", "lefthand"))
        Voicings.nextVoicing("A7", "lefthand")
        evaluateProgram("resetVoicings(); 0")
        assertEquals(firstOfAll, Voicings.nextVoicing("C^7", "lefthand"))
    }

    @Test
    fun everyThreadKeepsItsOwnLastVoicing() {
        Voicings.nextVoicing("A7", "lefthand")
        var other: List<String>? = null
        thread { other = Voicings.nextVoicing("C^7", "lefthand") }.join()
        Voicings.resetLast()
        assertEquals(Voicings.nextVoicing("C^7", "lefthand"), other, "the other thread had not voiced a chord before")
    }

    // ---- rootNotes -----------------------------------------------------------------------------------------------------------------------------

    @Test
    fun rootNotesOfTheDocumentation() {
        assertEquals(
            listOf("[ 0/1 → 1/1 | note:C2 ]", "[ 1/1 → 2/1 | note:A2 ]", "[ 2/1 → 3/1 | note:D2 ]", "[ 3/1 → 4/1 | note:G2 ]"),
            lines("\"<C^7 A7 Dm7 G7>\".rootNotes(2).note()", 4),
        )
    }

    @Test
    fun rootNotesOfAChordControlGivesOnlyTheNote() {
        assertEquals(listOf("[ 0/1 → 1/1 | note:C2 ]"), lines("chord('C^7').s('piano').rootNotes(2)"))
        assertEquals(listOf("[ 0/1 → 1/1 | note:Bb3 ]"), lines("\"Bb^7\".rootNotes(3).note()"))
        assertEquals(listOf("[ 0/1 → 1/1 | note:F#2 ]"), lines("\"F#m7\".rootNotes(2).note()"))
        assertFailsWith<CosicError> { lines("\"hello\".rootNotes(2)") }
    }
}
