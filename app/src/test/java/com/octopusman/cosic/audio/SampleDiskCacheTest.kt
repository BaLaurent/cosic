package com.octopusman.cosic.audio

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
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

class SampleDiskCacheTest {
    private lateinit var dir: File

    // Every reading of the clock is one second later than the one before, so that the order of use is the order of the calls.
    private var clock = 1_700_000_000_000L
    private val now: () -> Long = { clock.also { clock += 1_000 } }

    @BeforeTest
    fun makeDir() {
        dir = Files.createTempDirectory("sample-cache").toFile().resolve("samples")
    }

    @AfterTest
    fun removeDir() {
        dir.parentFile.deleteRecursively()
    }

    private fun cache(cap: Long = 1_000_000) = SampleDiskCache(dir, cap, now)

    private fun SampleDiskCache.putBytes(key: String, size: Int, fill: Byte = 1) = put(key) { it.write(ByteArray(size) { fill }) }

    @Test
    fun aFileComesBackWhatWasWritten() {
        val cache = cache()
        val file = cache.put("k") { it.write(byteArrayOf(1, 2, 3)) }

        assertContentEquals(byteArrayOf(1, 2, 3), file.readBytes())
        assertEquals(file, cache.get("k"))
        assertEquals(3, cache.sizeBytes())
    }

    @Test
    fun aKeyThatIsNotThereIsNull() {
        assertNull(cache().get("nothing"))
    }

    @Test
    fun aFailedWriteLeavesNothingBehindUnderTheKeyOrAnotherName() {
        val cache = cache()
        cache.putBytes("k", 10, fill = 7)

        assertFailsWith<IOException> { cache.put("k") { it.write(1); throw IOException("connection lost") } }
        assertFailsWith<IOException> { cache.put("other") { throw IOException("connection lost") } }

        assertContentEquals(ByteArray(10) { 7 }, cache.get("k")!!.readBytes()) // the older file is still whole
        assertNull(cache.get("other"))
        assertEquals(listOf("k"), dir.list()!!.toList())
        assertEquals(10, cache.sizeBytes())
    }

    @Test
    fun writingAKeyAgainReplacesItAndCountsItOnce() {
        val cache = cache()
        cache.putBytes("k", 100)
        cache.putBytes("k", 40, fill = 2)

        assertEquals(40, cache.get("k")!!.length())
        assertEquals(40, cache.sizeBytes())
    }

    @Test
    fun whenTheCapIsPassedTheLeastRecentlyUsedFilesGoUntilEightyPercentIsLeft() {
        val cache = cache(cap = 1_000)
        cache.putBytes("a", 300)
        cache.putBytes("b", 300)
        cache.putBytes("c", 300)
        assertEquals(900, cache.sizeBytes()) // under the cap: nothing is evicted

        cache.putBytes("d", 300) // 1200 > 1000: a and b go, leaving 600 (the first state at or under 800)

        assertNull(cache.get("a"))
        assertNull(cache.get("b"))
        assertNotNull(cache.get("c"))
        assertNotNull(cache.get("d"))
        assertEquals(600, cache.sizeBytes())
    }

    @Test
    fun readingAFileMarksItAsUsedSoItOutlivesOlderOnes() {
        val cache = cache(cap = 1_000)
        cache.putBytes("a", 300)
        cache.putBytes("b", 300)
        cache.putBytes("c", 300)
        assertNotNull(cache.get("a")) // a is the most recently used now

        cache.putBytes("d", 300)

        assertNotNull(cache.get("a"))
        assertNull(cache.get("b"))
        assertNull(cache.get("c"))
        assertNotNull(cache.get("d"))
    }

    @Test
    fun theFileThatWasJustWrittenIsNeverEvictedEvenWhenItAloneIsOverTheCap() {
        val cache = cache(cap = 100)
        cache.putBytes("old", 50)
        cache.putBytes("big", 500)

        assertNull(cache.get("old"))
        assertEquals(500, cache.get("big")!!.length())
    }

    @Test
    fun anotherRunFindsWhatTheFirstLeftCountsItAndKnowsWhichWasUsedLast() {
        cache().apply {
            putBytes("a", 100)
            putBytes("b", 200)
        }
        val second = cache(cap = 500)

        assertEquals(100, second.get("a")!!.length()) // a was used after b now
        assertEquals(300, second.sizeBytes())
        second.putBytes("c", 250) // 550 is over the cap: b, used least recently, goes, and 350 is under 80 % of it

        assertNull(second.get("b"))
        assertNotNull(second.get("a"))
        assertNotNull(second.get("c"))
    }

    @Test
    fun aDownloadThatAnEarlierRunDidNotFinishIsDeletedWhenTheCacheIsLookedAt() {
        dir.mkdirs()
        File(dir, "k.tmpdeadbeef-1").writeBytes(ByteArray(500))
        val cache = cache()
        cache.putBytes("a", 10)

        assertEquals(listOf("a"), dir.list()!!.toList())
        assertEquals(10, cache.sizeBytes())
    }

    @Test
    fun theDownloadsOfThisRunThatAreStillGoingOnAreLeftAlone() {
        val cache = cache()
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val writer = Thread { cache.put("slow") { out -> out.write(1); started.countDown(); finish.await(); out.write(2) } }
        writer.start()
        started.await()

        cache.putBytes("fast", 5) // this scans the directory for the first time, while the other download is half written
        finish.countDown()
        writer.join()

        assertEquals(2, cache.get("slow")!!.length())
        assertEquals(5, cache.get("fast")!!.length())
        assertEquals(7, cache.sizeBytes())
    }

    @Test
    fun removingAFileTakesItsBytesOffTheCount() {
        val cache = cache()
        cache.putBytes("a", 100)
        cache.putBytes("b", 50)

        cache.remove("a")
        cache.remove("never-there")

        assertNull(cache.get("a"))
        assertEquals(50, cache.sizeBytes())
        assertFalse(File(dir, "a").exists())
        assertTrue(File(dir, "b").exists())
    }
}
