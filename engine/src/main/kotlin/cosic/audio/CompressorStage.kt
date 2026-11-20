package cosic.audio

import cosic.audio.dsp.DynamicsCompressor

/**
 * Stage 14, the `DynamicsCompressorNode` (`getCompressor`, helpers.mjs:163-176; superdough.mjs:851-862): in the chain when `compressor` (the threshold,
 * dB) is set, with `compressorRatio` (default 10), `compressorKnee` (10 dB), `compressorAttack` (0.005 s) and `compressorRelease` (0.05 s).
 * The AudioParams are clamped to the node's ranges (threshold -100..0, knee 0..40, ratio 1..20, attack and release 0..1) and read once per render
 * quantum ([DynamicsCompressor] is Chromium's own DSP, ported). Modulators reach them as `compressor.threshold/ratio/knee/attack/release`.
 *
 * The node adds a latency of 6 ms (a look-ahead) and always outputs two channels: a mono voice becomes stereo with L = R (so a `pan` after it uses the
 * stereo-input law and does not lose 3 dB in the middle).
 *
 * The detector of a node that was just created starts at zero and takes some 20 ms to catch up, which dips the start of every voice. superdough pools its
 * nodes, so a note normally gets one that is already settled; the stage starts every voice in that settled state ([DynamicsCompressor.reset] with `warm`).
 * [warmStart] false starts it as a node created for the voice (what the tests compare with a fresh node of the browser).
 */
internal class CompressorStage(sampleRate: Int, private val warmStart: Boolean = true) : ChainStage {
    /** `threshold`, in dB. */
    val threshold = AParam(DEFAULT_THRESHOLD, -100.0, 0.0, 2)
    val knee = AParam(DEFAULT_KNEE, 0.0, 40.0, 2)
    val ratio = AParam(DEFAULT_RATIO, 1.0, 20.0, 2)

    /** Seconds. */
    val attack = AParam(DEFAULT_ATTACK, 0.0, 1.0, 2)
    val release = AParam(DEFAULT_RELEASE, 0.0, 1.0, 2)
    private val kernel = DynamicsCompressor(sampleRate)

    override fun configure(p: ParamSet, timing: VoiceTiming, isMain: Boolean): Boolean {
        if (!p.has(Param.compressor)) return false
        threshold.reset(p[Param.compressor])
        ratio.reset(p.or(Param.compressorRatio, DEFAULT_RATIO))
        knee.reset(p.or(Param.compressorKnee, DEFAULT_KNEE))
        attack.reset(p.or(Param.compressorAttack, DEFAULT_ATTACK))
        release.reset(p.or(Param.compressorRelease, DEFAULT_RELEASE))
        kernel.reset(warmStart)
        return true
    }

    override fun process(b: AudioBlock, ctx: BlockContext) {
        val stereo = b.channels == 2
        StageQuanta.forEachPiece(ctx) { from, to, quantum ->
            kernel.setParameters(
                StageQuanta.valueAt(threshold, ctx, quantum),
                StageQuanta.valueAt(knee, ctx, quantum),
                StageQuanta.valueAt(ratio, ctx, quantum),
                StageQuanta.valueAt(attack, ctx, quantum),
                StageQuanta.valueAt(release, ctx, quantum),
            )
            kernel.process(b.l, b.r, from, to, ctx.startFrame, stereo)
        }
        b.channels = 2
    }

    override fun reset() {
        threshold.reset(DEFAULT_THRESHOLD)
        ratio.reset(DEFAULT_RATIO)
        knee.reset(DEFAULT_KNEE)
        attack.reset(DEFAULT_ATTACK)
        release.reset(DEFAULT_RELEASE)
    }

    override fun registerParams(reg: NodeRegistry) {
        reg.register("compressor", "threshold", threshold)
        reg.register("compressor", "ratio", ratio)
        reg.register("compressor", "knee", knee)
        reg.register("compressor", "attack", attack)
        reg.register("compressor", "release", release)
    }

    private companion object {
        // getCompressor's defaults for what the pattern leaves out (helpers.mjs:166-170).
        const val DEFAULT_THRESHOLD = -3.0
        const val DEFAULT_RATIO = 10.0
        const val DEFAULT_KNEE = 10.0
        const val DEFAULT_ATTACK = 0.005
        const val DEFAULT_RELEASE = 0.05
    }
}
