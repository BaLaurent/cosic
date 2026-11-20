package cosic.audio

import cosic.SampleData

private const val MAX_PARAM = 3.4e38

/** The `delay` node of `getDelay` clamps its feedback to this (`clamp(feedback, 0, 0.98)`). */
private const val MAX_FEEDBACK = 0.98

/** `duck` lets the ramp down begin this long before the event (`webAudioTimeout(..., t - 0.01)`). */
private const val DUCK_LEAD_SECONDS = 0.01

/** The shortest attack of a duck (`Math.max(attacktime, 0.002)`). */
private const val MIN_DUCK_ATTACK = 0.002

/** A duck never goes below this level: `clamp(1 - sqrt(depth), 0.01, current)`. */
private const val MIN_DUCKED_GAIN = 0.01

/**
 * Runs background jobs of the audio engine (generating a reverb impulse response); `submit` must return at once. The real-time engine
 * hands in a single worker thread ([BackgroundIrExecutor]), offline rendering and tests [INLINE].
 */
internal fun interface IrExecutor {
    fun submit(job: Runnable)

    companion object {
        /** Runs the job on the calling thread. */
        val INLINE = IrExecutor { it.run() }
    }
}

/**
 * What a voice asks of its orbit's reverb (`getReverb(roomsize, roomfade, roomlp, roomdim, roomIR, irspeed, irbegin)`, superdough.mjs:959-977). A NaN
 * number or a null name is "not set". One instance lives in each voice and is refilled at every start.
 */
internal class ReverbParams {
    var duration = Double.NaN
    var fade = Double.NaN
    var lp = Double.NaN
    var dim = Double.NaN
    var irName: String? = null
    var irIndex = 1.0
    var irSample: SampleData? = null
    var irspeed = Double.NaN
    var irbegin = Double.NaN
}

/**
 * An orbit: the effect bus voices of the same `orbit` number mix into (superdoughoutput.mjs `Orbit`). Voices add their direct sound ([mixDry]) and
 * their delay and reverb sends ([sendDelay], [sendReverb]); at the end of a block ([finishBlock]) the orbit runs its feedback [FeedbackDelay] and
 * its [ReverbBus] on the sends and adds their output to the sum, passes the sum through its [DjfStage] if a voice has set `djf`, and through its output gain,
 * which `duck` moves. The result is in [summing], which the [MasterOutput] reads.
 *
 * The effects are made by the first voice that uses them and stay (the first one to ask decides the room, later voices may change it: [configureReverb]). Their
 * state is emptied by [clear].
 */
internal class Orbit(
    val number: Double,
    val channels: IntArray,
    private val sampleRate: Int,
    private val executor: IrExecutor = IrExecutor.INLINE,
) {
    /** The stereo sum of everything that reaches the orbit in the current block; after [finishBlock] its output. */
    val summing = StereoBuffer(BLOCK_FRAMES)

    private val delaySend = StereoBuffer(BLOCK_FRAMES)
    private val reverbSend = StereoBuffer(BLOCK_FRAMES)
    private val wet = StereoBuffer(BLOCK_FRAMES)

    private var delay: FeedbackDelay? = null
    private var lastDelayTime = Double.NaN
    private var lastFeedback = Double.NaN
    private var reverb: ReverbBus? = null
    private var djf: DjfStage? = null
    private var djfActive = false

    /** The orbit's reverb, once a voice has made it; for inspection by tests. */
    internal val reverbBus: ReverbBus? get() = reverb

    /** The output gain (`output.gain` of the original) that `duck` automates. */
    private val output = AParam(1.0, -MAX_PARAM, MAX_PARAM, 8)

    /** Starts a block: empties the sums. */
    fun beginBlock(n: Int) {
        summing.clear(n)
        if (delay != null) delaySend.clear(n)
        if (reverb != null) reverbSend.clear(n)
    }

    /** Adds the voice's block, scaled by the `dry` gain [g], to the orbit; a mono block reaches both channels. */
    fun mixDry(b: AudioBlock, g: AParam, ctx: BlockContext) = addScaled(b, g, summing, ctx)

    /** Adds the voice's block, scaled by the `delay` send level [send], to the delay. */
    fun sendDelay(b: AudioBlock, send: AParam, ctx: BlockContext) {
        if (delay != null) addScaled(b, send, delaySend, ctx)
    }

    /** Adds the voice's block, scaled by the `room` send level [send], to the reverb. */
    fun sendReverb(b: AudioBlock, send: AParam, ctx: BlockContext) {
        if (reverb != null) addScaled(b, send, reverbSend, ctx)
    }

    private fun addScaled(b: AudioBlock, g: AParam, dest: StereoBuffer, ctx: BlockContext) {
        val n = ctx.n
        val right = if (b.channels == 2) b.r else b.l
        if (g.isConstant) {
            val gain = g.constantValue.toFloat()
            for (i in 0 until n) {
                dest.left[i] += b.l[i] * gain
                dest.right[i] += right[i] * gain
            }
            return
        }
        val gains = ctx.borrow()
        g.fill(gains, ctx)
        for (i in 0 until n) {
            dest.left[i] += b.l[i] * gains[i]
            dest.right[i] += right[i] * gains[i]
        }
        ctx.release()
    }

    /**
     * `getDelay(delayTime, feedback, t)`: creates the orbit's delay on first use, with that time and feedback, and from then on moves them to the values of a
     * later voice from its start time [atTime] on (a change is abrupt: no crossfade). The feedback is clamped to `[0, 0.98]`. A voice that asks for what is already
     * set schedules nothing. The delay only exists once the first voice with `delay > 0`, a delay time and a feedback above 0 has started.
     */
    fun configureDelay(delayTimeSec: Double, feedback: Double, atTime: Double) {
        val fb = Math.min(Math.max(feedback, 0.0), MAX_FEEDBACK)
        val d = delay
        if (d == null) {
            val created = FeedbackDelay(sampleRate)
            created.start(delayTimeSec, fb)
            delay = created
            lastDelayTime = delayTimeSec
            lastFeedback = fb
            return
        }
        if (delayTimeSec != lastDelayTime) {
            d.delayTime.setValueAtTime(delayTimeSec, atTime)
            lastDelayTime = delayTimeSec
        }
        if (fb != lastFeedback) {
            d.feedback.setValueAtTime(fb, atTime)
            lastFeedback = fb
        }
        d.delayTime.timeline.pruneBefore(atTime)
        d.feedback.timeline.pruneBefore(atTime)
    }

    /**
     * `getReverb(...)`: creates the orbit's reverb on first use and regenerates its impulse response when [r] asks for another room (see [ReverbBus.update]). Returns false when
     * this voice cannot use the reverb yet (the impulse response sample it names is not loaded): it then sends nothing.
     */
    @Suppress("UNUSED_PARAMETER")
    fun configureReverb(r: ReverbParams, atTime: Double): Boolean {
        val bus = reverb ?: ReverbBus(sampleRate, executor, java.lang.Double.doubleToLongBits(number) xor SEED_MIX).also { reverb = it }
        return bus.update(r)
    }

    /** `getDjf(value, t)`: makes the orbit's DJF filter on first use (with this knob position) and sets the knob to [v] from time [atTime] on. */
    fun setDjf(v: Double, atTime: Double) {
        var stage = djf
        if (stage == null) {
            stage = DjfStage(sampleRate)
            djf = stage
        }
        if (!djfActive) {
            stage.value.reset(v)
            djfActive = true
        }
        stage.value.setValueAtTime(v, atTime)
        stage.value.timeline.pruneBefore(atTime)
    }

    /**
     * `Orbit.duck(t, onset, attack, depth)` as superdough runs it [lead] ahead of the event: at engine frame [applyFrame] (`max(now, startFrame - 10 ms)`: the event
     * itself when, as in the engine, voices start exactly on their frame) the output gain's schedule is cut, held at its current value, and ramps
     * exponentially down to `clamp(1 - sqrt(depth), 0.01, current)` at `max(t, now) + onset` and back to 1 over [attack] seconds. [attack] is at least 2 ms. A ramp
     * that cannot be scheduled (a depth that is not a number) is dropped.
     */
    fun duck(nowFrame: Long, startFrame: Long, onset: Double, attack: Double, depth: Double) {
        val applyFrame = Math.max(nowFrame, startFrame - Math.round(DUCK_LEAD_SECONDS * sampleRate))
        val now = applyFrame.toDouble() / sampleRate
        val t = startFrame.toDouble() / sampleRate
        val attackSeconds = Math.max(attack, MIN_DUCK_ATTACK)
        val current = output.valueAt(now)
        output.cancelScheduledValues(now)
        output.setValueAtTime(current, now)
        val t0 = Math.max(t, now)
        val ducked = Math.min(Math.max(1 - Math.sqrt(depth), MIN_DUCKED_GAIN), current)
        output.exponentialRampToValueAtTime(ducked, t0 + onset, now)
        output.exponentialRampToValueAtTime(1.0, t0 + onset + attackSeconds, now)
        output.timeline.pruneBefore(now)
    }

    /** Registers the orbit's parameters that modulators may target (`delay.delayTime`, `delay.feedback`, `djf.value`) with the voice's main registry. */
    fun registerParams(reg: NodeRegistry) {
        delay?.let {
            reg.register("delay", "delayTime", it.delayTime)
            reg.register("delay", "feedback", it.feedback)
        }
        djf?.let { reg.register("djf", "value", it.value) }
    }

    /**
     * Ends a block: the delay and the reverb process what was sent to them and add their output to the orbit's sum, which then goes through the DJF filter and the
     * output gain. After it [summing] is what the orbit contributes to the master output.
     */
    fun finishBlock(ctx: BlockContext) {
        val n = ctx.n
        delay?.let {
            it.process(delaySend.left, delaySend.right, wet.left, wet.right, n, ctx)
            addInto(summing, wet, n)
        }
        reverb?.let {
            it.process(reverbSend.left, reverbSend.right, wet.left, wet.right, n)
            addInto(summing, wet, n)
        }
        if (djfActive) djf!!.process(summing.left, summing.right, n, ctx)

        if (!output.isConstant && output.timeline.lastTime < ctx.time(0)) output.reset(output.valueAt(ctx.time(0))) // a duck that is over
        if (output.isConstant) {
            val g = output.constantValue.toFloat()
            if (g != 1f) {
                for (i in 0 until n) {
                    summing.left[i] *= g
                    summing.right[i] *= g
                }
            }
        } else {
            val gains = ctx.borrow()
            output.fill(gains, ctx)
            for (i in 0 until n) {
                summing.left[i] *= gains[i]
                summing.right[i] *= gains[i]
            }
            ctx.release()
        }
    }

    private fun addInto(dest: StereoBuffer, source: StereoBuffer, n: Int) {
        for (i in 0 until n) {
            dest.left[i] += source.left[i]
            dest.right[i] += source.right[i]
        }
    }

    /**
     * Empties every tail (delay lines, reverb, filter state) and resets the output gain. The delay and the reverb stay (and with it the room the reverb was last built
     * for: rebuilding it would allocate, and what stops playing is the voices); the DJF filter is switched off, as an orbit made anew has none, until a voice sets `djf` again.
     */
    fun clear() {
        summing.clear(BLOCK_FRAMES)
        delaySend.clear(BLOCK_FRAMES)
        reverbSend.clear(BLOCK_FRAMES)
        delay?.clear()
        reverb?.clear()
        djf?.reset()
        djfActive = false
        output.reset(1.0)
    }

    private companion object {
        const val SEED_MIX = 0x5851F42D4C957F2DL
    }
}
