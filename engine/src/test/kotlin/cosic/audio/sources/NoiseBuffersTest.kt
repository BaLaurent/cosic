package cosic.audio.sources

import cosic.audio.Fft
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class NoiseBuffersTest {
    private val sr = 48000

    private fun rms(x: FloatArray): Double = sqrt(x.sumOf { it.toDouble() * it } / x.size)
    private fun mean(x: FloatArray): Double = x.sumOf { it.toDouble() } / x.size

    /** FNV-1a over the float32 bit patterns, word by word: the loop of tools/strudel-ref/noise-oracle.mjs. */
    private fun checksum(x: FloatArray): Int {
        var h = 2166136261L.toInt()
        for (v in x) h = (h xor v.toRawBits()) * 16777619
        return h
    }

    /** The power of the first 65536 frames between [lowHz] and [highHz]. */
    private fun bandPower(x: FloatArray, lowHz: Double, highHz: Double): Double {
        val n = 65536
        val re = DoubleArray(n) { x[it].toDouble() }
        val im = DoubleArray(n)
        Fft(n).forward(re, im)
        var power = 0.0
        for (k in 1 until n / 2) {
            val hz = k.toDouble() * sr / n
            if (hz >= lowHz && hz < highHz) power += re[k] * re[k] + im[k] * im[k]
        }
        return power
    }

    @Test
    fun buffersHoldTwoSecondsAtTheSampleRate() {
        for (type in NoiseType.entries) {
            assertEquals(2 * sr, NoiseBuffers.get(type, sr).size, "$type")
            assertEquals(2 * 44100, NoiseBuffers.get(type, 44100).size)
        }
    }

    @Test
    fun whiteNoiseIsUniformAndCentred() {
        val x = NoiseBuffers.get(NoiseType.WHITE, sr)
        assertEquals(0.5774, rms(x), 0.5774 * 0.02) // 1 / sqrt(3)
        assertEquals(0.0, mean(x), 0.01)
        assertTrue(x.all { it >= -1f && it < 1f })
        // uniform: as many frames in each fifth of the range
        val counts = IntArray(5)
        for (v in x) counts[minOf(((v + 1f) * 2.5f).toInt(), 4)]++
        for (c in counts) assertEquals(x.size / 5.0, c.toDouble(), x.size / 5.0 * 0.03)
    }

    @Test
    fun brownNoiseIsTheIntegratedWhiteNoise() {
        val x = NoiseBuffers.get(NoiseType.BROWN, sr)
        assertEquals(0.0574, rms(x), 0.0574 * 0.1)
        assertTrue(x.all { abs(it) < 0.4f })
        // y1 - y0 = (0.02 w - 0.02 y0) / 1.02: a step is at most 0.0196 + 0.0196 |y0|
        for (i in 1 until x.size) assertTrue(abs(x[i] - x[i - 1]) <= 0.0197f * (1f + abs(x[i - 1])), "frame $i")
    }

    @Test
    fun pinkNoiseFallsThreeDecibelsAnOctave() {
        val pink = NoiseBuffers.get(NoiseType.PINK, sr)
        val white = NoiseBuffers.get(NoiseType.WHITE, sr)
        fun octaveRatioDb(x: FloatArray) = 10 * log10(bandPower(x, 250.0, 500.0) / bandPower(x, 4000.0, 8000.0))
        // pink has the same power in every octave, white 16 times (12 dB) more in the octave that is four octaves higher
        assertEquals(0.0, octaveRatioDb(pink), 2.0)
        assertEquals(-12.0, octaveRatioDb(white), 1.5)
        assertTrue(rms(pink) in 0.05..0.5, "${rms(pink)}")
    }

    @Test
    fun crackleIsRareClicksOfUniformSize() {
        // density 2 is a probability of 0.02 a frame: 1920 clicks in 96000 frames (standard deviation 43)
        val x = NoiseBuffers.get(NoiseType.CRACKLE, sr, density = 2.0, seed = 5)
        assertEquals(1920.0, x.count { it != 0f }.toDouble(), 130.0)
        assertTrue(x.all { it >= -1f && it < 1f })
        val clicks = x.filter { it != 0f }
        assertEquals(0.0, clicks.sumOf { it.toDouble() } / clicks.size, 0.1)
        // the default density is 0.02: a probability of 0.0002, about 19 clicks in the loop
        val sparse = NoiseBuffers.get(NoiseType.CRACKLE, sr, seed = 5)
        assertTrue(sparse.count { it != 0f } in 3..50, "${sparse.count { it != 0f }}")
        assertEquals(0.02, NoiseBuffers.DEFAULT_DENSITY)
        // no density, a NaN one: silence; a density of 100 is a click every frame
        assertTrue(NoiseBuffers.get(NoiseType.CRACKLE, sr, density = 0.0).all { it == 0f })
        assertTrue(NoiseBuffers.get(NoiseType.CRACKLE, sr, density = Double.NaN).all { it == 0f })
        assertTrue(NoiseBuffers.get(NoiseType.CRACKLE, sr, density = 100.0, seed = 5).count { it != 0f } > 2 * sr - 5)
    }

    @Test
    fun whitePinkAndBrownAreBuiltOnceAndCrackleForEveryNote() {
        for (type in listOf(NoiseType.WHITE, NoiseType.PINK, NoiseType.BROWN)) {
            val first = NoiseBuffers.get(type, sr)
            assertSame(first, NoiseBuffers.get(type, sr))
            assertSame(first, NoiseBuffers.get(type, sr, density = 5.0, seed = 123)) // neither applies to them
            assertNotSame(first, NoiseBuffers.get(type, 44100))
        }
        assertFalse(NoiseBuffers.get(NoiseType.WHITE, sr).contentEquals(NoiseBuffers.get(NoiseType.PINK, sr)))
        val a = NoiseBuffers.get(NoiseType.CRACKLE, sr, density = 2.0, seed = 9)
        val b = NoiseBuffers.get(NoiseType.CRACKLE, sr, density = 2.0, seed = 9)
        assertNotSame(a, b)
        assertContentEquals(a, b) // the same seed gives the same clicks
        assertFalse(a.contentEquals(NoiseBuffers.get(NoiseType.CRACKLE, sr, density = 2.0, seed = 10)))
    }

    @Test
    fun theBuffersAreThoseOfSuperdoughsNoiseCode() {
        // tools/strudel-ref/noise-oracle.mjs runs the pinned getNoiseBuffer with Math.random replaced by the generator NoiseBuffers uses
        for (c in NoiseOracleVectors.cases) {
            val x = NoiseBuffers.generate(c.type, c.sampleRate, c.density, c.seed)
            val label = "${c.type} ${c.sampleRate} Hz seed ${c.seed}"
            assertEquals(2 * c.sampleRate, x.size, label)
            for ((frame, value) in c.points) assertEquals(value, x[frame], "$label frame $frame")
            assertEquals(c.checksum, checksum(x), "$label: the whole buffer")
        }
    }

    @Test
    fun aLoopedNoisePlaysTheBufferFromTheStartAndWraps() {
        val noise = LoopedNoise(FloatArray(10) { it + 1f })
        val played = FloatArray(25)
        val block = FloatArray(25)
        var done = 0
        for (n in intArrayOf(4, 0, 10, 11)) { // blocks that end inside the loop, on its end, and after it
            noise.render(block, n)
            System.arraycopy(block, 0, played, done, n)
            done += n
        }
        assertEquals(25, done)
        assertContentEquals(FloatArray(25) { (it % 10) + 1f }, played)
        noise.reset()
        val again = FloatArray(12)
        noise.render(again, 12)
        assertContentEquals(FloatArray(12) { (it % 10) + 1f }, again)
        // a block longer than the loop wraps more than once
        noise.reset()
        val long = FloatArray(35)
        noise.render(long, 35)
        assertContentEquals(FloatArray(35) { (it % 10) + 1f }, long)
    }

    @Test
    fun everyLoopedNoiseOverTheSameBufferStartsAtItsFirstFrame() {
        val buffer = NoiseBuffers.get(NoiseType.PINK, sr)
        val x = FloatArray(256)
        val y = FloatArray(256)
        LoopedNoise(buffer).render(x, 256)
        LoopedNoise(buffer).render(y, 256)
        assertContentEquals(x, y)
        assertContentEquals(buffer.copyOf(256), x)
    }

    @Test
    fun renderingDoesNotAllocate() {
        val noise = LoopedNoise(NoiseBuffers.get(NoiseType.WHITE, sr))
        val out = FloatArray(256)
        fun work() = repeat(2000) { noise.render(out, 256) } // more than a loop: wraps
        repeat(3) { work() }
        assertEquals(0L, steadyAllocation { work() })
    }

    @Test
    fun theNoiseMixKeepsTheSourceUpToAHalfAndFadesItOutAfterwards() {
        assertEquals(1.0, wetFade(0.0), 1e-12)
        assertEquals(1.0, wetFade(0.3), 1e-12)
        assertEquals(1.0, wetFade(0.5), 1e-12)
        assertEquals(0.6, wetFade(0.7), 1e-12)
        assertEquals(0.4, wetFade(0.8), 1e-12)
        assertEquals(0.0, wetFade(1.0), 1e-12)
        assertEquals(-1.0, wetFade(1.5), 1e-12) // not clamped
        assertTrue(wetFade(Double.NaN).isNaN())
        // the mix of a source and pink noise at noise = w is source * wetFade(w) + pink * wetFade(1 - w)
        assertEquals(1.0, wetFade(0.3), 1e-12)
        assertEquals(0.6, wetFade(1 - 0.3), 1e-12)
        assertEquals(0.4, wetFade(0.8), 1e-12)
        assertEquals(1.0, wetFade(1 - 0.8), 1e-12)
    }
}
