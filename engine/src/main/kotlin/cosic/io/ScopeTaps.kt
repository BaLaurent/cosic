package cosic.io

import java.util.concurrent.ConcurrentHashMap

/** How many frames of each tap are kept: four times the 8192 of an analyser of size `2^(8 + 5)`, which is `fft`'s default. */
const val SCOPE_RING_FRAMES = 4 * 8192

/** The longest block the engine hands out that a tap sums without growing: the app renders 256 frames at a time. */
private const val ACCUMULATOR_FRAMES = 8192

/**
 * The sound that `scope`, `fscope` and `spectrum` draw: for every id of an `analyze:<id>` control, a mono ring of the most recent
 * [SCOPE_RING_FRAMES] frames of what the voices with that id sound (`AnalyserNode` fed by the `post` node of every such voice).
 *
 * The audio thread calls [add] for every voice (and segment of a block) with an `analyze` id and [endBlock] once per block, which appends
 * the sum of the block to the ring of each id (zeros for an id that had no sound in it); [read] copies the latest samples out, from any
 * thread. A tap is created by the first sound of its id ([add] allocates then, once; [prepare] does it ahead of time from another
 * thread) and is kept for the life of the engine.
 */
class ScopeTaps {
    private class Tap {
        /** The sum of the voices of the block being rendered, by frame of the block. */
        @JvmField
        var sum = FloatArray(ACCUMULATOR_FRAMES)

        @JvmField
        val ring = FloatArray(RING_FRAMES)

        /** How many frames have been appended to the ring in all; the write position is this modulo the size of the ring. */
        @Volatile
        @JvmField
        var written = 0L

        /** True once a voice has made sound in this tap: only then does [read] answer. */
        @Volatile
        @JvmField
        var hasSound = false
    }

    private val taps = ConcurrentHashMap<String, Tap>()

    /** The taps in creation order, for [endBlock] to walk without allocating. Replaced, never changed. */
    @Volatile
    private var all: Array<Tap> = emptyArray()

    /** Makes the tap of [id] now, so that the audio thread does not have to when the first sound arrives. Any thread. */
    fun prepare(id: String) {
        tapOf(id)
    }

    private fun tapOf(id: String): Tap {
        taps[id]?.let { return it }
        synchronized(this) {
            taps[id]?.let { return it }
            val tap = Tap()
            all = all + tap
            taps[id] = tap
            return tap
        }
    }

    /**
     * Adds the first [frames] samples of [mono] to the sum of the block being rendered, [offset] frames after the start of the block.
     * [mono] is the sound of one voice, after its own processing and before it reaches an orbit. Audio thread.
     */
    fun add(id: String, mono: FloatArray, offset: Int, frames: Int) {
        val tap = tapOf(id)
        tap.hasSound = true
        val n = minOf(frames, mono.size, tap.sum.size - offset)
        if (offset < 0 || n <= 0) return
        val sum = tap.sum
        for (i in 0 until n) sum[offset + i] += mono[i]
    }

    /**
     * Ends a block of [frames] frames: every tap that has had sound takes the sum of the block into its ring (zeros where no voice
     * sounded) and starts the next sum from silence. Audio thread.
     */
    fun endBlock(frames: Int) {
        for (tap in all) {
            if (!tap.hasSound) continue
            val sum = tap.sum
            val ring = tap.ring
            var position = (tap.written % ring.size).toInt()
            val summed = minOf(frames, sum.size)
            for (i in 0 until summed) {
                ring[position] = sum[i]
                sum[i] = 0f
                if (++position == ring.size) position = 0
            }
            // a block longer than the sum holds (nobody renders blocks that long to be drawn) keeps its place in time
            for (i in summed until frames) {
                ring[position] = 0f
                if (++position == ring.size) position = 0
            }
            tap.written += frames
        }
    }

    /**
     * Copies the latest [count] frames of the tap [id] into the first [count] places of [out], the oldest first; fewer than [count]
     * frames written so far leave zeros at the start. False when no voice has made sound with that id yet. False too when the audio thread lapped four copies in a row. Any thread.
     */
    fun read(id: String, count: Int, out: FloatArray): Boolean {
        val tap = taps[id] ?: return false
        if (!tap.hasSound) return false
        require(count in 0..SCOPE_RING_FRAMES && out.size >= count) { "cannot read $count frames of a tap of $SCOPE_RING_FRAMES into ${out.size}" }
        val ring = tap.ring
        // the audio thread may write while this reads: take the copy again if it got far enough to overwrite what was taken
        // (it writes a block ahead of `written`, so it has reached the oldest frame taken when written + a block is a lap past it)
        for (attempt in 0 until 4) {
            val end = tap.written
            val available = minOf(end, count.toLong()).toInt()
            val start = end - available
            val missing = count - available
            for (i in 0 until missing) out[i] = 0f
            var position = (start % ring.size).toInt()
            for (i in 0 until available) {
                out[missing + i] = ring[position]
                if (++position == ring.size) position = 0
            }
            if (tap.written + ACCUMULATOR_FRAMES - start <= ring.size) return true
        }
        // four copies in a row were lapped: the reader is starved, a torn copy is worse than none for a drawing
        return false
    }

    private companion object {
        /** The ring holds what can be read plus two blocks, so a copy of [SCOPE_RING_FRAMES] frames still has room for the writer to advance. */
        const val RING_FRAMES = SCOPE_RING_FRAMES + 2 * ACCUMULATOR_FRAMES
    }
}
