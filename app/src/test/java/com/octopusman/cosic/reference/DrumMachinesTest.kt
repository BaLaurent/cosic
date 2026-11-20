package com.octopusman.cosic.reference

import com.octopusman.cosic.audio.BankKind
import com.octopusman.cosic.audio.DRUM_MACHINES_TAG
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
    private fun kit(vararg sounds: String) = Kit("k", sounds.toList())
    private fun machineSound(name: String) = testBank(name, tag = DRUM_MACHINES_TAG)

    @Test
    fun aMachineOfTheEngineIsAKitWithItsSoundsInTheOrderTheEngineGivesThem() {
        val machines = mapOf("cosic808" to listOf("bd", "sd", "hh"), "cosic909" to listOf("sd", "bd"))

        assertEquals(
            listOf(Kit("cosic808", listOf("bd", "sd", "hh")), Kit("cosic909", listOf("sd", "bd"))),
            kits(machines, emptyList()),
        )
    }

    @Test
    fun theBanksOfAKitAreItsSoundsInDrumOrderWithOtherNamesLastInAlphabeticalOrder() {
        val banks = listOf("cr", "zap", "bd", "hh", "aaa", "sd", "rim").map { machineSound("vcsl_$it") }

        assertEquals(listOf("bd", "sd", "hh", "rim", "cr", "aaa", "zap"), kits(emptyMap(), banks).single().sounds)
    }

    @Test
    fun theKitIsWhatComesBeforeTheFirstUnderscoreAndTheSoundMayHoldMore() {
        val banks = listOf(machineSound("my_closed_hat"), machineSound("my_bd"))

        assertEquals(Kit("my", listOf("bd", "closed_hat")), kits(emptyMap(), banks).single())
    }

    @Test
    fun banksThatAreNotSoundsOfAKitMakeNoKit() {
        assertEquals(emptyList(), kits(emptyMap(), listOf(testBank("kick"), testBank("piano", pitched = true))))
    }

    @Test
    fun aBankIsOnlyASoundOfAKitWhenTheLibraryTagsItAsADrumMachine() {
        // VCSL has snare_modern and tom_mallet: names with an underscore that no kit has.
        assertEquals(emptyList(), kits(emptyMap(), listOf(testBank("snare_modern"), testBank("tom_mallet"))))
        // What the user imports is never a kit, however it is called.
        assertEquals(emptyList(), kits(emptyMap(), listOf(testBank("mykit_bd", kind = BankKind.USER, tag = DRUM_MACHINES_TAG))))
    }

    @Test
    fun theNamesOfTheKitsKeepTheirCaseAndAreSortedWithoutRegardToIt() {
        val banks = listOf("RolandTR909_bd", "AkaiLinn_sd", "mridangam_ka", "BossDR55_bd").map(::machineSound)

        assertEquals(listOf("AkaiLinn", "BossDR55", "mridangam", "RolandTR909"), kits(emptyMap(), banks).map { it.name })
        assertEquals("RolandTR909_bd", kits(emptyMap(), banks).last().previewSound())
    }

    @Test
    fun aKeyWithNothingAfterTheUnderscoreIsNoSoundOfAKit() {
        assertEquals(emptyList(), kits(emptyMap(), listOf(machineSound("OberheimDMX_"))))
    }

    @Test
    fun machinesComeFirstThenTheKitsOfTheBanksByName() {
        val banks = listOf(machineSound("vcsl_bd"), machineSound("acme_bd"))

        assertEquals(listOf("cosic808", "acme", "vcsl"), kits(mapOf("cosic808" to listOf("bd")), banks).map { it.name })
    }

    @Test
    fun banksOfAKitNamedLikeAMachineAddTheirSoundsToTheMachine() {
        val machines = mapOf("cosic808" to listOf("bd", "sd", "hh"))
        val banks = listOf(machineSound("cosic808_rd"), machineSound("cosic808_bd"))

        assertEquals(listOf(Kit("cosic808", listOf("bd", "sd", "hh", "rd"))), kits(machines, banks))
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
            val snippet = Kit("k", sounds).snippet()
            val pattern = snippet.substringAfter("s(\"").substringBefore("\")")
            val used = Regex("[a-z_][a-z0-9_]*").findAll(pattern).map { it.value }.toSet()

            assertTrue(used.isNotEmpty() && sounds.containsAll(used), "$snippet uses $used, the kit has $sounds")
            assertIs<EvalReport.Ok>(CosicEngine(48_000, NoSounds).evaluate(snippet), snippet)
        }
    }
}
