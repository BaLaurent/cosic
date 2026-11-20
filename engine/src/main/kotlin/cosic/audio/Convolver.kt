package cosic.audio

/** The direct (time-domain) part of the impulse response, and the partition length of the first tier. */
private const val HEAD = 128

/** The first tier covers the impulse response from [HEAD] to here, in partitions of [HEAD] frames; the second tier covers the rest. */
private const val TIER1_END = 2048

/** Partition length of the second tier. */
private const val BLOCK2 = 2048

/** The second tier's multiply-and-add work is spread over this many [HEAD]-frame steps of each of its blocks. */
private const val SLICES = BLOCK2 / HEAD

/** Input history: more than the 4096 frames the second tier's window needs. A power of two. */
private const val RING = 8192
private const val RING_MASK = RING - 1

/**
 * An impulse response ready to convolve with: its direct head and the spectra of its partitions. Immutable, built off the audio thread by [prepare], and shared by
 * any number of [Convolver]s. [channels] is 1 or 2; a mono response is used for both channels.
 */
internal class PreparedImpulse(
    val length: Int,
    val channels: Int,
    /** The first [HEAD] taps of each channel, reversed (`head[c][HEAD - 1 - m]` is tap `m`), already scaled. */
    val headLeft: FloatArray,
    val headRight: FloatArray,
    val tier1Left: Array<FloatArray>,
    val tier1Right: Array<FloatArray>,
    val tier2Left: Array<FloatArray>,
    val tier2Right: Array<FloatArray>,
) {
    /** Memory held by the spectra, in bytes; what a long room costs. */
    val bytes: Long
        get() {
            var floats = headLeft.size.toLong() * 2
            for (a in tier1Left) floats += a.size * 2L
            for (a in tier2Left) floats += a.size * 2L
            return floats * 4
        }

    companion object {
        /**
         * Builds the partitions of [ir] after Blink's `ConvolverNode`: unless [normalize] is false the response is scaled by `1 / rms * 10^(-58 / 20) *
         * (44100 / sampleRate)` (`Reverb::CalculateNormalizationScale`: the rms over all channels, at least 0.000125, half of that for four channels). Only
         * the first two channels are used. A response of one channel serves both.
         */
        fun prepare(ir: ImpulseResponse, normalize: Boolean = true): PreparedImpulse {
            val used = Math.min(ir.channels.size, 2)
            val scale = if (normalize) normalizationScale(ir) else 1.0 // over the whole buffer, trailing silence included, as Blink does
            val length = significantLength(ir, used)
            val left = ir.channels[0]
            val right = if (used == 2) ir.channels[1] else left

            val headLeft = reversedHead(left, scale)
            val headRight = if (used == 2) reversedHead(right, scale) else headLeft

            val tier1Count = if (length > HEAD) (Math.min(length, TIER1_END) - HEAD + HEAD - 1) / HEAD else 0
            val tier2Count = if (length > TIER1_END) (length - TIER1_END + BLOCK2 - 1) / BLOCK2 else 0
            val tier1Left = Array(tier1Count) { FloatArray(0) }
            val tier1Right = Array(tier1Count) { FloatArray(0) }
            val tier2Left = Array(tier2Count) { FloatArray(0) }
            val tier2Right = Array(tier2Count) { FloatArray(0) }
            fillSpectra(left, if (used == 2) right else null, scale, HEAD, HEAD, tier1Left, tier1Right)
            fillSpectra(left, if (used == 2) right else null, scale, TIER1_END, BLOCK2, tier2Left, tier2Right)
            return PreparedImpulse(length, used, headLeft, headRight, tier1Left, tier1Right, tier2Left, tier2Right)
        }

        /** The length up to the last frame of the first [used] channels that is not exactly 0 (1 for a response of nothing but zeros): what the convolution has to cover. */
        private fun significantLength(ir: ImpulseResponse, used: Int): Int {
            var end = 0
            for (c in 0 until used) {
                val channel = ir.channels[c]
                var i = channel.size - 1
                while (i >= end && channel[i] == 0f) i--
                if (i + 1 > end) end = i + 1
            }
            return Math.max(end, 1)
        }

        /** `CalculateNormalizationScale` of Blink's reverb.cc. */
        fun normalizationScale(ir: ImpulseResponse): Double {
            var energy = 0.0
            for (channel in ir.channels) for (v in channel) energy += v.toDouble() * v
            var power = Math.sqrt(energy / (ir.channels.size.toDouble() * ir.length))
            if (!(power >= MIN_POWER)) power = MIN_POWER // also catches NaN
            var scale = 1.0 / power
            scale *= Math.pow(10.0, GAIN_CALIBRATION_DB / 20.0)
            scale *= CALIBRATION_SAMPLE_RATE / ir.sampleRate
            if (ir.channels.size == 4) scale *= 0.5
            return scale
        }

        private const val MIN_POWER = 0.000125
        private const val GAIN_CALIBRATION_DB = -58.0
        private const val CALIBRATION_SAMPLE_RATE = 44100.0

        private fun reversedHead(h: FloatArray, scale: Double): FloatArray {
            val taps = FloatArray(HEAD)
            for (m in 0 until Math.min(HEAD, h.size)) taps[HEAD - 1 - m] = (h[m] * scale).toFloat()
            return taps
        }

        /**
         * The spectra (bins `0..block`, interleaved real and imaginary parts) of the partitions of length [block] of the taps from [from] on, one array of
         * [outLeft] and one of [outRight] per partition; for a mono response [right] is null and both lists get the same arrays. Two real
         * transforms are made by one complex one.
         */
        private fun fillSpectra(
            left: FloatArray, right: FloatArray?, scale: Double, from: Int, block: Int, outLeft: Array<FloatArray>, outRight: Array<FloatArray>,
        ) {
            if (outLeft.isEmpty()) return
            val size = 2 * block
            val fft = Fft(size)
            val re = DoubleArray(size)
            val im = DoubleArray(size)
            for (p in outLeft.indices) {
                val begin = from + p * block
                re.fill(0.0)
                im.fill(0.0)
                for (k in 0 until block) {
                    val m = begin + k
                    if (m >= left.size) break
                    re[k] = left[m] * scale
                    if (right != null) im[k] = right[m] * scale
                }
                fft.forward(re, im)
                val bins = block + 1
                val l = FloatArray(2 * bins)
                if (right == null) {
                    for (k in 0 until bins) {
                        l[2 * k] = re[k].toFloat()
                        l[2 * k + 1] = im[k].toFloat()
                    }
                    outLeft[p] = l
                    outRight[p] = l
                } else {
                    val r = FloatArray(2 * bins)
                    for (k in 0 until bins) {
                        val j = (size - k) and (size - 1)
                        l[2 * k] = ((re[k] + re[j]) * 0.5).toFloat()
                        l[2 * k + 1] = ((im[k] - im[j]) * 0.5).toFloat()
                        r[2 * k] = ((im[k] + im[j]) * 0.5).toFloat()
                        r[2 * k + 1] = ((re[j] - re[k]) * 0.5).toFloat()
                    }
                    outLeft[p] = l
                    outRight[p] = r
                }
            }
        }
    }
}

/**
 * Zero-latency convolution of a stereo signal with a [PreparedImpulse], the way Web Audio's `ConvolverNode` does it: the left channel with the response's first
 * channel, the right channel with its second (a mono response serves both). Mono input is the same array twice. The result is exactly the direct
 * convolution (up to float32 rounding) at a cost that grows with the length of the response only logarithmically per frame.
 *
 * Layout, three parts that add up to the whole response `h`:
 *  - frames `[0, 128)`: a direct 128-tap filter of the input history, run frame by frame;
 *  - frames `[128, 2048)`: 15 partitions of 128 frames. Every 128 input frames the spectrum of the last 256 (overlap-save) goes into a ring, is multiplied with the
 *    partitions' spectra, summed, and transformed back; half of it is the next 128 output frames that depend on past input only;
 *  - frames from 2048 on: partitions of 2048 frames and 4096-point transforms, the same way every 2048 input frames. A partition that is not the newest only
 *    needs input that is at least a whole block old, so its multiplications are done a sixteenth at a time over the block before (at every 128-frame
 *    step), leaving one partition and the two transforms for the block's own boundary: the work per audio callback stays flat however long the room is.
 * Two real signals share each complex transform (left in the real part, right in the imaginary part).
 *
 * The state is [RING] frames of input history plus one spectrum per partition; build one per room, [reset] it to make it silent again. Never allocates.
 */
internal class Convolver(private val ir: PreparedImpulse) {
    private val historyL = FloatArray(RING)
    private val historyR = FloatArray(RING)
    private var position = 0L
    private val window = FloatArray(HEAD - 1 + HEAD)
    private val windowR = FloatArray(HEAD - 1 + HEAD)
    private val tier1 = if (ir.tier1Left.isNotEmpty()) Tier(HEAD, ir.tier1Left, ir.tier1Right) else null
    private val tier2 = if (ir.tier2Left.isNotEmpty()) Tier(BLOCK2, ir.tier2Left, ir.tier2Right) else null

    /** Forgets the input so far: the next frames are convolved as if the signal began with them (what `ConvolverNode.buffer =` does). */
    fun reset() {
        clearState()
        position = 0
        lastActive = Long.MIN_VALUE / 2
        isIdle = true
    }

    private fun clearState() {
        historyL.fill(0f)
        historyR.fill(0f)
        tier1?.reset()
        tier2?.reset()
    }

    /** The position after the last frame of input that was not exactly 0. */
    private var lastActive = Long.MIN_VALUE / 2

    /**
     * True while the convolver has nothing in it (it was reset, or its input has been 0 for longer than the response) and its input is 0: [process] then writes
     * zeros and does no work (which is exactly what it would compute). A frame of sound ends it. The frame counter goes on, so the output never depends on
     * when the convolver idled or how the blocks were cut.
     */
    var isIdle = true
        private set

    /**
     * Convolves the first [n] frames of [inL] and [inR] into [outL] and [outR] (which must be other arrays than the inputs): output frame `i` is
     * `sum(h[m] * x[i - m])` over all earlier input, this call's frames up to `i` included.
     */
    fun process(inL: FloatArray, inR: FloatArray, outL: FloatArray, outR: FloatArray, n: Int) {
        var done = 0
        while (done < n) {
            val phase = (position and (HEAD - 1).toLong()).toInt()
            val k = Math.min(n - done, HEAD - phase)
            var active = false
            for (i in 0 until k) {
                if (inL[done + i] != 0f || inR[done + i] != 0f) {
                    active = true
                    lastActive = position + i + 1
                }
            }
            if (isIdle && !active) {
                java.util.Arrays.fill(outL, done, done + k, 0f)
                java.util.Arrays.fill(outR, done, done + k, 0f)
                position += k
                done += k
                continue
            }
            isIdle = false
            for (i in 0 until k) {
                val at = ((position + i) and RING_MASK.toLong()).toInt()
                historyL[at] = inL[done + i]
                historyR[at] = inR[done + i]
            }
            // the last 127 frames before this piece and the piece itself, contiguous
            val first = position - (HEAD - 1)
            for (j in 0 until HEAD - 1 + k) {
                val at = ((first + j) and RING_MASK.toLong()).toInt()
                window[j] = historyL[at]
                windowR[j] = historyR[at]
            }
            val tapsL = ir.headLeft
            val tapsR = ir.headRight
            val out1L = tier1?.outLeft
            val out1R = tier1?.outRight
            val out2L = tier2?.outLeft
            val out2R = tier2?.outRight
            for (i in 0 until k) {
                var l0 = 0f
                var l1 = 0f
                var r0 = 0f
                var r1 = 0f
                var j = 0
                while (j < HEAD) {
                    l0 += tapsL[j] * window[i + j] + tapsL[j + 2] * window[i + j + 2]
                    l1 += tapsL[j + 1] * window[i + j + 1] + tapsL[j + 3] * window[i + j + 3]
                    r0 += tapsR[j] * windowR[i + j] + tapsR[j + 2] * windowR[i + j + 2]
                    r1 += tapsR[j + 1] * windowR[i + j + 1] + tapsR[j + 3] * windowR[i + j + 3]
                    j += 4
                }
                var yl = l0 + l1
                var yr = r0 + r1
                val at1 = (position + i).toInt() and (HEAD - 1)
                if (out1L != null) {
                    yl += out1L[at1]
                    yr += out1R!![at1]
                }
                if (out2L != null) {
                    val at2 = (position + i).toInt() and (BLOCK2 - 1)
                    yl += out2L[at2]
                    yr += out2R!![at2]
                }
                outL[done + i] = yl + 0f // + 0 turns a -0 into 0, so that an output of exact zeros looks the same whether it was computed or skipped
                outR[done + i] = yr + 0f
            }
            position += k
            done += k
            if ((position and (HEAD - 1).toLong()) == 0L) {
                step()
                if (position - lastActive >= ir.length + 2L * BLOCK2) {
                    // every frame of the input that the response still reaches was 0: all the state is 0 already, and the output stays 0 until there is sound again
                    clearState()
                    isIdle = true
                }
            }
        }
    }

    /** The work at the end of every [HEAD] frames of input. */
    private fun step() {
        val t1 = tier1
        if (t1 != null) {
            t1.push(historyL, historyR, position)
            t1.clearAccumulators()
            t1.accumulate(0, t1.partitions, 0)
            t1.finish()
        }
        val t2 = tier2
        if (t2 != null) {
            val slice = ((position / HEAD) % SLICES).toInt()
            if (slice == 0) {
                // a block of 2048 is complete: its spectrum, the newest partition, and the sum for the next 2048 output frames is done
                t2.push(historyL, historyR, position)
                t2.accumulate(0, 1, 0)
                t2.finish()
                t2.clearAccumulators()
            }
            // the partitions that need nothing newer than this block, for the block after it
            val others = t2.partitions - 1
            if (others > 0) {
                val per = (others + SLICES - 1) / SLICES
                val from = 1 + slice * per
                val to = Math.min(t2.partitions, from + per)
                if (from < to) t2.accumulate(from, to, 1)
            }
        }
    }
}

/**
 * One tier of the partitioned convolution: partitions of [block] frames, transforms of `2 * block` points. Keeps the spectra of the last `partitions` input
 * windows (the frequency-domain delay line), accumulates their products with the partitions' spectra, and turns the sum into [block] output frames.
 */
private class Tier(val block: Int, private val spectraLeft: Array<FloatArray>, private val spectraRight: Array<FloatArray>) {
    val partitions = spectraLeft.size
    private val size = 2 * block
    private val bins = block + 1
    private val fft = Fft(size)
    private val re = DoubleArray(size)
    private val im = DoubleArray(size)
    private val delayLeft = Array(partitions) { FloatArray(2 * bins) }
    private val delayRight = Array(partitions) { FloatArray(2 * bins) }
    private val accLeft = DoubleArray(2 * bins)
    private val accRight = DoubleArray(2 * bins)
    private var newest = 0

    /** The next [block] output frames. */
    val outLeft = FloatArray(block)
    val outRight = FloatArray(block)

    fun reset() {
        for (a in delayLeft) a.fill(0f)
        for (a in delayRight) a.fill(0f)
        accLeft.fill(0.0)
        accRight.fill(0.0)
        outLeft.fill(0f)
        outRight.fill(0f)
        newest = 0
    }

    /** Transforms the last `2 * block` input frames before frame [end] and makes them the newest entry of the delay line. */
    fun push(historyL: FloatArray, historyR: FloatArray, end: Long) {
        val first = end - size
        for (k in 0 until size) {
            val at = ((first + k) and RING_MASK.toLong()).toInt()
            re[k] = historyL[at].toDouble()
            im[k] = historyR[at].toDouble()
        }
        fft.forward(re, im)
        newest = if (newest + 1 == partitions) 0 else newest + 1
        val l = delayLeft[newest]
        val r = delayRight[newest]
        for (k in 0 until bins) {
            val j = (size - k) and (size - 1)
            l[2 * k] = ((re[k] + re[j]) * 0.5).toFloat()
            l[2 * k + 1] = ((im[k] - im[j]) * 0.5).toFloat()
            r[2 * k] = ((im[k] + im[j]) * 0.5).toFloat()
            r[2 * k + 1] = ((re[j] - re[k]) * 0.5).toFloat()
        }
    }

    fun clearAccumulators() {
        accLeft.fill(0.0)
        accRight.fill(0.0)
    }

    /**
     * Adds the products of partitions `[from, to)` with the delay-line entries they pair with: partition `p` pairs with the entry `p - behind` places before the newest
     * (`behind` is 1 while the newest entry is the one of the block that has just been summed, 0 when it is the block the sum is for).
     */
    fun accumulate(from: Int, to: Int, behind: Int) {
        for (p in from until to) {
            var slot = newest - (p - behind)
            while (slot < 0) slot += partitions
            val xl = delayLeft[slot]
            val xr = delayRight[slot]
            val hl = spectraLeft[p]
            val hr = spectraRight[p]
            var k = 0
            val end = 2 * bins
            while (k < end) {
                val a = xl[k].toDouble()
                val b = xl[k + 1].toDouble()
                val c = hl[k].toDouble()
                val d = hl[k + 1].toDouble()
                accLeft[k] += a * c - b * d
                accLeft[k + 1] += a * d + b * c
                val e = xr[k].toDouble()
                val f = xr[k + 1].toDouble()
                val g = hr[k].toDouble()
                val h = hr[k + 1].toDouble()
                accRight[k] += e * g - f * h
                accRight[k + 1] += e * h + f * g
                k += 2
            }
        }
    }

    /** Turns the accumulated spectra into the output: the second half of the inverse transform (the first half is the overlap that is thrown away). */
    fun finish() {
        for (k in 0 until bins) {
            val lr = accLeft[2 * k]
            val li = accLeft[2 * k + 1]
            val rr = accRight[2 * k]
            val ri = accRight[2 * k + 1]
            // Y = Y_L + i Y_R, bin k; the mirrored bin is the conjugates (both signals are real)
            re[k] = lr - ri
            im[k] = li + rr
            if (k in 1 until block) {
                val j = size - k
                re[j] = lr + ri
                im[j] = -li + rr
            }
        }
        fft.inverse(re, im)
        for (i in 0 until block) {
            outLeft[i] = re[block + i].toFloat()
            outRight[i] = im[block + i].toFloat()
        }
    }
}
