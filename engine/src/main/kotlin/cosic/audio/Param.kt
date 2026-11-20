@file:Suppress("EnumEntryName")

package cosic.audio

/**
 * Every numeric control the audio engine reads, under its canonical Strudel name (`controls.mjs`): `lpf` is [cutoff], `size` is
 * [roomsize]. One entry per key; a [ParamSet] holds one number per entry, NaN meaning "not set".
 *
 * **Append-only.** A task that needs another numeric control adds a line at the end of the list (never in the middle, never a
 * reordering); when two tasks add lines at the same place, a merge keeps both. Before adding, check the list: the entries of the
 * sampler (`speed`, `begin`, `end`) and of the amplitude envelope (`attack`, `decay`,
 * `sustain`, `release`, `clip`, `loop`, `cut`) are already here. String and list controls are not entries; see [STRING_KEYS] and [LIST_KEYS].
 */
internal enum class Param {
    // level, routing
    gain,
    velocity,
    postgain,
    dry,
    orbit,
    bus,
    busgain,
    release,
    FXrelease,
    pan,
    djf,
    analyze,
    fft,

    // filters: low-pass, high-pass, band-pass
    cutoff,
    resonance,
    lpenv,
    lpattack,
    lpdecay,
    lpsustain,
    lprelease,
    lprate,
    lpsync,
    lpdepth,
    lpdepthfrequency,
    lpshape,
    lpdc,
    lpskew,
    hcutoff,
    hresonance,
    hpenv,
    hpattack,
    hpdecay,
    hpsustain,
    hprelease,
    hprate,
    hpsync,
    hpdepth,
    hpdepthfrequency,
    hpshape,
    hpdc,
    hpskew,
    bandf,
    bandq,
    bpenv,
    bpattack,
    bpdecay,
    bpsustain,
    bprelease,
    bprate,
    bpsync,
    bpdepth,
    bpdepthfrequency,
    bpshape,
    bpdc,
    bpskew,
    ftype,
    fanchor,
    drive,

    // distortion, modulation, dynamics
    coarse,
    crush,
    shape,
    shapevol,
    distort,
    distortvol,
    distorttype,
    tremolo,
    tremolosync,
    tremolodepth,
    tremoloskew,
    tremolophase,
    tremoloshape,
    compressor,
    compressorRatio,
    compressorKnee,
    compressorAttack,
    compressorRelease,
    phaserrate,
    phaserdepth,
    phasercenter,
    phasersweep,
    transient,
    transsustain,
    stretch,

    // space
    delay,
    delaytime,
    delayfeedback,
    delaysync,
    room,
    roomsize,
    roomfade,
    roomlp,
    roomdim,
    i,
    irspeed,
    irbegin,
    duckorbit,
    duckonset,
    duckattack,
    duckdepth,

    // amplitude envelope, gate and cut group
    attack,
    decay,
    sustain,
    clip,
    loop,
    cut,

    // the sampler: playback
    speed,
    begin,
    end,

    // sample start offset in seconds (only the cut ramp reads it here; the sampler delays its start by it)
    nudge,

    // the sampler: the loop region as fractions of the sample (sampler.mjs:92-104)
    loopBegin,
    loopEnd,

    // sources append their own entries below this line
    ;

    /** The control name, which is the entry name. */
    val key: String get() = name

    companion object {
        val COUNT: Int = entries.size
        val byKey: Map<String, Param> = entries.associateBy { it.name }
    }
}

/**
 * Controls that carry text: they land in [ParamSet.strings] instead of [ParamSet.values]. Where a control can be a number or a
 * name (`ftype 2`, `ftype "ladder"`; `lpshape 1`, `lpshape "sine"`) it is in this set and, as a [Param], too. One key per line, so
 * that a task adding one merges cleanly.
 */
internal val STRING_KEYS: Set<String> = setOf(
    "vowel",
    "ir",
    "distorttype",
    "ftype",
    "tremoloshape",
    "lpshape",
    "hpshape",
    "bpshape",
    "unit",
)

/** Controls that carry a list of numbers (`orbit` of a duck, `channels`): they land in [ParamSet.lists], a plain number as a list of one. */
internal val LIST_KEYS: Set<String> = setOf(
    "channels",
    "duckorbit",
    "duckonset",
    "duckattack",
    "duckdepth",
)

/**
 * The controls of one effect chain (the main one of a voice, or one `FX(...)` element), as the audio engine reads them: [values]
 * holds one double per [Param] (NaN: not set), [strings] the text controls, [lists] the list controls. Built by `voiceParams`; read-only
 * afterwards.
 */
internal class ParamSet(
    val values: DoubleArray = DoubleArray(Param.COUNT) { Double.NaN },
    val strings: Map<String, String> = emptyMap(),
    val lists: Map<String, DoubleArray> = emptyMap(),
) {
    operator fun get(p: Param): Double = values[p.ordinal]

    /** True when the control was set to a number. */
    fun has(p: Param): Boolean = !values[p.ordinal].isNaN()

    /** [p] when set, else [default]. */
    fun or(p: Param, default: Double): Double {
        val v = values[p.ordinal]
        return if (v.isNaN()) default else v
    }

    fun str(key: String): String? = strings[key]

    fun list(key: String): DoubleArray? = lists[key]
}

/** The three modulators: an oscillator (`.lfo`), an envelope (`.env`) and a bus tap (`.bmod`). */
internal enum class ModKind { LFO, ENV, BMOD }

/**
 * One modulator of a voice, as the pattern described it (`lfo`/`env`/`bmod` of the control map): the [control] it modulates
 * (canonical name; `lfo_0` for another modulator), an optional [subControl], the effect chain it lives in ([fxi]: `main` or the index
 * of an `FX` element), and the remaining options by name, [numbers] and [strings].
 */
internal class ModSpec(
    val kind: ModKind,
    val id: String,
    val fxi: String,
    val control: String,
    val subControl: String?,
    val numbers: Map<String, Double>,
    val strings: Map<String, String>,
) {
    /**
     * What [control] and [subControl] modulate, looked up when the event is built (on the scheduler's side, so that connecting the modulator in the
     * audio thread has nothing left to compute); null for a control that cannot be modulated.
     */
    val target: ControlTarget? = ControlTargets.lookup(control, subControl)
}
