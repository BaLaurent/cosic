package cosic.mini

/*
 * The tree the mini-notation parser builds, the same one Strudel's generated parser (packages/mini/krill.pegjs) builds:
 * `AtomStub`, `PatternStub` and `ElementStub` there, and the operators (`ops`) an element carries.
 */

internal sealed class MiniNode

/**
 * A word, a number or a rest: [source] is its text. [start] and [end] are the range of the whole `step` rule that read
 * it, whitespace on both sides included, in the coordinates of the quoted notation (`"` + text + `"`), so the first
 * character of the text is at 1. [MiniLocations] turns that into the range of the text itself.
 */
internal class AtomNode(val source: String, val start: Int, val end: Int) : MiniNode()

/** How the sequences of a [PatternNode] are put together. */
internal enum class Alignment {
    /** A sequence: the elements one after the other. */
    FASTCAT,

    /** `a, b`: at the same time. */
    STACK,

    /** `a | b`: one of them, chosen at random each cycle. */
    RAND,

    /** `a . b`: the sequences are the elements of a sequence. */
    FEET,

    /** `{a b, c d e}`: every sequence at the pace of the first one. */
    POLYMETER,

    /** `<a b, c d e>`: every sequence plays one element per cycle. */
    POLYMETER_SLOWCAT,
}

/**
 * Elements put together. For [Alignment.FASTCAT] and the polymeters the [source] are [ElementNode]s, for the others
 * they are the sequences (`PatternNode`s). [seed] is the number of the random stream of `|`; [stepsFlag] is the `^` that
 * opens a sequence; [stepsPerCycle] the `%` argument of a polymeter.
 */
internal class PatternNode(
    val source: List<MiniNode>,
    var alignment: Alignment,
    val seed: Int?,
    val stepsFlag: Boolean,
) : MiniNode() {
    var stepsPerCycle: MiniNode? = null
}

/** A slice with the operators written after it: [weight] and [reps] are the numbers the `@`, `_` and `!` made. */
internal class ElementNode(val source: MiniNode) : MiniNode() {
    var weight: Double = 1.0
    var reps: Double = 1.0
    val ops = ArrayList<Op>()
}

/** What is applied to an element, in the order it was written. */
internal sealed class Op {
    /** `*x` and `/x`. */
    class Stretch(val fast: Boolean, val amount: MiniNode) : Op()

    /** `!n`, `!!`: [amount] is the number of copies. */
    class Replicate(val amount: Double) : Op()

    /** `(pulses, steps, rotation)`: the arguments are elements whose own operators are ignored. */
    class Bjorklund(val pulse: ElementNode, val step: ElementNode, val rotation: ElementNode?) : Op()

    /** `?` and `?0.3`: [seed] is the number of the random stream. */
    class DegradeBy(val amount: Double?, val seed: Int) : Op()

    /** `a:b`. */
    class Tail(val element: MiniNode) : Op()

    /** `a .. b`. */
    class Range(val element: MiniNode) : Op()
}
