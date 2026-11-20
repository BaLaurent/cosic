package cosic.mini

import cosic.SourceRange
import cosic.pattern.CosicError
import cosic.pattern.MAX_COUNT
import cosic.pattern.Pattern
import cosic.pattern.Rational
import cosic.pattern.TOO_DENSE
import cosic.pattern.Undefined
import cosic.pattern._degradeByWith
import cosic.pattern._fast
import cosic.pattern._slow
import cosic.pattern.callMethod
import cosic.pattern.chooseInWith
import cosic.pattern.fastcat
import cosic.pattern.jsFn
import cosic.pattern.jsStringToNumber
import cosic.pattern.jsToNumber
import cosic.pattern.jsTruthy
import cosic.pattern.miniRand
import cosic.pattern.pure
import cosic.pattern.silence
import cosic.pattern.stack
import cosic.pattern.stepcat
import kotlin.math.abs
import kotlin.math.floor

/*
 * The conversion of the tree to patterns, `patternifyAST` of packages/mini/mini.mjs. It follows that function step by step,
 * since what the patterns do (where a step's location ends up, how many steps a pattern has, which pattern is queried
 * how) is what the parity snapshots test. The only things a JavaScript pattern carries that [Pattern] does not are the
 * `__weight` of a sequence and its `__steps_source` flag: [Compiled] carries those.
 */

/** A pattern, and what the conversion remembers about it: the sum of the weights of the sequence it is (used by polymeters), and whether `^` marked it as a source of steps. */
internal class Compiled(val pat: Pattern, val weight: Rational? = null, val stepsSource: Boolean = false)

/**
 * `patternifyAST(ast, code, onEnter, offset)`: the pattern of [ast], the tree of the quoted notation [code]. Every atom
 * carries its source range in the program, found with [offset] (the position of the opening quote); an [offset] of -1
 * leaves the locations out. [onEnter] is told about every node in the order the conversion visits it.
 */
internal fun patternifyAST(ast: MiniNode, code: String, onEnter: ((MiniNode) -> Unit)? = null, offset: Int = 0): Pattern =
    Patternifier(code, onEnter, if (offset == -1) null else offset).enter(ast).pat

/**
 * The pattern of [ast] with the source ranges of its atoms counted from [offset], which is the position of the opening
 * quote of [code] in the program. (Unlike [patternifyAST], where that is -1, this takes any offset.)
 */
internal fun patternifyLocated(ast: MiniNode, code: String, offset: Int): Pattern = Patternifier(code, null, offset).enter(ast).pat

/** [offset] is null when the atoms are not to carry their locations. */
private class Patternifier(private val code: String, private val onEnter: ((MiniNode) -> Unit)?, private val offset: Int?) {
    fun enter(node: MiniNode): Compiled {
        onEnter?.invoke(node)
        return when (node) {
            is PatternNode -> pattern(node)
            is ElementNode -> enter(node.source)
            is AtomNode -> atom(node)
        }
    }

    // ---- atoms ----------------------------------------------------------------------------------------------------

    private fun atom(node: AtomNode): Compiled {
        if (node.source == "~" || node.source == "-") return Compiled(silence)
        val number = jsStringToNumber(node.source)
        val value: Any = if (!number.isNaN()) number else node.source
        if (offset == null) return Compiled(pure(value))
        val (from, to) = getLeafLocation(code, node, offset)
        return Compiled(pure(value).withLoc(SourceRange(from, to)))
    }

    // ---- sequences, stacks and choices ----------------------------------------------------------------------------

    private fun pattern(ast: PatternNode): Compiled {
        val children = ast.source.map { enter(it) }.mapIndexed { i, child -> applyOptions(ast, i, child) }
        val withSteps = children.filter { it.stepsSource }
        val pat: Pattern
        var weight: Rational? = null
        var stepsSource = false
        when (ast.alignment) {
            Alignment.STACK -> {
                pat = stack(children.map { it.pat }).withStepsOf(withSteps)
            }
            Alignment.POLYMETER_SLOWCAT -> {
                pat = stack(children.map { it.pat._slow(it.weight ?: Undefined) }).withStepsOf(withSteps)
            }
            Alignment.POLYMETER -> {
                val stepsPerCycle = ast.stepsPerCycle?.let { enter(it).pat.fmap { x -> Rational.of(x) } }
                    ?: pure(if (children.isNotEmpty()) Rational.of(children[0].weight ?: Undefined) else Rational.ONE)
                pat = stack(children.map { child ->
                    child.pat.callMethod("fast", stepsPerCycle.fmap { x -> (x as Rational) / Rational.of(child.weight ?: Undefined) })
                })
            }
            Alignment.RAND -> {
                val choice = miniRand(ast.seed ?: 0).callMethod("segment", 1.0)
                pat = chooseInWith(choice, children.map { it.pat }).withStepsOf(withSteps)
            }
            Alignment.FEET -> {
                pat = fastcat(children.map { it.pat })
            }
            Alignment.FASTCAT -> {
                val weights = ast.source.map { (it as ElementNode).weight }
                if (weights.any { jsTruthy(it) }) {
                    // a weight of 0 counts as 1: `child.options_?.weight || Fraction(1)`
                    val effective = weights.map { if (jsTruthy(it)) it else 1.0 }
                    val weightSum = effective.fold(Rational.ZERO) { sum, w -> sum + Rational.of(w) }
                    var steps = weightSum
                    if (withSteps.isNotEmpty()) steps *= lcmOfSteps(withSteps)
                    pat = stepcat(effective.mapIndexed { i, w -> listOf(w, children[i].pat) }).setSteps(steps)
                    weight = weightSum
                } else {
                    pat = fastcat(children.map { it.pat }).setSteps(Rational(children.size.toLong()))
                }
                if (ast.stepsFlag) stepsSource = true
            }
        }
        if (withSteps.isNotEmpty()) stepsSource = true
        return Compiled(pat, weight, stepsSource)
    }

    /** What `pat._steps = lcm(...)` does when some of the children are sources of steps: the pattern then has the least common multiple of their steps. */
    private fun Pattern.withStepsOf(withSteps: List<Compiled>): Pattern =
        if (withSteps.isEmpty()) this else setSteps(lcmOfSteps(withSteps))

    private fun lcmOfSteps(withSteps: List<Compiled>): Rational =
        Rational.lcmOf(withSteps.map { Rational.of(it.pat.steps ?: Undefined) })!!

    // ---- operators ------------------------------------------------------------------------------------------------

    /** `applyOptions`: the operators of child [i] of [parent], applied to its pattern in the order they were written. */
    private fun applyOptions(parent: PatternNode, i: Int, child: Compiled): Compiled {
        val element = parent.source[i] as? ElementNode ?: return child
        if (element.ops.isEmpty()) return child
        var pat = child.pat
        for (op in element.ops) pat = apply(op, pat)
        // every operator makes a new pattern, which is not a weighted sequence; the mark of a source of steps stays
        return Compiled(pat, null, child.stepsSource)
    }

    private fun apply(op: Op, pat: Pattern): Pattern = when (op) {
        is Op.Stretch -> pat.callMethod(if (op.fast) "fast" else "slow", enter(op.amount).pat)
        is Op.Replicate -> pat.callMethod("repeatCycles", op.amount)._fast(op.amount)
        is Op.Bjorklund -> {
            val pulse = enter(op.pulse).pat
            val step = enter(op.step).pat
            val rotation = op.rotation
            if (rotation != null) pat.callMethod("euclidRot", pulse, step, enter(rotation).pat) else pat.callMethod("euclid", pulse, step)
        }
        is Op.DegradeBy -> pat._degradeByWith(miniRand(op.seed), op.amount ?: 0.5)
        is Op.Tail -> {
            val friend = enter(op.element).pat
            pat.fmap { a ->
                jsFn(1) { b ->
                    if (a is List<*>) ArrayList<Any?>(a).also { it.add(b[0]) } else listOf(a, b[0])
                }
            }.appLeft(friend)
        }
        is Op.Range -> {
            val friend = enter(op.element).pat
            pat.squeezeBind { a -> friend.bind { b -> fastcat(arrayRange(a, b)) } }
        }
    }
}

/**
 * `arrayRange(start, stop)` of mini.mjs: the numbers from [start] to [stop] counting by one, up or down. Anything that is
 * not a number makes an empty list, as it does there (the length is NaN).
 */
private fun arrayRange(start: Any?, stop: Any?): List<Any?> {
    val from = jsToNumber(start)
    val to = jsToNumber(stop)
    val length = abs(to - from) / 1.0 + 1.0
    if (length.isNaN()) return emptyList()
    val count = floor(length)
    if (count > 4294967295.0) throw CosicError("Invalid array length")
    if (count > MAX_COUNT) throw CosicError(TOO_DENSE)
    return List(count.toInt()) { i -> if (from < to) from + i * 1.0 else from - i * 1.0 }
}
