package cosic.audio

import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

private const val TONAL_LEVEL = 0.35f
private const val NOISE_LEVEL = 0.25f

/** Highest oscillator frequency as a fraction of the sample rate; keeps the PolyBLEP edge width below half a period. */
private const val MAX_FREQUENCY_RATIO = 0.49

private const val SINE_TABLE_SIZE = 2048
private val SINE_TABLE = FloatArray(SINE_TABLE_SIZE + 1) { sin(2.0 * PI * it / SINE_TABLE_SIZE).toFloat() }

private const val PINK_SCALE = 0.11
private const val BROWN_STEP = 0.02
private const val BROWN_GAIN = 3.5

/** One step of xorshift32; [state] must be non-zero. */
internal fun xorshift32(state: Int): Int {
    var x = state
    x = x xor (x shl 13)
    x = x xor (x ushr 17)
    x = x xor (x shl 5)
    return x
}

/** Maps a random 32-bit word onto `[-1, 1]`. */
internal fun Int.toBipolar(): Float = this * (1f / 2_147_483_648f)

/**
 * The built-in synth sources. [soundName] is the name patterns use (`s("sawtooth")`); `saw` and `tri` are
 * accepted as aliases by [byName]. [baseLevel] is the nominal peak level of a voice before `gain`.
 */
internal enum class Waveform(val soundName: String, val baseLevel: Float) {
    SINE("sine", TONAL_LEVEL),
    SAWTOOTH("sawtooth", TONAL_LEVEL),
    SQUARE("square", TONAL_LEVEL),
    TRIANGLE("triangle", TONAL_LEVEL),
    WHITE("white", NOISE_LEVEL),
    PINK("pink", NOISE_LEVEL),
    BROWN("brown", NOISE_LEVEL);

    companion object {
        private val NAMES: Map<String, Waveform> =
            entries.associateBy { it.soundName } + mapOf("saw" to SAWTOOTH, "tri" to TRIANGLE)

        /** The waveform called [name], or null when [name] is not a synth. */
        fun byName(name: String): Waveform? = NAMES[name]
    }
}

/** Canonical synth names in display order (aliases excluded), for the Sounds screen. */
internal val SYNTH_NAMES: List<String> = Waveform.entries.map { it.soundName }

/**
 * Unit-level tone and noise generator for one voice. Output peaks near +-1; the voice applies [Waveform.baseLevel].
 * Tonal waveforms start at zero so a note onset never clicks. Not thread-safe; never allocates after construction.
 */
internal class Oscillator(private val sampleRate: Int) {
    private var waveform = Waveform.SINE
    private var phase = 0.0
    private var increment = 0.0
    private var noise = 1
    private val pink = DoubleArray(7)
    private var brown = 0.0

    /** Restarts as [waveform] at [frequencyHz] (ignored by noise); [seed] makes noise reproducible. */
    fun start(waveform: Waveform, frequencyHz: Double, seed: Int) {
        this.waveform = waveform
        phase = 0.0
        increment = if (frequencyHz > 0.0) min(frequencyHz, MAX_FREQUENCY_RATIO * sampleRate) / sampleRate else 0.0
        noise = if (seed == 0) 1 else seed
        pink.fill(0.0)
        brown = 0.0
    }

    /** Writes the next [n] samples into `out[0 until n]`. */
    fun fill(out: FloatArray, n: Int) {
        when (waveform) {
            Waveform.SINE -> fillSine(out, n)
            Waveform.SAWTOOTH -> fillSawtooth(out, n)
            Waveform.SQUARE -> fillSquare(out, n)
            Waveform.TRIANGLE -> fillTriangle(out, n)
            Waveform.WHITE -> fillWhite(out, n)
            Waveform.PINK -> fillPink(out, n)
            Waveform.BROWN -> fillBrown(out, n)
        }
    }

    private fun fillSine(out: FloatArray, n: Int) {
        val table = SINE_TABLE
        var p = phase
        for (i in 0 until n) {
            val position = p * SINE_TABLE_SIZE
            val index = position.toInt()
            val a = table[index]
            out[i] = a + (table[index + 1] - a) * (position - index).toFloat()
            p += increment
            if (p >= 1.0) p -= 1.0
        }
        phase = p
    }

    private fun fillSawtooth(out: FloatArray, n: Int) {
        val dt = increment
        var p = phase
        for (i in 0 until n) {
            out[i] = (2.0 * p - 1.0 - polyBlep(p, dt)).toFloat()
            p += dt
            if (p >= 1.0) p -= 1.0
        }
        phase = p
    }

    private fun fillSquare(out: FloatArray, n: Int) {
        val dt = increment
        var p = phase
        for (i in 0 until n) {
            val shifted = if (p >= 0.5) p - 0.5 else p + 0.5
            val level = if (p < 0.5) 1.0 else -1.0
            out[i] = (level + polyBlep(p, dt) - polyBlep(shifted, dt)).toFloat()
            p += dt
            if (p >= 1.0) p -= 1.0
        }
        phase = p
    }

    private fun fillTriangle(out: FloatArray, n: Int) {
        var p = phase
        for (i in 0 until n) {
            out[i] = (if (p < 0.25) 4.0 * p else if (p < 0.75) 2.0 - 4.0 * p else 4.0 * p - 4.0).toFloat()
            p += increment
            if (p >= 1.0) p -= 1.0
        }
        phase = p
    }

    private fun fillWhite(out: FloatArray, n: Int) {
        var state = noise
        for (i in 0 until n) {
            state = xorshift32(state)
            out[i] = state.toBipolar()
        }
        noise = state
    }

    /** Paul Kellet's seven-pole approximation of a -3 dB/octave spectrum. */
    private fun fillPink(out: FloatArray, n: Int) {
        val b = pink
        var state = noise
        for (i in 0 until n) {
            state = xorshift32(state)
            val white = state.toBipolar().toDouble()
            b[0] = 0.99886 * b[0] + white * 0.0555179
            b[1] = 0.99332 * b[1] + white * 0.0750759
            b[2] = 0.96900 * b[2] + white * 0.1538520
            b[3] = 0.86650 * b[3] + white * 0.3104856
            b[4] = 0.55000 * b[4] + white * 0.5329522
            b[5] = -0.7616 * b[5] - white * 0.0168980
            out[i] = ((b[0] + b[1] + b[2] + b[3] + b[4] + b[5] + b[6] + white * 0.5362) * PINK_SCALE).toFloat()
            b[6] = white * 0.115926
        }
        noise = state
    }

    /** White noise through a leaky integrator: -6 dB/octave. */
    private fun fillBrown(out: FloatArray, n: Int) {
        var state = noise
        var b = brown
        for (i in 0 until n) {
            state = xorshift32(state)
            b = (b + BROWN_STEP * state.toBipolar()) / (1.0 + BROWN_STEP)
            out[i] = (b * BROWN_GAIN).toFloat()
        }
        noise = state
        brown = b
    }

    /** Polynomial band-limited step correction around the phase wrap; [t] in `[0, 1)`, [dt] the phase increment. */
    private fun polyBlep(t: Double, dt: Double): Double = when {
        t < dt -> {
            val x = t / dt
            x + x - x * x - 1.0
        }
        t > 1.0 - dt -> {
            val x = (t - 1.0) / dt
            x * x + x + x + 1.0
        }
        else -> 0.0
    }
}
