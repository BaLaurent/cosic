package com.octopusman.cosic.audio

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import cosic.SampleData
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** A web server on the loopback address that records what it is asked. */
private class TestServer {
    class Request(val path: String, val headers: Map<String, String>)

    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0).apply {
        executor = Executors.newFixedThreadPool(16) { Thread(it).apply { isDaemon = true } }
    }
    val requests = CopyOnWriteArrayList<Request>()

    init {
        server.start()
    }

    fun url(path: String) = "http://127.0.0.1:${server.address.port}$path"

    fun hits(path: String) = requests.count { it.path == path }

    fun handle(path: String, handler: (HttpExchange) -> Unit) {
        server.createContext(path) { exchange ->
            requests.add(Request(exchange.requestURI.rawPath, exchange.requestHeaders.entries.associate { it.key.lowercase() to it.value.joinToString() }))
            try {
                handler(exchange)
            } finally {
                try {
                    exchange.close()
                } catch (_: IOException) {
                    // a response that is shorter than it said it would be: the tests make those on purpose
                }
            }
        }
    }

    fun reply(path: String, body: ByteArray, etag: String? = null, status: Int = 200) = handle(path) { exchange ->
        if (etag != null) exchange.responseHeaders.add("ETag", etag)
        exchange.sendResponseHeaders(status, if (body.isEmpty()) -1 else body.size.toLong())
        if (body.isNotEmpty()) exchange.responseBody.write(body)
    }

    fun reply(path: String, text: String, etag: String? = null, status: Int = 200) = reply(path, text.toByteArray(), etag, status)

    fun redirect(path: String, location: String, status: Int = 302) = handle(path) { exchange ->
        exchange.responseHeaders.add("Location", location)
        exchange.sendResponseHeaders(status, -1)
    }

    fun stop() = server.stop(0)
}

class HttpSampleStoreTest {
    private lateinit var dir: File
    private lateinit var server: TestServer
    private val stores = ArrayList<HttpSampleStore>()
    private val clock = AtomicLong(0)
    private val decoded = AtomicInteger()

    @BeforeTest
    fun setUp() {
        dir = Files.createTempDirectory("sample-store").toFile()
        server = TestServer()
    }

    @AfterTest
    fun tearDown() {
        stores.forEach { it.close() }
        server.stop()
        dir.deleteRecursively()
    }

    /** What the fake decoder makes of a file: one float per byte (a byte of 127 is full scale), 44.1 kHz mono; the bytes `BAD` are no audio. */
    private fun decoder(bytes: ByteArray, hint: String): SampleData {
        decoded.incrementAndGet()
        if (String(bytes) == "BAD") throw IOException("cannot decode $hint")
        return SampleData(FloatArray(bytes.size) { bytes[it] / 127f }, 44_100)
    }

    private fun config(
        decodedBudget: Long = 1_000_000,
        maxText: Long = 20L shl 20,
        maxFile: Long = 256L shl 20,
        awaitMs: Long = 10_000,
    ) = StoreConfig(
        mapCacheDir = File(dir, "maps"),
        sampleCacheDir = File(dir, "samples"),
        userAgent = "CosicTest/1.0 (Android)",
        decodedBudgetFloats = decodedBudget,
        connectTimeoutMs = 2_000,
        readTimeoutMs = 3_000,
        revalidateConnectTimeoutMs = 1_000,
        revalidateReadTimeoutMs = 1_500,
        maxTextBytes = maxText,
        maxFileBytes = maxFile,
        awaitTimeoutMs = awaitMs,
        monotonicMillis = { clock.get() },
    )

    private fun store(
        config: StoreConfig = config(),
        openContent: (String) -> java.io.InputStream = { throw IOException("no document $it") },
    ) = HttpSampleStore(config, ::decoder, openContent).also { stores.add(it) }

    /** Polls [block] until it gives something. */
    private fun <T : Any> eventually(timeoutMs: Long = 5_000, block: () -> T?): T {
        val end = System.nanoTime() + timeoutMs * 1_000_000
        while (true) {
            block()?.let { return it }
            check(System.nanoTime() < end) { "gave up after $timeoutMs ms" }
            Thread.sleep(10)
        }
    }

    private fun HttpSampleStore.loaded(url: String): SampleData = eventually { sample(url) }

    private fun HttpSampleStore.settled() = eventually { true.takeIf { loading.value == 0 } }

    // -- fetchText ---------------------------------------------------------------------------------------------------------------

    @Test
    fun aMapIsReadAsTextAndTheServerSeesWhoAsksForIt() {
        server.reply("/map.json", "{\"bd\": [\"a.wav\"]}")

        assertEquals("{\"bd\": [\"a.wav\"]}", store().fetchText(server.url("/map.json")))
        assertEquals("CosicTest/1.0 (Android)", server.requests.single().headers["user-agent"])
    }

    @Test
    fun theByteOrderMarkOfAMapIsDropped() {
        server.reply("/map.json", "\uFEFF{}")

        assertEquals("{}", store().fetchText(server.url("/map.json")))
    }

    @Test
    fun aMapIsAskedForAgainWithItsEtagAndANotModifiedAnswerKeepsTheCopy() {
        val etag = AtomicReference("\"v1\"")
        val body = AtomicReference("first")
        server.handle("/map.json") { exchange ->
            if (exchange.requestHeaders.getFirst("If-None-Match") == etag.get()) {
                exchange.sendResponseHeaders(304, -1)
            } else {
                val text = body.get().toByteArray()
                exchange.responseHeaders.add("ETag", etag.get())
                exchange.sendResponseHeaders(200, text.size.toLong())
                exchange.responseBody.write(text)
            }
        }
        val store = store()
        val url = server.url("/map.json")

        assertEquals("first", store.fetchText(url))
        assertEquals("first", store.fetchText(url)) // 304: the copy on disk
        assertEquals(listOf(null, "\"v1\""), server.requests.map { it.headers["if-none-match"] })

        etag.set("\"v2\"")
        body.set("second")
        assertEquals("second", store.fetchText(url)) // the etag no longer matches: the new text, kept
        assertEquals("second", store.fetchText(url))
        assertEquals("\"v2\"", server.requests.last().headers["if-none-match"])
    }

    @Test
    fun aMapOfAnEarlierRunIsUsedWhenTheNetworkIsDown() {
        server.reply("/map.json", "{\"a\": 1}", etag = "\"x\"")
        val url = server.url("/map.json")
        store().fetchText(url)
        server.stop()

        assertEquals("{\"a\": 1}", store().fetchText(url)) // a new store: the copy is on disk
    }

    @Test
    fun aMapThatWasNeverLoadedAndCannotBeIsAnError() {
        server.stop()

        assertFailsWith<IOException> { store().fetchText(server.url("/map.json")) }
    }

    @Test
    fun aMissingMapIsAnErrorUnlessACopyIsThere() {
        val store = store()
        assertFailsWith<IOException> { store.fetchText(server.url("/nothing.json")) }

        val there = AtomicBoolean(true)
        server.handle("/gone.json") { exchange ->
            if (there.get()) {
                exchange.sendResponseHeaders(200, 4)
                exchange.responseBody.write("kept".toByteArray())
            } else {
                exchange.sendResponseHeaders(404, -1)
            }
        }
        store.fetchText(server.url("/gone.json"))
        there.set(false)
        assertEquals("kept", store.fetchText(server.url("/gone.json")))
    }

    @Test
    fun aMapIsFollowedThroughRedirectsAsTheyAreMade() {
        server.reply("/final.json", "arrived")
        server.redirect("/moved", "/final.json", 301)
        server.redirect("/found", "/moved", 302)
        server.redirect("/seeother", "final.json", 303)
        server.redirect("/temporary", server.url("/final.json"), 307)
        server.redirect("/permanent", "/final.json", 308)
        val store = store()

        for (start in listOf("/moved", "/found", "/seeother", "/temporary", "/permanent")) assertEquals("arrived", store.fetchText(server.url(start)), start)
    }

    @Test
    fun fiveRedirectsAreFollowedAndASixthIsOneTooMany() {
        for (i in 0..5) server.redirect("/r$i", "/r${i + 1}")
        server.reply("/r6", "end")
        val store = store()

        assertEquals("end", store.fetchText(server.url("/r1"))) // r1 to r6 is five redirects
        assertFailsWith<IOException> { store.fetchText(server.url("/r0")) }.also { assertTrue("redirects" in it.message!!, it.message) }
    }

    @Test
    fun aRedirectToPlainHttpOnAPublicHostIsRefusedLikeAnyOtherUrlOfThatKind() {
        server.redirect("/away", "http://example.com/x.json")

        val error = assertFailsWith<IOException> { store().fetchText(server.url("/away")) }

        assertTrue("cleartext" in error.message!!, error.message)
    }

    @Test
    fun plainHttpToAPublicHostIsRefusedWithoutAskingTheNetwork() {
        val store = store()

        assertTrue("cleartext" in assertFailsWith<IOException> { store.fetchText("http://example.com/x.json") }.message!!)
        assertTrue("cleartext" in assertFailsWith<IOException> { store.awaitSample("http://example.com/x.wav") }.message!!)
        assertNull(store.sample("http://example.com/y.wav"))
        store.settled()
        assertTrue(File(dir, "samples").list().isNullOrEmpty())
    }

    @Test
    fun aMapOverTheLimitIsAnErrorWhetherItsLengthIsGivenOrNot() {
        server.reply("/fixed.json", ByteArray(2_000) { 'a'.code.toByte() })
        server.handle("/chunked.json") { exchange ->
            exchange.sendResponseHeaders(200, 0) // no length: chunked
            exchange.responseBody.write(ByteArray(2_000) { 'a'.code.toByte() })
        }
        val store = store(config(maxText = 1_000))

        assertFailsWith<IOException> { store.fetchText(server.url("/fixed.json")) }
        assertFailsWith<IOException> { store.fetchText(server.url("/chunked.json")) }
        server.reply("/small.json", ByteArray(1_000) { 'a'.code.toByte() })
        assertEquals(1_000, store.fetchText(server.url("/small.json")).length) // exactly the limit is fine
    }

    @Test
    fun theUrlOfAMapIsNormalizedLikeABrowserDoesIt() {
        server.reply("/a", "ok")

        assertEquals("ok", store().fetchText(server.url("/a b/c#d.json")))
        assertEquals("/a%20b/c%23d.json", server.requests.single().path)
    }

    // -- sample / awaitSample ----------------------------------------------------------------------------------------------------

    @Test
    fun aSampleIsNullAtFirstThenDecodedAndTheFileIsKeptOnDisk() {
        server.reply("/s/kick.wav", byteArrayOf(10, 20, 30))
        val store = store()
        val url = server.url("/s/kick.wav")

        assertNull(store.sample(url)) // never blocks: this starts the load
        val data = store.loaded(url)

        assertContentEquals(floatArrayOf(10 / 127f, 20 / 127f, 30 / 127f), data.samples)
        assertEquals(44_100, data.sampleRate)
        assertEquals(1, server.hits("/s/kick.wav"))
        assertEquals(1, File(dir, "samples").list()!!.size)
        assertEquals("CosicTest/1.0 (Android)", server.requests.single().headers["user-agent"])
    }

    @Test
    fun aSampleThatIsDecodedIsHandedOutAgainAsTheSameObject() {
        server.reply("/k.wav", byteArrayOf(1, 2))
        val store = store()
        val first = store.loaded(server.url("/k.wav"))

        assertTrue(first === store.sample(server.url("/k.wav")))
        assertEquals(1, decoded.get())
    }

    @Test
    fun theFileOfAnEarlierRunIsDecodedFromDiskAndNotFetchedAgain() {
        server.reply("/k.wav", byteArrayOf(5, 6))
        val url = server.url("/k.wav")
        store().loaded(url)

        val second = store()
        val data = second.loaded(url)

        assertEquals(2, data.samples.size)
        assertEquals(1, server.hits("/k.wav"))
        assertEquals(2, decoded.get())
    }

    @Test
    fun awaitSampleWaitsForTheLoadAndStartsOneItselfIfNeedBe() {
        server.reply("/k.wav", byteArrayOf(9))
        val store = store()

        assertEquals(1, store.awaitSample(server.url("/k.wav")).samples.size)
        assertEquals(1, store.awaitSample(server.url("/k.wav")).samples.size)
        assertEquals(1, server.hits("/k.wav"))
    }

    @Test
    fun aSampleThatIsMissingIsAnErrorForAwaitSample() {
        val error = assertFailsWith<IOException> { store().awaitSample(server.url("/nothing.wav")) }

        assertTrue("404" in error.message!!, error.message)
    }

    @Test
    fun aSampleThatFailedIsNotAskedForAgainForThirtySecondsAndThenItIs() {
        val there = AtomicBoolean(false)
        server.handle("/late.wav") { exchange ->
            if (there.get()) {
                exchange.sendResponseHeaders(200, 3)
                exchange.responseBody.write(byteArrayOf(1, 2, 3))
            } else {
                exchange.sendResponseHeaders(404, -1)
            }
        }
        val store = store()
        val url = server.url("/late.wav")

        assertNull(store.sample(url)) // 404: the file is not there yet
        store.settled()
        assertEquals(1, server.hits("/late.wav"))

        there.set(true)
        repeat(3) { assertNull(store.sample(url)) }
        clock.addAndGet(29_999)
        assertNull(store.sample(url))
        Thread.sleep(150)
        assertEquals(1, server.hits("/late.wav")) // nobody asked

        clock.addAndGet(2)
        assertNull(store.sample(url)) // 30 seconds are over: the load is started again
        assertEquals(3, store.loaded(url).samples.size)
        assertEquals(2, server.hits("/late.wav"))
    }

    @Test
    fun awaitSampleDoesNotWaitOutThe30SecondsOfAFailure() {
        val there = AtomicBoolean(false)
        server.handle("/soon.wav") { exchange ->
            if (there.get()) {
                exchange.sendResponseHeaders(200, 1)
                exchange.responseBody.write(4)
            } else {
                exchange.sendResponseHeaders(404, -1)
            }
        }
        val store = store()
        val url = server.url("/soon.wav")
        assertNull(store.sample(url))
        store.settled()

        there.set(true)

        assertEquals(1, store.awaitSample(url).samples.size) // somebody explicitly wants it: it is tried at once
    }

    @Test
    fun aFileThatDoesNotDecodeIsAFailureLikeAnyOther() {
        server.reply("/bad.wav", "BAD")
        val store = store()
        val url = server.url("/bad.wav")

        assertFailsWith<IOException> { store.awaitSample(url) }

        assertNull(store.sample(url)) // remembered for 30 seconds
        store.settled()
        assertEquals(1, server.hits("/bad.wav"))
    }

    @Test
    fun aDamagedFileOnDiskIsDeletedAndFetchedAgain() {
        server.reply("/k.wav", byteArrayOf(7, 8, 9))
        val url = server.url("/k.wav")
        val cache = SampleDiskCache(File(dir, "samples"))
        cache.put(sha1Hex(url)) { it.write("BAD".toByteArray()) }

        val data = store().loaded(url)

        assertEquals(3, data.samples.size)
        assertEquals(1, server.hits("/k.wav"))
        assertContentEquals(byteArrayOf(7, 8, 9), cache.get(sha1Hex(url))!!.readBytes())
    }

    @Test
    fun aFileOverTheLimitOrCutShortIsNotKept() {
        server.reply("/big.wav", ByteArray(500) { 1 })
        server.handle("/cut.wav") { exchange ->
            exchange.sendResponseHeaders(200, 1_000)
            exchange.responseBody.write(ByteArray(400) { 1 })
            exchange.responseBody.flush()
        }
        val store = store(config(maxFile = 200))

        assertFailsWith<IOException> { store.awaitSample(server.url("/big.wav")) }
        assertFailsWith<IOException> { store(config()).awaitSample(server.url("/cut.wav")) }
        assertTrue(File(dir, "samples").list().isNullOrEmpty(), File(dir, "samples").list()?.toList().toString())
    }

    @Test
    fun theBudgetOfDecodedSamplesDropsTheOneUsedLeastRecentlyAndItComesBackFromDisk() {
        for (name in listOf("one", "two", "three")) server.reply("/$name.wav", ByteArray(60) { 3 })
        val store = store(config(decodedBudget = 100))

        store.loaded(server.url("/one.wav"))
        store.loaded(server.url("/two.wav")) // 120 floats: one has to go
        store.settled()

        assertNotNull(store.sample(server.url("/two.wav")))
        assertNull(store.sample(server.url("/one.wav"))) // forgotten: reloading starts
        store.loaded(server.url("/one.wav"))
        assertEquals(1, server.hits("/one.wav")) // from the disk cache
        store.loaded(server.url("/three.wav"))
        store.settled()
        assertNotNull(store.sample(server.url("/three.wav")))
    }

    @Test
    fun aDocumentOfAnImportedFolderIsDecodedStraightFromItsStream() {
        val store = store(openContent = { url ->
            if (url == "content://docs/tree/1/document/2") ByteArrayInputStream(byteArrayOf(40, 50)) else throw IOException("no $url")
        })

        val data = store.loaded("content://docs/tree/1/document/2")

        assertEquals(2, data.samples.size)
        assertTrue(server.requests.isEmpty())
        assertFalse(File(dir, "samples").exists())
    }

    @Test
    fun aDocumentThatCannotBeOpenedIsAFailureLikeAnyOther() {
        val store = store()

        assertFailsWith<IOException> { store.awaitSample("content://docs/tree/1/document/missing") }
    }

    @Test
    fun manySamplesLoadAtOnceAndTheLoadingCountComesBackToZero() {
        for (i in 0 until 40) server.reply("/m/$i.wav", ByteArray(8) { i.toByte() })
        val store = store()

        for (i in 0 until 40) assertNull(store.sample(server.url("/m/$i.wav")))
        assertTrue(store.loading.value > 0)
        store.settled()

        for (i in 0 until 40) assertEquals(8, store.sample(server.url("/m/$i.wav"))!!.samples.size, "sample $i")
        assertEquals(40, server.requests.size)
    }

    @Test
    fun awaitSampleGivesUpAfterTheTimeout() {
        val release = CountDownLatch(1)
        server.handle("/slow.wav") { exchange ->
            release.await(10, TimeUnit.SECONDS)
            exchange.sendResponseHeaders(200, 1)
            exchange.responseBody.write(1)
        }
        val store = store(config(awaitMs = 300))

        val error = assertFailsWith<IOException> { store.awaitSample(server.url("/slow.wav")) }
        release.countDown()

        assertTrue("timed out" in error.message!!, error.message)
        assertEquals(1, store.loaded(server.url("/slow.wav")).samples.size) // the load went on
    }

    @Test
    fun awaitIdleWaitsForTheLoadsAndGivesUpAtItsTimeout() = runBlocking {
        val release = CountDownLatch(1)
        server.handle("/held.wav") { exchange ->
            release.await(10, TimeUnit.SECONDS)
            exchange.sendResponseHeaders(200, 1)
            exchange.responseBody.write(1)
        }
        val store = store()
        assertTrue(store.awaitIdle(100)) // nothing is loading

        store.sample(server.url("/held.wav"))
        assertFalse(store.awaitIdle(200))
        release.countDown()
        assertTrue(store.awaitIdle(5_000))
    }

    @Test
    fun theDecoderIsAvailableForDataThatIsNotAFileOfTheLibrary() {
        assertEquals(3, store().decode(byteArrayOf(1, 2, 3), "zone").samples.size)
    }

    @Test
    fun theBudgetOfDecodedSamplesFollowsTheMemoryOfTheAppBetweenSixteenAndSixtyFourMillion() {
        val mega = 1024L * 1024
        assertEquals(16 * mega, decodedBudgetFloats(64))
        assertEquals(256 * mega / 4 * 35 / 100, decodedBudgetFloats(256))
        assertEquals(64 * mega, decodedBudgetFloats(2048))
    }
}
