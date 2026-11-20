package cosic.audio

import cosic.SampleData
import cosic.pattern.jsMathRound

/** The longest room the engine builds (decision D-1: `roomsize` is clamped to the documented range, a room of that length has an impulse response of 15 s). */
internal const val MAX_ROOM_SECONDS = 10.0

/** An impulse response (or any decoded audio): one array per channel, all of the same length, at [sampleRate] Hz. */
internal class ImpulseResponse(val channels: Array<FloatArray>, val sampleRate: Int) {
    val length: Int get() = channels[0].size
}

/** A source of uniform random numbers in `[0, 1)`, which `Math.random` is in the original; the engine hands in a seeded generator so that rendering is repeatable. */
internal fun interface UniformRandom {
    fun next(): Double
}

/**
 * The reverb's impulse responses: generated noise ([generate]) or a sample reshaped to the room's size ([adjustLength]). Ports of `reverbGen.mjs` and
 * `BaseAudioContext.prototype.adjustLength` of `reverb.mjs`. Allocates: run it off the audio thread.
 */
internal object ReverbGen {
    /**
     * `reverbGen.generateReverb({numChannels: 2, decayTime: decay, fadeInTime: fade, lpFreqStart: lp, lpFreqEnd: dim})` for a context at [sampleRate]: two
     * channels of random noise that decays by 60 dB over [decay] seconds and lasts half as long again, faded in over [fade] seconds, and run
     * through a low-pass filter that moves from [lp] to [dim] Hz over the first [decay] seconds. A [lp] of 0 (or NaN) leaves the noise unfiltered, a [dim]
     * of NaN counts as 0 (`|| 0`). The caller guarantees `frames(decay, sampleRate) >= 1`.
     *
     * The numbers are rounded to float32 where the original stores them in a `Float32Array` or an AudioParam, and the filter is Chromium's biquad
     * (see [gradualLowpass]), so the result matches the browser's to float32 precision when the same random numbers are used.
     */
    fun generate(sampleRate: Int, decay: Double, fade: Double, lp: Double, dim: Double, random: UniformRandom): ImpulseResponse {
        val decayFrames = jsMathRound(decay * sampleRate)
        val total = frames(decay, sampleRate)
        val fadeFrames = jsMathRound(orZero(fade) * sampleRate)
        val decayBase = Math.pow(1.0 / 1000.0, 1.0 / decayFrames)
        val channels = Array(2) { FloatArray(total) }
        for (chan in channels) {
            for (j in 0 until total) chan[j] = ((random.next() * 2 - 1) * Math.pow(decayBase, j.toDouble())).toFloat()
            val fadeEnd = Math.min(fadeFrames, total.toDouble()).toInt()
            for (j in 0 until fadeEnd) chan[j] = (chan[j] * (j / fadeFrames)).toFloat()
        }
        val start = orZero(lp)
        if (start != 0.0) gradualLowpass(channels, sampleRate, start, orZero(dim), decay)
        return ImpulseResponse(channels, sampleRate)
    }

    /** The length in frames of the impulse response of a room of [decay] seconds, `round(1.5 * decay * sampleRate)`; below 1 the original throws and the event fails. */
    fun frames(decay: Double, sampleRate: Int): Int {
        val total = jsMathRound(decay * 1.5 * sampleRate)
        return if (total >= 1.0 && total <= Int.MAX_VALUE) total.toInt() else 0
    }

    /** JavaScript's `x || 0`: NaN becomes 0. */
    private fun orZero(x: Double): Double = if (x.isNaN()) 0.0 else x

    /**
     * `applyGradualLowpass`: a Web Audio low-pass `BiquadFilterNode` over [channels] in place (every channel gets the same coefficients), Q 0.0001 dB, with
     * its frequency set to [start] at time 0 and ramped linearly to [end] at [rampEnd] seconds (held after). Both are limited to the Nyquist frequency.
     *
     * Blink's biquad with a moving frequency (an a-rate AudioParam) computes its coefficients for every frame; the direct form I keeps its input history as
     * float32 and the OUTPUT it feeds back as float32 too, the coefficients and sums being doubles. The low-pass is the cookbook one with `Q` in decibels
     * (`alpha = sin(w) / (2 * 10^(Q / 20))`), the identity at a cutoff of the Nyquist frequency and silence at 0.
     */
    private fun gradualLowpass(channels: Array<FloatArray>, sampleRate: Int, start: Double, end: Double, rampEnd: Double) {
        val nyquist = sampleRate / 2.0
        val f0 = Math.min(start, nyquist).toFloat().toDouble()
        val f1 = Math.min(end, nyquist).toFloat().toDouble()
        val qLinear = Math.pow(10.0, 0.0001f.toDouble() / 20.0)
        val count = channels.size
        val x1 = DoubleArray(count)
        val x2 = DoubleArray(count)
        val y1 = DoubleArray(count)
        val y2 = DoubleArray(count)
        val length = channels[0].size
        for (k in 0 until length) {
            val t = k.toDouble() / sampleRate
            val f = (if (t >= rampEnd) f1 else f0 + (f1 - f0) * (t / rampEnd)).toFloat().toDouble()
            val cutoff = Math.min(Math.max(f / nyquist, 0.0), 1.0)
            var b0: Double
            var b1 = 0.0
            var b2 = 0.0
            var a1 = 0.0
            var a2 = 0.0
            if (cutoff == 1.0 || cutoff == 0.0) {
                b0 = cutoff
            } else {
                val theta = Math.PI * cutoff
                val alpha = Math.sin(theta) / (2.0 * qLinear)
                val c = Math.cos(theta)
                val a0 = 1.0 + alpha
                b0 = (1.0 - c) / 2.0 / a0
                b1 = (1.0 - c) / a0
                b2 = b0
                a1 = -2.0 * c / a0
                a2 = (1.0 - alpha) / a0
            }
            for (ch in 0 until count) {
                val data = channels[ch]
                val x = data[k].toDouble()
                val y = (b0 * x + b1 * x1[ch] + b2 * x2[ch] - a1 * y1[ch] - a2 * y2[ch]).toFloat()
                data[k] = y
                x2[ch] = x1[ch]
                x1[ch] = x
                y2[ch] = y1[ch]
                y1[ch] = y.toDouble()
            }
        }
    }

    /**
     * `adjustLength(duration, ir, speed, offsetAmount)` of reverb.mjs: a buffer of the length of [ir] whose first `sampleRate * duration` frames (the rest is
     * silence; a longer duration changes nothing) are read from [ir] starting at fraction [offsetAmount] of it, [speed] frames at a time, wrapping around.
     * Below a speed of 1 the position is negated, and `Array.prototype.at` counts negative indices from the end, so the impulse response is read backwards
     * from the start of the buffer (index `-0` is the first frame). Like `at`, an index is truncated towards zero, and a position that falls outside the buffer
     * (or a sample that is NaN) reads as 0. [speed] and [offsetAmount] are NaN when the pattern did not set them (1 and 0).
     */
    fun adjustLength(duration: Double, ir: ImpulseResponse, speed: Double, offsetAmount: Double): ImpulseResponse {
        val speedValue = if (speed.isNaN()) 1.0 else speed
        val offset = if (offsetAmount.isNaN()) 0.0 else offsetAmount
        val length = ir.length
        val sampleOffset = Math.floor(Math.min(Math.max(offset, 0.0), 1.0) * length)
        val newLength = ir.sampleRate * duration
        val absSpeed = Math.abs(speedValue)
        val channels = Array(ir.channels.size) { ch ->
            val old = ir.channels[ch]
            val data = FloatArray(length)
            var i = 0
            while (i < newLength && i < length) {
                var position = (sampleOffset + i * absSpeed) % length
                if (speedValue < 1) position *= -1.0
                var index = if (position.isNaN()) 0.0 else if (position < 0) Math.ceil(position) else Math.floor(position)
                if (index < 0) index += length
                if (index >= 0 && index < length) {
                    val v = old[index.toInt()]
                    data[i] = if (v == v) v else 0f // `|| 0`: NaN (and -0) read as 0
                }
                i++
            }
            data
        }
        return ImpulseResponse(channels, ir.sampleRate)
    }
}

/** What a loaded sample is as a reverb impulse response (superdough decodes it at the context's rate, which this does with linear interpolation). */
@Suppress("EXTENSION_SHADOWED_BY_MEMBER")
internal fun SampleData.toImpulseResponse(sampleRate: Int): ImpulseResponse {
    val count = Math.min(Math.max(this.channelCount, 1), 2) // more than two channels: the first two
    val stride = Math.max(this.channelCount, 1)
    val frames = samples.size / stride
    val ratio = this.sampleRate.toDouble() / sampleRate
    val outFrames = if (this.sampleRate == sampleRate) frames else Math.max(1, Math.ceil(frames / ratio).toInt())
    val channels = Array(count) { ch ->
        val out = FloatArray(outFrames)
        if (this.sampleRate == sampleRate) {
            for (i in 0 until frames) out[i] = samples[i * stride + ch]
        } else {
            for (i in 0 until outFrames) {
                val position = i * ratio
                val i0 = Math.min(position.toInt(), frames - 1)
                val i1 = Math.min(i0 + 1, frames - 1)
                val fraction = (position - i0).toFloat()
                out[i] = samples[i0 * stride + ch] * (1f - fraction) + samples[i1 * stride + ch] * fraction
            }
        }
        out
    }
    return ImpulseResponse(channels, sampleRate)
}

/**
 * The number of interleaved channels of the sample. A sample is mono until the sound library (Task 3.5) says otherwise: its `SampleData.channels` is a member
 * and shadows this extension, which only exists so that this file compiles before that field does.
 */
@Suppress("EXTENSION_SHADOWED_BY_MEMBER")
private val SampleData.channelCount: Int
    get() = this.channels

@Suppress("EXTENSION_SHADOWED_BY_MEMBER")
private val SampleData.channels: Int
    get() = 1
