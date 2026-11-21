package cosic.kabel

import cosic.pattern.jsStringToNumber
import java.util.Collections
import java.util.IdentityHashMap
import kotlin.math.max

/** The most nodes a graph may have: a program that builds more than this is a runaway loop, not music. */
internal const val MAX_NODES = 50_000

/** The most memory one voice of a program may take (delay lines, mostly). */
internal const val MAX_INSTANCE_BYTES = 32L * 1024 * 1024

private const val MAX_OUTPUT_SLOTS = 4096
private const val MIN_OUTPUT_SLOTS = 16
private const val MAX_DELAY_SECONDS = 10

/**
 * `topoSort` of compiler.js:75-90: the nodes reachable from [root], each after all its inputs (a depth-first post-order over `ins`, in order, so a loop
 * of feedback is cut where the walk meets a node it is already inside of). Done with an explicit stack: graphs of thousands of nodes are chains.
 */
internal fun topoSort(root: KNode): List<KNode> {
    val sorted = ArrayList<KNode>()
    val visited = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
    val stack = ArrayList<KNode>()
    val next = ArrayList<Int>()
    fun push(x: Any?) {
        if (x is KNode && visited.add(x)) {
            stack.add(x)
            next.add(0)
        }
    }
    push(root)
    while (stack.isNotEmpty()) {
        val top = stack.size - 1
        val node = stack[top]
        val i = next[top]
        val items = node.inputs
        if (i < items.size) {
            next[top] = i + 1
            push(items[i])
        } else {
            sorted.add(node)
            stack.removeAt(top)
            next.removeAt(top)
        }
    }
    return sorted
}

/** The runtime class of a ugen node type and the inputs it is called with when the node has fewer (the destructuring defaults of the compile lambdas of lib.js). */
private class UgenKind(val cls: String, val defaults: DoubleArray, val allInputs: Boolean = false)

private val UGEN_KINDS: Map<String, UgenKind> = mapOf(
    "adsr" to UgenKind("ADSRNode", doubleArrayOf(0.0, 0.02, 0.1, 0.2, 0.1)),
    "clock" to UgenKind("Clock", doubleArrayOf(120.0)),
    "clockdiv" to UgenKind("ClockDiv", doubleArrayOf(0.0, 2.0)),
    "distort" to UgenKind("Distort", doubleArrayOf(0.0, 0.0)),
    "noise" to UgenKind("NoiseOsc", doubleArrayOf(1.0)),
    "lcgnoise" to UgenKind("LcgNoise", doubleArrayOf(1.0, 0.0)),
    "pink" to UgenKind("PinkNoise", doubleArrayOf()),
    "brown" to UgenKind("BrownNoiseOsc", doubleArrayOf()),
    "dust" to UgenKind("DustOsc", doubleArrayOf(0.0)),
    "impulse" to UgenKind("ImpulseOsc", doubleArrayOf(0.0, 0.0)),
    "saw" to UgenKind("SawOsc", doubleArrayOf(0.0)),
    "zaw" to UgenKind("ZawOsc", doubleArrayOf(0.0)),
    "sine" to UgenKind("SineOsc", doubleArrayOf(0.0, 0.0, 0.0)),
    "tri" to UgenKind("TriOsc", doubleArrayOf(0.0)),
    "pulse" to UgenKind("PulseOsc", doubleArrayOf(0.0, 0.5)),
    "slide" to UgenKind("Slide", doubleArrayOf(0.0, 1.0)),
    "lag" to UgenKind("Lag", doubleArrayOf(0.0, 1.0)),
    "slew" to UgenKind("Slew", doubleArrayOf(0.0, 1.0, 1.0)),
    "filter" to UgenKind("Filter", doubleArrayOf(0.0, 1.0, 0.0)),
    "bpf" to UgenKind("BPF", doubleArrayOf(0.0, 1.0, 0.0)),
    "fold" to UgenKind("Fold", doubleArrayOf(0.0, 0.0)),
    "seq" to UgenKind("Sequence", doubleArrayOf(), allInputs = true),
    "delay" to UgenKind("Delay", doubleArrayOf(0.0, 0.0)),
    "hold" to UgenKind("Hold", doubleArrayOf(0.0, 0.0)),
    "midifreq" to UgenKind("MidiFreq", doubleArrayOf()),
    "midigate" to UgenKind("MidiGate", doubleArrayOf()),
    "midivel" to UgenKind("MidiVel", doubleArrayOf()),
    "midicc" to UgenKind("MidiCC", doubleArrayOf()),
    "cc" to UgenKind("CC", doubleArrayOf()),
    "remap" to UgenKind("Remap", doubleArrayOf(0.0, -1.0, 1.0, -1.0, 1.0)),
    "pick" to UgenKind("Pick", doubleArrayOf(), allInputs = true),
    "clip" to UgenKind("Clip", doubleArrayOf(0.0, -1.0, 1.0)),
    "trig" to UgenKind("Trig", doubleArrayOf(0.0, -1.0, 1.0)),
    "qf" to UgenKind("BiquadFilter", doubleArrayOf(0.0, 0.0, 500.0, 1.0, 1.0)),
)

/** An input of a node: the register it is read from and what compiler.js's `vars` holds for it. */
private class V(val reg: Int, val raw: UgenInput, val number: Double?)

/**
 * Compiles a kabel graph (`compile` of compiler.js plus the `compile` lambdas of lib.js, which write one line of JavaScript per node) into a
 * [KabelProgram]: the same computation as those lines, done by an interpreter of operations over a register file. Where the JavaScript is
 * `a * b * c` the operation is a left fold; where a node has fewer inputs than its lambda destructures, the default of the lambda is a constant register.
 */
internal class KabelCompiler(private val kr: KabelRealm, private val sampleRate: Int) {
    /** Evaluates [source] (a kabel program) and compiles its graph. */
    fun compile(source: String): KabelProgram = Build(source, kr.evaluate(source)).run()

    private inner class Build(val source: String, exit: KNode) {
        val nodes: List<KNode> = topoSort(exit)
        val index = IdentityHashMap<Any, Int>()
        val regInit = ArrayList<Double>()
        val constRegs = HashMap<Long, Int>()
        val opCode = ArrayList<Int>()
        val opDst = ArrayList<Int>()
        val opArgOff = ArrayList<Int>()
        val opArgLen = ArrayList<Int>()
        val opUgen = ArrayList<Int>()
        val args = ArrayList<Int>()
        val specs = ArrayList<UgenSpec>()
        val delayLens = ArrayList<Int>()
        val gates = ArrayList<Int>()
        val isBool = BooleanArray(nodes.size)
        var maxOutput = MIN_OUTPUT_SLOTS - 1
        var loopFault: String? = null
        var loopFaultAt = -1
        var syntaxFault: String? = null

        fun run(): KabelProgram {
            if (nodes.size > MAX_NODES) throw KabelError("the graph has more than $MAX_NODES nodes")
            for ((i, node) in nodes.withIndex()) {
                index[node] = i
                regInit.add(0.0)
            }
            // constants are inlined by compiler.js: their register holds the value where the operations read it
            for ((i, node) in nodes.withIndex()) if (node.type == CONST) regInit[i] = constValue(node.value)
            for ((i, node) in nodes.withIndex()) emit(i, node, node.inputs.map { varOf(it) })

            val memory = regInit.size * 8L + delayLens.sumOf { it * 4L } + specs.size * 64L
            if (memory > MAX_INSTANCE_BYTES) throw KabelError("the program needs more than ${MAX_INSTANCE_BYTES / (1024 * 1024)} MiB per voice")
            val fault = loopFault ?: syntaxFault
            return KabelProgram(
                source, nodes.size, regInit.toDoubleArray(), opCode.toIntArray(), opDst.toIntArray(), opArgOff.toIntArray(), opArgLen.toIntArray(),
                opUgen.toIntArray(), args.toIntArray(), specs, delayLens.toIntArray(), gates.toIntArray(), max(MIN_OUTPUT_SLOTS, maxOutput + 1),
                fault, if (loopFault != null) loopFaultAt else specs.size, memory,
            )
        }

        private fun constValue(value: Any?): Double = when (value) {
            is Double -> if (value == 0.0) 0.0 else value
            is String -> jsStringToNumber(value)
            is Boolean -> if (value) 1.0 else 0.0
            else -> Double.NaN
        }

        /** `v(nodes.indexOf(inlet))` (compiler.js:16-24); null for a constant without a value, which reads as `undefined` and takes the default. */
        fun varOf(inlet: Any?): V? {
            val idx = index[inlet] ?: throw KabelError("Cannot read properties of undefined (reading 'type')")
            val n = nodes[idx]
            if (n.type != CONST) return V(idx, UgenInput.Reg(idx), null)
            return when (val value = n.value) {
                is Double -> V(idx, UgenInput.Num(if (value == 0.0) 0.0 else value), if (value == 0.0) 0.0 else value)
                is String -> {
                    // `"${value}"` is not escaped: a quote or a line break ends the literal
                    if (value.any { it == '"' || it == '\n' || it == '\r' || it == '\u2028' || it == '\u2029' }) fault("SyntaxError: Unexpected identifier")
                    V(idx, UgenInput.Str(value), null)
                }
                is Boolean -> V(idx, UgenInput.Num(if (value) 1.0 else 0.0), if (value) 1.0 else 0.0)
                else -> null
            }
        }

        // ---- registers and operations ---------------------------------------------------------------------------------

        fun constReg(value: Double): Int {
            val key = if (value.isNaN()) 0x7ff8000000000000L else java.lang.Double.doubleToRawLongBits(if (value == 0.0) 0.0 else value)
            return constRegs.getOrPut(key) {
                regInit.add(value)
                regInit.size - 1
            }
        }

        /** The register of input [i], [default] when the node has none. */
        fun input(v: List<V?>, i: Int, default: Double): Int = v.getOrNull(i)?.reg ?: constReg(default)

        /** The register of input [i] where the JavaScript has no default: a missing input is `undefined`, which computes to NaN. */
        fun input(v: List<V?>, i: Int): Int = input(v, i, Double.NaN)

        fun op(code: Int, dst: Int, vararg regs: Int, ugen: Int = -1) {
            opCode.add(code)
            opDst.add(dst)
            opArgOff.add(args.size)
            opArgLen.add(regs.size)
            opUgen.add(ugen)
            for (r in regs) args.add(r)
        }

        fun opList(code: Int, dst: Int, regs: List<Int>) {
            opCode.add(code)
            opDst.add(dst)
            opArgOff.add(args.size)
            opArgLen.add(regs.size)
            opUgen.add(-1)
            args.addAll(regs)
        }

        fun refIsBool(v: V?): Boolean = v != null && v.raw is UgenInput.Reg && isBool[(v.raw as UgenInput.Reg).index]

        fun fault(message: String) {
            if (syntaxFault == null) syntaxFault = message
        }

        fun thru(i: Int, v: List<V?>) {
            op(Op.THRU, i, input(v, 0))
            isBool[i] = refIsBool(v.getOrNull(0))
        }

        /** A register that no node owns, for what an operation computes on the way. */
        fun temp(): Int {
            regInit.add(0.0)
            return regInit.size - 1
        }

        /**
         * `vars.join(" op ")`: an undefined operand (a constant without a value) is empty text. The only operand empty is `"" || 0`; a last operand empty is a
         * syntax error, and so is any empty one before `*` or `/`; before `+` and `-` the operator that follows is a unary one (`a + + b`, `a - - b`), which
         * for `-` negates the operand after each empty one. A single operand is passed through as it is.
         */
        fun variadic(code: Int, i: Int, v: List<V?>, joined: Boolean = true) {
            if (v.isEmpty()) {
                op(Op.THRU, i, constReg(0.0))
                return
            }
            if (joined && v.any { it == null }) {
                when {
                    v.size == 1 -> op(Op.THRU, i, constReg(0.0))
                    v.last() == null || code == Op.MUL || code == Op.DIV -> {
                        fault("SyntaxError: Unexpected token")
                        op(Op.THRU, i, constReg(Double.NaN))
                    }
                    code == Op.ADD -> opList(Op.ADD, i, v.filterNotNull().map { it.reg })
                    else -> {
                        val terms = ArrayList<Int>()
                        var previous = -1
                        for (k in v.indices) {
                            val operand = v[k] ?: continue
                            val unary = if (previous == -1) k else k - previous - 1 // the operators before it that are unary
                            if (unary % 2 == 1) {
                                val negated = temp()
                                op(Op.SUB, negated, constReg(0.0), operand.reg)
                                terms.add(negated)
                            } else {
                                terms.add(operand.reg)
                            }
                            previous = k
                        }
                        opList(Op.SUB, i, terms)
                    }
                }
                return
            }
            opList(code, i, v.indices.map { input(v, it) })
            if (v.size == 1) isBool[i] = refIsBool(v[0])
        }

        fun math(code: Int, i: Int, v: List<V?>) = op(code, i, input(v, 0, 0.0))

        // ---- one node ----------------------------------------------------------------------------------------------------

        fun emit(i: Int, node: KNode, v: List<V?>) {
            val type = node.type
            val kind = UGEN_KINDS[type]
            if (kind != null) {
                emitUgen(i, type, kind, v)
                return
            }
            when (type) {
                CONST, EXIT -> Unit
                "time" -> op(Op.TIME, i)
                "thru", POLY -> thru(i, v)
                "add" -> variadic(Op.ADD, i, v)
                "mul" -> variadic(Op.MUL, i, v)
                "sub" -> variadic(Op.SUB, i, v)
                "div" -> variadic(Op.DIV, i, v)
                "mix" -> {
                    // `(${vars.join(" + ")})`: empty parentheses, or an empty operand, do not parse
                    if (v.isEmpty() || v.any { it == null }) {
                        fault("SyntaxError: Unexpected token ')'")
                        op(Op.THRU, i, constReg(Double.NaN))
                    } else {
                        variadic(Op.ADD, i, v)
                    }
                }
                "mod" -> op(Op.MOD, i, input(v, 0), input(v, 1))
                "pow" -> {
                    // `${input} ** ${power}` is not valid JavaScript when the input is a negative constant
                    if ((v.getOrNull(0)?.number ?: 0.0) < 0.0) fault("SyntaxError: Unary operator used immediately before exponentiation expression. Parenthesis must be used to disambiguate operator precedence")
                    op(Op.POW, i, input(v, 0, 0.0), input(v, 1, 1.0))
                }
                "log" -> math(Op.LOG, i, v)
                "exp" -> math(Op.EXP, i, v)
                "sin" -> math(Op.SIN, i, v)
                "cos" -> math(Op.COS, i, v)
                "tan" -> math(Op.TAN, i, v)
                "asin" -> math(Op.ASIN, i, v)
                "acos" -> math(Op.ACOS, i, v)
                "atan" -> math(Op.ATAN, i, v)
                "abs" -> math(Op.ABS, i, v)
                "floor" -> math(Op.FLOOR, i, v)
                "ceil" -> math(Op.CEIL, i, v)
                "round" -> math(Op.ROUND, i, v)
                "sign" -> math(Op.SIGN, i, v)
                "clamp" -> op(Op.CLAMP, i, input(v, 0, 0.0), input(v, 1, -1.0), input(v, 2, 1.0))
                "min", "max", "argmin", "argmax" -> {
                    if (v.isEmpty()) throw KabelError("Reduce of empty array with no initial value")
                    variadic(
                        when (type) {
                            "min" -> Op.MIN
                            "max" -> Op.MAX
                            "argmin" -> Op.ARGMIN
                            else -> Op.ARGMAX
                        },
                        i, v, joined = false,
                    )
                }
                "greater" -> {
                    op(Op.GT, i, input(v, 0, 0.0), input(v, 1, 0.0))
                    isBool[i] = true
                }
                "lower" -> {
                    op(Op.LT, i, input(v, 0, 0.0), input(v, 1, 0.0))
                    isBool[i] = true
                }
                "xor" -> op(Op.XOR, i, input(v, 0, 0.0), input(v, 1, 0.0))
                "and" -> op(Op.AND, i, input(v, 0, 0.0), input(v, 1, 0.0))
                "or" -> op(Op.OR, i, input(v, 0, 0.0), input(v, 1, 0.0))
                // a comparison stores a JavaScript boolean, which is never `=== 0` or `=== 1`
                "not" -> if (refIsBool(v.getOrNull(0))) op(Op.THRU, i, constReg(0.0)) else op(Op.NOT, i, input(v, 0, 0.0))
                "bool" -> if (refIsBool(v.getOrNull(0))) op(Op.THRU, i, constReg(1.0)) else op(Op.BOOL, i, input(v, 0, 0.0))
                "ifelse" -> {
                    if (refIsBool(v.getOrNull(0))) {
                        op(Op.THRU, i, input(v, 2, 0.0))
                    } else {
                        op(Op.IFELSE, i, input(v, 0, 0.0), input(v, 1, 0.0), input(v, 2, 0.0))
                    }
                }
                "range" -> {
                    val curve = v.size > 3 && v[3]?.number != 1.0
                    if (curve) op(Op.RANGE_CURVE, i, input(v, 0), input(v, 1), input(v, 2), input(v, 3)) else op(Op.RANGE, i, input(v, 0), input(v, 1), input(v, 2))
                }
                "midinote" -> op(Op.MIDINOTE, i, input(v, 0))
                "src" -> op(Op.SRC, i, input(v, 0, 0.0))
                "output" -> {
                    specs.add(UgenSpec("Output", v.map { it?.raw ?: UgenInput.Missing }))
                    delayLens.add(0)
                    val id = v.getOrNull(1)?.number
                    if (id != null && id >= 0 && id == Math.floor(id) && id < MAX_OUTPUT_SLOTS) maxOutput = max(maxOutput, id.toInt())
                    op(Op.OUT, i, input(v, 0), input(v, 1, 0.0))
                }
                "audioin" -> {
                    specs.add(UgenSpec("AudioIn", v.map { it?.raw ?: UgenInput.Missing }))
                    delayLens.add(0)
                    op(Op.AUDIOIN, i)
                }
                // `raw` and the beats compile to a `const r[i] = ...` that does not parse in the worklet
                "raw", "bytebeat" -> {
                    fault("SyntaxError: Identifier 'r' has already been declared")
                    op(Op.THRU, i, constReg(Double.NaN))
                }
                else -> {
                    kr.logOnce("unhandled node type \"$type\". falling back to \"thru\"")
                    thru(i, v)
                }
            }
        }

        fun emitUgen(i: Int, type: String, kind: UgenKind, v: List<V?>) {
            val spec = specs.size
            specs.add(UgenSpec(kind.cls, v.map { it?.raw ?: UgenInput.Missing }))
            delayLens.add(0)
            when (type) {
                "cc" -> {
                    val first = v.getOrNull(0)?.raw
                    if (first is UgenInput.Num) {
                        // `ugen.inputs?.[0]?.includes('strudel-gate')` on a number: the worklet throws while it makes its ugens
                        if (loopFault == null) {
                            loopFault = "TypeError: ugen.inputs?.[0]?.includes is not a function"
                            loopFaultAt = spec
                        }
                    } else if (first is UgenInput.Str && first.value.contains("strudel-gate")) {
                        gates.add(spec)
                    }
                    op(Op.UGEN, i, ugen = spec)
                }
                "midifreq", "midigate", "midivel", "midicc", "pink", "brown" -> op(Op.UGEN, i, ugen = spec)
                else -> {
                    val regs = ArrayList<Int>()
                    if (kind.allInputs) {
                        if (v.isEmpty()) regs.add(constReg(Double.NaN)) else for (k in v.indices) regs.add(input(v, k))
                    } else {
                        for (k in kind.defaults.indices) regs.add(input(v, k, kind.defaults[k]))
                    }
                    opCode.add(Op.UGEN)
                    opDst.add(i)
                    opArgOff.add(args.size)
                    opArgLen.add(regs.size)
                    opUgen.add(spec)
                    args.addAll(regs)
                    if (type == "delay") delayLens[spec] = delayLength(v)
                }
            }
        }

        /**
         * The ring of a delay: for a time that is a constant, `floor(sr * t) + 2` frames read exactly what the ten seconds of the original ring read; a time
         * that is computed, negative or ten seconds or more, and a delay with an id (whose line is shared and cloned), use the whole ten seconds.
         */
        fun delayLength(v: List<V?>): Int {
            val full = MAX_DELAY_SECONDS * sampleRate
            if (v.size >= 3) return full
            val timeInput = v.getOrNull(1)
            val t = if (timeInput == null) 0.0 else timeInput.number ?: return full
            if (!(t >= 0.0 && t < MAX_DELAY_SECONDS)) return full
            return (Math.floor(sampleRate * t).toInt() + 2).coerceIn(2, full)
        }
    }
}
