package cosic.script

import cosic.pattern.CosicError
import cosic.pattern.Pattern
import cosic.pattern.fastcat
import cosic.pattern.pure

/** What an expression evaluates to. */
internal sealed class Value {
    class Num(val value: Double) : Value()

    class Str(val value: String) : Value()

    class Bool(val value: Boolean) : Value()

    class Pat(val pattern: Pattern) : Value()

    class Arr(val items: List<Value>) : Value()

    /** A function; builtins and lambdas both end up as this. */
    class Fn(val body: (List<Value>) -> Value) : Value()

    /** The result of `setcpm(...)` and the like, which have an effect but no value. */
    object None : Value()
}

/** Turns any value into a pattern: scalars repeat every cycle and lists become sequences. */
internal fun toPattern(value: Value): Pattern = when (value) {
    is Value.Pat -> value.pattern
    is Value.Num -> pure(value.value)
    is Value.Str -> pure(value.value)
    is Value.Bool -> pure(value.value)
    is Value.Arr -> fastcat(value.items.map(::toPattern))
    is Value.Fn -> throw CosicError("Expected a pattern, got a function")
    Value.None -> throw CosicError("Expected a pattern, got nothing")
}

/** Whole numbers without a decimal point: `4`, but `0.5`. */
internal fun formatNumber(value: Double): String =
    if (value == Math.floor(value) && Math.abs(value) < 1e15) value.toLong().toString() else value.toString()

/** How a value reads in an error message: `'bd'`, `'4'`, `a pattern`, `a function`. */
internal fun describe(value: Value): String = when (value) {
    is Value.Num -> "'${formatNumber(value.value)}'"
    is Value.Str -> "'${value.value}'"
    is Value.Bool -> "'${value.value}'"
    is Value.Pat -> "a pattern"
    is Value.Arr -> "a list"
    is Value.Fn -> "a function"
    Value.None -> "nothing"
}

/** How a pattern value reads in an error message. */
internal fun describeValue(value: Any?): String = when (value) {
    is Double -> "'${formatNumber(value)}'"
    is Map<*, *> -> "a control pattern"
    else -> "'$value'"
}
