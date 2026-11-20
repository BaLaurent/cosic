package cosic.script.next.js.stdlib

import cosic.pattern.Undefined
import cosic.pattern.jsNumberToString
import cosic.pattern.jsParseInt
import cosic.pattern.jsToFixed
import cosic.script.next.Realm
import cosic.script.next.js.JsObject
import cosic.script.next.js.PropFlags
import cosic.script.next.js.toInt32
import cosic.script.next.js.toNumber
import cosic.script.next.js.toStr
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.truncate

/*
 * `Number`, `parseInt`, `parseFloat`, `isNaN`, `isFinite`. The conversions of numbers to strings are the kernel's
 * (`jsNumberToString`, `jsToFixed`); this file adds the radix, precision and exponent forms.
 */

private val FLOAT_PREFIX = Regex("[+-]?(Infinity|(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?)")

internal fun installNumber(realm: Realm, std: StdRealm) {
    val proto = realm.numberPrototype
    val ctor = realm.constructor(
        "Number", 1, proto,
        { _, args -> if (args.isEmpty()) 0.0 else realm.toNumber(args[0]) },
        { args -> JsBox(proto, "Number", if (args.isEmpty()) 0.0 else realm.toNumber(args[0])) },
    )
    realm.defineGlobal("Number", ctor)

    ctor.defineOwn("EPSILON", Math.ulp(1.0), 0)
    ctor.defineOwn("MAX_SAFE_INTEGER", 9007199254740991.0, 0)
    ctor.defineOwn("MIN_SAFE_INTEGER", -9007199254740991.0, 0)
    ctor.defineOwn("MAX_VALUE", Double.MAX_VALUE, 0)
    ctor.defineOwn("MIN_VALUE", java.lang.Double.MIN_VALUE, 0)
    ctor.defineOwn("POSITIVE_INFINITY", Double.POSITIVE_INFINITY, 0)
    ctor.defineOwn("NEGATIVE_INFINITY", Double.NEGATIVE_INFINITY, 0)
    ctor.defineOwn("NaN", Double.NaN, 0)

    val parseFloat = realm.defineGlobalFunction("parseFloat", 1) { _, args -> parseFloat(realm.toStr(args.arg(0))) }
    val parseInt = realm.defineGlobalFunction("parseInt", 2) { _, args -> parseInt(realm, realm.toStr(args.arg(0)), args.arg(1)) }
    ctor.defineOwn("parseFloat", parseFloat, PropFlags.HIDDEN)
    ctor.defineOwn("parseInt", parseInt, PropFlags.HIDDEN)
    realm.defineGlobalFunction("isNaN", 1) { _, args -> realm.toNumber(args.arg(0)).isNaN() }
    realm.defineGlobalFunction("isFinite", 1) { _, args -> realm.toNumber(args.arg(0)).isFinite() }

    realm.defineMethod(ctor, "isNaN", 1) { _, args -> args.arg(0).let { it is Double && it.isNaN() } }
    realm.defineMethod(ctor, "isFinite", 1) { _, args -> args.arg(0).let { it is Double && it.isFinite() } }
    realm.defineMethod(ctor, "isInteger", 1) { _, args -> args.arg(0).let { it is Double && it.isFinite() && truncate(it) == it } }
    realm.defineMethod(ctor, "isSafeInteger", 1) { _, args ->
        args.arg(0).let { it is Double && it.isFinite() && truncate(it) == it && abs(it) <= 9007199254740991.0 }
    }

    installNumberPrototype(realm, proto)
}

/** `parseFloat`: the longest prefix of the trimmed text that is a decimal literal (or `Infinity`), NaN when there is none. */
internal fun parseFloat(text: String): Double {
    var start = 0
    while (start < text.length && isJsWhitespace(text[start])) start++
    val match = FLOAT_PREFIX.matchAt(text, start) ?: return Double.NaN
    return java.lang.Double.parseDouble(match.value)
}

/**
 * `parseInt(text, radix)`. Without a radix (or 0) the text is read as decimal, or as hexadecimal after `0x`; an explicit
 * 10 does not read `0x`, which the kernel's [jsParseInt] would.
 */
private fun parseInt(realm: Realm, text: String, radix: Any?): Double {
    val r = realm.toInt32(radix)
    if (r == 10) {
        var i = 0
        while (i < text.length && isJsWhitespace(text[i])) i++
        val negative = i < text.length && text[i] == '-'
        if (i < text.length && (text[i] == '-' || text[i] == '+')) i++
        if (text.startsWith("0x", i) || text.startsWith("0X", i)) return if (negative) -0.0 else 0.0
    }
    return jsParseInt(text, if (r == 0) 10 else r)
}

private fun installNumberPrototype(realm: Realm, proto: JsObject) {
    fun thisNumber(self: Any?, method: String): Double = when {
        self is Double -> self
        self is Number -> self.toDouble()
        self is JsBox && self.kind == "Number" -> self.primitive as Double
        else -> throw realm.typeError("Number.prototype.$method requires that 'this' be a Number")
    }
    realm.defineMethod(proto, "valueOf", 0) { self, _ -> thisNumber(self, "valueOf") }
    realm.defineMethod(proto, "toString", 1) { self, args ->
        val x = thisNumber(self, "toString")
        val radixArg = args.arg(0)
        val radix = if (radixArg === Undefined) 10.0 else realm.toIntegerOrInfinity(radixArg)
        if (radix < 2 || radix > 36) throw realm.rangeError("toString() radix argument must be between 2 and 36")
        if (radix == 10.0 || x.isNaN() || x.isInfinite() || x == 0.0) jsNumberToString(x) else radixString(x, radix.toInt())
    }
    realm.defineMethod(proto, "toLocaleString", 0) { self, _ -> localeString(thisNumber(self, "toLocaleString")) }
    realm.defineMethod(proto, "toFixed", 1) { self, args ->
        val x = thisNumber(self, "toFixed")
        val digits = realm.toIntegerOrInfinity(args.arg(0))
        if (digits < 0 || digits > 100) throw realm.rangeError("toFixed() digits argument must be between 0 and 100")
        jsToFixed(x, digits.toInt())
    }
    realm.defineMethod(proto, "toPrecision", 1) { self, args ->
        val x = thisNumber(self, "toPrecision")
        if (args.arg(0) === Undefined) return@defineMethod jsNumberToString(x)
        val p = realm.toIntegerOrInfinity(args[0])
        if (x.isNaN() || x.isInfinite()) return@defineMethod jsNumberToString(x)
        if (p < 1 || p > 100) throw realm.rangeError("toPrecision() argument must be between 1 and 100")
        toPrecision(x, p.toInt())
    }
    realm.defineMethod(proto, "toExponential", 1) { self, args ->
        val x = thisNumber(self, "toExponential")
        val f = realm.toIntegerOrInfinity(args.arg(0))
        if (x.isNaN() || x.isInfinite()) return@defineMethod jsNumberToString(x)
        if (f < 0 || f > 100) throw realm.rangeError("toExponential() argument must be between 0 and 100")
        toExponential(x, if (args.arg(0) === Undefined) null else f.toInt())
    }
}

// ---- number to text -------------------------------------------------------------------------------------------------------

/**
 * `Number.prototype.toString(radix)` for a finite non-zero number and a radix other than 10: V8's `DoubleToRadixCString`
 * (src/numbers/conversions.cc). The fraction is written until the digits identify the double, the integer part is
 * divided out with the digits beyond 2^53 filled with zeros.
 */
internal fun radixString(x: Double, radix: Int): String {
    val chars = "0123456789abcdefghijklmnopqrstuvwxyz"
    val buffer = CharArray(2200)
    val middle = buffer.size / 2
    var integerCursor = middle
    var fractionCursor = middle
    val negative = x < 0
    val value = if (negative) -x else x
    var integer = floor(value)
    var fraction = value - integer
    var delta = 0.5 * (Math.nextUp(value) - value)
    delta = max(Math.nextUp(0.0), delta)
    if (fraction >= delta) {
        buffer[fractionCursor++] = '.'
        do {
            fraction *= radix
            delta *= radix
            val digit = fraction.toInt()
            buffer[fractionCursor++] = chars[digit]
            fraction -= digit
            if (fraction > 0.5 || (fraction == 0.5 && (digit and 1) != 0)) {
                if (fraction + delta > 1) {
                    // Round up: carry through the digits already written, and into the integer part if they are all full.
                    while (true) {
                        fractionCursor--
                        if (fractionCursor == middle) {
                            integer += 1
                            break
                        }
                        val c = buffer[fractionCursor]
                        val d = if (c > '9') c - 'a' + 10 else c - '0'
                        if (d + 1 < radix) {
                            buffer[fractionCursor++] = chars[d + 1]
                            break
                        }
                    }
                    break
                }
            }
        } while (fraction >= delta)
    }
    // Digits of the integer part; what a double cannot represent (from 2^53 up) is zeros.
    while (Math.getExponent(integer) - 52 > 0) {
        integer /= radix
        buffer[--integerCursor] = '0'
    }
    do {
        val remainder = integer % radix
        buffer[--integerCursor] = chars[remainder.toInt()]
        integer = (integer - remainder) / radix
    } while (integer > 0)
    if (negative) buffer[--integerCursor] = '-'
    return String(buffer, integerCursor, fractionCursor - integerCursor)
}

/** The shortest digits that identify [x] (finite, positive) and the exponent of the first: `x = d.ddd * 10^e`. */
private fun shortestDigits(x: Double): Pair<String, Int> {
    val d = BigDecimal(jsNumberToString(x))
    val unscaled = d.unscaledValue().toString()
    val digits = unscaled.trimEnd('0').ifEmpty { "0" }
    return digits to (unscaled.length - d.scale() - 1)
}

/** [x] (finite, positive) rounded to [count] significant digits, halves up (on the exact binary value): the digits and the exponent of the first. */
private fun roundedDigits(x: Double, count: Int): Pair<String, Int> {
    val d = BigDecimal(x).round(MathContext(count, RoundingMode.HALF_UP))
    val unscaled = d.unscaledValue().toString()
    val digits = unscaled.padEnd(count, '0').substring(0, count)
    return digits to (unscaled.length - d.scale() - 1)
}

internal fun toPrecision(x: Double, p: Int): String {
    val sign = if (x < 0) "-" else ""
    val v = abs(x)
    val digits: String
    val e: Int
    if (v == 0.0) {
        digits = "0".repeat(p)
        e = 0
    } else {
        val (d, exp) = roundedDigits(v, p)
        digits = d
        e = exp
    }
    if (e < -6 || e >= p) {
        val mantissa = if (p == 1) digits else digits[0] + "." + digits.substring(1)
        return sign + mantissa + "e" + (if (e >= 0) "+" else "-") + abs(e)
    }
    if (e == p - 1) return sign + digits
    if (e >= 0) return sign + digits.substring(0, e + 1) + "." + digits.substring(e + 1)
    return sign + "0." + "0".repeat(-(e + 1)) + digits
}

internal fun toExponential(x: Double, fractionDigits: Int?): String {
    val sign = if (x < 0) "-" else ""
    val v = abs(x)
    val digits: String
    val e: Int
    if (v == 0.0) {
        digits = "0".repeat((fractionDigits ?: 0) + 1)
        e = 0
    } else if (fractionDigits == null) {
        val (d, exp) = shortestDigits(v)
        digits = d
        e = exp
    } else {
        val (d, exp) = roundedDigits(v, fractionDigits + 1)
        digits = d
        e = exp
    }
    val mantissa = if (digits.length == 1) digits else digits[0] + "." + digits.substring(1)
    return sign + mantissa + "e" + (if (e >= 0) "+" else "-") + abs(e)
}

/** `toLocaleString()` in the `en-US` locale: digit groups of three, at most three fraction digits (halves away from zero). */
private fun localeString(x: Double): String {
    if (x.isNaN()) return "NaN"
    if (x.isInfinite()) return if (x > 0) "∞" else "-∞"
    if (x == 0.0) return if (1.0 / x < 0) "-0" else "0"
    val d = BigDecimal(jsNumberToString(abs(x))).setScale(3, RoundingMode.HALF_UP).stripTrailingZeros()
    val plain = d.toPlainString()
    val dot = plain.indexOf('.')
    val whole = if (dot < 0) plain else plain.substring(0, dot)
    val fraction = if (dot < 0) "" else plain.substring(dot)
    val grouped = whole.reversed().chunked(3).joinToString(",").reversed()
    return (if (x < 0) "-" else "") + grouped + fraction
}
