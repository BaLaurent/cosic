package cosic.io

import java.lang.management.ManagementFactory
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The sound of the voices with an `analyze` id, for the scope and spectrum drawings. */
class ScopeTapsTest {
    private val taps = ScopeTaps()

    private fun read(id: String, count: Int): FloatArray = FloatArray(count).also { assertTrue(taps.read(id, count, it), "$id has sound") }

    @Test
    fun voicesOfTheSameIdAreSummed() {
        taps.add("a", floatArrayOf(1f, 1f, 1f, 1f), 0, 4)
        taps.add("a", floatArrayOf(2f, 2f, 2f, 2f), 0, 4)
        taps.endBlock(4)
        assertContentEquals(floatArrayOf(3f, 3f, 3f, 3f), read("a", 4))
    }

    @Test
    fun idsAreKeptApart() {
        taps.add("a", floatArrayOf(1f, 1f), 0, 2)
        taps.add("b", floatArrayOf(5f, 5f), 0, 2)
        taps.endBlock(2)
        assertContentEquals(floatArrayOf(1f, 1f), read("a", 2))
        assertContentEquals(floatArrayOf(5f, 5f), read("b", 2))
    }

    @Test
    fun aSegmentIsAddedAtItsOffsetInTheBlock() {
        taps.add("a", floatArrayOf(7f, 8f), 2, 2)
        taps.endBlock(4)
        assertContentEquals(floatArrayOf(0f, 0f, 7f, 8f), read("a", 4))
    }

    @Test
    fun readGivesTheLatestSamplesOldestFirstAcrossBlocks() {
        taps.add("a", floatArrayOf(1f, 2f, 3f, 4f), 0, 4)
        taps.endBlock(4)
        taps.add("a", floatArrayOf(5f, 6f, 7f, 8f), 0, 4)
        taps.endBlock(4)
        assertContentEquals(floatArrayOf(3f, 4f, 5f, 6f, 7f, 8f), read("a", 6))
        assertContentEquals(floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f, 7f, 8f), read("a", 8))
    }

    @Test
    fun aBlockWithoutSoundAfterSoundAppendsZeros() {
        taps.add("a", floatArrayOf(1f, 2f, 3f, 4f), 0, 4)
        taps.endBlock(4)
        taps.endBlock(4)
        assertContentEquals(floatArrayOf(1f, 2f, 3f, 4f, 0f, 0f, 0f, 0f), read("a", 8))
    }

    @Test
    fun theSumStartsFromSilenceInEveryBlock() {
        taps.add("a", floatArrayOf(1f, 1f), 0, 2)
        taps.endBlock(2)
        taps.add("a", floatArrayOf(2f, 2f), 0, 2)
        taps.endBlock(2)
        assertContentEquals(floatArrayOf(1f, 1f, 2f, 2f), read("a", 4))
    }

    @Test
    fun fewerFramesThanAskedForLeaveZerosAtTheStart() {
        taps.add("a", floatArrayOf(1f, 2f), 0, 2)
        taps.endBlock(2)
        assertContentEquals(floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 1f, 2f), read("a", 8))
    }

    @Test
    fun anIdThatNeverSoundedCannotBeRead() {
        val out = FloatArray(4)
        assertFalse(taps.read("nothing", 4, out))
        taps.prepare("ready")
        taps.endBlock(4)
        assertFalse(taps.read("ready", 4, out), "a tap that was prepared has no sound until a voice adds some")
        taps.add("ready", floatArrayOf(1f), 0, 1)
        taps.endBlock(1)
        assertTrue(taps.read("ready", 4, out))
        assertContentEquals(floatArrayOf(0f, 0f, 0f, 1f), out)
    }

    @Test
    fun theRingKeepsTheLatestFramesWhenItWrapsAround() {
        val block = FloatArray(256)
        var next = 0
        repeat(160) { // 40960 frames, more than the 32768 of the ring
            for (i in block.indices) block[i] = (next++ % 1000).toFloat()
            taps.add("a", block, 0, 256)
            taps.endBlock(256)
        }
        val count = 8192
        val read = read("a", count)
        for (i in 0 until count) assertEquals(((next - count + i) % 1000).toFloat(), read[i], "frame $i")
    }

    @Test
    fun aBlockLongerThanTheSumHoldsKeepsItsPlaceInTime() {
        taps.add("a", floatArrayOf(1f), 0, 1)
        taps.endBlock(10_000)
        taps.add("a", floatArrayOf(9f), 0, 1)
        taps.endBlock(1)
        val out = read("a", 3)
        assertContentEquals(floatArrayOf(0f, 0f, 9f), out, "the long block was 10000 frames, the last of which are silent")
    }

    @Test
    fun readingMoreThanTheRingHoldsIsRefused() {
        taps.add("a", floatArrayOf(1f), 0, 1)
        taps.endBlock(1)
        assertFailsWith<IllegalArgumentException> { taps.read("a", SCOPE_RING_FRAMES + 1, FloatArray(SCOPE_RING_FRAMES + 1)) }
        assertFailsWith<IllegalArgumentException> { taps.read("a", 4, FloatArray(2)) }
    }

    @Test
    fun theAudioThreadDoesNotAllocateOnceATapExists() {
        val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        val mono = FloatArray(256) { 0.1f }
        fun block() {
            taps.add("a", mono, 0, 256)
            taps.add("b", mono, 64, 128)
            taps.endBlock(256)
        }
        repeat(10) { block() }
        val before = threads.currentThreadAllocatedBytes
        repeat(1000) { block() }
        val allocated = threads.currentThreadAllocatedBytes - before
        // a block that allocated even one small object would show 16 bytes a block, 16000 here; the little there is is the measuring
        assertTrue(allocated < 1024, "1000 blocks allocated $allocated bytes")
    }

    @Test
    fun aReaderOnAnotherThreadSeesWholeBlocksWhileTheAudioThreadWrites() {
        val block = FloatArray(256)
        var written = 0
        val writer = thread {
            repeat(4000) { n ->
                // every block is a constant, so a copy that mixes up two blocks of the ring shows steps inside a block
                java.util.Arrays.fill(block, (n % 100).toFloat())
                taps.add("a", block, 0, 256)
                taps.endBlock(256)
                written = n + 1
            }
        }
        val out = FloatArray(8192)
        var reads = 0
        while (writer.isAlive || reads == 0) {
            if (taps.read("a", out.size, out)) {
                reads++
                // 256-frame blocks of one value each, in order: the value changes only on block boundaries, by one at a time
                var changes = 0
                for (i in 1 until out.size) {
                    if (out[i] != out[i - 1]) {
                        changes++
                        val rising = (out[i] - out[i - 1]).let { it == 1f || it == -99f }
                        val zeroPad = out[i - 1] == 0f
                        assertTrue(rising || zeroPad, "a step from ${out[i - 1]} to ${out[i]} at $i")
                    }
                }
                assertTrue(changes <= 33, "$changes steps in 8192 frames")
            }
        }
        writer.join()
        assertEquals(4000, written)
    }
}
