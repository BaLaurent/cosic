package cosic.soundfont

import cosic.sound.RegistryLibrary
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class FontLoaderTest {
    private val chaos = fontFixture("0160_Chaos_sf2_file")
    private val font = "0000_JCLive_sf2_file"
    private val base = "https://felixroos.github.io/webaudiofontdata/sound"

    private fun library(store: FontStore, executor: Executor?): RegistryLibrary = RegistryLibrary(store, emptySet()).also { it.fontExecutor = executor }

    private val direct = Executor { it.run() }

    // ------------------------------------------------------------------------------------------------ the first ask

    @Test
    fun nothingIsFetchedUntilANoteNeedsAFont() {
        val store = FontStore(fallback = chaos)
        val library = library(store, QueuedExecutor())
        GmFonts.register(library)
        assertEquals(emptyList(), store.fetched)
    }

    @Test
    fun theFirstAskStartsTheLoadAndAnswersNullUntilFontAndZoneAreReady() {
        val store = FontStore(fallback = chaos)
        val jobs = QueuedExecutor()
        val library = library(store, jobs)
        assertNull(library.fontZone(font, 48.0))
        assertEquals(1, jobs.pending)
        assertNull(library.fontZone(font, 48.0), "still loading")
        assertEquals(1, jobs.pending, "no second job for the same font")
        assertEquals(emptyList(), store.fetched, "the job has not run")
        assertEquals(1, jobs.runAll())
        assertEquals(listOf("$base/$font.js"), store.fetched)
        val zone = assertNotNull(library.fontZone(font, 48.0))
        assertSame(store.datas.getValue("$font#0"), zone.data)
        assertEquals(6800.0, zone.originalPitch)
        assertEquals(50.0, zone.loopStart)
        assertEquals(262.0, zone.loopEnd)
        assertEquals(44_100.0, zone.sampleRate)
    }

    @Test
    fun theDecodedZoneIsTheOneThatCoversTheNote() {
        val store = FontStore(fallback = chaos)
        val library = library(store, direct)
        // zones: 0..54, 55..72, 73..83, 84..96, 97..107, 108..127
        for ((midi, index) in listOf(0.0 to 0, 48.0 to 0, 54.0 to 0, 55.0 to 0, 55.5 to 1, 60.0 to 1, 73.0 to 1, 73.5 to 2, 80.0 to 2, 90.0 to 3, 100.0 to 4, 127.0 to 5, 128.0 to 5)) {
            library.fontZone(font, midi)
            val zone = assertNotNull(library.fontZone(font, midi), "midi $midi")
            assertSame(store.datas.getValue("$font#$index"), zone.data, "midi $midi is zone $index")
        }
    }

    // ------------------------------------------------------------------------------------------------ what is fetched and decoded

    @Test
    fun aZoneIsDecodedOnceFromTheBytesOfItsPayloadAndOtherNotesOfItNeedNothing() {
        val store = FontStore(fallback = chaos)
        val library = library(store, direct)
        library.fontZone(font, 48.0)
        val first = assertNotNull(library.fontZone(font, 48.0))
        assertEquals(listOf("$font#0"), store.decoded)
        val expected = Base64.getDecoder().decode(FontPreset.parse(chaos).zones[0].file)
        assertContentEquals(expected, store.decodedBytes.getValue("$font#0"))
        for (midi in listOf(0.0, 10.0, 54.0, 55.0, 30.5)) assertSame(first, library.fontZone(font, midi), "midi $midi")
        assertEquals(listOf("$font#0"), store.decoded, "no decode for the same zone")
        assertEquals(1, store.fetched.size, "no fetch for the same font")
    }

    @Test
    fun aNoteOfAnotherZoneDecodesOnlyThatZoneAndFetchesNothing() {
        val store = FontStore(fallback = chaos)
        val library = library(store, direct)
        library.fontZone(font, 48.0)
        library.fontZone(font, 90.0)
        assertEquals(listOf("$font#0", "$font#3"), store.decoded)
        assertEquals(1, store.fetched.size)
        assertNotSame(library.fontZone(font, 48.0), library.fontZone(font, 90.0))
    }

    @Test
    fun anotherFontIsAnotherFetchAndEachFontKeepsItsOwnZones() {
        val store = FontStore(fallback = chaos)
        val library = library(store, direct)
        library.fontZone("0000_JCLive_sf2_file", 48.0)
        library.fontZone("0000_Aspirin_sf2_file", 48.0)
        assertEquals(listOf("$base/0000_JCLive_sf2_file.js", "$base/0000_Aspirin_sf2_file.js"), store.fetched)
        assertNotSame(library.fontZone("0000_JCLive_sf2_file", 48.0), library.fontZone("0000_Aspirin_sf2_file", 48.0))
    }

    @Test
    fun theSoundfontUrlIsReadWhenAFontIsFirstAskedFor() {
        val store = FontStore(fallback = chaos)
        val library = library(store, direct)
        library.fontZone("a", 48.0)
        library.setSoundfontUrl("https://example.test/fonts")
        library.fontZone("b", 48.0)
        library.fontZone("a", 60.0)
        assertEquals(listOf("$base/a.js", "https://example.test/fonts/b.js"), store.fetched, "a font that was loaded stays, as in Strudel")
    }

    // ------------------------------------------------------------------------------------------------ failures

    @Test
    fun aFailedFetchIsToldOnceAndNotRepeatedBeforeThirtySeconds() {
        collectingLog { log ->
            val store = FontStore(fallback = chaos).also { it.offline = true }
            val clock = AtomicLong(1_000_000_000_000L)
            val loader = FontLoader(store, { base }, { direct }, { clock.get() })
            assertNull(loader.zone(font, 48.0))
            assertEquals(1, store.fetched.size)
            assertEquals(listOf("error: could not load soundfont $font: offline: $base/$font.js"), log)
            for (i in 1..5) assertNull(loader.zone(font, 48.0))
            assertEquals(1, store.fetched.size, "waiting")
            clock.addAndGet(29_000_000_000L)
            assertNull(loader.zone(font, 48.0))
            assertEquals(1, store.fetched.size, "29 s are not 30")
            clock.addAndGet(2_000_000_000L)
            assertNull(loader.zone(font, 48.0))
            assertEquals(2, store.fetched.size, "tried again after 30 s")
            assertEquals(1, log.size, "the same failure is not told again")
            store.offline = false
            clock.addAndGet(31_000_000_000L)
            loader.zone(font, 48.0)
            assertNotNull(loader.zone(font, 48.0), "and works when the network is back")
            assertEquals(3, store.fetched.size)
        }
    }

    @Test
    fun aFileThatIsNoFontFailsLikeAFailedFetch() {
        collectingLog { log ->
            val store = FontStore(fallback = "<html>404 Not Found</html>")
            val library = library(store, direct)
            assertNull(library.fontZone(font, 48.0))
            assertNull(library.fontZone(font, 48.0))
            assertEquals(1, store.fetched.size)
            assertEquals(1, log.size)
            assertTrue(log[0].startsWith("error: could not load soundfont $font: not a soundfont"), log[0])
        }
    }

    @Test
    fun aNoteWithoutAZoneIsToldOnceAndNeverFetchesAgain() {
        collectingLog { log ->
            val store = FontStore(fallback = chaos)
            val library = library(store, direct)
            assertNull(library.fontZone(font, 128.5), "the font is read, and has no zone for the note")
            assertNull(library.fontZone(font, 128.5))
            assertNull(library.fontZone(font, -3.0))
            assertEquals(1, store.fetched.size)
            assertEquals(emptyList(), store.decoded)
            assertEquals(listOf("error: no soundfont zone found for preset $font pitch 128.5", "error: no soundfont zone found for preset $font pitch -3"), log)
            library.fontZone(font, 48.0)
            assertNotNull(library.fontZone(font, 48.0), "other notes still work")
        }
    }

    @Test
    fun aZoneThatCannotBeDecodedIsToldOnceAndOnlyThatZoneIsRetriedAfterThirtySeconds() {
        collectingLog { log ->
            val store = FontStore(fallback = chaos).also { it.failDecode = setOf("$font#1") }
            val clock = AtomicLong(0L)
            val loader = FontLoader(store, { base }, { direct }, { clock.get() })
            loader.zone(font, 48.0)
            assertNotNull(loader.zone(font, 48.0))
            assertNull(loader.zone(font, 60.0))
            assertNull(loader.zone(font, 60.0))
            assertEquals(listOf("$font#0", "$font#1"), store.decoded)
            assertEquals(listOf("error: could not load a zone of soundfont $font: cannot decode $font#1"), log)
            assertNotNull(loader.zone(font, 48.0), "the other zones play")
            clock.addAndGet(30_000_000_001L)
            store.failDecode = emptySet()
            assertNull(loader.zone(font, 60.0))
            assertEquals(listOf("$font#0", "$font#1", "$font#1"), store.decoded)
            assertNotNull(loader.zone(font, 60.0))
        }
    }

    @Test
    fun aZoneWithoutAnAudioPayloadHasNoBuffer() {
        collectingLog { log ->
            // the zones of the edge font at 80..89 have a `sample` (the raw branch of getBuffer returns nothing) and at 90..99 neither
            val store = FontStore(fallback = fontFixture("edges_sf2_file"))
            val library = library(store, direct)
            library.fontZone(font, 85.0)
            assertNull(library.fontZone(font, 85.0))
            library.fontZone(font, 95.0)
            assertNull(library.fontZone(font, 95.0))
            assertEquals(emptyList(), store.decoded)
            assertEquals(
                listOf("error: could not load a zone of soundfont $font: no soundfont buffer found for preset $font, pitch: 85", "error: could not load a zone of soundfont $font: no soundfont buffer found for preset $font, pitch: 95"),
                log,
            )
            assertNull(library.fontZone(font, 25.0), "the zone is found, the decode starts")
            assertNotNull(library.fontZone(font, 25.0))
        }
    }

    @Test
    fun anExecutorThatRefusesTheJobIsAFailureNotACrash() {
        collectingLog { log ->
            val store = FontStore(fallback = chaos)
            val library = library(store, Executor { throw java.util.concurrent.RejectedExecutionException("shut down") })
            assertNull(library.fontZone(font, 48.0))
            assertEquals(1, log.size)
            assertTrue("shut down" in log[0], log[0])
            assertEquals(emptyList(), store.fetched)
        }
    }

    // ------------------------------------------------------------------------------------------------ what is kept

    @Test
    fun theZonesUsedLongestAgoAreDroppedWhenTheDecodedFramesAreOverTheBudget() {
        // each decoded zone of the fake store is 4410 frames: the budget keeps two
        val store = FontStore(fallback = chaos)
        val loader = FontLoader(store, { base }, { direct }, maxDecodedFrames = 9_000L)
        for (midi in listOf(48.0, 60.0, 80.0)) {
            loader.zone(font, midi)
            assertNotNull(loader.zone(font, midi))
        }
        assertEquals(listOf("$font#0", "$font#1", "$font#2"), store.decoded)
        assertNull(loader.zone(font, 48.0), "zone 0 was used first and is gone; asking decodes it again")
        assertNotNull(loader.zone(font, 80.0), "zone 2, the newest, stays")
        assertEquals(listOf("$font#0", "$font#1", "$font#2", "$font#0"), store.decoded)
    }

    @Test
    fun theFontsUsedLongestAgoAreDroppedWhenTheirPayloadsAreOverTheBudget() {
        val store = FontStore(fallback = chaos)
        val weight = FontPreset.parse(chaos).weight.toLong()
        val loader = FontLoader(store, { base }, { direct }, maxPresetChars = weight * 2 + 1)
        for (name in listOf("a", "b", "c")) {
            loader.zone(name, 48.0)
            assertNotNull(loader.zone(name, 48.0))
        }
        assertEquals(listOf("$base/a.js", "$base/b.js", "$base/c.js"), store.fetched)
        assertNotNull(loader.zone("c", 48.0))
        assertNotNull(loader.zone("b", 48.0))
        assertEquals(3, store.fetched.size, "b and c are still there")
        assertNull(loader.zone("a", 48.0), "a was used first and is gone: fetched again")
        assertEquals(4, store.fetched.size)
    }

    // ------------------------------------------------------------------------------------------------ threads

    @Test
    fun manyThreadsAskingAtOnceCauseOneFetchAndOneDecodePerFontAndZone() {
        val store = FontStore(fallback = chaos).also { it.latencyMillis = 20 }
        val pool = Executors.newFixedThreadPool(2)
        val library = library(store, pool)
        val callers = 8
        val start = CountDownLatch(1)
        val done = CountDownLatch(callers)
        val ready = AtomicInteger()
        repeat(callers) { caller ->
            Thread {
                start.await()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (System.nanoTime() < deadline) {
                    val a = library.fontZone(font, 48.0)
                    val b = library.fontZone(font, 90.0 + caller % 2)
                    if (a != null && b != null) {
                        ready.incrementAndGet()
                        break
                    }
                    Thread.sleep(1)
                }
                done.countDown()
            }.start()
        }
        start.countDown()
        val finished = done.await(20, TimeUnit.SECONDS)
        pool.shutdownNow()
        assertTrue(finished)
        assertEquals(callers, ready.get())
        assertEquals(1, store.fetched.size)
        assertEquals(listOf("$font#0", "$font#3"), store.decoded.sorted())
        assertFalse(store.decoded.size > 2)
    }
}
