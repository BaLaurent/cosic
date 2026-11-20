package cosic.audio

import cosic.pattern.jsToInt32

/*
 * The `lfo-processor` AudioWorklet of superdough (packages/superdough/worklets.mjs:72-191) in two layers: [LfoOscillator] is its
 * per-sample core, [LfoOsc] the node, with the begin/end gating and the render-quantum behaviour of a worklet.
 */

private const val TRI = 0
private const val SINE = 1
private const val RAMP = 2
private const val SAW = 3
private const val SQUARE = 4

private const val MAX_PARAM = 3.4e38

/**
 * The index of an LFO shape (`getModulationShapeInput`, helpers.mjs:116-121): 0 triangle, 1 sine, 2 ramp, 3 saw, 4 square. A number
 * is taken modulo 5 (`7` is the ramp); a name is looked up (`tri` and `triangle` are 0), and anything else is 0. A number that
 * leaves no valid index (`-1`, `1.5`) gives -1, a shape that kills the worklet when it runs.
 */
internal fun lfoShapeIndex(v: Any?): Int = when (v) {
    is Number -> {
        val m = v.toDouble() % 5.0
        if (m >= 0.0 && m == Math.floor(m)) m.toInt() else -1
    }
    is String -> when (v) {
        "tri", "triangle" -> TRI
        "sine" -> SINE
        "ramp" -> RAMP
        "saw" -> SAW
        "square" -> SQUARE
        else -> 0
    }
    else -> 0
}

/** [lfoShapeIndex] of a control that was either a number or a name; the number wins. */
internal fun lfoShapeIndex(number: Double?, name: String?): Int = if (number != null) lfoShapeIndex(number) else lfoShapeIndex(name)

/** The LFO waveforms, all in `[0, 1]` for a phase in `[0, 1]` (worklets.mjs:72-95). [skew] moves the peak of the triangle and the edge of the square. */
internal fun lfoWave(shape: Int, phase: Double, skew: Double): Double = when (shape) {
    TRI -> {
        val x = 1.0 - skew
        if (phase >= skew) 1.0 / x - phase / x else phase / skew
    }
    SINE -> Math.sin(2.0 * Math.PI * phase) * 0.5 + 0.5
    RAMP -> phase
    SAW -> 1.0 - phase
    SQUARE -> if (phase >= skew) 0.0 else 1.0
    else -> 0.0
}

/** The default lower bound of an LFO output, `dcoffset * depth` (`getLfo`, helpers.mjs:127-161). */
internal fun lfoDefaultMin(dcoffset: Double, depth: Double): Double = dcoffset * depth

/** The default upper bound of an LFO output, `dcoffset * depth + depth`. */
internal fun lfoDefaultMax(dcoffset: Double, depth: Double): Double = dcoffset * depth + depth

/**
 * The oscillator of the LFO worklet, one sample per [next]: `clamp(((wave(phase, skew) + dcoffset) * depth) ^ curve, min, max)`, then the
 * phase advances by `frequency / sampleRate` and wraps at 1. The fields may be changed between samples, which is what
 * [LfoOsc] does once per render quantum. [min] and [max] default to the bounds [lfoDefaultMin] and [lfoDefaultMax] of the
 * initial [dcoffset] and [depth], as `getLfo` computes them once.
 */
internal class LfoOscillator(
    sampleRate: Int,
    var shape: Int = 0,
    var frequency: Double = 1.0,
    var skew: Double = 0.5,
    var depth: Double = 1.0,
    var dcoffset: Double = -0.5,
    var phaseoffset: Double = 0.0,
    var curve: Double = 1.0,
    min: Double? = null,
    max: Double? = null,
) {
    private val invSr = 1.0 / sampleRate

    var min: Double = min ?: lfoDefaultMin(dcoffset, depth)
    var max: Double = max ?: lfoDefaultMax(dcoffset, depth)
    private var phase = 0.0

    /** Restarts the phase as the worklet does on its first block: `ffrac(time * frequency + phaseoffset)` (a truncating fraction). */
    fun reset(timeSec: Double) {
        val x = timeSec * frequency + phaseoffset
        phase = x - jsToInt32(x)
    }

    /** The next sample. May be NaN (a negative base with a fractional [curve]); the caller decides what that means. */
    fun next(): Double {
        var m = (lfoWave(shape, phase, skew) + dcoffset) * depth
        if (curve != 1.0) m = Math.pow(m, curve)
        val out = clamp(m, min, max)
        phase += frequency * invSr
        if (phase > 1.0) phase -= 1.0
        return out
    }
}

/**
 * An LFO connected to parameters: the `lfo-processor` node. It is silent until a render quantum starts after [start]'s `begin`, runs
 * until the quantum that starts at or after `end` (then it is gone for good), and reads its parameters once per quantum, at the
 * quantum's first frame, as the worklet reads `parameters[name][0]`. Quanta are the absolute 128-frame blocks of the engine, so
 * the output does not depend on how rendering is split into blocks. The output is the raw LFO signal; whoever connects it
 * ([AParam.addInput]) decides what it modulates.
 *
 * [frequencyParam], [skewParam], [depthParam], [curveParam] and [dcoffsetParam] are the parameters of the node that other modulators may
 * target (`lfo_<id>` of the control targets).
 */
internal class LfoOsc(sampleRate: Int) : BlockModulator() {
    val frequencyParam = AParam(1.0, -MAX_PARAM, MAX_PARAM, 4)
    val skewParam = AParam(0.5, -MAX_PARAM, MAX_PARAM, 4)
    val depthParam = AParam(1.0, -MAX_PARAM, MAX_PARAM, 4)
    val curveParam = AParam(1.0, -MAX_PARAM, MAX_PARAM, 4)
    val dcoffsetParam = AParam(-0.5, -MAX_PARAM, MAX_PARAM, 4)

    private val osc = LfoOscillator(sampleRate)
    private var frequencySource: AParam? = null
    private var time = 0.0
    private var begin = 0.0
    private var end = 0.0
    private var phaseSet = false
    private var dead = false
    private var active = false
    private var quantum = Long.MIN_VALUE

    /**
     * Starts the LFO for one voice. [shape] is a [lfoShapeIndex]; [time] seeds the first phase (`time * frequency + phaseoffset`; the voice's
     * `cycle / cps`, or the voice start); [min] and [max] bound the output (see [lfoDefaultMin]). The frequency is [freqHz], or follows
     * [frequency] when that is given. [begin] and [end] are absolute seconds.
     */
    fun start(
        shape: Int, skew: Double, depth: Double, dcoffset: Double, frequency: AParam?, freqHz: Double, time: Double,
        phaseoffset: Double, curve: Double, min: Double, max: Double, begin: Double, end: Double,
    ) {
        osc.shape = shape
        osc.phaseoffset = phaseoffset
        osc.min = min
        osc.max = max
        frequencySource = frequency
        frequencyParam.reset(freqHz)
        skewParam.reset(skew)
        depthParam.reset(depth)
        curveParam.reset(curve)
        dcoffsetParam.reset(dcoffset)
        this.time = time
        this.begin = begin
        this.end = end
        phaseSet = false
        dead = shape < 0
        active = false
        quantum = Long.MIN_VALUE
        invalidate()
    }

    override fun render(ctx: BlockContext) {
        val n = ctx.n
        var i = 0
        while (i < n) {
            val frame = ctx.startFrame + i
            val q = Math.floorDiv(frame, QUANTUM.toLong())
            val stop = Math.min(n.toLong(), (q + 1) * QUANTUM - ctx.startFrame).toInt()
            if (q != quantum) enter(q, ctx)
            if (active) {
                for (k in i until stop) {
                    val v = osc.next()
                    buf[k] = if (v == v) v.toFloat() else 0f
                }
            } else {
                java.util.Arrays.fill(buf, i, stop, 0f)
            }
            i = stop
        }
    }

    /** The start of a render quantum: the begin/end gating of `process` and the reading of the parameters. */
    private fun enter(q: Long, ctx: BlockContext) {
        quantum = q
        active = false
        if (dead) return
        val currentTime = ctx.quantumTime(q)
        if (currentTime >= end) {
            dead = true
            return
        }
        if (currentTime <= begin) return
        val index = Math.max(0L, q * QUANTUM - ctx.startFrame).toInt()
        osc.frequency = (frequencySource ?: frequencyParam).sampleAt(ctx, index)
        osc.skew = skewParam.sampleAt(ctx, index)
        osc.depth = depthParam.sampleAt(ctx, index)
        osc.curve = curveParam.sampleAt(ctx, index)
        osc.dcoffset = dcoffsetParam.sampleAt(ctx, index)
        if (!phaseSet) {
            osc.reset(time)
            phaseSet = true
        }
        active = true
    }
}
