package cosic.audio

import kotlin.math.max
import kotlin.math.min

/** Voices that may sound at once; a new voice beyond this steals the oldest. */
private const val POLYPHONY = 64

/** Voices being faded out still occupy a slot, so the pool is larger than the polyphony. */
private const val VOICE_SLOTS = POLYPHONY * 2

/** Frames rendered per internal block; buffers are sized for it. */
private const val BLOCK = 256

private const val MASTER_GAIN = 0.8f
private const val STEAL_FADE_MS = 5
private const val CUT_FADE_MS = 5

/**
 * The whole sound engine behind the scheduler: a pool of 64 [Voice]s, cut groups, the shared delay and reverb
 * buses, and the master chain (gain 0.8, peak limiter, hard clip). Not thread-safe: the audio thread owns it.
 *
 * Usage: call [startVoice] for each event exactly when it is due, then [render] up to the next event. Voices start
 * on the first frame rendered after [startVoice], so splitting `render` calls at event boundaries is sample-exact.
 * Rendering is independent of how it is split into calls, and neither it nor [startVoice] allocates.
 */
internal class Mixer(val sampleRate: Int) {
    private val voices = Array(VOICE_SLOTS) { Voice(sampleRate) }
    private val buffers = VoiceBuffers(BLOCK)
    private val delay = DelayBus(sampleRate)
    private val reverb = Reverb(sampleRate)
    private val limiter = Limiter(sampleRate)
    private var started = 0L

    /** How long the stop begun by [stopAll] lasts, in frames, and how many of them are left; 0 when none is under way. */
    private var stopFrames = 0
    private var stopFramesLeft = 0

    /**
     * Voices that are sounding and have not been told to fade out. Voices fading after a cut, a steal or
     * [stopAll] are not counted, so this is zero right after [stopAll] even though their tails still sound.
     */
    val activeVoices: Int
        get() {
            var count = 0
            for (voice in voices) if (voice.isActive && !voice.isFading) count++
            return count
        }

    /** Whether any voice still makes sound, those fading out included. When it is false only the buses can ring on. */
    val isSounding: Boolean
        get() {
            for (voice in voices) if (voice.isActive) return true
            return false
        }

    /**
     * Starts a voice from raw [params] (clamped here, see [VoiceParams]). Returns false when the event cannot sound
     * (see [canSound]), in which case nothing happens at all: no voice is stolen or cut and no bus is configured.
     * With 64 voices already sounding, the oldest is faded out over 5 ms to make room.
     */
    fun startVoice(params: VoiceParams): Boolean {
        if (!params.canSound()) return false
        val voice = claimVoice()
        started++
        voice.start(params, started, (started.toInt() * -1640531527) or 1)
        if (voice.hasCut) {
            for (other in voices) {
                if (other !== voice && other.isActive && !other.isFading && other.hasCut && other.cutGroup == voice.cutGroup) {
                    other.fade(CUT_FADE_MS)
                }
            }
        }
        params.delaytime.ifSet { delay.setTime(it) }
        params.delayfeedback.ifSet { delay.setFeedback(it) }
        params.size.ifSet { reverb.setSize(it) }
        return true
    }

    /**
     * Stops everything, which is what stopping playback does: every voice fades out over [fadeMs] milliseconds, the
     * output fades out with them, so that the delay and reverb tails do too, and once that is over the delay and
     * reverb buffers are emptied. Nothing rings on after it, and nothing is cut off while it is still loud.
     */
    fun stopAll(fadeMs: Int) {
        for (voice in voices) if (voice.isActive) voice.fade(fadeMs)
        stopFrames = max(1, fadeMs * sampleRate / 1000)
        stopFramesLeft = stopFrames
    }

    /** Empties the delay and reverb buffers and resets the limiter; call when going idle so old tails cannot return. */
    fun clearBuses() {
        delay.clear()
        reverb.clear()
        limiter.reset()
    }

    /**
     * Renders [frames] frames of interleaved stereo into `out[frameOffset * 2 until (frameOffset + frames) * 2]`,
     * overwriting it (silence is written as zeros). Output is always within `[-1, 1]`.
     */
    fun render(out: FloatArray, frames: Int, frameOffset: Int = 0) {
        var done = 0
        while (done < frames) {
            var n = min(BLOCK, frames - done)
            if (stopFramesLeft > 0) n = min(n, stopFramesLeft)
            val dry = buffers.dry
            dry.clear(n)
            buffers.delaySend.clear(n)
            buffers.reverbSend.clear(n)
            for (voice in voices) if (voice.isActive) voice.render(n, buffers)
            delay.process(buffers.delaySend, n, dry)
            reverb.process(buffers.reverbSend, n, dry)

            val left = dry.left
            val right = dry.right
            val stopping = stopFramesLeft > 0
            val stopStep = if (stopping) MASTER_GAIN / stopFrames else 0f // the gain falls to 0 over the frames left
            for (i in 0 until n) {
                val gain = if (stopping) stopStep * (stopFramesLeft - i) else MASTER_GAIN
                left[i] *= gain
                right[i] *= gain
            }
            limiter.process(left, right, n)
            var o = (frameOffset + done) * 2
            for (i in 0 until n) {
                out[o++] = left[i]
                out[o++] = right[i]
            }
            done += n
            if (stopFramesLeft > 0) {
                stopFramesLeft -= n
                if (stopFramesLeft == 0) clearBuses()
            }
        }
    }

    /** A free slot; when 64 voices are sounding the oldest is faded first. If every slot is busy, the oldest is reused. */
    private fun claimVoice(): Voice {
        var free: Voice? = null
        var oldestSounding: Voice? = null
        var oldest: Voice? = null
        var sounding = 0
        for (voice in voices) {
            if (!voice.isActive) {
                if (free == null) free = voice
                continue
            }
            if (oldest == null || voice.order < oldest.order) oldest = voice
            if (!voice.isFading) {
                sounding++
                if (oldestSounding == null || voice.order < oldestSounding.order) oldestSounding = voice
            }
        }
        if (sounding >= POLYPHONY) oldestSounding!!.fade(STEAL_FADE_MS)
        return free ?: oldest!!
    }

    private inline fun Double?.ifSet(block: (Double) -> Unit) {
        if (this != null && !isNaN()) block(this)
    }
}
