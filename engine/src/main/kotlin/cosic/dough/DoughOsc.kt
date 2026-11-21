package cosic.dough

/*
 * dough.h 438-727: the generators of a dough voice. Phases are `Float`, the way the wasm keeps them, so a frequency is never exact; `isr` is the engine's `1 / sr`.
 */

internal const val GAIN_SINE = 0.2
internal const val GAIN_TRI = 0.2
internal const val GAIN_SAW = 0.2
internal const val GAIN_PULSE = 0.2
internal const val GAIN_WHITE = 0.2
internal const val GAIN_PINK = 0.2
internal const val GAIN_BROWN = 0.2

/** The linear congruential generator every noise of the engine draws from (`seed` is a global of the C code). */
internal class DoughRng {
    @JvmField
    var seed: Int = 123456789

    fun lcgRand(): Int {
        seed = seed * 1103515245 + 12345
        return (seed ushr 16) and 0x7fff
    }

    fun randomFloat(): Float = lcgRand().toFloat() / 32767.0f
}

internal class Phasor {
    @JvmField
    var phase: Float = 0f
}

/** `polyBlep`: the correction that takes the aliasing out of a discontinuity [dt] cycles wide, at phase [t0]. */
internal fun polyBlep(t0: Float, dt: Float): Float {
    var t = t0
    if (t < dt) {
        t /= dt
        return t + t - t * t - 1
    }
    if (t > 1 - dt) {
        t = (t - 1) / dt
        return t * t + t + t + 1
    }
    return 0f
}

/** Moves [p] by [freq] hertz; true when the phase wrapped around (in either direction). */
internal fun updatePhasor(p: Phasor, freq: Float, isr: Float): Boolean {
    p.phase += freq * isr
    if (p.phase >= 1.0f) {
        p.phase = (p.phase.toDouble() - 1.0).toFloat()
        return true
    } else if (p.phase < 0f) {
        p.phase = (p.phase.toDouble() + 1.0).toFloat()
        return true
    }
    return false
}

internal fun triOscUpdate(p: Phasor, freq: Float, isr: Float): Float {
    val s = if (p.phase < 0.5f) 4 * p.phase - 1 else 3 - 4 * p.phase
    updatePhasor(p, freq, isr)
    return s
}

internal fun sineOscUpdate(p: Phasor, freq: Float, isr: Float): Float {
    val s = ourSinf((p.phase.toDouble() * 2.0 * FPI.toDouble()).toFloat())
    updatePhasor(p, freq, isr)
    return s
}

internal fun zawOscUpdate(p: Phasor, freq: Float, isr: Float): Float {
    val s = p.phase * 2 - 1
    updatePhasor(p, freq, isr)
    return s
}

internal fun sawOscUpdate(p: Phasor, freq: Float, isr: Float): Float {
    val blep = polyBlep(p.phase, freq * isr)
    val s = p.phase * 2 - 1 - blep
    updatePhasor(p, freq, isr)
    return s
}

internal fun pulseOscUpdate(p: Phasor, freq: Float, pw: Float, isr: Float): Float {
    val dt = freq * isr
    var phi = p.phase + pw
    if (phi >= 1) phi -= 1
    val p1 = polyBlep(phi, dt)
    val p2 = polyBlep(p.phase, dt)
    val pulse = 2 * (p.phase - phi) - p2 + p1
    updatePhasor(p, freq, isr)
    return pulse + pw * 2 - 1
}

internal fun pulzeOscUpdate(p: Phasor, freq: Float, duty: Float, isr: Float): Float {
    val s = if (p.phase < duty) 1f else -1f
    updatePhasor(p, freq, isr)
    return s
}

/** A file a voice plays: interleaved [pcm] of [frames] frames in [channels] channels at the engine's rate, recorded at the pitch [freq]. */
internal class DoughFile(@JvmField val pcm: FloatArray, @JvmField val frames: Int, @JvmField val channels: Int, @JvmField val freq: Float) {
    init {
        require(channels >= 1 && frames >= 0 && pcm.size >= frames * channels) { "a file of $frames frames in $channels channels needs ${frames * channels} values, not ${pcm.size}" }
    }
}

/**
 * `FileSource_update`: writes the frame under the phase of the slice `[begin, end)` of [file] to `out[0]` and `out[1]` (a mono file is on both). True when the phase wrapped, which
 * ends the voice. An empty slice is silence and does not move the phase.
 */
internal fun fileSourceUpdate(p: Phasor, freq: Float, file: DoughFile, begin: Float, end: Float, out: FloatArray, isr: Float): Boolean {
    val beginFrame = (begin * file.frames.toFloat()).toInt()
    val endFrame = (end * file.frames.toFloat()).toInt()
    val sliceFrames = endFrame - beginFrame
    if (sliceFrames <= 0) {
        out[0] = 0f
        out[1] = 0f
        return false
    }
    var currentFrame = beginFrame + Math.floor((p.phase * sliceFrames.toFloat()).toDouble()).toInt()
    if (currentFrame >= endFrame) currentFrame = endFrame - 1
    if (currentFrame < beginFrame) currentFrame = beginFrame // not in the C code: a phase far below 0 must not index before the data
    for (c in 0 until DOUGH_CHANNELS) {
        val channel = c % file.channels
        out[c] = file.pcm[currentFrame * file.channels + channel]
    }
    return updatePhasor(p, freq, isr)
}

internal class PinkNoise {
    private var b0 = 0f
    private var b1 = 0f
    private var b2 = 0f
    private var b3 = 0f
    private var b4 = 0f
    private var b5 = 0f
    private var b6 = 0f

    fun reset() {
        b0 = 0f
        b1 = 0f
        b2 = 0f
        b3 = 0f
        b4 = 0f
        b5 = 0f
        b6 = 0f
    }

    fun update(rng: DoughRng): Float {
        val white = rng.randomFloat() * 2 - 1
        val w = white.toDouble()
        b0 = (0.99886 * b0.toDouble() + w * 0.0555179).toFloat()
        b1 = (0.99332 * b1.toDouble() + w * 0.0750759).toFloat()
        b2 = (0.969 * b2.toDouble() + w * 0.153852).toFloat()
        b3 = (0.8665 * b3.toDouble() + w * 0.3104856).toFloat()
        b4 = (0.55 * b4.toDouble() + w * 0.5329522).toFloat()
        b5 = (-0.7616 * b5.toDouble() - w * 0.016898).toFloat()
        val sum = b0 + b1 + b2 + b3 + b4 + b5 + b6
        val pink = (sum.toDouble() + w * 0.5362).toFloat()
        b6 = (w * 0.115926).toFloat()
        return (pink.toDouble() * 0.11).toFloat()
    }
}

internal class BrownNoise {
    private var out = 0f

    fun reset() {
        out = 0f
    }

    fun update(rng: DoughRng): Float {
        val white = (rng.randomFloat().toDouble() * 2.0 - 1.0).toFloat()
        out = ((out.toDouble() + 0.02 * white.toDouble()) / 1.02).toFloat()
        return out
    }
}

/** The number of channels dough renders (hard coded in dough.c). */
internal const val DOUGH_CHANNELS = 2
