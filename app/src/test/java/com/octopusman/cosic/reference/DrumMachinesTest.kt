package com.octopusman.cosic.reference

import com.octopusman.cosic.audio.BankOrigin
import cosic.CosicEngine
import cosic.EvalReport
import cosic.NoSampleStore
import cosic.SoundLibrary
import cosic.sound.RegistryLibrary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A library with no sounds: enough to check that a snippet is well-formed code. */
private val NoSounds: SoundLibrary = RegistryLibrary(NoSampleStore, emptySet())

class DrumMachinesTest {
    private fun kit(vararg sounds: String) = Kit("k", sounds.toList(), folder = null)

    @Test
    fun aMachineOfTheEngineIsAKitWithItsSoundsInTheOrderTheEngineGivesThem() {
        val machines = mapOf("cosic808" to listOf("bd", "sd", "hh"), "cosic909" to listOf("sd", "bd"))

        assertEquals(
            listOf(Kit("cosic808", listOf("bd", "sd", "hh"), null), Kit("cosic909", listOf("sd", "bd"), null)),
            kits(machines, emptyList()),
        )
    }

    @Test
    fun theBanksOfAKitAreItsSoundsInDrumOrderWithOtherNamesLastInAlphabeticalOrder() {
        val banks = listOf("cr", "zap", "bd", "hh", "aaa", "sd", "rim").map { testBank("vcsl_$it", kit = "vcsl") }

        assertEquals(listOf("bd", "sd", "hh", "rim", "cr", "aaa", "zap"), kits(emptyMap(), banks).single().sounds)
    }

    @Test
    fun theKitNameIsTakenOffEachBankNameEvenWhenTheSoundNameHasUnderscores() {
        val banks = listOf(testBank("my_kit_closed_hat", kit = "my_kit"), testBank("my_kit_bd", kit = "my_kit"))

        assertEquals(Kit("my_kit", listOf("bd", "closed_hat"), null), kits(emptyMap(), banks).single())
    }

    @Test
    fun banksThatAreNotSoundsOfAKitMakeNoKit() {
        assertEquals(emptyList(), kits(emptyMap(), listOf(testBank("kick"), testBank("piano", pitched = true))))
    }

    @Test
    fun machinesComeFirstThenTheKitsOfTheBanksByName() {
        val banks = listOf(
            testBank("vcsl_bd", kit = "vcsl"),
            testBank("acme_bd", origin = BankOrigin.IMPORTED, kit = "acme"),
        )

        assertEquals(listOf("cosic808", "acme", "vcsl"), kits(mapOf("cosic808" to listOf("bd")), banks).map { it.name })
    }

    @Test
    fun banksOfAKitNamedLikeAMachineAddTheirSoundsToTheMachine() {
        val machines = mapOf("cosic808" to listOf("bd", "sd", "hh"))
        val banks = listOf(testBank("cosic808_rd", kit = "cosic808"), testBank("cosic808_bd", kit = "cosic808"))

        assertEquals(listOf(Kit("cosic808", listOf("bd", "sd", "hh", "rd"), null)), kits(machines, banks))
    }

    @Test
    fun aMachineWithoutSoundsIsNotAKit() {
        assertEquals(emptyList(), kits(mapOf("empty" to emptyList()), emptyList()))
    }

    @Test
    fun tappingAKitPlaysItsKickOrElseItsFirstSound() {
        assertEquals("k_bd", kit("sd", "bd").previewSound())
        assertEquals("k_cb", kit("cb", "cl").previewSound())
    }

    @Test
    fun aKitWithKickSnareAndHatsPlaysTheFullGroove() {
        assertEquals("\$: s(\"bd sd [~ bd] sd, hh*8\").bank(\"k\")", kit("bd", "sd", "hh", "oh", "cp").snippet())
    }

    @Test
    fun aKitWithoutASnareRestsWhereTheSnareWouldBe() {
        assertEquals("\$: s(\"bd ~ [~ bd] ~, hh*8\").bank(\"k\")", kit("bd", "hh").snippet())
    }

    @Test
    fun aKitWithoutAKickRestsWhereTheKickWouldBe() {
        assertEquals("\$: s(\"~ sd ~ sd, hh*8\").bank(\"k\")", kit("sd", "hh").snippet())
    }

    @Test
    fun aKitWithoutHatsHasNoHatLine() {
        assertEquals("\$: s(\"bd sd [~ bd] sd\").bank(\"k\")", kit("bd", "sd").snippet())
    }

    @Test
    fun aKitWithNoneOfThemPlaysItsFirstFourSounds() {
        assertEquals("\$: s(\"cb cl sh tb\").bank(\"k\")", kit("cb", "cl", "sh", "tb", "perc").snippet())
        assertEquals("\$: s(\"cb\").bank(\"k\")", kit("cb").snippet())
    }

    @Test
    fun everySnippetPlaysOnlySoundsItsKitHasAndIsValidCode() {
        val sets = listOf(
            listOf("bd", "sd", "hh", "oh", "cp", "rim"),
            listOf("bd", "hh"),
            listOf("sd", "hh"),
            listOf("bd", "sd"),
            listOf("cb", "cl", "sh", "tb", "perc"),
            listOf("closed_hat", "kick"),
        )
        for (sounds in sets) {
            val snippet = Kit("k", sounds, null).snippet()
            val pattern = snippet.substringAfter("s(\"").substringBefore("\")")
            val used = Regex("[a-z_][a-z0-9_]*").findAll(pattern).map { it.value }.toSet()

            assertTrue(used.isNotEmpty() && sounds.containsAll(used), "$snippet uses $used, the kit has $sounds")
            assertIs<EvalReport.Ok>(CosicEngine(48_000, NoSounds).evaluate(snippet), snippet)
        }
    }
}
