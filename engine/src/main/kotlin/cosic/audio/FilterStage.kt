package cosic.audio

import cosic.audio.dsp.LadderFilter

private const val MAX_PARAM = 3.4e38

/** `getADSRValues(..., 'exponential', [0.005, 0.14, 0, 0.1])` of `createFilter` (helpers.mjs:262-266): the envelope of a filter nobody gave one. */
private val FILTER_ENVELOPE_DEFAULTS = doubleArrayOf(0.005, 0.14, 0.0, 0.1)

/** The most events a filter's frequency envelope puts on its parameter (`getParamADSR` schedules five). */
private const val ENVELOPE_EVENTS = 6

/** `drive = 0.69` of `createFilter`: the ladder's input gain control (exponentiated by the filter itself). */
private const val DEFAULT_DRIVE = 0.69

/** The upper bound `createFilter` puts on a filter envelope's frequencies and on its LFO (helpers.mjs:272-279). */
private const val MAX_FILTER_HZ = 20000.0

/**
 * The controls one of the three filter stages reads, under their canonical names (`lpMap`, `hpMap` and `bpMap` of superdough.mjs:683-773).
 * [shapeKey] is the name of the LFO shape control, which may hold text.
 */
internal class FilterKeys(
    val frequency: Param, val q: Param,
    val attack: Param, val decay: Param, val sustain: Param, val release: Param, val env: Param,
    val rate: Param, val sync: Param, val depth: Param, val depthFrequency: Param,
    val shape: Param, val shapeKey: String, val dcoffset: Param, val skew: Param,
) {
    companion object {
        val LOWPASS = FilterKeys(
            Param.cutoff, Param.resonance, Param.lpattack, Param.lpdecay, Param.lpsustain, Param.lprelease, Param.lpenv,
            Param.lprate, Param.lpsync, Param.lpdepth, Param.lpdepthfrequency, Param.lpshape, "lpshape", Param.lpdc, Param.lpskew,
        )
        val HIGHPASS = FilterKeys(
            Param.hcutoff, Param.hresonance, Param.hpattack, Param.hpdecay, Param.hpsustain, Param.hprelease, Param.hpenv,
            Param.hprate, Param.hpsync, Param.hpdepth, Param.hpdepthfrequency, Param.hpshape, "hpshape", Param.hpdc, Param.hpskew,
        )
        val BANDPASS = FilterKeys(
            Param.bandf, Param.bandq, Param.bpattack, Param.bpdecay, Param.bpsustain, Param.bprelease, Param.bpenv,
            Param.bprate, Param.bpsync, Param.bpdepth, Param.bpdepthfrequency, Param.bpshape, "bpshape", Param.bpdc, Param.bpskew,
        )
    }
}

/**
 * Stages 5 to 7: the low-pass, high-pass and band-pass filter of a chain (superdough.mjs:682-788, `createFilter` of helpers.mjs:234-305),
 * each in the chain when its cutoff (`cutoff`, `hcutoff`, `bandf`) is set.
 *
 * * **Model.** A Web Audio biquad ([Biquad], Chromium's), whose Q is in decibels for the low-pass and the high-pass and linear for the
 *   band-pass; or, when the chain's own `ftype` is the *string* `"ladder"`, the ladder filter ([LadderFilter], always a low-pass, Q and
 *   cutoff raw). A number `ftype` never selects the ladder (`ftype(1)` is a plain biquad), but the 24 dB test goes through
 *   `getFilterType(value.ftype)`, which takes the number modulo 3 (0 `12db`, 1 `ladder`, 2 `24db`) *of the event's own `ftype`, for every
 *   chain*: when that is `"24db"` (or a number that gives it) a second filter with the same settings follows the first (`FX(...)` elements
 *   included, whatever their own `ftype`). The main chain publishes its decision in [VoiceTiming.filter24db]; the second filter is read from there
 *   at the first block. Both filters of a 24 dB pair have identical parameters (same envelope, same LFO, same modulators), so they share
 *   [frequency] and [q] and the coefficients are computed once: modulators connect once, to the shared parameter, which is registered once.
 * * **Envelope.** When any of attack/decay/sustain/release/env is set, [getParamADSR] schedules an exponential envelope on the frequency
 *   between `2^-(|env| anchor) f` and `2^(|env| (1 - anchor)) f` (both limited to 0..20000 Hz, swapped for a negative `env`), with
 *   the defaults `[0.005, 0.14, 0, 0.1]` when none of the four is given, from the voice's onset to the end of its gate.
 * * **LFO.** When any of depth, depthfrequency, skew, shape, rate (or sync) is set, an [LfoOsc] adds `depthfrequency`, or `depth * f` (depth
 *   1 by default), around the cutoff at `rate`, or `sync * cps`, or `cps` Hz, clamped to `[-f + 30, 20000 - f]`; a depth of 0 means no LFO.
 * * **Update rate.** A biquad is not a function of its parameters sample by sample in this engine: while the frequency or Q moves (an
 *   envelope that has not finished, an LFO or another modulator) the coefficients are computed again every [updateInterval] frames
 *   (16: [FILTER_UPDATE_INTERVAL]) of the absolute frame counter, from the parameters at the first frame of the interval; Chromium does it for
 *   every frame, which the stage reproduces with an interval of 1 (the golden tests do). A parameter that has finished moving (past its last
 *   event, nothing connected) is evaluated once. The ladder reads its parameters at the first frame of each absolute 128-frame quantum, as
 *   its worklet does. Both are independent of how the rendering is split into blocks.
 *
 * Parameters are float32 in Web Audio and are rounded to float here for the same reason. Memory: about 4 KB per instance, shared by
 * the two filters of a 24 dB pair; nothing allocates after construction.
 */
internal class FilterStage(
    private val sampleRate: Int,
    val type: BiquadType,
    val keys: FilterKeys,
    private val updateInterval: Int = FILTER_UPDATE_INTERVAL,
) : ChainStage {
    init {
        require(updateInterval >= 1) { "updateInterval must be at least 1" }
    }

    private val nodeName: String = when (type) {
        BiquadType.LOWPASS -> "lpf"
        BiquadType.HIGHPASS -> "hpf"
        BiquadType.BANDPASS -> "bpf"
        BiquadType.NOTCH -> "notch"
    }
    private val lfoNodeName: String = nodeName + "_lfo"

    /** The cutoff in Hz (`lpf.frequency`): the envelope and the LFO act on it. Range `[0, nyquist]` like the Web Audio parameter. */
    val frequency = AParam(0.0, 0.0, sampleRate / 2.0, ENVELOPE_EVENTS)

    /** The resonance (`lpf.Q`): decibels for the low-pass and high-pass biquad, linear for the band-pass biquad and the ladder. */
    val q = AParam(1.0, -MAX_PARAM, MAX_PARAM, 2)

    private val biquad = Biquad(sampleRate)
    private val biquad2 = Biquad(sampleRate)
    private val ladder = LadderFilter(sampleRate)
    private val ladder2 = LadderFilter(sampleRate)
    private val lfo = LfoOsc(sampleRate)

    private var timing: VoiceTiming? = null
    private var useLadder = false
    private var drive = DEFAULT_DRIVE
    private var lfoOn = false

    /** Whether [twice] has been read from the timing yet (at the first block, so that `FX` chains see the main chain's `ftype`). */
    private var resolved = false
    private var twice = false

    // the biquad schedule: coefficients are valid from the last evaluation until frame nextEval (NEVER once the parameters have settled)
    private var valid = false
    private var nextEval = 0L

    // the ladder schedule: the parameter values of the quantum being rendered
    private var ladderQuantum = Long.MIN_VALUE
    private var ladderFrequency = 0.0
    private var ladderQ = 0.0

    override fun configure(p: ParamSet, timing: VoiceTiming, isMain: Boolean): Boolean {
        // getFilterType(value.ftype): the event's own ftype decides the 24 dB pair of every chain; the three filter stages of the main
        // chain all write the same answer, and the stages of the FX chains (configured before it) read it later
        if (isMain) timing.filter24db = is24db(p)
        if (!p.has(keys.frequency)) return false
        this.timing = timing

        val f = p[keys.frequency]
        useLadder = p.str("ftype") == "ladder"
        drive = p.or(Param.drive, DEFAULT_DRIVE)
        frequency.reset(f)
        q.reset(p.or(keys.q, 1.0))
        valid = false
        nextEval = 0L
        ladderQuantum = Long.MIN_VALUE
        resolved = false
        twice = false

        scheduleEnvelope(p, timing, f)
        lfoOn = startLfo(p, timing, f)
        return true
    }

    /** Envelope on the frequency (helpers.mjs:262-278). */
    private fun scheduleEnvelope(p: ParamSet, timing: VoiceTiming, f: Double) {
        val a = p[keys.attack]
        val d = p[keys.decay]
        val s = p[keys.sustain]
        val r = p[keys.release]
        val hasEnvelope = !(a.isNaN() && d.isNaN() && s.isNaN() && r.isNaN() && !p.has(keys.env))
        if (!hasEnvelope) return
        val env = nanFallback(p[keys.env], 1.0)
        val anchor = nanFallback(p[Param.fanchor], 0.0)
        val envAbs = Math.abs(env)
        val offset = envAbs * anchor
        var min = clamp(Math.pow(2.0, -offset) * f, 0.0, MAX_FILTER_HZ)
        var max = clamp(Math.pow(2.0, envAbs - offset) * f, 0.0, MAX_FILTER_HZ)
        if (env < 0.0) {
            val swap = min
            min = max
            max = swap
        }
        getParamADSR(
            frequency,
            adsrComponent(0, a, d, s, r, FILTER_ENVELOPE_DEFAULTS), adsrComponent(1, a, d, s, r, FILTER_ENVELOPE_DEFAULTS),
            adsrComponent(2, a, d, s, r, FILTER_ENVELOPE_DEFAULTS), adsrComponent(3, a, d, s, r, FILTER_ENVELOPE_DEFAULTS),
            min, max, timing.t, timing.end, true,
        )
    }

    /** The LFO on the frequency (helpers.mjs:280-305); returns whether one runs. */
    private fun startLfo(p: ParamSet, timing: VoiceTiming, f: Double): Boolean {
        val cps = timing.cps
        val hasSync = p.has(keys.sync)
        val hasRate = hasSync || p.has(keys.rate)
        val hasShape = p.has(keys.shape) || p.str(keys.shapeKey) != null
        val hasLfo = p.has(keys.depth) || p.has(keys.depthFrequency) || p.has(keys.skew) || hasShape || hasRate
        if (!hasLfo) return false
        val rate = if (hasSync) cps * p[keys.sync] else p[keys.rate]
        val depth = p.or(keys.depth, 1.0)
        val modDepth = if (p.has(keys.depthFrequency)) p[keys.depthFrequency] else depth * f
        // getParamLfo: `if (depth)`, a depth of 0 (or NaN) makes no LFO
        if (modDepth == 0.0 || modDepth.isNaN()) return false
        val shape = if (p.has(keys.shape)) shapeIndex(p[keys.shape]) else lfoShapeIndex(p.str(keys.shapeKey))
        lfo.start(
            shape, p.or(keys.skew, 0.5), modDepth, p.or(keys.dcoffset, -0.5), null, if (hasRate) rate else cps,
            timing.cycle / cps, 0.0, 1.0, -f + 30.0, MAX_FILTER_HZ - f, timing.t, timing.end,
        )
        frequency.addInput(lfo)
        return true
    }

    override fun process(b: AudioBlock, ctx: BlockContext) {
        val n = ctx.n
        if (n <= 0) return
        if (!resolved) {
            twice = timing?.filter24db == true
            resolved = true
        }
        // a parameter with connected modulators is read from a block-sized buffer (the modulators render once per block)
        var borrowed = 0
        var frequencies: FloatArray? = null
        var qs: FloatArray? = null
        if (frequency.hasInputs) {
            frequencies = ctx.borrow()
            borrowed++
            frequency.fill(frequencies, ctx)
        }
        if (q.hasInputs) {
            qs = ctx.borrow()
            borrowed++
            q.fill(qs, ctx)
        }
        if (useLadder) processLadder(b, ctx, frequencies, qs) else processBiquad(b, ctx, frequencies, qs)
        while (borrowed > 0) {
            ctx.release()
            borrowed--
        }
    }

    private fun processBiquad(b: AudioBlock, ctx: BlockContext, frequencies: FloatArray?, qs: FloatArray?) {
        val n = ctx.n
        val stereo = b.channels == 2
        var i = 0
        while (i < n) {
            if (!valid || ctx.startFrame + i >= nextEval) evaluate(ctx, i, frequencies, qs)
            val to = if (nextEval == NEVER) n else Math.min(n.toLong(), nextEval - ctx.startFrame).toInt()
            biquad.process(b.l, i, to, 0)
            if (stereo) biquad.process(b.r, i, to, 1)
            if (twice) {
                biquad2.process(b.l, i, to, 0)
                if (stereo) biquad2.process(b.r, i, to, 1)
            }
            i = to
        }
    }

    /** Computes the coefficients from the parameters at block index [i] and schedules the next computation. */
    private fun evaluate(ctx: BlockContext, i: Int, frequencies: FloatArray?, qs: FloatArray?) {
        val time = ctx.time(i)
        val f = if (frequencies != null) frequencies[i].toDouble() else frequency.valueAt(time).toFloat().toDouble()
        val qv = if (qs != null) qs[i].toDouble() else q.valueAt(time).toFloat().toDouble()
        biquad.setCoefficients(type, f, qv)
        if (twice) biquad2.copyCoefficientsFrom(biquad)
        valid = true
        nextEval = if (settled(frequency, time) && settled(q, time)) NEVER else (Math.floorDiv(ctx.startFrame + i, updateInterval.toLong()) + 1) * updateInterval
    }

    private fun processLadder(b: AudioBlock, ctx: BlockContext, frequencies: FloatArray?, qs: FloatArray?) {
        val n = ctx.n
        val right = if (b.channels == 2) b.r else null
        var i = 0
        while (i < n) {
            val quantum = Math.floorDiv(ctx.startFrame + i, QUANTUM.toLong())
            if (quantum != ladderQuantum) {
                ladderQuantum = quantum
                // the worklet reads parameters[name][0]: the value at the first frame of the quantum, even when this block starts later
                val start = quantum * QUANTUM
                val index = Math.max(0L, start - ctx.startFrame).toInt()
                val time = start.toDouble() / sampleRate
                ladderFrequency = if (frequencies != null) frequencies[index].toDouble() else frequency.valueAt(time).toFloat().toDouble()
                ladderQ = if (qs != null) qs[index].toDouble() else q.valueAt(time).toFloat().toDouble()
            }
            val to = Math.min(n.toLong(), (quantum + 1) * QUANTUM - ctx.startFrame).toInt()
            ladder.process(b.l, right, i, to, ladderFrequency, ladderQ, drive)
            if (twice) ladder2.process(b.l, right, i, to, ladderFrequency, ladderQ, drive)
            i = to
        }
    }

    override fun reset() {
        biquad.reset()
        biquad2.reset()
        ladder.reset()
        ladder2.reset()
        frequency.reset(0.0)
        q.reset(1.0)
        timing = null
        useLadder = false
        lfoOn = false
        resolved = false
        twice = false
        valid = false
        nextEval = 0L
        ladderQuantum = Long.MIN_VALUE
    }

    /**
     * `lpf.frequency` and `lpf.Q` (the 24 dB pair registers them once, see the class comment), and, when the stage has an LFO, the
     * parameters of `lpf_lfo` that its modulation can reach (`lpdepth`, `lpskew`, `lpdc`: `depth`, `skew`, `dcoffset`). Superdough's
     * `lprate` and `lpsync` target `lpf_lfo.rate`, which the worklet does not have, so they find nothing there.
     */
    override fun registerParams(reg: NodeRegistry) {
        reg.register(nodeName, "frequency", frequency)
        reg.register(nodeName, "Q", q)
        if (lfoOn) {
            reg.register(lfoNodeName, "frequency", lfo.frequencyParam)
            reg.register(lfoNodeName, "depth", lfo.depthParam)
            reg.register(lfoNodeName, "skew", lfo.skewParam)
            reg.register(lfoNodeName, "curve", lfo.curveParam)
            reg.register(lfoNodeName, "dcoffset", lfo.dcoffsetParam)
        }
    }

    private companion object {
        const val NEVER = Long.MAX_VALUE

        /** No modulator is connected and the automation is over: the value stays what it is. */
        fun settled(p: AParam, time: Double): Boolean = !p.hasInputs && time >= p.timeline.lastTime

        /**
         * `getFilterType(value.ftype) === '24db'` (superdough.mjs:374-378): a string is taken as it is; a number selects
         * `['12db', 'ladder', '24db'][floor(_mod(n, 3))]`, and no number (`ftype ?? 0`) is `'12db'`.
         */
        fun is24db(p: ParamSet): Boolean {
            val name = p.str("ftype")
            if (name != null) return name == "24db"
            val v = p.or(Param.ftype, 0.0)
            return Math.floor(((v % 3.0) + 3.0) % 3.0) == 2.0
        }

        /** `getModulationShapeInput` of a number (`val % 5`) as [lfoShapeIndex] reads it, without boxing the number. */
        fun shapeIndex(v: Double): Int {
            val m = v % 5.0
            return if (m >= 0.0 && m == Math.floor(m)) m.toInt() else -1
        }
    }
}
