package cosic.mini

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The characters an atom may be made of are those of Strudel's grammar: the letters of the Unicode tables written into
 * krill.pegjs (BMP only, one UTF-16 code unit at a time), digits, and `~ - # . ^ _`. The table is generated from Strudel's
 * parser (tools/strudel-ref/mini-oracle.mjs --letters); this compares it with the JVM's idea of a letter.
 */
class MiniLettersTest {
    private val jsLetterTypes = setOf(
        Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER, Character.MODIFIER_LETTER,
        Character.OTHER_LETTER, Character.LETTER_NUMBER,
    ).map { it.toInt() }.toSet()

    private fun isJvmLetter(c: Char): Boolean = Character.getType(c) in jsLetterTypes

    @Test
    fun asciiIsLettersDigitsAndSixSymbols() {
        for (code in 0..0x7F) {
            val c = code.toChar()
            val expected = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in "~-#.^_"
            assertEquals(expected, MiniLetters.isStepChar(c), "U+%04X".format(code))
        }
    }

    @Test
    fun theCharactersThatSeparateOrOperateAreNotStepCharacters() {
        for (c in " \t\n\r\u00A0[]{}<>(),|*/!@?:%\"'\$=+;&\\`") assertFalse(MiniLetters.isStepChar(c), "'$c'")
    }

    @Test
    fun otherScriptsAreLetters() {
        for (c in "éÅßΩжא日本語한 ".trim()) assertTrue(MiniLetters.isStepChar(c), "'$c'")
    }

    @Test
    fun surrogatesAreNotStepCharactersOnTheirOwn() {
        // a character outside the BMP is two code units, and neither of them is in the grammar's tables
        for (c in "\uD835\uDC00") assertFalse(MiniLetters.isStepChar(c))
    }

    @Test
    fun everyNonAsciiCharacterOfTheTableIsALetterToo() {
        // the grammar's tables are older than the JVM's Unicode: it may know more letters, but not fewer
        val extra = (0x80..0xFFFF).map { it.toChar() }.filter { MiniLetters.isStepChar(it) && !isJvmLetter(it) }
        assertEquals(emptyList(), extra.map { "U+%04X".format(it.code) }, "characters of the table that are not letters on this JVM")
    }

    @Test
    fun theLettersTheGrammarLacksAreTheFewAddedToUnicodeAfterItsTables() {
        val missing = (0x80..0xFFFF).map { it.toChar() }.filter { isJvmLetter(it) && !MiniLetters.isStepChar(it) }
        println("mini letters: ${missing.size} letters of this JVM are not in Strudel's grammar (added to Unicode since its tables were made)")
        assertTrue(missing.size < 500, "${missing.size} letters of this JVM are not in the table: is it still Strudel's?")
    }
}
