package cosic.audio

/**
 * One block of audio travelling through a voice's chain: a left and a right channel, [channels] of which carry sound.
 * A mono block (`channels == 1`) only has meaning in [l]; [r] is stale until [toStereo]. Stages work in place on
 * the first `ctx.n` frames. Never allocates after construction.
 */
internal class AudioBlock(val capacity: Int) {
    val l = FloatArray(capacity)
    val r = FloatArray(capacity)

    /** 1 while the signal is mono, 2 once something (a stereo source, the panner, the compressor, the orbit) made it stereo. */
    var channels = 1

    /** Zeroes `[0, n)` of both channels and makes the block mono again. */
    fun clear(n: Int) {
        l.fill(0f, 0, n)
        r.fill(0f, 0, n)
        channels = 1
    }

    /** Makes a mono block stereo with L = R (the up-mix of an explicit-stereo node); a stereo block stays as it is. */
    fun toStereo(n: Int) {
        if (channels == 2) return
        System.arraycopy(l, 0, r, 0, n)
        channels = 2
    }

    /** Multiplies every channel in use by [g]. */
    fun mulBy(g: Float, n: Int) {
        for (i in 0 until n) l[i] *= g
        if (channels == 2) for (i in 0 until n) r[i] *= g
    }

    /** Multiplies frame `i` of every channel in use by `gains[i]`. */
    fun mulBy(gains: FloatArray, n: Int) {
        for (i in 0 until n) l[i] *= gains[i]
        if (channels == 2) for (i in 0 until n) r[i] *= gains[i]
    }

    /** Sets frames `[from, n)` to silence; what follows the end of a voice. */
    fun silenceFrom(from: Int, n: Int) {
        if (from >= n) return
        l.fill(0f, from, n)
        if (channels == 2) r.fill(0f, from, n)
    }
}

/** A stereo block of audio that voices and buses add into: one summing buffer per orbit, one per send. */
internal class StereoBuffer(frames: Int) {
    val left = FloatArray(frames)
    val right = FloatArray(frames)

    fun clear(n: Int) {
        left.fill(0f, 0, n)
        right.fill(0f, 0, n)
    }

    /** Adds `mono[0 until n]` panned with the given per-channel gains. */
    fun addMono(mono: FloatArray, n: Int, leftGain: Float, rightGain: Float) {
        for (i in 0 until n) {
            left[i] += mono[i] * leftGain
            right[i] += mono[i] * rightGain
        }
    }
}

/**
 * Where in time a block is: it holds [n] frames, the first of which is engine frame [startFrame] (frames count from the first
 * one the [Mixer] ever rendered, so a time in seconds is `frame / sampleRate`). Every block-aware stage keys its state
 * on absolute frame numbers, never on how a `render` call happened to be split, which is what keeps the output
 * independent of the block size.
 *
 * It also lends out scratch buffers ([borrow] / [release]) so parameter and modulator code can read a value in the middle of a
 * block without allocating.
 */
internal class BlockContext(val sampleRate: Int, private val capacity: Int = BLOCK_FRAMES) {
    /** The absolute engine frame of index 0 of the block. */
    var startFrame = 0L

    /** The number of frames in the block. */
    var n = 0

    private val scratch = Array(SCRATCH_DEPTH) { FloatArray(capacity) }
    private var borrowed = 0

    /** The time in seconds of frame [i] of the block. */
    fun time(i: Int): Double = (startFrame + i).toDouble() / sampleRate

    /** The time in seconds at which render quantum [q] (frames `[128 q, 128 q + 128)`) starts: a worklet's `currentTime`. */
    fun quantumTime(q: Long): Double = (q * QUANTUM).toDouble() / sampleRate

    /** A scratch buffer of block size; give it back with [release] in reverse order. */
    fun borrow(): FloatArray {
        check(borrowed < SCRATCH_DEPTH) { "scratch buffers exhausted" }
        return scratch[borrowed++]
    }

    fun release() {
        borrowed--
    }

    private companion object {
        const val SCRATCH_DEPTH = 6
    }
}
