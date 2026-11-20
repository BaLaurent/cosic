package cosic.audio

import cosic.audio.dsp.TransientShaper

/**
 * Stage 3, the transient shaper (`transient-processor`, worklets.mjs:1417-1494; superdough.mjs:655-671): in the chain when `transient` is set.
 * `transient` raises (positive) or lowers (negative) the attacks of the signal and `transsustain` its sustains, both -1..1; the worklet is built with
 * `begin = onset` and `end = endWithRelease`, and is configured with its other defaults (3 ms attack and 80 ms sustain followers, sensitivity 0.1, mix 1).
 *
 * The worklet is silent until the first render quantum that starts after the onset, which mutes the 1 to 128 frames the onset's quantum has left,
 * and from the quantum that starts at the end it is gone for good; it soft-clips `y / (1 + |y|)` even with neither control moving the signal (a sustained 0.5
 * comes out as 0.333). See [TransientShaper].
 */
internal class TransientStage(sampleRate: Int) : ChainStage {
    private val shaper = TransientShaper(sampleRate)

    override fun configure(p: ParamSet, timing: VoiceTiming, isMain: Boolean): Boolean {
        if (!p.has(Param.transient)) return false
        shaper.configure(p[Param.transient], p[Param.transsustain], timing.t, timing.endWithRelease)
        return true
    }

    override fun process(b: AudioBlock, ctx: BlockContext) {
        shaper.process(b.l, if (b.channels == 2) b.r else null, 0, ctx.n, ctx.startFrame)
    }

    override fun reset() {
        shaper.reset()
    }
}
