package cosic.audio.dsp

/**
 * The real-input forward transform of `fft.js` (indutny/fft.js, MIT, vendored by superdough as `packages/superdough/fft.js`), ported line for line.
 *
 * Why a port and not [cosic.audio.Fft]: the phase vocoder reads this transform's *whole* output buffer, and `fft.js` only computes the
 * first half of the spectrum (bins 0..N/2) properly. Its upper half keeps the intermediate values of an earlier butterfly stage, and the vocoder reads them
 * when it moves peaks down in pitch (`stretch` below 0): the sound of those bins is part of the effect, so they have to be the same.
 * Everything here has the same rounding as the original (double precision, same operation order); the only difference is the twiddle table,
 * which uses `StrictMath` (fdlibm, like V8's `Math.cos`).
 *
 * Output layout: interleaved complex, `out[2 k]` and `out[2 k + 1]` are the real and imaginary part of bin `k`; `out` holds `2 * size` doubles.
 * Stateless after construction, so one instance can serve any number of processors.
 */
internal class FftJs(val size: Int) {
    private val csize = size shl 1
    private val table = DoubleArray(size * 2)
    private val width: Int
    private val bitrev: IntArray

    init {
        require(size > 1 && (size and (size - 1)) == 0) { "FFT size must be a power of two and bigger than 1" }
        var i = 0
        while (i < table.size) {
            val angle = (Math.PI * i) / size
            table[i] = StrictMath.cos(angle)
            table[i + 1] = -StrictMath.sin(angle)
            i += 2
        }
        // Find size's power of two
        var power = 0
        var t = 1
        while (size > t) {
            power++
            t = t shl 1
        }
        // Calculate initial step's width: if we are full radix-4 it is 2x smaller to give initial len=8, otherwise it is the same as `power` to give len=4.
        width = if (power % 2 == 0) power - 1 else power
        // Pre-compute bit-reversal patterns. (For an odd width the top digit shifts by -1, which JavaScript and the JVM both take mod 32;
        // those entries are never read.)
        bitrev = IntArray(1 shl width)
        for (j in bitrev.indices) {
            var v = 0
            var shift = 0
            while (shift < width) {
                val revShift = width - shift - 2
                v = v or (((j ushr shift) and 3) shl revShift)
                shift += 2
            }
            bitrev[j] = v
        }
    }

    /** `realTransform(out, data)`: the forward transform of the real signal [data] (`size` values) into [out] (`2 * size` doubles). */
    fun realTransform(out: DoubleArray, data: FloatArray) {
        // Initial step (permute and transform)
        var step = 1 shl width
        var len = (csize / step) shl 1
        var outOff = 0
        var t = 0
        if (len == 4) {
            while (outOff < csize) {
                val off = bitrev[t]
                singleRealTransform2(out, data, outOff, off ushr 1, step ushr 1)
                outOff += len
                t++
            }
        } else {
            // len == 8
            while (outOff < csize) {
                val off = bitrev[t]
                singleRealTransform4(out, data, outOff, off ushr 1, step ushr 1)
                outOff += len
                t++
            }
        }

        // Loop through steps in decreasing order
        step = step shr 2
        while (step >= 2) {
            len = (csize / step) shl 1
            val halfLen = len ushr 1
            val quarterLen = halfLen ushr 1
            val hquarterLen = quarterLen ushr 1

            // Loop through offsets in the data
            outOff = 0
            while (outOff < csize) {
                var i = 0
                var k = 0
                while (i <= hquarterLen) {
                    val a = outOff + i
                    val b = a + quarterLen
                    val c = b + quarterLen
                    val d = c + quarterLen

                    // Original values
                    val ar = out[a]
                    val ai = out[a + 1]
                    val br = out[b]
                    val bi = out[b + 1]
                    val cr = out[c]
                    val ci = out[c + 1]
                    val dr = out[d]
                    val di = out[d + 1]

                    // Middle values
                    val mar = ar
                    val mai = ai

                    val tableBr = table[k]
                    val tableBi = table[k + 1]
                    val mbr = br * tableBr - bi * tableBi
                    val mbi = br * tableBi + bi * tableBr

                    val tableCr = table[2 * k]
                    val tableCi = table[2 * k + 1]
                    val mcr = cr * tableCr - ci * tableCi
                    val mci = cr * tableCi + ci * tableCr

                    val tableDr = table[3 * k]
                    val tableDi = table[3 * k + 1]
                    val mdr = dr * tableDr - di * tableDi
                    val mdi = dr * tableDi + di * tableDr

                    // Pre-Final values
                    val t0r = mar + mcr
                    val t0i = mai + mci
                    val t1r = mar - mcr
                    val t1i = mai - mci
                    val t2r = mbr + mdr
                    val t2i = mbi + mdi
                    val t3r = mbr - mdr
                    val t3i = mbi - mdi

                    // Final values
                    val far = t0r + t2r
                    val fai = t0i + t2i

                    val fbr = t1r + t3i
                    val fbi = t1i - t3r

                    out[a] = far
                    out[a + 1] = fai
                    out[b] = fbr
                    out[b + 1] = fbi

                    if (i == 0) {
                        // Output final middle point
                        out[c] = t0r - t2r
                        out[c + 1] = t0i - t2i
                    } else if (i != hquarterLen) {
                        // Do not overwrite ourselves. In the flipped case:
                        // MAi = -MAi, MBr = -MBi, MBi = -MBr, MCr = -MCr, MDr = MDi, MDi = MDr
                        val st0r = t1r
                        val st0i = -t1i
                        val st1r = t0r
                        val st1i = -t0i
                        val st2r = -t3i
                        val st2i = -t3r
                        val st3r = -t2i
                        val st3i = -t2r

                        val sfar = st0r + st2r
                        val sfai = st0i + st2i

                        val sfbr = st1r + st3i
                        val sfbi = st1i - st3r

                        val sa = outOff + quarterLen - i
                        val sb = outOff + halfLen - i

                        out[sa] = sfar
                        out[sa + 1] = sfai
                        out[sb] = sfbr
                        out[sb + 1] = sfbi
                    }
                    i += 2
                    k += step
                }
                outOff += len
            }
            step = step shr 2
        }
    }

    /** Radix-2, only called for len = 4. */
    private fun singleRealTransform2(out: DoubleArray, data: FloatArray, outOff: Int, off: Int, step: Int) {
        val evenR = data[off].toDouble()
        val oddR = data[off + step].toDouble()

        val leftR = evenR + oddR
        val rightR = evenR - oddR

        out[outOff] = leftR
        out[outOff + 1] = 0.0
        out[outOff + 2] = rightR
        out[outOff + 3] = 0.0
    }

    /** Radix-4, only called for len = 8. */
    private fun singleRealTransform4(out: DoubleArray, data: FloatArray, outOff: Int, off: Int, step: Int) {
        val step2 = step * 2
        val step3 = step * 3

        // Original values
        val ar = data[off].toDouble()
        val br = data[off + step].toDouble()
        val cr = data[off + step2].toDouble()
        val dr = data[off + step3].toDouble()

        // Pre-Final values
        val t0r = ar + cr
        val t1r = ar - cr
        val t2r = br + dr
        val t3r = br - dr

        // Final values
        val far = t0r + t2r

        val fbr = t1r
        val fbi = -t3r

        val fcr = t0r - t2r

        val fdr = t1r
        val fdi = t3r

        out[outOff] = far
        out[outOff + 1] = 0.0
        out[outOff + 2] = fbr
        out[outOff + 3] = fbi
        out[outOff + 4] = fcr
        out[outOff + 5] = 0.0
        out[outOff + 6] = fdr
        out[outOff + 7] = fdi
    }
}
