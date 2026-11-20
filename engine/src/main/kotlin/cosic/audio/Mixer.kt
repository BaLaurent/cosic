package cosic.audio

import kotlin.math.max
import kotlin.math.min

/**
 * The whole sound engine behind the scheduler: a pool of [VOICE_SLOTS] [Voice]s, the orbits they mix into, and the master output (the
 * orbits summed and hard-clamped; Strudel has no master gain and no limiter). Not thread-safe: the audio thread owns it.
 *
 * Usage: call [startVoice] for each event exactly when it is due, then [render] up to the next event. Voices start on the first frame
 * rendered after [startVoice], so splitting `render` calls at event boundaries is sample-exact. Rendering is independent of how it
 * is split into calls, and neither it nor [startVoice] allocates (except for the first voice of a new orbit number).
 *
 * The mixer counts frames from the first one it renders: [frame] is the frame the next voice starts on, and `frame / sampleRate`
 * the time every automation of every voice is written in.
 */
internal class Mixer(val sampleRate: Int, irExecutor: IrExecutor = IrExecutor.INLINE) {
    private val orbits = OrbitSet(sampleRate, irExecutor)
    private val buses = AudioBuses(sampleRate)
    private val fxPool = FxGroupPool(sampleRate)
    private val voices = Array(VOICE_SLOTS) { Voice(sampleRate, orbits, buses, fxPool) }
    private val ledger = PolyphonyLedger(VOICE_SLOTS)
    private val block = AudioBlock(BLOCK_FRAMES)
    private val ctx = BlockContext(sampleRate)
    private val stopGain = FloatArray(BLOCK_FRAMES)
    private val stealAction = StealAction { voice, tNew -> voice.steal(tNew) }
    private var started = 0L

    /** How long the stop begun by [stopAll] lasts, in frames, and how many of them are left; 0 when none is under way. */
    private var stopFrames = 0
    private var stopFramesLeft = 0

    /** The number of frames rendered so far: the frame a voice started now begins on. */
    var frame = 0L
        private set

    /**
     * Strudel's `maxPolyphony`: voices that count at once before the oldest is ramped down. 1 to 192, 128 by default (more would not fit
     * the pool: stolen and cut voices keep their slot for a while).
     */
    var maxPolyphony: Int = DEFAULT_MAX_POLYPHONY
        set(value) {
            field = value.coerceIn(1, MAX_POLYPHONY_LIMIT)
        }

    /** Strudel's `multiChannelOrbits`: orbit `n` plays on output channels `2n - 1` and `2n` instead of 1 and 2. */
    var multiChannelOrbits = false

    /**
     * Voices that can make sound now (see [Voice.isAudibleAt]) and have not been told to fade out. Voices fading after a cut, a steal or
     * [stopAll] are not counted, so this is zero right after [stopAll] even though their tails still sound. A voice that is only waiting
     * for its chain to be released is not counted either; [countedVoices] has those.
     */
    val activeVoices: Int
        get() {
            var count = 0
            for (voice in voices) if (voice.isAudibleAt(frame) && !voice.isFading) count++
            return count
        }

    /** Whether any voice can still make sound, those fading out included. When it is false only the orbits can ring on. */
    val isSounding: Boolean
        get() {
            for (voice in voices) if (voice.isAudibleAt(frame)) return true
            return false
        }

    /**
     * Voices that count toward [maxPolyphony] now: the ones whose chain has not been released, silent or not (the stolen and the stopped ones
     * are not among them).
     */
    val countedVoices: Int
        get() {
            ledger.expire(frame.toDouble() / sampleRate)
            return ledger.size
        }

    /**
     * Starts a voice from [params] on the next frame rendered. Returns false when the event cannot sound (see [canSound]) or finds no orbit,
     * in which case nothing happens at all: no voice is stolen or cut. With [maxPolyphony] voices already counted, the oldest is
     * ramped down over 0.25 s to make room (superdough.mjs:546-555); a sample with a `cut` group ramps down the previous sample of its
     * group over 10 ms.
     */
    fun startVoice(params: VoiceParams): Boolean {
        if (!params.canSound()) return false
        val t = frame.toDouble() / sampleRate
        ledger.expire(t)
        val voice = claimVoice()
        ledger.remove(voice)
        started++
        val activeUntil = voice.start(params, frame, started, (started.toInt() * -1640531527) or 1, multiChannelOrbits)
        if (activeUntil.isNaN()) return false
        ledger.admit(voice, t, activeUntil, maxPolyphony, stealAction)
        if (voice.isSample && voice.hasCut) {
            // sampler.mjs:365-373: only the previous sample of the group fades, from the new voice's `t + nudge` over 10 ms
            for (other in voices) {
                if (other !== voice && other.isSample && other.canBeCut && other.cutGroup == voice.cutGroup) other.cut(voice.cutTime)
            }
        }
        return true
    }

    /**
     * Stops everything, which is what stopping playback does: every voice fades out over [fadeMs] milliseconds, the
     * output fades out with them, so that the effect tails do too, and once that is over the orbits are emptied. Nothing rings on
     * after it, and nothing is cut off while it is still loud.
     */
    fun stopAll(fadeMs: Int) {
        val frames = max(1, fadeMs * sampleRate / 1000)
        for (voice in voices) if (voice.isActive) voice.fadeOut(frames)
        ledger.clear()
        stopFrames = frames
        stopFramesLeft = frames
    }

    /** Empties the orbits' tails and the buses; call when going idle so old tails cannot return. */
    fun clearBuses() {
        orbits.clear()
        buses.clear()
    }

    /**
     * Renders [frames] frames of interleaved stereo into `out[frameOffset * 2 until (frameOffset + frames) * 2]`,
     * overwriting it (silence is written as zeros). Output is always within `[-1, 1]`.
     */
    fun render(out: FloatArray, frames: Int, frameOffset: Int = 0) {
        var done = 0
        while (done < frames) {
            var n = min(BLOCK_FRAMES, frames - done)
            if (stopFramesLeft > 0) n = min(n, stopFramesLeft)
            ctx.startFrame = frame
            ctx.n = n
            orbits.beginBlock(n)
            for (voice in voices) if (voice.isActive) voice.render(ctx, block)

            if (stopFramesLeft > 0) {
                val step = 1f / stopFrames // the gain falls to 0 over the frames left
                for (i in 0 until n) stopGain[i] = step * (stopFramesLeft - i)
            } else {
                java.util.Arrays.fill(stopGain, 0, n, 1f)
            }
            orbits.render(out, frameOffset + done, n, ctx, stopGain)
            frame += n
            done += n
            if (stopFramesLeft > 0) {
                stopFramesLeft -= n
                if (stopFramesLeft == 0) clearBuses()
            }
        }
    }

    /** A free slot; if every slot is busy, the oldest voice is reused (and cut off). */
    private fun claimVoice(): Voice {
        var oldest: Voice? = null
        for (voice in voices) {
            if (!voice.isActive) return voice
            if (oldest == null || voice.order < oldest.order) oldest = voice
        }
        return oldest!!
    }

    private companion object {
        const val MAX_POLYPHONY_LIMIT = 192
    }
}
