package cosic.io

import cosic.FrameClock
import kotlin.math.abs
import kotlin.math.exp

/** `expDecay(a, b, decay, dt)` of clockbridge.mjs: [a] approaches [b] by the factor `exp(-decay * dt)`. */
internal fun expDecay(a: Double, b: Double, decay: Double, dt: Double): Double = b + (a - b) * exp(-decay * dt)

/** A change of the clock offset bigger than this many milliseconds snaps the estimate: the system slept, or the audio route changed. */
private const val SNAP_MILLIS = 20.0

/** How fast the estimate follows the offsets it is given: `1 / 10000` per millisecond (`decay` of `getOffset()`). */
private const val DECAY_PER_MILLI = 1.0 / 10000.0

/**
 * The bridge between the frames the engine renders and the system clock (`System.nanoTime()`), which MIDI and OSC time stamps are
 * given in (`ClockBridge` of superdough/clockbridge.mjs). The audio device says now and then which frame of its track is being heard
 * at which nanosecond ([observe]); the offset between engine time and system time that results is smoothed so that the jitter of
 * those reports does not reach the time stamps, and snaps when it jumps.
 *
 * Until the first observation there is no estimate and [nanoTimeAtFrame] is null (the audio device has not reported a time stamp
 * yet); a host may start a [beginSession] to have a rough estimate (the time its audio track was started, plus a latency) meanwhile.
 *
 * [observe] and [beginSession] are called by one thread (the audio thread or the thread that owns the track); the readers may be any.
 */
class ClockBridge : FrameClock {
    /** The smoothed offset in milliseconds, `p` of the class in Strudel: system time of an engine frame is `frame * 1000 / sampleRate + p`. */
    private class Estimate(val offsetMillis: Double, val lastTimeMillis: Double, val sampleRate: Int)

    /** Before the first observation: the engine frame [frameAtStart] was written when the system time was [startNanos]. */
    private class Rough(val startNanos: Long, val frameAtStart: Long, val sampleRate: Int, val latencyNanos: Long)

    @Volatile
    private var estimate: Estimate? = null

    @Volatile
    private var rough: Rough? = null

    /**
     * The audio track said that its frame [framePosition] is being heard at the system time [nanoTime] (nanoseconds of
     * `System.nanoTime()`). [frameOffset] is the engine frame that was the first of the track, so that `framePosition + frameOffset` is
     * an engine frame. The reported times may jitter by a few milliseconds.
     */
    fun observe(framePosition: Long, nanoTime: Long, sampleRate: Int, frameOffset: Long) {
        val timeMillis = nanoTime / 1e6
        val offset = timeMillis - (framePosition + frameOffset) * 1000.0 / sampleRate
        val previous = estimate
        val smoothed = when {
            previous == null -> offset
            abs(offset - previous.offsetMillis) > SNAP_MILLIS -> offset
            else -> expDecay(previous.offsetMillis, offset, DECAY_PER_MILLI, timeMillis - previous.lastTimeMillis)
        }
        estimate = Estimate(smoothed, timeMillis, sampleRate)
    }

    /**
     * Starts a track: forgets the estimate of the one before and answers [nanoTimeAtFrame] with a rough one until the track has
     * reported a time stamp. [startNanos] is the system time at which the engine frame [engineFrameAtStart] was handed to the track,
     * and the frames are heard [latencyMillis] later (the buffers in between).
     */
    fun beginSession(startNanos: Long, engineFrameAtStart: Long, sampleRate: Int, latencyMillis: Double = DEFAULT_LATENCY_MILLIS) {
        estimate = null
        rough = Rough(startNanos, engineFrameAtStart, sampleRate, Math.round(latencyMillis * 1e6))
    }

    /** Forgets everything: there is no estimate until the next [observe]. */
    fun reset() {
        estimate = null
        rough = null
    }

    override fun nanoTimeAtFrame(frame: Long): Long? {
        estimate?.let { return Math.round((frame * 1000.0 / it.sampleRate + it.offsetMillis) * 1e6) }
        val r = rough ?: return null
        return r.startNanos + Math.round((frame - r.frameAtStart) * 1e9 / r.sampleRate) + r.latencyNanos
    }

    override fun frameAtNanoTime(nano: Long): Long? {
        estimate?.let { return Math.round((nano / 1e6 - it.offsetMillis) * it.sampleRate / 1000.0) }
        val r = rough ?: return null
        return r.frameAtStart + Math.round((nano - r.startNanos - r.latencyNanos) * r.sampleRate / 1e9)
    }

    /** The smoothed offset in milliseconds, or null before the first observation. */
    val offsetMillis: Double? get() = estimate?.offsetMillis

    companion object {
        /** What a track is assumed to be late by until it reports its first time stamp. */
        const val DEFAULT_LATENCY_MILLIS = 40.0
    }
}
