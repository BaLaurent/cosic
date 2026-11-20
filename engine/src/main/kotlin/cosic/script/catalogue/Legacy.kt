package cosic.script.catalogue

import cosic.pattern.ArithOp
import cosic.pattern.ControlMap
import cosic.pattern.CosicError
import cosic.pattern.JsFn
import cosic.pattern.MAX_COUNT
import cosic.pattern.Pattern
import cosic.pattern.Rational
import cosic.pattern.Registry
import cosic.pattern.arith
import cosic.pattern.asControlMap
import cosic.pattern.fastcat
import cosic.pattern.jsFn
import cosic.pattern.jsToString
import cosic.pattern.legacy.chunk
import cosic.pattern.legacy.cosine
import cosic.pattern.legacy.degradeBy
import cosic.pattern.legacy.early
import cosic.pattern.legacy.euclid
import cosic.pattern.legacy.euclidRot
import cosic.pattern.legacy.every
import cosic.pattern.legacy.fast
import cosic.pattern.legacy.inside
import cosic.pattern.legacy.irand
import cosic.pattern.legacy.isaw
import cosic.pattern.legacy.iter
import cosic.pattern.legacy.iterBack
import cosic.pattern.legacy.lastOf
import cosic.pattern.legacy.late
import cosic.pattern.legacy.legacyNoteToMidi
import cosic.pattern.legacy.legacyStack
import cosic.pattern.legacy.mask
import cosic.pattern.legacy.off
import cosic.pattern.legacy.outside
import cosic.pattern.legacy.perlin
import cosic.pattern.legacy.ply
import cosic.pattern.legacy.rand
import cosic.pattern.legacy.rev
import cosic.pattern.legacy.saw
import cosic.pattern.legacy.scaleNote
import cosic.pattern.legacy.segment
import cosic.pattern.legacy.sine
import cosic.pattern.legacy.slow
import cosic.pattern.legacy.someCyclesBy
import cosic.pattern.legacy.sometimesBy
import cosic.pattern.legacy.splitWholes
import cosic.pattern.legacy.square
import cosic.pattern.legacy.struct
import cosic.pattern.legacy.superimpose
import cosic.pattern.legacy.swingBy
import cosic.pattern.legacy.timecat
import cosic.pattern.legacy.tri
import cosic.pattern.pure
import cosic.pattern.reify
import cosic.pattern.reg1
import cosic.pattern.reg2
import cosic.pattern.reg3
import cosic.pattern.reg4
import cosic.pattern.silence
import cosic.pattern.slowcat
import cosic.pattern.soundControl
import cosic.pattern.withControls
import cosic.script.js.JsArray
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * Every function of the language Cosic had before it ran Strudel's (the old `BuiltinCatalogue`), registered in the one
 * catalogue so that the interpreter serves the app's current programs. They are registered first, so every function of
 * the parity plan replaces the entry of the same name; Task 2.12 deletes this file. Behaviour, keys and messages are
 * the old ones: control maps use Cosic's own keys (`lpf`, `lpq`, ...), `note` turns note names into MIDI numbers.
 *
 * The program settings of the old catalogue (`setcps`, `setcpm`, `hush`, `samples`) are not here: they change the
 * program, not a pattern, and are globals of the language catalogue.
 */

// ---- argument helpers -------------------------------------------------------------------------------------------------

private fun numberText(value: Double): String =
    if (value == Math.floor(value) && abs(value) < 1e15) value.toLong().toString() else value.toString()

/** How a value reads in an error message: `'bd'`, `'4'`, `a pattern`, `a function`. */
private fun describe(value: Any?): String = when (value) {
    is Double -> "'${numberText(value)}'"
    is Pattern -> "a pattern"
    is JsFn -> "a function"
    is List<*>, is JsArray -> "a list"
    is Map<*, *> -> "a control pattern"
    else -> "'${jsToString(value)}'"
}

private fun number(name: String, value: Any?): Double =
    value as? Double ?: throw CosicError("'$name' expects a number, got ${describe(value)}")

private fun rational(name: String, value: Any?): Rational = Rational.of(number(name, value))

/** A whole number between -[MAX_COUNT] and [MAX_COUNT]. */
private fun int(name: String, value: Any?): Int {
    val number = number(name, value)
    if (!(abs(number) <= MAX_COUNT)) throw CosicError("'$name' expects a number up to $MAX_COUNT, got ${numberText(number)}")
    return number.roundToInt()
}

private fun text(name: String, value: Any?): String = when (value) {
    is String -> value
    is Double -> numberText(value)
    else -> throw CosicError("'$name' expects a string, got ${describe(value)}")
}

/** The items of a JavaScript array, or null when [value] is not one. */
private fun itemsOf(value: Any?): List<Any?>? = when (value) {
    is List<*> -> value
    is JsArray -> value.items
    else -> null
}

/** Any value as a pattern: a pattern is itself, a list is a sequence of its items, anything else repeats every cycle. */
private fun patternArg(value: Any?): Pattern {
    val items = itemsOf(value)
    return if (items != null) fastcat(items.map(::patternArg)) else reify(value)
}

/** The function argument [f], applied to a pattern. */
private fun function(name: String, f: Any?): (Pattern) -> Pattern {
    if (f !is JsFn) throw CosicError("'$name' expects a function, got ${describe(f)}")
    return { p -> patternArg(f.call(listOf(p))) }
}

/** The pattern for a numeric argument: a plain number is used directly, a pattern of numbers changes the result with it. */
private fun joinNumber(name: String, arg: Any?, f: (Double) -> Pattern): Pattern =
    if (arg is Pattern) arg.fmap { f(number(name, it)) }.innerJoin() else f(number(name, arg))

private fun joinInt(name: String, arg: Any?, f: (Int) -> Pattern): Pattern = joinNumber(name, arg) { f(int(name, it)) }

/** Errors of number conversion read like the old ones: `'fast': number out of range`. */
private inline fun <T> guarded(name: String, body: () -> T): T = try {
    body()
} catch (e: ArithmeticException) {
    throw CosicError("'$name': number out of range")
} catch (e: IllegalArgumentException) {
    throw CosicError("'$name': ${e.message}")
}

// ---- registration shorthands ------------------------------------------------------------------------------------------

private fun method1(vararg names: String, f: (Pattern) -> Pattern) {
    Registry.reg1(*names) { p -> guarded(names[0]) { f(p) } }
}

private fun method2(vararg names: String, patternify: Boolean = true, f: (Any?, Pattern) -> Pattern) {
    Registry.reg2(*names, patternify = patternify) { a, p -> guarded(names[0]) { f(a, p) } }
}

private fun method3(vararg names: String, f: (Any?, Any?, Pattern) -> Pattern) {
    Registry.reg3(*names) { a, b, p -> guarded(names[0]) { f(a, b, p) } }
}

private fun method4(vararg names: String, f: (Any?, Any?, Any?, Pattern) -> Pattern) {
    Registry.reg4(*names) { a, b, c, p -> guarded(names[0]) { f(a, b, c, p) } }
}

internal fun registerLegacy() {
    registerLegacyConstructors()
    registerLegacyTime()
    registerLegacyStructure()
    registerLegacyRandom()
    registerLegacyValues()
    registerLegacyControls()
}

// ---- constructors and signals -----------------------------------------------------------------------------------------

private fun registerLegacyConstructors() {
    Registry.constant(listOf("silence"), silence)
    Registry.variadic(listOf("seq", "sequence", "fastcat")) { a -> fastcat(a.map(::patternArg)) }
    Registry.variadic(listOf("cat", "slowcat")) { a -> slowcat(a.map(::patternArg)) }
    Registry.variadic(listOf("stack")) { a -> legacyStack(a.map(::patternArg)) }
    Registry.variadic(listOf("arrange"), method = false) { a ->
        val sections = a.map { section ->
            val pair = itemsOf(section)?.takeIf { it.size == 2 }
            val cycles = pair?.get(0) as? Double
            if (pair == null || cycles == null || !(cycles > 0)) throw CosicError("'arrange' expects [cycles, pattern] pairs")
            val length = guarded("arrange") { Rational.of(cycles) }
            length to patternArg(pair[1]).fast(length)
        }
        timecat(sections).slow(sections.fold(Rational.ZERO) { total, section -> total + section.first })
    }
    Registry.function(listOf("run")) { a ->
        joinInt("run", a.getOrNull(0)) { n -> fastcat((0 until n).map { pure(it.toDouble()) }) }
    }

    Registry.constant(listOf("sine"), sine)
    Registry.constant(listOf("cosine"), cosine)
    Registry.constant(listOf("saw"), saw)
    Registry.constant(listOf("isaw"), isaw)
    Registry.constant(listOf("tri"), tri)
    Registry.constant(listOf("square"), square)
    Registry.constant(listOf("rand"), rand)
    Registry.constant(listOf("perlin"), perlin)
    Registry.function(listOf("irand")) { a -> joinNumber("irand", a.getOrNull(0)) { irand(it) } }
}

// ---- time ---------------------------------------------------------------------------------------------------------------

private fun registerLegacyTime() {
    method2("fast") { x, p -> p.fast(rational("fast", x)) }
    method2("slow") { x, p -> p.slow(rational("slow", x)) }
    method2("early") { x, p -> p.early(rational("early", x)) }
    method2("late") { x, p -> p.late(rational("late", x)) }
    method2("hurry") { x, p ->
        val factor = rational("hurry", x)
        p.fast(factor).fmap { value ->
            val controls = asControlMap(value)
            controls + ("speed" to ((controls["speed"] as? Double ?: 1.0) * factor.toDouble()))
        }
    }
    method1("rev") { p -> p.rev() }
    method1("palindrome") { p -> p.lastOf(2, p.rev()) }
    method2("iter") { x, p -> p.iter(int("iter", x)) }
    method2("iterBack") { x, p -> p.iterBack(int("iterBack", x)) }
    method2("ply") { x, p -> p.ply(int("ply", x)) }
    method3("inside") { x, f, p -> p.inside(rational("inside", x), function("inside", f)) }
    method3("outside") { x, f, p -> p.outside(rational("outside", x), function("outside", f)) }
    method3("swingBy") { x, n, p -> p.swingBy(rational("swingBy", x), rational("swingBy", n)) }
    method2("swing") { n, p -> p.swingBy(Rational(1, 3), rational("swing", n)) }
    method3("off") { x, f, p -> p.off(rational("off", x), function("off", f)) }
}

// ---- structure ----------------------------------------------------------------------------------------------------------

private fun registerLegacyStructure() {
    method2("struct", patternify = false) { s, p -> p.struct(patternArg(s)) }
    method2("mask", patternify = false) { s, p -> p.mask(patternArg(s)) }
    method3("euclid") { k, n, p -> p.euclid(int("euclid", k), int("euclid", n)) }
    method4("euclidRot") { k, n, r, p -> p.euclidRot(int("euclidRot", k), int("euclidRot", n), int("euclidRot", r)) }
    method2("segment") { n, p -> p.segment(rational("segment", n)) }
    method3("chunk") { n, f, p -> p.chunk(int("chunk", n), function("chunk", f)(p)) }
    method3("every", "firstOf") { n, f, p -> p.every(int("every", n), function("every", f)(p)) }
    method3("lastOf") { n, f, p -> p.lastOf(int("lastOf", n), function("lastOf", f)(p)) }
    method2("superimpose") { f, p -> p.superimpose(function("superimpose", f)) }
    Registry.variadic(listOf("layer")) { a ->
        val receiver = a.firstOrNull() as? Pattern
        if (receiver != null) {
            legacyStack(a.drop(1).map { function("layer", it)(receiver) })
        } else {
            jsFn(1) { rest -> legacyStack(a.map { function("layer", it)(patternArg(rest[0])) }) }
        }
    }
    method2("jux") { f, p ->
        val copy = function("jux", f)(p)
        legacyStack(p.withControls("pan" to 0.0), copy.withControls("pan" to 1.0))
    }
    method3("juxBy") { n, f, p ->
        val copy = function("juxBy", f)(p)
        joinNumber("juxBy", n) { width ->
            legacyStack(p.withControls("pan" to 0.5 - width / 2), copy.withControls("pan" to 0.5 + width / 2))
        }
    }
    method2("chop") { n, p -> val pieces = int("chop", n); p.splitWholes(pieces) { value, i -> chopped(value, i, pieces) } }
    method2("striate") { n, p ->
        val slices = int("striate", n)
        fastcat(
            (0 until slices).map { i ->
                p.withControls("begin" to i.toDouble() / slices, "end" to (i + 1).toDouble() / slices)
            },
        )
    }
}

/** Piece [index] of [count] of a sample: narrows `begin`..`end` (0..1 unless set) to that piece. */
private fun chopped(value: Any?, index: Int, count: Int): ControlMap {
    val controls = asControlMap(value)
    val begin = controls["begin"] as? Double ?: 0.0
    val end = controls["end"] as? Double ?: 1.0
    return controls + mapOf(
        "begin" to begin + (end - begin) * index / count,
        "end" to begin + (end - begin) * (index + 1) / count,
    )
}

// ---- randomness -----------------------------------------------------------------------------------------------------------

private fun registerLegacyRandom() {
    method1("degrade") { p -> p.degradeBy(0.5) }
    method2("degradeBy") { x, p -> p.degradeBy(number("degradeBy", x)) }
    method3("sometimesBy") { x, f, p -> p.sometimesBy(number("sometimesBy", x), function("sometimesBy", f)) }
    for ((name, probability) in listOf("sometimes" to 0.5, "often" to 0.75, "rarely" to 0.25, "almostNever" to 0.1, "almostAlways" to 0.9)) {
        method2(name) { f, p -> p.sometimesBy(probability, function(name, f)) }
    }
    method3("someCyclesBy") { x, f, p -> p.someCyclesBy(number("someCyclesBy", x), function("someCyclesBy", f)(p)) }
    method2("someCycles") { f, p -> p.someCyclesBy(0.5, function("someCycles", f)(p)) }
}

// ---- values ---------------------------------------------------------------------------------------------------------------

/**
 * The pattern combining [left] and [right] with [op]. The structure comes from the left; a plain value on the right
 * (a number or a string written in the program) is applied to every value.
 */
private fun arithmetic(op: ArithOp, left: Pattern, right: Pattern): Pattern {
    val scalar = right.pureValue.takeIf { it is Double || it is String || it is Boolean }
    return if (scalar != null) left.fmap { arith(op, it, scalar) } else left.zipLeft(right) { a, b -> arith(op, a, b) }
}

private fun registerLegacyValues() {
    method2("add", patternify = false) { x, p -> arithmetic(ArithOp.ADD, p, x as Pattern) }
    method2("sub", patternify = false) { x, p -> arithmetic(ArithOp.SUB, p, x as Pattern) }
    method2("mul", patternify = false) { x, p -> arithmetic(ArithOp.MUL, p, x as Pattern) }
    method2("div", patternify = false) { x, p -> arithmetic(ArithOp.DIV, p, x as Pattern) }
    method3("range") { lo, hi, p ->
        val low = number("range", lo)
        val high = number("range", hi)
        p.fmap { number("range", it) * (high - low) + low }
    }
    method2("scale") { s, p ->
        val scale = text("scale", s)
        scaleNote(0, scale)
        p.fmap { scaled(it, scale) }
    }
}

/** [value] (a degree, or a control map holding one in `n`) as a note of [scale]. */
private fun scaled(value: Any?, scale: String): ControlMap = when (value) {
    is Double -> mapOf("note" to scaleNote(Math.round(value).toInt(), scale))
    is Map<*, *> -> {
        val degree = value["n"] as? Double ?: throw CosicError("scale: needs n values")
        (asControlMap(value) - "n") + ("note" to scaleNote(Math.round(degree).toInt(), scale))
    }
    else -> throw CosicError("'scale' expects a number, got ${describe(value)}")
}

// ---- controls -------------------------------------------------------------------------------------------------------------

/**
 * A control. Called as a global it is a pattern of control maps; called as a method it sets the control on every value
 * of the receiver, with the structure of the receiver. A plain number or string is converted once, so mistakes in it are
 * reported right away.
 */
private fun control(names: List<String>, convert: (String, Any?) -> ControlMap) {
    val name = names[0]
    fun build(self: Pattern?, args: List<Any?>): Pattern {
        if (args.size != 1) throw CosicError("'$name' expects 1 arguments, got ${args.size}")
        val arg = args[0]
        return guarded(name) {
            val constant: ControlMap? = if (arg is Double || arg is String) convert(name, arg) else null
            val set: (Any?) -> ControlMap = { convert(name, it) }
            when {
                self == null -> if (constant != null) pure(constant) else patternArg(arg).fmap(set)
                constant != null -> self.fmap { asControlMap(it) + constant }
                else -> self.zipLeft(patternArg(arg).fmap(set)) { value, other -> asControlMap(value) + asControlMap(other) }
            }
        }
    }
    Registry.dual(names, global = { a -> build(null, a) }, method = { self, a -> build(self, a) })
}

private fun numericControl(key: String, vararg aliases: String) =
    control(listOf(key) + aliases) { name, value -> mapOf(key to number(name, value)) }

private fun registerLegacyControls() {
    control(listOf("s", "sound")) { name, value -> soundControl(text(name, value)) }
    control(listOf("n")) { name, value -> mapOf("n" to number(name, value)) }
    control(listOf("note")) { name, value ->
        mapOf(
            "note" to when (value) {
                is Double -> value
                is String -> legacyNoteToMidi(value)?.toDouble() ?: throw CosicError("Not a note: '$value'")
                else -> throw CosicError("'$name' expects a number or a note name, got ${describe(value)}")
            },
        )
    }
    control(listOf("bank")) { name, value -> mapOf("bank" to text(name, value)) }
    numericControl("speed")
    numericControl("begin")
    numericControl("end")
    numericControl("cut")
    numericControl("gain")
    numericControl("velocity")
    numericControl("postgain")
    numericControl("pan")
    numericControl("clip", "legato")
    numericControl("attack", "att")
    numericControl("decay", "dec")
    numericControl("sustain", "sus")
    numericControl("release", "rel")
    numericControl("lpf", "cutoff", "ctf")
    numericControl("lpq", "resonance")
    numericControl("hpf", "hcutoff")
    numericControl("hpq", "hresonance")
    numericControl("bpf", "bandf")
    numericControl("bpq", "bandq")
    numericControl("shape")
    numericControl("crush")
    numericControl("coarse")
    numericControl("delay")
    numericControl("delaytime", "delayt")
    numericControl("delayfeedback", "delayfb")
    numericControl("room")
    numericControl("size", "roomsize")
}
