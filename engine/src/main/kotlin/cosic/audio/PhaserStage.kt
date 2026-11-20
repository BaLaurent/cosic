package cosic.audio

private const val MAX_PARAM = 3.4028235e38

/**
 * Stage 16, the phaser: one notch `BiquadFilterNode` whose `detune` (cents) follows an LFO (`getPhaser`, superdough.mjs:352-372, 871-885). In the chain
 * when `phaserrate` is set and `phaserdepth` (default 0.75) is above 0. (superdough's phaser is ONE notch, not a chain of all-pass filters.)
 *
 * The filter: `frequency = phasercenter + 282` (`phasercenter` defaults to 1000 Hz; the offset keeps old patches sounding as they did), linear
 * `Q = 2 - clamp(2 * phaserdepth, 0, 1.9)`. The LFO is `getLfo({frequency: phaserrate, depth: 2 * phasersweep, begin: t, end: endWithRelease})`: a triangle
 * of `phasersweep` (default 2000, so `phasersweep` is in cents, not Hz) around 0, starting from the phase `t * rate` (absolute time, not the cycle),
 * silent until the first render quantum after the onset. The notch moves with the LFO sample by sample, like the browser's a-rate `detune`.
 *
 * The filter is Chromium's `BiquadFilterHandler` + `Biquad` for the notch (platform/audio/biquad.cc, modules/webaudio/biquad_filter_handler.cc), same arithmetic:
 * the frequency, `Q` and `detune` AudioParams are float32 per frame, the coefficients are computed in double from `frequency / nyquist * 2^(detune / 1200)`
 * (clamped to 0..1) for every frame the inputs change, and the filter runs in direct form I with double coefficients, float32 input history and
 * float32-rounded output feedback, one state per channel. Q of 0 or less outputs silence, a frequency of 0 or the Nyquist frequency is a pass-through.
 *
 * Modulators reach it as `phaser.frequency/Q/detune` and `phaser_lfo.frequency/depth/skew/curve/dcoffset`.
 */
internal class PhaserStage(sampleRate: Int) : ChainStage {
    private val nyquist = sampleRate / 2.0
    private val lfo = LfoOsc(sampleRate)

    /** `frequency` of the notch, Hz (0 to Nyquist). */
    val frequency = AParam(350.0, 0.0, nyquist, 2)

    /** `Q`, linear for a notch. */
    val q = AParam(1.0, -MAX_PARAM, MAX_PARAM, 2)

    /** `detune`, cents (+-153600, the range of the node); the LFO is connected to it. */
    val detune = AParam(0.0, -153600.0, 153600.0, 2)

    // Direct form I state of the two channels.
    private var x1l = 0.0
    private var x2l = 0.0
    private var y1l = 0.0
    private var y2l = 0.0
    private var x1r = 0.0
    private var x2r = 0.0
    private var y1r = 0.0
    private var y2r = 0.0

    // The coefficients of the last frame, reused while the three inputs stay the same.
    private var lastFrequency = Float.NaN
    private var lastQ = Float.NaN
    private var lastDetune = Float.NaN
    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0

    override fun configure(p: ParamSet, timing: VoiceTiming, isMain: Boolean): Boolean {
        val rate = p[Param.phaserrate]
        if (rate.isNaN()) return false
        val depth = p.or(Param.phaserdepth, DEFAULT_DEPTH)
        if (!(depth > 0.0)) return false
        val center = p.or(Param.phasercenter, DEFAULT_CENTER)
        val sweep = p.or(Param.phasersweep, DEFAULT_SWEEP)
        frequency.reset(center + FREQUENCY_OFFSET)
        q.reset(2.0 - Math.min(Math.max(depth * 2.0, 0.0), 1.9))
        detune.reset(0.0)
        val lfoDepth = sweep * 2.0
        // The worklet's parameters are AudioParams, float32 (an onset on a quantum boundary is "after begin" or not depending on how that rounds).
        lfo.start(
            shape = 0,
            skew = 0.5,
            depth = f32(lfoDepth),
            dcoffset = -0.5,
            frequency = null,
            freqHz = f32(rate),
            time = f32(timing.t),
            phaseoffset = 0.0,
            curve = 1.0,
            min = f32(lfoDefaultMin(-0.5, lfoDepth)),
            max = f32(lfoDefaultMax(-0.5, lfoDepth)),
            begin = f32(timing.t),
            end = f32(timing.endWithRelease),
        )
        detune.addInput(lfo)
        clearState()
        return true
    }

    override fun process(b: AudioBlock, ctx: BlockContext) {
        val n = ctx.n
        val stereo = b.channels == 2
        val frequencies = ctx.borrow()
        val qs = ctx.borrow()
        val detunes = ctx.borrow()
        frequency.fill(frequencies, ctx)
        q.fill(qs, ctx)
        detune.fill(detunes, ctx)
        val l = b.l
        val r = b.r
        var x1L = x1l
        var x2L = x2l
        var y1L = y1l
        var y2L = y2l
        var x1R = x1r
        var x2R = x2r
        var y1R = y1r
        var y2R = y2r
        for (i in 0 until n) {
            val f = frequencies[i]
            val qq = qs[i]
            val dt = detunes[i]
            if (f != lastFrequency || qq != lastQ || dt != lastDetune) {
                setCoefficients(f, qq, dt)
                lastFrequency = f
                lastQ = qq
                lastDetune = dt
            }
            var x = l[i]
            if (x - x != 0f) x = 0f // a non-finite input would stay in the filter for good
            val y = (b0 * x + b1 * x1L + b2 * x2L - a1 * y1L - a2 * y2L).toFloat()
            l[i] = y
            x2L = x1L
            x1L = x.toDouble()
            y2L = y1L
            y1L = y.toDouble()
            if (stereo) {
                var xr = r[i]
                if (xr - xr != 0f) xr = 0f
                val yr = (b0 * xr + b1 * x1R + b2 * x2R - a1 * y1R - a2 * y2R).toFloat()
                r[i] = yr
                x2R = x1R
                x1R = xr.toDouble()
                y2R = y1R
                y1R = yr.toDouble()
            }
        }
        // Blink flushes denormals from the state at the end of every quantum, so that they do not slow the loop down.
        x1l = flush(x1L)
        x2l = flush(x2L)
        y1l = flush(y1L)
        y2l = flush(y2L)
        x1r = flush(x1R)
        x2r = flush(x2R)
        y1r = flush(y1R)
        y2r = flush(y2R)
        ctx.release()
        ctx.release()
        ctx.release()
    }

    override fun reset() {
        frequency.reset(350.0)
        q.reset(1.0)
        detune.reset(0.0)
        clearState()
    }

    override fun registerParams(reg: NodeRegistry) {
        reg.register("phaser", "frequency", frequency)
        reg.register("phaser", "Q", q)
        reg.register("phaser", "detune", detune)
        reg.register("phaser_lfo", "frequency", lfo.frequencyParam)
        reg.register("phaser_lfo", "depth", lfo.depthParam)
        reg.register("phaser_lfo", "skew", lfo.skewParam)
        reg.register("phaser_lfo", "curve", lfo.curveParam)
        reg.register("phaser_lfo", "dcoffset", lfo.dcoffsetParam)
    }

    private fun clearState() {
        x1l = 0.0
        x2l = 0.0
        y1l = 0.0
        y2l = 0.0
        x1r = 0.0
        x2r = 0.0
        y1r = 0.0
        y2r = 0.0
        lastFrequency = Float.NaN
        lastQ = Float.NaN
        lastDetune = Float.NaN
    }

    /**
     * `NormalizeFrequency` and `Biquad::SetNotchParams` (+ `SetNormalizedCoefficients`): the coefficients of the notch for the AudioParam values of one
     * frame (all float32).
     */
    private fun setCoefficients(frequencyHz: Float, qValue: Float, detuneCents: Float) {
        var normalized = frequencyHz / nyquist
        if (detuneCents != 0f) normalized *= Math.pow(2.0, (detuneCents / 1200f).toDouble()).toFloat()
        // Clip the frequency to between 0 and 1, inclusive; do not let Q go negative.
        val frequency = if (normalized >= 1.0) 1.0 else if (normalized <= 0.0) 0.0 else normalized
        val quality = if (qValue.toDouble() > 0.0) qValue.toDouble() else 0.0
        if (frequency > 0.0 && frequency < 1.0) {
            if (quality > 0.0) {
                val w0 = Math.PI * frequency
                val alpha = Math.sin(w0) / (2.0 * quality)
                val k = Math.cos(w0)
                val inverse = 1.0 / (1.0 + alpha)
                b0 = 1.0 * inverse
                b1 = -2.0 * k * inverse
                b2 = 1.0 * inverse
                a1 = -2.0 * k * inverse
                a2 = (1.0 - alpha) * inverse
            } else {
                // The limit as Q approaches 0 is 0: nothing gets through.
                b0 = 0.0
                b1 = 0.0
                b2 = 0.0
                a1 = 0.0
                a2 = 0.0
            }
        } else {
            // At 0 and at the Nyquist frequency the notch is the identity.
            b0 = 1.0
            b1 = 0.0
            b2 = 0.0
            a1 = 0.0
            a2 = 0.0
        }
    }

    private fun f32(x: Double): Double = x.toFloat().toDouble()

    private fun flush(x: Double): Double = if ((if (x >= 0.0) x else -x) < java.lang.Float.MIN_NORMAL) 0.0 else x

    private companion object {
        const val DEFAULT_DEPTH = 0.75
        const val DEFAULT_CENTER = 1000.0
        const val DEFAULT_SWEEP = 2000.0

        /** "for backward compat in #1800": superdough adds this to the center frequency. */
        const val FREQUENCY_OFFSET = 282.0
    }
}
