package cosic.audio

import cosic.audio.dsp.PhaseVocoder

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

    /**
     * Whether the event's own `ftype` asks for the 24 dB filter pair (`getFilterType(value.ftype) === '24db'`, superdough.mjs:374-378): that
     * test uses the main value for every chain, `FX(...)` elements included. The main chain's filter stages write it in `configure`, the
     * stages of the other chains read it at their first block (the main chain is configured last).
     */
    var filter24db: Boolean = false
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

    /**
     * The voice has ended, or is about to start again: gives back whatever the stage took from a shared pool at [configure]. Called once per
     * voice end (and harmlessly more often), never while the stage is processing.
     */
    fun release() {}
}

/**
 * The stages of one effect chain, in superdough's fixed order (the voice's `FX` elements and its main chain are each one group):
 * stretch, transient, gain, low-pass, high-pass, band-pass, vowel, coarse, crush, shape, distort, tremolo, compressor, pan,
 * phaser, and, for `FX` elements only, delay and reverb. A stage that is not triggered by the controls leaves the chain.
 * (Kabelsalat's generic processor goes first once it exists.)
 */
internal class StageGroup(
    sampleRate: Int,
    vocoders: GrowablePool<PhaseVocoder> = GrowablePool(VOCODER_LIMIT, 0) { PhaseVocoder() },
) {
    val stretch = StretchStage(sampleRate, vocoders)
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

    /** The modulatable parameters of this chain by Web Audio name; filled by [registerParams] when the voice has modulators. */
    val registry = NodeRegistry(sampleRate = sampleRate)

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

    /**
     * The voice has ended or restarts: the stages give back what they took from shared pools and the modulators connected to the
     * parameters of this chain are disconnected (those of a voice's own parameters would not outlive it, but a parameter of the orbit does).
     */
    fun release() {
        for (i in stages.indices) stages[i].release()
        registry.release()
    }
}

/**
 * The shared pools of effect-chain resources of one [Mixer]. The chains of `FX(...)` elements are large and only some patterns use them, so a voice
 * takes the groups it needs for as long as it lasts and [Voice] gives them back when it ends; the phase vocoders of `stretch` (about 150 KB each) are
 * taken by the stage that needs one. Both pools grow ahead of demand (see [GrowablePool] and [prepare]), so a voice gets all the chains and effects
 * its pattern asks for.
 */
internal class FxGroupPool(
    sampleRate: Int,
    initial: Int = FX_GROUP_POOL,
    limit: Int = FX_GROUP_LIMIT,
    space: InChainSpace = InChainSpace(sampleRate, IrExecutor.INLINE),
) {
    /** The phase vocoders that the `stretch` stages of all chains share. */
    val vocoders = GrowablePool<PhaseVocoder>(VOCODER_LIMIT, 0) { PhaseVocoder() }

    private val groups = GrowablePool<StageGroup>(limit, initial) {
        StageGroup(sampleRate, vocoders).also {
            it.delay.attach(space)
            it.reverb.attach(space)
        }
    }

    /** The chains that exist, free or not. */
    val capacity: Int get() = groups.capacity

    /** The chains voices hold now. */
    val inUse: Int get() = groups.inUse

    /** How many chains the audio thread had to make itself because the pool was not prepared for them. */
    val madeOnTheSpot: Int get() = groups.madeOnTheSpot

    /** A chain, or null in the impossible case that all [FX_GROUP_LIMIT] are in use. Audio thread only. */
    fun take(): StageGroup? = groups.take()

    /** Takes a chain back, after it has given back what it holds. Audio thread only. */
    fun give(group: StageGroup) {
        group.release()
        groups.give(group)
    }

    /** Makes both pools big enough for [demand] on top of what voices hold now. Any thread. */
    fun prepare(demand: ChainDemand) = prepare(demand.fxChains, demand.vocoders)

    /** Makes the pools big enough for [fxChains] more chains and [vocoders] more vocoders on top of what voices hold now. Any thread. */
    fun prepare(fxChains: Int, vocoders: Int) {
        groups.ensureFree(fxChains)
        this.vocoders.ensureFree(vocoders)
    }

    /** True when [fxChains] more chains and [vocoders] more vocoders can be taken without making any. Any thread. */
    fun covers(fxChains: Int, vocoders: Int): Boolean = groups.covers(fxChains) && this.vocoders.covers(vocoders)

    /** The chains and vocoders that the audio thread had to make itself. */
    val madeOnTheSpotTotal: Int get() = groups.madeOnTheSpot + vocoders.madeOnTheSpot
}
