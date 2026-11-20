package com.octopusman.cosic.audio

import kotlin.test.Test
import kotlin.test.assertEquals

class BankNamesTest {
    @Test
    fun aFolderNameBecomesALowercaseNameWithUnderscores() {
        assertEquals("my_kit_2", bankName("My Kit-2"))
        assertEquals("hi_hat_open", bankName("Hi-Hat (open)"))
    }

    @Test
    fun aFolderWithoutAUsableNameHasAnEmptyBankName() {
        assertEquals("", bankName(null))
        assertEquals("", bankName("???"))
    }

    @Test
    fun theFolderThatIsPickedIsABankNamedAfterIt() {
        assertEquals(listOf(ImportedBank("mykit", null)), importedBanks("MyKit"))
    }

    @Test
    fun aFolderInsideThePickedOneIsABankAndAlsoASoundOfTheKitNamedAfterThePickedFolder() {
        assertEquals(
            listOf(ImportedBank("bd", null), ImportedBank("mykit_bd", "mykit")),
            importedBanks("BD", parent = "MyKit"),
        )
    }

    @Test
    fun theKitNameIsCleanedLikeAnyBankName() {
        assertEquals(
            listOf(ImportedBank("hi_hat", null), ImportedBank("my_kit_hi_hat", "my_kit")),
            importedBanks("Hi-Hat", parent = "My Kit"),
        )
    }

    @Test
    fun aFolderWithoutAUsableNameMakesNoBank() {
        assertEquals(emptyList(), importedBanks("???"))
        assertEquals(emptyList(), importedBanks(null, parent = "mykit"))
    }

    @Test
    fun aPickedFolderWithoutAUsableNameMakesNoKit() {
        assertEquals(listOf(ImportedBank("bd", null)), importedBanks("bd", parent = "???"))
        assertEquals(listOf(ImportedBank("bd", null)), importedBanks("bd", parent = null))
    }

    @Test
    fun aSoundOfAKitIsJoinedToTheKitNameAndTakenOffAgain() {
        assertEquals("mykit_bd", kitBankName("mykit", "bd"))
        assertEquals("bd", kitSoundName("mykit", "mykit_bd"))
        assertEquals("closed_hat", kitSoundName("my_kit", "my_kit_closed_hat"))
    }

    @Test
    fun aSoundOfAKitNamedLikeABuiltInSoundOnlyPlaysThroughItsKit() {
        // mykit/bd must not replace the built-in bd for every project: it plays as s("bd").bank("mykit").
        assertEquals(
            listOf(ImportedBank("mykit_bd", "mykit")),
            importedBanks("bd", parent = "mykit", builtIn = { it == "bd" }),
        )
    }

    @Test
    fun thePickedFolderItselfStillReplacesABuiltInSoundOfTheSameName() {
        assertEquals(listOf(ImportedBank("piano", null)), importedBanks("piano", builtIn = { it == "piano" }))
    }
}
