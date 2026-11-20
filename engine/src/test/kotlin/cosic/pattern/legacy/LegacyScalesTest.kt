package cosic.pattern.legacy

import cosic.pattern.CosicError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** The note and scale code of the language before Strudel's; goes away with the legacy builtins. */
class LegacyScalesTest {
    @Test
    fun noteNamesToMidi() {
        assertEquals(48, legacyNoteToMidi("c"))
        assertEquals(60, legacyNoteToMidi("c4"))
        assertEquals(51, legacyNoteToMidi("eb3"))
        assertEquals(69, legacyNoteToMidi("a4"))
        assertEquals(69, legacyNoteToMidi("A4"))
        assertEquals(61, legacyNoteToMidi("c#4"))
        assertEquals(61, legacyNoteToMidi("db4"))
        assertEquals(58, legacyNoteToMidi("bb"))
        assertEquals(59, legacyNoteToMidi("b"))
        assertEquals(59, legacyNoteToMidi("cb4"))
        assertEquals(0, legacyNoteToMidi("c-1"))
        assertEquals(127, legacyNoteToMidi("g9"))
    }

    @Test
    fun invalidNoteNamesAreNull() {
        for (bad in listOf("", "h3", "bd", "c##3", "4", "c 4", "c4x", "cb#")) {
            assertNull(legacyNoteToMidi(bad), "'$bad' must not be a note")
        }
    }

    @Test
    fun scaleDegreesToNotes() {
        assertEquals(60.0, scaleNote(7, "C:major"))
        assertEquals(48.0, scaleNote(0, "C:major"))
        assertEquals(52.0, scaleNote(2, "C:major"))
        assertEquals(47.0, scaleNote(-1, "C:major"))
        assertEquals(51.0, scaleNote(2, "C:minor"))
        assertEquals(69.0, scaleNote(0, "A4:minor"))
        assertEquals(57.0, scaleNote(4, "C:majorpentatonic"))
        assertEquals(60.0, scaleNote(5, "C:majorpentatonic"))
    }

    @Test
    fun scaleNamesAreNormalised() {
        assertEquals(59.0, scaleNote(6, "C:harmonic minor"))
        assertEquals(59.0, scaleNote(6, "C:Harmonic_Minor"))
        assertEquals(53.0, scaleNote(1, "D:minor:pentatonic"))
        assertEquals(scaleNote(3, "C:major"), scaleNote(3, "C:ionian"))
        assertEquals(scaleNote(3, "C:minor"), scaleNote(3, "C:aeolian"))
    }

    @Test
    fun everyScaleKnownByThePlanExists() {
        val names = listOf(
            "major", "ionian", "minor", "aeolian", "dorian", "phrygian", "lydian", "mixolydian", "locrian",
            "harmonicminor", "melodicminor", "majorpentatonic", "minorpentatonic", "blues", "chromatic", "wholetone",
        )
        for (name in names) scaleNote(1, "C:$name")
        assertEquals(49.0, scaleNote(1, "C:chromatic"))
        assertEquals(50.0, scaleNote(1, "C:wholetone"))
        assertEquals(51.0, scaleNote(1, "C:blues"))
    }

    @Test
    fun unknownScaleThrows() {
        val e = assertFailsWith<CosicError> { scaleNote(0, "C:foo") }
        assertEquals("Unknown scale 'C:foo'", e.message)
        assertFailsWith<CosicError> { scaleNote(0, "H:major") }
        assertFailsWith<CosicError> { scaleNote(0, "C") }
    }
}
