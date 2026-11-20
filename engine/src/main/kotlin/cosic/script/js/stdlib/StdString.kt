package cosic.script.js.stdlib

import cosic.pattern.JsFn
import cosic.pattern.Undefined
import cosic.pattern.jsNumberToString
import cosic.script.Realm
import cosic.script.js.JsArray
import cosic.script.js.JsFunction
import cosic.script.js.JsObject
import cosic.script.js.MAX_STRING_LENGTH
import cosic.script.js.toInt32
import cosic.script.js.toNumber
import cosic.script.js.toStr
import cosic.script.js.toUint32
import java.text.Collator
import java.text.Normalizer
import java.util.Locale

/*
 * `String` and the methods of `String.prototype`. There are no regular expressions, so `split`, `replace` and
 * `replaceAll` take strings only and `match`, `matchAll` and `search` (which JavaScript turns into regular expressions) are
 * refused. Strings are UTF-16 like JavaScript's, so indices are the same.
 */

private const val NO_REGEXP = "Regular expressions are not supported"

/** `WhiteSpace` and `LineTerminator` of ECMA-262: what `trim`, `parseFloat` and `Number("...")` skip. */
internal fun isJsWhitespace(c: Char): Boolean = when (c) {
    '\t', '\n', '\u000B', '\u000C', '\r', ' ', '\u00A0', '\u1680', '\u2028', '\u2029', '\u202F', '\u205F', '\u3000', '\uFEFF' -> true
    else -> c in '\u2000'..'\u200A'
}

private val collator: Collator by lazy { Collator.getInstance(Locale.ENGLISH) }

internal fun installString(realm: Realm, std: StdRealm) {
    val proto = realm.stringPrototype
    val ctor = realm.constructor(
        "String", 1, proto,
        { _, args -> if (args.isEmpty()) "" else realm.toStr(args[0]) },
        { args -> JsBox(proto, "String", if (args.isEmpty()) "" else realm.toStr(args[0])) },
    )
    realm.defineGlobal("String", ctor)

    realm.defineMethod(ctor, "fromCharCode", 1) { _, args ->
        val sb = StringBuilder(args.size)
        for (a in args) sb.append((realm.toInt32(a) and 0xFFFF).toChar())
        sb.toString()
    }
    realm.defineMethod(ctor, "fromCodePoint", 1) { _, args ->
        val sb = StringBuilder(args.size)
        for (a in args) {
            val n = realm.toNumber(a)
            if (n != Math.floor(n) || n < 0 || n > 0x10FFFF || n.isNaN()) throw realm.rangeError("Invalid code point ${jsNumberToString(n)}")
            sb.appendCodePoint(n.toInt())
        }
        sb.toString()
    }
    realm.defineMethod(ctor, "raw", 1) { _, args ->
        val cooked = realm.toObject(args.arg(0))
        val raw = realm.toObject(cooked.get("raw"))
        val count = realm.toLength(raw.get("length")).toInt()
        val sb = StringBuilder()
        for (i in 0 until count) {
            sb.append(realm.toStr(raw.get(i.toString())))
            if (i + 1 < count && i + 1 < args.size) sb.append(realm.toStr(args[i + 1]))
        }
        sb.toString()
    }

    installCharacterAccess(realm, proto)
    installSearching(realm, proto)
    installTransforming(realm, proto)
    installSplittingAndReplacing(realm, proto)
}

private fun Realm.thisString(self: Any?, method: String): String = when {
    self is String -> self
    self is JsBox && self.kind == "String" -> self.primitive as String
    self == null || self === Undefined -> throw typeError("String.prototype.$method called on null or undefined")
    else -> toStr(self)
}

/** A position argument as an index into a string of [len] characters: cut to a whole number, then to `0..len`. */
private fun Realm.clampedPosition(v: Any?, len: Int): Int {
    val n = toIntegerOrInfinity(v)
    return if (n <= 0.0) 0 else if (n >= len) len else n.toInt()
}

private fun installCharacterAccess(realm: Realm, proto: JsObject) {
    realm.defineMethod(proto, "toString", 0) { self, _ -> thisStringValue(realm, self, "toString") }
    realm.defineMethod(proto, "valueOf", 0) { self, _ -> thisStringValue(realm, self, "valueOf") }
    realm.defineMethod(proto, "charAt", 1) { self, args ->
        val s = realm.thisString(self, "charAt")
        val pos = realm.toIntegerOrInfinity(args.arg(0))
        if (pos < 0 || pos >= s.length) "" else s[pos.toInt()].toString()
    }
    realm.defineMethod(proto, "charCodeAt", 1) { self, args ->
        val s = realm.thisString(self, "charCodeAt")
        val pos = realm.toIntegerOrInfinity(args.arg(0))
        if (pos < 0 || pos >= s.length) Double.NaN else s[pos.toInt()].code.toDouble()
    }
    realm.defineMethod(proto, "codePointAt", 1) { self, args ->
        val s = realm.thisString(self, "codePointAt")
        val pos = realm.toIntegerOrInfinity(args.arg(0))
        if (pos < 0 || pos >= s.length) Undefined else s.codePointAt(pos.toInt()).toDouble()
    }
    realm.defineMethod(proto, "at", 1) { self, args ->
        val s = realm.thisString(self, "at")
        val rel = realm.toIntegerOrInfinity(args.arg(0))
        val k = if (rel >= 0) rel else s.length + rel
        if (k < 0 || k >= s.length) Undefined else s[k.toInt()].toString()
    }
    realm.defineMethod(proto, "isWellFormed", 0) { self, _ -> wellFormed(realm.thisString(self, "isWellFormed")) == null }
    realm.defineMethod(proto, "toWellFormed", 0) { self, _ ->
        val s = realm.thisString(self, "toWellFormed")
        wellFormed(s) ?: s
    }
    realm.defineMethod(proto, "concat", 1) { self, args ->
        val sb = StringBuilder(realm.thisString(self, "concat"))
        for (a in args) {
            sb.append(realm.toStr(a))
            if (sb.length > MAX_STRING_LENGTH) throw realm.rangeError("Invalid string length")
        }
        sb.toString()
    }
}

private fun thisStringValue(realm: Realm, self: Any?, method: String): String = when {
    self is String -> self
    self is JsBox && self.kind == "String" -> self.primitive as String
    else -> throw realm.typeError("String.prototype.$method requires that 'this' be a String")
}

/** [s] with every lone surrogate replaced by U+FFFD, or null when it has none. */
private fun wellFormed(s: String): String? {
    var out: StringBuilder? = null
    var i = 0
    while (i < s.length) {
        val c = s[i]
        val lone = if (c.isHighSurrogate()) !(i + 1 < s.length && s[i + 1].isLowSurrogate()) else c.isLowSurrogate()
        if (lone && out == null) out = StringBuilder(s.length).append(s, 0, i)
        if (c.isHighSurrogate() && !lone) {
            out?.append(c)?.append(s[i + 1])
            i += 2
            continue
        }
        out?.append(if (lone) '\uFFFD' else c)
        i++
    }
    return out?.toString()
}

private fun installSearching(realm: Realm, proto: JsObject) {
    realm.defineMethod(proto, "indexOf", 1) { self, args ->
        val s = realm.thisString(self, "indexOf")
        val search = realm.toStr(args.arg(0))
        s.indexOf(search, realm.clampedPosition(args.arg(1), s.length)).toDouble()
    }
    realm.defineMethod(proto, "lastIndexOf", 1) { self, args ->
        val s = realm.thisString(self, "lastIndexOf")
        val search = realm.toStr(args.arg(0))
        val n = realm.toNumber(args.arg(1))
        val start = if (n.isNaN()) s.length else realm.clampedPosition(n, s.length)
        if (search.length > s.length) -1.0 else s.lastIndexOf(search, minOf(start, s.length - search.length)).toDouble()
    }
    realm.defineMethod(proto, "includes", 1) { self, args ->
        val s = realm.thisString(self, "includes")
        val search = realm.toStr(args.arg(0))
        s.indexOf(search, realm.clampedPosition(args.arg(1), s.length)) >= 0
    }
    realm.defineMethod(proto, "startsWith", 1) { self, args ->
        val s = realm.thisString(self, "startsWith")
        val search = realm.toStr(args.arg(0))
        s.startsWith(search, realm.clampedPosition(args.arg(1), s.length))
    }
    realm.defineMethod(proto, "endsWith", 1) { self, args ->
        val s = realm.thisString(self, "endsWith")
        val search = realm.toStr(args.arg(0))
        val end = if (args.arg(1) === Undefined) s.length else realm.clampedPosition(args[1], s.length)
        val start = end - search.length
        start >= 0 && s.startsWith(search, start)
    }
    realm.defineMethod(proto, "localeCompare", 1) { self, args ->
        val s = realm.thisString(self, "localeCompare")
        Integer.signum(collator.compare(s, realm.toStr(args.arg(0)))).toDouble()
    }
    for (name in listOf("match", "matchAll", "search")) {
        realm.defineMethod(proto, name, 1) { self, _ ->
            realm.thisString(self, name)
            throw realm.typeError(NO_REGEXP)
        }
    }
}

private fun installTransforming(realm: Realm, proto: JsObject) {
    realm.defineMethod(proto, "slice", 2) { self, args ->
        val s = realm.thisString(self, "slice")
        val start = cosic.pattern.jsSliceIndex(realm.toNumber(args.arg(0)), s.length)
        val end = if (args.arg(1) === Undefined) s.length else cosic.pattern.jsSliceIndex(realm.toNumber(args.arg(1)), s.length)
        if (start < end) s.substring(start, end) else ""
    }
    realm.defineMethod(proto, "substring", 2) { self, args ->
        val s = realm.thisString(self, "substring")
        val a = realm.clampedPosition(args.arg(0), s.length)
        val b = if (args.arg(1) === Undefined) s.length else realm.clampedPosition(args[1], s.length)
        s.substring(minOf(a, b), maxOf(a, b))
    }
    realm.defineMethod(proto, "substr", 2) { self, args ->
        val s = realm.thisString(self, "substr")
        val start = cosic.pattern.jsSliceIndex(realm.toNumber(args.arg(0)), s.length)
        val length = if (args.arg(1) === Undefined) Double.POSITIVE_INFINITY else realm.toIntegerOrInfinity(args[1])
        val end = minOf(start + maxOf(length, 0.0), s.length.toDouble()).toInt()
        if (start < end) s.substring(start, end) else ""
    }
    realm.defineMethod(proto, "toUpperCase", 0) { self, _ -> realm.thisString(self, "toUpperCase").uppercase(Locale.ROOT) }
    realm.defineMethod(proto, "toLowerCase", 0) { self, _ -> realm.thisString(self, "toLowerCase").lowercase(Locale.ROOT) }
    realm.defineMethod(proto, "toLocaleUpperCase", 0) { self, _ -> realm.thisString(self, "toLocaleUpperCase").uppercase(Locale.ROOT) }
    realm.defineMethod(proto, "toLocaleLowerCase", 0) { self, _ -> realm.thisString(self, "toLocaleLowerCase").lowercase(Locale.ROOT) }
    realm.defineMethod(proto, "trim", 0) { self, _ -> trimJs(realm.thisString(self, "trim"), start = true, end = true) }
    realm.defineMethod(proto, "trimStart", 0) { self, _ -> trimJs(realm.thisString(self, "trimLeft"), start = true, end = false) }
    realm.defineMethod(proto, "trimEnd", 0) { self, _ -> trimJs(realm.thisString(self, "trimRight"), start = false, end = true) }
    realm.alias(proto, "trimLeft", "trimStart")
    realm.alias(proto, "trimRight", "trimEnd")
    realm.defineMethod(proto, "padStart", 1) { self, args -> pad(realm, realm.thisString(self, "padStart"), args, atStart = true) }
    realm.defineMethod(proto, "padEnd", 1) { self, args -> pad(realm, realm.thisString(self, "padEnd"), args, atStart = false) }
    realm.defineMethod(proto, "repeat", 1) { self, args ->
        val s = realm.thisString(self, "repeat")
        val n = realm.toIntegerOrInfinity(args.arg(0))
        if (n < 0 || n.isInfinite()) throw realm.rangeError("Invalid count value: ${realm.toStr(args.arg(0))}")
        if (s.isEmpty() || n == 0.0) return@defineMethod ""
        if (s.length * n > MAX_STRING_LENGTH) throw realm.rangeError("Invalid string length")
        s.repeat(n.toInt())
    }
    realm.defineMethod(proto, "normalize", 0) { self, args ->
        val s = realm.thisString(self, "normalize")
        val form = if (args.arg(0) === Undefined) "NFC" else realm.toStr(args[0])
        val f = when (form) {
            "NFC" -> Normalizer.Form.NFC
            "NFD" -> Normalizer.Form.NFD
            "NFKC" -> Normalizer.Form.NFKC
            "NFKD" -> Normalizer.Form.NFKD
            else -> throw realm.rangeError("The normalization form should be one of NFC, NFD, NFKC, NFKD.")
        }
        Normalizer.normalize(s, f)
    }
}

private fun trimJs(s: String, start: Boolean, end: Boolean): String {
    var a = 0
    var b = s.length
    if (start) while (a < b && isJsWhitespace(s[a])) a++
    if (end) while (b > a && isJsWhitespace(s[b - 1])) b--
    return s.substring(a, b)
}

private fun pad(realm: Realm, s: String, args: List<Any?>, atStart: Boolean): String {
    val target = realm.toLength(args.arg(0))
    if (target <= s.length) return s
    val filler = if (args.arg(1) === Undefined) " " else realm.toStr(args[1])
    if (filler.isEmpty()) return s
    if (target > MAX_STRING_LENGTH) throw realm.rangeError("Invalid string length")
    val fillLength = target.toInt() - s.length
    val fill = StringBuilder(fillLength)
    while (fill.length < fillLength) fill.append(filler)
    fill.setLength(fillLength)
    return if (atStart) fill.toString() + s else s + fill
}

private fun installSplittingAndReplacing(realm: Realm, proto: JsObject) {
    realm.defineMethod(proto, "split", 2) { self, args ->
        val s = realm.thisString(self, "split")
        val limit = if (args.arg(1) === Undefined) 4294967295L else realm.toUint32(args[1])
        val separator = args.arg(0)
        val sep = if (separator === Undefined) null else realm.toStr(separator)
        val parts = ArrayList<Any?>()
        when {
            limit == 0L -> Unit
            sep == null -> parts.add(s)
            s.isEmpty() -> if (sep.isNotEmpty()) parts.add(s)
            sep.isEmpty() -> for (c in s) {
                if (parts.size >= limit) break
                parts.add(c.toString())
            }
            else -> {
                var from = 0
                while (parts.size < limit) {
                    val at = s.indexOf(sep, from)
                    if (at < 0) {
                        parts.add(s.substring(from))
                        break
                    }
                    parts.add(s.substring(from, at))
                    from = at + sep.length
                }
            }
        }
        JsArray(realm, parts)
    }
    realm.defineMethod(proto, "replace", 2) { self, args ->
        val s = realm.thisString(self, "replace")
        val search = realm.toStr(args.arg(0))
        val replacement = args.arg(1)
        val at = s.indexOf(search)
        if (at < 0) s else s.substring(0, at) + replacementFor(realm, s, search, at, replacement) + s.substring(at + search.length)
    }
    realm.defineMethod(proto, "replaceAll", 2) { self, args ->
        val s = realm.thisString(self, "replaceAll")
        val search = realm.toStr(args.arg(0))
        val replacement = args.arg(1)
        val positions = ArrayList<Int>()
        val advance = maxOf(1, search.length)
        var at = s.indexOf(search)
        while (at >= 0) {
            positions.add(at)
            at = if (at + advance > s.length) -1 else s.indexOf(search, at + advance)
        }
        var end = 0
        val sb = StringBuilder()
        for (p in positions) {
            sb.append(s, end, p).append(replacementFor(realm, s, search, p, replacement))
            end = p + search.length
            if (sb.length > MAX_STRING_LENGTH) throw realm.rangeError("Invalid string length")
        }
        if (end < s.length) sb.append(s, end, s.length)
        sb.toString()
    }
}

/** What replaces the match of [search] at [position] in [s]: the function's result, or the template with its `$` patterns expanded. */
private fun replacementFor(realm: Realm, s: String, search: String, position: Int, replacement: Any?): String {
    if (replacement is JsFunction || replacement is JsFn) {
        return realm.toStr(realm.callFunction(replacement, Undefined, listOf(search, position.toDouble(), s)))
    }
    val template = realm.toStr(replacement)
    if (template.indexOf('$') < 0) return template
    val sb = StringBuilder()
    var i = 0
    while (i < template.length) {
        val c = template[i]
        if (c == '$' && i + 1 < template.length) {
            when (template[i + 1]) {
                '$' -> { sb.append('$'); i += 2; continue }
                '&' -> { sb.append(search); i += 2; continue }
                '`' -> { sb.append(s, 0, position); i += 2; continue }
                '\'' -> { sb.append(s, position + search.length, s.length); i += 2; continue }
            }
        }
        sb.append(c)
        i++
    }
    return sb.toString()
}
