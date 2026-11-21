package cosic.dough

import java.util.concurrent.atomic.AtomicReferenceArray

/*
 * dough.h 2924-3618: the engine. A fixed array of voices (32), one orbit (the delay and the reverb that every voice sends to), the files voices can play, and the loop that
 * makes one stereo frame at a time. The wasm build keeps an array of scheduled events too and starts each at the first sample at or after its time; here the caller starts an
 * event at the frame it belongs to (`play`, `run`), so that array is not ported (and neither are the 10 ms rule for events that are late and the `repeat` field).
 *
 * Audio thread only, except [registerFile] (any thread). Nothing here allocates after the constructor.
 */

internal const val DOUGH_MAX_VOICES = 32
internal const val DOUGH_MAX_FILES = 128

/** The seconds of the delay line: `MAX_DELAY_TIME` of dough.js. */
internal const val DOUGH_MAX_DELAY_SECONDS = 1

/**
 * The most floats of audio the files of an engine hold in all. The wasm engine reserves 196 MB for them; a phone does not have that to give, so this is a quarter (the files
 * are decoded and converted to the engine's rate once and never freed, as in dough).
 */
internal const val DOUGH_MAX_PCM_FLOATS = 12_000_000

/** What `dough_engine_init` makes for the one orbit: the delay of both channels and the reverb, and what the voices last said about them. */
internal class DoughOrbit(maxDelaySamples: Int) {
    @JvmField val delay = Array(DOUGH_CHANNELS) { Delay(maxDelaySamples) }
    @JvmField val delaysend = FloatArray(DOUGH_CHANNELS)
    @JvmField var delaytime = 0f
    @JvmField var delayfeedback = 0f
    @JvmField val verb = DattorroVerb()
    @JvmField val verbsend = FloatArray(DOUGH_CHANNELS)
    @JvmField var verbdecay = 0.75f
    @JvmField var verbdamp = 0.95f
    @JvmField var verbpredelay = 0.1f
    @JvmField var verbdiff = 0.7f

    /** Silence: the delay line and the reverb empty, the sends 0, the parameters as `dough_engine_init` leaves them. */
    fun clear() {
        for (c in 0 until DOUGH_CHANNELS) {
            delay[c].clear()
            delaysend[c] = 0f
            verbsend[c] = 0f
        }
        delaytime = 0f
        delayfeedback = 0f
        verb.reset()
        verbdecay = 0.75f
        verbdamp = 0.95f
        verbpredelay = 0.1f
        verbdiff = 0.7f
    }
}

internal class DoughEngine(val sampleRate: Int) {
    /** `engine.sr`, `isr` (`1.0 / sr` in double, stored as float) and `lag_unit`. */
    @JvmField val sr: Float = sampleRate.toFloat()
    @JvmField val isr: Float = (1.0 / sr.toDouble()).toFloat()
    @JvmField val lagUnit: Int = sampleRate / 10

    @JvmField val rng = DoughRng()
    private val voices = Array(DOUGH_MAX_VOICES) { DoughVoice() }
    private val orbit = DoughOrbit(DOUGH_MAX_DELAY_SECONDS * sampleRate)
    private val files = AtomicReferenceArray<DoughFile?>(DOUGH_MAX_FILES)
    private val scratch = EnvelopeFields()
    private var voiceCounter = 0L

    @Volatile
    private var pcmFloats = 0L

    /** How many voices are playing. */
    var activeVoices = 0
        private set

    /** The largest absolute value [render] wrote (after the final gain and clamp) since the last call of [resetPeak]. */
    var peak = 0f
        private set

    /** The frames of fade left after [fadeOut], and the length it started with; 0 when not fading. */
    private var fadeLeft = 0
    private var fadeLength = 0

    fun resetPeak() {
        peak = 0f
    }

    /** True while any voice plays. (The delay and the reverb may still ring after the last one: see [peak].) */
    val isPlaying: Boolean get() = activeVoices > 0

    // ---- files -------------------------------------------------------------------------------------------------------------------

    /**
     * `dough_register_pcm`: makes [file] the file number [index]. False when [index] is not one of the 128 slots or the files would hold more than [DOUGH_MAX_PCM_FLOATS] floats
     * (a registered file is never freed, and registering the same index again does not give its space back). Any thread.
     */
    fun registerFile(index: Int, file: DoughFile): Boolean {
        if (index < 0 || index >= DOUGH_MAX_FILES) return false
        val size = file.frames.toLong() * file.channels
        synchronized(files) {
            if (pcmFloats + size > DOUGH_MAX_PCM_FLOATS) return false
            pcmFloats += size
        }
        files.set(index, file)
        return true
    }

    /** The file registered as [index], or null. */
    fun file(index: Int): DoughFile? = if (index in 0 until DOUGH_MAX_FILES) files.get(index) else null

    // ---- voices ------------------------------------------------------------------------------------------------------------------

    private fun allocateVoice(): Int {
        if (activeVoices >= DOUGH_MAX_VOICES) return -1
        return activeVoices++
    }

    /** `free_voice`: the last voice takes the place of voice [i] (the objects swap, so the freed one is ready for the next [play]). */
    internal fun freeVoice(i: Int) {
        val last = --activeVoices
        if (i != last) {
            val freed = voices[i]
            voices[i] = voices[last]
            voices[last] = freed
        }
    }

    /**
     * `play_event`: starts a voice with [p] (a command `play` is not required: [run] checks that). A group that has `voices` voices already gives up its oldest instead of a
     * new one (which keeps the voice's envelopes and phase unless `reset` is 1); with no room the event is dropped. Returns the voice's slot, or -1.
     */
    fun play(p: DoughEvent): Int {
        val i: Int
        var voiceStolen = false
        if (p.group != -1 && p.voices != -1) {
            var count = 0
            var oldestId = 0L
            var oldest = -1
            for (v in 0 until activeVoices) {
                val voice = voices[v]
                if (voice.p.group == p.group) {
                    count++
                    if (oldest == -1 || voice.id < oldestId) {
                        oldestId = voice.id
                        oldest = v
                    }
                }
            }
            voiceStolen = count >= p.voices
            i = if (!voiceStolen) allocateVoice() else oldest
        } else {
            i = allocateVoice()
        }
        if (i == -1) return -1

        val v = voices[i]
        val isFresh = !voiceStolen || p.reset == 1
        if (isFresh) v.reset()
        v.p.copyFrom(p)
        v.id = voiceCounter++
        v.shiftEnvelopes(-v.time) // matters for a voice that is played again: its envelopes move with its clock
        v.time = 0f
        initEvent(v.p, files, sr, scratch)
        if (isFresh) {
            v.glideLag.s = v.p.rate // no glide from 0
            v.phasor.phase = if (v.p.speed < 0) 1.0f else 0.0f // avoid an early wrap
        }
        return i
    }

    /** `update_all`: merges the values [p] gives into every voice. */
    fun updateAll(p: DoughEvent) {
        for (i in 0 until activeVoices) {
            val v = voices[i]
            v.p.merge(p)
            initEvent(v.p, files, sr, scratch)
        }
    }

    /** `update_group`: merges the values [p] gives into the voices of its group. */
    fun updateGroup(p: DoughEvent) {
        for (i in 0 until activeVoices) {
            val v = voices[i]
            if (p.group != -1 && v.p.group == p.group) {
                v.p.merge(p)
                initEvent(v.p, files, sr, scratch)
            }
        }
    }

    /** Opens no gate any more: every voice goes into its release. */
    fun hush() {
        for (i in 0 until DOUGH_MAX_VOICES) voices[i].p.gate = 0.0f
    }

    /** Releases the voices that have no duration (they would play forever). */
    fun hushEndless() {
        for (i in 0 until DOUGH_MAX_VOICES) if (voices[i].p.duration.isNaN()) voices[i].p.gate = 0.0f
    }

    fun hushGroup(group: Int) {
        for (i in 0 until DOUGH_MAX_VOICES) {
            if (voices[i].p.group != -1 && voices[i].p.group == group) voices[i].p.gate = 0.0f
        }
    }

    /** Drops every voice at once (the delay and the reverb ring on). */
    fun panic() {
        activeVoices = 0
    }

    /** `run_event`: does what the event's command says. [CMD_RESET_TIME] and [CMD_RESET_SCHEDULE] have nothing to do here (no clock, no schedule). */
    fun run(p: DoughEvent) {
        when (p.cmd) {
            CMD_PLAY -> play(p)
            CMD_UPDATE_ALL -> updateAll(p)
            CMD_UPDATE_GROUP -> updateGroup(p)
            CMD_PANIC -> panic()
            CMD_HUSH -> hush()
            CMD_HUSH_ENDLESS -> hushEndless()
            CMD_HUSH_GROUP -> hushGroup(p.group)
            CMD_RESET -> panic()
        }
    }

    /**
     * Empties everything that keeps sounding or would sound again: voices, the delay line and the reverb. Not in dough (a stopped dough is a suspended audio context), so
     * a stop here does not leave the tail of the last notes to come back with the next play.
     */
    fun clearTails() {
        panic()
        orbit.clear()
    }

    /** Fades everything out over the next [frames] frames of [render], then drops the voices and empties the delay and the reverb. */
    fun fadeOut(frames: Int) {
        if (frames <= 0) {
            clearTails()
            return
        }
        fadeLength = frames
        fadeLeft = frames
    }

    // ---- rendering ---------------------------------------------------------------------------------------------------------------

    /**
     * Adds [frames] stereo frames to `out[2 * frameOffset ...]` (interleaved): the sum of the voices, the delay and the reverb at half gain and clamped to -1..1 (`gen_sample`),
     * and the sum with what was in [out] clamped to -1..1 again. NaN and infinity are 0 in every voice's contribution (the C code lets them into the delay and the reverb, where they
     * stay for good), and in the result. The largest value written is [peak].
     */
    fun render(out: FloatArray, frames: Int, frameOffset: Int = 0) {
        var base = frameOffset * DOUGH_CHANNELS
        var written = 0f
        for (n in 0 until frames) {
            val o = orbit
            var left = 0.0f
            var right = 0.0f
            var v = 0
            while (v < activeVoices) {
                voices[v].run(this, v)
                val voice = voices[v] // the slot: after a free it holds the voice that took the place of the one that ended
                val p = voice.p
                for (c in 0 until DOUGH_CHANNELS) {
                    var s = voice.ch[c]
                    if (s - s != 0.0f) s = 0.0f
                    if (c == 0) left += s else right += s
                    if (p.delayActive) {
                        o.delaytime = p.delaytime
                        o.delayfeedback = p.delayfeedback
                        o.delaysend[c] += s * p.delay
                    }
                    if (p.verbActive) {
                        o.verbdecay = p.verbdecay
                        o.verbdamp = p.verbdamp
                        o.verbpredelay = p.verbpredelay
                        o.verbdiff = p.verbdiff
                        o.verbsend[c] += s * p.verb
                    }
                }
                v++
            }
            // delay, per channel
            for (c in 0 until DOUGH_CHANNELS) {
                o.delaysend[c] = ftz(o.delaysend[c], 0.0001f)
                val delayout = o.delay[c].update(o.delaysend[c], o.delaytime, sr)
                o.delaysend[c] = delayout * o.delayfeedback
                if (c == 0) left += delayout else right += delayout
            }
            // reverb: stereo in as mono, stereo out
            var monoIn = (o.verbsend[0] + o.verbsend[1]) * 0.5f
            monoIn = ftz(monoIn, 0.0001f)
            if (monoIn != 0.0f) {
                o.verb.setDecay(o.verbdecay)
                o.verb.setDamping(o.verbdamp)
                o.verb.setPreDelay(o.verbpredelay)
                o.verb.setInputDiffusion1(o.verbdiff)
                o.verb.setInputDiffusion2(o.verbdiff)
            }
            o.verb.process(monoIn)
            left += o.verb.left()
            right += o.verb.right()
            o.verbsend[0] = 0.0f
            o.verbsend[1] = 0.0f

            var l = clamp(left * 0.5f, -1f, 1f)
            var r = clamp(right * 0.5f, -1f, 1f)
            if (fadeLeft > 0) {
                val g = fadeLeft.toFloat() / fadeLength.toFloat()
                l *= g
                r *= g
                if (--fadeLeft == 0) clearTails()
            }
            if (l - l != 0.0f) l = 0.0f
            if (r - r != 0.0f) r = 0.0f
            val al = if (l < 0f) -l else l
            val ar = if (r < 0f) -r else r
            if (al > written) written = al
            if (ar > written) written = ar
            out[base] = clamp(out[base] + l, -1f, 1f)
            out[base + 1] = clamp(out[base + 1] + r, -1f, 1f)
            base += DOUGH_CHANNELS
        }
        if (written > peak) peak = written
    }
}
