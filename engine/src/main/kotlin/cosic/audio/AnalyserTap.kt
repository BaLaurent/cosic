package cosic.audio

import java.util.concurrent.atomic.AtomicLong

/** Blink's input buffer: the most recent 2^16 frames, of which an analyser looks at its `fftSize`. */
private const val RING_SIZE = 65536
private const val RING_MASK = RING_SIZE - 1

/** `AnalyserNode.smoothingTimeConstant` of `getAnalyserById` (superdough.mjs). */
private const val SMOOTHING = 0.5

/** Blink's `Blackman` window parameter. */
private const val ALPHA = 0.16

/**
 * One analyser of superdough (`getAnalyserById(id, 2 ** (fft + 5))`): an `AnalyserNode` of [fftSize] frames that every voice with `analyze: id` feeds from its
 * `post` node, which the scope and spectrum visuals read. Down-mixes to mono as Web Audio does (`(L + R) / 2`; the voices' sum is the same whichever side it is taken on),
 * and keeps the last 65536 frames of that signal.
 *
 * Threads: the audio thread calls [add] for each voice and [commit] at the end of every block (so the signal advances in time even when nobody sends: the node keeps
 * reading silence), and never waits. Any other thread may call [timeDomain] and [frequencyDb] (readers lock only against each other): the ring is read optimistically
 * and the copy is repeated if the writer has caught up with it, which takes over half a second of audio.
 *
 * [timeDomain] is `getFloatTimeDomainData` and [frequencyDb] `getFloatFrequencyData`, which does what Blink's `RealtimeAnalyser` does: the newest [fftSize] frames are
 * weighted by a Blackman window (`alpha = 0.16`), transformed, scaled by `1 / fftSize`, averaged with the previous result per bin (`0.5 * old + 0.5 * new`, once
 * per call that finds new audio, so the smoothing depends on how often the data is asked for) and converted to decibels (0 is -1000 dB).
 */
internal class AnalyserTap(val id: Int, fftSize: Int) {
    private val ring = FloatArray(RING_SIZE)
    private val sum = FloatArray(BLOCK_FRAMES)
    private val written = AtomicLong(0L)

    /** The size of the analysis in frames, `2^(fft + 5)`; the number of frequency bins is half of it. May be changed from the audio thread ([resize]). */
    @Volatile
    var fftSize: Int = checkSize(fftSize)
        private set

    // reader state, guarded by `this`
    private var size = 0
    private var magnitude = DoubleArray(0)
    private var window = DoubleArray(0)
    private var fft: Fft? = null
    private var re = DoubleArray(0)
    private var im = DoubleArray(0)
    private var analysedAt = -1L

    /** Adds the first [n] frames of the voice's block to this block's signal (audio thread). */
    fun add(b: AudioBlock, n: Int) {
        if (b.channels == 2) {
            for (i in 0 until n) sum[i] += (b.l[i] + b.r[i]) * 0.5f
        } else {
            for (i in 0 until n) sum[i] += b.l[i]
        }
    }

    /** Ends a block of [n] frames: appends the signal added to it to the ring (audio thread). */
    fun commit(n: Int) {
        val start = written.get()
        for (i in 0 until n) ring[((start + i) and RING_MASK.toLong()).toInt()] = sum[i]
        java.util.Arrays.fill(sum, 0, n, 0f)
        written.set(start + n)
    }

    /** `analyser.fftSize = size`: the analysis starts over (the ring stays). Audio thread. */
    fun resize(size: Int) {
        fftSize = checkSize(size)
    }

    /** Forgets what was heard: silence, and the smoothing starts over. */
    fun clear() {
        java.util.Arrays.fill(sum, 0f)
        java.util.Arrays.fill(ring, 0f)
        synchronized(this) {
            magnitude.fill(0.0)
            analysedAt = -1L
        }
    }

    /**
     * `getFloatTimeDomainData` into [out]: the oldest `min(out.size, fftSize)` frames of the newest [fftSize] frames, oldest first. Returns the number of frames written.
     */
    fun timeDomain(out: FloatArray): Int {
        val n = Math.min(out.size, fftSize)
        while (true) {
            val end = written.get()
            val start = end - fftSize
            for (i in 0 until n) out[i] = ring[((start + i) and RING_MASK.toLong()).toInt()]
            if (written.get() - start <= RING_SIZE) return n
        }
    }

    /**
     * `getFloatFrequencyData` into [out]: decibels of bins `0 until min(out.size, fftSize / 2)`. Returns the number of bins written. Does the analysis (and the smoothing)
     * only when audio has been added since the last call.
     */
    @Synchronized
    fun frequencyDb(out: FloatArray): Int {
        val n = fftSize
        if (n != size) prepare(n)
        val end = written.get()
        if (end != analysedAt) {
            analysedAt = end
            analyse(end - n)
        }
        val bins = Math.min(out.size, n / 2)
        for (k in 0 until bins) out[k] = linearToDb(magnitude[k])
        return bins
    }

    private fun prepare(n: Int) {
        size = n
        magnitude = DoubleArray(n / 2)
        re = DoubleArray(n)
        im = DoubleArray(n)
        fft = Fft(n)
        window = DoubleArray(n) { i ->
            val x = i.toDouble() / n
            0.5 * (1 - ALPHA) - 0.5 * Math.cos(2 * Math.PI * x) + 0.5 * ALPHA * Math.cos(4 * Math.PI * x)
        }
        analysedAt = -1L
    }

    private fun analyse(start: Long) {
        val n = size
        while (true) {
            for (i in 0 until n) re[i] = ring[((start + i) and RING_MASK.toLong()).toInt()].toDouble() * window[i].toFloat()
            if (written.get() - start <= RING_SIZE) break
        }
        java.util.Arrays.fill(im, 0.0)
        fft!!.forward(re, im)
        val scale = 1.0 / n
        for (k in 0 until n / 2) {
            val m = Math.hypot(re[k], im[k]) * scale
            magnitude[k] = SMOOTHING * magnitude[k] + (1 - SMOOTHING) * m
        }
    }

    private fun linearToDb(x: Double): Float = if (x == 0.0) -1000f else (20.0 * Math.log10(x)).toFloat()

    private companion object {
        fun checkSize(size: Int): Int {
            require(size >= 32 && size <= 32768 && (size and (size - 1)) == 0) { "fftSize must be a power of two from 32 to 32768, was $size" }
            return size
        }
    }
}
