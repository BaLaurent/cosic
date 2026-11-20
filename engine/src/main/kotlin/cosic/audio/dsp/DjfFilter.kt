package cosic.audio.dsp

import cosic.audio.clamp

/**
 * The `djf-processor` worklet behind the `djf` control (superdough `worklets.mjs:297-362`, created by the orbit's `getDjf`,
 * `superdoughoutput.mjs:42-50`): one knob that is a low-pass below 0.5 and a high-pass above it.
 *
 * `value` is clamped to 0..1. Above 0.51 the knob is a high-pass with `v = (value - 0.5) * 2`, below 0.49 a low-pass with `v = value * 2`, in between
 * the signal passes untouched (and the filter state stays frozen). The cutoff is `(v * 11)^4` Hz: 0 Hz at the ends of the knob, 915 Hz at 0.25.
 *
 * The filter is the worklet's `TwoPoleFilter` with a fixed resonance of 0.1: a state-variable low-pass whose output is `s1`, and the high-pass is
 * `input - s1`. Its DC gain is `1 / (1 + r^2) = 0.9238`, not 1, so the high-pass leaks 7.6 % of DC and a low-passed DC input settles at 0.9238; kept as is.
 *
 * `value` is read at the start of every render quantum (index 0): pass the AudioParam at the first frame of the quantum.
 */
internal class DjfFilter(sampleRate: Int) {
    private val invSampleRate = 1.0 / sampleRate
    private val nyquistLess1 = sampleRate / 2.0 - 1

    // `TwoPoleFilter.s0` (band-pass) and `.s1` (low-pass) per channel.
    private val s0 = DoubleArray(2)
    private val s1 = DoubleArray(2)

    fun reset() {
        s0.fill(0.0)
        s1.fill(0.0)
    }

    /** Filters frames `[from, to)` of [left] (and [right]) in place with the knob at [value] (0.5 by default in the worklet). */
    fun process(left: FloatArray, right: FloatArray?, from: Int, to: Int, value: Double) {
        if (to <= from) return
        val knob = clamp(value, 0.0, 1.0)
        val highpass: Boolean
        val v: Double
        if (knob > 0.51) {
            highpass = true
            v = (knob - 0.5) * 2
        } else if (knob < 0.49) {
            highpass = false
            v = knob * 2
        } else {
            return // 'none': the input is the output, the state stays where it is
        }
        val cutoff = clamp(Math.pow(v * 11, 4.0), 0.0, nyquistLess1)
        // TwoPoleFilter.update with resonance 0.1
        val c = clamp(2 * Math.sin(cutoff * Math.PI * invSampleRate), 0.0, 1.14)
        val r = Math.pow(0.5, 8 * RESONANCE + 1)
        val mrc = 1 - r * c
        channel(left, 0, from, to, c, mrc, highpass)
        if (right != null) channel(right, 1, from, to, c, mrc, highpass)
    }

    private fun channel(io: FloatArray, ch: Int, from: Int, to: Int, c: Double, mrc: Double, highpass: Boolean) {
        var a = s0[ch]
        var b = s1[ch]
        if (highpass) {
            for (n in from until to) {
                val x = io[n]
                a = mrc * a - c * b + c * x
                b = mrc * b + c * a
                val y = (x - b).toFloat()
                io[n] = if (y - y == 0f) y else 0f
            }
        } else {
            for (n in from until to) {
                val x = io[n]
                a = mrc * a - c * b + c * x
                b = mrc * b + c * a
                val y = b.toFloat()
                io[n] = if (y - y == 0f) y else 0f
            }
        }
        // A NaN or infinite input leaves the memory unusable for good: restart from silence (the worklet would stay broken).
        if (a - a != 0.0 || b - b != 0.0) {
            a = 0.0
            b = 0.0
        }
        s0[ch] = a
        s1[ch] = b
    }

    private companion object {
        const val RESONANCE = 0.1
    }
}
