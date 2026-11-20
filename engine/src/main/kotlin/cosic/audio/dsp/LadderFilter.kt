package cosic.audio.dsp

import cosic.audio.clamp

/**
 * The `ladder-processor` worklet: a four-pole transistor-ladder low-pass with a saturating `fast_tanh` in every stage
 * (superdough `worklets.mjs:365-427`, adapted there from TheBouteillacBear/webaudioworklet-wasm).
 *
 * It is a low-pass whatever `type` the chain asked for: `createFilter` builds it for `ftype('ladder')` only (`helpers.mjs:253-255`), with the
 * `frequency`, `q` and `drive` of the filter's controls passed on raw (Q is NOT in decibels here, unlike the biquad low-pass).
 *
 * The worklet reads its three AudioParams at index 0, so they are constant over a render quantum. The caller therefore passes the value
 * at the start of each 128-frame quantum (`AParam.blockStart`), and splits a call at quantum boundaries when the parameter moves.
 * State is a handful of doubles per channel; the processor itself never allocates.
 *
 * `process` differs from the worklet in one place only: a result that is not finite (an unstable setting such as a huge `q`
 * with an extreme `drive`) is written as 0 and the filter state restarts from silence, so a bad parameter cannot put NaN into the orbit's feedback loops.
 */
internal class LadderFilter(sampleRate: Int) {
    private val invSampleRate = 1.0 / sampleRate

    // Per channel: the four filter poles and three delayed copies of the last one (the output is a 4-tap FIR over them).
    private val p0 = DoubleArray(2)
    private val p1 = DoubleArray(2)
    private val p2 = DoubleArray(2)
    private val p3 = DoubleArray(2)
    private val p32 = DoubleArray(2)
    private val p33 = DoubleArray(2)
    private val p34 = DoubleArray(2)

    /** Back to silence: what a fresh processor starts with. */
    fun reset() {
        p0.fill(0.0)
        p1.fill(0.0)
        p2.fill(0.0)
        p3.fill(0.0)
        p32.fill(0.0)
        p33.fill(0.0)
        p34.fill(0.0)
    }

    /**
     * Filters frames `[from, to)` of [left] (and [right], when stereo) in place.
     *
     * @param frequency cutoff in Hz, as the `frequency` AudioParam (500 by default in the worklet).
     * @param q resonance, linear (1 by default); the feedback is `min(8, q * 0.13)` and the makeup gain `min(1.75, 1 + k)`.
     * @param drive input gain as `exp(drive)` clamped to 0.1..2000 (0.69 by default in the worklet), divided out again at the output.
     */
    fun process(left: FloatArray, right: FloatArray?, from: Int, to: Int, frequency: Double, q: Double, drive: Double) {
        if (to <= from) return
        val driveGain = clamp(Math.exp(drive), 0.1, 2000.0)
        var cutoff = frequency * TWO_PI * invSampleRate
        cutoff = if (cutoff > 1.0) 1.0 else cutoff
        val k = Math.min(8.0, q * 0.13)
        // drive makeup * resonance volume loss makeup
        val makeup = (1.0 / driveGain) * Math.min(1.75, 1.0 + k)
        channel(left, 0, from, to, driveGain, cutoff, k, makeup)
        if (right != null) channel(right, 1, from, to, driveGain, cutoff, k, makeup)
    }

    private fun channel(io: FloatArray, ch: Int, from: Int, to: Int, drive: Double, cutoff: Double, k: Double, makeup: Double) {
        var s0 = p0[ch]
        var s1 = p1[ch]
        var s2 = p2[ch]
        var s3 = p3[ch]
        var s32 = p32[ch]
        var s33 = p33[ch]
        var s34 = p34[ch]
        for (n in from until to) {
            val out = s3 * 0.360891 + s32 * 0.41729 + s33 * 0.177896 + s34 * 0.0439725
            s34 = s33
            s33 = s32
            s32 = s3
            s0 += (fastTanh(io[n] * drive - k * out) - fastTanh(s0)) * cutoff
            s1 += (fastTanh(s0) - fastTanh(s1)) * cutoff
            s2 += (fastTanh(s1) - fastTanh(s2)) * cutoff
            s3 += (fastTanh(s2) - fastTanh(s3)) * cutoff
            val y = (out * makeup).toFloat()
            if (y - y == 0f) {
                io[n] = y
            } else {
                // NaN or infinite: unstable parameters. Restart from silence rather than keep poisoning the chain.
                io[n] = 0f
                s0 = 0.0
                s1 = 0.0
                s2 = 0.0
                s3 = 0.0
                s32 = 0.0
                s33 = 0.0
                s34 = 0.0
            }
        }
        p0[ch] = s0
        p1[ch] = s1
        p2[ch] = s2
        p3[ch] = s3
        p32[ch] = s32
        p33[ch] = s33
        p34[ch] = s34
    }

    private companion object {
        const val TWO_PI = 2.0 * Math.PI

        /** `fast_tanh` of the worklet: `x (27 + x^2) / (27 + 9 x^2)`, exact at 0 and ±3, and not bounded: its slope tends to 1/9 for large |x|. */
        @Suppress("NOTHING_TO_INLINE")
        inline fun fastTanh(x: Double): Double {
            val x2 = x * x
            return (x * (27.0 + x2)) / (27.0 + 9.0 * x2)
        }
    }
}
