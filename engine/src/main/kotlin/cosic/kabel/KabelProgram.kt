package cosic.kabel

/** The operations of a compiled program: what the lines of the JavaScript that kabelsalat's compiler writes do, one per node. */
internal object Op {
    const val THRU = 0
    const val TIME = 1
    const val ADD = 2
    const val MUL = 3
    const val SUB = 4
    const val DIV = 5
    const val MOD = 6
    const val POW = 7
    const val LOG = 8
    const val EXP = 9
    const val SIN = 10
    const val COS = 11
    const val TAN = 12
    const val ASIN = 13
    const val ACOS = 14
    const val ATAN = 15
    const val ABS = 16
    const val FLOOR = 17
    const val CEIL = 18
    const val ROUND = 19
    const val SIGN = 20
    const val CLAMP = 21
    const val MIN = 22
    const val MAX = 23
    const val ARGMIN = 24
    const val ARGMAX = 25
    const val GT = 26
    const val LT = 27
    const val XOR = 28
    const val AND = 29
    const val OR = 30
    const val NOT = 31
    const val BOOL = 32
    const val IFELSE = 33
    const val RANGE = 34
    const val RANGE_CURVE = 35
    const val MIDINOTE = 36
    const val SRC = 37
    const val OUT = 38
    const val AUDIOIN = 39
    const val UGEN = 40
}

/** One input of a node as the compiler's `vars` hold it (compiler.js:16-24): the number or string a constant inlines, or the register of a computed node. */
internal sealed class UgenInput {
    class Num(val value: Double) : UgenInput()
    class Str(val value: String) : UgenInput()
    class Reg(val index: Int) : UgenInput()

    /** `undefined`: the node has fewer inputs than the ugen takes, or the input is a constant without a value. */
    data object Missing : UgenInput()
}

/** A ugen of the program (`{type, inputs}` of compiler.js:56): the class of the runtime object and the inputs its node had. */
internal class UgenSpec(val type: String, val inputs: List<UgenInput>)

/**
 * A compiled kabel graph: the registers and the operations of one sample, in the order of the topological sort. Immutable and shared by all voices
 * that run the same program; the state of a voice is a [KabelInstance].
 *
 * Register `i` belongs to node `i` of the sort; constants are preloaded into theirs, and the defaults of the inputs a node does not have
 * are registers added after the nodes. Operation `k` computes `opCode[k]` of the registers `args[opArgOff[k] until opArgOff[k] + opArgLen[k]]` into
 * `opDst[k]`; [Op.UGEN] calls ugen `opUgen[k]`.
 *
 * [loadFault] is a failure that kabelsalat's worklet has when it loads the program (code that does not parse, a gate whose name is not text): the voice is
 * silent. [loadFaultAt] is how many of the [ugens] the worklet had made by then.
 */
internal class KabelProgram(
    val source: String,
    val nodeCount: Int,
    val regInit: DoubleArray,
    val opCode: IntArray,
    val opDst: IntArray,
    val opArgOff: IntArray,
    val opArgLen: IntArray,
    val opUgen: IntArray,
    val args: IntArray,
    val ugens: List<UgenSpec>,
    val delayLens: IntArray,
    val gateCc: IntArray,
    val outputSlots: Int,
    val loadFault: String?,
    val loadFaultAt: Int,
    val memoryBytes: Long,
) {
    val opCount: Int get() = opCode.size
}
