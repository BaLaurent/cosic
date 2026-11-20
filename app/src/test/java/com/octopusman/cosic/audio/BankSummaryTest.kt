package com.octopusman.cosic.audio

import cosic.BankGroup
import cosic.SoundEntry
import cosic.SampleBank
import cosic.WaveTableBank
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BankSummaryTest {
    private fun bank(name: String, files: Int = 2, tag: String? = null, prebake: Boolean = true) =
        SampleBank(name, listOf(BankGroup(null, null, List(files) { "u$it" })), tag, prebake)

    private fun pitched(name: String, vararg notes: Pair<String, Double>, prebake: Boolean = true) =
        SampleBank(name, notes.map { (note, midi) -> BankGroup(note, midi, listOf("u-$note")) }, null, prebake)

    private fun summary(name: String, kind: BankKind = BankKind.DEFAULT, tag: String? = null) =
        BankSummary(name, 1, false, kind, tag, prebake = kind == BankKind.DEFAULT, treeUri = null)

    @Test
    fun aBankOfFilesIsListedWithItsCountAndWhereItCameFrom() {
        val summaries = bankSummaries(listOf(bank("RolandTR909_bd", 4, tag = "drum-machines")), treeOf = { null })

        assertEquals(listOf(BankSummary("RolandTR909_bd", 4, false, BankKind.DEFAULT, "drum-machines", true, null)), summaries)
    }

    @Test
    fun aPitchedBankCountsAllItsFilesAndIsPitched() {
        val piano = pitched("piano", "C3" to 48.0, "C4" to 60.0)

        val summary = bankSummaries(listOf(piano), treeOf = { null }).single()

        assertEquals(2, summary.count)
        assertEquals(true, summary.pitched)
    }

    @Test
    fun aWavetableBankIsMarkedAsOneAndIsPitched() {
        val table = WaveTableBank("wt_digital", listOf(BankGroup(null, null, listOf("a", "b", "c"))), 2048, null, true)

        val summary = bankSummaries(listOf(table), treeOf = { null }).single()

        assertEquals(BankSummary("wt_digital", 3, true, BankKind.DEFAULT, null, true, null, wavetable = true), summary)
    }

    @Test
    fun aUserBankIsListedUnderItsRegistryKeyWithTheFolderItCameFrom() {
        val asked = ArrayList<String>()

        val summary = bankSummaries(listOf(bank("My Drums", prebake = false)), treeOf = { key -> asked.add(key); null }).single()

        assertEquals("my_drums", summary.name)
        assertEquals(BankKind.USER, summary.kind)
        assertEquals(false, summary.prebake)
        assertEquals(listOf("my_drums"), asked)
    }

    @Test
    fun theDefaultBanksAreNotAskedForAFolder() {
        val asked = ArrayList<String>()

        bankSummaries(listOf(bank("bd")), treeOf = { key -> asked.add(key); null })

        assertEquals(emptyList(), asked)
    }

    @Test
    fun theListIsSortedByNameWithoutRegardToCase() {
        val names = bankSummaries(listOf(bank("b"), bank("B_x"), bank("a"), bank("C"), bank("A_y")), treeOf = { null }).map { it.name }

        assertEquals(listOf("a", "A_y", "b", "B_x", "C"), names)
    }

    @Test
    fun anEntryThatIsNotMadeOfFilesIsLeftOut() {
        val other = object : SoundEntry {
            override val name = "gm_piano"
            override val tag: String? = null
            override val prebake = true
        }

        assertEquals(emptyList(), bankSummaries(listOf(other), treeOf = { null }))
    }

    @Test
    fun aDrumMachineSoundKnowsItsKit() {
        assertEquals("RolandTR909", summary("RolandTR909_bd", tag = "drum-machines").kit)
        assertEquals("mridangam", summary("mridangam_ka", tag = "drum-machines").kit)
        assertEquals("my", summary("my_closed_hat", tag = "drum-machines").kit) // the sound may hold underscores
    }

    @Test
    fun otherBanksHaveNoKit() {
        assertNull(summary("snare_modern").kit) // no tag: a VCSL bank
        assertNull(summary("bd", tag = "drum-machines").kit) // no underscore
        assertNull(summary("OberheimDMX_", tag = "drum-machines").kit) // nothing after it
        assertNull(summary("_x", tag = "drum-machines").kit) // nothing before it
        assertNull(summary("mykit_bd", kind = BankKind.USER, tag = "drum-machines").kit) // what the user adds is never a kit
    }
}
