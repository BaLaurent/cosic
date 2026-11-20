package cosic.audio

import cosic.SampleData
import cosic.audio.sources.SourceRng
import cosic.pattern.logger
import java.util.concurrent.atomic.AtomicReference

/** `generate(d = 2, fade = 0.1, lp = 15000, dim = 1000, ...)` of reverb.mjs: what an argument that is not set becomes. */
private const val DEFAULT_ROOM_SECONDS = 2.0
private const val DEFAULT_FADE_SECONDS = 0.1
private const val DEFAULT_LOWPASS_START = 15000.0
private const val DEFAULT_LOWPASS_END = 1000.0

private const val GOLDEN_GAMMA = 0x9E3779B97F4A7C15UL

/** Whether a room of [roomsize] seconds (NaN: not set) can be built; `createBuffer` throws for an empty impulse response, and then the event does not sound. */
internal fun roomCanBeBuilt(roomsize: Double, sampleRate: Int): Boolean {
    if (roomsize.isNaN()) return true
    return ReverbGen.frames(Math.min(roomsize, MAX_ROOM_SECONDS), sampleRate) >= 1
}

/**
 * superdough's `ConvolverNode` reverb of an orbit (reverb.mjs `createReverb`, superdoughoutput.mjs `getReverb`): a convolver whose impulse response is
 * generated (or taken from a sample) when the first voice asks for the reverb, and regenerated when a later voice asks for another room.
 *
 * The impulse response is built and its partitions are transformed on the [executor] (the real-time engine hands in a background thread, so the
 * audio thread never does it); until it is ready the reverb is silent, as the `ConvolverNode` is without a buffer. The audio thread polls for the finished
 * [Convolver] at the start of every [process] and swaps it in, with empty state (`convolver.buffer = ...`). Of several requests only the newest is used.
 * Nothing on the audio thread allocates except [request], which allocates the small job (and only when the room changes).
 */
internal class ReverbBus(private val sampleRate: Int, private val executor: IrExecutor, private val seed: Long) {
    private val ready = AtomicReference<Convolver?>(null)
    private var convolver: Convolver? = null

    @Volatile
    private var newest = 0
    /** How many impulse responses have been requested (generated or taken from a sample) so far. */
    var requests = 0
        private set

    /** The length in frames of the impulse response the convolver was last built with (0 before the first is ready). */
    @Volatile
    var impulseFrames = 0
        private set

    private var created = false

    // what the convolver was last generated with (`convolver.duration`, `.fade`, ... of the original): the arguments after their defaults, except the two the original
    // leaves undefined (NaN here) and the impulse response sample
    var duration = Double.NaN
        private set
    var fade = Double.NaN
        private set
    var lp = Double.NaN
        private set
    var dim = Double.NaN
        private set
    var irspeed = Double.NaN
        private set
    var irbegin = Double.NaN
        private set
    private var ir: SampleData? = null

    /**
     * `getReverb(duration, fade, lp, dim, ir, irspeed, irbegin)`: creates the reverb on the first call and regenerates it when any argument that is set
     * differs from the last generation's (`hasChanged`: an argument that is not set never counts) or the impulse response is another sample. A regeneration uses
     * the arguments of this call, so one that is not set becomes its default, not what it was. Returns false when the call cannot be served: [ReverbParams.irName]
     * asks for a sample that is not loaded.
     */
    fun update(p: ReverbParams): Boolean {
        if (p.irName != null && p.irSample == null) return false
        if (!created) {
            created = true
            generate(p)
        } else if (hasChanged(p.duration, duration) || hasChanged(p.fade, fade) || hasChanged(p.lp, lp) || hasChanged(p.dim, dim) ||
            hasChanged(p.irspeed, irspeed) || hasChanged(p.irbegin, irbegin) || p.irSample !== ir
        ) {
            generate(p)
        }
        return true
    }

    /**
     * A reverb of the room [p] describes for a voice of its own (the `FX(...)` elements, which build a reverb per voice): the impulse response is only generated when it
     * would differ from the one the bus has, and the input history is emptied.
     */
    fun setup(p: ReverbParams): Boolean {
        if (p.irName != null && p.irSample == null) return false
        if (isSetUpFor(p)) {
            clear()
        } else {
            created = true
            generate(p)
        }
        return true
    }

    /** Whether the impulse response the bus has (or is getting) is the one [setup] would build for [p]. */
    fun isSetUpFor(p: ReverbParams): Boolean {
        val d = if (p.duration.isNaN()) DEFAULT_ROOM_SECONDS else p.duration
        return created && d == duration && orDefault(p.fade, DEFAULT_FADE_SECONDS) == fade && orDefault(p.lp, DEFAULT_LOWPASS_START) == lp &&
            orDefault(p.dim, DEFAULT_LOWPASS_END) == dim && sameOrBothUnset(p.irspeed, irspeed) && sameOrBothUnset(p.irbegin, irbegin) && p.irSample === ir
    }

    /** Empties the convolver's memory; its impulse response stays. */
    fun clear() {
        convolver?.reset()
    }

    /** Reverberates the first [n] frames of [inL], [inR] into [outL], [outR] (other arrays): silence while there is no impulse response yet. */
    fun process(inL: FloatArray, inR: FloatArray, outL: FloatArray, outR: FloatArray, n: Int) {
        val next = ready.getAndSet(null)
        if (next != null) convolver = next
        val c = convolver
        if (c == null) {
            java.util.Arrays.fill(outL, 0, n, 0f)
            java.util.Arrays.fill(outR, 0, n, 0f)
        } else {
            c.process(inL, inR, outL, outR, n)
        }
    }

    private fun generate(p: ReverbParams) {
        duration = if (p.duration.isNaN()) DEFAULT_ROOM_SECONDS else p.duration
        fade = orDefault(p.fade, DEFAULT_FADE_SECONDS)
        lp = orDefault(p.lp, DEFAULT_LOWPASS_START)
        dim = orDefault(p.dim, DEFAULT_LOWPASS_END)
        irspeed = p.irspeed
        irbegin = p.irbegin
        ir = p.irSample
        val room = Math.min(duration, MAX_ROOM_SECONDS)
        if (p.irSample == null && ReverbGen.frames(room, sampleRate) < 1) {
            logger("reverb: a room of $duration seconds has no impulse response", "error", null)
            return
        }
        val number = ++requests
        newest = number
        val state = (seed.toULong() + GOLDEN_GAMMA * number.toULong()).toLong()
        executor.submit(Job(number, room, fade, lp, dim, p.irSample, irspeed, irbegin, state))
    }

    private inner class Job(
        private val number: Int,
        private val room: Double,
        private val fade: Double,
        private val lp: Double,
        private val dim: Double,
        private val sample: SampleData?,
        private val speed: Double,
        private val begin: Double,
        private val state: Long,
    ) : Runnable {
        override fun run() {
            if (number != newest) return // a newer request has been made in the meantime
            try {
                val response = if (sample != null) {
                    ReverbGen.adjustLength(room, sample.toImpulseResponse(sampleRate), speed, begin)
                } else {
                    val rng = SourceRng(state)
                    ReverbGen.generate(sampleRate, room, fade, lp, dim) { rng.nextDouble() }
                }
                val convolver = Convolver(PreparedImpulse.prepare(response))
                if (number == newest) {
                    impulseFrames = response.length
                    ready.set(convolver)
                }
            } catch (e: Throwable) {
                logger("reverb: could not build the impulse response: ${e.message ?: e}", "error", null)
            }
        }
    }

    private companion object {
        fun hasChanged(now: Double, before: Double): Boolean = !now.isNaN() && now != before

        fun orDefault(v: Double, default: Double): Double = if (v.isNaN()) default else v

        fun sameOrBothUnset(a: Double, b: Double): Boolean = if (a.isNaN()) b.isNaN() else a == b
    }
}
