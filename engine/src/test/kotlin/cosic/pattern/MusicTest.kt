package cosic.pattern

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The note and frequency utilities of Music.kt against Strudel's util.test.mjs. */
class MusicTest {
    @Test
    fun isNote() {
        for (note in "C3 D3 E3 F3 G3 A3 B3 C4 D5 c5 d5 e5 C#3 D##3 Eb3 Fbb3 Bb5 C Bb fs c-1".split(' ')) assertTrue(isNote(note), note)
        for (bad in listOf<Any?>("H5", "X", 1.0, "", "bd", Undefined, null)) assertFalse(isNote(bad), "$bad")
        assertTrue(isNoteWithOctave("c3") && !isNoteWithOctave("c-1"))
    }

    @Test
    fun tokenizeNote() {
        assertEquals(Triple("C", "", 3.0), tokenizeNote("C3"))
        assertEquals(Triple("D", "##", 3.0), tokenizeNote("D##3"))
        assertEquals(Triple("B", "b", 5.0), tokenizeNote("Bb5"))
        assertEquals(Triple("C", "", null), tokenizeNote("C"))
        assertEquals(Triple("B", "bb", null), tokenizeNote("Bbb"))
        assertEquals(Triple("c", "", -1.0), tokenizeNote("c-1"))
        assertTrue(tokenizeNote("c-")!!.third!!.isNaN(), "the octave of c- is NaN, as in JavaScript")
        for (bad in listOf<Any?>("X", "asfasf", 123.0, null)) assertNull(tokenizeNote(bad), "$bad")
    }

    @Test
    fun noteToMidi() {
        val vectors = listOf(
            "A4" to 69.0, "C4" to 60.0, "Db4" to 61.0, "C3" to 48.0, "Cb3" to 47.0, "Cbb3" to 46.0, "C#3" to 49.0, "C##3" to 50.0,
            "c" to 48.0, "eb3" to 51.0, "bb" to 58.0, "c-1" to 0.0, "g9" to 127.0, "fs2" to 42.0, "as" to 58.0,
        )
        for ((name, midi) in vectors) assertEquals(midi, noteToMidi(name), name)
        assertEquals(60.0, noteToMidi("c", 4))
        assertEquals("not a note: \"Q\"", assertFailsWith<CosicError> { noteToMidi("Q") }.message)
        assertEquals("not a note: \"Z\"", assertFailsWith<CosicError> { noteToMidi("Z") }.message)
        assertEquals("not a note: \"undefined\"", assertFailsWith<CosicError> { noteToMidi(Undefined) }.message)
    }

    @Test
    fun frequencies() {
        assertEquals(440.0, midiToFreq(69.0))
        assertEquals(220.0, midiToFreq(57.0))
        assertEquals(69.0, freqToMidi(440.0))
        assertEquals(57.0, freqToMidi(220.0))
        assertEquals(440.0, getFrequency("a4"))
        assertEquals(220.0, getFrequency("a3"))
        assertEquals(440.0, getFrequency(69.0))
        assertEquals(440.0, getFreq(69.0))
        assertEquals(440.0, getFrequency(440.0, "frequency"))
        val whole = TimeSpan(Rational.ZERO, Rational.ONE)
        assertEquals(220.0, getFrequency(Hap(whole, whole, mapOf("freq" to 220.0))))
        assertEquals(220.0, getFrequency(Hap(whole, whole, mapOf("note" to "a3"))))
        assertEquals(220.0, getFrequency(Hap(whole, whole, mapOf("n" to 57.0))))
    }

    @Test
    fun frequencyOfAHapReadsTheContextType() {
        val whole = TimeSpan(Rational.ZERO, Rational.ONE)
        fun hap(value: Any?, type: String?) = Hap(whole, whole, value, HapContext(extra = if (type == null) emptyMap() else mapOf("type" to type)))
        assertEquals(440.0, getFrequency(hap(69.0, "midi")))
        assertEquals(432.0, getFrequency(hap(432.0, "frequency")))
        assertEquals(440.0, getFrequency(hap("a4", null)))
    }

    @Test
    fun notFrequencies() {
        assertEquals("not a note or frequency: Q", assertFailsWith<CosicError> { getFrequency("Q") }.message)
        assertEquals("not a note or frequency: Z", assertFailsWith<CosicError> { getFrequency("Z") }.message)
        val whole = TimeSpan(Rational.ZERO, Rational.ONE)
        assertEquals("not a note or frequency: undefined", assertFailsWith<CosicError> { getFrequency(Hap(whole, whole, mapOf("s" to "x"))) }.message)
        assertEquals("not a note or frequency: undefined", assertFailsWith<CosicError> { getFrequency(mapOf("freq" to 220.0)) }.message)
    }

    @Test
    fun valueToMidi() {
        assertEquals(69.0, valueToMidi(mapOf("freq" to 440.0)))
        assertEquals(60.0, valueToMidi(mapOf("note" to "c4")))
        assertEquals(61.0, valueToMidi(mapOf("note" to 61.0)))
        assertEquals(10.0, valueToMidi(mapOf("s" to "x"), 10.0))
        assertEquals("valueToMidi: expected freq or note to be set", assertFailsWith<CosicError> { valueToMidi(mapOf("s" to "x")) }.message)
        assertEquals("valueToMidi: expected object value", assertFailsWith<CosicError> { valueToMidi("c4") }.message)
    }

    @Test
    fun midi2note() {
        assertEquals("C4", midi2note(60.0))
        assertEquals("Eb3", midi2note(51.0))
        assertEquals("Bb-1", midi2note(10.0))
    }

    @Test
    fun parseNumeral() {
        assertEquals(4.0, parseNumeral(4.0))
        assertEquals(20.0, parseNumeral("20"))
        assertEquals(1.5, parseNumeral(1.5))
        assertEquals(48.0, parseNumeral("c"))
        assertEquals(60.0, parseNumeral("c4"))
        assertEquals(1.0, parseNumeral(true))
        assertEquals("cannot parse as numeral: \"xyz\"", assertFailsWith<CosicError> { parseNumeral("xyz") }.message)
        assertEquals("cannot parse as numeral: \"undefined\"", assertFailsWith<CosicError> { parseNumeral(Undefined) }.message)
    }

    @Test
    fun parseFractional() {
        assertEquals(0.5, parseFractional("h"))
        assertEquals(0.125, parseFractional("e"))
        assertEquals(Math.PI, parseFractional("pi"))
        assertEquals(2.0, parseFractional("2"))
        assertEquals("cannot parse as fractional: \"z\"", assertFailsWith<CosicError> { parseFractional("z") }.message)
    }

    @Test
    fun soundIndexWrapsAndRounds() {
        assertEquals(1, getSoundIndex(1.0, 4))
        assertEquals(2, getSoundIndex(-2.0, 4))
        assertEquals(1, getSoundIndex(5.4, 4))
        assertEquals(0, getSoundIndex(null, 4))
        assertEquals(0, getSoundIndex("x", 4))
        assertEquals(3, getSoundIndex("3", 4))
        assertEquals(2.0, _mod(-2.0, 4.0))
    }
}
