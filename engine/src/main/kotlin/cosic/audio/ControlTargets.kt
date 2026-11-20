package cosic.audio

/** The Web Audio node and the parameter on it that a control modulates. */
internal class ControlTarget(val node: String, val param: String)

/**
 * `CONTROL_TARGETS` (superdoughdata.mjs:8-146), copied literally: for each control name the node of an effect chain and the parameter of that node that
 * `.lfo`, `.env` and `.bmod` modulators connect to. The stages register their parameters under these node and parameter names
 * ([ChainStage.registerParams]); a control that is not in the table cannot be modulated. A test compares the table with the real file.
 */
internal object ControlTargets {
    private val table = HashMap<String, ControlTarget>(256)

    private fun target(control: String, node: String, param: String) {
        table[control] = ControlTarget(node, param)
    }

    init {
        target("stretch", "stretch", "pitchFactor")
        target("gain", "gain", "gain")
        target("postgain", "post", "gain")
        target("pan", "pan", "pan")
        target("tremolo", "tremolo", "frequency")
        target("tremolosync", "tremolo", "frequency")
        target("tremolodepth", "tremolo_gain", "gain")
        target("tremoloskew", "tremolo", "skew")
        target("tremolophase", "tremolo", "phase")
        target("tremoloshape", "tremolo", "shape")

        // MODULATORS
        target("lfo", "lfo", "frequency")
        target("lfo_rate", "lfo", "frequency")
        target("lfo_sync", "lfo", "frequency")
        target("lfo_depth", "lfo", "depth")
        target("lfo_depthabs", "lfo", "depth")
        target("lfo_skew", "lfo", "skew")
        target("lfo_curve", "lfo", "curve")
        target("lfo_dcoffset", "lfo", "dcoffset")
        target("env", "env", "depth")
        target("env_attack", "env", "attack")
        target("env_decay", "env", "decay")
        target("env_sustain", "env", "sustain")
        target("env_release", "env", "release")
        target("bmod", "bmod", "depth")
        target("bmod_depth", "bmod", "depth")
        target("bmod_depthabs", "bmod", "depth")

        // LPF
        target("cutoff", "lpf", "frequency")
        target("resonance", "lpf", "Q")
        target("lprate", "lpf_lfo", "rate")
        target("lpsync", "lpf_lfo", "sync")
        target("lpdepth", "lpf_lfo", "depth")
        target("lpdepthfrequency", "lpf_lfo", "depth")
        target("lpshape", "lpf_lfo", "shape")
        target("lpdc", "lpf_lfo", "dcoffset")
        target("lpskew", "lpf_lfo", "skew")

        // HPF
        target("hcutoff", "hpf", "frequency")
        target("hresonance", "hpf", "Q")
        target("hprate", "hpf_lfo", "rate")
        target("hpsync", "hpf_lfo", "sync")
        target("hpdepth", "hpf_lfo", "depth")
        target("hpdepthfrequency", "hpf_lfo", "depth")
        target("hpshape", "hpf_lfo", "shape")
        target("hpdc", "hpf_lfo", "dcoffset")
        target("hpskew", "hpf_lfo", "skew")

        // BPF
        target("bandf", "bpf", "frequency")
        target("bandq", "bpf", "Q")
        target("bprate", "bpf_lfo", "rate")
        target("bpsync", "bpf_lfo", "sync")
        target("bpdepth", "bpf_lfo", "depth")
        target("bpdepthfrequency", "bpf_lfo", "depth")
        target("bpshape", "bpf_lfo", "shape")
        target("bpdc", "bpf_lfo", "dcoffset")
        target("bpskew", "bpf_lfo", "skew")

        target("vowel", "vowel", "frequency")

        // DISTORTION
        target("coarse", "coarse", "coarse")
        target("crush", "crush", "crush")
        target("shape", "shape", "shape")
        target("shapevol", "shape", "postgain")
        target("distort", "distort", "distort")
        target("distortvol", "distort", "postgain")
        target("distorttype", "distort", "distort")

        // COMPRESSOR
        target("compressor", "compressor", "threshold")
        target("compressorRatio", "compressor", "ratio")
        target("compressorKnee", "compressor", "knee")
        target("compressorAttack", "compressor", "attack")
        target("compressorRelease", "compressor", "release")

        // PHASER
        target("phaserrate", "phaser_lfo", "frequency")
        target("phasersweep", "phaser_lfo", "depth")
        target("phasercenter", "phaser", "frequency")
        target("phaserdepth", "phaser", "Q")

        // ORBIT EFFECTS
        target("delay", "delay_mix", "gain")
        target("delaytime", "delay", "delayTime")
        target("delayfeedback", "delay", "feedback")
        target("delaysync", "delay", "delayTime")
        target("dry", "dry", "gain")
        target("room", "room_mix", "gain")
        target("djf", "djf", "value")
        target("busgain", "bus", "gain")

        // SYNTHS
        target("s", "source", "frequency")
        target("detune", "source", "freqspread")
        target("wt", "source", "position")
        target("warp", "source", "warp")
        target("freq", "source", "frequency")
        target("note", "source", "frequency")
        target("wtdc", "wt_lfo", "dc")
        target("wtskew", "wt_lfo", "skew")
        target("wtrate", "wt_lfo", "frequency")
        target("wtsync", "wt_lfo", "frequency")
        target("wtdepth", "wt_lfo", "depth")
        target("warpdc", "warp_lfo", "dc")
        target("warpskew", "warp_lfo", "skew")
        target("warprate", "warp_lfo", "frequency")
        target("warpsync", "warp_lfo", "frequency")
        target("warpdepth", "warp_lfo", "depth")
        target("fmi", "fm_1_gain", "gain")
        target("fmi2", "fm_2_gain", "gain")
        target("fmi3", "fm_3_gain", "gain")
        target("fmi4", "fm_4_gain", "gain")
        target("fmi5", "fm_5_gain", "gain")
        target("fmi6", "fm_6_gain", "gain")
        target("fmi7", "fm_7_gain", "gain")
        target("fmi8", "fm_8_gain", "gain")
        target("fmh", "fm_1", "frequency")
        target("fmh2", "fm_2", "frequency")
        target("fmh3", "fm_3", "frequency")
        target("fmh4", "fm_4", "frequency")
        target("fmh5", "fm_5", "frequency")
        target("fmh6", "fm_6", "frequency")
        target("fmh7", "fm_7", "frequency")
        target("fmh8", "fm_8", "frequency")
        target("pw", "source", "pulsewidth")
        target("pwrate", "pw_lfo", "frequency")
        target("pwsweep", "pw_lfo", "depth")
        target("vib", "vib", "frequency")
        target("vibmod", "vib_gain", "gain")
        target("byteBeatStartTime", "source", "byteBeatStartTime")
        target("spread", "source", "panspread")
        target("transient", "transient", "attack")
    }

    /** The number of controls in the table. */
    val size: Int get() = table.size

    /** The name of every control in the table, in no particular order (for tests). */
    val controls: Set<String> get() = table.keys

    /**
     * `getControlData(control, subControl)` (modulators.mjs:35-38): `table[<control up to its first underscore>_<subControl>] ?? table[<control up to
     * its first underscore>]`. The underscore is how a modulator names another one (`lfo_0`, `env_depth`).
     */
    fun lookup(control: String, subControl: String?): ControlTarget? {
        val underscore = control.indexOf('_')
        val base = if (underscore < 0) control else control.substring(0, underscore)
        if (subControl != null) {
            val specific = table["${base}_$subControl"]
            if (specific != null) return specific
        }
        return table[base]
    }
}
