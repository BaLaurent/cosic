package cosic.audio.dsp

import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

/** The port of fft.js's real transform: the lower half is the DFT; the upper half is whatever the butterflies leave there. */
class FftJsTest {
    private fun naive(data: FloatArray): Pair<DoubleArray, DoubleArray> {
        val n = data.size
        val re = DoubleArray(n)
        val im = DoubleArray(n)
        for (k in 0 until n) {
            var sr = 0.0
            var si = 0.0
            for (t in 0 until n) {
                val angle = -2.0 * Math.PI * ((k.toLong() * t) % n) / n
                sr += data[t] * cos(angle)
                si += data[t] * sin(angle)
            }
            re[k] = sr
            im[k] = si
        }
        return re to im
    }

    @Test
    fun theLowerHalfOfTheSpectrumIsTheDft() {
        for (size in intArrayOf(2, 4, 8, 16, 32, 64, 256, 1024, 2048)) {
            val rng = Random(size.toLong())
            val data = FloatArray(size) { rng.nextGaussian().toFloat() }
            val out = DoubleArray(2 * size)
            FftJs(size).realTransform(out, data)
            val (re, im) = naive(data)
            var worst = 0.0
            for (k in 0..size / 2) worst = maxOf(worst, abs(out[2 * k] - re[k]), abs(out[2 * k + 1] - im[k]))
            assertTrue(worst < 1e-9 * size, "size $size: worst error $worst")
        }
    }

    @Test
    fun theOutputIsAFunctionOfTheInputAloneNotOfWhatTheBufferHeld() {
        // The upper half keeps butterfly leftovers; they must not depend on the previous contents of the output buffer.
        val rng = Random(5)
        val data = FloatArray(2048) { rng.nextGaussian().toFloat() }
        val fft = FftJs(2048)
        val clean = DoubleArray(4096)
        fft.realTransform(clean, data)
        val dirty = DoubleArray(4096) { 1e9 * (it % 7 - 3) }
        fft.realTransform(dirty, data)
        for (i in 0 until 4096) assertTrue(clean[i] == dirty[i], "entry $i")
    }
}
