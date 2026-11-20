package cosic.audio

import cosic.SampleData
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

internal const val SR = 48_000

internal fun sineWave(freq: Double, seconds: Double, amp: Double = 1.0, sr: Int = SR): FloatArray =
    FloatArray((seconds * sr).roundToInt()) { (amp * sin(2.0 * PI * freq * it / sr)).toFloat() }

internal fun sineSample(freq: Double, seconds: Double, rate: Int = SR): SampleData =
    SampleData(sineWave(freq, seconds, 1.0, rate), rate)

internal fun constantSample(seconds: Double, value: Float = 1f, rate: Int = SR): SampleData =
    SampleData(FloatArray((seconds * rate).roundToInt()) { value }, rate)

internal fun rms(x: FloatArray, from: Int = 0, to: Int = x.size): Double {
    var sum = 0.0
    for (i in from until to) sum += x[i].toDouble() * x[i]
    return sqrt(sum / (to - from))
}

internal fun peak(x: FloatArray, from: Int = 0, to: Int = x.size): Float {
    var m = 0f
    for (i in from until to) m = max(m, abs(x[i]))
    return m
}

internal fun dbOf(ratio: Double): Double = 20.0 * log10(ratio)

internal fun noteForFrequency(hz: Double): Double = 69.0 + 12.0 * ln(hz / 440.0) / ln(2.0)

internal fun allFinite(x: FloatArray): Boolean {
    for (v in x) if (!v.isFinite()) return false
    return true
}

/** The share of [x]'s energy below [cutoffHz], measured through a 2-pole low-pass. */
internal fun energyBelow(x: FloatArray, cutoffHz: Double): Double {
    val low = x.copyOf()
    SvFilter(SR, FilterKind.LOW_PASS).also { it.configure(cutoffHz, 0.707) }.process(low, low.size)
    return (rms(low) / rms(x)).pow(2)
}

/** How loud the 10 ms of [x] that start [seconds] in are, relative to its first 10 ms. */
internal fun remainingAt(x: FloatArray, seconds: Double): Double {
    val from = (seconds * SR).toInt()
    return rms(x, from, from + 480) / rms(x, 0, 480)
}

/** One channel of an interleaved stereo buffer. */
internal fun channel(interleaved: FloatArray, index: Int): FloatArray =
    FloatArray(interleaved.size / 2) { interleaved[it * 2 + index] }

/** Frequency from the interpolated upward zero crossings found in frames `[from, to)`. */
internal fun estimateFrequency(x: FloatArray, from: Int, to: Int, sr: Int = SR): Double {
    var first = 0.0
    var last = 0.0
    var count = 0
    for (i in max(from, 1) until to) {
        if (x[i - 1] < 0f && x[i] >= 0f) {
            val t = (i - 1) + (-x[i - 1]).toDouble() / (x[i] - x[i - 1]).toDouble()
            if (count == 0) first = t
            last = t
            count++
        }
    }
    check(count >= 2) { "fewer than two zero crossings in [$from, $to)" }
    return (count - 1) * sr / (last - first)
}

internal fun Mixer.renderFrames(frames: Int): FloatArray {
    val out = FloatArray(frames * 2)
    render(out, frames)
    return out
}
