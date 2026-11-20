package cosic.audio

import cosic.audio.sources.frequencyFromParams
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.pow

private const val MAX_GAIN_PARAM = 3.4e38

/** Speed limit and note range of the legacy sample path; Task 3.5 replaces the sampler and drops them. */
private const val MAX_SPEED = 8.0
private const val MIN_NOTE = -24.0
private const val MAX_NOTE = 132.0
private const val MAX_CUT_GROUP = 1e9

/** `cut` fades the previous voice of the group over this long (sampler.mjs:365-373). */
private const val CUT_RAMP_SECONDS = 0.01

/** A voice that is stolen ramps down to silence over this long (superdough.mjs:546-555). */
internal const val STEAL_RAMP_SECONDS = 0.25

/** `delaysync` when none is given, in cycles (superdough.mjs:199). */
private const val DEFAULT_DELAY_SYNC = 3.0 / 16.0

private val DEFAULT_DUCK_ONSET = doubleArrayOf(0.0)
private val DEFAULT_DUCK_ATTACK = doubleArrayOf(0.1)
private val DEFAULT_DUCK_DEPTH = doubleArrayOf(1.0)

/** [v], or [default] when NaN. */
private fun valueOr(v: Double, default: Double): Double = if (v.isNaN()) default else v

private fun regionBegin(p: ParamSet): Double = valueOr(p[Param.begin], 0.0).coerceIn(0.0, 1.0)

private fun regionEnd(p: ParamSet): Double = valueOr(p[Param.end], 1.0).coerceIn(0.0, 1.0)

private fun playbackSpeed(p: ParamSet): Double = valueOr(p[Param.speed], 1.0).coerceIn(-MAX_SPEED, MAX_SPEED)

/**
 * Whether an event can sound at all. A sample cannot when it is empty, when its `begin..end` region is empty or when
 * `speed` is zero; a synth always can. Checking this before a voice is chosen keeps an event that cannot sound from
 * disturbing the voices that do.
 */
internal fun VoiceParams.canSound(): Boolean {
    val sample = source as? VoiceSource.Sample ?: return true
    return sample.data.samples.isNotEmpty() && regionEnd(main) > regionBegin(main) && playbackSpeed(main) != 0.0
}

/**
 * One sounding note: the `superdough()` graph of one event. A source (an oscillator or a sample) is multiplied by the amplitude
 * envelope and the cut/steal gain, runs through the effect chains of the event's `FX(...)` elements and then through the main
 * chain (each a [StageGroup]), through the `FXrelease` fade and the `post` gain, and is mixed into its orbit: the direct sound with the
 * `dry` gain, plus the sends to the orbit's delay and reverb, to a bus and to an analyser. Modulators (`lfo`, `env`, `bmod`) are connected to
 * the parameters of all of it.
 *
 * Time is absolute: the voice starts at engine frame `startFrame`, `t = startFrame / sampleRate`, and every parameter lives on the same
 * clock, so its sound does not depend on how blocks are cut. Instances live in a pool and are restarted with [start]; nothing here
 * allocates after construction (the first voice of an orbit number creates the orbit).
 *
 * A voice stays active, silent or not, until `max(end of its source, endWithRelease)`: that is when Strudel releases its nodes, and
 * until then it counts toward `maxPolyphony`.
 */
internal class Voice(private val sampleRate: Int, private val orbits: OrbitSet, private val buses: AudioBuses) {
    // The legacy sources; Task 3.17 replaces them with SoundSource, Task 3.5 the sampler.
    private val oscillator = Oscillator(sampleRate)
    private val player = SamplePlayer(sampleRate)
    private var isSynth = true
    private var sourceLevel = 1f

    /** The amplitude envelope that multiplies the source (stage 0 of the chain, inside the source node in Strudel). */
    val ampEnv = AParam(0.0, -MAX_GAIN_PARAM, MAX_GAIN_PARAM, 8)

    /** The gain of the source's output node: 1, except for the ramps of a cut or a steal. */
    val outGain = AParam(1.0, -MAX_GAIN_PARAM, MAX_GAIN_PARAM, 4)

    private val fxRelease = AParam(1.0, -MAX_GAIN_PARAM, MAX_GAIN_PARAM, 4)
    private val post = PostStage()
    private val delaySend = AParam(0.0, -MAX_GAIN_PARAM, MAX_GAIN_PARAM, 2)
    private val reverbSend = AParam(0.0, -MAX_GAIN_PARAM, MAX_GAIN_PARAM, 2)
    private val busSend = AParam(1.0, -MAX_GAIN_PARAM, MAX_GAIN_PARAM, 2)

    /** The chains: `FX(...)` elements in order at 0 until [MAX_FX], the main chain last. */
    private val groups = Array(MAX_FX + 1) { StageGroup(sampleRate) }

    /** What [connectModulators] gets: the [fx] chains in use followed by the main one, one array per number of `FX` elements. */
    private val groupViews = Array(MAX_FX + 1) { fxCount -> Array(fxCount + 1) { if (it < fxCount) groups[it] else groups[MAX_FX] } }
    private val registries = Array(MAX_FX + 1) { NodeRegistry() }
    private val registryLookup: (String) -> NodeRegistry = { key -> registries[registryIndex(key)] }
    private val reverbParams = ReverbParams()
    private val timing = VoiceTiming()
    private var fxCount = 0
    private var fxReleaseUsed = false
    private var delayUsed = false
    private var reverbUsed = false
    private var busIndex = -1
    private var analyzeId = -1
    private var fftSize = 0
    private var orbit: Orbit? = null

    private var activeUntilFrame = 0L
    private var audibleUntilFrame = 0L
    private var stopFrames = 0
    private var stopLeft = 0
    private var cutPending = false

    /** True while the voice is part of the output: from [start] until its end. */
    var isActive = false
        private set

    /** True once the voice has been told to fade out (cut, steal or stop). */
    var isFading = false
        private set

    /** Start sequence number, so the pool can find the oldest voice. */
    var order = 0L
        private set

    /** True for a sample voice, the only kind that `cut` applies to. */
    var isSample = false
        private set

    var hasCut = false
        private set
    var cutGroup = 0
        private set

    /** True while the voice's cut group may still be cut by a later voice of the group. */
    val canBeCut: Boolean get() = isActive && hasCut && !cutPending

    /**
     * Whether the voice's source and envelope can still make sound at engine frame [frame]: until the source stops (`holdEnd + release + 0.01`),
     * or until a cut or a steal has ramped it down. A voice stays [isActive] (and counts toward polyphony) until its chain is released, which
     * for a short sample under a long hap is much later; it is silent in between.
     */
    fun isAudibleAt(frame: Long): Boolean = isActive && frame < audibleUntilFrame

    /**
     * Starts the voice at engine frame [startFrame] from [params] and returns the time in seconds until which it counts toward polyphony
     * (`activeUntil`), or NaN when it cannot start (more than [MAX_ORBITS] orbits). [params] must be able to sound, see [canSound].
     */
    fun start(params: VoiceParams, startFrame: Long, order: Long, seed: Int, multiChannelOrbits: Boolean): Double {
        isActive = false
        val main = params.main
        val t = startFrame.toDouble() / sampleRate
        val duration = if (params.durationSec > 0.0) params.durationSec else 0.0 // NaN becomes 0 too
        val end = t + duration
        val release = valueOr(main[Param.release], DEFAULT_RELEASE)
        val fullRelease = Math.max(release, valueOr(main[Param.FXrelease], 0.0))
        val endWithRelease = end + fullRelease
        timing.set(t, end, endWithRelease, params.cps, params.cycle)

        // Orbit first: when it cannot be had, nothing has been touched yet. An orbit keeps the channels it was created with, so the
        // `channels` control is only turned into an array (the one allocation here) for the voice that creates the orbit.
        val orbitNumber = valueOr(main[Param.orbit], 1.0)
        val orbit = orbits.existing(orbitNumber) ?: run {
            val list = main.list("channels")
            val channels = if (list == null) null else IntArray(list.size) { list[it].toInt() }
            orbits.orbit(orbitNumber, channels, multiChannelOrbits)
        } ?: return Double.NaN
        this.orbit = orbit

        // The source and its envelope.
        ampEnv.reset(0.0)
        outGain.reset(1.0)
        val envEnd = startSource(params, main, t, duration, seed)

        // The effect chains: FX elements first, the main chain last.
        val fx = params.fx
        fxCount = Math.min(fx.size, MAX_FX)
        for (i in 0 until fxCount) groups[i].configure(fx[i], timing, false)
        groups[MAX_FX].configure(main, timing, true)

        val fxr = main[Param.FXrelease]
        fxReleaseUsed = !fxr.isNaN() && fxr > release
        if (fxReleaseUsed) {
            fxRelease.reset(1.0)
            fxRelease.setValueAtTime(1.0, end + release)
            fxRelease.linearRampToValueAtTime(0.0, endWithRelease, end + release)
        }
        post.configure(main)
        startSends(params, main, orbit, t)

        if (params.mods.isNotEmpty()) connectMods(params, main, orbit, t, endWithRelease)

        val activeUntil = Math.max(envEnd, endWithRelease)
        activeUntilFrame = frameAtOrAfter(activeUntil)
        audibleUntilFrame = frameAtOrAfter(envEnd)
        stopFrames = 0
        stopLeft = 0
        cutPending = false
        isFading = false
        hasCut = !main[Param.cut].isNaN()
        cutGroup = if (hasCut) floor(main[Param.cut].coerceIn(-MAX_CUT_GROUP, MAX_CUT_GROUP)).toInt() else 0
        this.order = order
        isActive = true
        return activeUntil
    }

    /** Starts the legacy source and schedules the amplitude envelope; returns when the source ends. */
    private fun startSource(params: VoiceParams, main: ParamSet, t: Double, duration: Double, seed: Int): Double {
        val clipSet = main.has(Param.clip)
        val loopSet = main.has(Param.loop)
        val releaseSet = main.has(Param.release)
        when (val source = params.source) {
            is VoiceSource.Synth -> {
                oscillator.start(source.waveform, frequencyFromParams(main), seed)
                isSynth = true
                isSample = false
                sourceLevel = source.waveform.baseLevel
                return AmpEnvelope.schedule(
                    ampEnv, AmpKind.SYNTH, main[Param.attack], main[Param.decay], main[Param.sustain], main[Param.release],
                    t, duration, Double.NaN, clipSet, loopSet, releaseSet,
                )
            }
            is VoiceSource.Sample -> {
                val data = source.data
                val note = valueOr(main[Param.note], Double.NaN).coerceIn(MIN_NOTE, MAX_NOTE)
                val root = source.rootNote
                val pitchRatio = when {
                    root != null -> 2.0.pow(((if (note.isNaN()) DEFAULT_NOTE else note) - root) / 12.0)
                    note.isNaN() -> 1.0
                    else -> 2.0.pow((note - DEFAULT_NOTE) / 12.0)
                }
                val begin = regionBegin(main)
                val end = regionEnd(main)
                val rate = playbackSpeed(main) * data.sampleRate / sampleRate * pitchRatio
                player.start(data.samples, begin, end, rate)
                isSynth = false
                isSample = true
                sourceLevel = 1f
                val sliceSec = (end - begin) * data.samples.size / (abs(rate) * sampleRate)
                return AmpEnvelope.schedule(
                    ampEnv, AmpKind.SAMPLE, main[Param.attack], main[Param.decay], main[Param.sustain], main[Param.release],
                    t, duration, sliceSec, clipSet, loopSet, releaseSet,
                )
            }
        }
    }

    /** What superdough.mjs:951-1002 does after the chain: the orbit's delay and reverb, the bus, DJF, the analyser. */
    private fun startSends(params: VoiceParams, main: ParamSet, orbit: Orbit, t: Double) {
        val cps = params.cps
        val delay = main.or(Param.delay, 0.0)
        val delayfeedback = main.or(Param.delayfeedback, 0.5)
        val delaytime = main.or(Param.delaytime, main.or(Param.delaysync, DEFAULT_DELAY_SYNC) / cps)
        delayUsed = delay > 0.0 && delaytime > 0.0 && delayfeedback > 0.0
        if (delayUsed) {
            delaySend.reset(delay)
            orbit.configureDelay(delaytime, delayfeedback, t)
        }

        val room = main.or(Param.room, 0.0)
        reverbUsed = room > 0.0
        if (reverbUsed) {
            reverbSend.reset(room)
            val r = reverbParams
            r.duration = main[Param.roomsize]
            r.fade = main[Param.roomfade]
            r.lp = main[Param.roomlp]
            r.dim = main[Param.roomdim]
            r.irName = main.str("ir")
            r.irIndex = main.or(Param.i, 1.0)
            r.irSample = params.irSample
            r.irspeed = main[Param.irspeed]
            r.irbegin = main[Param.irbegin]
            orbit.configureReverb(r, t)
        }

        busIndex = if (main.has(Param.bus)) main[Param.bus].toInt() else -1
        if (busIndex >= 0) busSend.reset(main.or(Param.busgain, 1.0))
        if (main.has(Param.djf)) orbit.setDjf(main[Param.djf], t)
        val analyze = main[Param.analyze]
        analyzeId = if (!analyze.isNaN() && analyze != 0.0) analyze.toInt() else -1
        fftSize = 1 shl main.or(Param.fft, 8.0).toInt().coerceIn(0, 10).plus(5)

        val duck = main.list("duckorbit")
        if (duck != null) {
            val frame = Math.round(t * sampleRate)
            orbits.duck(
                duck, frame, frame, main.list("duckonset") ?: DEFAULT_DUCK_ONSET,
                main.list("duckattack") ?: DEFAULT_DUCK_ATTACK, main.list("duckdepth") ?: DEFAULT_DUCK_DEPTH,
            )
        }
    }

    private fun connectMods(params: VoiceParams, main: ParamSet, orbit: Orbit, t: Double, endWithRelease: Double) {
        for (r in registries) r.clear()
        for (i in 0 until fxCount) groups[i].registerParams(registries[i])
        groups[MAX_FX].registerParams(registries[MAX_FX])
        val m = registries[MAX_FX]
        m.register("post", "gain", post.post)
        m.register("dry", "gain", post.dry)
        if (delayUsed) m.register("delay_mix", "gain", delaySend)
        if (reverbUsed) m.register("room_mix", "gain", reverbSend)
        if (busIndex >= 0) m.register("bus", "gain", busSend)
        orbit.registerParams(m)
        connectModulators(params.mods, groupViews[fxCount], registryLookup, t, endWithRelease, params.cps, params.cycle, buses)
    }

    private fun registryIndex(key: String): Int {
        val index = key.toIntOrNull() ?: return MAX_FX
        return if (index in 0 until fxCount) index else MAX_FX
    }

    /** Ramps the voice out because of a cut group: its out gain goes to 0 over 10 ms from [t]. */
    fun cut(t: Double) {
        if (!isActive || cutPending) return
        cutPending = true
        isFading = true
        outGain.setValueAtTime(1.0, t)
        outGain.linearRampToValueAtTime(0.0, t + CUT_RAMP_SECONDS, t)
        audibleUntilFrame = Math.min(audibleUntilFrame, frameAtOrAfter(t + CUT_RAMP_SECONDS))
    }

    /**
     * Steals the voice for one that starts at [tNew]: its amplitude (the out gain for a sample) ramps to 0 at `tNew + 0.25` and it stops
     * there, if it had not ended before.
     */
    fun steal(tNew: Double) {
        if (!isActive) return
        isFading = true
        val target = if (isSample) outGain else ampEnv
        target.linearRampToValueAtTime(0.0, tNew + STEAL_RAMP_SECONDS, tNew)
        activeUntilFrame = Math.min(activeUntilFrame, frameAtOrAfter(tNew + STEAL_RAMP_SECONDS))
        audibleUntilFrame = Math.min(audibleUntilFrame, activeUntilFrame)
    }

    /** Fades the whole voice out linearly over [frames] frames from the next block on (stopping playback). */
    fun fadeOut(frames: Int) {
        if (!isActive) return
        isFading = true
        stopFrames = Math.max(1, frames)
        stopLeft = stopFrames
    }

    /** The first frame at or after time [seconds]. */
    private fun frameAtOrAfter(seconds: Double): Long {
        val frame = Math.ceil(seconds * sampleRate)
        return if (frame > HORIZON_FRAMES) HORIZON_FRAMES.toLong() else frame.toLong()
    }

    /**
     * Adds the next `ctx.n` frames of this voice to its orbit and buses. [blk] is the mixer's scratch block.
     */
    fun render(ctx: BlockContext, blk: AudioBlock) {
        val n = ctx.n
        val left = activeUntilFrame - ctx.startFrame
        if (left <= 0L) {
            isActive = false
            return
        }
        val live = if (left < n) left.toInt() else n

        blk.channels = 1
        val produced = if (isSynth) {
            oscillator.fill(blk.l, n)
            val level = sourceLevel
            for (i in 0 until n) blk.l[i] *= level
            n
        } else {
            player.fill(blk.l, n)
        }
        if (produced < n) blk.l.fill(0f, produced, n)

        val gains = ctx.borrow()
        ampEnv.fill(gains, ctx)
        blk.mulBy(gains, n)
        if (outGain.isConstant) {
            if (outGain.constantValue != 1.0) blk.mulBy(outGain.constantValue.toFloat(), n)
        } else {
            outGain.fill(gains, ctx)
            blk.mulBy(gains, n)
        }
        ctx.release()

        for (i in 0 until fxCount) groups[i].process(blk, ctx)
        groups[MAX_FX].process(blk, ctx)
        if (fxReleaseUsed) {
            val g = ctx.borrow()
            fxRelease.fill(g, ctx)
            blk.mulBy(g, n)
            ctx.release()
        }
        post.process(blk, ctx)

        var end = live
        if (stopLeft > 0) {
            val step = 1f / stopFrames
            for (i in 0 until n) {
                val gain = if (i < stopLeft) step * (stopLeft - i) else 0f
                blk.l[i] *= gain
                if (blk.channels == 2) blk.r[i] *= gain
            }
            if (stopLeft <= n) {
                end = Math.min(end, stopLeft)
                isActive = false
            }
            stopLeft -= n
        }
        blk.silenceFrom(end, n)

        val orbit = orbit!!
        orbit.mixDry(blk, post.dry, ctx)
        if (delayUsed) orbit.sendDelay(blk, delaySend, ctx)
        if (reverbUsed) orbit.sendReverb(blk, reverbSend, ctx)
        if (busIndex >= 0) buses.send(busIndex, blk, busSend, ctx)
        if (analyzeId >= 0) buses.analyse(analyzeId, fftSize, blk, ctx)
        if (left <= n) isActive = false
    }

    private companion object {
        /** `release` when the pattern sets none (superdough.mjs:206). */
        const val DEFAULT_RELEASE = 0.01

        /** Times beyond this many frames (about 1900 years at 48 kHz) count as for ever. */
        const val HORIZON_FRAMES = 9.0e16
    }
}
