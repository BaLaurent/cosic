package cosic.sound

import cosic.BankGroup
import cosic.pattern.CosicError
import cosic.pattern.getSoundIndex
import cosic.pattern.jsMathRound
import cosic.pattern.midiToFreq
import cosic.pattern.noteToMidi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SampleSelectionTest {
    private fun list(vararg urls: String) = listOf(BankGroup(null, null, urls.toList()))

    private fun pitched(vararg keys: Pair<String, List<String>>) = keys.map { (name, urls) -> BankGroup(name, noteToMidi(name), urls) }

    // ----------------------------------------------------------------- the helpers this builds on (util.mjs)

    @Test
    fun theHelpersGiveTheValuesOfStrudel() {
        assertEquals(-1.0, jsMathRound(-1.5))
        assertEquals(3.0, jsMathRound(2.5))
        assertEquals(-0.0, jsMathRound(-0.5))
        assertEquals(4, getSoundIndex(-1.0, 5))
        assertEquals(1, getSoundIndex(5.5, 5))
        assertEquals(0, getSoundIndex("x", 5))
        assertEquals(48.0, noteToMidi("c"))
        assertEquals(27.0, noteToMidi("Ds1"))
        assertEquals(22.0, noteToMidi("A#0"))
        assertEquals(58.0, noteToMidi("bb3"))
        assertEquals(69.0, cosic.pattern.freqToMidi(440.0))
        assertEquals(440.0, midiToFreq(69.0))
    }

    // ------------------------------------------------------------------------------------- a list of files

    @Test
    fun aListPlaysFileZeroAtItsOwnPitchWhenNothingIsAsked() {
        val choice = commonSampleInfo(list("a", "b"), mapOf())
        assertEquals(0, choice.group)
        assertEquals(0, choice.index)
        assertEquals(0.0, choice.transposeSemitones)
        assertEquals(36.0, choice.midi)
    }

    @Test
    fun noteAndFreqRepitchARelativeToC2() {
        assertEquals(12.0, commonSampleInfo(list("a", "b"), mapOf("note" to 48.0)).transposeSemitones)
        assertEquals(12.0, commonSampleInfo(list("a", "b"), mapOf("note" to "c3")).transposeSemitones)
        assertEquals(-36.0, commonSampleInfo(list("a", "b"), mapOf("note" to 0.0)).transposeSemitones, "note 0 is a note: midi 0")
        assertEquals(69.0, commonSampleInfo(list("a", "b"), mapOf("freq" to 440.0)).midi, 1e-9)
        assertEquals(33.0, commonSampleInfo(list("a", "b"), mapOf("freq" to 440.0, "note" to 12.0)).transposeSemitones, 1e-9, "freq beats note")
    }

    @Test
    fun nPicksTheFileRoundedWrappedAndFromTheEndWhenNegative() {
        fun index(n: Any?) = commonSampleInfo(list("a", "b", "c", "d", "e"), mapOf("n" to n)).index
        assertEquals(1, index(1.0))
        assertEquals(3, index(3.0))
        assertEquals(0, index(5.0))
        assertEquals(4, index(-1.0))
        assertEquals(1, index(5.5))
        assertEquals(2, index(1.5))
        assertEquals(0, index("x"), "anything that is not a number is 0")
        assertEquals(2, index("2"), "a numeric string is its number")
        assertEquals(0, index(null))
    }

    @Test
    fun theFourArgumentFormBuildsTheSameChoice() {
        val choice = commonSampleInfo(list("a", "b"), 3.0, "c3", null)
        assertEquals(1, choice.index)
        assertEquals(12.0, choice.transposeSemitones)
        assertEquals(69.0, commonSampleInfo(list("a"), null, null, 440.0).midi, 1e-9)
    }

    // ------------------------------------------------------------------------------------- pitched banks

    @Test
    fun aPitchedBankPlaysTheKeyNearestToTheNote() {
        val bank = pitched("c2" to listOf("low"), "c4" to listOf("high"))
        val choice = commonSampleInfo(bank, mapOf("note" to "c3"))
        assertEquals(0, choice.group, "c3 is 12 from c2 and 12 from c4: the first key wins the tie")
        assertEquals(12.0, choice.transposeSemitones)
        assertEquals(1, commonSampleInfo(bank, mapOf("note" to 61.0)).group)
        assertEquals(1.0, commonSampleInfo(bank, mapOf("note" to 61.0)).transposeSemitones)
    }

    @Test
    fun theTieGoesToTheFirstKeyOfTheMapWhateverItsPitch() {
        val reversed = pitched("c4" to listOf("high"), "c2" to listOf("low"))
        val choice = commonSampleInfo(reversed, mapOf("note" to "c3"))
        assertEquals(0, choice.group, "c4 comes first")
        assertEquals(-12.0, choice.transposeSemitones)
    }

    @Test
    fun withNoNoteAPitchedBankIsPlayedFromC2() {
        val bank = pitched("c1" to listOf("a"), "c3" to listOf("b"), "c5" to listOf("c"))
        val choice = commonSampleInfo(bank, mapOf())
        assertEquals(0, choice.group, "36 is 12 from c1 (24) and 12 from c3 (48): the first")
        assertEquals(12.0, choice.transposeSemitones)
    }

    @Test
    fun nChoosesTheFileInsideTheKeyNotTheKey() {
        val bank = pitched("c2" to listOf("a", "b", "c"), "c4" to listOf("d"))
        val choice = commonSampleInfo(bank, mapOf("note" to 36.0, "n" to 4.0))
        assertEquals(0, choice.group)
        assertEquals(1, choice.index, "4 wraps by the 3 files of the key")
        assertEquals(0, commonSampleInfo(bank, mapOf("note" to 60.0, "n" to 4.0)).index, "the other key has one file")
    }

    @Test
    fun aKeyThatIsNotANoteIsAnErrorEvenWhenAnotherKeyIsNearer() {
        val bank = listOf(BankGroup("c4", 60.0, listOf("a")), BankGroup("junk", Double.NaN, listOf("b")))
        val error = assertFailsWith<CosicError> { commonSampleInfo(bank, mapOf("note" to 60.0)) }
        assertEquals("not a note: \"junk\"", error.message)
    }

    @Test
    fun aBankWithoutFilesIsAnError() {
        assertFailsWith<CosicError> { commonSampleInfo(emptyList(), mapOf()) }
        assertFailsWith<CosicError> { commonSampleInfo(list(), mapOf()) }
        assertFailsWith<CosicError> { commonSampleInfo(pitched("c4" to emptyList()), mapOf()) }
    }

    @Test
    fun theMidiOfAKeyIsReadFromItsNameWhenTheGroupHasNone() {
        val bank = listOf(BankGroup("c4", null, listOf("a")))
        assertEquals(1.0, commonSampleInfo(bank, mapOf("note" to 61.0)).transposeSemitones)
        assertTrue(commonSampleInfo(bank, mapOf()).transposeSemitones < 0)
    }
}
