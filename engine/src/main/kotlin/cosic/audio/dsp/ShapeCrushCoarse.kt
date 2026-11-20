package cosic.audio.dsp

import cosic.audio.QUANTUM
import cosic.pattern.jsMathRound

/**
 * The `shape-processor` and `crush-processor` worklets (superdough `worklets.mjs:259-295` and `:225-257`, both adapted there from
 * dktr0's WebDirt). Both are memoryless; like the worklets they take their AudioParam from the start of the render quantum, so the caller
 * passes the value at the first frame of every 128-frame quantum it covers.
 */
internal object ShapeCrushCoarse {
    /**
     * `((1 + s) x / (1 + s |x|)) * postgain` with `s = 2 shape / (1 - shape)`: a soft clipper. `shape` from 1 up is `1 - 4e-10` (a very hard
     * clip), [postgain] is clamped to 0.001..1 (`shapevol`).
     */
    fun shape(left: FloatArray, right: FloatArray?, from: Int, to: Int, shape: Double, postgain: Double) {
        if (to <= from) return
        val clipped = if (shape < 1.0) shape else 1.0 - 4e-10
        val s = (2.0 * clipped) / (1.0 - clipped)
        val gain = if (postgain.isNaN()) 1.0 else Math.max(0.001, Math.min(1.0, postgain))
        shaped(left, from, to, s, gain)
        if (right != null) shaped(right, from, to, s, gain)
    }

    /** Rounds every sample to `2^(crush - 1)` steps per unit: `Math.round(x * 2^(max(1, crush) - 1)) / 2^(max(1, crush) - 1)`. */
    fun crush(left: FloatArray, right: FloatArray?, from: Int, to: Int, crush: Double) {
        if (to <= from) return
        val steps = Math.pow(2.0, (if (crush.isNaN()) 1.0 else Math.max(1.0, crush)) - 1)
        crushed(left, from, to, steps)
        if (right != null) crushed(right, from, to, steps)
    }

    private fun shaped(io: FloatArray, from: Int, to: Int, s: Double, gain: Double) {
        for (n in from until to) {
            val x = io[n].toDouble()
            io[n] = ((((1 + s) * x) / (1 + s * Math.abs(x))) * gain).toFloat()
        }
    }

    private fun crushed(io: FloatArray, from: Int, to: Int, steps: Double) {
        for (n in from until to) io[n] = (jsMathRound(io[n] * steps) / steps).toFloat()
    }
}

/**
 * The `coarse-processor` worklet (`worklets.mjs:193-223`): sample-and-hold that keeps frame `n` of a render quantum when `n % coarse < 1` and
 * otherwise repeats the previous output, with `n` counting from the start of the 128-frame quantum. So holds restart at every quantum
 * boundary, and a fractional `coarse` such as 2.5 gives the pattern of `n % 2.5 < 1`.
 *
 * `n` is taken from the absolute frame counter, so a call may start anywhere in a quantum: what carries over is the last output of each channel.
 */
internal class CoarseProcessor {
    private var heldLeft = 0f
    private var heldRight = 0f

    fun reset() {
        heldLeft = 0f
        heldRight = 0f
    }

    /**
     * Holds frames `[from, to)` in place. [startFrame] is the absolute frame of index 0 of the arrays (so index `i` is frame `startFrame + i`).
     * [coarse] is the AudioParam at the start of the quantum (1 is a bypass, values below 1 behave as 1).
     */
    fun process(left: FloatArray, right: FloatArray?, from: Int, to: Int, coarse: Double, startFrame: Long) {
        if (to <= from) return
        val c = if (coarse.isNaN()) 1.0 else Math.max(1.0, coarse)
        heldLeft = hold(left, from, to, c, startFrame, heldLeft)
        if (right != null) heldRight = hold(right, from, to, c, startFrame, heldRight)
    }

    private fun hold(io: FloatArray, from: Int, to: Int, coarse: Double, startFrame: Long, held: Float): Float {
        var last = held
        for (i in from until to) {
            val n = ((startFrame + i) % QUANTUM).toInt()
            if (n.toDouble() % coarse < 1.0) last = io[i] else io[i] = last
        }
        return last
    }
}
