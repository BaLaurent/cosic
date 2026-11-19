package cosic.audio

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max

private const val THRESHOLD = 0.95

/** The gain closes to within 1% of its target in this long (about five time constants). */
private const val ATTACK_SECONDS = 0.001
private const val ATTACK_TIME_CONSTANTS = 5.0

/** Time constant of the gain recovery; slower than a bass cycle so low tones are not modulated. */
private const val RELEASE_SECONDS = 0.1

/**
 * Stereo-linked peak limiter followed by a hard clip, so the output never leaves `[-1, 1]` whatever goes in.
 * The gain falls toward `0.95 / peak` within 1 ms and recovers with a 100 ms time constant. A time constant of
 * a whole millisecond would let a loud sustained tone overshoot into the clip on every cycle, so the attack is
 * quoted as settling time instead. The clip only trims what the attack lets through, and turns NaN into silence.
 * Zero latency; never allocates.
 */
internal class Limiter(sampleRate: Int) {
    private val attackCoefficient = 1.0 - exp(-ATTACK_TIME_CONSTANTS / (ATTACK_SECONDS * sampleRate))
    private val releaseCoefficient = 1.0 - exp(-1.0 / (RELEASE_SECONDS * sampleRate))
    private var gain = 1.0

    /** Limits `left[0 until n]` and `right[0 until n]` in place. */
    fun process(left: FloatArray, right: FloatArray, n: Int) {
        var g = gain
        for (i in 0 until n) {
            val peak = max(abs(left[i]), abs(right[i])).toDouble()
            val target = if (peak > THRESHOLD) THRESHOLD / peak else 1.0
            g += (target - g) * (if (target < g) attackCoefficient else releaseCoefficient)
            val factor = g.toFloat()
            left[i] = clip(left[i] * factor)
            right[i] = clip(right[i] * factor)
        }
        gain = g
    }

    fun reset() {
        gain = 1.0
    }

    private fun clip(x: Float): Float = when {
        x > 1f -> 1f
        x < -1f -> -1f
        x == x -> x
        else -> 0f
    }
}
