package cosic.audio

/**
 * An in-place radix-2 complex FFT of a fixed power-of-two [size] (2 to 65536), in double precision. The tables are built
 * once, so [forward] and [inverse] never allocate.
 *
 * [forward] computes `X[k] = sum x[n] e^{-2 pi i k n / N}` without scaling. [inverse] is its inverse: `e^{+...}` scaled by `1/N`, so
 * `inverse(forward(x)) == x`.
 */
internal class Fft(val size: Int) {
    private val bitReversed = IntArray(size)
    private val cosTable = DoubleArray(size / 2)
    private val sinTable = DoubleArray(size / 2)

    init {
        require(size >= 2 && size <= MAX_SIZE && (size and (size - 1)) == 0) { "FFT size must be a power of two from 2 to $MAX_SIZE, was $size" }
        val bits = Integer.numberOfTrailingZeros(size)
        for (i in 0 until size) bitReversed[i] = Integer.reverse(i) ushr (32 - bits)
        for (i in 0 until size / 2) {
            val angle = 2.0 * Math.PI * i / size
            cosTable[i] = Math.cos(angle)
            sinTable[i] = Math.sin(angle)
        }
    }

    /** In place, unscaled, with the `e^{-i}` kernel. [re] and [im] must hold [size] values. */
    fun forward(re: DoubleArray, im: DoubleArray) = transform(re, im, -1.0)

    /** In place, scaled by `1/size`, with the `e^{+i}` kernel. */
    fun inverse(re: DoubleArray, im: DoubleArray) {
        transform(re, im, 1.0)
        val scale = 1.0 / size
        for (i in 0 until size) {
            re[i] *= scale
            im[i] *= scale
        }
    }

    private fun transform(re: DoubleArray, im: DoubleArray, sign: Double) {
        for (i in 0 until size) {
            val j = bitReversed[i]
            if (j > i) {
                val tr = re[i]
                re[i] = re[j]
                re[j] = tr
                val ti = im[i]
                im[i] = im[j]
                im[j] = ti
            }
        }
        var length = 2
        while (length <= size) {
            val half = length / 2
            val step = size / length
            var start = 0
            while (start < size) {
                var k = 0
                for (j in start until start + half) {
                    val wr = cosTable[k]
                    val wi = sign * sinTable[k]
                    val a = j + half
                    val xr = re[a] * wr - im[a] * wi
                    val xi = re[a] * wi + im[a] * wr
                    re[a] = re[j] - xr
                    im[a] = im[j] - xi
                    re[j] += xr
                    im[j] += xi
                    k += step
                }
                start += length
            }
            length *= 2
        }
    }

    companion object {
        const val MAX_SIZE = 65536
    }
}
