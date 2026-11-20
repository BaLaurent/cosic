package cosic.audio.sources

import cosic.WavetableFrames
import cosic.audio.AParam
import cosic.audio.BLOCK_FRAMES
import cosic.audio.BlockContext
import cosic.audio.LfoOsc
import cosic.audio.QUANTUM
import cosic.audio.STEAL_RAMP_SECONDS
import cosic.audio.adsrComponent
import cosic.audio.getParamADSR
import cosic.audio.lfoDefaultMax
import cosic.audio.lfoDefaultMin
import cosic.audio.lfoShapeIndex

private const val MAX_PARAM = 3.4e38

/** The peak the envelope of a wavetable note reaches (`getParamADSR(node.gain, …, 0, 0.3, …)`, wavetable.mjs:316). */
private const val ENVELOPE_PEAK = 0.3

/** What the worklet is given beyond the note's own end: the release is followed by this much silence before the node is dropped. */
private const val TAIL_SECONDS = 0.01

private val ENVELOPE_DEFAULTS = doubleArrayOf(0.0, 0.5, 0.0, 0.1)

/**
 * The sound `s("wt_…")` makes: `onTriggerSynth` of superdough's wavetable.mjs (234-336) on [WavetableOscillator]. It renders stereo, with the
 * amplitude envelope inside (a linear ADSR from 0 to 0.3, the peak of the `gain` node of superdough), and ends `0.01 s` after the release.
 *
 * Controls read, all by Strudel's canonical names: `note`/`freq`/`octave` (the pitch), `detune` (unison spread in semitones, 0.18), `unison` (voices),
 * `spread` (stereo spread, 0.7), `wtphaserand`, `wt` (position) and `warp` with `warpmode`, `attack`, `decay`, `sustain`, `release` and `clip`, and the
 * position and warp modulators: an envelope (`wtenv`, `wtattack`, `wtdecay`, `wtsustain`, `wtrelease`) and an LFO (`wtrate` or `wtsync`, `wtdepth`,
 * `wtshape`, `wtskew`, `wtdc`), likewise `warpenv`, `warpattack`…, `warprate`, `warpsync`…
 *
 * [pitch] (vibrato, pitch envelope) and [fm] add into the oscillator's pitch inputs through a [PitchBus]. Both may be null. The note starts at its
 * first frame, not at the next render quantum as the worklet does; it ends at the first quantum that starts after its end.
 */
internal class WavetableSource(
    private val spec: SourceSpec,
    val frames: WavetableFrames,
    private val pitch: PitchModulator? = null,
    private val fm: FrequencyModulator? = null,
) : SoundSource {
    override val channels: Int get() = 2

    private val sampleRate = spec.sampleRate
    private val c = spec.controls

    private val unison = c.number("unison")
    private val voices = Math.max(unison ?: 1.0, 1.0)
    private val oscillator = WavetableOscillator(sampleRate, frames.frames, SourceRng(spec.seed), Math.ceil(voices).toInt())
    private val params = WavetableParams()
    private val bus: PitchBus? = if (pitch != null || fm != null) PitchBus(BLOCK_FRAMES) else null
    private val ctx = BlockContext(sampleRate)

    private val envelope = AParam(1.0, -MAX_PARAM, MAX_PARAM, 8)
    private val position = AParam(0.0, -MAX_PARAM, MAX_PARAM, 8)
    private val warp = AParam(0.0, -MAX_PARAM, MAX_PARAM, 8)
    private val positionLfo = LfoOsc(sampleRate)
    private val warpLfo = LfoOsc(sampleRate)
    private val positionBuf = FloatArray(BLOCK_FRAMES)
    private val warpBuf = FloatArray(BLOCK_FRAMES)
    private val gainBuf = FloatArray(BLOCK_FRAMES)

    private var frame = 0L
    private var endFrame = Long.MAX_VALUE
    private var started = false

    /** The time in seconds at which the source ends: the end of the release and `0.01 s` more (`envEnd`). Valid after [start]. */
    override var endSeconds = 0.0
        private set

    override val isFinished: Boolean get() = started && frame >= endFrame

    override fun start(startFrame: Long) {
        val t = startFrame.toDouble() / sampleRate
        val duration = if (spec.durationSec > 0.0) spec.durationSec else 0.0
        val attackSet = c.number("attack") ?: Double.NaN
        val decaySet = c.number("decay") ?: Double.NaN
        val sustainSet = c.number("sustain") ?: Double.NaN
        val releaseSet = c.number("release") ?: Double.NaN
        val attack = adsrComponent(0, attackSet, decaySet, sustainSet, releaseSet)
        val decay = adsrComponent(1, attackSet, decaySet, sustainSet, releaseSet)
        val sustain = adsrComponent(2, attackSet, decaySet, sustainSet, releaseSet)
        val release = adsrComponent(3, attackSet, decaySet, sustainSet, releaseSet)

        // wavetable.mjs:222-230: the hold ends with the event; `clip` shortens it again (the duration already has the clip in it).
        var holdEnd = t + duration
        c.number("clip")?.let { holdEnd = Math.min(t + it * duration, holdEnd) }
        val endWithRelease = holdEnd + release
        val envEnd = endWithRelease + TAIL_SECONDS
        endSeconds = envEnd

        params.frequency = frequencyFromValue(c)
        params.freqspread = c.number("detune") ?: 0.18
        params.warpMode = WarpModes.fromControl(c["warpmode"])
        params.voices = voices
        params.panspread = c.number("spread") ?: 0.7
        val phaseRand = c.number("wtphaserand")
        params.phaserand = if (if (phaseRand != null) phaseRand != 0.0 else (unison ?: 0.0) > 1.0) 1.0 else 0.0

        val wt = c.number("wt")
        val warpValue = c.number("warp")
        position.reset(wt ?: 0.0)
        warp.reset(warpValue ?: 0.0)
        val cps = spec.cps
        modulate(position, positionLfo, "wt", wt, cps, t, endWithRelease, holdEnd)
        modulate(warp, warpLfo, "warp", warpValue, cps, t, endWithRelease, holdEnd)

        envelope.reset(1.0)
        getParamADSR(envelope, attack, decay, sustain, release, 0.0, ENVELOPE_PEAK, t, holdEnd, false)

        oscillator.reset()
        frame = startFrame
        // The worklet runs the quanta that start before its `end`: the last one is the one that contains it.
        endFrame = Math.max(startFrame, Math.ceil(envEnd * sampleRate / QUANTUM).toLong() * QUANTUM)
        started = true
    }

    /** The note is stolen by a voice that starts at [tNew]: `node.gain.linearRampToValueAtTime(0, tNew + 0.25)`, on the timeline of the envelope. */
    override fun steal(tNew: Double) {
        envelope.linearRampToValueAtTime(0.0, tNew + STEAL_RAMP_SECONDS, tNew)
    }

    /**
     * `applyParameterModulators` (helpers.mjs:216-233) for the position or the warp [param] of the controls that start with [stem] (`wt`, `warp`):
     * the envelope from [offset] (the `wt` or `warp` control) by `<stem>env`, shaped by `<stem>attack`, `decay`, `sustain` and `release`, and the LFO of
     * `<stem>rate`, `sync`, `depth`, `shape`, `skew` and `dc`.
     */
    private fun modulate(param: AParam, lfo: LfoOsc, stem: String, offset: Double?, cps: Double, start: Double, end: Double, holdEnd: Double) {
        val amountKey = stem + "env"
        val rateKey = stem + "rate"
        val syncKey = stem + "sync"
        val depthKey = stem + "depth"
        val shapeKey = stem + "shape"
        val skewKey = stem + "skew"
        val dcKey = stem + "dc"
        val a = c.number(stem + "attack") ?: Double.NaN
        val d = c.number(stem + "decay") ?: Double.NaN
        val s = c.number(stem + "sustain") ?: Double.NaN
        val r = c.number(stem + "release") ?: Double.NaN
        val hasAdsr = !a.isNaN() || !d.isNaN() || !s.isNaN() || !r.isNaN()
        val amount = c.number(amountKey) ?: if (hasAdsr) 0.5 else 0.0
        val min = offset ?: 0.0
        val max = amount + min
        if (Math.abs(max - min) != 0.0) {
            getParamADSR(
                param,
                adsrComponent(0, a, d, s, r, ENVELOPE_DEFAULTS), adsrComponent(1, a, d, s, r, ENVELOPE_DEFAULTS),
                adsrComponent(2, a, d, s, r, ENVELOPE_DEFAULTS), adsrComponent(3, a, d, s, r, ENVELOPE_DEFAULTS),
                min, max, start, holdEnd, false,
            )
        }

        // getParamLfo (helpers.mjs:195-214): no `depth` control means 0.5 when any of the LFO's own controls is set, else no LFO.
        var rate = c.number(rateKey)
        c.number(syncKey)?.let { rate = cps * it }
                val skew = c.number(skewKey)
        val hasLfoInputs = rate != null || c.isSet(shapeKey) || skew != null
        val depth = c.number(depthKey) ?: if (hasLfoInputs) 0.5 else 0.0
        if (depth != 0.0) {
            val dcoffset = c.number(dcKey) ?: 0.0
            lfo.start(
                lfoShapeIndex(if (c.isSet(shapeKey)) c[shapeKey] else null), skew ?: 0.5, depth, dcoffset, null, rate ?: 1.0, start,
                0.0, 1.0, lfoDefaultMin(dcoffset, depth), lfoDefaultMax(dcoffset, depth), start, end,
            )
            param.addInput(lfo)
        }
    }

    override fun render(left: FloatArray, right: FloatArray, n: Int): Int {
        check(started) { "WavetableSource.render before start" }
        val total = Math.min(n.toLong(), endFrame - frame).toInt().coerceAtLeast(0)
        var done = 0
        while (done < total) {
            val chunk = Math.min(total - done, BLOCK_FRAMES)
            renderChunk(left, right, done, chunk)
            done += chunk
        }
        return total
    }

    private fun renderChunk(left: FloatArray, right: FloatArray, offset: Int, n: Int) {
        ctx.startFrame = frame
        ctx.n = n
        val p = params
        if (position.isConstant) {
            p.positionBuf = null
            p.position = position.constantValue
        } else {
            position.fill(positionBuf, ctx)
            p.positionBuf = positionBuf
        }
        if (warp.isConstant) {
            p.warpBuf = null
            p.warp = warp.constantValue
        } else {
            warp.fill(warpBuf, ctx)
            p.warpBuf = warpBuf
        }
        val bus = bus
        if (bus != null) {
            bus.clear(n)
            pitch?.add(bus, n)
            fm?.add(bus, n)
        }
        // The oscillator writes from index 0; the caller's arrays are written at [offset], so a chunk goes through scratch only when it is not the first.
        val outL = if (offset == 0) left else scratchL
        val outR = if (offset == 0) right else scratchR
        oscillator.render(outL, outR, n, p, bus)
        envelope.fill(gainBuf, ctx)
        for (i in 0 until n) {
            val g = gainBuf[i]
            outL[i] *= g
            outR[i] *= g
        }
        if (offset != 0) {
            System.arraycopy(outL, 0, left, offset, n)
            System.arraycopy(outR, 0, right, offset, n)
        }
        frame += n
    }

    private val scratchL = FloatArray(BLOCK_FRAMES)
    private val scratchR = FloatArray(BLOCK_FRAMES)
}
