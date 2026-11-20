package cosic.visual

import cosic.audio.Fft
import cosic.io.ScopeTaps

/**
 * The part of Web Audio's `AnalyserNode` that `scope`, `fscope` and `spectrum` read (superdough's `getAnalyserById` and `getAnalyzerData`): the sound of the voices
 * with one `analyze` id, taken from the [ScopeTaps] of the engine, as the waveform of the latest [fftSize] samples or as the level of each frequency in decibels.
 * Superdough makes the node with `fftSize = 2^(fft + 5)` (8192 for the default `fft` of 8) and `smoothingTimeConstant = 0.5`; both reads give
 * [frequencyBinCount] = `fftSize / 2` values.
 *
 * Time data is the *oldest* [frequencyBinCount] of the latest [fftSize] samples (`getFloatTimeDomainData` copies from the start of the block into an array that
 * is half as long). Frequency data is the Blackman-windowed transform of the latest [fftSize] samples, scaled by `1 / fftSize`, smoothed across reads with the
 * time constant, in decibels (`-Infinity` for silence). Each [frequency] call is one step of the smoothing, as each read of Chromium's node is.
 */
internal class Analyser(val fftSize: Int, private val smoothingTimeConstant: Double = 0.5) {
    init {
        require(fftSize in MIN_FFT_SIZE..MAX_FFT_SIZE && fftSize and (fftSize - 1) == 0) { "fftSize must be a power of two from $MIN_FFT_SIZE to $MAX_FFT_SIZE" }
    }

    val frequencyBinCount: Int = fftSize / 2

    private val fft = Fft(fftSize)
    private val window = DoubleArray(fftSize) { n ->
        // Blackman window, alpha 0.16: 0.42 - 0.5 cos(2 pi n / N) + 0.08 cos(4 pi n / N)
        val a = 0.16
        (1 - a) / 2 - 0.5 * Math.cos(2 * Math.PI * n / fftSize) + (a / 2) * Math.cos(4 * Math.PI * n / fftSize)
    }
    private val samples = FloatArray(fftSize)
    private val re = DoubleArray(fftSize)
    private val im = DoubleArray(fftSize)
    private val smoothed = DoubleArray(frequencyBinCount)

    /** Copies the waveform into [out] (at least [frequencyBinCount] long) and answers true, or answers false while no sound with [id] has played. */
    fun timeDomain(taps: ScopeTaps, id: String, out: FloatArray): Boolean {
        if (!taps.read(id, fftSize, samples)) return false
        System.arraycopy(samples, 0, out, 0, frequencyBinCount)
        return true
    }

    /** Takes one step of the analysis of the sound of [id] and copies the decibels of the bins into [out]; false while no sound with [id] has played. */
    fun frequency(taps: ScopeTaps, id: String, out: FloatArray): Boolean {
        if (!taps.read(id, fftSize, samples)) return false
        transform(samples)
        copyDecibels(out)
        return true
    }

    /** One step of the analysis of the block [block] (the latest [fftSize] samples, oldest first), the same as [frequency] does with what the taps hold. For tests. */
    fun frequencyOf(block: FloatArray, out: FloatArray) {
        require(block.size >= fftSize) { "a block of $fftSize samples is needed" }
        transform(block)
        copyDecibels(out)
    }

    private fun transform(block: FloatArray) {
        for (n in 0 until fftSize) {
            re[n] = block[n] * window[n]
            im[n] = 0.0
        }
        fft.forward(re, im)
        val scale = 1.0 / fftSize
        val tau = smoothingTimeConstant
        for (k in 0 until frequencyBinCount) {
            val magnitude = Math.hypot(re[k], im[k]) * scale
            var value = tau * smoothed[k] + (1 - tau) * magnitude
            if (value.isNaN() || value.isInfinite()) value = 0.0
            smoothed[k] = value
        }
    }

    private fun copyDecibels(out: FloatArray) {
        for (k in 0 until frequencyBinCount) out[k] = (20 * Math.log10(smoothed[k])).toFloat()
    }

    companion object {
        const val MIN_FFT_SIZE = 32
        const val MAX_FFT_SIZE = 32768

        /** The size of the analyser for the `fft` control of a hap: `2^(fft + 5)`, with `fft` held to the sizes a node accepts (0 to 10). */
        fun sizeOf(fft: Double): Int {
            val exponent = if (fft.isNaN()) 8 else Math.floor(fft).toInt().coerceIn(0, 10)
            return 1 shl (exponent + 5)
        }
    }
}
