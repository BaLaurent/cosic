package cosic.audio

import cosic.SampleData
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tanh

/** Drum-kit sound names in display order. */
internal val DRUM_NAMES: List<String> = listOf("bd", "sd", "hh", "oh", "cp", "rim", "lt", "mt", "ht", "cr")

/** Every sound exists in this many variants, picked by `n` modulo this count. */
private const val VARIANTS = 4

private const val PEAK = 0.9f
private const val TWO_PI = 2.0 * PI

/**
 * A synthesised drum kit. Each sound is rendered on first use at the engine sample rate, normalised to a 0.9
 * peak and cached per (name, variant); rendering is deterministic, so the same name and variant always give the
 * same samples. The sounds are unpitched samples: play them through [VoiceSource.Sample] with a null root note.
 * Safe to call from any thread. A first call renders on the calling thread (a few milliseconds for the longest
 * sound), which the audio thread must not spend: [warmUp] renders them all from another thread beforehand.
 */
internal class DrumKit(private val sampleRate: Int) {
    private val cache = ConcurrentHashMap<Int, SampleData>()

    /** The sound called [name] (see [DRUM_NAMES]) in variant `floorMod(variant, 4)`, or null for an unknown name. */
    fun sample(name: String, variant: Int): SampleData? {
        val id = DRUM_NAMES.indexOf(name)
        if (id < 0) return null
        val v = Math.floorMod(variant, VARIANTS)
        return cache.getOrPut(id * VARIANTS + v) { SampleData(normalised(render(name, v, ((id * VARIANTS + v + 1) * 0x2545F491) or 1)), sampleRate) }
    }

    /** How many of the kit's sounds (every name in every variant) are rendered and cached so far. */
    val renderedCount: Int get() = cache.size

    /** Renders every sound in every variant that is not rendered yet, so that no lookup has to do it later. */
    fun warmUp() {
        for (name in DRUM_NAMES) for (variant in 0 until VARIANTS) sample(name, variant)
    }

    private fun render(name: String, v: Int, seed: Int): FloatArray = when (name) {
        "bd" -> kick(v, seed)
        "sd" -> snare(v, seed)
        "hh" -> hat(0.03 + 0.01 * v, 0.15, seed)
        "oh" -> hat(0.25 * (1 + 0.15 * v), 0.8, seed)
        "cp" -> clap(seed)
        "rim" -> rim(seed)
        "lt" -> tom(80.0, 0.3, v)
        "mt" -> tom(120.0, 0.25, v)
        "ht" -> tom(180.0, 0.2, v)
        else -> cymbal(seed)
    }

    private fun frames(seconds: Double) = (seconds * sampleRate).roundToInt()

    private fun time(frame: Int) = frame.toDouble() / sampleRate

    private fun whiteNoise(n: Int, seed: Int): FloatArray =
        FloatArray(n).also { Oscillator(sampleRate).apply { start(Waveform.WHITE, 0.0, seed) }.fill(it, n) }

    private fun filtered(x: FloatArray, kind: FilterKind, cutoffHz: Double, q: Double = 0.707): FloatArray {
        SvFilter(sampleRate, kind).also { it.configure(cutoffHz, q) }.process(x, x.size)
        return x
    }

    /** A sine whose frequency falls exponentially from [fromHz] to [toHz] over [sweepSeconds], then holds. */
    private fun sweptSine(fromHz: Double, toHz: Double, sweepSeconds: Double, out: FloatArray, decay: Double): FloatArray {
        var phase = 0.0
        for (i in out.indices) {
            val t = time(i)
            val hz = if (t < sweepSeconds) fromHz * (toHz / fromHz).pow(t / sweepSeconds) else toHz
            phase += TWO_PI * hz / sampleRate
            out[i] = (sin(phase) * exp(-t / decay)).toFloat()
        }
        return out
    }

    /** Sine sweeping to 45 Hz, plus a 2 ms noise click, driven through tanh. */
    private fun kick(v: Int, seed: Int): FloatArray {
        val out = sweptSine(150.0 * (1 + 0.06 * v), 45.0, 0.06, FloatArray(frames(0.6)), 0.25 * (1 + 0.15 * v))
        val click = whiteNoise(frames(0.002), seed)
        for (i in out.indices) {
            val noise = if (i < click.size) click[i] * 0.3 * (1.0 - i.toDouble() / click.size) else 0.0
            out[i] = tanh(1.5 * (out[i] + noise)).toFloat()
        }
        return out
    }

    /** Two detuned-by-variant tones plus high-passed noise, each with its own decay. */
    private fun snare(v: Int, seed: Int): FloatArray {
        val out = FloatArray(frames(0.35))
        val noise = filtered(whiteNoise(out.size, seed), FilterKind.HIGH_PASS, 1000.0)
        val detune = 1 + 0.06 * v
        val noiseDecay = 0.12 * (1 + 0.15 * v)
        for (i in out.indices) {
            val t = time(i)
            val tone = sin(TWO_PI * 185.0 * detune * t) + 0.5 * sin(TWO_PI * 330.0 * detune * t)
            out[i] = (tone * exp(-t / 0.06) + noise[i] * 0.6 * exp(-t / noiseDecay)).toFloat()
        }
        return out
    }

    /** Closed and open hats: noise above 7 kHz with an exponential decay. */
    private fun hat(decay: Double, seconds: Double, seed: Int): FloatArray {
        val out = filtered(whiteNoise(frames(seconds), seed), FilterKind.HIGH_PASS, 7000.0)
        for (i in out.indices) out[i] *= exp(-time(i) / decay).toFloat()
        return out
    }

    /** Band-passed noise shaped as three quick bursts followed by a longer tail. */
    private fun clap(seed: Int): FloatArray {
        val out = filtered(whiteNoise(frames(0.4), seed), FilterKind.BAND_PASS, 1200.0, 1.5)
        for (i in out.indices) {
            val t = time(i)
            var envelope = 0.0
            for (start in doubleArrayOf(0.0, 0.01, 0.02)) if (t >= start) envelope += exp(-(t - start) / 0.004)
            if (t >= 0.02) envelope += 0.5 * exp(-(t - 0.02) / 0.12)
            out[i] *= envelope.toFloat()
        }
        return out
    }

    /** A high sine plus a lower triangle, very short, with a 1 ms noise click. */
    private fun rim(seed: Int): FloatArray {
        val out = FloatArray(frames(0.08))
        val click = whiteNoise(frames(0.001), seed)
        for (i in out.indices) {
            val t = time(i)
            val triangle = triangleWave((800.0 * t) % 1.0)
            val noise = if (i < click.size) click[i] * 0.3 * (1.0 - i.toDouble() / click.size) else 0.0
            out[i] = ((sin(TWO_PI * 1700.0 * t) + 0.6 * triangle) * exp(-t / 0.012) + noise).toFloat()
        }
        return out
    }

    /** A sine that starts 1.6 times higher than [hz] and settles within 80 ms. */
    private fun tom(hz: Double, decay: Double, v: Int): FloatArray {
        val base = hz * (1 + 0.06 * v)
        return sweptSine(base * 1.6, base, 0.08, FloatArray(frames(0.8)), decay)
    }

    /** Noise above 5 kHz plus six inharmonic square partials, decaying slowly. */
    private fun cymbal(seed: Int): FloatArray {
        val out = filtered(whiteNoise(frames(2.5), seed), FilterKind.HIGH_PASS, 5000.0)
        for (i in out.indices) out[i] *= 0.5f
        val partial = FloatArray(out.size)
        val oscillator = Oscillator(sampleRate)
        for (hz in doubleArrayOf(340.0, 523.0, 627.0, 812.0, 1043.0, 1345.0)) {
            oscillator.start(Waveform.SQUARE, hz, 1)
            oscillator.fill(partial, partial.size)
            for (i in out.indices) out[i] += partial[i] * 0.08f
        }
        for (i in out.indices) out[i] *= exp(-time(i) / 1.2).toFloat()
        return out
    }

    private fun normalised(x: FloatArray): FloatArray {
        var peak = 0f
        for (v in x) peak = max(peak, abs(v))
        val scale = if (peak > 0f) PEAK / peak else 1f
        for (i in x.indices) x[i] *= scale
        return x
    }
}
