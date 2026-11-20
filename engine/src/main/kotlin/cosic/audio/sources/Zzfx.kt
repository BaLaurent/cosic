package cosic.audio.sources

import cosic.pattern.CosicError
import cosic.pattern.Undefined
import cosic.pattern.jsToInt32
import cosic.pattern.jsToNumber
import cosic.pattern.jsTruthy
import cosic.pattern.midiToFreq
import cosic.pattern.noteToMidi

/*
 * ZZFX, the little sound generator of Frank Force (zzfx.mjs and zzfx_fork.mjs of superdough). A ZZFX sound is a buffer of at most a few seconds that a function of 20
 * numbers computes once, sample by sample (an oscillator whose frequency slides and jumps, a waveshaper, a bit crusher, an echo and an envelope); superdough plays that
 * buffer, once, with no envelope of its own. [ZzfxGenerator] is that function as a generator that produces the buffer a block at a time, [buildZzfx] the buffer at once,
 * [zzfxParams] `getZZFX`'s mapping of the controls of an event to the 20 numbers.
 */

/** The number of parameters of `buildSamples`. */
internal const val ZZFX_PARAMS = 20

/** The longest sound a source plays, in seconds. Strudel has no limit: a `duration` of an hour allocates an hour of buffer. */
internal const val ZZFX_MAX_SECONDS = 60

/** The defaults of the parameters of `buildSamples` (zzfx_fork.mjs:5-26), in parameter order: what a missing entry of a `zzfx` list is. */
internal val ZZFX_DEFAULTS: DoubleArray = doubleArrayOf(1.0, 0.05, 220.0, 0.0, 0.0, 0.1, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0)

private const val PI2 = Math.PI * 2

/**
 * `buildSamples` of zzfx_fork.mjs as a state machine: [fill] writes the next samples of the sound, so nothing but the history an echo reads has to be stored. The samples
 * of the JavaScript are doubles in an array; they become 32 bit floats when they reach the buffer, as here.
 *
 * [p] holds the 20 parameters in order (volume, randomness, frequency, attack, sustain, release, shape, shapeCurve, slide, deltaSlide, pitchJump, pitchJumpTime,
 * repeatTime, noise, modulation, bitCrush, delay, sustainVolume, decay, tremolo). `Math.random()` is `rng`, called once. Whatever happens, no more than [ZZFX_MAX_SECONDS]
 * of sound is produced; the envelope is that of the full length, which is cut off.
 */
internal class ZzfxGenerator(sampleRate: Int, rng: SourceRng, p: DoubleArray) {
    private val sr = sampleRate.toDouble()

    private val volume = p[0]
    private val shapeCurve = p[7]
    private val noise = p[13]
    private val sustainVolume = p[17]
    private val tremolo = p[19]
    private val shape = p[6]
    private val shaped = shape != 0.0 && !shape.isNaN()
    private val modulation: Double
    private val pitchJump: Double
    private val pitchJumpTime: Double
    private val repeatTime: Int
    private val bitCrush: Int
    private val attack: Double
    private val decay: Double
    private val sustain: Double
    private val release: Double
    private val delay: Double
    private val deltaSlide: Double
    private val startSlide: Double
    private val length: Int

    private var slide: Double
    private var frequency: Double
    private var startFrequency: Double

    /** The number of samples of the sound: `length` of the JavaScript, but 0 when that is negative (superdough then fails to create the buffer) and at most 60 s. */
    val frames: Int

    private var i = 0
    private var t = 0.0
    private var tm = 0.0
    private var j = 1.0
    private var r = 0L
    private var c = 0L
    private var s = 0.0

    /** The last samples, for the echo: sample `k` is at `k % history.size`. One entry when the echo never plays. */
    private val history: DoubleArray

    init {
        val randomness = p[1]
        slide = p[8]
        // slide *= 500 * PI2 / sampleRate / sampleRate
        slide *= (500 * PI2) / sr / sr
        startSlide = slide
        // frequency *= (1 + randomness * 2 * Math.random() - randomness) * PI2 / sampleRate
        frequency = p[2] * (((1 + randomness * 2 * rng.nextDouble() - randomness) * PI2) / sr)
        startFrequency = frequency

        attack = p[3] * sr + 9 // minimum attack to prevent pop
        decay = p[18] * sr
        sustain = p[4] * sr
        release = p[5] * sr
        delay = p[16] * sr
        deltaSlide = p[9] * ((500 * PI2) / (sr * sr * sr))
        modulation = p[14] * (PI2 / sr)
        pitchJump = p[10] * (PI2 / sr)
        pitchJumpTime = p[11] * sr
        repeatTime = jsToInt32(p[12] * sr)
        bitCrush = jsToInt32(p[15] * 100)

        length = jsToInt32(attack + decay + sustain + release + delay)
        frames = Math.min(Math.max(length, 0), ZZFX_MAX_SECONDS * sampleRate)
        // an echo that starts after the end of the sound is never heard, so it needs no history
        history = DoubleArray(if (jsTruthy(delay) && delay > 0 && delay < frames) Math.ceil(delay).toInt() + 2 else 1)
    }

    /** Writes the next `n` samples (fewer when the sound ends, the rest of `out` is not touched) at `out[offset]` and returns how many. */
    fun fill(out: FloatArray, offset: Int, n: Int): Int {
        val count = Math.min(n, frames - i)
        for (k in 0 until count) out[offset + k] = step().toFloat()
        return count
    }

    /** One iteration of the loop of `buildSamples`. */
    private fun step(): Double {
        val i = this.i
        if (crushed(++c)) {
            // bit crush: the sample is computed on every n-th iteration only, in between the last one is repeated
            var w = waveform()

            val level = if (i < attack) i / attack
            else if (i < attack + decay) 1 - ((i - attack) / decay) * (1 - sustainVolume)
            else if (i < attack + decay + sustain) sustainVolume
            else if (i < length - delay) ((length - i - delay) / release) * sustainVolume
            else 0.0
            val repeat = if (repeatTime != 0) 1 - tremolo + tremolo * Math.sin((PI2 * i) / repeatTime) else 1.0
            w = repeat * (if (w > 0) 1.0 else -1.0) * curved(Math.abs(w)) * volume * 1 * level

            s = if (jsTruthy(delay)) {
                // sample delay: the sound plus its own echo, which fades with the release
                w / 2 + (if (delay > i) 0.0 else ((if (i < length - delay) 1.0 else (length - i) / delay) * echo(i, delay)) / 2)
            } else w
        }
        history[i % history.size] = s

        // frequency += slide += deltaSlide
        slide += deltaSlide
        frequency += slide
        val f = frequency * Math.cos(modulation * tm++) // modulation
        t += if (noise == 0.0) f - f * noise else f - f * noise * (1 - (((Math.sin(i.toDouble()) + 1) * 1e9) % 2)) // noise: Math.sin, not StrictMath.sin (which allocates in the JIT's early tiers); the golden error is 8e-7

        if (j != 0.0 && ++j > pitchJumpTime) {
            // pitch jump
            frequency += pitchJump
            startFrequency += pitchJump
            j = 0.0
        }
        if (repeatTime != 0 && ++r % repeatTime == 0L) {
            // repeat
            frequency = startFrequency
            slide = startSlide
            if (j == 0.0) j = 1.0
        }
        this.i = i + 1
        return s
    }

    /** The waveform at phase [t]: 0 sine, 1 triangle, 2 sawtooth, 3 tan, 4 noise; any other truthy shape (-1 is the one that matters) is the triangle, as the cascade of conditions has it. */
    private fun waveform(): Double = if (shaped) {
        if (shape > 1) {
            if (shape > 2) {
                if (shape > 3) Math.sin(Math.pow(t % PI2, 3.0)) else Math.max(Math.min(Math.tan(t), 1.0), -1.0)
            } else 1 - ((((2 * t) / PI2) % 2 + 2) % 2)
        } else 1 - 4 * Math.abs(jsRound(t / PI2) - t / PI2)
    } else Math.sin(t)

    /** `!(c % ((bitCrush * 100) | 0))`: a remainder of 0 is a hit, and so is `% 0`, which is NaN. */
    private fun crushed(count: Long): Boolean = bitCrush == 0 || count % bitCrush == 0L

    /** `x ** shapeCurve` for the non-negative [x], with the exponents that are used in practice computed exactly. */
    private fun curved(x: Double): Double = when (shapeCurve) {
        1.0 -> x
        0.0 -> 1.0
        2.0 -> x * x
        else -> Math.pow(x, shapeCurve)
    }

    /** `b[(i - delay) | 0]`: the sample of the sound [delay] samples ago; NaN when there is none (`undefined` times a number). */
    private fun echo(i: Int, delay: Double): Double {
        val k = jsToInt32(i - delay)
        return if (k < 0 || k >= i || i - k >= history.size) Double.NaN else history[k % history.size]
    }
}

/** `Math.round`: halves go up. */
private fun jsRound(x: Double): Double = cosic.pattern.jsMathRound(x)

/**
 * The buffer `buildSamples` returns for the 20 parameters [p], at [sampleRate]; at most 60 s long, empty when the length of the sound is not positive. [rng] is its
 * `Math.random`.
 */
internal fun buildZzfx(sampleRate: Int, rng: SourceRng, p: DoubleArray): FloatArray {
    val generator = ZzfxGenerator(sampleRate, rng, p)
    val out = FloatArray(generator.frames)
    generator.fill(out, 0, out.size)
    return out
}

/** [buildZzfx] with the parameters by name, the defaults being those of `buildSamples`. */
internal fun buildZzfx(
    sampleRate: Int, rng: SourceRng, volume: Double = 1.0, randomness: Double = 0.05, frequency: Double = 220.0, attack: Double = 0.0, sustain: Double = 0.0,
    release: Double = 0.1, shape: Double = 0.0, shapeCurve: Double = 1.0, slide: Double = 0.0, deltaSlide: Double = 0.0, pitchJump: Double = 0.0,
    pitchJumpTime: Double = 0.0, repeatTime: Double = 0.0, noise: Double = 0.0, modulation: Double = 0.0, bitCrush: Double = 0.0, delay: Double = 0.0,
    sustainVolume: Double = 1.0, decay: Double = 0.0, tremolo: Double = 0.0,
): FloatArray = buildZzfx(
    sampleRate, rng,
    doubleArrayOf(
        volume, randomness, frequency, attack, sustain, release, shape, shapeCurve, slide, deltaSlide, pitchJump, pitchJumpTime, repeatTime, noise, modulation, bitCrush,
        delay, sustainVolume, decay, tremolo,
    ),
)

private val SHAPE_NAMES = listOf("sine", "triangle", "sawtooth", "tan", "noise")

/** A control as a number: [default] when it is not set (absent, null or undefined, which is how a JavaScript default applies), else `Number(value)`. */
private fun SourceControls.numberOr(key: String, default: Double): Double {
    val v = get(key)
    return if (v == null || v === Undefined) default else jsToNumber(v)
}

/**
 * The 20 parameters a ZZFX sound of event [controls] is built with (`getZZFX`, zzfx.mjs:8-65). [soundName] is the registered name (`zzfx`, `z_sine`...), unless the event's
 * own `s` says otherwise, as it does in Strudel, where `{ s: wave, ...value }` lets the value win: the waveform is the position of `s` without its `z_` in sine, triangle,
 * sawtooth, tan, noise, and -1 (the triangle) for any other name, `zzfx` and `z_square` included, which also sets `curve` to 0 for `square`. [durationSec] is the event's duration
 * (`value.duration`); the sound sustains for what is left of it after the attack and the decay.
 *
 * The frequency is `freq` when that is set, else the frequency of `note` (default 36, a name is turned into its number, no `octave`); a `note` that is neither a number
 * nor a name leaves it to the default of `buildSamples` (220 Hz). A `zzfx` control, a list of up to 20 numbers, replaces the parameters; the entries it leaves out are the
 * defaults of `buildSamples`, which is `randomness` 0.05, not 0. The control `lfo` is the repeat time. Throws `not a note` for a `note` that is a string but no note.
 */
internal fun zzfxParams(soundName: String, controls: SourceControls, durationSec: Double): DoubleArray {
    val c = controls
    val attack = c.numberOr("attack", 0.0)
    val decay = c.numberOr("decay", 0.0)
    val duration = if (durationSec.isNaN()) 0.2 else durationSec
    val sustainTime = Math.max(duration - attack - decay, 0.0)

    val rawNote = c["note"].let { if (it == null || it === Undefined) 36.0 else it }
    val note: Any? = if (rawNote is String) noteToMidi(rawNote) else rawNote
    val rawFreq = c["freq"]
    val frequency = when {
        jsTruthy(rawFreq) -> jsToNumber(rawFreq)
        note is Number -> midiToFreq(note.toDouble())
        rawFreq == null || rawFreq === Undefined -> ZZFX_DEFAULTS[2]
        else -> jsToNumber(rawFreq)
    }

    val sound = (c.text("s") ?: soundName).replaceFirst("z_", "")
    val shape = SHAPE_NAMES.indexOf(sound).let { if (it == 0) 0.0 else it.toDouble() }
    val curve = if (sound == "square") 0.0 else c.numberOr("curve", 1.0)

    val list = c["zzfx"]
    if (jsTruthy(list)) return fromList(list)
    return doubleArrayOf(
        0.25, // volume
        c.numberOr("zrand", 0.0), frequency, attack, sustainTime, c.numberOr("release", 0.1), shape, curve, c.numberOr("slide", 0.0), c.numberOr("deltaSlide", 0.0),
        c.numberOr("pitchJump", 0.0), c.numberOr("pitchJumpTime", 0.0), c.numberOr("lfo", 0.0), c.numberOr("znoise", 0.0), c.numberOr("zmod", 0.0),
        c.numberOr("zcrush", 0.0), c.numberOr("zdelay", 0.0),
        c.numberOr("sustain", 0.8), // sustain volume!
        decay, c.numberOr("tremolo", 0.0),
    )
}

/** `buildSamples(...zzfx)`: the entries of the list, a string being spread into its characters; a missing or undefined entry is the parameter's default, `null` is 0. */
private fun fromList(list: Any?): DoubleArray {
    val items: List<Any?> = when (list) {
        is List<*> -> list
        is String -> list.map { it.toString() }
        else -> throw CosicError("zzfx is not iterable")
    }
    return DoubleArray(ZZFX_PARAMS) { k ->
        val v = items.getOrNull(k)
        if (v === Undefined || (k >= items.size)) ZZFX_DEFAULTS[k] else jsToNumber(v)
    }
}
