package cosic.audio

import cosic.pattern.logger

private const val MAX_PARAM = 3.4e38

/** `delaysync` when none is given, in cycles (superdough.mjs:199). */
private const val DEFAULT_DELAY_SYNC = 3.0 / 16.0

/** Delays and convolvers that `FX(...)` elements can use at the same time, across all voices. */
private const val IN_CHAIN_DELAYS = 8
private const val IN_CHAIN_REVERBS = 4

/**
 * The delay lines and reverbs that the `FX(...)` elements of voices (stages 17 and 18 of the chain) share. A voice's element needs a delay line of up to a second
 * (and a reverb of a convolver and its impulse response); a pool of [IN_CHAIN_DELAYS] delays and [IN_CHAIN_REVERBS] reverbs serves all of them, a voice takes
 * one for as long as it lasts, and an element that finds the pool empty goes without the effect (with a warning, once each). The first use of a slot allocates it,
 * which is the one allocation on the audio thread here; reverbs are built on the [executor] like an orbit's.
 *
 * [wet] is the scratch buffer the effects write into, which the stages mix with their input; stages run one at a time, so one buffer serves all of them.
 */
internal class InChainSpace(val sampleRate: Int, private val executor: IrExecutor) {
    val wet = StereoBuffer(BLOCK_FRAMES)

    /** Filled by the reverb stage for the pool; one shared instance because stages run one at a time. */
    val reverbParams = ReverbParams()

    private val delays = arrayOfNulls<FeedbackDelay>(IN_CHAIN_DELAYS)
    private val delaysTaken = BooleanArray(IN_CHAIN_DELAYS)
    private val reverbs = arrayOfNulls<ReverbBus>(IN_CHAIN_REVERBS)
    private val reverbsTaken = BooleanArray(IN_CHAIN_REVERBS)
    private var warnedDelays = false
    private var warnedReverbs = false

    /** A free delay slot (allocating the line on its first use), or -1. */
    fun takeDelay(): Int {
        for (i in 0 until IN_CHAIN_DELAYS) {
            if (!delaysTaken[i]) {
                delaysTaken[i] = true
                if (delays[i] == null) delays[i] = FeedbackDelay(sampleRate)
                return i
            }
        }
        if (!warnedDelays) {
            warnedDelays = true
            logger("more than $IN_CHAIN_DELAYS FX(...) delays at once: the others are left out", "warning", null)
        }
        return -1
    }

    fun delay(slot: Int): FeedbackDelay = delays[slot]!!

    fun giveDelay(slot: Int) {
        delaysTaken[slot] = false
    }

    /** A free reverb slot, preferring one that is set up for [p] already (its impulse response can be kept), or -1. */
    fun takeReverb(p: ReverbParams): Int {
        var pick = -1
        for (i in 0 until IN_CHAIN_REVERBS) {
            if (reverbsTaken[i]) continue
            val bus = reverbs[i]
            if (bus != null && bus.isSetUpFor(p)) {
                pick = i
                break
            }
            if (pick < 0) pick = i
        }
        if (pick < 0) {
            if (!warnedReverbs) {
                warnedReverbs = true
                logger("more than $IN_CHAIN_REVERBS FX(...) reverbs at once: the others are left out", "warning", null)
            }
            return -1
        }
        reverbsTaken[pick] = true
        if (reverbs[pick] == null) reverbs[pick] = ReverbBus(sampleRate, executor, 0x1F3D5B79L * (pick + 1))
        return pick
    }

    fun reverb(slot: Int): ReverbBus = reverbs[slot]!!

    fun giveReverb(slot: Int) {
        reverbsTaken[slot] = false
    }
}

/**
 * Stage 17, the delay of an `FX(...)` element (superdough.mjs:886-902): `dry * (fx.dry ?? 1) + wet * delay`, where the delay is a feedback delay line (like the
 * orbit's, with this element's time, feedback and level) and `wet` is the element's own `delay`. Only for non-main elements (the main chain's delay is the orbit's). The
 * condition is the element's `delay > 0 && delaytime > 0 && delayfeedback > 0`. The output is stereo.
 */
internal class InChainDelay(@Suppress("UNUSED_PARAMETER") sampleRate: Int) : ChainStage {
    private var space: InChainSpace? = null
    private var slot = -1
    private var line: FeedbackDelay? = null
    private var wetGain: AParam? = null
    private var dryGain: AParam? = null

    /** Gives the stage the pool it takes its delay from. */
    fun attach(space: InChainSpace) {
        this.space = space
    }

    override fun configure(p: ParamSet, timing: VoiceTiming, isMain: Boolean): Boolean {
        release()
        if (isMain) return false
        val pool = space ?: return false
        val level = p.or(Param.delay, 0.0)
        val feedback = p.or(Param.delayfeedback, 0.5)
        val time = if (p.has(Param.delaytime)) p[Param.delaytime] else p.or(Param.delaysync, DEFAULT_DELAY_SYNC) / timing.cps
        if (!(level > 0.0 && time > 0.0 && feedback > 0.0)) return false
        val taken = pool.takeDelay()
        if (taken < 0) return false
        slot = taken
        val delay = pool.delay(taken)
        line = delay
        delay.start(time, Math.min(Math.max(feedback, 0.0), 0.98))
        (wetGain ?: AParam(1.0, -MAX_PARAM, MAX_PARAM, 2).also { wetGain = it }).reset(level)
        (dryGain ?: AParam(1.0, -MAX_PARAM, MAX_PARAM, 2).also { dryGain = it }).reset(p.or(Param.dry, 1.0))
        return true
    }

    override fun process(b: AudioBlock, ctx: BlockContext) {
        val pool = space!!
        val n = ctx.n
        val wet = pool.wet
        val inR = if (b.channels == 2) b.r else b.l
        line!!.process(b.l, inR, wet.left, wet.right, n, ctx)
        mixDryAndWet(b, inR, wet, dryGain!!, wetGain!!, ctx)
    }

    override fun reset() {
        release()
    }

    override fun registerParams(reg: NodeRegistry) {
        val delay = line ?: return
        reg.register("delay", "delayTime", delay.delayTime)
        reg.register("delay", "feedback", delay.feedback)
        reg.register("delay_mix", "gain", wetGain!!)
    }

    /** Gives the delay line back to the pool; the voice is over (or starts again). */
    override fun release() {
        if (slot >= 0) {
            space?.giveDelay(slot)
            slot = -1
        }
        line = null
    }
}

/**
 * Stage 18, the reverb of an `FX(...)` element (superdough.mjs:903-936): `dry * (fx.dry ?? 1) + wet * convolver`, with this element's `room` as the wet level and its
 * `roomsize`, `roomfade`, `roomlp`, `roomdim` (`irspeed`, `irbegin`) for the room. Its impulse response is that of another element's or orbit's reverb when the same room was
 * built before (the slot keeps it); the element's `ir` sample is not available to the engine and is not used. Only for non-main elements. The output is stereo.
 */
internal class InChainReverb(@Suppress("UNUSED_PARAMETER") sampleRate: Int) : ChainStage {
    private var space: InChainSpace? = null
    private var slot = -1
    private var bus: ReverbBus? = null
    private var wetGain: AParam? = null
    private var dryGain: AParam? = null

    /** Gives the stage the pool it takes its reverb from. */
    fun attach(space: InChainSpace) {
        this.space = space
    }

    override fun configure(p: ParamSet, timing: VoiceTiming, isMain: Boolean): Boolean {
        release()
        if (isMain) return false
        val pool = space ?: return false
        val room = p.or(Param.room, 0.0)
        if (!(room > 0.0)) return false
        val r = pool.reverbParams
        r.duration = p[Param.roomsize]
        r.fade = p[Param.roomfade]
        r.lp = p[Param.roomlp]
        r.dim = p[Param.roomdim]
        r.irName = null
        r.irSample = null
        r.irspeed = p[Param.irspeed]
        r.irbegin = p[Param.irbegin]
        if (!roomCanBeBuilt(r.duration, pool.sampleRate)) return false
        val taken = pool.takeReverb(r)
        if (taken < 0) return false
        slot = taken
        val reverb = pool.reverb(taken)
        bus = reverb
        reverb.setup(r)
        (wetGain ?: AParam(1.0, -MAX_PARAM, MAX_PARAM, 2).also { wetGain = it }).reset(room)
        (dryGain ?: AParam(1.0, -MAX_PARAM, MAX_PARAM, 2).also { dryGain = it }).reset(p.or(Param.dry, 1.0))
        return true
    }

    override fun process(b: AudioBlock, ctx: BlockContext) {
        val pool = space!!
        val n = ctx.n
        val wet = pool.wet
        val inR = if (b.channels == 2) b.r else b.l
        bus!!.process(b.l, inR, wet.left, wet.right, n)
        mixDryAndWet(b, inR, wet, dryGain!!, wetGain!!, ctx)
    }

    override fun reset() {
        release()
    }

    override fun registerParams(reg: NodeRegistry) {
        if (bus == null) return
        reg.register("room_mix", "gain", wetGain!!)
    }

    /** Gives the reverb back to the pool. */
    override fun release() {
        if (slot >= 0) {
            space?.giveReverb(slot)
            slot = -1
        }
        bus = null
    }
}

/**
 * `out = dry * input + wet * effect` for both channels into [b] (made stereo): the `dryGain` and `wetGain` nodes of the in-chain delay and reverb, summed in an
 * explicit-stereo node.
 */
private fun mixDryAndWet(b: AudioBlock, inR: FloatArray, effect: StereoBuffer, dry: AParam, wetLevel: AParam, ctx: BlockContext) {
    val n = ctx.n
    if (dry.isConstant && wetLevel.isConstant) {
        val d = dry.constantValue.toFloat()
        val w = wetLevel.constantValue.toFloat()
        for (i in 0 until n) {
            val l = b.l[i]
            val r = inR[i]
            b.l[i] = d * l + w * effect.left[i]
            b.r[i] = d * r + w * effect.right[i]
        }
    } else {
        val dryGains = ctx.borrow()
        val wetGains = ctx.borrow()
        dry.fill(dryGains, ctx)
        wetLevel.fill(wetGains, ctx)
        for (i in 0 until n) {
            val l = b.l[i]
            val r = inR[i]
            b.l[i] = dryGains[i] * l + wetGains[i] * effect.left[i]
            b.r[i] = dryGains[i] * r + wetGains[i] * effect.right[i]
        }
        ctx.release()
        ctx.release()
    }
    b.channels = 2
}
