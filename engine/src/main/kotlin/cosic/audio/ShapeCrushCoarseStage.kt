package cosic.audio

import cosic.audio.dsp.CoarseProcessor
import cosic.audio.dsp.ShapeCrushCoarse

private const val MAX_PARAM = 3.4e38

/**
 * Stage 9, the `coarse` sample-and-hold (`coarse-processor`, worklets.mjs:193-223; superdough.mjs:797-801): in the chain when `coarse` is set.
 * The hold restarts at every 128-frame render quantum and counts from the absolute frame counter ([CoarseProcessor]); `coarse` is the AudioParam of
 * the node (`coarse.coarse`, which modulators can target), read at the start of each quantum.
 */
internal class CoarseStage(@Suppress("UNUSED_PARAMETER") sampleRate: Int) : ChainStage {
    /** The `coarse` AudioParam of the `coarse-processor` node. */
    val coarse = AParam(1.0, -MAX_PARAM, MAX_PARAM, 2)
    private val processor = CoarseProcessor()

    override fun configure(p: ParamSet, timing: VoiceTiming, isMain: Boolean): Boolean {
        if (!p.has(Param.coarse)) return false
        coarse.reset(p[Param.coarse])
        processor.reset()
        return true
    }

    override fun process(b: AudioBlock, ctx: BlockContext) {
        val right = if (b.channels == 2) b.r else null
        if (coarse.isConstant) {
            processor.process(b.l, right, 0, ctx.n, StageQuanta.valueAt(coarse, ctx, 0), ctx.startFrame)
            return
        }
        StageQuanta.forEachPiece(ctx) { from, to, quantum ->
            processor.process(b.l, right, from, to, StageQuanta.valueAt(coarse, ctx, quantum), ctx.startFrame)
        }
    }

    override fun reset() {
        coarse.reset(1.0)
        processor.reset()
    }

    override fun registerParams(reg: NodeRegistry) {
        reg.register("coarse", "coarse", coarse)
    }
}

/**
 * Stage 10, the bit crusher (`crush-processor`, worklets.mjs:225-257; superdough.mjs:802-806): `round(x * 2^(crush - 1)) / 2^(crush - 1)` with `crush`
 * at least 1 and JavaScript's half-up rounding. In the chain when `crush` is set; `crush.crush` is its AudioParam. A crush so large that the step count
 * overflows gives NaN in the worklet; here those samples are silence.
 */
internal class CrushStage(@Suppress("UNUSED_PARAMETER") sampleRate: Int) : ChainStage {
    /** The `crush` AudioParam of the `crush-processor` node (descriptor default 0, which acts as 1). */
    val crush = AParam(0.0, -MAX_PARAM, MAX_PARAM, 2)

    override fun configure(p: ParamSet, timing: VoiceTiming, isMain: Boolean): Boolean {
        if (!p.has(Param.crush)) return false
        crush.reset(p[Param.crush])
        return true
    }

    override fun process(b: AudioBlock, ctx: BlockContext) {
        val right = if (b.channels == 2) b.r else null
        StageQuanta.forEachPiece(ctx) { from, to, quantum ->
            ShapeCrushCoarse.crush(b.l, right, from, to, StageQuanta.valueAt(crush, ctx, quantum))
            StageQuanta.finiteOrZero(b.l, from, to)
            if (right != null) StageQuanta.finiteOrZero(right, from, to)
        }
    }

    override fun reset() {
        crush.reset(0.0)
    }

    override fun registerParams(reg: NodeRegistry) {
        reg.register("crush", "crush", crush)
    }
}

/**
 * Stage 11, the waveshaper (`shape-processor`, worklets.mjs:259-295; superdough.mjs:807-811): a soft clipper `(1 + k) x / (1 + k |x|)` with
 * `k = 2s / (1 - s)`, then `shapevol` (default 1, clamped to 0.001..1). In the chain when `shape` is set; its AudioParams are `shape.shape` and
 * `shape.postgain`. A negative `shape` below -1 has poles inside the signal range and gives infinities, which become silence here.
 */
internal class ShapeStage(@Suppress("UNUSED_PARAMETER") sampleRate: Int) : ChainStage {
    val shape = AParam(0.0, -MAX_PARAM, MAX_PARAM, 2)
    val postgain = AParam(1.0, -MAX_PARAM, MAX_PARAM, 2)

    override fun configure(p: ParamSet, timing: VoiceTiming, isMain: Boolean): Boolean {
        if (!p.has(Param.shape)) return false
        shape.reset(p[Param.shape])
        postgain.reset(p.or(Param.shapevol, 1.0))
        return true
    }

    override fun process(b: AudioBlock, ctx: BlockContext) {
        val right = if (b.channels == 2) b.r else null
        StageQuanta.forEachPiece(ctx) { from, to, quantum ->
            ShapeCrushCoarse.shape(b.l, right, from, to, StageQuanta.valueAt(shape, ctx, quantum), StageQuanta.valueAt(postgain, ctx, quantum))
            StageQuanta.finiteOrZero(b.l, from, to)
            if (right != null) StageQuanta.finiteOrZero(right, from, to)
        }
    }

    override fun reset() {
        shape.reset(0.0)
        postgain.reset(1.0)
    }

    override fun registerParams(reg: NodeRegistry) {
        reg.register("shape", "shape", shape)
        reg.register("shape", "postgain", postgain)
    }
}
