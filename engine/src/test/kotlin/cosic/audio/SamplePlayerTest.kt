package cosic.audio

import cosic.SampleData
import cosic.player.paramSetOf
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The sampler's player on its own: positions, rates, reversal, loops, the delay of `nudge`, stereo. Voice-level behaviour is in [SamplerPlaybackTest]. */
class SamplePlayerTest {
    /** A mono sample whose value is its frame number. */
    private fun counting(frames: Int, rate: Int = SR) = SampleData(FloatArray(frames) { it.toFloat() }, rate)

    private fun start(
        data: SampleData,
        transpose: Double = 0.0,
        vararg controls: Pair<String, Any?>,
        peak: Double = 1.0,
        nudge: Double = 0.0,
    ): SamplePlayer = SamplePlayer(SR).also {
        it.start(SampleSourceParams(data, transposeSemitones = transpose, peakGain = peak, nudgeSeconds = nudge), paramSetOf(mapOf(*controls)))
    }

    /** Everything the player produces until it ends, in blocks of [block] frames, or [limit] frames at most. */
    private fun SamplePlayer.drain(block: Int = 256, limit: Int = 1_000_000): FloatArray {
        val out = ArrayList<Float>()
        val l = FloatArray(block)
        val r = FloatArray(block)
        while (out.size < limit) {
            val n = fill(l, r, block)
            for (i in 0 until n) out.add(l[i])
            if (n < block) break
        }
        val all = out.toFloatArray()
        return if (all.size > limit) all.copyOf(limit) else all
    }

    @Test
    fun aSamplePlaysFromItsFirstFrameToItsLastAndThenEnds() {
        val player = start(counting(10))
        assertContentEquals(FloatArray(10) { it.toFloat() }, player.drain())
        assertEquals(0, player.fill(FloatArray(4), FloatArray(4), 4), "nothing comes after the end")
    }

    @Test
    fun fillCanBeSplitAnywhereAndGivesTheSameSamples() {
        val data = counting(1000)
        for (controls in listOf(
            emptyArray<Pair<String, Any?>>(),
            arrayOf<Pair<String, Any?>>("speed" to -1.7, "begin" to 0.1),
            arrayOf<Pair<String, Any?>>("loop" to 1.0, "loopBegin" to 0.3, "loopEnd" to 0.6),
        )) {
            val whole = start(data, 3.0, *controls).drain(block = 4096, limit = 5000)
            val parts = start(data, 3.0, *controls).drain(block = 7, limit = 5000)
            assertContentEquals(whole, parts, controls.joinToString())
        }
    }

    @Test
    fun beginStartsInsideTheSampleAndEndDoesNotStopIt() {
        // end only decides the gate; the player reads to the end of the file (the release plays on past `end`)
        assertContentEquals(FloatArray(75) { 25f + it }, start(counting(100), 0.0, "begin" to 0.25).drain())
        assertContentEquals(FloatArray(75) { 25f + it }, start(counting(100), 0.0, "begin" to 0.25, "end" to 0.5).drain())
    }

    @Test
    fun aBeginAtOrBeyondTheEndOfTheSamplePlaysNothing() {
        assertEquals(0, start(counting(100), 0.0, "begin" to 1.0).drain().size)
        assertEquals(0, start(counting(100), 0.0, "begin" to 7.5).drain().size)
    }

    @Test
    fun aNegativeSpeedReadsTheReversedFileSoBeginCountsFromTheEnd() {
        val out = start(counting(100), 0.0, "speed" to -1.0, "begin" to 0.25).drain()
        assertEquals(75, out.size)
        assertEquals(74f, out[0], "the reversed buffer's frame 25 is the original's frame 74")
        assertEquals(0f, out.last())
        for (i in 1 until out.size) assertEquals(out[i - 1] - 1f, out[i])
    }

    @Test
    fun theRateIsSpeedTimesTwoToTheTransposeOverTwelve() {
        assertContentEquals(FloatArray(50) { it * 2f }, start(counting(100), 12.0).drain(), "an octave up")
        assertContentEquals(FloatArray(50) { it * 2f }, start(counting(100), 0.0, "speed" to 2.0).drain())
        assertContentEquals(FloatArray(50) { 99f - it * 2f }, start(counting(100), 0.0, "speed" to -2.0).drain())
    }

    @Test
    fun theSampleRateOfTheFileIsConvertedWithLinearInterpolation() {
        val out = start(counting(10, rate = 24_000)).drain()
        assertEquals(20, out.size, "half the rate: twice the frames")
        for (i in out.indices) assertEquals(minOf(i / 2f, 9f), out[i], 1e-6f)
    }

    @Test
    fun unitCMakesTheSampleLastOneCycleAtSpeedOne() {
        // 2 s of data, speed .25 and unit c: the rate is .25 * 2 s = 0.5, so the sample lasts 4 s
        val frames = start(counting(2 * SR), 0.0, "speed" to 0.25, "unit" to "c").drain().size
        assertEquals(4.0 * SR, frames.toDouble(), 2.0)
        assertEquals(2 * SR, start(counting(2 * SR), 0.0, "unit" to "s").drain().size, "any other unit changes nothing")
    }

    @Test
    fun theLoopRegionIsTheFractionsOfTheSampleAndThePositionWrapsAtItsEnd() {
        val ramp = SampleData(FloatArray(48_000) { it / 48_000f }, SR)
        val out = start(ramp, 0.0, "loop" to 1.0, "loopBegin" to 0.25, "loopEnd" to 0.5).drain(limit = 96_000)
        assertEquals(96_000, out.size, "it loops for ever")
        assertEquals(0.35, out[(0.6 * SR).toInt()].toDouble(), 1e-3, "0.6 s in: 0.25 + (0.6 - 0.25) mod 0.25")
        assertEquals(0.25, out[(0.75 * SR).toInt()].toDouble(), 1e-3, "back at the start of the region")
        assertTrue(out.drop(24_000).all { it in 0.249f..0.5f }, "after the first pass it stays inside the region")
    }

    @Test
    fun aLoopThatIsNotAProperRegionLoopsTheWholeSample() {
        for (controls in listOf(
            arrayOf<Pair<String, Any?>>("loop" to 1.0),
            arrayOf<Pair<String, Any?>>("loop" to 1.0, "loopBegin" to 0.5, "loopEnd" to 0.25),
            arrayOf<Pair<String, Any?>>("loop" to 1.0, "loopBegin" to 0.5, "loopEnd" to 0.5),
            arrayOf<Pair<String, Any?>>("loop" to 1.0, "loopBegin" to 1.5, "loopEnd" to 2.0),
        )) {
            val out = start(counting(100), 0.0, *controls).drain(limit = 250)
            assertEquals(250, out.size, controls.joinToString())
            assertEquals(0f, out[100], controls.joinToString())
            assertEquals(49f, out[249], controls.joinToString())
        }
        val clipped = start(counting(100), 0.0, "loop" to 1.0, "loopBegin" to 0.5, "loopEnd" to 5.0).drain(limit = 250)
        assertEquals(50f, clipped[50], "a loop end beyond the sample is clipped to it")
        assertEquals(50f, clipped[100])
    }

    @Test
    fun loopZeroOrNoLoopMeansNoLoop() {
        assertEquals(100, start(counting(100), 0.0, "loop" to 0.0).drain().size)
        assertEquals(100, start(counting(100), 0.0, "loopBegin" to 0.2, "loopEnd" to 0.4).drain().size)
    }

    @Test
    fun aPositionBeyondTheLoopEndWrapsAtOnce() {
        val out = start(counting(100), 0.0, "loop" to 1.0, "loopBegin" to 0.25, "loopEnd" to 0.5, "begin" to 0.9).drain(limit = 10)
        assertEquals(40f, out[0], "0.9 is 15 past the region's end of 50: 25 + (90 - 25) mod 25")
    }

    @Test
    fun theLoopOfAReversedSampleIsInTheReversedFile() {
        val out = start(counting(100), 0.0, "speed" to -1.0, "loop" to 1.0, "loopBegin" to 0.0, "loopEnd" to 0.2).drain(limit = 60)
        assertEquals(99f, out[0])
        assertEquals(80f, out[19])
        assertEquals(99f, out[20], "wraps at the end of the first fifth of the reversed file")
    }

    @Test
    fun stereoSamplesComeOutAsTwoChannels() {
        val data = SampleData(floatArrayOf(1f, 0.5f, 3f, 1.5f, 5f, 2.5f), SR, channels = 2)
        val player = SamplePlayer(SR)
        player.start(SampleSourceParams(data), paramSetOf(emptyMap()))
        assertTrue(player.stereo)
        val l = FloatArray(8)
        val r = FloatArray(8)
        assertEquals(3, player.fill(l, r, 8))
        assertContentEquals(floatArrayOf(1f, 3f, 5f), l.copyOf(3))
        assertContentEquals(floatArrayOf(0.5f, 1.5f, 2.5f), r.copyOf(3))

        val slow = SamplePlayer(SR)
        slow.start(SampleSourceParams(data, transposeSemitones = -12.0), paramSetOf(emptyMap()))
        slow.fill(l, r, 8)
        assertEquals(2f, l[1], 1e-6f, "each channel is interpolated: half way between 1 and 3")
        assertEquals(1f, r[1], 1e-6f)
    }

    @Test
    fun aFileWithMoreThanTwoChannelsPlaysItsFirstTwo() {
        val data = SampleData(floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f), SR, channels = 3)
        val player = SamplePlayer(SR)
        player.start(SampleSourceParams(data), paramSetOf(emptyMap()))
        val l = FloatArray(4)
        val r = FloatArray(4)
        assertEquals(2, player.fill(l, r, 4))
        assertContentEquals(floatArrayOf(1f, 4f), l.copyOf(2))
        assertContentEquals(floatArrayOf(2f, 5f), r.copyOf(2))
    }

    @Test
    fun nudgeDelaysTheSourceByWholeFrames() {
        val ones = SampleData(FloatArray(10) { 1f }, SR)
        val out = start(ones, nudge = 10.0 / SR).drain()
        assertContentEquals(FloatArray(10) + FloatArray(10) { 1f }, out)
        assertContentEquals(out, start(ones, nudge = 10.0 / SR).drain(block = 4), "a delay that does not fit in a block goes on in the next")
        assertEquals(10, start(ones, nudge = -1.0).drain().size, "a negative nudge is no nudge")
    }

    @Test
    fun thePeakGainScalesTheSource() {
        assertContentEquals(FloatArray(4) { it * 0.3f }, start(counting(4), peak = 0.3).drain())
    }

    @Test
    fun aSoundfontIgnoresTheSamplerControlsAndLoopsItsOwnRegion() {
        val player = SamplePlayer(SR)
        player.start(
            SampleSourceParams(counting(100), loopStartFrame = 20.0, loopEndFrame = 40.0, honoursSampleControls = false, nudgeSeconds = 0.5),
            paramSetOf(mapOf("speed" to -3.0, "begin" to 0.5, "loop" to 0.0, "unit" to "c", "end" to 0.1)),
        )
        val out = FloatArray(120)
        assertEquals(120, player.fill(out, FloatArray(120), 120))
        assertEquals(0f, out[0], "no nudge, no begin, no reversing")
        assertEquals(39f, out[39])
        assertEquals(20f, out[40], "wrapped into its own loop")
        assertEquals(39f, out[59])
    }

    @Test
    fun theDetuneInputTransposesPerFrameInCents() {
        val player = start(counting(100))
        val l = FloatArray(10)
        val r = FloatArray(10)
        assertEquals(10, player.fill(l, r, 10, DoubleArray(10) { 1200.0 }))
        for (i in 0 until 10) assertEquals(i * 2f, l[i], 1e-4f)
        player.fill(l, r, 1)
        assertEquals(20f, l[0], "without detune it goes on at the normal rate from where it was")
    }

    @Test
    fun samplerHelpersReadTheControls() {
        val data = counting(2 * SR)
        fun params(vararg controls: Pair<String, Any?>) = paramSetOf(mapOf(*controls))
        val p = SampleSourceParams(data, 12.0)
        assertEquals(2.0, Sampler.playbackRate(p, params()), 1e-12)
        assertEquals(1.0, Sampler.playbackRate(p, params("speed" to -0.5)), 1e-12, "the sign only reverses")
        assertEquals(4.0, Sampler.playbackRate(p, params("unit" to "c")), 1e-12, "x the 2 seconds of the sample")
        assertEquals(1.0, Sampler.sliceSeconds(p, params(), 2.0), 1e-12, "(end - begin) * duration / rate")
        assertEquals(0.5, Sampler.sliceSeconds(p, params("begin" to 0.25, "end" to 0.5), 1.0), 1e-12)
        assertFalse(Sampler.gateIsHap(p, params()))
        for (key in listOf("clip", "loop", "release")) assertTrue(Sampler.gateIsHap(p, params(key to 0.0)), "$key set, even to 0, makes the gate the hap's")
        assertTrue(Sampler.gateIsHap(SampleSourceParams(data, honoursSampleControls = false), params()))
    }
}
