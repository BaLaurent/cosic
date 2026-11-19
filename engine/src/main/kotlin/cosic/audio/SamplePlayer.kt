package cosic.audio

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Length of the fade applied to the last frames of a region so slices and clipped samples do not click. */
private const val END_FADE_SECONDS = 0.002

/**
 * Plays a region of a mono sample with linear interpolation. The region `[begin, end)` is given as fractions of the
 * sample; `rate` is source frames per output frame, negative to play backwards (starting from the last frame of
 * the region). The final 2 ms of the region fade to zero. Never allocates after construction.
 */
internal class SamplePlayer(sampleRate: Int) {
    private val fadeFrames = END_FADE_SECONDS * sampleRate
    private var samples = FloatArray(0)
    private var playing = false
    private var position = 0.0
    private var firstPosition = 0.0
    private var lastPosition = 0.0
    private var step = 0.0
    private var fadeScale = 0.0

    /** Silently plays nothing when the region is empty, [samples] is empty or [rate] is zero. */
    fun start(samples: FloatArray, begin: Double, end: Double, rate: Double) {
        this.samples = samples
        playing = samples.isNotEmpty() && end > begin && rate != 0.0
        if (!playing) return
        val length = samples.size.toDouble()
        firstPosition = begin * length
        lastPosition = max(firstPosition, end * length - 1.0)
        step = rate
        fadeScale = 1.0 / (abs(rate) * fadeFrames)
        position = if (rate > 0.0) firstPosition else lastPosition
    }

    /** Writes up to [n] frames into `out[0 until n]` and returns how many; fewer than [n] means the region ended. */
    fun fill(out: FloatArray, n: Int): Int {
        if (!playing) return 0
        val data = samples
        val lastIndex = data.size - 1
        var pos = position
        var count = 0
        if (step > 0.0) {
            while (count < n && pos <= lastPosition) {
                out[count++] = interpolate(data, lastIndex, pos) * fade(lastPosition - pos)
                pos += step
            }
        } else {
            while (count < n && pos >= firstPosition) {
                out[count++] = interpolate(data, lastIndex, pos) * fade(pos - firstPosition)
                pos += step
            }
        }
        position = pos
        if (count < n) playing = false
        return count
    }

    private fun interpolate(data: FloatArray, lastIndex: Int, pos: Double): Float {
        val index = pos.toInt()
        val a = data[index]
        val b = if (index < lastIndex) data[index + 1] else a
        return a + (b - a) * (pos - index).toFloat()
    }

    /** Gain for a frame that is [remaining] source frames from the end of the region. */
    private fun fade(remaining: Double): Float = min(1f, (remaining * fadeScale).toFloat())
}
