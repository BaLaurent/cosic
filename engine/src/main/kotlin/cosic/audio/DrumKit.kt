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

/**
 * The synthesised drum machines, each with its sounds in display order. A machine's sound plays as
 * `<machine>_<name>`: `s("bd").bank("cosic808")` is `cosic808_bd`. Machine names are lower case letters and digits.
 * The names are the drum abbreviations: bd kick, sd snare, hh closed and oh open hi-hat, cp clap, rim rimshot, lt mt
 * ht low, mid and high tom, cr crash, rd ride, cb cowbell, cl clave.
 */
internal val DRUM_MACHINES: Map<String, List<String>> = linkedMapOf(
    "cosic808" to listOf("bd", "sd", "hh", "oh", "cp", "rim", "lt", "mt", "ht", "cr", "cb", "cl"),
    "cosic909" to listOf("bd", "sd", "hh", "oh", "cp", "rim", "lt", "mt", "ht", "cr", "rd"),
)

/** Every sound exists in this many variants, picked by `n` modulo this count. */
/** How many variants of each drum sound there are, picked with `bd:0` to `bd:3`. */
internal const val DRUM_VARIANTS = 4

/** Every sound the kit has under the name it is asked for: the kit's own first, then each machine's as `<machine>_<name>`. A sound's place here is its id. */
private val DRUM_KEYS: List<String> = DRUM_NAMES + DRUM_MACHINES.flatMap { (machine, names) -> names.map { "${machine}_$it" } }

private val DRUM_IDS: Map<String, Int> = DRUM_KEYS.withIndex().associate { (id, key) -> key to id }

private const val PEAK = 0.9f
private const val TWO_PI = 2.0 * PI

/**
 * Fundamentals of six square waves: 270 Hz times the square roots of 1, 2, 3, 5, 7 and 11. No two share a harmonic,
 * so their sum is dense and inharmonic once its low end is filtered away: the metal of hats, cymbals and bells.
 */
private val METAL_HZ = doubleArrayOf(270.0, 382.0, 468.0, 604.0, 714.0, 895.0)

/** [METAL_HZ] with every fundamental multiplied by [pitch]. */
private fun metal(pitch: Double) = DoubleArray(METAL_HZ.size) { METAL_HZ[it] * pitch }

/** Highest square wave frequency as a fraction of the sample rate; keeps the PolyBLEP edge width below half a period. */
private const val MAX_FREQUENCY_RATIO = 0.49

/** One step of xorshift32; [state] must be non-zero. */
private fun xorshift32(state: Int): Int {
    var x = state
    x = x xor (x shl 13)
    x = x xor (x ushr 17)
    x = x xor (x shl 5)
    return x
}

/** Maps a random 32-bit word onto `[-1, 1]`. */
private fun Int.toBipolar(): Float = this * (1f / 2_147_483_648f)

/**
 * The triangle wave at [phase], a fraction of a period in `[0, 1)`: 0 at the start, 1 a quarter period in, -1 at
 * three quarters, and back to 0 at the end.
 */
private fun triangleWave(phase: Double): Double =
    if (phase < 0.25) 4.0 * phase else if (phase < 0.75) 2.0 - 4.0 * phase else 4.0 * phase - 4.0

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

/**
 * A synthesised drum kit and the drum machines that go with it. Each sound is rendered on first use at the engine
 * sample rate, normalised to a 0.9 peak and cached per (name, variant); rendering is deterministic, so the same name
 * and variant always give the same samples. The kit's sounds are named like `bd` ([DRUM_NAMES]), a machine's like
 * `cosic808_bd` ([DRUM_MACHINES]). The sounds are unpitched samples: play them through [VoiceSource.Sample] with a
 * null root note. Safe to call from any thread. A first call renders on the calling thread (a few milliseconds for
 * the longest sound), which the audio thread must not spend: [warmUp] renders them all from another thread beforehand.
 */
internal class DrumKit(private val sampleRate: Int) {
    private val cache = ConcurrentHashMap<Int, SampleData>()

    /** The sound called [name] (see [DRUM_NAMES] and [DRUM_MACHINES]) in variant `floorMod(variant, 4)`, or null for an unknown name. */
    fun sample(name: String, variant: Int): SampleData? {
        val id = DRUM_IDS[name] ?: return null
        val v = Math.floorMod(variant, DRUM_VARIANTS)
        return cache.getOrPut(id * DRUM_VARIANTS + v) { SampleData(normalised(render(name, v, ((id * DRUM_VARIANTS + v + 1) * 0x2545F491) or 1)), sampleRate) }
    }

    /** How many sounds (every name of the kit and of the machines, in every variant) are rendered and cached so far. */
    val renderedCount: Int get() = cache.size

    /** Renders every sound in every variant that is not rendered yet, so that no lookup has to do it later. */
    fun warmUp() {
        for (name in DRUM_KEYS) for (variant in 0 until DRUM_VARIANTS) sample(name, variant)
    }

    /** Renders [name], which is a sound of the kit or a machine's `<machine>_<sound>`. */
    private fun render(name: String, v: Int, seed: Int): FloatArray {
        val split = name.indexOf('_')
        if (split < 0) return renderKit(name, v, seed)
        val sound = name.substring(split + 1)
        return when (val machine = name.substring(0, split)) {
            "cosic808" -> render808(sound, v, seed)
            "cosic909" -> render909(sound, v, seed)
            else -> error("no drum machine called '$machine'")
        }
    }

    private fun renderKit(name: String, v: Int, seed: Int): FloatArray = when (name) {
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

    /** Uniform white noise from xorshift32 of [seed] (0 counts as 1), [n] frames long. */
    private fun whiteNoise(n: Int, seed: Int): FloatArray {
        val out = FloatArray(n)
        var state = if (seed == 0) 1 else seed
        for (i in out.indices) {
            state = xorshift32(state)
            out[i] = state.toBipolar()
        }
        return out
    }

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

    /** A sine at [hz] that fades with time constant [decay], [n] frames long. */
    private fun ping(hz: Double, decay: Double, n: Int): FloatArray = sweptSine(hz, hz, 0.0, FloatArray(n), decay)

    /** Multiplies [x] by an exponential fade with time constant [seconds], in place. */
    private fun decayed(x: FloatArray, seconds: Double): FloatArray {
        for (i in x.indices) x[i] *= exp(-time(i) / seconds).toFloat()
        return x
    }

    /** Runs [x] through tanh at [drive] times its level, in place: the louder a part, the more it is squashed. */
    private fun driven(x: FloatArray, drive: Double): FloatArray {
        for (i in x.indices) x[i] = tanh(drive * x[i]).toFloat()
        return x
    }

    /** Adds [gain] times [other] to this signal, in place. */
    private fun FloatArray.mix(other: FloatArray, gain: Double): FloatArray {
        for (i in indices) this[i] += (other[i] * gain).toFloat()
        return this
    }

    /** Adds a burst of white noise [seconds] long to the start of [out], at [level] and fading out linearly. */
    private fun addClick(out: FloatArray, seconds: Double, level: Double, seed: Int) {
        val click = whiteNoise(frames(seconds), seed)
        for (i in click.indices) out[i] += (click[i] * level * (1.0 - i.toDouble() / click.size)).toFloat()
    }

    /** Adds a square wave at each of [frequencies] to [out], each at level [gain]: band-limited with PolyBLEP, starting at phase 0. */
    private fun addSquares(out: FloatArray, gain: Float, frequencies: DoubleArray) {
        val partial = FloatArray(out.size)
        for (hz in frequencies) {
            val dt = if (hz > 0.0) Math.min(hz, MAX_FREQUENCY_RATIO * sampleRate) / sampleRate else 0.0
            var p = 0.0
            for (i in partial.indices) {
                val shifted = if (p >= 0.5) p - 0.5 else p + 0.5
                val level = if (p < 0.5) 1.0 else -1.0
                partial[i] = (level + polyBlep(p, dt) - polyBlep(shifted, dt)).toFloat()
                p += dt
                if (p >= 1.0) p -= 1.0
            }
            for (i in out.indices) out[i] += partial[i] * gain
        }
    }

    /** Sine sweeping to 45 Hz, plus a 2 ms noise click, driven through tanh. */
    private fun kick(v: Int, seed: Int): FloatArray {
        val out = sweptSine(150.0 * (1 + 0.06 * v), 45.0, 0.06, FloatArray(frames(0.6)), 0.25 * (1 + 0.15 * v))
        addClick(out, 0.002, 0.3, seed)
        return driven(out, 1.5)
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
    private fun hat(decay: Double, seconds: Double, seed: Int): FloatArray =
        decayed(filtered(whiteNoise(frames(seconds), seed), FilterKind.HIGH_PASS, 7000.0), decay)

    /**
     * Noise band-passed around [centerHz], shaped as three quick bursts [gap] seconds apart followed by a tail that
     * fades with time constant [tail]. The kit's own clap is the default.
     */
    private fun clap(
        seed: Int,
        centerHz: Double = 1200.0,
        q: Double = 1.5,
        gap: Double = 0.01,
        tail: Double = 0.12,
        seconds: Double = 0.4,
    ): FloatArray {
        val out = filtered(whiteNoise(frames(seconds), seed), FilterKind.BAND_PASS, centerHz, q)
        val bursts = doubleArrayOf(0.0, gap, 2 * gap)
        for (i in out.indices) {
            val t = time(i)
            var envelope = 0.0
            for (start in bursts) if (t >= start) envelope += exp(-(t - start) / 0.004)
            if (t >= 2 * gap) envelope += 0.5 * exp(-(t - 2 * gap) / tail)
            out[i] *= envelope.toFloat()
        }
        return out
    }

    /** A high sine plus a lower triangle, very short, with a 1 ms noise click. */
    private fun rim(seed: Int): FloatArray {
        val out = FloatArray(frames(0.08))
        for (i in out.indices) {
            val t = time(i)
            out[i] = ((sin(TWO_PI * 1700.0 * t) + 0.6 * triangleWave((800.0 * t) % 1.0)) * exp(-t / 0.012)).toFloat()
        }
        addClick(out, 0.001, 0.3, seed)
        return out
    }

    /**
     * A sine that starts [drop] times higher than [hz] and settles within [sweepSeconds]. The kit's own toms are the
     * default: 1.6 times higher, settling within 80 ms, 0.8 s long.
     */
    private fun tom(hz: Double, decay: Double, v: Int, drop: Double = 1.6, sweepSeconds: Double = 0.08, seconds: Double = 0.8): FloatArray {
        val base = hz * (1 + 0.06 * v)
        return sweptSine(base * drop, base, sweepSeconds, FloatArray(frames(seconds)), decay)
    }

    /** Noise above 5 kHz plus six inharmonic square partials, decaying slowly. */
    private fun cymbal(seed: Int): FloatArray {
        val out = filtered(whiteNoise(frames(2.5), seed), FilterKind.HIGH_PASS, 5000.0)
        for (i in out.indices) out[i] *= 0.5f
        addSquares(out, 0.08f, doubleArrayOf(340.0, 523.0, 627.0, 812.0, 1043.0, 1345.0))
        return decayed(out, 1.2)
    }

    // ---------------------------------------------------------------- what the machines are built from

    /**
     * The sound of struck metal: [noise] times white noise plus [squares] times the six squares of [METAL_HZ] at
     * [pitch], high-passed at [highPassHz] (twice over, for a steep edge) and fading with time constant [decay].
     * The squares and the filters run for 20 ms before the sound starts, so that it begins in mid-flow: squares that
     * all start at once would make the filters ring out in one loud click that the rest of the sound is dwarfed by.
     */
    private fun metallic(noise: Double, squares: Double, pitch: Double, highPassHz: Double, decay: Double, seconds: Double, seed: Int): FloatArray {
        val lead = frames(0.02)
        val out = whiteNoise(lead + frames(seconds), seed)
        for (i in out.indices) out[i] *= noise.toFloat()
        addSquares(out, squares.toFloat(), metal(pitch))
        filtered(filtered(out, FilterKind.HIGH_PASS, highPassHz), FilterKind.HIGH_PASS, highPassHz)
        return decayed(out.copyOfRange(lead, out.size), decay)
    }

    /** Two short sines and a noise click, driven hard through tanh: the crack of a rimshot. */
    private fun snap(lowHz: Double, highHz: Double, decay: Double, seconds: Double, v: Int, seed: Int): FloatArray {
        val tune = 1 + 0.04 * v
        val n = frames(seconds)
        val out = ping(highHz * tune, decay, n).mix(ping(lowHz * tune, decay, n), 0.7)
        addClick(out, 0.001, 0.4, seed)
        return driven(out, 2.0)
    }

    // ---------------------------------------------------------------------------------------- cosic808

    private fun render808(name: String, v: Int, seed: Int): FloatArray {
        fun metalHat(decay: Double, seconds: Double) =
            metallic(noise = 0.0, squares = 1.0, pitch = 1 + 0.04 * v, highPassHz = 7000.0, decay = decay, seconds = seconds, seed = seed)
        fun boomTom(hz: Double, decay: Double, seconds: Double) = tom(hz, decay, v, drop = 1.35, sweepSeconds = 0.03, seconds = seconds)
        return when (name) {
            "bd" -> kick808(v)
            "sd" -> snare808(v, seed)
            "hh" -> metalHat(0.03 * (1 + 0.15 * v), 0.2)
            "oh" -> metalHat(0.15 * (1 + 0.1 * v), 0.9)
            "cp" -> clap(seed, centerHz = 1000.0 * (1 + 0.05 * v), q = 1.0, gap = 0.011, tail = 0.14, seconds = 0.5)
            "rim" -> snap(lowHz = 480.0, highHz = 1650.0, decay = 0.006, seconds = 0.06, v = v, seed = seed)
            "lt" -> boomTom(90.0, 0.22, 1.2)
            "mt" -> boomTom(135.0, 0.18, 1.0)
            "ht" -> boomTom(200.0, 0.15, 0.8)
            "cr" -> metallic(noise = 0.25, squares = 0.9, pitch = 0.9 + 0.05 * v, highPassHz = 4500.0, decay = 0.6, seconds = 2.5, seed = seed)
            "cb" -> cowbell(v)
            "cl" -> clave(v, seed)
            else -> error("cosic808 has no sound '$name'")
        }
    }

    /** A clean sine that drops from 135 Hz to 50 Hz in 45 ms and rings for most of a second; the tanh gives it harmonics that small speakers can play. */
    private fun kick808(v: Int): FloatArray =
        driven(sweptSine(135.0 * (1 + 0.05 * v), 50.0 * (1 + 0.04 * v), 0.045, FloatArray(frames(1.3)), 0.22 * (1 + 0.06 * v)), 2.0)

    /** Two tuned sines that start a little sharp, under noise above 1.8 kHz that snaps away a moment later. */
    private fun snare808(v: Int, seed: Int): FloatArray {
        val n = frames(0.45)
        val tune = 1 + 0.05 * v
        val noise = decayed(filtered(whiteNoise(n, seed), FilterKind.HIGH_PASS, 1800.0), 0.09 * (1 + 0.12 * v))
        return sweptSine(220.0 * tune, 180.0 * tune, 0.012, FloatArray(n), 0.07)
            .mix(sweptSine(400.0 * tune, 330.0 * tune, 0.012, FloatArray(n), 0.04), 0.5)
            .mix(noise, 1.0)
    }

    /** Two squares just under a fifth apart, band-passed to a clank that is gone in a quarter of a second. */
    private fun cowbell(v: Int): FloatArray {
        val out = FloatArray(frames(0.5))
        val tune = 1 + 0.03 * v
        addSquares(out, 1f, doubleArrayOf(540.0 * tune, 800.0 * tune))
        return decayed(filtered(out, FilterKind.BAND_PASS, 2400.0, 1.2), 0.06)
    }

    /** A 2.5 kHz sine that is gone within 40 ms, with a click: two sticks of wood. */
    private fun clave(v: Int, seed: Int): FloatArray {
        val out = ping(2500.0 * (1 + 0.03 * v), 0.012, frames(0.12))
        addClick(out, 0.001, 0.3, seed)
        return out
    }

    // ---------------------------------------------------------------------------------------- cosic909

    private fun render909(name: String, v: Int, seed: Int): FloatArray {
        fun noisyHat(decay: Double, seconds: Double) =
            metallic(noise = 1.0, squares = 0.35, pitch = 2.2 + 0.1 * v, highPassHz = 8000.0, decay = decay, seconds = seconds, seed = seed)
        fun punchTom(hz: Double, decay: Double, seconds: Double) =
            tom(hz, decay, v, drop = 1.6, sweepSeconds = 0.03, seconds = seconds).also { addClick(it, 0.002, 0.3, seed) }
        return when (name) {
            "bd" -> kick909(v, seed)
            "sd" -> snare909(v, seed)
            "hh" -> noisyHat(0.02 * (1 + 0.2 * v), 0.15)
            "oh" -> noisyHat(0.12 * (1 + 0.1 * v), 0.7)
            "cp" -> clap(seed, centerHz = 1500.0 * (1 + 0.05 * v), q = 1.4, gap = 0.008, tail = 0.09, seconds = 0.4)
            "rim" -> snap(lowHz = 510.0, highHz = 1850.0, decay = 0.008, seconds = 0.07, v = v, seed = seed)
            "lt" -> punchTom(115.0, 0.14, 0.7)
            "mt" -> punchTom(160.0, 0.12, 0.6)
            "ht" -> punchTom(230.0, 0.1, 0.5)
            "cr" -> metallic(noise = 1.0, squares = 0.12, pitch = 1.5 + 0.06 * v, highPassHz = 6000.0, decay = 0.6, seconds = 2.5, seed = seed)
            "rd" -> ride909(v, seed)
            else -> error("cosic909 has no sound '$name'")
        }
    }

    /** A fast sweep from 200 Hz to 55 Hz, a noise click on top and a light squash through tanh: all punch, little boom. */
    private fun kick909(v: Int, seed: Int): FloatArray {
        val out = sweptSine(200.0 * (1 + 0.05 * v), 55.0 * (1 + 0.03 * v), 0.03, FloatArray(frames(0.65)), 0.1 * (1 + 0.08 * v))
        addClick(out, 0.003, 0.5, seed)
        return driven(out, 1.8)
    }

    /** Like the 808 snare but tuned higher, with much more and brighter noise: a crack rather than a thump. */
    private fun snare909(v: Int, seed: Int): FloatArray {
        val n = frames(0.5)
        val tune = 1 + 0.05 * v
        val noise = decayed(filtered(whiteNoise(n, seed), FilterKind.HIGH_PASS, 2500.0), 0.1 * (1 + 0.1 * v))
        return sweptSine(300.0 * tune, 200.0 * tune, 0.01, FloatArray(n), 0.05)
            .mix(sweptSine(480.0 * tune, 340.0 * tune, 0.01, FloatArray(n), 0.03), 0.4)
            .mix(noise, 1.5)
    }

    /** A wash of metal that fades over a couple of seconds, with the three inharmonic pings of a bell on top. */
    private fun ride909(v: Int, seed: Int): FloatArray {
        val out = metallic(noise = 0.0, squares = 0.8, pitch = 2.6 + 0.08 * v, highPassHz = 4500.0, decay = 0.6, seconds = 2.5, seed = seed)
        for (hz in doubleArrayOf(3100.0, 4300.0, 5700.0)) out.mix(ping(hz * (1 + 0.03 * v), 0.25, out.size), 0.25)
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
