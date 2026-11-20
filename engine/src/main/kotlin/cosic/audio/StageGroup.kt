package cosic.audio

/**
 * Where in time one voice is, as stages need it: the onset [t], the end of the gate [end] (`t + durationSec`) and the end of the
 * release [endWithRelease] (`end + max(release, FXrelease)`), all absolute seconds, plus the tempo and the cycle position of the hap.
 * One instance lives in each [Voice] and is overwritten at every start, so a stage that wants a value later copies it in
 * [ChainStage.configure].
 */
internal class VoiceTiming(
    var t: Double = 0.0,
    var end: Double = 0.0,
    var endWithRelease: Double = 0.0,
    var cps: Double = 0.5,
    var cycle: Double = 0.0,
) {
    fun set(t: Double, end: Double, endWithRelease: Double, cps: Double, cycle: Double) {
        this.t = t
        this.end = end
        this.endWithRelease = endWithRelease
        this.cps = cps
        this.cycle = cycle
    }
}

/**
 * One node (or small group of nodes) of the per-event graph of `superdough()`. A voice owns one instance of every stage per effect
 * chain and reuses it: [reset] and [configure] run at every voice start, [process] once per block while the voice sounds.
 *
 * Rules for implementations: never allocate in any of the three; keep per-instance memory small (a voice pool holds
 * `VOICE_SLOTS * (MAX_FX + 1)` of each stage; large buffers belong in a shared pool that [configure] takes from); work in
 * place on `b` for the first `ctx.n` frames, keeping any state keyed on absolute frames (`ctx.startFrame + i`) rather than on how
 * blocks were cut.
 */
internal interface ChainStage {
    /**
     * Sets the stage up for a voice from the controls [p] of one effect chain; [isMain] is false for an `FX(...)` element. Returns true when
     * the stage is part of the chain for this event (when the control that triggers it is set), false to leave it out.
     */
    fun configure(p: ParamSet, timing: VoiceTiming, isMain: Boolean): Boolean

    /** Processes `ctx.n` frames of [b] in place. Only called after [configure] returned true. */
    fun process(b: AudioBlock, ctx: BlockContext)

    /** Forgets all state of the previous voice. Called before every [configure]. */
    fun reset()

    /**
     * Makes the parameters of the stage that other modulators may target visible under their Web Audio names (`lpf.frequency`, `pan.pan`,
     * `gain.gain`: see `CONTROL_TARGETS`). Called after [configure] and only when the voice has modulators.
     */
    fun registerParams(reg: NodeRegistry) {}
}

/**
 * The stages of one effect chain, in superdough's fixed order (the voice's `FX` elements and its main chain are each one group):
 * stretch, transient, gain, low-pass, high-pass, band-pass, vowel, coarse, crush, shape, distort, tremolo, compressor, pan,
 * phaser, and, for `FX` elements only, delay and reverb. A stage that is not triggered by the controls leaves the chain.
 * (Kabelsalat's generic processor goes first once it exists.)
 */
internal class StageGroup(sampleRate: Int) {
    val stretch = StretchStage(sampleRate)
    val transient = TransientStage(sampleRate)
    val gain = GainStage(sampleRate)
    val lowpass = FilterStage(sampleRate, BiquadType.LOWPASS, FilterKeys.LOWPASS)
    val highpass = FilterStage(sampleRate, BiquadType.HIGHPASS, FilterKeys.HIGHPASS)
    val bandpass = FilterStage(sampleRate, BiquadType.BANDPASS, FilterKeys.BANDPASS)
    val vowel = VowelStage(sampleRate)
    val coarse = CoarseStage(sampleRate)
    val crush = CrushStage(sampleRate)
    val shape = ShapeStage(sampleRate)
    val distort = DistortStage(sampleRate)
    val tremolo = TremoloStage(sampleRate)
    val compressor = CompressorStage(sampleRate)
    val pan = PanStage()
    val phaser = PhaserStage(sampleRate)
    val delay = InChainDelay(sampleRate)
    val reverb = InChainReverb(sampleRate)

    private val stages: Array<ChainStage> = arrayOf(
        stretch, transient, gain, lowpass, highpass, bandpass, vowel, coarse, crush, shape, distort, tremolo, compressor, pan, phaser,
        delay, reverb,
    )
    private val active = BooleanArray(stages.size)

    /** Resets every stage and configures it from [p]. */
    fun configure(p: ParamSet, timing: VoiceTiming, isMain: Boolean) {
        for (i in stages.indices) {
            val stage = stages[i]
            stage.reset()
            active[i] = stage.configure(p, timing, isMain)
        }
    }

    /** Runs the block through the stages that are in the chain, in order. */
    fun process(b: AudioBlock, ctx: BlockContext) {
        for (i in stages.indices) if (active[i]) stages[i].process(b, ctx)
    }

    /** Lets the stages that are in the chain register their modulatable parameters. */
    fun registerParams(reg: NodeRegistry) {
        for (i in stages.indices) if (active[i]) stages[i].registerParams(reg)
    }

    /** Leaves every stage out of the chain; what an unused group is. */
    fun clear() {
        active.fill(false)
    }
}

/**
 * The chains of `FX(...)` elements, shared by all voices: only some patterns use them and a chain is large, so a voice takes the groups it needs
 * for as long as it lasts ([Voice] gives them back when it ends). When the pool is empty a voice gets fewer chains than it has elements.
 */
internal class FxGroupPool(sampleRate: Int, capacity: Int = FX_GROUP_POOL) {
    private val free = arrayOfNulls<StageGroup>(capacity)
    private var count = capacity

    init {
        for (i in 0 until capacity) free[i] = StageGroup(sampleRate)
    }

    /** A group, or null when all are taken. */
    fun take(): StageGroup? {
        if (count == 0) return null
        val group = free[--count]
        free[count] = null
        return group
    }

    fun give(group: StageGroup) {
        free[count++] = group
    }
}
