package cosic.script.js.stdlib

import cosic.pattern.JsFn
import cosic.pattern.Undefined
import cosic.pattern.jsonStringify
import cosic.script.Realm
import cosic.script.js.JsArray
import cosic.script.js.JsFunction
import cosic.script.js.JsObject
import cosic.script.js.PropFlags
import cosic.script.js.toNumber
import cosic.script.js.toStr

/*
 * `JSON.parse` and `JSON.stringify`. Primitives are written by the kernel's `jsonStringify` (the one place that knows how
 * a string is quoted and a number printed); this file walks objects, which the kernel cannot see. The errors are V8's
 * (Node 25): `Unexpected token 'x', "[1,x]" is not valid JSON`, `Expected ',' or ']' after array element in JSON at
 * position 4 (line 1 column 5)`, the circular structure report with its path.
 */

/**
 * A namespace object (`Math`, `JSON`, `console`): shared, and tagged for the inspector (`Object [Math] {}`). Its class stays
 * `Object`, which is what V8 puts in messages (`#<Object>`).
 */
internal class JsNamespace(realm: Realm, val tag: String) : JsObject(realm.objectPrototype, shared = true)

private const val MAX_DEPTH = 2000

internal fun installJson(realm: Realm, std: StdRealm) {
    val json = JsNamespace(realm, "JSON")
    realm.defineGlobal("JSON", json)
    realm.defineMethod(json, "parse", 2) { _, args -> parseJson(realm, realm.toStr(args.arg(0)), args.arg(1)) }
    realm.defineMethod(json, "stringify", 3) { _, args -> stringifyJson(realm, args.arg(0), args.arg(1), args.arg(2)) }
}

// ---- parse ------------------------------------------------------------------------------------------------------------

private enum class Token { EOS, NUMBER, STRING, WHITESPACE, ILLEGAL, OTHER }

private fun tokenOf(c: Char): Token = when {
    c == '"' -> Token.STRING
    c == '-' || c in '0'..'9' -> Token.NUMBER
    c == ' ' || c == '\t' || c == '\n' || c == '\r' -> Token.WHITESPACE
    c == '{' || c == '}' || c == '[' || c == ']' || c == ':' || c == ',' || c == 't' || c == 'f' || c == 'n' -> Token.OTHER
    else -> Token.ILLEGAL
}

private class JsonParser(val realm: Realm, val text: String) {
    var pos = 0

    fun parse(): Any? {
        val v = value(0)
        skip()
        if (pos < text.length) fail("Unexpected non-whitespace character after JSON")
        return v
    }

    private fun skip() {
        while (pos < text.length) {
            val c = text[pos]
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++ else break
        }
    }

    private fun cur(): Int = if (pos < text.length) text[pos].code else -1

    /** The error V8 reports at [pos]: [message] names what was expected, or null for the generic words about the token there. */
    private fun fail(message: String?): Nothing {
        if (message != null) throw realm.syntaxError("$message${if (message.endsWith("after JSON")) "" else " in JSON"} at ${location()}")
        throw realm.syntaxError(unexpectedTokenMessage())
    }

    private fun location(): String {
        var line = 1
        var lastBreak = 0
        var i = 0
        val end = minOf(pos, text.length)
        while (i < end) {
            if (text[i] == '\r' && i < end - 1 && text[i + 1] == '\n') i++
            if (text[i] == '\r' || text[i] == '\n') {
                line++
                lastBreak = i + 1
            }
            i++
        }
        return "position $pos (line $line column ${1 + i - lastBreak})"
    }

    private fun unexpectedTokenMessage(): String {
        val token = if (pos >= text.length) Token.EOS else tokenOf(text[pos])
        return when (token) {
            Token.EOS -> "Unexpected end of JSON input"
            Token.NUMBER -> "Unexpected number in JSON at ${location()}"
            Token.STRING -> "Unexpected string in JSON at ${location()}"
            else -> {
                if (text == "NaN" || text == "Infinity" || text == "undefined" || text == "[object Object]") return "\"$text\" is not valid JSON"
                val char = text[pos]
                if (text.length < 21) return "Unexpected token '$char', \"$text\" is not valid JSON"
                when {
                    pos < 10 -> "Unexpected token '$char', \"${text.substring(0, pos + 10)}\"... is not valid JSON"
                    pos < text.length - 10 -> "Unexpected token '$char', ...\"${text.substring(pos - 10, pos + 10)}\"... is not valid JSON"
                    else -> "Unexpected token '$char', ...\"${text.substring(pos - 10)}\" is not valid JSON"
                }
            }
        }
    }

    private fun value(depth: Int): Any? {
        if (depth > MAX_DEPTH) throw realm.rangeError("Maximum call stack size exceeded")
        skip()
        val c = cur()
        return when {
            c == '"'.code -> string()
            c == '-'.code || (c >= '0'.code && c <= '9'.code) -> number()
            c == '{'.code -> obj(depth)
            c == '['.code -> array(depth)
            c == 't'.code -> literal("true", true)
            c == 'f'.code -> literal("false", false)
            c == 'n'.code -> literal("null", null)
            else -> fail(null)
        }
    }

    private fun literal(word: String, v: Any?): Any? {
        for (i in word.indices) {
            if (pos >= text.length || text[pos] != word[i]) fail(null)
            pos++
        }
        return v
    }

    private fun obj(depth: Int): Any? {
        pos++
        val result = realm.newObject()
        skip()
        if (cur() == '}'.code) {
            pos++
            return result
        }
        var expected = "Expected property name or '}'"
        while (true) {
            skip()
            if (cur() != '"'.code) fail(expected)
            val key = string()
            skip()
            if (cur() != ':'.code) fail("Expected ':' after property name")
            pos++
            result.defineOwn(key, value(depth + 1), PropFlags.ALL)
            skip()
            if (cur() == ','.code) {
                pos++
                expected = "Expected double-quoted property name"
                continue
            }
            if (cur() != '}'.code) fail("Expected ',' or '}' after property value")
            pos++
            return result
        }
    }

    private fun array(depth: Int): Any? {
        pos++
        val items = ArrayList<Any?>()
        skip()
        if (cur() == ']'.code) {
            pos++
            return JsArray(realm, items)
        }
        while (true) {
            items.add(value(depth + 1))
            skip()
            if (cur() == ','.code) {
                pos++
                continue
            }
            if (cur() != ']'.code) fail("Expected ',' or ']' after array element")
            pos++
            return JsArray(realm, items)
        }
    }

    private fun string(): String {
        pos++
        var sb: StringBuilder? = null
        var chunk = pos
        while (true) {
            if (pos >= text.length) fail("Unterminated string")
            val c = text[pos]
            if (c == '"') {
                val s = if (sb == null) text.substring(chunk, pos) else sb.append(text, chunk, pos).toString()
                pos++
                return s
            }
            if (c == '\\') {
                if (sb == null) sb = StringBuilder()
                sb.append(text, chunk, pos)
                pos++
                val e = cur()
                if (e == -1) fail(null)
                when (e) {
                    '"'.code, '\\'.code, '/'.code -> sb.append(e.toChar())
                    'b'.code -> sb.append('\b')
                    'f'.code -> sb.append('\u000C')
                    'n'.code -> sb.append('\n')
                    'r'.code -> sb.append('\r')
                    't'.code -> sb.append('\t')
                    'u'.code -> {
                        var code = 0
                        for (i in 0 until 4) {
                            pos++
                            val digit = if (pos < text.length) Character.digit(text[pos], 16) else -1
                            if (digit < 0) fail("Bad Unicode escape")
                            code = code * 16 + digit
                        }
                        sb.append(code.toChar())
                    }
                    else -> fail("Bad escaped character")
                }
                pos++
                chunk = pos
                continue
            }
            if (c < ' ') fail("Bad control character in string literal")
            pos++
        }
    }

    private fun number(): Double {
        val start = pos
        if (cur() == '-'.code) pos++
        if (cur() == '0'.code) {
            pos++
            if (cur() >= '0'.code && cur() <= '9'.code) fail(null)
        } else {
            if (!(cur() >= '0'.code && cur() <= '9'.code)) fail("No number after minus sign")
            digits()
        }
        if (cur() == '.'.code) {
            pos++
            if (!(cur() >= '0'.code && cur() <= '9'.code)) fail("Unterminated fractional number")
            digits()
        }
        if (cur() == 'e'.code || cur() == 'E'.code) {
            pos++
            if (cur() == '-'.code || cur() == '+'.code) pos++
            if (!(cur() >= '0'.code && cur() <= '9'.code)) fail("Exponent part is missing a number")
            digits()
        }
        return java.lang.Double.parseDouble(text.substring(start, pos))
    }

    private fun digits() {
        while (cur() >= '0'.code && cur() <= '9'.code) pos++
    }
}

private fun parseJson(realm: Realm, text: String, reviver: Any?): Any? {
    val result = JsonParser(realm, text).parse()
    if (reviver !is JsFunction && reviver !is JsFn) return result
    val root = realm.newObject()
    root.defineOwn("", result, PropFlags.ALL)
    return revive(realm, root, "", reviver)
}

/** `InternalizeJSONProperty`: the reviver sees each value after the ones inside it, and may replace or delete it. */
private fun revive(realm: Realm, holder: JsObject, name: String, reviver: Any?): Any? {
    val v = holder.get(name)
    if (v is JsObject) {
        val keys = if (v is JsArray) List(v.items.size) { it.toString() } else v.ownEnumerableKeys()
        for (key in keys) {
            val revived = revive(realm, v, key, reviver)
            if (revived === Undefined) v.delete(key) else v.defineOwn(key, revived, PropFlags.ALL)
        }
    }
    return realm.callFunction(reviver, holder, listOf(name, v))
}

// ---- stringify --------------------------------------------------------------------------------------------------------

private class Stringifier(
    val realm: Realm,
    val replacer: Any?,
    val propertyList: List<String>?,
    val gap: String,
) {
    /** The objects being written, with the key each was reached by (an `Int` for an array element, else the property name). */
    private val stack = ArrayList<Pair<Any, JsObject>>()

    fun property(key: String, keyForStack: Any, holder: Any?, valueIn: Any?, indent: String): String? {
        var value = valueIn
        if (value is JsObject) {
            val toJson = value.get("toJSON")
            if (toJson is JsFunction || toJson is JsFn) value = realm.callFunction(toJson, value, listOf(key))
        }
        if (replacer != null) value = realm.callFunction(replacer, holder, listOf(key, value))
        if (value is JsBox) {
            value = when (value.kind) {
                "Number" -> realm.toNumber(value)
                "String" -> realm.toStr(value)
                else -> value.primitive
            }
        }
        return when (value) {
            null -> "null"
            Undefined -> null
            is Boolean, is Double, is Number, is String -> jsonStringify(value)
            is JsFunction -> null
            is JsArray -> array(value, keyForStack, indent)
            is JsObject -> obj(value, keyForStack, indent)
            else -> jsonStringify(value)
        }
    }

    private fun enter(key: Any, o: JsObject) {
        val at = stack.indexOfFirst { it.second === o }
        if (at >= 0) throw realm.typeError(circularMessage(at, key))
        if (stack.size >= MAX_DEPTH) throw realm.rangeError("Maximum call stack size exceeded")
        stack.add(key to o)
    }

    private fun obj(o: JsObject, key: Any, indent: String): String {
        enter(key, o)
        val stepback = indent
        val inner = indent + gap
        val keys = propertyList ?: o.ownEnumerableKeys()
        val parts = ArrayList<String>()
        for (k in keys) {
            val s = property(k, k, o, o.get(k), inner) ?: continue
            parts.add(jsonStringify(k) + ":" + (if (gap.isNotEmpty()) " " else "") + s)
        }
        stack.removeAt(stack.size - 1)
        return join(parts, "{", "}", stepback, inner)
    }

    private fun array(a: JsArray, key: Any, indent: String): String {
        enter(key, a)
        val inner = indent + gap
        val parts = ArrayList<String>(a.items.size)
        var i = 0
        while (i < a.items.size) {
            parts.add(property(i.toString(), i, a, a.items[i], inner) ?: "null")
            i++
        }
        stack.removeAt(stack.size - 1)
        return join(parts, "[", "]", indent, inner)
    }

    private fun join(parts: List<String>, open: String, close: String, stepback: String, inner: String): String {
        if (parts.isEmpty()) return open + close
        if (gap.isEmpty()) return parts.joinToString(",", open, close)
        return parts.joinToString(",\n$inner", "$open\n$inner", "\n$stepback$close")
    }

    /** V8's report: where the circle starts, the first two and the last of the objects on the way, and the key that closes it. */
    private fun circularMessage(start: Int, lastKey: Any): String {
        val sb = StringBuilder("Converting circular structure to JSON")
        fun describe(o: JsObject) = "object with constructor '${constructorName(o)}'"
        fun keyText(k: Any) = if (k is Int) "index $k" else "property '$k'"
        var index = start
        sb.append("\n    --> starting at ").append(describe(stack[index++].second))
        val prefixEnd = minOf(stack.size, index + 2)
        while (index < prefixEnd) {
            sb.append("\n    |     ").append(keyText(stack[index].first)).append(" -> ").append(describe(stack[index].second))
            index++
        }
        if (stack.size > index + 1) sb.append("\n    |     ...")
        index = maxOf(index, stack.size - 1)
        while (index < stack.size) {
            sb.append("\n    |     ").append(keyText(stack[index].first)).append(" -> ").append(describe(stack[index].second))
            index++
        }
        sb.append("\n    --- ").append(keyText(lastKey)).append(" closes the circle")
        return sb.toString()
    }

    private fun constructorName(o: JsObject): String {
        var p: JsObject? = o
        while (p != null) {
            val c = p.getOwnProp("constructor")?.value
            if (c is JsFunction && c.name.isNotEmpty()) return c.name
            p = p.prototype
        }
        return "Object"
    }
}

/** `JSON.stringify(value, replacer, space)`: the text, or `undefined` for a value that has none. */
internal fun stringifyJson(realm: Realm, value: Any?, replacerArg: Any?, spaceArg: Any?): Any? {
    var replacer: Any? = null
    var propertyList: List<String>? = null
    if (replacerArg is JsFunction || replacerArg is JsFn) {
        replacer = replacerArg
    } else if (replacerArg is JsArray) {
        val names = LinkedHashSet<String>()
        for (v in replacerArg.items) {
            val name = when {
                v is String -> v
                v is Double || v is Number -> realm.toStr(v)
                v is JsBox && (v.kind == "String" || v.kind == "Number") -> realm.toStr(v)
                else -> null
            }
            if (name != null) names.add(name)
        }
        propertyList = names.toList()
    }
    var space = spaceArg
    if (space is JsBox) space = if (space.kind == "Number") realm.toNumber(space) else if (space.kind == "String") realm.toStr(space) else space
    val gap = when (space) {
        is Double, is Number -> " ".repeat(minOf(10.0, realm.toIntegerOrInfinity(space)).toInt().coerceAtLeast(0))
        is String -> space.take(10)
        else -> ""
    }
    val wrapper = realm.newObject()
    wrapper.defineOwn("", value, PropFlags.ALL)
    return Stringifier(realm, replacer, propertyList, gap).property("", "", wrapper, value, "") ?: Undefined
}
