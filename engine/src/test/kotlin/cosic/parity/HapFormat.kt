package cosic.parity

import cosic.pattern.Hap
import cosic.pattern.Rational
import cosic.pattern.TimeSpan
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/*
 * Test-side port of the strings Strudel's own test suite compares: Hap.show(true), sortHapsByPart and the float
 * rounding of tunes.test.mjs. It lives here, in the test source set, because the engine has no such formatter yet;
 * Task 1.1 moves Hap.show, sortHapsByPart and the JS number/JSON helpers into the engine and deletes this file.
 *
 * Reference (Strudel at 84ef32eec8074f90c6690ab8d6a6c204f508b182):
 *   packages/core/hap.mjs:122-149 (show), packages/core/timespan.mjs:112-114, packages/core/fraction.mjs:84-87,
 *   packages/core/pattern.mjs:783-791 (sortHapsByPart), packages/core/pattern.mjs:421-428 (queryArc),
 *   test/tunes.test.mjs:8-18 (roundFloatStrings).
 */

/** `Fraction.prototype.show`: always `n/d`, never a bare integer (`0/1`, `-1/2`). */
internal fun fractionShow(fraction: Rational): String = "${fraction.num}/${fraction.den}"

/** `TimeSpan.prototype.show`. The arrow is U+2192. */
internal fun timeSpanShow(span: TimeSpan): String = "${fractionShow(span.begin)} \u2192 ${fractionShow(span.end)}"

/**
 * `Hap.prototype.show(compact)`.
 *
 * A hap without a whole (a continuous signal) prints `~` followed by the part (LANG DEV-5, which Task 1.1 also adopts
 * for the engine). Strudel itself concatenates the `show` *method* there, a missing pair of parentheses, and so prints
 * the JS source text of that method; no snapshot contains such a hap.
 */
internal fun showHap(hap: Hap<Any?>, compact: Boolean = true): String {
    val whole = hap.whole
    val spans = if (whole == null) {
        "~" + timeSpanShow(hap.part)
    } else {
        val isWhole = whole.begin == hap.part.begin && whole.end == hap.part.end
        buildString {
            if (whole.begin != hap.part.begin) append(fractionShow(whole.begin)).append(" \u21DC ")
            if (!isWhole) append('(')
            append(timeSpanShow(hap.part))
            if (!isWhole) append(')')
            if (whole.end != hap.part.end) append(" \u21DD ").append(fractionShow(whole.end))
        }
    }
    return "[ $spans | ${showValue(hap.value, compact)} ]"
}

/**
 * The value part of [showHap]: for `typeof value === 'object'` (maps, lists and `null`) the `JSON.stringify` text
 * without its first and last character, without quotes and with spaces for commas; anything else as `String(value)`.
 */
internal fun showValue(value: Any?, compact: Boolean = true): String {
    val isObject = value == null || value is Map<*, *> || value is List<*> || value is Array<*> || value is Rational
    if (!isObject) return jsToString(value)
    val json = jsonStringify(value) ?: "undefined"
    return if (compact) json.substring(1, json.length - 1).replace("\"", "").replace(",", " ") else json
}

/** `String(value)` for the primitives a hap value can be. */
private fun jsToString(value: Any?): String = when (value) {
    is Double -> jsNumberToString(value)
    is Number -> jsNumberToString(value.toDouble())
    else -> value.toString()
}

/**
 * The comparator of `Pattern.sortHapsByPart`: part begin, part end, whole begin, whole end; the sort is stable.
 *
 * Strudel evaluates the whole-based arguments eagerly, so any comparison that involves a hap without a whole throws
 * a `TypeError`, and `queryArc` turns that into an empty result. Every hap takes part in at least one comparison
 * when there are two or more, hence the rule below (LANG §1.4).
 */
internal fun <T> sortHapsByPart(haps: List<Hap<T>>): List<Hap<T>> {
    if (haps.size >= 2 && haps.any { it.whole == null }) throw HapsWithoutWholeSortError()
    return haps.sortedWith { a, b ->
        val partBegin = a.part.begin.compareTo(b.part.begin)
        if (partBegin != 0) return@sortedWith partBegin
        val partEnd = a.part.end.compareTo(b.part.end)
        if (partEnd != 0) return@sortedWith partEnd
        val wholeA = a.whole!!
        val wholeB = b.whole!!
        val wholeBegin = wholeA.begin.compareTo(wholeB.begin)
        if (wholeBegin != 0) wholeBegin else wholeA.end.compareTo(wholeB.end)
    }
}

/** What `sortHapsByPart` throws for two or more haps when one of them has no whole; Strudel's `queryArc` returns `[]`. */
internal class HapsWithoutWholeSortError : RuntimeException("Cannot read properties of undefined (reading 'begin')")

// ---- JavaScript numbers and JSON ---------------------------------------------------------------------------------

/**
 * ECMAScript `Number::toString(10)`: the shortest digits that read back as the same double, positional notation for
 * exponents up to 21 and down to -7, exponent notation beyond; integers carry no `.0`; `-0` is `0`.
 */
internal fun jsNumberToString(d: Double): String {
    if (d.isNaN()) return "NaN"
    if (d == 0.0) return "0"
    if (d.isInfinite()) return if (d > 0) "Infinity" else "-Infinity"
    if (d < 0) return "-" + jsNumberToString(-d)
    if (d < 1e15 && d == Math.floor(d)) return d.toLong().toString()
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
            mantissa + "e" + (if (e < 0) "-" else "+") + Math.abs(e)
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

/** `Number.prototype.toFixed`: rounds the exact binary value half up; `x < 0` keeps its sign (`-0.000000000000`). */
internal fun jsToFixed(x: Double, digits: Int): String {
    if (x.isNaN()) return "NaN"
    if (x.isInfinite() || Math.abs(x) >= 1e21) return jsNumberToString(x)
    val text = BigDecimal(Math.abs(x)).setScale(digits, RoundingMode.HALF_UP).toPlainString()
    return if (x < 0) "-$text" else text
}

private val FLOAT_TEXT = Regex("-?\\d+\\.\\d+")
private val TRAILING_ZEROS = Regex("\\.?0+\\z")
private val DANGLING_DOT = Regex("\\.\\z")

/**
 * `roundFloatStrings` of test/tunes.test.mjs: every decimal number in [input] is rounded to [precision] digits with
 * `toFixed`, then loses trailing zeros and a dangling dot. Tunes and extra snippets are compared in this form,
 * examples are not.
 */
internal fun roundFloatStrings(input: String, precision: Int = 12): String =
    FLOAT_TEXT.replace(input) { match ->
        jsToFixed(match.value.toDouble(), precision)
            .replaceFirst(TRAILING_ZEROS, "")
            .replaceFirst(DANGLING_DOT, "")
    }

/**
 * `JSON.stringify`: maps in JavaScript key order, lists in order, strings escaped like JSON, numbers as
 * [jsNumberToString] with non-finite ones as `null`. Returns null where JavaScript returns `undefined`
 * (values that JSON cannot represent, such as functions).
 */
internal fun jsonStringify(value: Any?): String? = when (value) {
    null -> "null"
    is Boolean -> value.toString()
    is Double -> if (value.isFinite()) jsNumberToString(value) else "null"
    is Number -> jsonStringify(value.toDouble())
    is String -> jsonQuote(value)
    is Rational -> "{\"s\":${if (value.num < 0) -1 else 1},\"n\":${Math.abs(value.num)},\"d\":${value.den}}"
    is Map<*, *> -> jsonObject(value)
    is List<*> -> value.joinToString(",", "[", "]") { jsonStringify(it) ?: "null" }
    is Array<*> -> value.joinToString(",", "[", "]") { jsonStringify(it) ?: "null" }
    else -> null
}

private fun jsonObject(map: Map<*, *>): String {
    val byKey = LinkedHashMap<String, Any?>()
    for ((key, value) in map) byKey[key.toString()] = value
    return jsKeyOrder(byKey.keys).mapNotNull { key ->
        jsonStringify(byKey[key])?.let { jsonQuote(key) + ":" + it }
    }.joinToString(",", "{", "}")
}

/**
 * The order JavaScript enumerates own string keys in (K1): integer-like keys first, ascending, then the others in
 * insertion order.
 */
internal fun jsKeyOrder(keys: Collection<String>): List<String> {
    val (indices, others) = keys.partition(::isArrayIndex)
    return indices.sortedBy { it.toLong() } + others
}

private val ARRAY_INDEX = Regex("0|[1-9][0-9]{0,9}")

private fun isArrayIndex(key: String): Boolean = ARRAY_INDEX.matches(key) && key.toLong() <= 4_294_967_294L

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
