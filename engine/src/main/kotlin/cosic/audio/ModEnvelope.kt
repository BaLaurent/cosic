package cosic.audio

/*
 * The `envelope-processor` AudioWorklet of superdough (packages/superdough/worklets.mjs:987-1091), the envelope that `.env(...)`
 * attaches to a parameter. Ported literally, quirks included: time is `currentTime`, the start of the render quantum, so the
 * value steps once per 128 frames; the decay is measured from the start of the envelope over attack plus decay, so the
 * value jumps where the attack ends; and the release state is never reached because the node stops at its `end`.
 */

private const val IDLE = 0
private const val ATTACK = 1
private const val DECAY = 2
private const val SUSTAIN = 3
private const val RELEASE = 4
private const val STATES = 5

private const val MAX_PARAM = 3.4e38

/** `_warp(phase, curvature, strength = 8)`: bends the straight line; positive curvature is snappier, negative calmer. */
private fun warp(phase: Double, curvature: Double): Double {
    if (phase == 0.0 || phase == 1.0 || curvature == 0.0) return phase // pow(phase, 1.0) is phase itself
    return if (curvature > 0.0) 1.0 - Math.pow(1.0 - phase, 1.0 + 8.0 * curvature) else Math.pow(phase, 1.0 - 8.0 * curvature)
}

/**
 * An envelope connected to parameters. Like [LfoOsc] it is silent until the quantum after `begin`, gone from the quantum at
 * `end` on, reads its parameters once per quantum, and keys everything on absolute quanta. Its output is the envelope times
 * `depth`, clamped to `[min, max]`. [attackParam], [decayParam], [sustainParam], [releaseParam] and [depthParam] are the parameters other
 * modulators may target (`env_attack` and friends). The worklet reads its parameters per sample; this reads them at the first
 * frame of the quantum, which differs only when a parameter itself moves within a quantum.
 */
internal class ModEnvelope(@Suppress("UNUSED_PARAMETER") sampleRate: Int) : BlockModulator() {
    val attackParam = AParam(0.005, 0.0, MAX_PARAM, 4)
    val decayParam = AParam(0.14, 0.0, MAX_PARAM, 4)
    val sustainParam = AParam(0.0, 0.0, 1.0, 4)
    val releaseParam = AParam(0.1, 0.0, MAX_PARAM, 4)
    val depthParam = AParam(1.0, -MAX_PARAM, MAX_PARAM, 4)

    private var attackCurve = 0.0
    private var decayCurve = 0.0
    private var releaseCurve = 0.0
    private var min = -1e9
    private var max = 1e9
    private val retrigger = 1.0
    private var begin = 0.0
    private var end = 0.0

    // The processor's own state.
    private var value = 0.0
    private var state = IDLE
    private var beginTime = Double.NaN
    private var endTime = 0.0
    private var attackStart = 0.0

    // The parameters as read at the start of the current quantum.
    private var attack = 0.0
    private var decay = 0.0
    private var sustain = 0.0
    private var release = 0.0
    private var depth = 1.0
    private var susTime = 0.0
    private var currentTime = 0.0

    private var dead = false
    private var active = false
    private var quantum = Long.MIN_VALUE

    /**
     * Starts the envelope for one voice: [a], [d], [s], [r] in seconds and levels, the three curves in `[-1, 1]`, the output
     * scaled by [depth] and clamped to [min]..[max]; [begin] and [end] are absolute seconds.
     */
    fun start(
        a: Double, d: Double, s: Double, r: Double, aCurve: Double, dCurve: Double, rCurve: Double,
        depth: Double, min: Double, max: Double, begin: Double, end: Double,
    ) {
        attackParam.reset(a)
        decayParam.reset(d)
        sustainParam.reset(s)
        releaseParam.reset(r)
        depthParam.reset(depth)
        attackCurve = Math.max(-1.0, Math.min(1.0, aCurve))
        decayCurve = Math.max(-1.0, Math.min(1.0, dCurve))
        releaseCurve = Math.max(-1.0, Math.min(1.0, rCurve))
        this.min = min
        this.max = max
        this.begin = begin
        this.end = end
        value = 0.0
        state = IDLE
        beginTime = Double.NaN
        endTime = 0.0
        attackStart = 0.0
        dead = false
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
                for (k in i until stop) buf[k] = sample()
            } else {
                java.util.Arrays.fill(buf, i, stop, 0f)
            }
            i = stop
        }
    }

    /** The start of a render quantum: gating, triggering and reading the parameters. */
    private fun enter(q: Long, ctx: BlockContext) {
        quantum = q
        active = false
        if (dead) return
        currentTime = ctx.quantumTime(q)
        if (currentTime >= end) {
            dead = true
            return
        }
        if (currentTime <= begin) return
        active = true
        // The retrigger parameter has no modulation target; it is the constant 1.
        if (begin != beginTime && (state == IDLE || retrigger >= 0.5)) {
            beginTime = begin
            state = ATTACK
            endTime = end
            attackStart = value
        }
        susTime = endTime - beginTime
        val index = Math.max(0L, q * QUANTUM - ctx.startFrame).toInt()
        attack = attackParam.sampleAt(ctx, index)
        decay = decayParam.sampleAt(ctx, index)
        sustain = sustainParam.sampleAt(ctx, index)
        release = releaseParam.sampleAt(ctx, index)
        depth = depthParam.sampleAt(ctx, index)
    }

    /** One iteration of the processor's sample loop. */
    private fun sample(): Float {
        advance()
        while (currentTime - beginTime >= timeOf(state)) state = (state + 1) % STATES
        val v = clamp(value * depth, min, max)
        return if (v == v) v.toFloat() else 0f
    }

    private fun timeOf(s: Int): Double = when (s) {
        ATTACK -> attack
        DECAY -> attack + decay
        SUSTAIN -> susTime
        RELEASE -> susTime + release
        else -> Double.POSITIVE_INFINITY
    }

    /** `_advance(start, target, time, curvature)` for the current state. */
    private fun advance() {
        val time = timeOf(state)
        val start: Double
        val target: Double
        val curvature: Double
        when (state) {
            ATTACK -> { start = attackStart; target = 1.0; curvature = attackCurve }
            DECAY -> { start = 1.0; target = sustain; curvature = decayCurve }
            SUSTAIN -> { start = sustain; target = sustain; curvature = 0.0 }
            RELEASE -> { start = sustain; target = 0.0; curvature = releaseCurve }
            else -> { start = 0.0; target = 0.0; curvature = 0.0 }
        }
        value = if (time == 0.0 || start == target) {
            target
        } else {
            val phase = Math.min(1.0, (currentTime - beginTime) / time)
            start + (target - start) * warp(phase, curvature)
        }
    }
}
