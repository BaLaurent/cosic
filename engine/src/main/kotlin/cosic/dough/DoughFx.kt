package cosic.dough

/*
 * dough.h 1015-1268: the effects of a voice (lag, coarse, crush, phaser, flanger, chorus, distort) and the delay line of an orbit.
 */

internal class Lag {
    @JvmField
    var s = 0f

    fun reset() {
        s = 0f
    }

    /** `Lag_update`: follows [input] at [rate] (in tenths of a second at the engine's rate: `lag_unit` samples), never faster than one sample. */
    fun update(input: Float, rate0: Float, lagUnit: Int): Float {
        var rate = rate0 * lagUnit.toFloat()
        if (rate < 1) rate = 1f
        s += (1 / rate) * (input - s)
        return s
    }
}

/** Sample rate reduction: holds a value for `coarse` samples. */
internal class Coarse {
    private var hold = 0f
    private var t = 0

    fun reset() {
        hold = 0.0f
        t = 0
    }

    fun update(input: Float, coarse0: Int): Float {
        // the C code takes `t % coarse`, which traps in the wasm when `coarse` is 0 (a `coarse` below 1); 0 holds nothing here
        val coarse = if (coarse0 == 0) 1 else coarse0
        if (t % coarse == 0) {
            t = 0
            hold = input
        }
        t++
        return hold
    }
}

/** Bit crusher: quantises to `2^(crush - 1)` steps per unit; a [crush] below 1 is 1. */
internal fun crush(input: Float, crush0: Float): Float {
    val crush = fmaxf(1f, crush0)
    val x = ourExp2f(crush - 1)
    return roundf(input * x) / x
}

internal class Phaser {
    private val filter1 = BiquadFilter()
    private val filter2 = BiquadFilter()
    private val lfo = Phasor()

    fun reset() {
        filter1.reset()
        filter2.reset()
        lfo.phase = 0f
    }

    /** `Phaser_update`: two notch filters whose centre frequencies swing by [sweep] cents around [center] (and [center] + 282 Hz). */
    fun update(input: Float, rate: Float, depth: Float, center: Float, sweep: Float, sr: Float, isr: Float): Float {
        val lfoVal = sineOscUpdate(lfo, rate, isr)
        val q = 2.0f - fminf(fmaxf(depth * 2.0f, 0.0f), 1.9f)
        val detuneCents = lfoVal * sweep
        val detuneMultiplier = ourExp2f(detuneCents / 1200.0f)
        var freq1 = center * detuneMultiplier
        var freq2 = (center + 282.0f) * detuneMultiplier
        freq1 = fmaxf(freq1, 20.0f)
        freq1 = fminf(freq1, sr * 0.45f)
        freq2 = fmaxf(freq2, 20.0f)
        freq2 = fminf(freq2, sr * 0.45f)
        var out = filter1.update(input, BIQUAD_NOTCH, freq1, q, 0.0f, sr)
        out = filter2.update(out, BIQUAD_NOTCH, freq2, q, 0.0f, sr)
        return out
    }
}

internal const val FLANGER_MAX_DELAY_MS = 10.0f
internal const val FLANGER_BUFFER_SIZE = 512

internal class Flanger {
    private val buffer = FloatArray(FLANGER_BUFFER_SIZE)
    private var writePos = 0
    private val lfo = Phasor()
    private var feedbackBuffer = 0f

    fun reset() {
        java.util.Arrays.fill(buffer, 0.0f)
        writePos = 0
        lfo.phase = 0f
        feedbackBuffer = 0.0f
    }

    fun update(input: Float, rate: Float, depth: Float, feedback0: Float, sr: Float, isr: Float): Float {
        val lfoVal = sineOscUpdate(lfo, rate, isr)
        val minDelayMs = 0.5f
        val maxDelayMs = FLANGER_MAX_DELAY_MS
        val delayRange = maxDelayMs - minDelayMs
        val depthCurve = depth * depth
        val delayMs = minDelayMs + depthCurve * delayRange * (lfoVal * 0.5f + 0.5f)
        var delaySamples = delayMs * sr * 0.001f
        delaySamples = fmaxf(1.0f, fminf(delaySamples, FLANGER_BUFFER_SIZE - 2.0f))
        val readPosInt = floorf(delaySamples).toInt()
        val frac = delaySamples - readPosInt
        val readIndex1 = Math.floorMod(writePos - readPosInt + FLANGER_BUFFER_SIZE, FLANGER_BUFFER_SIZE)
        val readIndex2 = Math.floorMod(writePos - readPosInt - 1 + FLANGER_BUFFER_SIZE, FLANGER_BUFFER_SIZE)
        val delayed1 = buffer[readIndex1]
        val delayed2 = buffer[readIndex2]
        val delayed = delayed1 + frac * (delayed2 - delayed1)
        val feedback = fmaxf(0.0f, fminf(feedback0, 0.95f))
        buffer[writePos] = input + feedbackBuffer * feedback
        writePos = (writePos + 1) % FLANGER_BUFFER_SIZE
        feedbackBuffer = delayed
        return input * 0.5f + delayed * 0.5f
    }
}

internal const val CHORUS_MAX_DELAY_MS = 50.0f
internal const val CHORUS_BUFFER_SIZE = 2048
internal const val CHORUS_VOICES = 3

internal class Chorus {
    private val buffer = FloatArray(CHORUS_BUFFER_SIZE)
    private var writePos = 0
    private val lfo = Array(CHORUS_VOICES) { Phasor() }

    /** The result of [update]: the new left and right samples. */
    @JvmField
    var left = 0f

    @JvmField
    var right = 0f

    init {
        reset()
    }

    fun reset() {
        java.util.Arrays.fill(buffer, 0.0f)
        writePos = 0
        for (i in 0 until CHORUS_VOICES) lfo[i].phase = i.toFloat() / CHORUS_VOICES.toFloat()
    }

    /** `Chorus_update`: mixes [leftIn] and [rightIn] down, sends three swinging delay taps to each side and mixes the result (in [left] and [right]) with the dry mono signal. */
    fun update(leftIn: Float, rightIn: Float, rate: Float, depth0: Float, delayMs: Float, sr: Float, isr: Float) {
        val minMs = 1.5f
        val maxMs = fminf(delayMs * 2.0f, CHORUS_MAX_DELAY_MS)
        val range = maxMs - minMs
        val depth = fmaxf(0.0f, fminf(depth0, 1.0f))
        val mono = (leftIn + rightIn) * 0.5f
        buffer[writePos] = mono
        var outL = 0.0f
        var outR = 0.0f
        for (v in 0 until CHORUS_VOICES) {
            val l = sineOscUpdate(lfo[v], rate, isr)
            var dlyL = delayMs + depth * range * l * 0.5f
            var dlyR = delayMs - depth * range * l * 0.5f
            dlyL = fmaxf(minMs, fminf(dlyL, maxMs))
            dlyR = fmaxf(minMs, fminf(dlyR, maxMs))
            val sampL = fmaxf(1.0f, fminf(dlyL * sr * 0.001f, CHORUS_BUFFER_SIZE - 2.0f))
            val sampR = fmaxf(1.0f, fminf(dlyR * sr * 0.001f, CHORUS_BUFFER_SIZE - 2.0f))
            val posL = floorf(sampL).toInt()
            val fracL = sampL - posL
            val idxL0 = Math.floorMod(writePos - posL + CHORUS_BUFFER_SIZE, CHORUS_BUFFER_SIZE)
            val idxL1 = Math.floorMod(writePos - posL - 1 + CHORUS_BUFFER_SIZE, CHORUS_BUFFER_SIZE)
            val tapL = buffer[idxL0] + fracL * (buffer[idxL1] - buffer[idxL0])
            val posR = floorf(sampR).toInt()
            val fracR = sampR - posR
            val idxR0 = Math.floorMod(writePos - posR + CHORUS_BUFFER_SIZE, CHORUS_BUFFER_SIZE)
            val idxR1 = Math.floorMod(writePos - posR - 1 + CHORUS_BUFFER_SIZE, CHORUS_BUFFER_SIZE)
            val tapR = buffer[idxR0] + fracR * (buffer[idxR1] - buffer[idxR0])
            outL += tapL
            outR += tapR
        }
        writePos = (writePos + 1) % CHORUS_BUFFER_SIZE
        outL /= CHORUS_VOICES.toFloat()
        outR /= CHORUS_VOICES.toFloat()
        val dry = sqrtf(0.5f)
        val wet = sqrtf(0.5f)
        left = mono * dry + outL * wet
        right = mono * dry + outR * wet
    }
}

/** `distort`: the shaper of superdough (`expm1` of the amount as the gain of a soft clipper) times [postgain]. */
internal fun distort(input: Float, amount: Float, postgain: Float): Float {
    val k = ourExpm1f(amount)
    return (((1 + k) * input) / (1 + k * fabsf(input))) * postgain
}

/** One channel of the delay of an orbit: a ring buffer of [size] samples (a second) read [Delay.update]'s `time` seconds behind the write. */
internal class Delay(private val size: Int) {
    private var write = 0
    private var read = 0
    private val buffer = FloatArray(size)

    fun clear() {
        java.util.Arrays.fill(buffer, 0.0f)
        write = 0
        read = 0
    }

    fun update(input: Float, time: Float, sr: Float): Float {
        write = (write + 1) % size
        buffer[write] = input
        val samples = Math.floor((sr * time).toDouble())
        var numSamples = if (samples < (size - 1)) samples.toInt() else size - 1
        if (numSamples < 0) numSamples = 0 // a negative time reads ahead of the write in the C code (and may read outside the buffer)
        read = write - numSamples
        if (read < 0) read += size
        return buffer[read]
    }
}
