package cosic.sound

import cosic.BankGroup
import cosic.BuiltinSound
import cosic.SampleBank
import cosic.SampleData
import cosic.SampleMapException
import cosic.SampleMapOptions
import cosic.SampleStore
import cosic.WaveTableBank
import cosic.pattern.logger
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RegistryLibraryTest {
    private class TableStore(
        val texts: Map<String, String> = emptyMap(),
        val samples: Map<String, SampleData> = emptyMap(),
    ) : SampleStore {
        val fetched = ArrayList<String>()
        val asked = ArrayList<String>()

        override fun fetchText(url: String): String {
            fetched += url
            return texts[url] ?: throw IOException("404 $url")
        }

        override fun sample(url: String): SampleData? {
            asked += url
            return samples[url]
        }

        override fun awaitSample(url: String): SampleData = samples[url] ?: throw IOException("no sample at $url")

        override fun decode(bytes: ByteArray, nameHint: String): SampleData = throw IOException("no decoder")
    }

    private fun data(frames: Int, channels: Int = 1, rate: Int = 48_000) = SampleData(FloatArray(frames * channels) { it.toFloat() }, rate, channels)

    private fun library(store: SampleStore = TableStore(), vararg builtins: String, ready: Boolean = true) = RegistryLibrary(store, builtins.toSet(), ready)

    private fun SampleBank.urls() = groups.flatMap { it.urls }

    // ------------------------------------------------------------------------------------ samples(url)

    @Test
    fun aGithubMapIsFetchedFromItsStrudelJsonAndItsBanksAreRegistered() {
        val store = TableStore(
            texts = mapOf(
                "https://raw.githubusercontent.com/tidalcycles/dirt-samples/main/strudel.json" to
                    """{"_base": "https://raw.githubusercontent.com/tidalcycles/Dirt-Samples/master/", "bd": ["bd/BT0A0A7.wav", "bd/BT0A0D0.wav"], "casio": ["casio/high.wav"]}""",
            ),
        )
        val library = library(store)
        library.loadSampleMap("github:tidalcycles/dirt-samples")
        val bd = assertIs<SampleBank>(library.sound("BD"))
        assertEquals(
            listOf(
                "https://raw.githubusercontent.com/tidalcycles/Dirt-Samples/master/bd/BT0A0A7.wav",
                "https://raw.githubusercontent.com/tidalcycles/Dirt-Samples/master/bd/BT0A0D0.wav",
            ),
            bd.urls(),
        )
        assertNotNull(library.sound("casio"))
        assertNull(library.sound("_base"), "the base is not a sound")
        assertEquals(1, store.fetched.size)
    }

    @Test
    fun withoutABaseTheDirectoryOfTheMapIsTheBaseWithNoSeparator() {
        val store = TableStore(texts = mapOf("https://x/maps/strudel.json" to """{"bd": ["/bd/a.wav"], "sd": ["sd.wav"]}"""))
        val library = library(store)
        library.loadSampleMap("https://x/maps/strudel.json")
        assertEquals(listOf("https://x/maps/bd/a.wav"), assertIs<SampleBank>(library.sound("bd")).urls())
        assertEquals(listOf("https://x/mapssd.wav"), assertIs<SampleBank>(library.sound("sd")).urls(), "base + file, as Strudel concatenates")
    }

    @Test
    fun anExplicitBaseBeatsTheOneOfTheMapUnlessItIsEmpty() {
        val store = TableStore(texts = mapOf("https://x/m.json" to """{"_base": "B/", "a": ["a.wav"]}"""))
        val library = library(store)
        library.loadSampleMap("https://x/m.json", "ME/")
        assertEquals(listOf("ME/a.wav"), assertIs<SampleBank>(library.sound("a")).urls())
        library.loadSampleMap("https://x/m.json", "")
        assertEquals(listOf("B/a.wav"), assertIs<SampleBank>(library.sound("a")).urls())
        library.loadSampleMap("https://x/m.json", null)
        assertEquals(listOf("B/a.wav"), assertIs<SampleBank>(library.sound("a")).urls())
    }

    @Test
    fun aMapThatCannotBeFetchedOrParsedIsAnErrorLoadingItsUrl() {
        val store = TableStore(texts = mapOf("https://x/bad.json" to "{nope", "https://x/list.json" to "[1]"))
        val library = library(store)
        assertEquals("error loading \"https://x/missing.json\"", assertFailsWith<SampleMapException> { library.loadSampleMap("https://x/missing.json") }.message)
        assertEquals("error loading \"https://x/bad.json\"", assertFailsWith<SampleMapException> { library.loadSampleMap("https://x/bad.json") }.message)
        assertEquals("error loading \"https://x/list.json\"", assertFailsWith<SampleMapException> { library.loadSampleMap("https://x/list.json") }.message)
        assertEquals(
            "error loading \"https://raw.githubusercontent.com/u/samples/main/strudel.json\"",
            assertFailsWith<SampleMapException> { library.loadSampleMap("github:u") }.message,
            "the message names the url the pseudo url stands for",
        )
        assertEquals(emptyList(), library.entries())
    }

    @Test
    fun aMapGivenAsAnObjectUsesItsBaseOrTheOneGiven() {
        val library = library()
        library.loadSampleMap(mapOf("_base" to "B/", "a" to listOf("a.wav")))
        assertEquals(listOf("B/a.wav"), assertIs<SampleBank>(library.sound("a")).urls())
        library.loadSampleMap(mapOf("_base" to "B/", "a" to listOf("a.wav")), "X/")
        assertEquals(listOf("X/a.wav"), assertIs<SampleBank>(library.sound("a")).urls())
        library.loadSampleMap(mapOf("a" to "a.wav"))
        assertEquals(listOf("a.wav"), assertIs<SampleBank>(library.sound("a")).urls())
    }

    @Test
    fun theOptionsMarkTheBanksAndALaterMapReplacesTheWholeEntry() {
        val library = library()
        library.loadSampleMap(mapOf("kit" to listOf("a.wav", "b.wav")), null, SampleMapOptions(prebake = true, tag = "drum-machines"))
        val first = assertIs<SampleBank>(library.sound("kit"))
        assertTrue(first.prebake)
        assertEquals("drum-machines", first.tag)
        library.loadSampleMap(mapOf("kit" to listOf("c.wav")))
        val second = assertIs<SampleBank>(library.sound("kit"))
        assertEquals(listOf("c.wav"), second.urls(), "no merge: the registration replaces the sound")
        assertFalse(second.prebake)
    }

    @Test
    fun theBanksBeforeAWrongOneStayRegistered() {
        val library = library()
        assertFailsWith<SampleMapException> { library.loadSampleMap(linkedMapOf("a" to "a.wav", "b" to 3.0, "c" to "c.wav")) }
        assertNotNull(library.sound("a"))
        assertNull(library.sound("c"))
    }

    @Test
    fun aWavetableKeyRegistersAWavetableBankWhoseFramesAreCutFromChannelZero() {
        val file = SampleData(FloatArray(2 * 5000) { if (it % 2 == 0) (it / 2).toFloat() else -1f }, 44_100, channels = 2)
        val store = TableStore(samples = mapOf("t/a.wav" to file))
        val library = library(store)
        library.loadSampleMap(mapOf("wt_a" to listOf("t/a.wav")))
        val bank = assertIs<WaveTableBank>(library.sound("WT_A"))
        assertEquals(2048, bank.frameLen)
        val table = assertNotNull(library.wavetable(bank, 0, 0))
        assertEquals(2, table.frames.size, "floor(5000 / 2048)")
        assertEquals(2048, table.frames[0].size)
        assertEquals(0f, table.frames[0][0])
        assertEquals(2048f, table.frames[1][0], "channel 0 only")
        assertEquals(2047f, table.frames[0][2047])
        assertEquals(44_100, table.sampleRate)
        assertSame(table, library.wavetable(bank, 0, 0), "cut once")
    }

    @Test
    fun aWavetableShorterThanAFrameIsOneShortFrameAndAnUnloadedOneIsNull() {
        val store = TableStore(samples = mapOf("short.wav" to data(600)))
        val library = library(store)
        library.loadSampleMap(mapOf("wt_s" to listOf("short.wav", "later.wav")))
        val bank = assertIs<WaveTableBank>(library.sound("wt_s"))
        val table = assertNotNull(library.wavetable(bank, 0, 0))
        assertEquals(1, table.frames.size)
        assertEquals(600, table.frames[0].size)
        assertNull(library.wavetable(bank, 0, 1), "not loaded yet")
        assertNull(library.wavetable(bank, 0, 5))
        assertNull(library.wavetable(bank, 3, 0))
    }

    // -------------------------------------------------------------------------------- samples and ir

    @Test
    fun aSampleIsTheStoresAndAskingStartsItsLoad() {
        val one = data(10)
        val store = TableStore(samples = mapOf("a.wav" to one))
        val library = library(store)
        library.loadSampleMap(mapOf("kit" to listOf("a.wav", "b.wav")))
        val bank = assertIs<SampleBank>(library.sound("kit"))
        assertSame(one, library.sample(bank, 0, 0))
        assertNull(library.sample(bank, 0, 1))
        assertNull(library.sample(bank, 0, 2), "no such file")
        assertNull(library.sample(bank, 1, 0), "no such group")
        assertEquals(listOf("a.wav", "b.wav"), store.asked)
    }

    @Test
    fun theIrSampleWrapsByTheNumberOfFilesOfAListAndByTheNumberOfKeysOfAPitchedBank() {
        val files = List(3) { data(it + 1) }
        val store = TableStore(samples = mapOf("l0" to files[0], "l1" to files[1], "l2" to files[2], "p0" to files[0], "p1" to files[1], "p2" to files[2]))
        val library = library(store)
        library.loadSampleMap(mapOf("room" to listOf("l0", "l1", "l2"), "hall" to mapOf("c2" to listOf("p0", "p1"), "c4" to "p2")))
        assertSame(files[1], library.irSample("room", 1))
        assertSame(files[1], library.irSample("ROOM", 4))
        assertSame(files[0], library.irSample("room", 0))
        // pitched: the files in one flat list [p0, p1, p2], the index wrapped by the 2 keys
        assertSame(files[0], library.irSample("hall", 0))
        assertSame(files[1], library.irSample("hall", 1))
        assertSame(files[0], library.irSample("hall", 2))
        assertSame(files[1], library.irSample("hall", 3))
    }

    @Test
    fun noIrSampleForAnUnknownSoundABuiltInANegativeIndexOrAnUnloadedFile() {
        val library = library(TableStore(), "sine")
        library.loadSampleMap(mapOf("room" to listOf("a.wav")))
        assertNull(library.irSample("nope", 1))
        assertNull(library.irSample("sine", 1))
        assertNull(library.irSample("room", -1))
        assertNull(library.irSample("room", 0), "not loaded yet")
    }

    // ------------------------------------------------------------------------------- aliases and duration

    @Test
    fun soundAliasRegistersTheAliasAndReportsAnUnknownOriginal() {
        val library = library()
        library.loadSampleMap(mapOf("bd" to listOf("a.wav")))
        val log = ArrayList<String>()
        val saved = logger
        logger = { message, _, _ -> log += message }
        try {
            assertTrue(library.soundAlias("bd", "kick"))
            assertSame(library.sound("bd"), library.sound("KICK"))
            assertFalse(library.soundAlias("nope", "x"))
        } finally {
            logger = saved
        }
        assertEquals(listOf("soundAlias: original sound not found"), log)
        assertNull(library.sound("x"))
    }

    @Test
    fun aliasBankWorksFromAMapFromTwoArgumentsAndFromAUrl() {
        val store = TableStore(texts = mapOf("https://x/alias.json" to """{"RolandTR808": "TR808", "Linn": ["l1", "l2"]}"""))
        val library = library(store)
        library.loadSampleMap(mapOf("RolandTR808_bd" to listOf("a.wav"), "Linn_sd" to listOf("b.wav"), "Acme_hh" to listOf("c.wav")))
        library.aliasBank("https://x/alias.json")
        assertSame(library.sound("rolandtr808_bd"), library.sound("tr808_bd"))
        assertSame(library.sound("linn_sd"), library.sound("l1_sd"))
        assertSame(library.sound("linn_sd"), library.sound("l2_sd"))
        library.aliasBank("Acme", "ac")
        assertSame(library.sound("acme_hh"), library.sound("ac_hh"))
        library.aliasBank("Acme", listOf("a1", "a2"))
        assertSame(library.sound("acme_hh"), library.sound("a2_hh"))
        library.aliasBank(mapOf("Acme" to "ac2"))
        assertSame(library.sound("acme_hh"), library.sound("ac2_hh"))
    }

    @Test
    fun anAliasMapThatCannotBeLoadedIsAnErrorLoadingItsUrl() {
        val library = library(TableStore(texts = mapOf("https://x/bad" to "[")))
        assertEquals("error loading \"https://x/nope\"", assertFailsWith<SampleMapException> { library.aliasBank("https://x/nope") }.message)
        assertEquals("error loading \"https://x/bad\"", assertFailsWith<SampleMapException> { library.aliasBank("https://x/bad") }.message)
    }

    @Test
    fun durationIsTheLengthOfTheChosenSampleAndNeedsTheNameAsRegistered() {
        val store = TableStore(samples = mapOf("a.wav" to data(24_000), "b.wav" to data(48_000), "c.wav" to data(12_000)))
        val library = library(store, "sine")
        library.loadSampleMap(mapOf("sax" to listOf("a.wav", "b.wav"), "Mixed" to listOf("c.wav"), "keys" to mapOf("c1" to "a.wav", "c3" to "b.wav")))
        assertEquals(0.5, library.duration("sax"))
        assertEquals(1.0, library.duration("sax", 1.0))
        assertEquals(0.5, library.duration("sax", 2.0), "n wraps")
        assertEquals(0.5, library.duration("keys"), "a pitched bank is read at C2: c1 and c3 are both 12 away and the first key wins")
    }

    @Test
    fun durationDoesNotLowerCaseAndFailsForWhatHasNoSamples() {
        val store = TableStore(samples = mapOf("a.wav" to data(24_000)))
        val library = library(store, "sine")
        library.loadSampleMap(mapOf("Sax" to listOf("a.wav")))
        assertFailsWith<IllegalArgumentException> { library.duration("Sax") }
        assertFailsWith<IllegalArgumentException> { library.duration("nope") }
        assertFailsWith<IllegalArgumentException> { library.duration("sine") }
        library.loadSampleMap(mapOf("late" to listOf("later.wav")))
        assertFailsWith<IOException> { library.duration("late") }
    }

    // ------------------------------------------------------------------------ the host's own registrations

    @Test
    fun aSampleSourceIsABankOfTheFilesAndAWtKeyMakesAWavetable() {
        val library = library()
        library.registerSampleSource("My Kit", listOf("a.wav", "b.wav"))
        assertEquals(listOf("a.wav", "b.wav"), assertIs<SampleBank>(library.sound("my_kit")).urls())
        library.registerSampleSource("wt_mine", listOf("w.wav"), SampleMapOptions(prebake = true))
        assertTrue(assertIs<WaveTableBank>(library.sound("wt_mine")).prebake)
        library.registerSampleSource("WT_up", listOf("w.wav"))
        assertIs<SampleBank>(library.sound("wt_up"), "case-sensitive on the key as written")
    }

    @Test
    fun registerReplacesAndUnregisterRemoves() {
        val library = library(TableStore(), "sine")
        val mine = SampleBank("Sine", listOf(BankGroup(null, null, listOf("s.wav"))), null, false)
        library.register(mine)
        assertSame(mine, library.sound("sine"))
        assertTrue(library.unregister("SINE"))
        assertIs<BuiltinSound>(library.sound("sine"), "the built-in is back")
        assertFalse(library.unregister("sine"))
    }

    @Test
    fun theRevisionChangesWhenTheRegistryDoes() {
        val library = library()
        val before = library.revision
        library.registerSampleSource("a", listOf("a.wav"))
        assertTrue(library.revision != before)
        val after = library.revision
        library.sound("a")
        library.entries()
        assertEquals(after, library.revision, "reading changes nothing")
    }

    @Test
    fun awaitReadyWaitsForMarkReadyWhenTheLibraryIsNotReadyYet() {
        val waiting = library(ready = false)
        assertFalse(waiting.awaitReady(20))
        assertFalse(waiting.awaitReady(0))
        waiting.markReady()
        assertTrue(waiting.awaitReady(0))
        assertTrue(library().awaitReady(0), "a library with nothing to install is ready")
    }

    @Test
    fun anotherThreadCanReleaseTheWait() {
        val waiting = library(ready = false)
        Thread { Thread.sleep(30); waiting.markReady() }.start()
        assertTrue(waiting.awaitReady(5_000))
    }

    @Test
    fun theSoundfontUrlStartsAtTheFontsHostAndCanBeSet() {
        val library = library()
        assertEquals("https://felixroos.github.io/webaudiofontdata/sound", library.soundfontUrl)
        library.setSoundfontUrl("http://localhost:8000/fonts")
        assertEquals("http://localhost:8000/fonts", library.soundfontUrl)
    }

    @Test
    fun anAliasedBankKeepsListingTheEntryOnce() {
        val library = library()
        library.loadSampleMap(mapOf("Roland_bd" to listOf("a.wav"), "Roland_sd" to listOf("b.wav")))
        library.aliasBank(mapOf("Roland" to "R"))
        assertContentEquals(listOf("Roland_bd", "Roland_sd"), library.entries().map { it.name })
    }
}
