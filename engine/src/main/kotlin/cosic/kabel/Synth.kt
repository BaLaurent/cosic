package cosic.kabel

/*
 * The helpers of @kabelsalat/lib@0.4.1 synth.js and utils.js that the ugens use, ported literally (AGPL-3.0-or-later).
 */

/** `lerp` of synth.js: no lower clamp. */
internal fun lerpS(x: Double, y0: Double, y1: Double): Double = if (x >= 1) y1 else y0 + x * (y1 - y0)

/** `lerp` of utils.js: clamped on both sides. */
internal fun lerpU(x: Double, y0: Double, y1: Double): Double {
    if (x <= 0) return y0
    if (x >= 1) return y1
    return y0 + x * (y1 - y0)
}

/** `invLerp` of utils.js. */
internal fun invLerp(x: Double, y0: Double, y1: Double): Double {
    if (x <= y0) return 0.0
    if (x >= y1) return 1.0
    if (y1 == y0) return 0.0
    return (x - y0) / (y1 - y0)
}

/** `polyBlep` of utils.js: the correction that takes the edge out of a sawtooth. */
internal fun polyBlep(phase: Double, dt: Double): Double {
    var p = phase
    if (p < dt) {
        p /= dt
        return p + p - p * p - 1
    }
    if (p > 1 - dt) {
        p = (p - 1) / dt
        return p * p + p + p + 1
    }
    return 0.0
}

/** JavaScript's `Math.round` (ties toward +infinity), without the precision loss of `floor(x + 0.5)`. */
internal fun kabelRound(x: Double): Double {
    val f = Math.floor(x)
    return if (x - f >= 0.5) f + 1.0 else f
}

/** `x` is neither zero nor NaN: a JavaScript number in a condition. */
internal fun truthy(x: Double): Boolean = x != 0.0 && !x.isNaN()

/** A source of the `Math.random()` the noise ugens use. */
internal fun interface KabelRandom {
    fun nextDouble(): Double
}

/**
 * `Delay` of synth.js: a ring of floats. Writes at the next index and reads [time] seconds back; a time that is NaN, or reaches before the start of the ring,
 * reads nothing (NaN), as `undefined` does there. [buf] is the ring: ten seconds of the original, or fewer frames when the time is a constant (same output).
 */
internal class DelayLine(private val sampleRate: Int, val buf: FloatArray) {
    private var writeIdx = 0
    private var readIdx = 0.0

    fun write(s: Double, time: Double) {
        writeIdx = (writeIdx + 1) % buf.size
        val f = s.toFloat()
        buf[writeIdx] = if (f > -1e-30f && f < 1e-30f) 0f else f // denormals are not worth keeping
        val numSamples = Math.min(Math.floor(sampleRate * time), (buf.size - 1).toDouble())
        readIdx = writeIdx - numSamples
        if (readIdx < 0) readIdx += buf.size
    }

    fun read(): Double {
        val ri = readIdx
        if (ri.isNaN() || ri < 0 || ri >= buf.size || ri != Math.floor(ri)) return Double.NaN
        return buf[ri.toInt()].toDouble()
    }

    fun copy(): DelayLine {
        val c = DelayLine(sampleRate, buf.copyOf())
        c.writeIdx = writeIdx
        c.readIdx = readIdx
        return c
    }
}
