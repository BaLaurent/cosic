package cosic.audio.sources

private val GOLDEN_GAMMA = 0x9E3779B97F4A7C15UL.toLong()
private val MIX_1 = 0xBF58476D1CE4E5B9UL.toLong()
private val MIX_2 = 0x94D049BB133111EBUL.toLong()

/**
 * A small deterministic random generator for sources (SplitMix64): the same seed gives the same sequence on every platform. Allocation-free.
 */
internal class SourceRng(seed: Long) {
    private var state = seed

    fun nextLong(): Long {
        state += GOLDEN_GAMMA
        var z = state
        z = (z xor (z ushr 30)) * MIX_1
        z = (z xor (z ushr 27)) * MIX_2
        return z xor (z ushr 31)
    }

    /** A double in `[0, 1)` with 53 random bits. */
    fun nextDouble(): Double = (nextLong() ushr 11).toDouble() * (1.0 / (1L shl 53).toDouble())
}
