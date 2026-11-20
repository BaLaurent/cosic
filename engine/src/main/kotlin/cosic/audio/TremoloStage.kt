package cosic.audio

private const val MAX_PARAM = 3.4e38

/**
 * Stage 13, tremolo: a gain whose value follows an LFO (superdough.mjs:818-849). In the chain when `tremolo` (Hz) or `tremolosync` (cycles, so the
 * rate is `cps * tremolosync`, and it wins) is set.
 *
 * The graph is a `GainNode` with the base gain `max(1 - tremolodepth, 0)` (`tremolodepth` defaults to 1) and an `lfo-processor` connected to its
 * `gain` AudioParam, so the gain at a frame is `max(1 - depth, 0) + lfo`, with the LFO `clamp(((wave(phase, skew) + 0) * depth) ^ 1.5, 0, 1)` (a [LfoOsc]):
 * shape `tremoloshape` (a name or an index, default the triangle), skew `tremoloskew` (default 0.5 when a shape is given, 1 when not: the default tremolo
 * is a rising ramp, not a triangle), phase offset `tremolophase`, start phase `cycle / cps * rate`, running from the voice start to the end of its
 * release. The LFO is silent until the first render quantum that starts after the onset, so a depth-1 tremolo is mute for the 1 to 128 frames
 * the onset's quantum has left.
 *
 * Modulators reach it through `tremolo.frequency/skew/depth/curve/dcoffset` (the LFO node) and `tremolo_gain.gain`.
 */
internal class TremoloStage(sampleRate: Int) : ChainStage {
    private val lfo = LfoOsc(sampleRate)

    /** The `gain` AudioParam of the tremolo's `GainNode`: the base gain, with the LFO connected to it. */
    val gain = AParam(1.0, -MAX_PARAM, MAX_PARAM, 2)

    override fun configure(p: ParamSet, timing: VoiceTiming, isMain: Boolean): Boolean {
        val sync = p[Param.tremolosync]
        val rate = if (!sync.isNaN()) timing.cps * sync else p[Param.tremolo]
        if (rate.isNaN()) return false
        val depth = p.or(Param.tremolodepth, 1.0)
        val shapeName = p.str("tremoloshape")
        val shapeNumber = p[Param.tremoloshape]
        val hasShape = shapeName != null || !shapeNumber.isNaN()
        val shape = if (!shapeNumber.isNaN()) shapeIndexOfNumber(shapeNumber) else lfoShapeIndex(shapeName)
        gain.reset(Math.max(1.0 - depth, 0.0))
        // The worklet's parameters are AudioParams, float32 (an onset on a quantum boundary is "after begin" or not depending on how that rounds).
        lfo.start(
            shape = shape,
            skew = f32(p.or(Param.tremoloskew, if (hasShape) 0.5 else 1.0)),
            depth = f32(depth),
            dcoffset = 0.0,
            frequency = null,
            freqHz = f32(rate),
            time = f32(timing.cycle / timing.cps),
            phaseoffset = f32(p.or(Param.tremolophase, 0.0)),
            curve = 1.5,
            min = 0.0,
            max = 1.0,
            begin = f32(timing.t),
            end = f32(timing.endWithRelease),
        )
        gain.addInput(lfo)
        return true
    }

    override fun process(b: AudioBlock, ctx: BlockContext) {
        val gains = ctx.borrow()
        gain.fill(gains, ctx)
        b.mulBy(gains, ctx.n)
        ctx.release()
    }

    override fun reset() {
        gain.reset(1.0)
    }

    override fun registerParams(reg: NodeRegistry) {
        reg.register("tremolo", "frequency", lfo.frequencyParam)
        reg.register("tremolo", "skew", lfo.skewParam)
        reg.register("tremolo", "depth", lfo.depthParam)
        reg.register("tremolo", "curve", lfo.curveParam)
        reg.register("tremolo", "dcoffset", lfo.dcoffsetParam)
        reg.register("tremolo_gain", "gain", gain)
    }

    private fun f32(x: Double): Double = x.toFloat().toDouble()

    /** [lfoShapeIndex] of a number (`getModulationShapeInput`: taken modulo 5; -1 for a value that is no index, which stops the LFO), without boxing it. */
    private fun shapeIndexOfNumber(v: Double): Int {
        val m = v % 5.0
        return if (m >= 0.0 && m == Math.floor(m)) m.toInt() else -1
    }
}
