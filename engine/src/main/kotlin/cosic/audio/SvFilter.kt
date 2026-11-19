package cosic.audio

import kotlin.math.PI
import kotlin.math.tan

private const val MIN_CUTOFF_HZ = 20.0
private const val MAX_CUTOFF_RATIO = 0.45
private const val MIN_Q = 0.1
private const val MAX_Q = 30.0

internal enum class FilterKind { LOW_PASS, HIGH_PASS, BAND_PASS }

/**
 * Two-pole state-variable filter in its topology-preserving-transform form (Zavalishin), damping `k = 1/Q`.
 * The cutoff is prewarped, so the response at the cutoff is exact: a low-pass has gain Q there and a band-pass
 * has gain 1 (the band output is normalised, so raising Q narrows the band without making it louder).
 * Stable for every setting; never allocates after construction.
 */
internal class SvFilter(private val sampleRate: Int, private val kind: FilterKind) {
    private var k = 1.0
    private var a1 = 0.0
    private var a2 = 0.0
    private var a3 = 0.0
    private var ic1 = 0.0
    private var ic2 = 0.0

    /** Sets the response and clears the filter memory. [cutoffHz] is clamped to 20 Hz..0.45 x sample rate, [q] to 0.1..30. */
    fun configure(cutoffHz: Double, q: Double) {
        val cutoff = cutoffHz.coerceIn(MIN_CUTOFF_HZ, MAX_CUTOFF_RATIO * sampleRate)
        k = 1.0 / q.coerceIn(MIN_Q, MAX_Q)
        val g = tan(PI * cutoff / sampleRate)
        a1 = 1.0 / (1.0 + g * (g + k))
        a2 = g * a1
        a3 = g * a2
        ic1 = 0.0
        ic2 = 0.0
    }

    /** Filters `buf[0 until n]` in place. */
    fun process(buf: FloatArray, n: Int) {
        var s1 = ic1
        var s2 = ic2
        for (i in 0 until n) {
            val v0 = buf[i].toDouble()
            val v3 = v0 - s2
            val v1 = a1 * s1 + a2 * v3
            val v2 = s2 + a2 * s1 + a3 * v3
            s1 = 2.0 * v1 - s1
            s2 = 2.0 * v2 - s2
            buf[i] = when (kind) {
                FilterKind.LOW_PASS -> v2
                FilterKind.HIGH_PASS -> v0 - k * v1 - v2
                FilterKind.BAND_PASS -> k * v1
            }.toFloat()
        }
        ic1 = s1
        ic2 = s2
    }
}
