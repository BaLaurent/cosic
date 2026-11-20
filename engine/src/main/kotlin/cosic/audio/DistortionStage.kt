package cosic.audio

import cosic.audio.dsp.DistortProcessor
import cosic.audio.dsp.Distortion

private const val MAX_PARAM = 3.4e38

/**
 * Stage 12, `distort` with its nine algorithms (`distort-processor`, worklets.mjs:429-463; curves helpers.mjs:509-599; superdough.mjs:812-816): in the
 * chain when `distort` is set. The output is `postgain * curve(x, expm1(distort))` with `postgain = distortvol` (default 1, clamped to 0.001..1 by the
 * worklet) and the curve picked by `distorttype`: a name (`scurve`, `soft`, `hard`, `cubic`, `diode`, `asym`, `fold`, `sinefold`, `chebyshev`; an
 * unknown name is the first curve) or a number that wraps around the nine curves (default 0).
 *
 * Both AudioParams (`distort.distort`, `distort.postgain`) are read per sample by the worklet, so a moving one is applied per frame; constant ones
 * take the fast path. Infinities and NaN that a huge `distort` makes (`expm1(1000)` is infinite) are silence here.
 */
internal class DistortStage(@Suppress("UNUSED_PARAMETER") sampleRate: Int) : ChainStage {
    val distort = AParam(0.0, -MAX_PARAM, MAX_PARAM, 2)
    val postgain = AParam(1.0, -MAX_PARAM, MAX_PARAM, 2)
    private val processor = DistortProcessor()

    override fun configure(p: ParamSet, timing: VoiceTiming, isMain: Boolean): Boolean {
        if (!p.has(Param.distort)) return false
        distort.reset(p[Param.distort])
        postgain.reset(p.or(Param.distortvol, 1.0))
        processor.algorithmIndex = algorithmOf(p)
        return true
    }

    override fun process(b: AudioBlock, ctx: BlockContext) {
        val n = ctx.n
        val right = if (b.channels == 2) b.r else null
        if (distort.isConstant && postgain.isConstant) {
            processor.process(b.l, right, 0, n, distort.constantValue.toFloat().toDouble(), postgain.constantValue.toFloat().toDouble())
        } else {
            val d = ctx.borrow()
            val g = ctx.borrow()
            distort.fill(d, ctx)
            postgain.fill(g, ctx)
            processor.processModulated(b.l, right, 0, n, d, g)
            ctx.release()
            ctx.release()
        }
        StageQuanta.finiteOrZero(b.l, 0, n)
        if (right != null) StageQuanta.finiteOrZero(right, 0, n)
    }

    override fun reset() {
        distort.reset(0.0)
        postgain.reset(1.0)
    }

    override fun registerParams(reg: NodeRegistry) {
        reg.register("distort", "distort", distort)
        reg.register("distort", "postgain", postgain)
    }

    /**
     * The curve index of `distorttype` (`getDistortionAlgorithm`, helpers.mjs:586-599): a name by its position, a number wrapped around the nine curves
     * (negative and fractional numbers, where JavaScript would break, wrap with a positive modulo of the truncated value), default 0. Written out for
     * the number because [Distortion.algorithmIndex] takes a boxed value and a voice start must not allocate.
     */
    private fun algorithmOf(p: ParamSet): Int {
        val name = p.str("distorttype")
        if (name != null) return Distortion.algorithmIndex(name)
        val d = p[Param.distorttype]
        if (d - d != 0.0) return 0
        return Math.floorMod(d.toLong(), Distortion.names.size.toLong()).toInt()
    }
}
