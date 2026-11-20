package cosic.audio.dsp

import cosic.audio.Fft
import cosic.audio.QUANTUM

/**
 * The `phase-vocoder-processor` worklet behind `stretch` (superdough `worklets.mjs:580-748` on the OLA base class `ola-processor.js`, both adapted
 * there from olvb/phaze): a pitch shifter that moves spectral peaks. `stretch` s is the AudioParam `pitchFactor`; the worklet turns it into
 * `pf = max(0, (s < 0 ? s / 4 : s) + 1)`, so 0 leaves the pitch alone, 1 is an octave up, -2 an octave down.
 *
 * What one render quantum (128 frames) does, per channel: slide the last 2048 input samples left by 128 and append the new quantum; multiply them by a
 * periodic Hann window times 1.62; real FFT; squared magnitudes (rounded to float32); peaks are bins `2..1022` that are strictly larger than the two bins on each
 * side (after a peak the search skips 2 bins); every peak and its region of influence (half way to the neighbouring peaks, `ceil` taken as `floor(x + 1)`)
 * moves to `round(peak * pf)` with a phase rotation of `2 pi (shifted - peak) / 2048` per bin and per hop (`timeCursor`, which grows by 128 per quantum); mirror
 * the spectrum; inverse FFT; window again; add 1/16 of it into the overlap-add buffer, hand out its first 128 samples and slide it left by 128.
 * The 16-fold overlap and the two windows give a gain of `1.62^2 * 6 / 16 = 0.984` and a latency of 1920 frames.
 *
 * Two behaviours of the original are kept deliberately: the forward transform is the `fft.js` one ([FftJs]), so the spectrum bins above Nyquist that the
 * region copy reads when it moves peaks down hold that transform's leftovers, and the magnitudes are float32.
 *
 * Two ways to run it:
 *  * [processQuantum] is the worklet's `process()`: exactly one aligned quantum in, one out. It is what the golden vectors test.
 *  * [process] is the streaming form a voice chain needs: any number of frames at any alignment, keyed on the absolute frame counter so the 128-frame grid
 *    stays that of the engine. A quantum can only be processed once all of its input exists, so the output of quantum q is handed out during quantum q + 1: the
 *    output is the worklet's, delayed by 128 frames ([STREAM_LATENCY_FRAMES] = 1920 + 128 in all), independent of how the calls are split.
 *
 * One instance is about 150 KB of buffers (the tables are shared), which is why the chain keeps a small pool of them (the plan caps concurrent stretch voices at 8).
 */
internal class PhaseVocoder {
    // Sliding window of the last BLOCK input samples, and the overlap-add output, per channel.
    private val history = Array(2) { FloatArray(BLOCK) }
    private val accumulator = Array(2) { FloatArray(BLOCK) }

    // Scratch, shared by the channels (they are processed one after the other).
    private val windowed = FloatArray(BLOCK)
    private val retrieved = FloatArray(BLOCK)
    private val spectrum = DoubleArray(2 * BLOCK)
    private val shifted = DoubleArray(2 * BLOCK)
    private val inverseRe = DoubleArray(BLOCK)
    private val inverseIm = DoubleArray(BLOCK)
    private val magnitudes = FloatArray(BLOCK / 2 + 1)
    private val peakIndexes = IntArray(BLOCK / 2 + 1)
    private var peakCount = 0

    /** `timeCursor` of the worklet: the number of frames processed so far, which turns the phase rotation of the moved peaks from hop to hop. */
    private var timeCursor = 0.0

    // Streaming state: the quantum being collected and the finished one being handed out.
    private val pending = Array(2) { FloatArray(QUANTUM) }
    private val finished = Array(2) { FloatArray(QUANTUM) }
    private var hasFinished = false
    private var started = false
    private var nextFrame = 0L

    /** Back to a fresh processor. */
    fun reset() {
        for (ch in 0 until 2) {
            history[ch].fill(0f)
            accumulator[ch].fill(0f)
            pending[ch].fill(0f)
            finished[ch].fill(0f)
        }
        timeCursor = 0.0
        hasFinished = false
        started = false
        nextFrame = 0L
    }

    /**
     * One render quantum, as the worklet's `process()`: the first [QUANTUM] samples of [inLeft] (and [inRight], when stereo) in, the first [QUANTUM] of [outLeft]
     * (and [outRight]) out. Output is the input 1920 frames ago, moved in pitch. [stretch] is the `pitchFactor` AudioParam (the worklet reads its last value of the quantum).
     */
    fun processQuantum(inLeft: FloatArray, inRight: FloatArray?, outLeft: FloatArray, outRight: FloatArray?, stretch: Double) {
        var pitchFactor = if (stretch.isNaN()) 0.0 else stretch
        if (pitchFactor < 0) pitchFactor *= 0.25
        pitchFactor = Math.max(0.0, pitchFactor + 1)
        channel(0, inLeft, outLeft, pitchFactor)
        if (inRight != null && outRight != null) channel(1, inRight, outRight, pitchFactor)
        timeCursor += HOP
    }

    /**
     * Pitch-shifts frames `[from, to)` of [left] (and [right]) in place. [startFrame] is the absolute frame of index 0 of the arrays; calls must follow each
     * other without a gap (a gap restarts the processor). The first call may start anywhere: what precedes it in its quantum is silence.
     * [stretch] is the `stretch` control; the value of the call that completes a quantum is the one that quantum is processed with.
     */
    fun process(left: FloatArray, right: FloatArray?, from: Int, to: Int, startFrame: Long, stretch: Double) {
        if (to <= from) return
        if (started && startFrame + from != nextFrame) reset()
        started = true
        val pendingLeft = pending[0]
        val finishedLeft = finished[0]
        val pendingRight = if (right != null) pending[1] else null
        val finishedRight = if (right != null) finished[1] else null
        for (i in from until to) {
            val position = ((startFrame + i) % QUANTUM).toInt()
            pendingLeft[position] = left[i]
            left[i] = if (hasFinished) finishedLeft[position] else 0f
            if (right != null && pendingRight != null && finishedRight != null) {
                pendingRight[position] = right[i]
                right[i] = if (hasFinished) finishedRight[position] else 0f
            }
            if (position == QUANTUM - 1) {
                // The quantum is complete (the output of the previous one has just been handed out in full).
                processQuantum(pendingLeft, pendingRight, finishedLeft, finishedRight, stretch)
                hasFinished = true
            }
        }
        nextFrame = startFrame + to
    }

    private fun channel(ch: Int, input: FloatArray, output: FloatArray, pitchFactor: Double) {
        // readInputs / shiftInputBuffers / prepareInputBuffersToSend: the window slides by one hop and the new quantum is its tail.
        val h = history[ch]
        System.arraycopy(h, HOP, h, 0, BLOCK - HOP)
        for (i in 0 until HOP) {
            val v = input[i]
            h[BLOCK - HOP + i] = if (v - v == 0f) v else 0f // NaN and infinity would poison the next 16 windows
        }
        System.arraycopy(h, 0, windowed, 0, BLOCK)

        // processOLA
        applyHannWindow(windowed)
        FFT_JS.realTransform(spectrum, windowed)
        computeMagnitudes()
        findPeaks()
        shiftPeaks(pitchFactor)
        completeSpectrum()
        for (k in 0 until BLOCK) {
            inverseRe[k] = shifted[2 * k]
            inverseIm[k] = shifted[2 * k + 1]
        }
        INVERSE_FFT.inverse(inverseRe, inverseIm)
        for (k in 0 until BLOCK) retrieved[k] = inverseRe[k].toFloat() // fromComplexArray: the real parts, as float32
        applyHannWindow(retrieved)

        // handleOutputBuffersToRetrieve / writeOutputs / shiftOutputBuffers
        val acc = accumulator[ch]
        for (k in 0 until BLOCK) acc[k] = (acc[k].toDouble() + retrieved[k].toDouble() / OVERLAPS).toFloat()
        System.arraycopy(acc, 0, output, 0, HOP)
        System.arraycopy(acc, HOP, acc, 0, BLOCK - HOP)
        acc.fill(0f, BLOCK - HOP, BLOCK)
    }

    /** `input[i] *= hann[i] * 1.62`, in float32. */
    private fun applyHannWindow(data: FloatArray) {
        for (i in 0 until BLOCK) data[i] = (data[i] * HANN_GAIN[i]).toFloat()
    }

    /** Squared magnitudes (no square root, peaks do not need one) of bins `0..BLOCK/2`, stored as float32. */
    private fun computeMagnitudes() {
        for (i in magnitudes.indices) {
            val real = spectrum[2 * i]
            val imag = spectrum[2 * i + 1]
            magnitudes[i] = (real * real + imag * imag).toFloat()
        }
    }

    /** Bins that beat their two neighbours on each side; after a peak the search continues two bins further. */
    private fun findPeaks() {
        peakCount = 0
        var i = 2
        val end = magnitudes.size - 2
        while (i < end) {
            val mag = magnitudes[i]
            if (magnitudes[i - 1] >= mag || magnitudes[i - 2] >= mag) {
                i++
                continue
            }
            if (magnitudes[i + 1] >= mag || magnitudes[i + 2] >= mag) {
                i++
                continue
            }
            peakIndexes[peakCount] = i
            peakCount++
            i += 2
        }
    }

    /** Moves every peak and the bins around it by [pitchFactor] into a new, zero-filled spectrum. */
    private fun shiftPeaks(pitchFactor: Double) {
        shifted.fill(0.0)
        val bins = magnitudes.size
        for (i in 0 until peakCount) {
            val peakIndex = peakIndexes[i]
            val peakIndexShifted = fround(peakIndex * pitchFactor)
            if (peakIndexShifted > bins) break
            // find region of influence
            var startIndex = 0
            var endIndex = BLOCK
            if (i > 0) startIndex = peakIndex - fround((peakIndex - peakIndexes[i - 1]) / 2.0)
            if (i < peakCount - 1) endIndex = peakIndex + fceil((peakIndexes[i + 1] - peakIndex) / 2.0)
            // shift the whole region of influence around the peak to the shifted peak
            val startOffset = startIndex - peakIndex
            val endOffset = endIndex - peakIndex
            val omegaDelta = TWO_PI * INV_BLOCK * (peakIndexShifted - peakIndex)
            val phaseShiftReal = Math.cos(omegaDelta * timeCursor)
            val phaseShiftImag = Math.sin(omegaDelta * timeCursor)
            for (j in startOffset until endOffset) {
                val binIndex = peakIndex + j
                val binIndexShifted = peakIndexShifted + j
                if (binIndexShifted >= bins) break
                // A bin below 0 is a write to a non-element property of the JavaScript array: it goes nowhere.
                if (binIndexShifted < 0) continue
                // apply phase correction
                val valueReal = spectrum[2 * binIndex]
                val valueImag = spectrum[2 * binIndex + 1]
                val shiftedReal = valueReal * phaseShiftReal - valueImag * phaseShiftImag
                val shiftedImag = valueReal * phaseShiftImag + valueImag * phaseShiftReal
                shifted[2 * binIndexShifted] += shiftedReal
                shifted[2 * binIndexShifted + 1] += shiftedImag
            }
        }
    }

    /** `fft.completeSpectrum`: bins `N/2 + 1 .. N - 1` become the conjugates of bins `N/2 - 1 .. 1`. */
    private fun completeSpectrum() {
        val size = 2 * BLOCK
        val half = size ushr 1
        var i = 2
        while (i < half) {
            shifted[size - i] = shifted[i]
            shifted[size - i + 1] = -shifted[i + 1]
            i += 2
        }
    }

    companion object {
        /** The OLA block (and FFT) size: `BUFFERED_BLOCK_SIZE`. */
        const val BLOCK = 2048

        /** The hop: one Web Audio render quantum. */
        const val HOP = QUANTUM

        /** 16 windows overlap; the worklet divides each by this before adding it up. */
        const val OVERLAPS = BLOCK / HOP

        /** The latency of the worklet in frames: `BLOCK - HOP`, which is 40 ms at 48 kHz (superdough starts the source `0.04` s earlier when `stretch` is set). */
        const val LATENCY_FRAMES = BLOCK - HOP

        /** The latency of [process]: the worklet's plus one quantum, because the output of a quantum is handed out in the next one. */
        const val STREAM_LATENCY_FRAMES = BLOCK

        private const val TWO_PI = 2.0 * Math.PI
        private const val INV_BLOCK = 1.0 / BLOCK

        private val FFT_JS = FftJs(BLOCK)
        private val INVERSE_FFT = Fft(BLOCK)

        /** `hann[i] * 1.62`, with the Hann window stored as float32 as the worklet's `Float32Array` does. */
        private val HANN_GAIN = DoubleArray(BLOCK) { i ->
            (0.5 * (1 - StrictMath.cos((TWO_PI * i) / BLOCK))).toFloat().toDouble() * 1.62
        }

        /** `ffloor(x + 0.5)` with `ffloor = x | 0` (truncation): for the non-negative values used here, round half up. */
        private fun fround(x: Double): Int = (x + 0.5).toInt()

        /** The worklet's `fceil(x) = ffloor(x + 1)`: one more than the floor, also for whole numbers. */
        private fun fceil(x: Double): Int = (x + 1).toInt()
    }
}
