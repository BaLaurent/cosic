package cosic.script.mondo

import cosic.SourceRange
import cosic.pattern.CosicError
import cosic.pattern.JsFn
import cosic.pattern.MAX_COUNT
import cosic.pattern.Pattern
import cosic.pattern.Rational
import cosic.pattern.Registry
import cosic.pattern.TOO_DENSE
import cosic.pattern.Undefined
import cosic.pattern.callMethod
import cosic.pattern.jsFn
import cosic.pattern.jsLess
import cosic.pattern.jsNumberToString
import cosic.pattern.jsToNumber
import cosic.pattern.jsToPrimitive
import cosic.pattern.jsToString
import cosic.pattern.jsTruthy
import cosic.pattern.patternOf
import cosic.pattern.reify
import cosic.pattern.sequence
import cosic.pattern.silence
import cosic.pattern.stepcat
import cosic.script.Bridge
import kotlin.math.abs

/*
 * What mondo programs mean: the evaluator of `packages/mondough/mondough.mjs` (the package `@strudel/mondo`), which makes
 * the lisp of [MondoRunner] a pattern language. A name is looked up in the program's own definitions, then in `lib` (the
 * operators and the brackets), then in the global scope of the program (the functions of the library); what it finds
 * becomes a pattern, so that the *function* of a call can be patterned itself: `(s bd hh)` is a pattern whose one value is
 * the function `s`, and calling it means mapping over that pattern and joining the patterns the calls make. Strings are
 * never mini-notation here: `bd` is the pattern of the string "bd".
 *
 * The pattern functions are called through the [Registry] when they are needed, so a function registered later (or a
 * newer registration of the same name) is the one a program gets, as in the original, where the library is global.
 */

/** The names that are not made patterns of: they are called as the functions they are, and may take any argument. */
private val NON_PATTERNABLE = setOf("!", "extend", "@", "expand", "square", "angle", "all", "setcpm", "setcps")

/** The style the original gives the atoms of mondo code while they play. */
internal const val MONDO_MARK_STYLE = "color: var(--caret,--foreground);text-decoration:underline"

private fun undefinedRead(property: String): Nothing = throw CosicError("Cannot read properties of undefined (reading '$property')")

/** A function of the [Registry], called with whatever it is given when the program runs. */
private fun registryFn(name: String): JsFn = jsFn(0) { args -> Registry.call(name, args) }

/** `args[index]` as the receiver of the method [method], which a program makes it fail as a value that is not a pattern does. */
private fun receiver(args: List<Any?>, index: Int, method: String): Pattern {
    val value = args.getOrElse(index) { Undefined }
    return value as? Pattern ?: if (value === Undefined) undefinedRead(method) else throw CosicError("b.$method is not a function")
}

/** `(: y x)`: the values of `x`, each paired with what `y` is at the time: `bd:3` is the list `[bd, 3]`, and `a:b:c` is `[a, b, c]`. */
private fun tail(friend: Any?, pat: Any?): Pattern =
    patternOf(pat).fmap { a ->
        jsFn(1) { args ->
            val b = args.getOrElse(0) { Undefined }
            if (a is List<*>) a + listOf(b) else listOf(a, b)
        }
    }.appLeft(patternOf(friend))

/** `arrayRange(start, stop)`: the numbers from [start] to [stop], counting up or down in steps of one, with the arithmetic JavaScript does on whatever it is given. */
private fun arrayRange(start: Any?, stop: Any?): List<Any?> {
    val length = abs(jsToNumber(stop) - jsToNumber(start)) + 1.0
    if (length.isNaN()) return emptyList()
    if (length > MAX_COUNT) throw CosicError(TOO_DENSE)
    val ascending = jsLess(start, stop) == true
    return List(length.toInt()) { index ->
        if (ascending) {
            // `start + index`: a string start makes a string
            val primitive = jsToPrimitive(start)
            if (primitive is String) primitive + jsNumberToString(index.toDouble()) else jsToNumber(primitive) + index
        } else {
            jsToNumber(start) - index
        }
    }
}

/** `(.. max min)`: `0..3` is the sequence 0 1 2 3, for every `0` and `3` the patterns have, and backwards when the first is the larger. */
private fun range(max: Any?, min: Any?): Pattern {
    val maxPat = patternOf(max)
    return patternOf(min).squeezeBind { a -> maxPat.bind { b -> sequence(arrayRange(a, b)) } }
}

/** `lib` of mondough.mjs: the operators and the brackets, as functions (or, for `_` and `~`, patterns). */
private val LIB: Map<String, Any?> = mapOf(
    "nope" to jsFn(0) { args -> args.lastOrNull() ?: Undefined },
    "-" to jsFn(2) { args -> receiver(args, 1, "early").callMethod("early", args[0]) },
    "+" to jsFn(2) { args -> receiver(args, 1, "late").callMethod("late", args[0]) },
    "_" to silence,
    "~" to silence,
    "curly" to jsFn(0) { args -> stepcat(args) },
    "square" to jsFn(0) { args -> stepcat(args).setSteps(Rational.ONE) },
    "angle" to jsFn(0) { args -> stepcat(args).callMethod("pace", 1.0) },
    "*" to registryFn("fast"),
    "/" to registryFn("slow"),
    "!" to registryFn("replicate"),
    "@" to registryFn("expand"),
    "%" to registryFn("pace"),
    "?" to registryFn("degradeBy"),
    "&" to registryFn("bjork"),
    ":" to jsFn(2) { args -> tail(args.getOrElse(0) { Undefined }, args.getOrElse(1) { Undefined }) },
    ".." to jsFn(2) { args -> range(args.getOrElse(0) { Undefined }, args.getOrElse(1) { Undefined }) },
    "def" to jsFn(0) { silence },
    "or" to jsFn(0) { args -> Registry.call("chooseIn", args) },
)

/**
 * The evaluator of mondo programs that run in the realm of [bridge]: its global scope is where the names of the library
 * are looked up.
 */
internal class MondoStrudel(private val bridge: Bridge) : MondoEvaluator {
    /** `lib[name] ?? strudelScope[name]`, as the kernel sees it. */
    private fun lookup(name: String): Any? = LIB[name] ?: bridge.toKernel(bridge.strudelScope.get(name))

    override fun leaf(leaf: MondoLeaf, scope: MondoScope): Any? {
        val value = leaf.value
        var pat: Pattern? = null
        if (leaf.type == "plain") {
            val name = value as String
            // a name the program defined: it has no location
            val defined = scope[name]
            if (jsTruthy(defined)) return reify(defined)
            val variable = lookup(name)
            if (variable !== Undefined) {
                // some functions are not patterned, so that they work (`extend` takes its arguments as they are)
                if (name in NON_PATTERNABLE) return variable
                pat = reify(variable)
            }
        }
        if (pat == null) pat = reify(value)
        val loc = leaf.loc
        return if (loc != null) pat.withLoc(SourceRange(loc[0], loc[1])) else pat
    }

    override fun call(children: List<Any?>, scope: MondoScope): Any? {
        if (children.isEmpty()) throw CosicError("mondo: empty program")
        val name = children[0]
        val args = children.drop(1)
        // a function that was not made a pattern is called as it is
        if (name is JsFn) return name.call(args)
        // otherwise the name is a pattern of functions
        if (name !is Pattern) throw CosicError("[mondough] expected function, got \"${jsToString(name)}\"")
        val first = name.firstCycle(true).firstOrNull()
        val firstValue = if (first == null) Undefined else first.value
        if (firstValue !is JsFn) throw CosicError("[mondough] expected function, got \"${jsToString(firstValue)}\"")
        return name.fmap { fn ->
            if (fn !is JsFn) throw CosicError("[mondough] \"${jsToString(fn)}\" is not a function b")
            val result = fn.call(args)
            result as? Pattern ?: throw CosicError("[mondough] \"${jsToString(result)}\" is not a pattern")
        }.innerJoin()
    }

    /** The pattern [code] is, written in mondo; [offset] is where it is in the program (for the locations of its atoms). */
    fun run(code: String, offset: Int): Pattern {
        val result = MondoRunner(this).run(code, HashMap(), offset)
        return result as? Pattern ?: throw CosicError("mondo: expected a pattern")
    }
}
