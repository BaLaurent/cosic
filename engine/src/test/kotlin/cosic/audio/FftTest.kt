package cosic.audio

import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FftTest {
    private fun naive(re: DoubleArray, im: DoubleArray): Pair<DoubleArray, DoubleArray> {
        val n = re.size
        val outRe = DoubleArray(n)
        val outIm = DoubleArray(n)
        for (k in 0 until n) {
            var sr = 0.0
            var si = 0.0
            for (t in 0 until n) {
                val angle = -2.0 * Math.PI * ((k.toLong() * t) % n) / n
                sr += re[t] * cos(angle) - im[t] * sin(angle)
                si += re[t] * sin(angle) + im[t] * cos(angle)
            }
            outRe[k] = sr
            outIm[k] = si
        }
        return outRe to outIm
    }

    @Test
    fun forwardMatchesTheDefinitionOfTheDft() {
        for (size in intArrayOf(2, 8, 256, 4096)) {
            val rng = Random(size.toLong())
            val re = DoubleArray(size) { rng.nextGaussian() }
            val im = DoubleArray(size) { rng.nextGaussian() }
            val (expectedRe, expectedIm) = naive(re, im)
            Fft(size).forward(re, im)
            var worst = 0.0
            for (k in 0 until size) worst = maxOf(worst, abs(re[k] - expectedRe[k]), abs(im[k] - expectedIm[k]))
            assertTrue(worst < 1e-8 * size, "size $size: worst error $worst")
        }
    }

    @Test
    fun inverseUndoesForward() {
        for (size in intArrayOf(2, 64, 2048, 65_536)) {
            val rng = Random(7)
            val re = DoubleArray(size) { rng.nextGaussian() }
            val im = DoubleArray(size) { rng.nextGaussian() }
            val re0 = re.copyOf()
            val im0 = im.copyOf()
            val fft = Fft(size)
            fft.forward(re, im)
            fft.inverse(re, im)
            var worst = 0.0
            for (i in 0 until size) worst = maxOf(worst, abs(re[i] - re0[i]), abs(im[i] - im0[i]))
            assertTrue(worst < 1e-12, "size $size: round trip error $worst")
        }
    }

    @Test
    fun anImpulseHasAFlatSpectrumAndASineASinglePair() {
        val re = DoubleArray(16)
        val im = DoubleArray(16)
        re[0] = 1.0
        Fft(16).forward(re, im)
        for (k in 0 until 16) assertEquals(1.0, re[k], 1e-12)

        val n = 64
        val sine = DoubleArray(n) { sin(2.0 * Math.PI * 5 * it / n) }
        val zeros = DoubleArray(n)
        Fft(n).forward(sine, zeros)
        for (k in 0 until n) {
            val magnitude = Math.hypot(sine[k], zeros[k])
            assertEquals(if (k == 5 || k == n - 5) n / 2.0 else 0.0, magnitude, 1e-9, "bin $k")
        }
    }

    @Test
    fun sizesThatAreNotAPowerOfTwoAreRejected() {
        assertFailsWith<IllegalArgumentException> { Fft(12) }
        assertFailsWith<IllegalArgumentException> { Fft(1) }
        assertFailsWith<IllegalArgumentException> { Fft(131_072) }
    }
}
