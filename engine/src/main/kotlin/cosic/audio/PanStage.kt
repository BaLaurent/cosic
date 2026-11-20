package cosic.audio

/**
 * Stage 15, the `StereoPannerNode` (superdough.mjs:864-870), present only when `pan` is set; without it a mono voice reaches both channels
 * at gain 1. The node's value is `2 * pan - 1`, clamped to `[-1, 1]`, and the law is Chromium's (stereo_panner.cc):
 * - mono input: `theta = (p * 0.5 + 0.5) * pi / 2`, `L = x cos(theta)`, `R = x sin(theta)` (a centred voice is x 0.7071 on each side);
 * - stereo input, `p <= 0`: `theta = (p + 1) * pi / 2`, `L = inL + inR cos(theta)`, `R = inR sin(theta)`;
 * - stereo input, `p > 0`: `theta = p * pi / 2`, `L = inL cos(theta)`, `R = inR + inL sin(theta)`.
 *
 * The output is always stereo. The law is evaluated per frame from the parameter, which is a-rate.
 */
internal class PanStage : ChainStage {
    val pan = AParam(0.0, -1.0, 1.0)

    override fun configure(p: ParamSet, timing: VoiceTiming, isMain: Boolean): Boolean {
        if (!p.has(Param.pan)) return false
        pan.reset(2.0 * p[Param.pan] - 1.0)
        return true
    }

    override fun process(b: AudioBlock, ctx: BlockContext) {
        val n = ctx.n
        val mono = b.channels == 1
        if (pan.isConstant) {
            val p = pan.constantValue
            if (mono) {
                val theta = (p * 0.5 + 0.5) * HALF_PI
                val gl = Math.cos(theta).toFloat()
                val gr = Math.sin(theta).toFloat()
                for (i in 0 until n) {
                    val x = b.l[i]
                    b.l[i] = x * gl
                    b.r[i] = x * gr
                }
            } else if (p <= 0.0) {
                val theta = (p + 1.0) * HALF_PI
                val c = Math.cos(theta).toFloat()
                val s = Math.sin(theta).toFloat()
                for (i in 0 until n) {
                    val inR = b.r[i]
                    b.l[i] += inR * c
                    b.r[i] = inR * s
                }
            } else {
                val theta = p * HALF_PI
                val c = Math.cos(theta).toFloat()
                val s = Math.sin(theta).toFloat()
                for (i in 0 until n) {
                    val inL = b.l[i]
                    b.l[i] = inL * c
                    b.r[i] += inL * s
                }
            }
        } else {
            val values = ctx.borrow()
            pan.fill(values, ctx)
            for (i in 0 until n) {
                val p = values[i].toDouble()
                if (mono) {
                    val theta = (p * 0.5 + 0.5) * HALF_PI
                    val x = b.l[i]
                    b.l[i] = x * Math.cos(theta).toFloat()
                    b.r[i] = x * Math.sin(theta).toFloat()
                } else if (p <= 0.0) {
                    val theta = (p + 1.0) * HALF_PI
                    val inR = b.r[i]
                    b.l[i] += inR * Math.cos(theta).toFloat()
                    b.r[i] = inR * Math.sin(theta).toFloat()
                } else {
                    val theta = p * HALF_PI
                    val inL = b.l[i]
                    b.l[i] = inL * Math.cos(theta).toFloat()
                    b.r[i] += inL * Math.sin(theta).toFloat()
                }
            }
            ctx.release()
        }
        b.channels = 2
    }

    override fun reset() {
        pan.reset(0.0)
    }

    override fun registerParams(reg: NodeRegistry) {
        reg.register("pan", "pan", pan)
    }

    private companion object {
        const val HALF_PI = Math.PI / 2
    }
}
