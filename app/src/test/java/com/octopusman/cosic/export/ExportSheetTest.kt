package com.octopusman.cosic.export

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What the export sheet makes of its text fields. */
class ExportSheetTest {
    @Test
    fun theCyclesAreTheNumbersOfTheFieldsAndTheEndIsAfterTheStart() {
        assertEquals(0.0 to 4.0, parseCycleRange("0", "4"))
        assertEquals(1.5 to 2.0, parseCycleRange(" 1.5 ", "2"))
        assertNull(parseCycleRange("2", "2"), "an empty range")
        assertNull(parseCycleRange("3", "1"))
        assertNull(parseCycleRange("-1", "1"))
        assertNull(parseCycleRange("", "4"))
        assertNull(parseCycleRange("0", "abc"))
        assertNull(parseCycleRange("0", "Infinity"))
    }

    @Test
    fun theFileNameIsMadeSafeAndEndsInWav() {
        assertEquals("beat.wav", exportFileName("beat", "cosic"))
        assertEquals("beat.WAV", exportFileName("beat.WAV", "cosic"), "an extension in any case is kept")
        assertEquals("a_b_c.wav", exportFileName("a/b:c", "cosic"))
        assertEquals("cosic.wav", exportFileName("   ", "cosic"))
        assertEquals("cosic.wav", exportFileName("...", "cosic"))
    }
}
