package com.octopusman.cosic.audio

import cosic.BuiltinSound
import cosic.CosicEngine
import cosic.SampleBank
import cosic.SampleData
import cosic.SampleStore
import cosic.WaveTableBank
import cosic.sound.RegistryLibrary
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

private const val CDN = "https://strudel.b-cdn.net"

/** The maps of the default library as files of the test resources (copies of the live ones); [failing] are the file names that cannot be read. */
private class FixtureStore(val failing: MutableSet<String> = HashSet(), private val gate: CountDownLatch? = null) : SampleStore {
    val fetched = CopyOnWriteArrayList<String>()

    override fun fetchText(url: String): String {
        gate?.await(10, TimeUnit.SECONDS)
        fetched.add(url)
        val file = url.removePrefix("$CDN/")
        if (file in failing) throw IOException("offline")
        val stream = DefaultLibraryTest::class.java.getResourceAsStream("/samples/$file") ?: throw IOException("no fixture $file")
        return stream.bufferedReader().use { it.readText() }
    }

    override fun sample(url: String): SampleData? = null

    override fun awaitSample(url: String): SampleData = throw IOException("no samples here")

    override fun decode(bytes: ByteArray, nameHint: String): SampleData = throw IOException("no decoder here")
}

private val ALL_MAP_FILES = setOf(
    "piano.json", "vcsl.json", "tidal-drum-machines.json", "uzu-drumkit.json", "uzu-wavetables.json", "mridangam.json",
    "tidal-drum-machines-alias.json",
)

class DefaultLibraryTest {
    private fun library(store: FixtureStore = FixtureStore(), ready: Boolean = true) =
        RegistryLibrary(store, CosicEngine.BUILTIN_SOUNDS, ready)

    private fun loaded(): RegistryLibrary {
        val library = library()
        assertEquals(emptyList(), runBlocking { DefaultLibrary.load(library) })
        return library
    }

    @Test
    fun everyMapIsFetchedFromTheCdnAndTheAliasMapComesLast() {
        val store = FixtureStore()
        runBlocking { DefaultLibrary.load(library(store)) }

        assertEquals(ALL_MAP_FILES.map { "$CDN/$it" }.toSet(), store.fetched.toSet())
        assertEquals("$CDN/tidal-drum-machines-alias.json", store.fetched.last())
    }

    @Test
    fun bdIsTheBankOfTheDrumKitWithEightFiles() {
        val bd = loaded().sound("bd") as SampleBank

        assertEquals(8, bd.count)
        assertFalse(bd.pitched)
        assertEquals("drum-machines", bd.tag)
        assertTrue(bd.prebake)
        assertEquals("$CDN/uzu-drumkit/bd/10_bd_switchangel.wav", bd.groups.single().urls.first())
    }

    @Test
    fun aMachineAndItsAliasesAreOneEntry() {
        val library = loaded()
        val machine = library.sound("rolandtr909_bd") as SampleBank

        assertSame(machine, library.sound("RolandTR909_bd"))
        assertSame(machine, library.sound("tr909_bd"))
        assertSame(machine, library.sound("TR909_bd")) // lookups ignore case
        assertEquals("RolandTR909_bd", machine.name)
        assertEquals(4, machine.count)
        assertEquals("$CDN/tidal-drum-machines/machines/RolandTR909/rolandtr909-bd/Bassdrum-01.wav", machine.groups.single().urls.first())
    }

    @Test
    fun theMachinesAreFromTheListOfTheTidalDrumMachines() {
        val library = loaded()
        val machines = library.entries().filterIsInstance<SampleBank>().filter { it.name.contains('_') && it.tag == "drum-machines" && !it.name.startsWith("mridangam") }

        // 683 names of 71 machines, one of them (OberheimDMX_) with nothing after the underscore
        assertEquals(683, machines.size)
        assertEquals(71, machines.map { it.name.substringBefore('_') }.toSet().size)
    }

    @Test
    fun pianoIsOneBankOfTwentyNineNotesInTheOrderOfTheFile() {
        val piano = loaded().sound("piano") as SampleBank

        assertTrue(piano.pitched)
        assertEquals(29, piano.groups.size)
        assertEquals("A0", piano.groups.first().noteName)
        assertEquals(21.0, piano.groups.first().midi)
        assertEquals(listOf("$CDN/piano/A0v8.mp3"), piano.groups.first().urls) // the base of the CDN replaces the one of the file
        assertNull(piano.tag)
        assertTrue(piano.prebake)
    }

    @Test
    fun vcslHasTheHitsAndTheInstrumentsUnderTheirOwnNames() {
        val library = loaded()
        val hihat = library.sound("hihat") as SampleBank
        val steinway = library.sound("steinway") as SampleBank

        assertFalse(hihat.pitched)
        assertEquals(
            "$CDN/VCSL/Idiophones/Struck%20Idiophones/Hi-Hat%20Cymbal/HiHat_Close_rr1_Mid.wav",
            hihat.groups.single().urls.first(),
        )
        assertTrue(steinway.pitched)
        assertEquals("A#0", steinway.groups.first().noteName) // the order of the file, not by pitch
        assertNull(hihat.tag)
        val vcsl = library.entries().filterIsInstance<SampleBank>().filter { it.tag == null && !it.name.startsWith("wt_") && it.name !in DIRT && it.name != "piano" }
        assertEquals(128, vcsl.size)
        assertEquals(55, vcsl.count { !it.pitched })
        assertEquals(73, vcsl.count { it.pitched })
    }

    @Test
    fun mridangamIsATaggedBankWithItsFiles() {
        val ka = loaded().sound("mridangam_ka") as SampleBank

        assertEquals(12, ka.count)
        assertEquals("drum-machines", ka.tag)
        assertTrue(ka.groups.single().urls.all { it.startsWith("$CDN/mrid/norm_sounds/ka/") })
    }

    @Test
    fun wavetablesAreWaveTableBanksAndNotSamples() {
        val library = loaded()
        val digital = library.sound("wt_digital") as WaveTableBank

        assertEquals(5, digital.groups.single().urls.size)
        assertEquals("$CDN/uzu-wavetables/wt_digital/wt_bad_day.wav", digital.groups.single().urls.first())
        assertEquals(2048, digital.frameLen)
        assertEquals(11, (library.sound("wt_vgame") as WaveTableBank).groups.single().urls.size)
        assertEquals(7, library.entries().count { it is WaveTableBank })
    }

    @Test
    fun theKeyBaseOfAMapIsNoSound() {
        val library = loaded()

        assertNull(library.sound("_base"))
        assertTrue(library.entries().none { it.name == "_base" })
    }

    @Test
    fun theDirtSamplesAreTheTenOfTheTunes() {
        val library = loaded()

        assertEquals(10, DIRT.size)
        assertEquals(3, (library.sound("casio") as SampleBank).count)
        assertEquals(21, (library.sound("num") as SampleBank).count)
        assertEquals(9, (library.sound("numbers") as SampleBank).count)
        val wind = (library.sound("wind") as SampleBank).groups.single().urls
        assertEquals(listOf("$CDN/Dirt-Samples/wind/000_wind1.wav", "$CDN/Dirt-Samples/wind/001_wind10.wav"), wind.take(2))
        assertEquals(95, DIRT.sumOf { (library.sound(it) as SampleBank).count })
    }

    @Test
    fun everySoundIsMarkedAsComingFromTheDefaultLibraryAndTheDrumMachinesAreTagged() {
        val entries = loaded().entries()

        assertEquals(983, entries.size) // 1 + 128 + 683 + 16 + 7 + 13 + 10, and the 125 gm_* soundfonts
        assertTrue(entries.all { it.prebake })
        for (name in listOf("piano", "hihat", "casio", "wt_vgame")) assertNull(entries.first { it.name == name }.tag, name)
        for (name in listOf("bd", "sd", "RolandTR808_hh", "mridangam_tha")) assertEquals("drum-machines", entries.first { it.name == name }.tag, name)
    }

    @Test
    fun aMapThatCannotBeReadLeavesTheOtherOnesAndIsReportedByName() {
        val store = FixtureStore(failing = mutableSetOf("vcsl.json"))
        val library = library(store)

        assertEquals(listOf("vcsl"), runBlocking { DefaultLibrary.load(library) })

        assertNull(library.sound("hihat"))
        assertNotNull(library.sound("bd"))
        assertNotNull(library.sound("tr909_bd")) // the aliases are made all the same
    }

    @Test
    fun withoutTheNetworkEverythingFailsButTheDirtSamplesWhichNeedNoMap() {
        val library = library(FixtureStore(failing = ALL_MAP_FILES.toMutableSet()))

        val failed = runBlocking { DefaultLibrary.load(library) }

        assertEquals(listOf("piano", "vcsl", "drum-machines", "uzu-drumkit", "wavetables", "mridangam", "aliases"), failed)
        assertEquals(135, library.entries().size)
        assertNotNull(library.sound("casio"))
        assertIs<BuiltinSound>(library.sound("bd")) // the engine's own bd, behind the registry, is what a program gets
    }

    @Test
    fun aPackThatFailedCanBeLoadedLaterAndTheAliasesFollow() {
        val store = FixtureStore(failing = mutableSetOf("tidal-drum-machines.json", "tidal-drum-machines-alias.json"))
        val library = library(store)
        assertEquals(listOf("drum-machines", "aliases"), runBlocking { DefaultLibrary.load(library) })
        assertNull(library.sound("rolandtr909_bd"))

        store.failing.clear()
        val failed = runBlocking { DefaultLibrary.load(library, only = setOf("drum-machines")) }

        assertEquals(emptyList(), failed)
        assertSame(library.sound("RolandTR909_bd"), library.sound("tr909_bd"))
        assertEquals(983, library.entries().size)
    }

    @Test
    fun loadingAgainRegistersTheBanksAnewAndTheAliasesPointToTheNewEntries() {
        val library = loaded()
        val before = library.sound("RolandTR909_bd")

        assertEquals(emptyList(), runBlocking { DefaultLibrary.load(library) })

        assertEquals(983, library.entries().size)
        assertSame(library.sound("RolandTR909_bd"), library.sound("tr909_bd"))
        assertFalse(before === library.sound("RolandTR909_bd")) // a new entry: the old one only lives on in whatever held it
    }

    @Test
    fun installLoadsInTheBackgroundReportsAndOnlyThenTellsTheLibraryItIsReady() {
        val gate = CountDownLatch(1)
        val library = library(FixtureStore(gate = gate), ready = false)
        var reported: List<String>? = null

        val job = DefaultLibrary.install(library, CoroutineScope(Dispatchers.IO)) { reported = it }
        assertFalse(library.awaitReady(100)) // the maps are not there yet
        gate.countDown()
        runBlocking { job.join() }

        assertTrue(library.awaitReady(1_000))
        assertEquals(emptyList(), reported)
        assertNotNull(library.sound("bd"))
    }

    @Test
    fun theLibraryIsReadyAlsoWhenLoadingWentWrong() {
        val library = library(FixtureStore(failing = ALL_MAP_FILES.toMutableSet()), ready = false)
        var reported: List<String>? = null

        runBlocking { DefaultLibrary.install(library, CoroutineScope(Dispatchers.IO)) { reported = it }.join() }

        assertTrue(library.awaitReady(1_000))
        assertEquals(7, reported!!.size)
    }

    private companion object {
        val DIRT = listOf("casio", "crow", "insect", "wind", "jazz", "metal", "east", "space", "numbers", "num")
    }
}
