package cosic.pattern

import cosic.script.ReplSession
import cosic.script.SessionHost
import cosic.script.evaluateProgram
import cosic.script.scaleModes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Scales, transposition and arpeggios (tonal.mjs and the arp functions of pattern.mjs) against the tests of Strudel's
 * `packages/tonal/test/tonal.test.mjs` and the behaviour of the documentation's examples. What the port makes of every input
 * the libraries take is compared with the libraries themselves by [TonalGoldenTest] and the parity snippets of task 2.8.
 */
class TonalTest {
    private fun haps(code: String, cycles: Long = 1): List<Hap> = evaluateProgram(code).pattern.sortHapsByPart().queryArc(Rational.ZERO, Rational(cycles))

    /** The `note` of every hap (a plain value is its own note), in time order. */
    private fun notes(code: String, cycles: Long = 1): List<Any?> = haps(code, cycles).map { (it.value as? Map<*, *>)?.get("note") ?: it.value }

    private fun lines(code: String, cycles: Long = 1): List<String> = evaluateProgram(code).pattern.lines(0, cycles)

    // ---- tonal.test.mjs: scale -----------------------------------------------------------------------------------------------------

    @Test
    fun scaleTransposeMovesNotesByScaleDegrees() {
        assertEquals(listOf<Any?>("D3"), notes("pure('c3').scale('C major').scaleTranspose(1)"))
    }

    @Test
    fun scaleConvertsPlainValues() {
        assertEquals(listOf<Any?>("C3", "D3", "E3"), notes("seq(0, 1, 2).scale('C major').note()"))
    }

    @Test
    fun scaleConvertsNValues() {
        assertEquals(listOf<Any?>("C3", "D3", "E3"), notes("n(seq(0, 1, 2)).scale('C major')"))
    }

    @Test
    fun scaleNamesCanBeWrittenInThreeWays() {
        val expected = listOf<Any?>("C3", "D3", "E3")
        assertEquals(expected, notes("n(seq(0, 1, 2)).scale('C:major')"))
        assertEquals(expected, notes("n(seq(0, 1, 2)).scale('C major')"))
        assertEquals(expected, notes("n(seq(0, 1, 2)).scale(\"C:major\")"))
        assertEquals(lines("n(seq(0, 1, 2)).scale('C major')"), lines("n(seq(0, 1, 2)).scale(\"C:major\")"))
    }

    @Test
    fun scaleWithoutTonicStartsOnC() {
        assertEquals(listOf<Any?>("C3", "D3", "E3"), notes("n(seq(0, 1, 2)).scale('major')"))
    }

    @Test
    fun scaleConvertsDecoratedValues() {
        assertEquals(listOf<Any?>("B2", "Eb3", "A2", "G3", "F3"), notes("n(seq('0b', '1#', '-2', '3##', '4bb')).scale('C major')"))
    }

    @Test
    fun scaleSnapsNotesUpwardsToTheScale() {
        assertEquals(listOf<Any?>("B2", "E3", "G3", "B3", "B3"), notes("note(seq('Cb', 'Eb', 'G', 'A#', 'Bb')).scale('C major')"))
    }

    @Test
    fun scaleSnapsNotesToTheCorrectOctave() {
        assertEquals(
            listOf<Any?>("B#-1", "D#4", "G#1", "A#19", "A#8"),
            notes("note(seq('Cb0', 'Eb4', 'G1', 'A#19', 'Bb8')).scale('A# minor')"),
        )
    }

    @Test
    fun scaleHandlesNamesWithColons() {
        assertEquals(listOf<Any?>("A#2", "D#3", "G#3", "A#3", "A#3"), notes("note(seq('Cb', 'E', 'G', 'A#', 'Bb')).scale('F#:pentatonic')"))
    }

    @Test
    fun scaleOfTheDocumentation() {
        assertEquals(
            listOf("[ 0/1 → 1/4 | note:C3 ]", "[ 1/4 → 1/2 | note:D3 ]", "[ 1/2 → 3/4 | note:E3 ]", "[ 3/4 → 1/1 | note:F3 ]"),
            lines("n(\"0 1 2 3\").scale(\"C:major\")"),
        )
    }

    @Test
    fun scaleKeepsTheOtherControlsAndPutsTheNoteLast() {
        assertEquals(listOf("[ 0/1 → 1/1 | s:piano gain:0.5 note:D3 ]"), lines("pure({n: 1, s: 'piano', gain: 0.5}).scale('C major')"))
        assertEquals(listOf("[ 0/1 → 1/1 | note:D3 ]"), lines("pure({value: 1}).scale('C major')"))
    }

    @Test
    fun scaleKeepsTheStepsOfThePattern() {
        assertEquals(Rational(4), evaluateProgram("n(seq(0, 1, 2, 3)).scale('C major')").pattern.steps)
    }

    @Test
    fun scaleDegreesRoundUpAndWrapAroundWithTheOctave() {
        // 0.5 and 1.5 round up to 1 and 2; 7 is the root an octave up; negative degrees count down from it
        assertEquals(listOf<Any?>("D3", "E3", "C4", "D4", "B2", "C3"), notes("n(seq(0.5, 1.5, 7, 8, -1, 0)).scale('C major')"))
        assertEquals(listOf<Any?>("A2", "G2", "C2"), notes("n(seq(-2, -3, -7)).scale('C major')"))
    }

    @Test
    fun scaleWithAnAnchorGivesMidiNumbers() {
        assertEquals(
            listOf("[ 0/1 → 1/2 | anchor:c4 note:60 ]", "[ 1/2 → 1/1 | anchor:c4 note:62 ]"),
            lines("n(seq(0, 1)).anchor('c4').scale('C major')"),
        )
    }

    // ---- scale: what goes wrong ----------------------------------------------------------------------------------------------------

    private fun playedWithLogs(code: String): Pair<List<Hap>, List<String>> {
        val host = SessionHost()
        val pattern = ReplSession(host).evaluate(code).pattern
        val haps = pattern.sortHapsByPart().queryArc(Rational.ZERO, Rational.ONE)
        return haps to host.recentLines().map { it.message }
    }

    @Test
    fun aDegreeInAnUnknownScaleIsLoggedAndDropped() {
        val (haps, log) = playedWithLogs("n(seq(0, 1)).scale('C bogus')")
        assertEquals(emptyList(), haps)
        // every scale name that is not one is reported as incomplete: the original's check for an invalid name is never reached
        assertTrue(log.any { "Scale name C bogus is incomplete. Make sure to use \":\" instead of spaces, example: .scale(\"C:major\")" in it }, "$log")
        val (_, bare) = playedWithLogs("n(seq(0, 1)).scale('C')")
        assertTrue(bare.any { "Scale name C is incomplete" in it }, "$bare")
    }

    @Test
    fun aStepThatIsNoNumberIsLoggedAndDropped() {
        val (haps, log) = playedWithLogs("n(seq(0, 'x', 2)).scale('C major')")
        assertEquals(listOf<Any?>("C3", "E3"), haps.map { (it.value as Map<*, *>)["note"] })
        assertTrue(log.any { "invalid scale step \"x\"" in it }, "$log")
    }

    @Test
    fun aNoteThatCannotBeQuantizedFailsTheQuery() {
        val failure = assertFailsWith<CosicError> { evaluateProgram("note(seq('c', 'e')).scale('C bogus')").pattern.queryArc(Rational.ZERO, Rational.ONE) }
        assertTrue("Scale name C bogus is incomplete" in failure.message, failure.message)
    }

    @Test
    fun aValueWithoutNoteOrStepIsLoggedAndKept() {
        val (haps, log) = playedWithLogs("s(\"bd hh\").scale('C major')")
        assertEquals(2, haps.size)
        assertTrue(log.any { "Invalid value format for 'scale'. Value must contain n, note, or value but received keys [s]" in it }, "$log")
    }

    // ---- scaleTranspose ----------------------------------------------------------------------------------------------------------------

    @Test
    fun scaleTransposeCountsStepsOfTheScaleAcrossOctaves() {
        assertEquals(listOf<Any?>("D3", "F3", "A3"), notes("n(seq(0, 2, 4)).scale('C major').scaleTranspose(1)"))
        assertEquals(listOf<Any?>("B2", "D3", "F3"), notes("n(seq(0, 2, 4)).scale('C major').scaleTranspose(-1)"))
        assertEquals(listOf<Any?>("C4", "E4", "G4"), notes("n(seq(0, 2, 4)).scale('C major').scaleTranspose(7)"))
        assertEquals(listOf<Any?>("C1", "E1", "G1"), notes("n(seq(0, 2, 4)).scale('C major').scaleTranspose(-14)"))
    }

    @Test
    fun scaleTransposeOfTheDocumentation() {
        assertEquals(
            listOf("[ 0/1 → 1/2 | note:C3 ]", "[ 1/2 → 1/1 | note:E4 ]", "[ 1/2 → 1/1 | note:G4 ]", "[ 1/2 → 1/1 | note:A4 ]"),
            lines("\"-8 [2,4,6]\".scale('C4 bebop major').scaleTranspose(\"<0 -1 -2 -3 -4 -5 -6 -4>\").note()").take(4),
        )
    }

    @Test
    fun scaleTransposeNeedsAScaleAndNotes() {
        assertEquals(
            "can only use scaleTranspose after .scale",
            assertFailsWith<CosicError> { haps("note('c3').scaleTranspose(1)") }.message,
        )
        assertEquals(
            "can only use scaleTranspose with notes",
            assertFailsWith<CosicError> { haps("n(seq(0, 2)).scale('C major').fmap(x => 5).scaleTranspose(1)") }.message,
        )
        val outside = assertFailsWith<CosicError> { haps("note('c#3').scale('C major').transpose(1).scaleTranspose(1)") }
        assertEquals("note \"Eb3\" is not in scale \"C major\"", outside.message)
    }

    // ---- transpose -----------------------------------------------------------------------------------------------------------------------

    @Test
    fun transposeNumbersByIntervalNumbers() {
        assertEquals(listOf<Any?>(40.0, 41.0, 42.0), notes("note(seq(40, 40, 40)).transpose(0, 1, 2)"))
        assertEquals(listOf<Any?>(40.0, 41.0, 42.0), notes("seq(40, 40, 40).transpose(0, 1, 2)"))
    }

    @Test
    fun transposeNumbersByIntervalStrings() {
        assertEquals(listOf<Any?>(40.0, 42.0, 43.0), notes("note(seq(40, 40, 40)).transpose('1P', '2M', '3m')"))
        assertEquals(listOf<Any?>(40.0, 42.0, 43.0), notes("seq(40, 40, 40).transpose('1P', '2M', '3m')"))
    }

    @Test
    fun transposeNoteStringsByIntervalNumbers() {
        assertEquals(listOf<Any?>("C", "Db", "D"), notes("note(seq('c', 'c', 'c')).transpose(0, 1, 2)"))
        assertEquals(listOf<Any?>("C", "Db", "D"), notes("seq('c', 'c', 'c').transpose(0, 1, 2)"))
    }

    @Test
    fun transposeNoteStringsByIntervalStrings() {
        assertEquals(listOf<Any?>("C", "D", "Eb"), notes("note(seq('c', 'c', 'c')).transpose('1P', '2M', '3m')"))
        assertEquals(listOf<Any?>("C", "D", "Eb"), notes("seq('c', 'c', 'c').transpose('1P', '2M', '3m')"))
    }

    @Test
    fun transposeAddsTheOctaveOnlyWhenTheResultLeavesOctaveThree() {
        assertEquals(listOf<Any?>("Bb"), notes("note(seq('g')).transpose(3)").take(1))
        assertEquals(listOf<Any?>("C4"), notes("note(seq('g')).transpose(5)").take(1))
        assertEquals(listOf<Any?>("F2"), notes("note(seq('g')).transpose(-14)").take(1))
        assertEquals(listOf<Any?>("Bb3"), notes("note(seq('g3')).transpose(3)").take(1))
    }

    @Test
    fun transposeSpellsTheResultToFitTheInterval() {
        assertEquals(listOf<Any?>("Gb3", "F#3"), notes("note(seq('c3', 'c3')).transpose('5d', '4A')"))
        assertEquals(listOf<Any?>("Bb1"), notes("note('c2').transpose(-2)"))
    }

    @Test
    fun transposeKeepsTheControlsOfAMapAndLeavesWhatIsNoNoteAlone() {
        assertEquals(listOf("[ 0/1 → 1/1 | note:D3 s:piano ]"), lines("pure({note: 'c3', s: 'piano'}).transpose(2)"))
        assertEquals(listOf("[ 0/1 → 1/1 | s:bd ]"), lines("pure({s: 'bd'}).transpose(2)"))
        assertEquals(listOf("[ 0/1 → 1/1 | hello ]"), lines("pure('hello').transpose(2)"))
    }

    @Test
    fun transposeByAnImpossibleIntervalGivesNoNote() {
        assertEquals(listOf<Any?>(""), notes("pure('c4').transpose('bogus')"))
        assertEquals(listOf<Any?>(""), notes("pure('c4').transpose(1.5)"))
        assertEquals(listOf<Any?>(60.0), notes("pure(60).transpose('bogus')"))
    }

    @Test
    fun aNoteNameStrudelAcceptsAndTonalDoesNotFailsTheQuery() {
        // `cs3` passes isNote, but tonal does not read it as a note, and setting its octave fails on tonal's frozen empty note
        val failure = assertFailsWith<CosicError> { haps("pure('cs3').transpose(2)") }
        assertEquals("Cannot add property oct, object is not extensible", failure.message)
    }

    @Test
    fun transposeOfTheDocumentation() {
        assertEquals(
            listOf("[ 0/1 → 1/4 | note:C2 ]", "[ 1/4 → 1/2 | note:C3 ]", "[ 1/2 → 3/4 | note:C2 ]", "[ 3/4 → 1/1 | note:C3 ]"),
            lines("\"c2 c3\".fast(2).transpose(\"<0 -2 5 3>\".slow(2)).note()"),
        )
        assertEquals(
            listOf("[ 0/1 → 1/4 | note:C4 ]", "[ 1/4 → 1/2 | note:Eb4 ]", "[ 1/2 → 3/4 | note:G4 ]", "[ 3/4 → 1/1 | note:C5 ]"),
            lines("note(\"c4 eb4 g4 c5\").trans(0)"),
        )
    }

    @Test
    fun transposeAndScaleTransposeHaveTheirSynonyms() {
        assertEquals(notes("note('c3 e3').transpose(7)"), notes("note('c3 e3').trans(7)"))
        val scaled = "n(seq(0, 2, 4)).scale('C major')"
        assertEquals(notes("$scaled.scaleTranspose(2)"), notes("$scaled.scaleTrans(2)"))
        assertEquals(notes("$scaled.scaleTranspose(2)"), notes("$scaled.strans(2)"))
    }

    // ---- arp and arpWith ------------------------------------------------------------------------------------------------------------------

    @Test
    fun arpPlaysTheNotesOfAChordOneAfterTheOther() {
        assertEquals(
            listOf("[ 0/1 → 1/4 | note:c ]", "[ 1/4 → 1/2 | note:c ]", "[ 1/4 → 1/2 | note:g ]", "[ 1/2 → 3/4 | note:eb ]", "[ 3/4 → 1/1 | note:c ]", "[ 3/4 → 1/1 | note:g ]"),
            lines("note(\"<[c,eb,g]!2 [c,f,ab] [d,f,ab]>\").arp(\"0 [0,2] 1 [0,2]\")", 4).take(6),
        )
        // an index counts modulo the number of notes
        assertEquals(notes("note(\"[c,e,g]\").arp(\"0 1 2 2\")"), notes("note(\"[c,e,g]\").arp(\"3 4 5 -1\")"))
        assertEquals(listOf<Any?>("c", "e", "g", "g"), notes("note(\"[c,e,g]\").arp(\"3 4 5 -1\")"))
    }

    @Test
    fun arpWithPicksWhatTheFunctionGives() {
        assertEquals(listOf<Any?>("g", "g", "ab", "ab"), notes("note(\"<[c,eb,g]!2 [c,f,ab] [d,f,ab]>\").arpWith(haps => haps[2])", 4))
        assertEquals(listOf<Any?>("c", "eb", "g"), notes("note(\"[c,eb,g]\").arpWith(haps => sequence(...haps))"))
        assertEquals(listOf<Any?>("g", "eb", "c"), notes("note(\"[c,eb,g]\").arpWith(haps => sequence(...haps.slice().reverse()))"))
    }

    @Test
    fun arpWithKeepsTheOtherControlsOfTheChosenNote() {
        assertEquals(listOf("[ 0/1 → 1/1 | note:eb s:piano ]"), lines("note(\"[c,eb,g]\").s(\"piano\").arpWith(haps => haps[1])"))
    }

    @Test
    fun arpFailsOnIndicesThatAreNoNumbers() {
        assertFailsWith<CosicError> { haps("note(\"[c,e,g]\").arp(\"a b\")") }
        assertFailsWith<CosicError> { haps("note(\"[c,e,g]\").arp(\"0.5\")") }
    }

    // ---- the scale names -----------------------------------------------------------------------------------------------------------------

    @Test
    fun theScaleModesAreThoseOfTheLibraryThatTheNotationCanHold() {
        val modes = scaleModes()
        for (name in listOf("major", "minor", "dorian", "blues", "chromatic", "melodic:minor", "major:pentatonic", "whole:tone", "bebop", "hirajoshi", "ritusen", "altered"))
            assertTrue(name in modes, "$name is a scale mode")
        // a name with an apostrophe cannot be written in the notation, and a name tonal does not find again cannot be used
        assertFalse(modes.any { "'" in it })
        assertFalse("lydian:#5P:pentatonic" in modes)
        assertEquals(modes.size, modes.toSet().size)
        assertTrue(modes.all { ' ' !in it }, "spaces are colons")
    }

    @Test
    fun everyScaleModeMakesNotesOfAScale() {
        for (mode in scaleModes()) {
            val played = notes("n(\"0 1 2\").scale(\"C:$mode\")")
            assertEquals(3, played.size, mode)
            assertEquals("C3", played[0], mode)
        }
    }

    @Test
    fun scaleTypesCanBeFoundByAnyOfTheirKeys() {
        // the name, an alias, the number of the set of pitch classes and its twelve-digit chroma
        val hirajoshi = Tonal.scaleTypes().first { it.name == "hirajoshi" }
        val named = notes("n(seq(0, 1, 2, 3)).scale('C hirajoshi')")
        assertEquals(listOf<Any?>("C3", "D3", "Eb3", "G3"), named)
        assertEquals(named, notes("n(seq(0, 1, 2, 3)).scale('C ${hirajoshi.setNum}')"))
        assertEquals(named, notes("n(seq(0, 1, 2, 3)).scale('C ${hirajoshi.chroma}')"))
        assertEquals(notes("n(seq(0, 1, 2)).scale('C major')"), notes("n(seq(0, 1, 2)).scale('C ionian')"))
        assertEquals(listOf<Any?>("C3", "Eb3", "F3"), notes("n(seq(0, 1, 2)).scale('C minor blues')"))
        assertEquals(notes("n(seq(0, 1, 2)).scale('C minor blues')"), notes("n(seq(0, 1, 2)).scale('C blues')"))
        assertEquals(92, Tonal.scaleTypes().size)
    }

    // ---- the library under it -------------------------------------------------------------------------------------------------------------

    @Test
    fun notesAndIntervals() {
        val note = Tonal.note("c#4")
        assertEquals("C#4", note.name)
        assertEquals(61, note.midi)
        assertEquals("C#", note.pc)
        assertEquals(1, note.chroma)
        assertTrue(Tonal.note("cs3").empty, "tonal does not read s as a sharp")
        assertTrue(Tonal.note("h4").empty)
        assertEquals("E4", Tonal.transpose("C4", "3M"))
        assertEquals("Eb4", Tonal.transpose("C4", "3m"))
        assertEquals("", Tonal.transpose("C4", "nonsense"))
        assertEquals("B3", Tonal.transpose("C4", "-2m"))
        assertEquals("3m", Tonal.intervalFromSemitones(3.0))
        assertEquals("-8P", Tonal.intervalFromSemitones(-12.0))
        assertEquals("12P", Tonal.intervalAdd("5P", "8P"))
        assertEquals("3m", Tonal.intervalSubstract("5P", "3M"))
        assertEquals(null, Tonal.intervalAdd("5P", "nonsense"))
        assertEquals(7.0, Tonal.intervalSemitones("5P"))
        assertEquals("Db4", Tonal.enharmonic("C#4", "Db"))
        assertEquals(listOf("F3", "Gb3", "G3"), Tonal.rangeChromatic(listOf("F3", "G3")))
        assertEquals(listOf("G3", "Gb3", "F3"), Tonal.rangeChromatic(listOf("G3", "F3")))
    }

    @Test
    fun scalesOfTheLibrary() {
        assertEquals(listOf("C", "D", "E", "F", "G", "A", "B"), Tonal.scaleGet("C major").notes)
        assertEquals(listOf("C4", "D4", "E4", "G4", "A4"), Tonal.scaleGet("c4 major pentatonic").notes)
        assertEquals(listOf("Eb", "F", "Gb", "Ab", "Bb", "Cb", "Db"), Tonal.scaleGet("Eb minor").notes)
        assertEquals("" to "major", Tonal.scaleTokenize("major"))
        assertEquals("C" to "melodic minor", Tonal.scaleTokenize("C Melodic Minor"))
        assertTrue(Tonal.scaleGet("C bogus").empty)
        assertEquals("", Tonal.scaleGet("major").tonic)
        assertEquals(emptyList(), Tonal.scaleGet("major").notes)
    }
}
