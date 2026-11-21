package cosic.kabel

import cosic.pattern.jsNumberToString

/**
 * The state the worklet keeps across voices in its module scope: how many `LcgNoise` generators it has made (their seed) and the delay lines that
 * delays with an id share (`delays` of ugens.js). One per engine; used by the thread that makes instances.
 */
internal class KabelRuntime {
    var noiseChannels = 0
    val delays = HashMap<String, DelayLine>()
}

/**
 * A kabel program running for one voice: what `GenericProcessor` of superdough holds after it has been given a program (worklets.mjs:1496-1572): the
 * ugens, the registers, the outputs, the clock `playPos` that starts at 0 with the first sample and advances by `1 / sampleRate`.
 *
 * Making an instance may allocate a lot (the delay lines) and draws from [rng] in the order of the ugens, as the worklet does; [sample] allocates
 * nothing. Use from one thread at a time.
 */
internal class KabelInstance(val program: KabelProgram, val sampleRate: Int, runtime: KabelRuntime, rng: KabelRandom) {
    private val regs: DoubleArray = program.regInit.copyOf()
    private val outputs = DoubleArray(program.outputSlots)
    private val sources = DoubleArray(program.outputSlots)
    private val ugens: Array<Ugen?>
    private val gate: CcU?
    private val ctx = SampleCtx()
    private val sampleTime = 1.0 / sampleRate
    private var playPos = 0.0

    /** True when the worklet would be dead: it failed to load the program, or a ugen threw. Its output is silence. */
    var dead: Boolean = false
        private set

    val left: Double get() = outputs[0]
    val right: Double get() = outputs[1]

    init {
        val specs = program.ugens
        val count = if (program.loadFault != null) program.loadFaultAt.coerceIn(0, specs.size) else specs.size
        ugens = arrayOfNulls(specs.size)
        var last: CcU? = null
        for (i in 0 until count) {
            val ugen = makeUgen(specs[i], i, runtime, rng)
            ugens[i] = ugen
            if (ugen is CcU && i in program.gateCc) {
                ugen.value = 1.0
                last = ugen // the worklet keeps only the last gate it finds
            }
        }
        gate = last
        if (program.loadFault != null) dead = true
    }

    private fun makeUgen(spec: UgenSpec, index: Int, runtime: KabelRuntime, rng: KabelRandom): Ugen? = when (spec.type) {
        "ADSRNode" -> AdsrU()
        "Clock" -> ClockU(sampleRate)
        "ClockDiv" -> ClockDivU()
        "Distort" -> DistortU()
        "NoiseOsc" -> NoiseU(rng)
        "LcgNoise" -> LcgNoiseU(runtime)
        "PinkNoise" -> PinkU(rng)
        "BrownNoiseOsc" -> BrownU(rng)
        "DustOsc" -> DustU(rng)
        "ImpulseOsc" -> ImpulseU(sampleRate)
        "SawOsc" -> SawU(sampleRate, rng)
        "ZawOsc" -> ZawU(sampleRate)
        "SineOsc" -> SineU(sampleRate)
        "TriOsc" -> TriU(sampleRate)
        "PulseOsc" -> PulseU(sampleRate)
        "Slide" -> SlideU()
        "Lag" -> LagU()
        "Slew" -> SlewU()
        "Filter" -> TwoPoleU(false)
        "BPF" -> TwoPoleU(true)
        "Fold" -> FoldU()
        "Sequence" -> SequenceU()
        "Delay" -> DelayU(delayLine(spec, index, runtime))
        "Hold" -> HoldU()
        "MidiFreq", "MidiGate", "MidiVel" -> ConstU(0.0)
        "MidiCC" -> ConstU(spec.inputs.getOrNull(2).let { if (it == null || it === UgenInput.Missing) -1.0 else if (it is UgenInput.Num) it.value else Double.NaN })
        "CC" -> CcU(spec.inputs.getOrNull(1).let { if (it == null || it === UgenInput.Missing) 0.0 else if (it is UgenInput.Num) it.value else Double.NaN })
        "Remap" -> RemapU()
        "Pick" -> PickU()
        "Clip" -> ClipU()
        "Trig" -> TrigU()
        "BiquadFilter" -> BiquadU(sampleRate.toDouble())
        else -> null // Output and AudioIn have no state: the operations do their work
    }

    /** `Delay`'s constructor: with an id, the line is a copy of the line the last delay with that id made, and becomes the one to copy next. */
    private fun delayLine(spec: UgenSpec, index: Int, runtime: KabelRuntime): DelayLine {
        val key = spec.inputs.getOrNull(2)?.let { delayKey(it) }
        val shared = if (key != null) runtime.delays[key] else null
        val line = shared?.copy() ?: DelayLine(sampleRate, FloatArray(program.delayLens[index]))
        if (key != null) runtime.delays[key] = line
        return line
    }

    private fun delayKey(input: UgenInput): String? = when (input) {
        is UgenInput.Num -> if (truthy(input.value)) "n:" + jsNumberToString(input.value) else null
        is UgenInput.Str -> "s:" + input.value
        is UgenInput.Reg -> "r:" + input.index
        UgenInput.Missing -> null
    }

    /** `gateNode.setValue(on ? 1 : 0)`: switches the last gate of the program (`sGate` is a `cc` per use; only the last one is found). */
    fun setGate(on: Boolean) {
        gate?.value = if (on) 1.0 else 0.0
    }

    /**
     * Computes one sample from [input] (the voice's audio, which `audioin()` reads) into [left] and [right], then advances the clock. Does nothing
     * to the outputs of a dead instance beyond zeroing them.
     */
    fun sample(input: Double) {
        val outputs = outputs
        java.util.Arrays.fill(outputs, 0.0)
        if (dead) return
        val c = ctx
        c.time = playPos
        c.input = input
        val r = regs
        val a = program.args
        val code = program.opCode
        val dst = program.opDst
        val off = program.opArgOff
        val len = program.opArgLen
        val ugenIndex = program.opUgen
        for (k in code.indices) {
            val o = off[k]
            val d = dst[k]
            when (code[k]) {
                Op.THRU -> r[d] = r[a[o]]
                Op.TIME -> r[d] = c.time
                Op.ADD -> {
                    var acc = r[a[o]]
                    for (j in 1 until len[k]) acc += r[a[o + j]]
                    r[d] = acc
                }
                Op.MUL -> {
                    var acc = r[a[o]]
                    for (j in 1 until len[k]) acc *= r[a[o + j]]
                    r[d] = acc
                }
                Op.SUB -> {
                    var acc = r[a[o]]
                    for (j in 1 until len[k]) acc -= r[a[o + j]]
                    r[d] = acc
                }
                Op.DIV -> {
                    var acc = r[a[o]]
                    for (j in 1 until len[k]) acc /= r[a[o + j]]
                    r[d] = acc
                }
                Op.MOD -> r[d] = r[a[o]] % r[a[o + 1]]
                Op.POW -> r[d] = jsPow(r[a[o]], r[a[o + 1]])
                Op.LOG -> r[d] = Math.log(r[a[o]])
                Op.EXP -> r[d] = Math.exp(r[a[o]])
                Op.SIN -> r[d] = Math.sin(r[a[o]])
                Op.COS -> r[d] = Math.cos(r[a[o]])
                Op.TAN -> r[d] = Math.tan(r[a[o]])
                Op.ASIN -> r[d] = Math.asin(r[a[o]])
                Op.ACOS -> r[d] = Math.acos(r[a[o]])
                Op.ATAN -> r[d] = Math.atan(r[a[o]])
                Op.ABS -> r[d] = Math.abs(r[a[o]])
                Op.FLOOR -> r[d] = Math.floor(r[a[o]])
                Op.CEIL -> r[d] = Math.ceil(r[a[o]])
                Op.ROUND -> r[d] = kabelRound(r[a[o]])
                Op.SIGN -> r[d] = Math.signum(r[a[o]])
                Op.CLAMP -> {
                    val lo = r[a[o + 1]]
                    val hi = r[a[o + 2]]
                    r[d] = Math.min(Math.max(r[a[o]], Math.min(lo, hi)), Math.max(lo, hi))
                }
                Op.MIN -> {
                    var acc = r[a[o]]
                    for (j in 1 until len[k]) acc = Math.min(acc, r[a[o + j]])
                    r[d] = acc
                }
                Op.MAX -> {
                    var acc = r[a[o]]
                    for (j in 1 until len[k]) acc = Math.max(acc, r[a[o + j]])
                    r[d] = acc
                }
                Op.ARGMIN -> {
                    var best = r[a[o]]
                    var bestIndex = 0
                    for (j in 1 until len[k]) {
                        val q = r[a[o + j]]
                        if (!(best < q)) {
                            best = q
                            bestIndex = j
                        }
                    }
                    r[d] = bestIndex.toDouble()
                }
                Op.ARGMAX -> {
                    var best = r[a[o]]
                    var bestIndex = 0
                    for (j in 1 until len[k]) {
                        val q = r[a[o + j]]
                        if (!(best > q)) {
                            best = q
                            bestIndex = j
                        }
                    }
                    r[d] = bestIndex.toDouble()
                }
                Op.GT -> r[d] = if (r[a[o]] > r[a[o + 1]]) 1.0 else 0.0
                Op.LT -> r[d] = if (r[a[o]] < r[a[o + 1]]) 1.0 else 0.0
                Op.XOR -> r[d] = if (r[a[o]] != r[a[o + 1]]) 1.0 else 0.0
                Op.AND -> r[d] = if (truthy(r[a[o]]) && truthy(r[a[o + 1]])) 1.0 else 0.0
                Op.OR -> r[d] = if (truthy(r[a[o]]) || truthy(r[a[o + 1]])) 1.0 else 0.0
                Op.NOT -> r[d] = if (r[a[o]] == 0.0) 1.0 else 0.0
                Op.BOOL -> r[d] = if (r[a[o]] == 0.0) 0.0 else 1.0
                Op.IFELSE -> r[d] = if (r[a[o]] == 1.0) r[a[o + 1]] else r[a[o + 2]]
                Op.RANGE -> {
                    val u = (r[a[o]] + 1) * 0.5
                    r[d] = u * (r[a[o + 2]] - r[a[o + 1]]) + r[a[o + 1]]
                }
                Op.RANGE_CURVE -> {
                    val u = (r[a[o]] + 1) * 0.5
                    r[d] = jsPow(u, r[a[o + 3]]) * (r[a[o + 2]] - r[a[o + 1]]) + r[a[o + 1]]
                }
                Op.MIDINOTE -> r[d] = Math.pow(2.0, (r[a[o]] - 69) / 12) * 440
                Op.SRC -> {
                    val id = r[a[o]]
                    val i = id.toInt()
                    r[d] = if (i.toDouble() == id && i >= 0 && i < sources.size) sources[i] else Double.NaN
                }
                Op.OUT -> {
                    val id = r[a[o + 1]]
                    val i = id.toInt()
                    if (i.toDouble() == id && i >= 0 && i < outputs.size) {
                        outputs[i] = outputs[i] + r[a[o]]
                        sources[i] = outputs[i]
                    }
                }
                Op.AUDIOIN -> r[d] = c.input
                Op.UGEN -> r[d] = ugens[ugenIndex[k]]!!.update(c, r, a, o, len[k])
            }
        }
        if (c.fault) {
            dead = true
            c.fault = false
        }
        playPos += sampleTime
    }
}

/** `x ** y` of JavaScript: `Math.pow`, except that 1 or -1 to an infinite power is NaN there. */
internal fun jsPow(x: Double, y: Double): Double {
    if (y.isNaN()) return Double.NaN
    if (y == 0.0) return 1.0
    if ((x == 1.0 || x == -1.0) && y.isInfinite()) return Double.NaN
    return Math.pow(x, y)
}
