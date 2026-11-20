package cosic.audio

import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [Convolver] against direct convolution and against Chromium's `ConvolverNode` (`orbit-convolver.json`: the normalization and the channel routing, on responses that cross
 * the boundaries between the three parts of the partitioned convolver).
 */
class ConvolverTest {
    /** Runs [input] (one or two channels) through a convolver cut into pieces of the sizes [blocks] (repeated). */
    private fun run(convolver: Convolver, input: List<FloatArray>, blocks: IntArray): List<FloatArray> {
        val frames = input[0].size
        val outL = FloatArray(frames)
        val outR = FloatArray(frames)
        val capacity = blocks.max()
        val inL = FloatArray(capacity)
        val inR = FloatArray(capacity)
        val pieceL = FloatArray(capacity)
        val pieceR = FloatArray(capacity)
        var pos = 0
        var b = 0
        while (pos < frames) {
            val n = minOf(blocks[b++ % blocks.size], frames - pos)
            input[0].copyInto(inL, 0, pos, pos + n)
            val right = if (input.size == 2) inR.also { input[1].copyInto(it, 0, pos, pos + n) } else inL
            convolver.process(inL, right, pieceL, pieceR, n)
            pieceL.copyInto(outL, pos, 0, n)
            pieceR.copyInto(outR, pos, 0, n)
            pos += n
        }
        return listOf(outL, outR)
    }

    /** `sum(h[m] * x[n - m])` in doubles. */
    private fun direct(x: FloatArray, h: FloatArray, scale: Double, n: Int): Double {
        var s = 0.0
        for (m in 0..minOf(n, h.size - 1)) s += h[m].toDouble() * x[n - m]
        return s * scale
    }

    @Test
    fun matchesChromiumsConvolverNode() {
        for (case in OrbitGolden.cases("convolver")) {
            val name = OrbitGolden.string(case, "name")
            val sr = OrbitGolden.int(case, "sr")
            val irChannels = OrbitGolden.int(case, "irChannels")
            val inChannels = OrbitGolden.int(case, "inChannels")
            val irLength = OrbitGolden.int(case, "irLength")
            val frames = OrbitGolden.int(case, "frames")
            val ir = ImpulseResponse(Array(irChannels) { lcg(11L + it, irLength) }, sr)
            val input = List(inChannels) { lcg(21L + it, frames, 0.5) }
            assertEquals(OrbitGolden.number(case, "scale"), PreparedImpulse.normalizationScale(ir), 1e-9 * OrbitGolden.number(case, "scale"), "$name: normalization scale")
            val out = run(Convolver(PreparedImpulse.prepare(ir)), input, intArrayOf(128, 77, 256, 1, 200, 31))
            val expected = OrbitGolden.outputs(case)
            for (ch in 0..1) assertWithin("$name, channel $ch", expected[ch], out[ch], 5e-7)
        }
    }

    @Test
    fun equalsDirectConvolutionOfAOneSecondRoom() {
        val length = SR
        val frames = 2 * SR
        val scale = 1.0 / sqrt(length.toDouble())
        val irL = lcg(1, length, scale)
        val irR = lcg(2, length, scale)
        val xL = lcg(3, frames, 0.5)
        val xR = lcg(4, frames, 0.5)
        val prepared = PreparedImpulse.prepare(ImpulseResponse(arrayOf(irL, irR), SR), normalize = false)
        val out = run(Convolver(prepared), listOf(xL, xR), intArrayOf(256, 100, 255, 1, 128))
        val rng = Random(5)
        val frames2check = ArrayList<Int>()
        for (n in 0 until 300) frames2check.add(n)
        for (boundary in 128..20000 step 128 * 7) for (d in -2..2) frames2check.add(boundary + d)
        for (boundary in 2048..40960 step 2048) for (d in -1..1) frames2check.add(boundary + d)
        repeat(1000) { frames2check.add(rng.nextInt(frames)) }
        var worst = 0.0
        for (n in frames2check) {
            worst = maxOf(worst, kotlin.math.abs(direct(xL, irL, 1.0, n) - out[0][n]), kotlin.math.abs(direct(xR, irR, 1.0, n) - out[1][n]))
        }
        assertTrue(worst < 2e-6, "worst error against direct convolution $worst")
    }

    @Test
    fun everyLengthAroundThePartitionBoundariesIsExact() {
        val x = lcg(9, 6000, 0.5)
        for (length in intArrayOf(1, 2, 100, 127, 128, 129, 130, 255, 256, 257, 2047, 2048, 2049, 2050, 4095, 4096, 4097, 6143)) {
            val h = lcg(100L + length, length, 1.0 / sqrt(length.toDouble()))
            val out = run(Convolver(PreparedImpulse.prepare(ImpulseResponse(arrayOf(h), SR), normalize = false)), listOf(x), intArrayOf(256))
            var worst = 0.0
            for (n in 0 until 6000) worst = maxOf(worst, kotlin.math.abs(direct(x, h, 1.0, n) - out[0][n]), kotlin.math.abs(direct(x, h, 1.0, n) - out[1][n]))
            assertTrue(worst < 2e-6, "length $length: worst error $worst")
        }
    }

    @Test
    fun aMonoResponseServesBothChannels() {
        val h = lcg(7, 3000, 0.02)
        val left = lcg(8, 5000, 0.5)
        val right = lcg(9, 5000, 0.5)
        val out = run(Convolver(PreparedImpulse.prepare(ImpulseResponse(arrayOf(h), SR), normalize = false)), listOf(left, right), intArrayOf(256))
        for (n in intArrayOf(0, 50, 127, 128, 2047, 2048, 3000, 4999)) {
            assertEquals(direct(left, h, 1.0, n), out[0][n].toDouble(), 2e-6)
            assertEquals(direct(right, h, 1.0, n), out[1][n].toDouble(), 2e-6)
        }
    }

    @Test
    fun silenceAtTheEndOfAResponseCostsNothingButStillCountsInTheNormalization() {
        val h = lcg(5, 3000, 0.5)
        val padded = h.copyOf(3000 + 5 * SR)
        val short = PreparedImpulse.prepare(ImpulseResponse(arrayOf(h), SR))
        val long = PreparedImpulse.prepare(ImpulseResponse(arrayOf(padded), SR))
        assertEquals(3000, long.length, "only what is not silence is convolved")
        assertTrue(long.bytes <= short.bytes + 4096)
        // Blink takes the rms over the whole buffer, zeros included: the longer buffer is scaled up by sqrt(length ratio)
        val ratio = PreparedImpulse.normalizationScale(ImpulseResponse(arrayOf(padded), SR)) / PreparedImpulse.normalizationScale(ImpulseResponse(arrayOf(h), SR))
        assertEquals(sqrt((3000.0 + 5 * SR) / 3000.0), ratio, 1e-9)
        val x = FloatArray(500).also { it[0] = 1f }
        val out = run(Convolver(long), listOf(x), intArrayOf(256))[0]
        assertEquals(h[0] * PreparedImpulse.normalizationScale(ImpulseResponse(arrayOf(padded), SR)), out[0].toDouble(), 1e-6)
    }

    @Test
    fun theOutputDoesNotDependOnHowTheBlocksAreCut() {
        val h = lcg(1, 9000, 0.01)
        val x = listOf(lcg(2, 20000, 0.5), lcg(3, 20000, 0.5))
        val prepared = PreparedImpulse.prepare(ImpulseResponse(arrayOf(h, lcg(4, 9000, 0.01)), SR))
        val whole = run(Convolver(prepared), x, intArrayOf(4096))
        val cut = run(Convolver(prepared), x, intArrayOf(1, 127, 128, 129, 256, 3, 1000, 17))
        assertContentEquals(whole[0], cut[0])
        assertContentEquals(whole[1], cut[1])
    }

    @Test
    fun resetForgetsTheInput() {
        val h = lcg(1, 5000, 0.01)
        val convolver = Convolver(PreparedImpulse.prepare(ImpulseResponse(arrayOf(h), SR)))
        val noise = listOf(lcg(2, 6000, 0.5))
        val first = run(convolver, noise, intArrayOf(256))
        assertTrue(peak(first[0]) > 0f)
        convolver.reset()
        val silent = run(convolver, listOf(FloatArray(6000)), intArrayOf(256))
        assertEquals(0f, peak(silent[0]))
        assertEquals(0f, peak(silent[1]))
        convolver.reset()
        val again = run(convolver, noise, intArrayOf(256))
        assertContentEquals(first[0], again[0])
    }

    @Test
    fun aConvolverWhoseInputHasBeenSilentForLongerThanTheResponseGoesIdleAndComesBackExactly() {
        val prepared = PreparedImpulse.prepare(ImpulseResponse(arrayOf(lcg(1, 3000, 0.02)), SR))
        val burst = listOf(lcg(2, 3000, 0.5))
        val busy = Convolver(prepared)
        assertTrue(busy.isIdle, "a new convolver has nothing in it")
        run(busy, burst, intArrayOf(256))
        assertFalse(busy.isIdle)
        val tail = run(busy, listOf(FloatArray(3000 + 2 * 2048 + 1000)), intArrayOf(256))
        assertTrue(busy.isIdle, "the response has run out")
        assertTrue(peak(tail[0]) > 0f, "the tail was heard on the way")
        assertEquals(0f, peak(tail[0], 3000 + 2 * 2048 + 256), "and it is exactly 0 by then")
        val again = run(busy, burst, intArrayOf(256))
        assertFalse(busy.isIdle)
        // the frame counter went on while it idled, so its partitions are cut at other frames than a new convolver's: the same result up to float rounding
        assertWithin("restart", run(Convolver(prepared), burst, intArrayOf(256))[0], again[0], 1e-6)
    }

    @Test
    fun aTenSecondRoomIsAffordableAndExact() {
        // 1.5 * 10 s at 48 kHz: 720000 frames of response, 352 partitions of the second tier
        val length = 15 * SR
        val h = lcg(1, length, 1.0 / sqrt(length.toDouble()))
        val prepared = PreparedImpulse.prepare(ImpulseResponse(arrayOf(h), SR), normalize = false)
        val convolver = Convolver(prepared)
        val x = lcg(2, 3 * SR, 0.5)
        val start = System.nanoTime()
        val out = run(convolver, listOf(x), intArrayOf(256))
        val seconds = (System.nanoTime() - start) / 1e9
        // far faster than real time on any machine that runs the build (a phone core needs a sizeable share of the time, a desktop a few percent): well under twice real time
        assertTrue(seconds < 6.0, "three seconds of audio took $seconds s through a 15 s response")
        for (n in intArrayOf(5000, 48000, 100000, 143999)) assertEquals(direct(x, h, 1.0, n), out[0][n].toDouble(), 3e-6)
    }
}
