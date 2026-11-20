package cosic.audio.dsp

import cosic.audio.QUANTUM
import cosic.audio.clamp
import cosic.audio.dbToLin

/**
 * The `transient-processor` worklet behind `transient` and `transsustain` (superdough `worklets.mjs:1417-1494`, instantiated in
 * `superdough.mjs:655-671`): a transient designer that raises or lowers attacks and sustains by comparing a fast and a slow envelope follower.
 *
 * Per sample and channel: `x = |s|`, both followers move toward `x` (time constants [attackTime] 3 ms and [sustainTime] 80 ms), the "peakiness"
 * `clamp(scaling * (attEnv - susEnv) / (susEnv + 1e-6), -1.5, 1.5)` picks the attack side (positive: up to +18 dB times `attack`) or the sustain
 * side (negative: up to +36 dB times `sustain`), the gain is clamped to 0..8, divided by a slow average of itself ("makeup"), and the result is
 * `y / (1 + |y|)`. That soft clip is applied even with `attack = sustain = 0`.
 *
 * Quirks kept on purpose: the average gain is shared by the channels (the worklet runs channel 0's whole quantum first, so in stereo it moves
 * twice per frame), and the two envelope followers are rounded to float32 once per quantum, where the worklet keeps them in a `Float32Array`.
 *
 * Gating follows the worklet: it only works while `begin < currentTime < end`, where `currentTime` is the start of the render quantum in
 * seconds. Before that the output is silence (the worklet returns without writing), from the first quantum that starts at or after
 * `end` it is silence for good (the node returns `false`).
 *
 * Quanta come from the absolute frame counter, so a call may start and stop anywhere. In stereo a call that splits a quantum runs channel 0 and
 * then channel 1 of each piece (the worklet does so for the whole quantum), which only matters through the shared average gain.
 */
internal class TransientShaper(private val sampleRate: Int) {
    private var attackCoeff = 0.0
    private var sustainCoeff = 0.0
    private val gainCoeff = timeToCoeff(0.2)
    private var attackAmt = 0.0
    private var sustainAmt = 0.0
    private var scaling = 0.0
    private var mix = 1.0
    private var begin = 0.0
    private var end = 0.0

    private var avgGain = 1.0
    private var attackEnvLeft = 0.0
    private var attackEnvRight = 0.0
    private var sustainEnvLeft = 0.0
    private var sustainEnvRight = 0.0
    private var finished = false

    init {
        configure(0.0, 0.0, 0.0, 0.0)
    }

    /**
     * Sets the shaper for a new voice and clears its state; allocation-free.
     *
     * @param attack the `transient` control (-1..1; NaN is unset, 0), the amount applied to attacks.
     * @param sustain the `transsustain` control (-1..1; NaN is unset, 0), the amount applied to sustains.
     * @param beginSec the time the voice starts, in seconds: the processor is silent until a quantum starts after it.
     * @param endSec the time of the end of the voice including its release (`endWithRelease`).
     */
    fun configure(
        attack: Double,
        sustain: Double,
        beginSec: Double,
        endSec: Double,
        attackTime: Double = 0.003,
        sustainTime: Double = 0.08,
        sensitivity: Double = 0.1,
        mix: Double = 1.0,
    ) {
        attackCoeff = timeToCoeff(clamp(attackTime, 0.0005, 0.05))
        sustainCoeff = timeToCoeff(clamp(sustainTime, 0.01, 0.5))
        attackAmt = clamp(if (attack.isNaN()) 0.0 else attack, -1.0, 1.0)
        sustainAmt = clamp(if (sustain.isNaN()) 0.0 else sustain, -1.0, 1.0)
        scaling = 0.5 + 5 * clamp(sensitivity, 0.0, 1.0)
        this.mix = clamp(mix, 0.0, 1.0)
        begin = beginSec
        end = endSec
        reset()
    }

    /** Clears the followers and the average gain; the configuration stays. */
    fun reset() {
        avgGain = 1.0
        attackEnvLeft = 0.0
        attackEnvRight = 0.0
        sustainEnvLeft = 0.0
        sustainEnvRight = 0.0
        finished = false
    }

    /** True once a quantum has started at or after `end`: the shaper outputs silence from then on. */
    val isFinished: Boolean get() = finished

    /**
     * Shapes frames `[from, to)` of [left] (and [right], when stereo) in place. [startFrame] is the absolute frame of index 0 of the arrays; the
     * render quanta, and with them `currentTime`, are taken from it.
     */
    fun process(left: FloatArray, right: FloatArray?, from: Int, to: Int, startFrame: Long) {
        var i = from
        while (i < to) {
            val frame = startFrame + i
            val quantum = Math.floorDiv(frame, QUANTUM.toLong())
            val quantumEnd = (quantum + 1) * QUANTUM
            val pieceEnd = Math.min(to.toLong(), quantumEnd - startFrame).toInt()
            val currentTime = (quantum * QUANTUM).toDouble() / sampleRate
            if (finished || currentTime >= end) {
                finished = true
                silence(left, right, i, pieceEnd)
            } else if (currentTime <= begin) {
                silence(left, right, i, pieceEnd)
            } else {
                var avg = avgGain
                avg = channel(left, i, pieceEnd, false, avg)
                if (right != null) avg = channel(right, i, pieceEnd, true, avg)
                avgGain = avg
                if (startFrame + pieceEnd == quantumEnd) {
                    // The worklet keeps the followers in a Float32Array and stores them once per quantum.
                    attackEnvLeft = attackEnvLeft.toFloat().toDouble()
                    attackEnvRight = attackEnvRight.toFloat().toDouble()
                    sustainEnvLeft = sustainEnvLeft.toFloat().toDouble()
                    sustainEnvRight = sustainEnvRight.toFloat().toDouble()
                }
            }
            i = pieceEnd
        }
    }

    private fun silence(left: FloatArray, right: FloatArray?, from: Int, to: Int) {
        left.fill(0f, from, to)
        right?.fill(0f, from, to)
    }

    /** One channel of one piece of a quantum; returns the updated shared average gain. */
    private fun channel(io: FloatArray, from: Int, to: Int, isRight: Boolean, avgGainIn: Double): Double {
        var att = if (isRight) attackEnvRight else attackEnvLeft
        var sus = if (isRight) sustainEnvRight else sustainEnvLeft
        var avg = avgGainIn
        for (n in from until to) {
            val input = io[n]
            val sample = if (input - input == 0f) input.toDouble() else 0.0 // NaN and infinity would stay in the followers
            val x = Math.abs(sample)
            att = lerp(att, x, attackCoeff)
            sus = lerp(sus, x, sustainCoeff)
            val peakiness = clamp((scaling * (att - sus)) / (sus + 1e-6), -1.5, 1.5)
            val attScale = if (peakiness > 0) peakiness else 0.0
            val susScale = if (peakiness < 0) -peakiness else 0.0
            val attackGain = dbToLin(attackAmt * attScale * 18)
            val sustainGain = dbToLin(sustainAmt * susScale * 36)
            val gain = clamp(attackGain * sustainGain, 0.0, 8.0)
            avg = lerp(avg, gain, gainCoeff)
            val makeup = if (avg > 1e-3) 1 / avg else 1.0
            val wet = sample * gain * makeup
            var y = lerp(sample, wet, mix)
            y /= 1 + Math.abs(y) // soft clip
            io[n] = y.toFloat()
        }
        if (isRight) {
            attackEnvRight = att
            sustainEnvRight = sus
        } else {
            attackEnvLeft = att
            sustainEnvLeft = sus
        }
        return avg
    }

    private fun timeToCoeff(t: Double): Double = 1 - Math.exp(-(1.0 / sampleRate) / t)

    private companion object {
        /** `lerp(a, b, n) = n (b - a) + a`. */
        @Suppress("NOTHING_TO_INLINE")
        inline fun lerp(a: Double, b: Double, n: Double): Double = n * (b - a) + a
    }
}
