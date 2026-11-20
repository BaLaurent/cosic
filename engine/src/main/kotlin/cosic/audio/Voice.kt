package cosic.audio

import cosic.audio.sources.frequencyFromParams
import kotlin.math.abs
import kotlin.math.pow

private const val MAX_GAIN_PARAM = 3.4e38

/** A `nudge` beyond this many seconds counts as this, so that the time of a cut stays a number the timelines can hold. */
private const val MAX_NUDGE = 3600.0

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

/**
 * Whether an event can sound at all. A sample cannot when it is empty, when `speed` is zero, when `begin` is negative or when the slice
 * `begin..end` would last no time (see [Sampler.canSound]); a synth always can. Checking this before a voice is chosen keeps an event
 * that cannot sound from disturbing the voices that do.
 */
internal fun VoiceParams.canSound(): Boolean {
    val sample = source as? VoiceSource.Sample ?: return true
    return Sampler.canSound(sample.params, main)
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
internal class Voice(private val sampleRate: Int, private val orbits: OrbitSet, private val buses: AudioBuses, private val fxPool: FxGroupPool) {
    // The oscillator is the legacy synth source (Task 3.17 replaces it with SoundSource); the sampler is the player of superdough's.
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

    /**
     * The main effect chain, the voice's own. The chains of its `FX(...)` elements come from the shared [fxPool] for as long as the voice
     * lasts: few patterns use them, and a chain is large.
     */
    private val mainGroup = StageGroup(sampleRate)
    private val fxGroups = arrayOfNulls<StageGroup>(MAX_FX)

    /** What [connectModulators] gets: the [fxGroups] in use followed by the main one, one array per number of `FX` elements. */
    private val groupViews = Array(MAX_FX + 1) { count -> Array(count + 1) { mainGroup } }
    private val registryLookup: (String) -> NodeRegistry = { key -> registryOf(key) }
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

    /**
     * The cut group: `cutGroups[cut]` of the sampler, a property key of a JS array, so `1.5` is a group of its own and `-0` is `0`. Compared as a
     * double.
     */
    var cutGroup = 0.0
        private set

    /** When this voice, as the newest of its cut group, silences the previous one: `t + nudge` of sampler.mjs:332, a negative nudge counting as 0. */
    var cutTime = 0.0
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
        releaseFxGroups()
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

        // The effect chains: FX elements first (each takes a group from the shared pool; when it is empty the rest are dropped), the main chain last.
        val fx = params.fx
        val wanted = Math.min(fx.size, MAX_FX)
        for (i in 0 until wanted) {
            fxGroups[i] = fxPool.take() ?: break
            fxCount++
        }
        for (i in 0 until fxCount) {
            val group = fxGroups[i]!!
            group.configure(fx[i], timing, false)
            groupViews[fxCount][i] = group
        }
        mainGroup.configure(main, timing, true)

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
        cutGroup = if (hasCut) main[Param.cut] else 0.0
        cutTime = t + valueOr(main[Param.nudge], 0.0).coerceIn(0.0, MAX_NUDGE)
        this.order = order
        isActive = true
        return activeUntil
    }

    /** Starts the source and schedules the amplitude envelope; returns when the source ends. */
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
                val sample = source.params
                player.start(sample, main)
                isSynth = false
                isSample = sample.honoursSampleControls
                sourceLevel = 1f
                // The gate of a sample is the length of its slice unless clip, loop or release is set; a soundfont's is always the hap's.
                val hapGate = Sampler.gateIsHap(sample, main)
                val sliceSec = if (hapGate) Double.NaN else Sampler.sliceSeconds(sample, main, Sampler.playbackRate(sample, main))
                return AmpEnvelope.schedule(
                    ampEnv, AmpKind.SAMPLE, main[Param.attack], main[Param.decay], main[Param.sustain], main[Param.release],
                    t, duration, sliceSec, hapGate, loopSet, releaseSet,
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
        for (i in 0 until fxCount) {
            val group = fxGroups[i]!!
            group.registry.clear()
            group.registerParams(group.registry)
        }
        val m = mainGroup.registry
        m.clear()
        mainGroup.registerParams(m)
        m.register("post", "gain", post.post)
        m.register("dry", "gain", post.dry)
        if (delayUsed) m.register("delay_mix", "gain", delaySend)
        if (reverbUsed) m.register("room_mix", "gain", reverbSend)
        if (busIndex >= 0) m.register("bus", "gain", busSend)
        orbit.registerParams(m)
        connectModulators(params.mods, groupViews[fxCount], registryLookup, t, endWithRelease, params.cps, params.cycle, buses)
    }

    /** The registry of the chain `fxi` names: `main`, or the index of an `FX` element (an index that is not in use means the main chain). */
    private fun registryOf(key: String): NodeRegistry {
        val index = key.toIntOrNull() ?: return mainGroup.registry
        return if (index in 0 until fxCount) fxGroups[index]!!.registry else mainGroup.registry
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

    /** Gives the pooled chains of the `FX(...)` elements back: the voice has ended, or is about to start again. */
    private fun releaseFxGroups() {
        for (i in 0 until fxCount) {
            fxPool.give(fxGroups[i]!!)
            fxGroups[i] = null
        }
        fxCount = 0
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
            releaseFxGroups()
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
            if (player.stereo) blk.channels = 2
            player.fill(blk.l, blk.r, n)
        }
        if (produced < n) {
            blk.l.fill(0f, produced, n)
            if (blk.channels == 2) blk.r.fill(0f, produced, n)
        }

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

        for (i in 0 until fxCount) fxGroups[i]!!.process(blk, ctx)
        mainGroup.process(blk, ctx)
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
        if (!isActive) releaseFxGroups()
    }

    private companion object {
        /** `release` when the pattern sets none (superdough.mjs:206). */
        const val DEFAULT_RELEASE = 0.01

        /** Times beyond this many frames (about 1900 years at 48 kHz) count as for ever. */
        const val HORIZON_FRAMES = 9.0e16
    }
}
