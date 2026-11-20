package com.octopusman.cosic.reference

import com.octopusman.cosic.audio.BankOrigin
import cosic.CosicEngine
import cosic.script.ReferenceEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReferenceItemsTest {
    private val texts = ReferenceTexts(
        drumMachines = GroupText("Drum machines", "drum machines doc"),
        synth = GroupText("Synths", "synth doc"),
        drum = GroupText("Drum kit", "drum doc"),
        instrument = GroupText("Instruments", "instrument doc"),
        percussion = GroupText("Percussion", "percussion doc"),
        imported = GroupText("Imported", "imported doc"),
        scale = GroupText("Scales", "scale doc"),
        showSounds = "Show sounds",
        showDrumMachines = "Show drum machines",
        showScales = "Show scales",
    )
    private fun function(name: String, category: String = "Time", aliases: List<String> = emptyList()) =
        ReferenceEntry(name, aliases, category, ".$name()", "doc", "x", "\$: x")

    private val functions = listOf(
        function("fast", aliases = listOf("density")),
        function("s", "Sound", listOf("sound")),
        function("bank", "Sound"),
        function("scale", "Values"),
    )
    private val banks = listOf(
        testBank("piano", 26, pitched = true),
        testBank("kick", 8),
        testBank("mine", 3, origin = BankOrigin.IMPORTED),
        testBank("vcsl_bd", 3, kit = "vcsl"),
        testBank("vcsl_sd", 2, kit = "vcsl"),
        testBank("vcsl_hh", 2, kit = "vcsl"),
        testBank("mykit_cb", 1, origin = BankOrigin.IMPORTED, kit = "mykit"),
        testBank("mykit_bd", 2, origin = BankOrigin.IMPORTED, kit = "mykit"),
    )
    private val scales = listOf("major", "minor", "blues")
    private val items = referenceItems(functions, banks, scales, texts)
    private val soundItems = items.inTab(PanelTab.SOUNDS)

    private fun item(name: String) = items.first { it.name == name }

    @Test
    fun functionsComeFirstThenEverySoundGroupThenTheScales() {
        val categories = items.map { it.category }.distinct()

        assertEquals(
            listOf("Time", "Sound", "Values", "Drum machines", "Synths", "Drum kit", "Instruments", "Percussion", "Imported", "Scales"),
            categories,
        )
    }

    @Test
    fun aFunctionKeepsItsEntryAndHasNoSoundToPreview() {
        val fast = item("fast")

        assertEquals(listOf("density"), fast.aliases)
        assertEquals("\$: x", fast.snippet)
        assertNull(fast.sound)
        assertNull(fast.values)
    }

    @Test
    fun sLeadsToTheSoundsAndBankToTheDrumMachines() {
        assertEquals(ValuesLink("Synths", "Show sounds"), item("s").values)
        assertEquals(ValuesLink("Drum machines", "Show drum machines"), item("bank").values)
    }

    @Test
    fun scaleLeadsToTheScales() {
        assertEquals(ValuesLink("Scales", "Show scales"), item("scale").values)
    }

    @Test
    fun everyScaleIsListedWithAnExampleThatPlaysIt() {
        val minor = item("minor")

        assertEquals("Scales", minor.category)
        assertEquals("scale(\"C:minor\")", minor.signature)
        assertEquals("\$: n(\"0 2 4 6\").scale(\"C:minor\").s(\"triangle\")", minor.snippet)
        assertEquals(scales, items.filter { it.category == "Scales" }.map { it.name })
    }

    @Test
    fun everySynthAndDrumOfTheEngineIsListed() {
        for (name in CosicEngine.SYNTHS) assertTrue(items.any { it.name == name && it.category == "Synths" }, name)
        for (name in CosicEngine.DRUMS) assertTrue(items.any { it.name == name && it.category == "Drum kit" }, name)
    }

    @Test
    fun aSynthIsPlayedFromANoteAndPreviewedPitched() {
        val synth = items.first { it.name == "sine" && it.category == "Synths" }

        assertEquals("\$: note(\"c3 e3 g3\").s(\"sine\")", synth.snippet)
        assertEquals(SoundTarget("sine", pitched = true, count = null), synth.sound)
    }

    @Test
    fun aDrumIsPlayedAsARhythmAndPreviewedUnpitched() {
        val drum = item("bd")

        assertEquals("\$: s(\"bd*4\")", drum.snippet)
        assertEquals(SoundTarget("bd", pitched = false, count = null, variants = CosicEngine.DRUM_VARIANTS), drum.sound)
    }

    @Test
    fun aPitchedBankIsPlayedFromNotes() {
        val piano = item("piano")

        assertEquals("\$: note(\"c3 e3 g3\").s(\"piano\")", piano.snippet)
        assertEquals(SoundTarget("piano", pitched = true, count = 26), piano.sound)
        assertEquals("instrument doc", piano.doc)
    }

    @Test
    fun anUnpitchedBankPicksItsSamplesByNumber() {
        val kick = item("kick")

        assertEquals("\$: s(\"kick:0 kick:1\")", kick.snippet)
        assertEquals(SoundTarget("kick", pitched = false, count = 8), kick.sound)
        assertEquals("Percussion", kick.category)
    }

    @Test
    fun anImportedBankIsListedUnderImported() {
        assertEquals("Imported", item("mine").category)
        assertEquals("imported doc", item("mine").doc)
    }

    @Test
    fun noImportedBanksMeansNoImportedGroup() {
        val withoutImports = referenceItems(functions, banks.filter { it.origin == BankOrigin.BUNDLED }, scales, texts)

        assertFalse("Imported" in withoutImports.map { it.category })
    }

    @Test
    fun searchMatchesNameAliasAndCategoryIgnoringCaseAndSpaces() {
        assertTrue(item("fast").matches("FAS"))
        assertTrue(item("fast").matches(" density "))
        assertTrue(item("fast").matches("time"))
        assertTrue(item("piano").matches("instr"))
        assertTrue(item("minor").matches("scale"))
        assertTrue(item("fast").matches(""))
        assertFalse(item("fast").matches("piano"))
    }

    @Test
    fun aWordInsideACallThatTakesValuesOpensOnThoseValues() {
        assertEquals(OpenOn.Group("Synths"), items.openOn(CursorHit("s", insideCall = true)))
        assertEquals(OpenOn.Group("Drum machines"), items.openOn(CursorHit("bank", insideCall = true)))
        assertEquals(OpenOn.Group("Scales"), items.openOn(CursorHit("scale", insideCall = true)))
    }

    @Test
    fun theFunctionNameItselfOrAnyOtherCallOpensOnTheFunction() {
        assertEquals(OpenOn.Item(item("bank")), items.openOn(CursorHit("bank", insideCall = false)))
        assertEquals(OpenOn.Item(item("fast")), items.openOn(CursorHit("fast", insideCall = true)))
        assertEquals(OpenOn.Item(item("s")), items.openOn(CursorHit("sound", insideCall = false)))
        assertEquals(OpenOn.Item(item("bd")), items.openOn(CursorHit("bd", insideCall = false)))
    }

    @Test
    fun anUnknownNameOpensNowhere() {
        assertNull(items.openOn(CursorHit("nope", insideCall = false)))
        assertNull(items.openOn(null))
    }

    @Test
    fun theChipsListTheSoundsAndScalesBeforeTheFunctions() {
        val categories = items.map { it.category }.distinct()

        assertEquals(
            listOf("Drum machines", "Synths", "Drum kit", "Instruments", "Percussion", "Imported", "Scales", "Time", "Sound", "Values"),
            chipOrder(categories, functions.map { it.category }.toSet()),
        )
    }

    @Test
    fun soundsGoToTheSoundsTabAndEverythingElseToTheReference() {
        assertEquals(PanelTab.SOUNDS, items.tabFor(OpenOn.Group("Synths")))
        assertEquals(PanelTab.SOUNDS, items.tabFor(OpenOn.Group("Drum machines")))
        assertEquals(PanelTab.SOUNDS, items.tabFor(OpenOn.Item(item("bd"))))
        assertEquals(PanelTab.SOUNDS, items.tabFor(OpenOn.Item(item("vcsl"))))
        assertEquals(PanelTab.REFERENCE, items.tabFor(OpenOn.Group("Scales")))
        assertEquals(PanelTab.REFERENCE, items.tabFor(OpenOn.Item(item("fast"))))
        assertEquals(PanelTab.REFERENCE, items.tabFor(null))
    }

    @Test
    fun eachTabListsOnlyItsOwnItems() {
        assertTrue(items.inTab(PanelTab.SOUNDS).all { it.sound != null })
        assertTrue(items.inTab(PanelTab.REFERENCE).none { it.sound != null })
        assertEquals(items.size, items.inTab(PanelTab.SOUNDS).size + items.inTab(PanelTab.REFERENCE).size)
    }

    @Test
    fun aBankKnowsHowManySamplesItHasAndADrumItsVariants() {
        assertEquals(26, item("piano").sound?.count)
        assertEquals(CosicEngine.DRUM_VARIANTS, item("bd").sound?.variants)
        assertNull(item("bd").sound?.count)
        assertEquals(0, item("piano").sound?.variants)
        assertNull(items.first { it.name == "sine" && it.category == "Synths" }.sound?.count)
    }

    @Test
    fun theButtonOfTheEditorPicksTheTabUnlessTheCursorIsOnASound() {
        assertEquals(PanelTab.SOUNDS, items.tabOnOpen(PanelTab.REFERENCE, OpenOn.Item(item("bd"))))
        assertEquals(PanelTab.SOUNDS, items.tabOnOpen(PanelTab.SOUNDS, OpenOn.Item(item("fast"))))
        assertEquals(PanelTab.REFERENCE, items.tabOnOpen(PanelTab.REFERENCE, OpenOn.Item(item("fast"))))
        assertEquals(PanelTab.REFERENCE, items.tabOnOpen(PanelTab.REFERENCE, null))
    }

    @Test
    fun everyDrumMachineOfTheEngineIsListedWithItsSounds() {
        for ((name, sounds) in CosicEngine.DRUM_MACHINES) {
            val machine = items.first { it.name == name && it.category == "Drum machines" }

            assertEquals(sounds, machine.members, name)
            assertNull(machine.folder, name)
        }
    }

    @Test
    fun aKitOfBundledBanksIsOneRowWithItsSoundsInDrumOrder() {
        val vcsl = item("vcsl")

        assertEquals("Drum machines", vcsl.category)
        assertEquals("bank(\"vcsl\")", vcsl.signature)
        assertEquals("drum machines doc", vcsl.doc)
        assertEquals(listOf("bd", "sd", "hh"), vcsl.members)
    }

    @Test
    fun aKitOfImportedBanksIsListedTheSameWay() {
        val mykit = item("mykit")

        assertEquals("Drum machines", mykit.category)
        assertEquals(listOf("bd", "cb"), mykit.members)
    }

    @Test
    fun theSoundsOfAKitAreNotListedAgainAsPercussionOrImportedBanks() {
        val names = items.map { it.name }

        for (member in listOf("vcsl_bd", "vcsl_sd", "vcsl_hh", "mykit_bd", "mykit_cb")) assertFalse(member in names, member)
        assertEquals(listOf("kick"), items.filter { it.category == "Percussion" }.map { it.name })
        assertEquals(listOf("mine"), items.filter { it.category == "Imported" }.map { it.name })
    }

    @Test
    fun tappingAKitPlaysItsKickWithoutPitch() {
        assertEquals(SoundTarget("vcsl_bd", pitched = false, count = null), item("vcsl").sound)
    }

    @Test
    fun aKitIsInsertedAsAGrooveOfTheSoundsItHas() {
        assertEquals("\$: s(\"bd sd [~ bd] sd, hh*8\").bank(\"vcsl\")", item("vcsl").snippet)
        assertEquals("\$: s(\"bd ~ [~ bd] ~\").bank(\"mykit\")", item("mykit").snippet)
    }

    @Test
    fun aKitNameUnderTheCursorOpensOnItsRow() {
        val code = "s(\"bd\").bank(\"vcsl\")"
        val hit = functionAtCursor(code, code.indexOf("vcsl") + 2) { items.named(it) != null }

        assertEquals(OpenOn.Item(item("vcsl")), items.openOn(hit))
    }

    @Test
    fun theCursorBetweenTheQuotesOfBankOpensOnTheDrumMachinesAndInSOnTheSounds() {
        val bank = "s(\"bd\").bank(\"\")"
        val inBank = functionAtCursor(bank, bank.indexOf("\"\")") + 1) { items.named(it) != null }
        val sound = "s(\"\")"
        val inS = functionAtCursor(sound, 3) { items.named(it) != null }

        assertEquals(OpenOn.Group("Drum machines"), items.openOn(inBank))
        assertEquals(OpenOn.Group("Synths"), items.openOn(inS))
    }

    @Test
    fun theSoundGroupsAreListedInTheOrderOfTheirItems() {
        val headings = soundItems.rows("", "Imported").filterIsInstance<ListRow.Heading>().map { it.category }

        assertEquals(listOf("Drum machines", "Synths", "Drum kit", "Instruments", "Percussion", "Imported"), headings)
    }

    @Test
    fun theImportedGroupIsListedWithItsImportRowEvenWhenNothingIsImported() {
        val bundled = referenceItems(functions, banks.filter { it.origin == BankOrigin.BUNDLED }, scales, texts).inTab(PanelTab.SOUNDS)

        assertEquals(listOf(ListRow.Heading("Imported"), ListRow.Import("Imported")), bundled.rows("", "Imported").takeLast(2))
    }

    @Test
    fun theImportRowComesAfterTheGroupDescriptionAndBeforeItsSounds() {
        val rows = soundItems.rows("", "Imported")

        assertEquals(
            listOf(ListRow.Heading("Imported"), ListRow.Doc("Imported", "imported doc"), ListRow.Import("Imported"), ListRow.Entry(item("mine"))),
            rows.dropWhile { it != ListRow.Heading("Imported") },
        )
    }

    @Test
    fun searchingListsOnlyWhatMatchesWithoutTheImportRow() {
        val rows = soundItems.rows("kick", "Imported")

        assertEquals(listOf(ListRow.Heading("Percussion"), ListRow.Doc("Percussion", "percussion doc"), ListRow.Entry(item("kick"))), rows)
    }

    @Test
    fun theReferenceListsItsFunctionsAndScalesWithoutDescriptionsOrImportRow() {
        val rows = items.inTab(PanelTab.REFERENCE).rows("")

        assertTrue(rows.none { it is ListRow.Doc || it is ListRow.Import })
        assertEquals(listOf("Time", "Sound", "Values", "Scales"), rows.filterIsInstance<ListRow.Heading>().map { it.category })
    }
}
