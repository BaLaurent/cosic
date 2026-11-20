package cosic.audio

/**
 * The controls one of the three filter stages reads, under their canonical names (`lpMap`, `hpMap` and `bpMap` of superdough.mjs:683-773).
 * [shapeKey] is the name of the LFO shape control, which may hold text.
 *
 * Stub of Task 3.1: Task 3.7 (filters) owns this file and may reshape it, keeping [LOWPASS], [HIGHPASS] and [BANDPASS].
 */
internal class FilterKeys(
    val frequency: Param, val q: Param,
    val attack: Param, val decay: Param, val sustain: Param, val release: Param, val env: Param,
    val rate: Param, val sync: Param, val depth: Param, val depthFrequency: Param,
    val shape: Param, val shapeKey: String, val dcoffset: Param, val skew: Param,
) {
    companion object {
        val LOWPASS = FilterKeys(
            Param.cutoff, Param.resonance, Param.lpattack, Param.lpdecay, Param.lpsustain, Param.lprelease, Param.lpenv,
            Param.lprate, Param.lpsync, Param.lpdepth, Param.lpdepthfrequency, Param.lpshape, "lpshape", Param.lpdc, Param.lpskew,
        )
        val HIGHPASS = FilterKeys(
            Param.hcutoff, Param.hresonance, Param.hpattack, Param.hpdecay, Param.hpsustain, Param.hprelease, Param.hpenv,
            Param.hprate, Param.hpsync, Param.hpdepth, Param.hpdepthfrequency, Param.hpshape, "hpshape", Param.hpdc, Param.hpskew,
        )
        val BANDPASS = FilterKeys(
            Param.bandf, Param.bandq, Param.bpattack, Param.bpdecay, Param.bpsustain, Param.bprelease, Param.bpenv,
            Param.bprate, Param.bpsync, Param.bpdepth, Param.bpdepthfrequency, Param.bpshape, "bpshape", Param.bpdc, Param.bpskew,
        )
    }
}

/**
 * Stages 5 to 7: the low-pass, high-pass and band-pass filter of a chain, each a Web Audio biquad (or two for `ftype "24db"`, or the ladder
 * filter for `"ladder"`) with an optional envelope and LFO on its frequency.
 *
 * Stub of Task 3.1: never in the chain. Task 3.7 implements it; the parameters are the ones modulators target (`lpf.frequency`, `lpf.Q`).
 */
internal class FilterStage(sampleRate: Int, val type: BiquadType, val keys: FilterKeys) : ChainStage {
    val frequency = AParam(0.0, 0.0, sampleRate / 2.0)
    val detune = AParam(0.0, -153600.0, 153600.0)
    val q = AParam(1.0, -3.4e38, 3.4e38)

    override fun configure(p: ParamSet, timing: VoiceTiming, isMain: Boolean): Boolean = false

    override fun process(b: AudioBlock, ctx: BlockContext) {}

    override fun reset() {}
}
