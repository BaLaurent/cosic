package cosic.audio.sources

import cosic.audio.AParam
import cosic.audio.BLOCK_FRAMES
import cosic.audio.QUANTUM
import cosic.audio.STEAL_RAMP_SECONDS
import cosic.audio.getADSRValues
import cosic.audio.getParamADSR

/*
 * What the synth sources of superdough (synth.mjs) have in common: the amplitude envelope inside the source node, the level of the node that
 * follows it, how a worklet-based source starts and stops on the render quantum grid, and the float32 values of Web Audio parameters.
 */

/** The gain node superdough puts after a stock oscillator or a noise (`gainNode(0.3)`, "turn down"), as the float32 the node holds. */
internal const val SOURCE_GAIN = 0.3f

/** The envelope of a source that has no values of its own (`getADSRValues(..., 'linear', [0.001, 0.05, 0.6, 0.01])`): `[attack, decay, sustain, release]`. */
internal val SYNTH_ADSR = doubleArrayOf(0.001, 0.05, 0.6, 0.01)

/** A source stops 10 ms after its release is over (`envEnd = holdEnd + release + 0.01`). */
internal const val SOURCE_TAIL_SECONDS = 0.01

/**
 * Where a source draws random numbers in `[0, 1)` from (the start phases of a supersaw, `random()` of a bytebeat): `Math.random` in superdough, the source's own
 * generator here. An interface with a primitive return type, because a lambda `() -> Double` boxes every number it returns, which the audio thread must not do.
 */
internal fun interface RandomSource {
    fun next(): Double
}

/** The nominal range of an AudioParam that declares none: plus and minus the largest float32. */
private const val PARAM_LIMIT = 3.4028234663852886e38

/** A value as a Web Audio parameter holds it: a float32 (`param.value = x` rounds). The worklets and the oscillators read it back as that float. */
internal fun asParam(x: Double): Double = x.toFloat().toDouble()

/**
 * The first frame at or after [seconds]. `seconds * sampleRate` carries the rounding error of the product (1.02 s at 48 kHz is 48960.00000000001
 * frames), which `ceil` alone would turn into one frame too many, so an error that small is dropped first.
 */
internal fun frameAtOrAfter(seconds: Double, sampleRate: Int): Long {
    val frames = seconds * sampleRate
    if (frames.isNaN()) return 0L
    if (frames > 9.0e16) return 9_000_000_000_000_000L
    if (frames < -9.0e16) return -9_000_000_000_000_000L
    return Math.ceil(frames - 1e-6).toLong()
}

/** A control as the raw value `getADSRValues` sees: NaN when it is not set. */
private fun SourceControls.envelopeValue(key: String): Double = number(key) ?: Double.NaN

/**
 * The amplitude envelope of a source: the gain automation `getParamADSR(envGain.gain, attack, decay, sustain, release, 0, peak, t, holdEnd, 'linear')`
 * that superdough schedules on the `GainNode` after the oscillator (synth.mjs:44-61, 183-206, 280-290). [attack], [decay], [sustain] and [release] are what
 * `getADSRValues` makes of the pattern's values: [defaults] when none of the four is set. The gain is 1 until the envelope's first event.
 */
internal class SourceEnvelope(private val sampleRate: Int, controls: SourceControls, private val peak: Double, defaults: DoubleArray = SYNTH_ADSR) {
    private val values = getADSRValues(
        controls.envelopeValue("attack"), controls.envelopeValue("decay"), controls.envelopeValue("sustain"), controls.envelopeValue("release"), defaults,
    )
    val attack: Double get() = values[0]
    val decay: Double get() = values[1]
    val sustain: Double get() = values[2]
    val release: Double get() = values[3]

    private val gain = AParam(1.0, -PARAM_LIMIT, PARAM_LIMIT, 12)

    /**
     * Schedules the envelope for a source that starts at [t] seconds and is held for [durationSec]; returns when the source stops, `t + duration + release + 0.01`
     * seconds. Allocation-free.
     */
    fun schedule(t: Double, durationSec: Double): Double {
        gain.reset(1.0)
        val holdEnd = t + durationSec
        getParamADSR(gain, attack, decay, sustain, release, 0.0, peak, t, holdEnd, false)
        return holdEnd + release + SOURCE_TAIL_SECONDS
    }

    /** The source is stolen by a voice that starts at [tNew]: `gain.linearRampToValueAtTime(0, tNew + 0.25)` (superdough.mjs:546-555), on the same timeline as the envelope. */
    fun steal(tNew: Double) {
        gain.linearRampToValueAtTime(0.0, tNew + STEAL_RAMP_SECONDS, tNew)
    }

    /** Writes the gain of frames `firstFrame until firstFrame + n` (absolute) to `out[0 until n]`. */
    fun fill(out: FloatArray, firstFrame: Long, n: Int) {
        gain.timeline.fillBlock(out, firstFrame, sampleRate, n, gain.base)
    }
}

/**
 * When a source worklet of superdough produces sound. Its `process` skips every render quantum (128 frames, aligned to the engine's absolute frame counter)
 * whose start `currentTime` is not after `begin`, and stops for good at the first one that starts at or after `end` (supersaw: worklets.mjs:520-526; pulse and
 * bytebeat: `currentTime <= params.begin[0]`, `currentTime >= params.end[0]`). `begin` and `end` are AudioParams, so they are the float32 values. A note that starts
 * mid-quantum therefore sounds from the next quantum on (1 to 128 frames late), and one that starts exactly on a quantum boundary only from the quantum after it.
 *
 * [open] works out the span once; [from] until [to] (absolute frames, multiples of 128) is where the worklet runs.
 */
internal class WorkletGate(private val sampleRate: Int) {
    var from = 0L
        private set
    var to = 0L
        private set

    fun open(beginSec: Double, endSec: Double, startFrame: Long) {
        val begin = asParam(beginSec)
        val end = asParam(endSec)
        var first = Math.floorDiv(startFrame, QUANTUM.toLong())
        while (quantumTime(first) <= begin) first++
        from = first * QUANTUM
        val endFrames = end * sampleRate
        if (!(endFrames < NEVER_FRAMES)) { // a NaN or a huge `end`: `currentTime >= end` is never true
            to = Long.MAX_VALUE
            return
        }
        // the first quantum that starts at or after `end`: the estimate is one or two below it
        var last = Math.max(first, Math.floorDiv(endFrames.toLong(), QUANTUM.toLong()) - 1)
        while (quantumTime(last) < end) last++
        to = last * QUANTUM
    }

    private fun quantumTime(q: Long): Double = (q * QUANTUM).toDouble() / sampleRate

    private companion object {
        const val NEVER_FRAMES = 9.0e16
    }
}

/**
 * A [SoundSource] that renders in blocks of at most [BLOCK_FRAMES] frames (the size of every per-block buffer of a voice) and produces nothing from [endFrame] on.
 * A subclass starts by setting [frame], [endFrame] and [endSeconds] in `start`, and fills one block in [renderBlock].
 */
internal abstract class BlockSource : SoundSource {
    /** The absolute engine frame the next block starts at. */
    protected var frame = 0L

    /** The first frame the source no longer produces. Before the source is started it never finishes. */
    protected var endFrame = Long.MAX_VALUE

    final override var endSeconds = 0.0
        protected set

    final override val isFinished: Boolean get() = frame >= endFrame

    /** Fills `left[offset until offset + m]` (and `right`, for a stereo source) with the frames `frame until frame + m`; the base class advances [frame]. */
    protected abstract fun renderBlock(left: FloatArray, right: FloatArray, offset: Int, m: Int)

    final override fun render(left: FloatArray, right: FloatArray, n: Int): Int {
        var done = 0
        while (done < n) {
            val remaining = endFrame - frame
            if (remaining <= 0L) break
            var m = Math.min(BLOCK_FRAMES, n - done)
            if (remaining < m) m = remaining.toInt()
            renderBlock(left, right, done, m)
            // a worklet fed absurd controls writes NaN; a filter or a delay line behind this source would keep it for ever
            for (i in done until done + m) if (left[i] - left[i] != 0f) left[i] = 0f
            if (channels == 2) for (i in done until done + m) if (right[i] - right[i] != 0f) right[i] = 0f
            frame += m
            done += m
        }
        if (done < n) {
            java.util.Arrays.fill(left, done, n, 0f)
            if (channels == 2) java.util.Arrays.fill(right, done, n, 0f)
        }
        return done
    }
}
