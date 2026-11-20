package cosic.script

import cosic.SourceRange
import cosic.pattern.CosicError
import cosic.pattern.MAX_COUNT
import cosic.pattern.Pattern
import cosic.pattern.Rational
import cosic.pattern.legacy.SCALE_MODES
import kotlin.math.roundToInt

/** What kind of value a builtin parameter accepts; arguments are checked against it before the builtin runs. */
internal enum class ParamKind {
    /** A number, or a pattern of numbers. */
    NUM_PAT,

    /** Anything that can be turned into a pattern. */
    PAT,

    /** A function. */
    FN,

    /** A string, or a pattern of strings. */
    STR_PAT,

    /** Any value, passed through as it is. */
    ANY,
}

internal enum class BuiltinKind {
    /** Called at top level and builds a pattern from its arguments. */
    CONSTRUCTOR,

    /** Transforms a pattern: `x.fast(2)`, or `fast(2)` for a function awaiting the pattern. */
    METHOD,

    /** A sound parameter: builds a pattern of control maps, or sets that parameter on a pattern when used as a method. */
    CONTROL,

    /** A continuous pattern such as `sine`. */
    SIGNAL,

    /** Changes the program's settings rather than building a pattern. */
    SETTING,
}

internal const val DEFAULT_CPS = 0.5

/** Mutable state of one program run: what the settings changed. */
internal class Session {
    var cps: Double = DEFAULT_CPS
    var hushed: Boolean = false
}

/**
 * One entry of the catalogue of functions available to programs. [params] are the declared parameters
 * (not counting the pattern a method works on) and [variadic] the kind of any further arguments. Entries
 * with `listed = false` are not shown in the reference.
 */
internal class Builtin(
    val name: String,
    val aliases: List<String>,
    val category: String,
    val params: List<ParamKind>,
    val variadic: ParamKind?,
    val kind: BuiltinKind,
    val doc: String,
    val example: String,
    val listed: Boolean = true,
    val impl: Call.() -> Value,
)

/**
 * One invocation of a builtin with its arguments already checked. [self] is the pattern a method works on
 * (or the pattern a control is set on), null when called as a constructor; [name] is how the user wrote it.
 */
internal class Call(
    val name: String,
    val range: SourceRange,
    val self: Pattern?,
    val args: List<Value>,
    val session: Session,
) {
    fun fail(message: String): Nothing = throw CosicError(message, range)

    fun pattern(i: Int): Pattern = toPattern(args[i])

    /** The function argument [i], applied to a pattern. */
    fun function(i: Int): (Pattern) -> Pattern {
        val fn = args[i] as Value.Fn
        return { p -> toPattern(fn.body(listOf(Value.Pat(p)))) }
    }

    fun number(value: Any?): Double = value as? Double ?: fail("'$name' expects a number, got ${describeValue(value)}")

    fun text(value: Any?): String = when (value) {
        is String -> value
        is Double -> formatNumber(value)
        else -> fail("'$name' expects a string, got ${describeValue(value)}")
    }

    /**
     * Builds the result for a numeric argument. A plain number is used directly; a pattern of numbers
     * makes the result change with it (joined by the argument's structure).
     */
    fun joinNumber(i: Int, f: (Double) -> Pattern): Pattern {
        val arg = args[i]
        return if (arg is Value.Num) f(arg.value) else toPattern(arg).fmap { f(number(it)) }.innerJoin()
    }

    fun joinRational(i: Int, f: (Rational) -> Pattern): Pattern = joinNumber(i) { f(Rational.of(it)) }

    /** Like [joinNumber] for a whole number between -[MAX_COUNT] and [MAX_COUNT]. */
    fun joinInt(i: Int, f: (Int) -> Pattern): Pattern = joinNumber(i) { value ->
        if (!(Math.abs(value) <= MAX_COUNT)) fail("'$name' expects a number up to $MAX_COUNT, got ${formatNumber(value)}")
        f(value.roundToInt())
    }

    fun joinText(i: Int, f: (String) -> Pattern): Pattern = when (val arg = args[i]) {
        is Value.Str -> f(arg.value)
        is Value.Num -> f(formatNumber(arg.value))
        else -> toPattern(arg).fmap { f(text(it)) }.innerJoin()
    }
}

/** The catalogue of everything a program can call. */
internal object Builtins {
    val all: List<Builtin> = constructorBuiltins() + methodBuiltins() + controlBuiltins()

    private val byName: Map<String, Builtin> = HashMap<String, Builtin>().also { names ->
        for (builtin in all) {
            names[builtin.name] = builtin
            for (alias in builtin.aliases) names[alias] = builtin
        }
    }

    fun lookup(name: String): Builtin? = byName[name]
}

/**
 * One documented function for the reference screen. [example] is a program that runs on its own;
 * [snippet] is the same line prepared for pasting into an existing program: a pattern needs a `$:`
 * label there, because a program with labels ignores unlabelled expressions.
 */
public data class ReferenceEntry(
    val name: String,
    val aliases: List<String>,
    val category: String,
    val signature: String,
    val doc: String,
    val example: String,
    val snippet: String,
)

/** Every documented function, grouped by category, each with a one-line example that runs as a program. */
public fun reference(): List<ReferenceEntry> = Builtins.all.filter { it.listed }.map {
    ReferenceEntry(it.name, it.aliases, it.category, signature(it), it.doc, it.example, snippet(it))
}

/** The modes a scale name can use after its root note, as in `C:minor`. */
public fun scaleModes(): List<String> = SCALE_MODES

private fun snippet(builtin: Builtin): String {
    val alreadyLabelled = tokenize(builtin.example).any { it.kind == TokenKind.LABEL }
    return if (builtin.kind == BuiltinKind.SETTING || alreadyLabelled) builtin.example else "\$: ${builtin.example}"
}

private fun signature(builtin: Builtin): String {
    val bare = builtin.kind == BuiltinKind.SIGNAL ||
        (builtin.kind == BuiltinKind.CONSTRUCTOR && builtin.params.isEmpty() && builtin.variadic == null)
    if (bare) return builtin.name
    val parameters = builtin.params.map(::kindWord) + listOfNotNull(builtin.variadic?.let { "...${kindWord(it)}" })
    val prefix = if (builtin.kind == BuiltinKind.METHOD) "." else ""
    return "$prefix${builtin.name}(${parameters.joinToString(", ")})"
}

private fun kindWord(kind: ParamKind): String = when (kind) {
    ParamKind.NUM_PAT -> "number"
    ParamKind.STR_PAT -> "string"
    ParamKind.PAT -> "pattern"
    ParamKind.FN -> "function"
    ParamKind.ANY -> "value"
}
