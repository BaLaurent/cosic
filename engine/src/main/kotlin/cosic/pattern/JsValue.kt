package cosic.pattern

import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.truncate

/*
 * JavaScript value semantics, as far as Strudel's code and the parity snapshots depend on them (K9 of the parity
 * plan: this file is the one owner of these helpers; nobody else writes a second copy).
 *
 * Hap values are `Double`, `String`, `Boolean`, `Rational`, `List<Any?>`, `Map<String, Any?>`, `JsFn`, `Pattern`,
 * Kotlin `null` (JS `null`) and [Undefined] (JS `undefined`); see K3.
 */

/** JavaScript's `undefined`. Hap values may be undefined (`adsr("0.1")` stores undefined keys); Kotlin `null` is JS `null`. */
internal object Undefined {
    override fun toString(): String = "undefined"
}

/** `typeof value`. A function is `"function"`; `null`, lists, maps, patterns and fractions are all `"object"`. */
internal fun jsTypeof(v: Any?): String = when (v) {
    Undefined -> "undefined"
    is Number -> "number"
    is String -> "string"
    is Boolean -> "boolean"
    is JsFn -> "function"
    else -> "object"
}

/** JavaScript truthiness: `false`, `0`, `-0`, `NaN`, `""`, `null` and `undefined` are falsy; everything else, even `"false"` and `[]`, is truthy. */
internal fun jsTruthy(v: Any?): Boolean = when (v) {
    null, Undefined -> false
    is Boolean -> v
    is Double -> !(v == 0.0 || v.isNaN())
    is Number -> !(v.toDouble() == 0.0 || v.toDouble().isNaN())
    is String -> v.isNotEmpty()
    else -> true
}

// ---- numbers and strings ---------------------------------------------------------------------------------------

/** ToPrimitive for the values that can reach it: a list reads as its comma-joined elements, a fraction as its number. */
internal fun jsToPrimitive(v: Any?): Any? = when (v) {
    is List<*> -> jsToString(v)
    is Rational -> v.toDouble()
    null, Undefined, is Number, is String, is Boolean -> v
    else -> jsToString(v)
}

/** `String(value)`. Lists join their elements with commas (`null` and `undefined` as nothing); maps and patterns are `[object Object]`. */
internal fun jsToString(v: Any?): String = when (v) {
    null -> "null"
    Undefined -> "undefined"
    is String -> v
    is Double -> jsNumberToString(v)
    is Number -> jsNumberToString(v.toDouble())
    is Boolean -> v.toString()
    is List<*> -> v.joinToString(",") { if (it == null || it === Undefined) "" else jsToString(it) }
    is Rational -> v.toJsString()
    is JsFn -> "function () { [native code] }"
    else -> "[object Object]"
}

/** `Number(value)`: `null` is 0, `undefined` is NaN, strings are parsed by [jsStringToNumber], a one-element list is its element. */
internal fun jsToNumber(v: Any?): Double = when (v) {
    is Double -> v
    is Number -> v.toDouble()
    is Boolean -> if (v) 1.0 else 0.0
    is String -> jsStringToNumber(v)
    null -> 0.0
    Undefined -> Double.NaN
    is Rational -> v.toDouble()
    is List<*> -> jsStringToNumber(jsToString(v))
    else -> Double.NaN
}

private const val JS_WHITESPACE = "\t\n\u000B\u000C\r \u00A0\u1680\u2000\u2001\u2002\u2003\u2004\u2005\u2006\u2007\u2008\u2009\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF"

private val DECIMAL_LITERAL = Regex("[+-]?(Infinity|(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?)")
private val HEX_LITERAL = Regex("0[xX][0-9a-fA-F]+")
private val BINARY_LITERAL = Regex("0[bB][01]+")
private val OCTAL_LITERAL = Regex("0[oO][0-7]+")

/** The JS `StringToNumber` of `Number("...")`: surrounding whitespace is ignored, an empty string is 0, and anything that is not a numeric literal is NaN. */
internal fun jsStringToNumber(s: String): Double {
    val t = s.trim { it in JS_WHITESPACE }
    if (t.isEmpty()) return 0.0
    when {
        HEX_LITERAL.matches(t) -> return BigInteger(t.substring(2), 16).toDouble()
        BINARY_LITERAL.matches(t) -> return BigInteger(t.substring(2), 2).toDouble()
        OCTAL_LITERAL.matches(t) -> return BigInteger(t.substring(2), 8).toDouble()
        DECIMAL_LITERAL.matches(t) -> return java.lang.Double.parseDouble(t)
    }
    return Double.NaN
}

/** `parseInt(value)` (radix 10, or 16 after `0x`): leading digits are read, anything after them is ignored; NaN when there are none. */
internal fun jsParseInt(v: Any?, radix: Int = 10): Double {
    var s = jsToString(v).trim { it in JS_WHITESPACE }
    var sign = 1.0
    if (s.startsWith("-")) {
        sign = -1.0
        s = s.substring(1)
    } else if (s.startsWith("+")) {
        s = s.substring(1)
    }
    var base = radix
    if ((base == 10 || base == 16) && (s.startsWith("0x") || s.startsWith("0X"))) {
        s = s.substring(2)
        base = 16
    }
    if (base < 2 || base > 36) return Double.NaN
    val end = s.indexOfFirst { Character.digit(it, base) < 0 }.let { if (it < 0) s.length else it }
    if (end == 0) return Double.NaN
    return sign * BigInteger(s.substring(0, end), base).toDouble()
}

/** `Math.round`: the integer nearest to [x], halves going up (`2.5` is 3, `-2.5` is -2); NaN and infinities come back unchanged. */
internal fun jsMathRound(x: Double): Double {
    if (x.isNaN() || x.isInfinite()) return x
    val f = floor(x)
    val r = if (x - f >= 0.5) f + 1.0 else f
    // Math.round(-0.2) is -0, not 0.
    return if (r == 0.0 && (x < 0.0 || 1.0 / x < 0.0)) -0.0 else r
}

/** `((n % m) + m) % m`: a modulo that stays positive for negative [n] (`_mod` of Strudel's util.mjs). */
internal fun jsMod(n: Double, m: Double): Double = ((n % m) + m) % m

/** ECMAScript `ToInt32`: the number truncated and wrapped into 32 bits. */
internal fun jsToInt32(d: Double): Int {
    if (d.isNaN() || d.isInfinite()) return 0
    val t = truncate(d)
    return if (abs(t) < 9.0e18) t.toLong().toInt() else BigDecimal(t).toBigInteger().toInt()
}

/** `Number.prototype.toFixed`: rounds the exact binary value half up; a negative [x] keeps its sign (`-0.000000000000`). */
internal fun jsToFixed(x: Double, digits: Int): String {
    if (x.isNaN()) return "NaN"
    if (x.isInfinite() || abs(x) >= 1e21) return jsNumberToString(x)
    val text = BigDecimal(abs(x)).setScale(digits, RoundingMode.HALF_UP).toPlainString()
    return if (x < 0) "-$text" else text
}

/**
 * ECMAScript `Number::toString(10)`: the shortest digits that read back as the same double, positional notation for
 * exponents up to 21 and down to -7, exponent notation beyond; integers carry no `.0`; `-0` is `0`.
 */
internal fun jsNumberToString(d: Double): String {
    if (d.isNaN()) return "NaN"
    if (d == 0.0) return "0"
    if (d.isInfinite()) return if (d > 0) "Infinity" else "-Infinity"
    if (d < 0) return "-" + jsNumberToString(-d)
    if (d < 1e15 && d == floor(d)) return d.toLong().toString()
    // d == 0.<digits> * 10^n with k = digits.length significant digits (ECMA-262: s * 10^(n-k)).
    val (digits, n) = shortestDigits(d)
    val k = digits.length
    return when {
        n in k..21 -> digits + "0".repeat(n - k)
        n in 1..21 -> digits.substring(0, n) + "." + digits.substring(n)
        n in -5..0 -> "0." + "0".repeat(-n) + digits
        else -> {
            val e = n - 1
            val mantissa = if (k == 1) digits else digits[0] + "." + digits.substring(1)
            mantissa + "e" + (if (e < 0) "-" else "+") + abs(e)
        }
    }
}

/**
 * The fewest significant digits that identify [d] (positive, finite, not an integer below 1e15), and the exponent
 * `n` with `d = 0.digits * 10^n`. For each digit count both neighbours of the exact value are tried, because next to
 * a power of two the nearest decimal can fall outside the interval that still reads back as [d]; the closer of the
 * candidates that read back wins, ties go to the even last digit (ECMA-262 Number::toString step 5).
 */
private fun shortestDigits(d: Double): Pair<String, Int> {
    val exact = BigDecimal(d)
    for (precision in 1..17) {
        var best: BigDecimal? = null
        for (mode in arrayOf(RoundingMode.FLOOR, RoundingMode.CEILING)) {
            val candidate = exact.round(MathContext(precision, mode))
            if (java.lang.Double.parseDouble(candidate.toString()) != d) continue
            best = if (best == null) candidate else closer(exact, best, candidate)
        }
        if (best != null) {
            val unscaled = best.unscaledValue().toString()
            return unscaled.trimEnd('0') to (unscaled.length - best.scale())
        }
    }
    throw IllegalStateException("no 17 digit representation reads back as $d")
}

private fun closer(exact: BigDecimal, a: BigDecimal, b: BigDecimal): BigDecimal {
    val byDistance = (a - exact).abs().compareTo((b - exact).abs())
    if (byDistance != 0) return if (byDistance < 0) a else b
    return if (a.unscaledValue().testBit(0)) b else a
}

/**
 * `nanFallback` of util.mjs: [v] when it is a number or reads as one, otherwise [fallback], with a warning.
 * The value itself comes back, not its number (`"3"` stays `"3"`).
 */
internal fun nanFallback(v: Any?, fallback: Any? = 0.0): Any? {
    if (jsToNumber(v).isNaN()) {
        logger("\"${jsToString(v)}\" is not a number, falling back to ${jsToString(fallback)}", "warning", null)
        return fallback
    }
    return v
}

// ---- comparison ------------------------------------------------------------------------------------------------

/**
 * The abstract relational comparison behind `a < b`: true or false, or null when it is undefined (a NaN took part).
 * `a > b` is `jsLess(b, a) == true`, `a <= b` is `jsLess(b, a) == false` and `a >= b` is `jsLess(a, b) == false`.
 * Two strings compare by UTF-16 code units, anything else as numbers.
 */
internal fun jsLess(a: Any?, b: Any?): Boolean? {
    val x = jsToPrimitive(a)
    val y = jsToPrimitive(b)
    if (x is String && y is String) return x < y
    val nx = jsToNumber(x)
    val ny = jsToNumber(y)
    if (nx.isNaN() || ny.isNaN()) return null
    return nx < ny
}

/** `a === b`. Numbers compare as numbers (`NaN` is not equal to itself, `0` equals `-0`); lists, maps, patterns and fractions by identity. */
internal fun jsStrictEq(a: Any?, b: Any?): Boolean = when {
    a is Double && b is Double -> a == b
    a is Number && b is Number -> a.toDouble() == b.toDouble()
    a is String && b is String -> a == b
    a is Boolean && b is Boolean -> a == b
    a == null || b == null -> a == null && b == null
    a === Undefined || b === Undefined -> a === b
    else -> a === b
}

private fun isNullish(v: Any?) = v == null || v === Undefined

private fun isObjectLike(v: Any?) = !(isNullish(v) || v is Number || v is String || v is Boolean)

/** `a == b`: `null` equals `undefined` and nothing else, numbers equal strings that read as them, booleans count as 0 and 1, objects compare by identity. */
internal fun jsLooseEq(a: Any?, b: Any?): Boolean = when {
    isNullish(a) || isNullish(b) -> isNullish(a) && isNullish(b)
    a is Boolean -> jsLooseEq(if (a) 1.0 else 0.0, b)
    b is Boolean -> jsLooseEq(a, if (b) 1.0 else 0.0)
    a is String && b is String -> a == b
    a is Number && b is Number -> a.toDouble() == b.toDouble()
    a is Number && b is String -> a.toDouble() == jsStringToNumber(b)
    a is String && b is Number -> jsStringToNumber(a) == b.toDouble()
    isObjectLike(a) && isObjectLike(b) -> a === b
    isObjectLike(a) -> jsLooseEq(jsToPrimitive(a), b)
    else -> jsLooseEq(a, jsToPrimitive(b))
}

// ---- slicing ---------------------------------------------------------------------------------------------------

/** Where `array.slice(n)` starts in an array of [len] elements: negative [n] counts from the end, and the index is clamped into `0..len`. */
internal fun jsSliceIndex(n: Double, len: Int): Int {
    val t = if (n.isNaN()) 0.0 else truncate(n)
    return if (t < 0) max(len + t, 0.0).toInt() else min(t, len.toDouble()).toInt()
}

/** `list.slice(n).concat(list.slice(0, n))`: rotates [list] left by [n] steps, clamping [n] like `slice` does (so a rotation of a whole length or more leaves it as it is). */
internal fun <T> jsRotate(list: List<T>, n: Double): List<T> {
    val k = jsSliceIndex(n, list.size)
    return list.subList(k, list.size) + list.subList(0, k)
}

/** `[list.slice(0, index), list.slice(index)]`. */
internal fun <T> jsSplitAt(index: Double, list: List<T>): Pair<List<T>, List<T>> {
    val k = jsSliceIndex(index, list.size)
    return list.subList(0, k) to list.subList(k, list.size)
}

// ---- JSON and hap values ---------------------------------------------------------------------------------------

/**
 * `JSON.stringify`: maps in JavaScript key order, lists in order, strings escaped like JSON, numbers as
 * [jsNumberToString] with non-finite ones as `null`. Returns null where JavaScript returns `undefined`
 * (`undefined` and functions); object properties with such values are left out and list elements become `null`.
 * A fraction cannot be serialised (its parts are BigInts), which is a `TypeError` in JavaScript too.
 */
internal fun jsonStringify(v: Any?): String? = when (v) {
    null -> "null"
    Undefined -> null
    is Boolean -> v.toString()
    is Double -> if (v.isFinite()) jsNumberToString(v) else "null"
    is Number -> jsonStringify(v.toDouble())
    is String -> jsonQuote(v)
    is Rational -> throw CosicError("Do not know how to serialize a BigInt")
    is JsFn -> null
    is Map<*, *> -> jsonObject(v)
    is List<*> -> v.joinToString(",", "[", "]") { jsonStringify(it) ?: "null" }
    is Array<*> -> v.joinToString(",", "[", "]") { jsonStringify(it) ?: "null" }
    is Pattern -> "{\"_Pattern\":true}"
    else -> "{}"
}

private fun jsonObject(map: Map<*, *>): String {
    val byKey = LinkedHashMap<String, Any?>()
    for ((key, value) in map) byKey[key.toString()] = value
    return jsKeyOrder(byKey.keys).mapNotNull { key ->
        jsonStringify(byKey[key])?.let { jsonQuote(key) + ":" + it }
    }.joinToString(",", "{", "}")
}

/** The order JavaScript enumerates own string keys in (K1): integer-like keys first, ascending, then the others in insertion order. */
internal fun jsKeyOrder(keys: Collection<String>): List<String> {
    if (keys.none(::isArrayIndex)) return if (keys is List<String>) keys else keys.toList()
    val (indices, others) = keys.partition(::isArrayIndex)
    return indices.sortedBy { it.toLong() } + others
}

/** Whether JavaScript treats [key] as an array index, which orders it before the other keys of an object. */
internal fun isArrayIndex(key: String): Boolean {
    if (key.isEmpty() || key.length > 10) return false
    if (key.length > 1 && key[0] == '0') return false
    for (c in key) if (c !in '0'..'9') return false
    return key.toLong() <= 4_294_967_294L
}

private fun jsonQuote(text: String): String = buildString(text.length + 2) {
    append('"')
    for (i in text.indices) {
        val c = text[i]
        when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\b' -> append("\\b")
            c == '\u000C' -> append("\\f")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c < ' ' -> append("\\u").append(c.code.toString(16).padStart(4, '0'))
            c.isHighSurrogate() && (i + 1 >= text.length || !text[i + 1].isLowSurrogate()) ->
                append("\\u").append(c.code.toString(16))
            c.isLowSurrogate() && (i == 0 || !text[i - 1].isHighSurrogate()) ->
                append("\\u").append(c.code.toString(16))
            else -> append(c)
        }
    }
    append('"')
}

/**
 * `stringifyValues` of util.mjs, which is what `hap.show` prints for a value: an object (a map, a list or `null`) as
 * its `JSON.stringify` text, and in the compact form without the outer brackets, without quotes and with spaces for
 * commas (`{s:"bd",n:3}` is `s:bd n:3`); anything else as `String(value)`.
 */
internal fun stringifyValues(value: Any?, compact: Boolean = false): String {
    if (jsTypeof(value) != "object") return jsToString(value)
    // keys that start with `_cosic_` are routing data of the engine (K1 of the parity plan), not part of what a hap says
    val shown = if (value is Map<*, *> && value.keys.any { it is String && it.startsWith("_cosic_") }) value.filterKeys { !(it is String && it.startsWith("_cosic_")) } else value
    val json = jsonStringify(shown) ?: "undefined"
    return if (compact) json.substring(1, json.length - 1).replace("\"", "").replace(",", " ") else json
}
