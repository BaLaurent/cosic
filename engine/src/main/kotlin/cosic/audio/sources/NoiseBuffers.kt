package cosic.audio.sources

import java.util.concurrent.ConcurrentHashMap

/** The noise sounds of superdough (`s("white")`, `pink`, `brown`, `crackle`), also the noise of the `noise` mix and of FM modulators. */
internal enum class NoiseType { WHITE, PINK, BROWN, CRACKLE }

/**
 * The 2 second noise loops of superdough (`noise.mjs`, `getNoiseBuffer`): white is uniform in `[-1, 1)`, brown integrates it (`y = (y + 0.02 w) / 1.02`,
 * a standard deviation of about 0.057), pink filters it with Paul Kellet's economy filter, and crackle is silence with a click (uniform in `[-1, 1)`)
 * with probability `density * 0.01` per frame.
 *
 * Superdough builds white, pink and brown once and keeps them, so every note plays the same loop from its first frame; [get] does the same, with a
 * fixed seed per type. Crackle is built again for every note, as there. The values are float32 as an `AudioBuffer` holds them: the recurrences run on
 * the float32 values just as the JavaScript reads them back (`NoiseOracleVectors` compares the buffers to those of the pinned `noise.mjs`, bit for bit,
 * for the same random numbers).
 */
internal object NoiseBuffers {
    private val cache = ConcurrentHashMap<Long, FloatArray>()

    /** The seeds of the cached types: any fixed numbers will do, these are only chosen once. */
    private const val WHITE_SEED = 0x57A11E01L
    private const val PINK_SEED = 0x57A11E02L
    private const val BROWN_SEED = 0x57A11E03L

    /**
     * The loop of [type] at [sampleRate]: `2 * sampleRate` frames, to be played from frame 0 and wrapped. White, pink and brown are cached per type and
     * rate and the same array comes back every time (do not modify it; [density] and [seed] do not apply to them). Crackle is built per call, from
     * [seed], with [density] (`crackle`'s default, 0.02, is one click in 5000 frames); it allocates, so do it when the sound is created.
     */
    fun get(type: NoiseType, sampleRate: Int, density: Double = DEFAULT_DENSITY, seed: Long = 0): FloatArray = when (type) {
        NoiseType.CRACKLE -> generate(type, sampleRate, density, seed)
        else -> cache.computeIfAbsent((sampleRate.toLong() shl 8) or type.ordinal.toLong()) {
            generate(type, sampleRate, density, if (type == NoiseType.WHITE) WHITE_SEED else if (type == NoiseType.PINK) PINK_SEED else BROWN_SEED)
        }
    }

    /** Builds a loop of [type] from the random numbers of [seed], without the cache. */
    internal fun generate(type: NoiseType, sampleRate: Int, density: Double, seed: Long): FloatArray {
        require(sampleRate > 0) { "sample rate $sampleRate" }
        val rng = SourceRng(seed)
        val out = FloatArray(2 * sampleRate)
        when (type) {
            NoiseType.WHITE -> for (i in out.indices) out[i] = (rng.nextDouble() * 2.0 - 1.0).toFloat()
            NoiseType.BROWN -> {
                var last = 0.0
                for (i in out.indices) {
                    val white = rng.nextDouble() * 2.0 - 1.0
                    val y = ((last + 0.02 * white) / 1.02).toFloat()
                    out[i] = y
                    last = y.toDouble()
                }
            }
            NoiseType.PINK -> {
                var b0 = 0.0
                var b1 = 0.0
                var b2 = 0.0
                var b3 = 0.0
                var b4 = 0.0
                var b5 = 0.0
                var b6 = 0.0
                for (i in out.indices) {
                    val white = rng.nextDouble() * 2.0 - 1.0
                    b0 = 0.99886 * b0 + white * 0.0555179
                    b1 = 0.99332 * b1 + white * 0.0750759
                    b2 = 0.969 * b2 + white * 0.153852
                    b3 = 0.8665 * b3 + white * 0.3104856
                    b4 = 0.55 * b4 + white * 0.5329522
                    b5 = -0.7616 * b5 - white * 0.016898
                    // the buffer holds float32: the sum is stored, then read back and scaled
                    val sum = (b0 + b1 + b2 + b3 + b4 + b5 + b6 + white * 0.5362).toFloat()
                    out[i] = (sum.toDouble() * 0.11).toFloat()
                    b6 = white * 0.115926
                }
            }
            NoiseType.CRACKLE -> {
                val probability = density * 0.01
                for (i in out.indices) out[i] = if (rng.nextDouble() < probability) (rng.nextDouble() * 2.0 - 1.0).toFloat() else 0f
            }
        }
        return out
    }

    /** `crackle`'s density when the pattern sets none (`getNoiseOscillator(type, t, density = 0.02)`). */
    const val DEFAULT_DENSITY = 0.02
}

/** A looped `AudioBufferSourceNode` over a noise buffer: starts at frame 0 and wraps. Holds only its position; [render] does not allocate. */
internal class LoopedNoise(private val buffer: FloatArray) {
    private var position = 0

    init {
        require(buffer.isNotEmpty()) { "an empty noise buffer cannot loop" }
    }

    /** Back to the first frame of the loop, as when the source is started. */
    fun reset() {
        position = 0
    }

    /** Writes the next [n] frames of the loop to `out[0 until n]`. */
    fun render(out: FloatArray, n: Int) {
        var done = 0
        while (done < n) {
            val chunk = minOf(n - done, buffer.size - position)
            System.arraycopy(buffer, position, out, done, chunk)
            position += chunk
            if (position == buffer.size) position = 0
            done += chunk
        }
    }
}

/**
 * How much of its input the `noise` control mixes in, as superdough's `drywet` weighs it (`wetfade`): 1 up to half, then falling to 0 at 1 (and below
 * 0 beyond it: the control is not clamped). The `noise` mix of a source is `osc * wetFade(w) + pink * wetFade(1 - w)`.
 */
internal fun wetFade(d: Double): Double = if (d < 0.5) 1.0 else 1.0 - (d - 0.5) / 0.5
