package com.octopusman.cosic.audio

import cosic.BankGroup
import cosic.SampleBank
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SoundPreviewTest {
    private fun list(files: Int) = SampleBank("b", listOf(BankGroup(null, null, List(files) { "u$it" })), null, true)

    private fun notes(vararg keys: Pair<String, Double?>) =
        SampleBank("p", keys.map { (note, midi) -> BankGroup(note, midi, listOf("a-$note", "b-$note")) }, null, true)

    @Test
    fun aListOfFilesIsPickedByNumberAndWrapsAround() {
        assertEquals(0 to 0, previewFile(list(3), 0, 36.0))
        assertEquals(0 to 1, previewFile(list(3), 4, 36.0))
        assertEquals(0 to 2, previewFile(list(3), -1, 60.0))
    }

    @Test
    fun aBankWithoutFilesHasNothingToPlay() {
        assertNull(previewFile(list(0), 0, 36.0))
        assertNull(previewFile(SampleBank("e", emptyList(), null, true), 0, 36.0))
    }

    @Test
    fun aPitchedBankPlaysTheRecordingNearestToTheNote() {
        val bank = notes("C2" to 36.0, "C4" to 60.0, "A4" to 69.0)

        assertEquals(1 to 0, previewFile(bank, 0, 60.0))
        assertEquals(0 to 0, previewFile(bank, 0, 36.0))
        assertEquals(2 to 1, previewFile(bank, 1, 67.0))
    }

    @Test
    fun aTieGoesToTheFirstKeyOfTheMapAndNotToTheLowerOne() {
        val bank = notes("A4" to 70.0, "C3" to 50.0) // 60 is 10 from both: the first of them in the file wins

        assertEquals(0 to 0, previewFile(bank, 0, 60.0))
    }

    @Test
    fun keysThatAreNoNotesCannotBeChosen() {
        assertEquals(1 to 0, previewFile(notes("x" to Double.NaN, "C4" to 60.0, "y" to null), 0, 36.0))
        assertNull(previewFile(notes("x" to Double.NaN), 0, 60.0))
    }
}
