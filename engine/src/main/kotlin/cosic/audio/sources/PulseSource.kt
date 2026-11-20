package cosic.audio.sources

import cosic.audio.BLOCK_FRAMES
import cosic.audio.BlockContext
import cosic.audio.LfoOsc

/**
 * The sound `pulse` (synth.mjs:295-370, the `pulse-oscillator` worklet of worklets.mjs:750-850): a band-limited pulse wave made of two half-Tomisawa oscillators
 * (`0.15 * (out0 - out1)`, the second one offset by the pulse width), with a phase increment and an envelope that are smoothed one pole at a time. The pulse width is
 * `pw` (0.5), moved by a triangle LFO when `pwsweep` is not 0: `pwsweep` is the depth (0.3 when only `pwrate` is set, else 0), `pwrate` the frequency in Hz (1). The
 * width is clamped to ±0.99 where the worklet reads it, the sum with the LFO is not.
 *
 * Two quirks of the worklet are kept: its internal envelope `env` restarts at 1 at the start of every render quantum (a ripple of about 2.5 % at 375 Hz), and it only
 * runs on whole quanta, from the first one that starts after `begin` ([WorkletGate]). The first frames therefore come in with the one-pole smoothing from zero, 1 to
 * 128 frames after the start of the note. The outer envelope is `getParamADSR(0..1)` with the defaults `[0.001, 0.05, 0.6, 0.01]`. [pitch] (cents) and [fm] (Hz) arrive
 * through the [PitchBus]. Both channels are the same signal, but the node is stereo.
 */
internal class PulseSource(spec: SourceSpec, private val pitch: PitchModulator?, private val fm: FrequencyModulator?) : BlockSource() {
    private val sampleRate = spec.sampleRate
    private val invSampleRate = 1.0 / spec.sampleRate
    private val durationSec = if (spec.durationSec.isNaN()) 0.0 else spec.durationSec
    private val hz: Double
    private val pulseWidth: Float
    private val sweepDepth: Double
    private val sweepRate: Double
    private val envelope = SourceEnvelope(sampleRate, spec.controls, 1.0)
    private val gate = WorkletGate(sampleRate)
    private val bus: PitchBus? = if (pitch != null || fm != null) PitchBus(BLOCK_FRAMES) else null
    private val lfo: LfoOsc?
    private val lfoContext: BlockContext?
    private val widths = FloatArray(BLOCK_FRAMES)
    private val gains = FloatArray(BLOCK_FRAMES)

    // the state of the worklet
    private var phi = -Math.PI
    private var y0 = 0.0
    private var y1 = 0.0
    private var dphif = 0.0
    private var envf = 0.0
    private var env = 1.0

    override val channels: Int get() = 2

    init {
        val c = spec.controls
        val rate = c.number("pwrate")
        val sweep = c.number("pwsweep") ?: if (rate != null) DEFAULT_SWEEP else 0.0
        hz = asParam(frequencyFromValue(c))
        pulseWidth = (c.number("pw") ?: DEFAULT_WIDTH).toFloat()
        sweepDepth = sweep
        sweepRate = rate ?: DEFAULT_RATE
        if (sweep != 0.0) {
            lfo = LfoOsc(sampleRate)
            // the LFO only reads its own parameters; its scratch buffers are never borrowed, so the context needs no room for them
            lfoContext = BlockContext(sampleRate, 1)
        } else {
            lfo = null
            lfoContext = null
        }
    }

    override fun start(startFrame: Long) {
        frame = startFrame
        val t = startFrame.toDouble() / sampleRate
        val end = envelope.schedule(t, durationSec)
        gate.open(t, end, startFrame)
        endFrame = gate.to
        phi = -Math.PI
        y0 = 0.0
        y1 = 0.0
        dphif = 0.0
        envf = 0.0
        env = 1.0
        lfo?.let { l ->
            // getLfo(ac, { frequency: pwrate, depth: pwsweep, begin, end }): a triangle (shape 0) with the defaults of getLfo, its bounds `dcoffset * depth` and
            // `dcoffset * depth + depth`; every value is the float32 of a node parameter
            val depth = asParam(sweepDepth)
            l.start(
                0, 0.5, depth, -0.5, null, asParam(sweepRate), asParam(t), 0.0, 1.0,
                asParam(-0.5 * sweepDepth), asParam(-0.5 * sweepDepth + sweepDepth), asParam(t), asParam(end),
            )
        }
        (pitch as? PitchModulation)?.restart()
    }

    override fun renderBlock(left: FloatArray, right: FloatArray, offset: Int, m: Int) {
        val bus = this.bus
        if (bus != null) {
            bus.clear(m)
            pitch?.add(bus, m)
            fm?.add(bus, m)
        }
        val from = (gate.from - frame).coerceIn(0L, m.toLong()).toInt()
        val to = (gate.to - frame).coerceIn(0L, m.toLong()).toInt()
        java.util.Arrays.fill(left, offset, offset + m, 0f)
        if (to > from) {
            val lfo = this.lfo
            if (lfo != null) {
                val ctx = lfoContext!!
                ctx.startFrame = frame
                ctx.n = m
                java.util.Arrays.fill(widths, 0, m, pulseWidth)
                lfo.addInto(widths, ctx)
            } else {
                java.util.Arrays.fill(widths, 0, m, pulseWidth)
            }
            generate(left, offset, from, to, bus)
        }
        envelope.fill(gains, frame, m)
        for (i in 0 until m) {
            val v = left[offset + i] * gains[i]
            left[offset + i] = v
            right[offset + i] = v
        }
    }

    /** The worklet's `process` over frames `[from, to)` of the block. */
    private fun generate(out: FloatArray, offset: Int, from: Int, to: Int, bus: PitchBus?) {
        val cents = if (bus != null && bus.detuneActive) bus.detuneCents else null
        val add = if (bus != null && bus.frequencyActive) bus.frequencyAddHz else null
        val baseHz = hz.toFloat()
        var phi = this.phi
        var y0 = this.y0
        var y1 = this.y1
        var dphif = this.dphif
        var envf = this.envf
        var env = this.env
        for (k in from until to) {
            if (((frame + k) and (QUANTUM_MASK)) == 0L) env = 1.0 // `let env = 1` at the top of every process() call
            val width = (1.0 - Math.min(Math.max(widths[k].toDouble(), -0.99), 0.99)) * Math.PI
            var freq = if (add != null) (baseHz + add[k].toFloat()).toDouble() else hz
            if (cents != null) freq *= Math.pow(2.0, (cents[k].toFloat().toDouble() / 100.0) / 12.0)
            val dphi = freq * TWO_PI * invSampleRate
            dphif += 0.1 * (dphi - dphif)
            env *= 0.9998
            envf += 0.1 * (env - envf)
            var b = 2.3 * (1.0 - 0.0001 * freq)
            if (b < 0.0) b = 0.0
            phi += dphif
            if (phi >= Math.PI) phi -= TWO_PI
            val out0 = Math.cos(phi + b * y0)
            y0 = 0.5 * (out0 + y0)
            val out1 = Math.cos(phi + b * y1 + width)
            y1 = 0.5 * (out1 + y1)
            out[offset + k] = (0.15 * (out0 - out1) * envf).toFloat()
        }
        this.phi = phi
        this.y0 = y0
        this.y1 = y1
        this.dphif = dphif
        this.envf = envf
        this.env = env
    }

    private companion object {
        const val DEFAULT_WIDTH = 0.5
        const val DEFAULT_SWEEP = 0.3
        const val DEFAULT_RATE = 1.0
        const val TWO_PI = 2.0 * Math.PI
        const val QUANTUM_MASK = 127L
    }
}
