package cosic.audio

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Exact playback arithmetic of the sampler source: positions, interpolation, regions, direction, end fade. */
class SamplePlayerTest {
    private fun ramp(n: Int) = FloatArray(n) { it.toFloat() }

    private fun player(data: FloatArray, begin: Double = 0.0, end: Double = 1.0, rate: Double = 1.0) =
        SamplePlayer(SR).also { it.start(data, begin, end, rate) }

    /** Everything the player will produce, up to [limit] frames. */
    private fun drain(player: SamplePlayer, limit: Int = 100_000): FloatArray {
        val out = FloatArray(limit)
        return out.copyOf(player.fill(out, limit))
    }

    @Test
    fun readsTheSamplesInOrderAtUnitRate() {
        val out = drain(player(ramp(1000)))
        assertEquals(1000, out.size)
        for (i in 0 until 900) assertEquals(i.toFloat(), out[i], 1e-4f)
    }

    @Test
    fun interpolatesLinearlyAtFractionalRates() {
        val half = drain(player(ramp(1000), rate = 0.5))
        for (i in 0 until 1800) assertEquals(0.5f * i, half[i], 1e-3f)
        val fast = drain(player(ramp(1000), rate = 2.5))
        for (i in 0 until 300) assertEquals(2.5f * i, fast[i], 1e-3f)
        assertEquals(1999, half.size) // positions 0, 0.5, ..., 999
        assertEquals(400, fast.size) // positions 0, 2.5, ..., 997.5
    }

    @Test
    fun beginAndEndSelectTheFractionOfTheSample() {
        val out = drain(player(ramp(1000), begin = 0.25, end = 0.5))
        assertEquals(250, out.size)
        assertEquals(250f, out[0], 1e-4f)
        assertEquals(300f, out[50], 1e-4f)
    }

    @Test
    fun negativeRatePlaysTheRegionBackwardsStartingAtItsLastSample() {
        val out = drain(player(ramp(1000), rate = -1.0))
        assertEquals(1000, out.size)
        assertEquals(999f, out[0], 1e-4f)
        assertEquals(998f, out[1], 1e-4f)
        assertEquals(500f, out[499], 1e-4f)

        val region = drain(player(ramp(1000), begin = 0.25, end = 0.5, rate = -1.0))
        assertEquals(250, region.size)
        assertEquals(499f, region[0], 1e-4f)
    }

    @Test
    fun fadesOutOverTheLastTwoMillisecondsSoSlicesDoNotClick() {
        val out = drain(player(FloatArray(1000) { 1f }))
        assertEquals(1000, out.size)
        assertEquals(1f, out[out.size - 200])
        assertEquals(0.5f, out[out.size - 49], 0.02f) // 96 frames of fade
        assertEquals(0f, out[out.size - 1], 1e-6f)

        val backwards = drain(player(FloatArray(1000) { 1f }, rate = -1.0))
        assertEquals(0f, backwards[backwards.size - 1], 1e-6f)
        assertEquals(1f, backwards[backwards.size - 200])
    }

    @Test
    fun fillCanBeSplitAnywhereAndReportsTheEnd() {
        val whole = drain(player(ramp(100), rate = 0.7))
        assertEquals(142, whole.size)

        val chunked = player(ramp(100), rate = 0.7)
        val joined = ArrayList<Float>()
        val buffer = FloatArray(7)
        while (true) {
            val count = chunked.fill(buffer, 7)
            for (i in 0 until count) joined.add(buffer[i])
            if (count < 7) break
        }
        assertContentEquals(whole, joined.toFloatArray())
        assertEquals(0, chunked.fill(buffer, 7), "nothing is produced after the end")
    }

    @Test
    fun playsNothingWhenThereIsNothingToPlay() {
        assertEquals(0, drain(player(ramp(100), begin = 0.5, end = 0.5)).size)
        assertEquals(0, drain(player(ramp(100), begin = 0.7, end = 0.3)).size)
        assertEquals(0, drain(player(FloatArray(0))).size)
        assertEquals(0, drain(player(ramp(100), rate = 0.0)).size)
        assertTrue(drain(player(ramp(100), begin = 0.99, end = 1.0)).size >= 1, "a region under one frame still plays its single frame")
    }
}
