package com.octopusman.cosic.reference

import com.octopusman.cosic.audio.BankOrigin
import com.octopusman.cosic.audio.BankSummary
import cosic.CosicEngine
import cosic.script.ReferenceEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReferenceItemsTest {
    private val texts = SoundTexts(
        synth = SoundText("Synths", "synth doc"),
        drum = SoundText("Drum kit", "drum doc"),
        instrument = SoundText("Instruments", "instrument doc"),
        percussion = SoundText("Percussion", "percussion doc"),
        imported = SoundText("Imported", "imported doc"),
    )
    private val fast = ReferenceEntry("fast", listOf("density"), "Time", ".fast(number)", "Speeds up.", "s(\"bd\").fast(2)", "\$: s(\"bd\").fast(2)")
    private val banks = listOf(
        BankSummary("piano", 26, pitched = true, origin = BankOrigin.BUNDLED, treeUri = null),
        BankSummary("kick", 8, pitched = false, origin = BankOrigin.BUNDLED, treeUri = null),
        BankSummary("mine", 3, pitched = false, origin = BankOrigin.IMPORTED, treeUri = null),
    )
    private val items = referenceItems(listOf(fast), banks, texts)

    private fun item(name: String) = items.first { it.name == name }

    @Test
    fun functionsComeFirstThenEverySoundGroup() {
        val categories = items.map { it.category }.distinct()

        assertEquals(listOf("Time", "Synths", "Drum kit", "Instruments", "Percussion", "Imported"), categories)
    }

    @Test
    fun aFunctionKeepsItsEntryAndHasNoSoundToPreview() {
        val function = item("fast")

        assertEquals(listOf("density"), function.aliases)
        assertEquals("\$: s(\"bd\").fast(2)", function.snippet)
        assertNull(function.sound)
    }

    @Test
    fun everySynthAndDrumOfTheEngineIsListed() {
        for (name in CosicEngine.SYNTHS) assertEquals("Synths", item(name).category, name)
        for (name in CosicEngine.DRUMS) assertEquals("Drum kit", items.first { it.name == name && it.category == "Drum kit" }.category, name)
    }

    @Test
    fun aSynthIsPlayedFromANoteAndPreviewedPitched() {
        val synth = items.first { it.name == "sine" && it.category == "Synths" }

        assertEquals("\$: note(\"c3 e3 g3\").s(\"sine\")", synth.snippet)
        assertEquals(SoundTarget("sine", pitched = true), synth.sound)
    }

    @Test
    fun aDrumIsPlayedAsARhythmAndPreviewedUnpitched() {
        val drum = item("bd")

        assertEquals("\$: s(\"bd*4\")", drum.snippet)
        assertEquals(SoundTarget("bd", pitched = false), drum.sound)
    }

    @Test
    fun aPitchedBankIsPlayedFromNotes() {
        val piano = item("piano")

        assertEquals("\$: note(\"c3 e3 g3\").s(\"piano\")", piano.snippet)
        assertEquals(SoundTarget("piano", pitched = true), piano.sound)
        assertEquals("instrument doc", piano.doc)
    }

    @Test
    fun anUnpitchedBankPicksItsSamplesByNumber() {
        val kick = item("kick")

        assertEquals("\$: s(\"kick:0 kick:1\")", kick.snippet)
        assertEquals(SoundTarget("kick", pitched = false), kick.sound)
        assertEquals("Percussion", kick.category)
    }

    @Test
    fun anImportedBankIsListedUnderImported() {
        assertEquals("Imported", item("mine").category)
        assertEquals("imported doc", item("mine").doc)
    }

    @Test
    fun noImportedBanksMeansNoImportedGroup() {
        val withoutImports = referenceItems(listOf(fast), banks.filter { it.origin == BankOrigin.BUNDLED }, texts)

        assertFalse("Imported" in withoutImports.map { it.category })
    }

    @Test
    fun searchMatchesNameAliasAndCategoryIgnoringCaseAndSpaces() {
        assertTrue(item("fast").matches("FAS"))
        assertTrue(item("fast").matches(" density "))
        assertTrue(item("fast").matches("time"))
        assertTrue(item("piano").matches("instr"))
        assertTrue(item("fast").matches(""))
        assertFalse(item("fast").matches("piano"))
    }
}
