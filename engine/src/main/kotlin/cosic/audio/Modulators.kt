package cosic.audio

import cosic.pattern.logger

/*
 * The modulators of a voice: `.lfo`, `.env` and `.bmod` (packages/superdough/modulators.mjs, connected at the end of superdough.mjs:1004-1049).
 *
 * A modulator is a signal that is added to a parameter of the effect chains, the way `lfoNode.connect(audioParam)` adds the output of an AudioWorklet to
 * an AudioParam. Which parameter is found by name: the stages put theirs in a [NodeRegistry] under the Web Audio names of CONTROL_TARGETS
 * ([ControlTargets]), and a modulator looks up the node and parameter its control maps to.
 */

/** The most parameters one modulator is connected to: the filters of a 24 dB chain, the five bands of a vowel filter, the filters of a phaser. */
private const val MAX_TARGETS = 8

/** `depthGain = gainNode(sign(depth) * |depth| / 0.3)` of a bus modulator (modulators.mjs:160). */
private const val BUS_DEPTH_DIVISOR = 0.3

private const val SUPERDOUGH_ERROR = "[superdough] error: "

/** [x] as the float32 an AudioParam holds. */
private fun f32(x: Double): Double = x.toFloat().toDouble()

private fun superdoughError(message: String) = logger(SUPERDOUGH_ERROR + message, "error", null)

/**
 * The modulatable parameters of one effect chain of a voice, by their Web Audio names: the node (`lpf`, `gain`, `lfo_0`) and the parameter on it
 * (`frequency`, `Q`). Stages put their parameters in ([ChainStage.registerParams]); the modulators look their targets up. Fixed capacity, never
 * allocates.
 *
 * The chain that is the voice's own also holds the modulators themselves, each as a node named `<kind>_<id>` (`lfo_0`, `env_lpf_mod`) with the
 * parameters of CONTROL_TARGETS, so that a later modulator can modulate an earlier one. Their names are kept as two strings and compared piecewise, which
 * keeps registering them from building a string.
 */
internal class NodeRegistry(capacity: Int = 96, internal val sampleRate: Int = 48_000) {
    private val nodes = arrayOfNulls<String>(capacity)
    private val ids = arrayOfNulls<String>(capacity)
    private val params = arrayOfNulls<String>(capacity)
    private val targets = arrayOfNulls<AParam>(capacity)
    private var count = 0
    private var bank: ModulatorBank? = null

    /** The number of registered parameters. */
    val size: Int get() = count

    fun clear() {
        for (i in 0 until count) {
            ids[i] = null
            targets[i] = null
        }
        count = 0
    }

    /** Registers [p] as parameter [param] of node [node]; several parameters may share a name (one per filter in series). */
    fun register(node: String, param: String, p: AParam) {
        add(node, null, param, p)
    }

    /** Registers [p] as parameter [param] of the modulator node `<kind>_<id>`; a node has several parameters, registered one after another. */
    fun registerModulator(kind: String, id: String, param: String, p: AParam) {
        add(kind, id, param, p)
    }

    /**
     * Removes every parameter of the modulator node `<kind>_<id>`: what registering a node of that name again starts with, as superdough overwrites
     * `nodes.main['lfo_0']` when two chains of a voice both have an LFO with the id 0.
     */
    fun removeModulator(kind: String, id: String) {
        var kept = 0
        for (i in 0 until count) {
            if (ids[i] != null && nodes[i] == kind && ids[i] == id) continue
            if (kept != i) {
                nodes[kept] = nodes[i]
                ids[kept] = ids[i]
                params[kept] = params[i]
                targets[kept] = targets[i]
            }
            kept++
        }
        for (i in kept until count) {
            nodes[i] = null
            ids[i] = null
            params[i] = null
            targets[i] = null
        }
        count = kept
    }

    private fun add(node: String, id: String?, param: String, p: AParam) {
        if (count >= targets.size) {
            superdoughError("too many parameters are registered for modulation: '$node${if (id == null) "" else "_$id"}.$param' is left out")
            return
        }
        nodes[count] = node
        ids[count] = id
        params[count] = param
        targets[count] = p
        count++
    }

    /** Whether entry [i] belongs to the node called [name]. */
    private fun named(i: Int, name: String): Boolean {
        val id = ids[i] ?: return nodes[i] == name
        val kind = nodes[i]!!
        val k = kind.length
        return name.length == k + 1 + id.length && name.startsWith(kind) && name[k] == '_' && name.regionMatches(k + 1, id, 0, id.length)
    }

    /** The [index]-th parameter registered under [node] and [param], or null. */
    fun find(node: String, param: String, index: Int = 0): AParam? {
        var seen = 0
        for (i in 0 until count) {
            if (params[i] == param && named(i, node)) {
                if (seen == index) return targets[i]
                seen++
            }
        }
        return null
    }

    /** True when some parameter is registered under the node called [node]. */
    fun hasNode(node: String): Boolean {
        for (i in 0 until count) if (named(i, node)) return true
        return false
    }

    /** The names of the registered nodes, each once, in registration order, separated by commas (for error messages). */
    fun nodeNames(): String {
        val seen = LinkedHashSet<String>()
        for (i in 0 until count) seen.add(if (ids[i] == null) nodes[i]!! else "${nodes[i]}_${ids[i]}")
        return seen.joinToString(", ")
    }

    /**
     * `getTargetParamsForControl` (modulators.mjs:62-88): the parameters that [target] names, in registration order, written into [out]; their number is
     * returned. The node is `target.node` when the chain has one by that name and [control] itself otherwise, which is how a modulator is found:
     * `control` is `lfo_0` while the table only knows `lfo`.
     */
    fun resolve(target: ControlTarget, control: String, out: Array<AParam?>): Int {
        val byNode = hasNode(target.node)
        var n = 0
        for (i in 0 until count) {
            if (params[i] != target.param) continue
            if (!named(i, if (byNode) target.node else control)) continue
            if (n < out.size) out[n++] = targets[i]
        }
        for (i in n until out.size) out[i] = null
        return n
    }

    /** The modulator nodes of this chain's voice (made on first use). */
    internal fun modulators(): ModulatorBank = bank ?: ModulatorBank(sampleRate).also { bank = it }

    /** The voice has ended: its modulators let go of the parameters they were connected to. */
    fun release() {
        bank?.disconnect()
    }
}

/** A modulator node and the parameters it is connected to, so that it can be disconnected again. */
internal class Wire<M : Modulator>(val node: M) {
    private val connected = arrayOfNulls<AParam>(MAX_TARGETS)
    private var count = 0

    fun connect(target: AParam) {
        if (count >= connected.size) return
        target.addInput(node)
        connected[count++] = target
    }

    fun disconnect() {
        for (i in 0 until count) {
            connected[i]!!.removeInput(node)
            connected[i] = null
        }
        count = 0
    }
}

/**
 * The modulator nodes of one voice slot: up to [MAX_MODS] LFOs, envelopes and bus modulators, made the first time the slot needs them and reused by every
 * later voice (a voice that has modulators takes them in turn with [nextLfo] and the like; nothing is allocated after the first time). A modulator is
 * connected to parameters of the voice's chains, and sometimes of its orbit, which live longer than the voice; [disconnect] takes them out again when
 * the voice ends.
 */
internal class ModulatorBank(private val sampleRate: Int) {
    private val lfos = arrayOfNulls<Wire<LfoOsc>>(MAX_MODS)
    private val envelopes = arrayOfNulls<Wire<ModEnvelope>>(MAX_MODS)
    private val taps = arrayOfNulls<Wire<BusModulator>>(MAX_MODS)
    private var lfoCount = 0
    private var envelopeCount = 0
    private var tapCount = 0

    /** Where the parameters of a modulator's target are collected, so that resolving one allocates nothing. */
    val resolved = arrayOfNulls<AParam>(MAX_TARGETS)

    private var boundBuses: AudioBuses? = null
    private var boundTap: BusTap? = null

    /** [buses] as a [BusTap]; made once for the slot and reused, so connecting a bus modulator allocates nothing after the first time. */
    fun busTapOf(buses: AudioBuses): BusTap {
        val existing = boundTap
        if (existing != null && boundBuses === buses) return existing
        val made = BusTap { bus -> buses.tap(bus) }
        boundBuses = buses
        boundTap = made
        return made
    }

    /** The voice starts: every modulator is free again. */
    fun begin() {
        disconnect()
    }

    fun nextLfo(): Wire<LfoOsc> {
        val wire = lfos[lfoCount] ?: Wire(LfoOsc(sampleRate)).also { lfos[lfoCount] = it }
        lfoCount++
        return wire
    }

    fun nextEnvelope(): Wire<ModEnvelope> {
        val wire = envelopes[envelopeCount] ?: Wire(ModEnvelope(sampleRate)).also { envelopes[envelopeCount] = it }
        envelopeCount++
        return wire
    }

    fun nextBusTap(): Wire<BusModulator> {
        val wire = taps[tapCount] ?: Wire(BusModulator()).also { taps[tapCount] = it }
        tapCount++
        return wire
    }

    /** Disconnects every modulator that was handed out since [begin] and makes them all free. */
    fun disconnect() {
        for (i in 0 until lfoCount) lfos[i]!!.disconnect()
        for (i in 0 until envelopeCount) envelopes[i]!!.disconnect()
        for (i in 0 until tapCount) taps[i]!!.disconnect()
        lfoCount = 0
        envelopeCount = 0
        tapCount = 0
    }
}

/** Where a bus modulator reads the signal of a bus (`controller.getBus(n)`): the stereo sum of what was sent to it in the block being rendered, or null. */
internal fun interface BusTap {
    fun tap(bus: Int): StereoBuffer?
}

/**
 * `connectBusModulator` (modulators.mjs:146-179): a bus used as a modulator. Per channel the bus signal plus the `dc` offset goes through a gain of
 * `depth / 0.3`; the channels are then added to the parameter, which down-mixes them to mono (`0.5 (L + R)`). The connection is made at `begin`; before
 * that the modulator is silent.
 *
 * superdough clamps the signal to the parameter's range for a frequency of 30 Hz or more, with a `WaveShaperNode` (modulators.mjs:49-60), but
 * `clampWithWaveShaper` returns the node it was given instead of the shaper, so the shaper is built, fed, and connected to nothing: the real signal
 * is not clamped. Chromium rendering the real code confirms it (`modulators.json`), and so nothing is clamped here either.
 */
internal class BusModulator : BlockModulator() {
    private var source: BusTap? = null
    private var bus = -1
    private var dc = 0f
    private var gain = 0f
    private var begin = 0.0

    /** Starts the modulator for one voice: it reads bus [bus] from [source] (a negative number: a bus nobody sends to), adds [dc] and multiplies by `depth / 0.3`. */
    fun start(source: BusTap, bus: Int, dc: Double, depth: Double, begin: Double) {
        this.source = source
        this.bus = bus
        this.dc = dc.toFloat()
        this.gain = (Math.signum(depth) * Math.abs(depth) / BUS_DEPTH_DIVISOR).toFloat()
        this.begin = begin
        invalidate()
    }

    override fun render(ctx: BlockContext) {
        val n = ctx.n
        val signal = if (bus >= 0) source?.tap(bus) else null
        val left = signal?.left
        val right = signal?.right
        // the first frame at or after the time of the connection
        val first = Math.min(n.toDouble(), Math.max(0.0, Math.ceil(begin * ctx.sampleRate) - ctx.startFrame)).toInt()
        for (i in 0 until first) buf[i] = 0f
        for (i in first until n) {
            val l = ((if (left != null) left[i] else 0f) + dc) * gain
            val r = ((if (right != null) right[i] else 0f) + dc) * gain
            buf[i] = 0.5f * (l + r)
        }
    }
}

/**
 * Connects the `.lfo`, `.env` and `.bmod` modulators of a voice to the parameters they target (`superdough.mjs:1004-1049`, modulators.mjs). [mods] are
 * in the order superdough connects them: the `FX(...)` elements first, then the event itself, each with its LFOs, envelopes and bus modulators.
 * [groups] are the effect chains in use followed by the voice's own, [registryFor] gives the [NodeRegistry] of a chain by its `fxi` (`main` or the
 * index of an `FX` element). [t] and [endWithRelease] are the voice's start and the end of its release, [cps] and [cycle] the tempo and the hap's
 * cycle, which an LFO's phase is locked to.
 *
 * Per modulator: the target parameters are looked up by Web Audio name ([ControlTargets]; one that cannot be found is reported and skipped, and
 * so is a modulator that follows a chain the voice does not have); the base value of the first one (0 counts as 1) gives the modulation depth
 * (`depth * value`, or `depthabs`) and, for a frequency of 30 Hz or more, the range `20 - value .. 24000 - value`; the modulator is started for the
 * voice (begin [t], end [endWithRelease]) and connected to every target; LFOs and envelopes are registered as the nodes `lfo_<id>` and `env_<id>` of
 * the voice's own chain, where later modulators can target them. Every number a Web Audio node would hold in an AudioParam is rounded to float32 first.
 *
 * At most [MAX_MODS] modulators are connected; the rest are ignored with a warning. Allocates nothing once the voice slot has made its modulator nodes.
 * Unlike superdough, which would throw while connecting, an LFO option that is not a parameter of the LFO is ignored.
 */
internal fun connectModulators(
    mods: List<ModSpec>, groups: Array<StageGroup>, registryFor: (String) -> NodeRegistry,
    t: Double, endWithRelease: Double, cps: Double, cycle: Double, buses: AudioBuses,
) {
    val main = registryFor("main")
    val bank = main.modulators()
    bank.begin()
    val tap = bank.busTapOf(buses)
    connectModulators(mods, groups.size - 1, registryFor, bank, t, endWithRelease, cps, cycle, tap)
}

/** [connectModulators] with the bus signals given as a [BusTap]; [chains] is the number of `FX(...)` chains in use. */
internal fun connectModulators(
    mods: List<ModSpec>, chains: Int, registryFor: (String) -> NodeRegistry, bank: ModulatorBank,
    t: Double, endWithRelease: Double, cps: Double, cycle: Double, tap: BusTap,
) {
    val main = registryFor("main")
    val count = Math.min(mods.size, MAX_MODS)
    if (mods.size > MAX_MODS) superdoughError("a voice can connect $MAX_MODS modulators; ${mods.size - MAX_MODS} ignored")
    for (i in 0 until count) {
        val spec = mods[i]
        val registry = chainRegistry(spec, chains, registryFor) ?: continue
        when (spec.kind) {
            ModKind.LFO -> connectLfo(spec, registry, main, bank, t, endWithRelease, cps, cycle)
            ModKind.ENV -> connectEnvelope(spec, registry, main, bank, t, endWithRelease)
            ModKind.BMOD -> connectBus(spec, registry, bank, tap, t)
        }
    }
}

/** The registry of the chain [spec] lives in or names with `fxi`: `main`, or an `FX(...)` chain the voice has. */
private fun chainRegistry(spec: ModSpec, chains: Int, registryFor: (String) -> NodeRegistry): NodeRegistry? {
    val fxi = spec.fxi
    if (fxi == "main") return registryFor("main")
    val index = fxi.toIntOrNull()
    if (index == null || index < 0 || index >= chains) {
        superdoughError("Could not connect to target '${spec.control}': the voice has no effect chain '$fxi' (it has $chains)")
        return null
    }
    return registryFor(fxi)
}

/** Looks up the parameters [spec] modulates in [registry]; they are left in [ModulatorBank.resolved]. Returns how many there are. */
private fun resolveTargets(spec: ModSpec, registry: NodeRegistry, bank: ModulatorBank): Int {
    val target = spec.target
    if (target == null) {
        superdoughError("Could not find control data for target '${spec.control}'. It may not be modulatable.")
        return 0
    }
    val n = registry.resolve(target, spec.control, bank.resolved)
    if (n == 0) {
        val node = if (registry.hasNode(target.node)) target.node else spec.control
        if (registry.hasNode(node)) {
            superdoughError("Could not connect to target '$node': it has no parameter '${target.param}'")
        } else {
            superdoughError("Could not connect to target '$node' — it does not exist. Available targets: ${registry.nodeNames()}")
        }
    }
    return n
}

/** `currentValue` of connectLFO and friends: the value of the first target (what `AudioParam.value` reads, a float32), with 0 counted as 1. */
private fun currentValueOf(first: AParam): Double {
    val value = f32(first.base)
    return if (value == 0.0) 1.0 else value
}

/** The low end of the range a modulator is clamped to (`getRangeForParam`): `20 - value` for a frequency of 30 Hz or more, NaN (none) otherwise. */
private fun rangeMin(param: String, currentValue: Double): Double = if (param == "frequency" && currentValue >= 30.0) 20.0 - currentValue else Double.NaN

/** The high end of the range, `24000 - value` for the same parameters, NaN otherwise. */
private fun rangeMax(param: String, currentValue: Double): Double = if (param == "frequency" && currentValue >= 30.0) 24000.0 - currentValue else Double.NaN

/** The depth of a modulator: `depthabs` when given, else `depth * currentValue` (`depth` is 1 when not given). */
private fun depthOf(options: Map<String, Double>, currentValue: Double): Double {
    val absolute = options["depthabs"]
    return if (absolute != null) absolute else (options["depth"] ?: 1.0) * currentValue
}

private fun connectLfo(
    spec: ModSpec, registry: NodeRegistry, main: NodeRegistry, bank: ModulatorBank, t: Double, endWithRelease: Double, cps: Double, cycle: Double,
) {
    val found = resolveTargets(spec, registry, bank)
    if (found == 0) return
    val targets = bank.resolved
    val options = spec.numbers
    val currentValue = currentValueOf(targets[0]!!)
    val param = spec.target!!.param
    val depth = depthOf(options, currentValue)
    val sync = options["sync"]
    val frequency = if (sync != null) sync * cps else (options["rate"] ?: 1.0)
    val time = if ((options["retrig"] ?: 0.0) > 0.5) 0.0 else cycle / cps
    val dcoffset = options["dcoffset"] ?: -0.5
    val low = rangeMin(param, currentValue)
    val min = if (low.isNaN()) lfoDefaultMin(dcoffset, depth) else low
    val max = if (low.isNaN()) lfoDefaultMax(dcoffset, depth) else rangeMax(param, currentValue)

    val wire = bank.nextLfo()
    val lfo = wire.node
    lfo.start(
        lfoShapeIndex(options["shape"], spec.strings["shape"]), f32(options["skew"] ?: 0.5), f32(depth), f32(dcoffset), null, f32(frequency),
        f32(time), f32(options["phaseoffset"] ?: 0.0), f32(options["curve"] ?: 1.0), f32(min), f32(max), f32(t), f32(endWithRelease),
    )
    main.removeModulator("lfo", spec.id)
    main.registerModulator("lfo", spec.id, "frequency", lfo.frequencyParam)
    main.registerModulator("lfo", spec.id, "depth", lfo.depthParam)
    main.registerModulator("lfo", spec.id, "skew", lfo.skewParam)
    main.registerModulator("lfo", spec.id, "curve", lfo.curveParam)
    main.registerModulator("lfo", spec.id, "dcoffset", lfo.dcoffsetParam)
    for (i in 0 until found) wire.connect(targets[i]!!)
}

private fun connectEnvelope(spec: ModSpec, registry: NodeRegistry, main: NodeRegistry, bank: ModulatorBank, t: Double, endWithRelease: Double) {
    val found = resolveTargets(spec, registry, bank)
    if (found == 0) return
    val targets = bank.resolved
    val options = spec.numbers
    val currentValue = currentValueOf(targets[0]!!)
    val param = spec.target!!.param
    val depth = depthOf(options, currentValue)
    val low = rangeMin(param, currentValue)
    val min = if (low.isNaN()) ENVELOPE_LOW else low
    val max = if (low.isNaN()) ENVELOPE_HIGH else rangeMax(param, currentValue)

    val wire = bank.nextEnvelope()
    val envelope = wire.node
    envelope.start(
        f32(options["attack"] ?: 0.005), f32(options["decay"] ?: 0.14), f32(options["sustain"] ?: 0.0), f32(options["release"] ?: 0.1),
        f32(options["acurve"] ?: 0.0), f32(options["dcurve"] ?: 0.0), f32(options["rcurve"] ?: 0.0), f32(depth), f32(min), f32(max),
        f32(t), f32(endWithRelease),
    )
    main.removeModulator("env", spec.id)
    main.registerModulator("env", spec.id, "depth", envelope.depthParam)
    main.registerModulator("env", spec.id, "attack", envelope.attackParam)
    main.registerModulator("env", spec.id, "decay", envelope.decayParam)
    main.registerModulator("env", spec.id, "sustain", envelope.sustainParam)
    main.registerModulator("env", spec.id, "release", envelope.releaseParam)
    for (i in 0 until found) wire.connect(targets[i]!!)
}

private fun connectBus(spec: ModSpec, registry: NodeRegistry, bank: ModulatorBank, tap: BusTap, t: Double) {
    val found = resolveTargets(spec, registry, bank)
    if (found == 0) return
    val targets = bank.resolved
    val options = spec.numbers
    val depth = depthOf(options, currentValueOf(targets[0]!!))
    val bus = options["bus"]

    val wire = bank.nextBusTap()
    wire.node.start(tap, if (bus == null) -1 else bus.toInt(), options["dc"] ?: 0.0, depth, t)
    for (i in 0 until found) wire.connect(targets[i]!!)
}

/** `min` and `max` of an envelope node that has no range (the worklet's defaults). */
private const val ENVELOPE_LOW = -1e9
private const val ENVELOPE_HIGH = 1e9

/**
 * The signal of bus [bus] in the block being rendered, as [BusTap] wants it: the stereo sum of everything voices sent to it, or null when nothing ever
 * did. [AudioBuses] provides this itself (Task 3.9: `fun tap(bus: Int): StereoBuffer?`); this extension answers "nothing" until it does, and a member of
 * the same signature always wins over it.
 */
@Suppress("EXTENSION_SHADOWED_BY_MEMBER", "UNUSED_PARAMETER")
internal fun AudioBuses.tap(bus: Int): StereoBuffer? = null
