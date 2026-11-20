package cosic.script.next.js.stdlib

import cosic.pattern.JsFn
import cosic.pattern.jsParseInt
import cosic.pattern.Undefined
import cosic.pattern.jsMathRound
import cosic.pattern.jsNumberToString
import cosic.pattern.jsToString
import cosic.script.next.JsClosure
import cosic.script.next.Realm
import cosic.script.next.js.AccessorProp
import cosic.script.next.js.JsArray
import cosic.script.next.js.JsFunction
import cosic.script.next.js.JsObject
import cosic.script.next.js.PropFlags
import cosic.script.next.js.arrayIndexOf
import cosic.script.next.js.isNullish
import cosic.script.next.js.toStr
import java.util.IdentityHashMap
import kotlin.math.floor
import cosic.script.next.js.toNumber
import kotlin.math.max
import kotlin.math.sqrt

/*
 * What `console.log` prints for a value: a port of the part of Node's `util.inspect` (lib/internal/util/inspect.js) that
 * applies here, with Node's defaults (depth 2, compact 3, break at 80 columns), so that a program shows the same text in
 * the console of the editor as in Node: `{ s: 'bd', n: 3 }`, `[ 1, 2, 3 ]`, `Map(1) { 'a' => 1 }`, `[Function: f]`,
 * `[Object]` below the depth, `<ref *1>` and `[Circular *1]`, numbers in columns for long arrays.
 *
 * Not ported: symbols, typed arrays, proxies, `showHidden`, getters (`[Getter]` is printed, the getter is not run),
 * the East Asian width of characters, colours.
 */

/** An object that decides what `console.log` prints for it (the host's wrappers of haps and patterns). */
internal interface JsInspectable {
    /** The text, or null to print the object like any other. */
    fun inspectText(): String?
}

private const val MAX_ARRAY_DISPLAY = 100
private const val MIN_LINE_WIDTH = 16
private const val OBJECT_ENTRY = 0
private const val ARRAY_ELEMENT = 1
private const val ARRAY_EXTRA = 2

private val BARE_KEY = Regex("^[a-zA-Z_][a-zA-Z_0-9]*$")

/** The names of the constructors whose `toString` `%s` does not call: it prints the value like `console.log`. */
private val BUILT_IN_CONSTRUCTORS = setOf(
    "Object", "Array", "Error", "TypeError", "RangeError", "ReferenceError", "SyntaxError", "EvalError", "URIError", "AggregateError",
    "Map", "Set", "Date", "Promise", "Number", "String", "Boolean", "Function",
)

internal class Inspector(private val realm: Realm, private val depth: Int? = 2) {
    private val breakLength = 80
    private val compact = 3
    private val seen = ArrayList<JsObject>()
    private var circular: IdentityHashMap<Any, Int>? = null
    private var indentationLvl = 0
    private var currentDepth = 0

    fun inspect(value: Any?): String = formatValue(value, 0)

    // ---- values -----------------------------------------------------------------------------------------------------

    private fun formatValue(value: Any?, recurseTimes: Int): String {
        if (value !is JsObject) return formatOther(value, recurseTimes)
        if (value is JsInspectable) value.inspectText()?.let { return it }
        if (seen.any { it === value }) {
            val c = circular ?: IdentityHashMap<Any, Int>().also { circular = it }
            val index = c.getOrPut(value) { c.size + 1 }
            return "[Circular *$index]"
        }
        return formatRaw(value, recurseTimes)
    }

    private fun formatOther(value: Any?, recurseTimes: Int): String = when (value) {
        null -> "null"
        Undefined -> "undefined"
        is Boolean -> value.toString()
        is Double -> formatNumber(value)
        is Number -> formatNumber(value.toDouble())
        is String -> formatString(value)
        is JsFn -> "[Function (anonymous)]"
        is List<*> -> formatValue(JsArray(realm, ArrayList<Any?>(value)), recurseTimes)
        is Map<*, *> -> formatValue(
            realm.newObject().also { o -> for ((k, v) in value) o.defineOwn(k.toString(), v, PropFlags.ALL) },
            recurseTimes,
        )
        else -> jsToString(value)
    }

    private fun formatNumber(d: Double): String = if (d == 0.0 && 1.0 / d < 0) "-0" else jsNumberToString(d)

    /** A string as a value inside another: quoted, and a long one with newlines split into lines joined by `+`. */
    private fun formatString(s: String): String {
        if (s.length > MIN_LINE_WIDTH && s.length > breakLength - indentationLvl - 4) {
            val pieces = ArrayList<String>()
            var from = 0
            for (i in s.indices) {
                if (s[i] == '\n') {
                    pieces.add(s.substring(from, i + 1))
                    from = i + 1
                }
            }
            if (from < s.length) pieces.add(s.substring(from))
            if (pieces.size > 1) return pieces.joinToString(" +\n" + " ".repeat(indentationLvl + 2)) { quote(it) }
        }
        return quote(s)
    }

    // ---- objects ----------------------------------------------------------------------------------------------------

    private enum class Kind { OBJECT, ARRAY, SET, MAP, PROMISE }

    private fun formatRaw(value: JsObject, recurseTimesIn: Int): String {
        var recurseTimes = recurseTimesIn
        val constructor = constructorName(value)
        val tag = tagOf(value, constructor)
        var keys: MutableList<String> = if (value is JsArray) {
            value.ownEnumerableKeys().filter { arrayIndexOf(it) < 0 }.toMutableList()
        } else {
            value.ownEnumerableKeys().toMutableList()
        }
        var base = ""
        var braces = arrayOf("{", "}")
        var kind = Kind.OBJECT
        var extrasType = OBJECT_ENTRY
        when {
            value is JsArray -> {
                val prefix = if (constructor != "Array" || tag != "") prefixOf(constructor, tag, "Array", "(${value.items.size})") else ""
                braces = arrayOf("$prefix[", "]")
                if (value.items.isEmpty() && keys.isEmpty()) return "${braces[0]}]"
                extrasType = ARRAY_EXTRA
                kind = Kind.ARRAY
            }
            value is JsSetObject -> {
                val prefix = prefixOf(constructor, tag, "Set", "(${value.table.size})")
                if (value.table.size == 0 && keys.isEmpty()) return "$prefix{}"
                braces = arrayOf("$prefix{", "}")
                kind = Kind.SET
            }
            value is JsMapObject -> {
                val prefix = prefixOf(constructor, tag, "Map", "(${value.table.size})")
                if (value.table.size == 0 && keys.isEmpty()) return "$prefix{}"
                braces = arrayOf("$prefix{", "}")
                kind = Kind.MAP
            }
            constructor == "Object" && value !is JsFunction && value !is JsBox && value.className != "Error" -> {
                if (tag != "") braces = arrayOf("${prefixOf(constructor, tag, "Object")}{", "}")
                if (keys.isEmpty()) return "${braces[0]}}"
            }
            value is JsFunction -> {
                base = functionBase(value, constructor, tag)
                if (keys.isEmpty()) return base
            }
            value is JsDate -> {
                val text = if (value.time.isNaN()) "Invalid Date" else isoString(value.time)
                if (keys.isEmpty()) return text
                base = text
            }
            value.className == "Error" -> {
                base = formatError(value, keys)
                if (keys.isEmpty()) return base
            }
            value is JsPromise -> {
                braces = arrayOf("${prefixOf(constructor, tag, "Promise")}{", "}")
                kind = Kind.PROMISE
            }
            value is JsBox -> {
                if (value.primitive is String) keys = keys.filter { arrayIndexOf(it) < 0 }.toMutableList()
                base = boxedBase(value, constructor, tag)
                if (keys.isEmpty()) return base
            }
            else -> {
                if (keys.isEmpty()) return "${prefixOf(constructor, tag, "Object")}{}"
                braces = arrayOf("${prefixOf(constructor, tag, "Object")}{", "}")
            }
        }
        if (depth != null && recurseTimes > depth) {
            val name = prefixOf(constructor, tag, "Object").dropLast(1)
            return if (constructor != null) "[$name]" else name
        }
        recurseTimes += 1
        seen.add(value)
        currentDepth = recurseTimes
        val output: MutableList<String>
        try {
            output = when (kind) {
                Kind.ARRAY -> formatArray(value as JsArray, recurseTimes)
                Kind.SET -> formatSet(value as JsSetObject, recurseTimes)
                Kind.MAP -> formatMap(value as JsMapObject, recurseTimes)
                Kind.PROMISE -> formatPromise(value as JsPromise, recurseTimes)
                Kind.OBJECT -> ArrayList()
            }
            for (key in keys) output.add(formatProperty(value, recurseTimes, key, extrasType))
        } finally {
            seen.removeAt(seen.size - 1)
        }
        val index = circular?.get(value)
        if (index != null) {
            val reference = "<ref *$index>"
            base = if (base.isEmpty()) reference else "$reference $base"
        }
        return reduceToSingleString(output, base, braces, extrasType, recurseTimes, value as? JsArray)
    }

    private fun formatArray(array: JsArray, recurseTimes: Int): MutableList<String> {
        val length = array.items.size
        val shown = minOf(MAX_ARRAY_DISPLAY, length)
        val output = ArrayList<String>(shown + 1)
        for (i in 0 until shown) output.add(formatProperty(array, recurseTimes, i.toString(), ARRAY_ELEMENT))
        val remaining = length - shown
        if (remaining > 0) output.add("... $remaining more item${if (remaining > 1) "s" else ""}")
        return output
    }

    private fun formatSet(set: JsSetObject, recurseTimes: Int): MutableList<String> {
        val output = ArrayList<String>()
        indentationLvl += 2
        val it = set.table.iterator { e -> e.key }
        while (it.hasNext()) output.add(formatValue(it.next(), recurseTimes))
        indentationLvl -= 2
        return output
    }

    private fun formatMap(map: JsMapObject, recurseTimes: Int): MutableList<String> {
        val output = ArrayList<String>()
        indentationLvl += 2
        val it = map.table.iterator { e -> e }
        while (it.hasNext()) {
            val e = it.next() as OrderedTable.Entry
            output.add("${formatValue(e.key, recurseTimes)} => ${formatValue(e.value, recurseTimes)}")
        }
        indentationLvl -= 2
        return output
    }

    private fun formatPromise(promise: JsPromise, recurseTimes: Int): MutableList<String> {
        val state = promise.state
        if (state == JsPromise.State.PENDING) return arrayListOf("<pending>")
        indentationLvl += 2
        val text = formatValue(promise.result, recurseTimes)
        indentationLvl -= 2
        return arrayListOf(if (state == JsPromise.State.REJECTED) "<rejected> $text" else text)
    }

    private fun formatProperty(obj: JsObject, recurseTimes: Int, key: String, type: Int): String {
        val prop = obj.getOwnProp(key)
        val str: String = when {
            prop is AccessorProp -> when {
                prop.getter != null && prop.setter != null -> "[Getter/Setter]"
                prop.getter != null -> "[Getter]"
                prop.setter != null -> "[Setter]"
                else -> "undefined"
            }
            else -> {
                val v = if (prop == null) obj.get(key) else prop.value
                if (v === Undefined) {
                    "undefined"
                } else {
                    indentationLvl += 2
                    val s = formatValue(v, recurseTimes)
                    indentationLvl -= 2
                    s
                }
            }
        }
        if (type == ARRAY_ELEMENT) return str
        val name = when {
            key == "__proto__" -> "['__proto__']"
            prop != null && !prop.enumerable -> "[$key]"
            BARE_KEY.matches(key) -> key
            else -> quote(key)
        }
        return "$name: $str"
    }

    // ---- names ------------------------------------------------------------------------------------------------------

    /** The name of the constructor of [obj], found like Node does along the prototypes; null for an object without a prototype. */
    private fun constructorName(obj: JsObject): String? {
        var o: JsObject? = obj
        var first = true
        var firstProto: JsObject? = null
        while (o != null) {
            val c = o.getOwnProp("constructor")?.value
            if (c is JsFunction && c.name.isNotEmpty() && isInstance(obj, c)) return c.name
            o = o.prototype
            if (first) {
                firstProto = o
                first = false
            }
        }
        return if (firstProto == null) null else "Object"
    }

    private fun isInstance(obj: JsObject, ctor: JsFunction): Boolean {
        val proto = ctor.getOwnProp("prototype")?.value as? JsObject ?: return false
        var p = obj.prototype
        while (p != null) {
            if (p === proto) return true
            p = p.prototype
        }
        return false
    }

    /** What `Symbol.toStringTag` is for the objects that have one: namespaces and iterators. */
    private fun tagOf(value: JsObject, constructor: String?): String {
        val tag = when {
            value is JsNamespace -> value.tag
            value is JsIterator -> value.className
            else -> ""
        }
        return if (tag == constructor) "" else tag
    }

    private fun prefixOf(constructor: String?, tag: String, fallback: String, size: String = ""): String {
        if (constructor == null) {
            return if (tag != "" && fallback != tag) "[$fallback$size: null prototype] [$tag] " else "[$fallback$size: null prototype] "
        }
        return if (tag != "" && constructor != tag) "$constructor$size [$tag] " else "$constructor$size "
    }

    private fun functionBase(fn: JsFunction, constructor: String?, tag: String): String {
        val async = fn is JsClosure && fn.node.async
        val type = if (async) "AsyncFunction" else "Function"
        val sb = StringBuilder("[").append(type)
        if (constructor == null) sb.append(" (null prototype)")
        sb.append(if (fn.name.isEmpty()) " (anonymous)" else ": ${fn.name}").append("]")
        if (constructor != null && constructor != type && !(async && constructor == "Function")) sb.append(' ').append(constructor)
        if (tag != "" && constructor != tag) sb.append(" [").append(tag).append(']')
        return sb.toString()
    }

    private fun boxedBase(box: JsBox, constructor: String?, tag: String): String {
        val sb = StringBuilder("[").append(box.kind)
        if (box.kind != constructor) sb.append(if (constructor == null) " (null prototype)" else " ($constructor)")
        val shown = if (box.primitive is String) quote(box.primitive) else jsToString(box.primitive)
        sb.append(": ").append(shown).append(']')
        if (tag != "" && tag != constructor) sb.append(" [").append(tag).append(']')
        return sb.toString()
    }

    private fun formatError(err: JsObject, keys: MutableList<String>): String {
        val stackValue = err.get("stack")
        var stack = if (stackValue is String && stackValue.isNotEmpty()) stackValue else errorText(err)
        for (name in listOf("name", "message", "stack")) {
            val at = keys.indexOf(name)
            val v = err.get(name)
            if (at >= 0 && (v !is String || stack.contains(v))) keys.removeAt(at)
        }
        if (err.has("cause") && "cause" !in keys) keys.add("cause")
        val errors = err.get("errors")
        if (errors is JsArray && "errors" !in keys) keys.add("errors")
        if (stack.indexOf("\n    at") == -1) stack = "[$stack]"
        if (indentationLvl != 0) stack = stack.replace("\n", "\n" + " ".repeat(indentationLvl))
        return stack
    }

    private fun errorText(err: JsObject): String {
        val name = err.get("name").let { if (isNullish(it)) "Error" else realm.toStr(it) }
        val message = err.get("message").let { if (isNullish(it)) "" else realm.toStr(it) }
        return if (message.isEmpty()) name else if (name.isEmpty()) message else "$name: $message"
    }

    // ---- layout -----------------------------------------------------------------------------------------------------

    private fun reduceToSingleString(
        outputIn: MutableList<String>,
        base: String,
        braces: Array<String>,
        extrasType: Int,
        recurseTimes: Int,
        array: JsArray?,
    ): String {
        var output: List<String> = outputIn
        val entries = output.size
        if (extrasType == ARRAY_EXTRA && entries > 6) output = groupArrayElements(outputIn, array)
        if (currentDepth - recurseTimes < compact && entries == output.size) {
            val start = output.size + indentationLvl + braces[0].length + base.length + 10
            if (isBelowBreakLength(output, start, base)) {
                val joined = output.joinToString(", ")
                if (!joined.contains('\n')) return (if (base.isNotEmpty()) "$base " else "") + "${braces[0]} $joined ${braces[1]}"
            }
        }
        val indentation = "\n" + " ".repeat(indentationLvl)
        return (if (base.isNotEmpty()) "$base " else "") + "${braces[0]}$indentation  ${output.joinToString(",$indentation  ")}$indentation${braces[1]}"
    }

    private fun isBelowBreakLength(output: List<String>, start: Int, base: String): Boolean {
        var total = output.size + start
        if (total + output.size > breakLength) return false
        for (s in output) {
            total += s.length
            if (total > breakLength) return false
        }
        return base.isEmpty() || !base.contains('\n')
    }

    /** Puts the entries of a long array in columns, numbers aligned to the right, when that keeps lines short. */
    private fun groupArrayElements(output: MutableList<String>, array: JsArray?): List<String> {
        var totalLength = 0
        var maxLength = 0
        var outputLength = output.size
        if (MAX_ARRAY_DISPLAY < output.size) outputLength--
        val separatorSpace = 2
        val dataLen = IntArray(outputLength)
        for (i in 0 until outputLength) {
            val len = output[i].length
            dataLen[i] = len
            totalLength += len + separatorSpace
            if (maxLength < len) maxLength = len
        }
        val actualMax = maxLength + separatorSpace
        if (actualMax * 3 + indentationLvl < breakLength && (totalLength.toDouble() / actualMax > 5 || maxLength <= 6)) {
            val approxCharHeights = 2.5
            val averageBias = sqrt(actualMax - totalLength.toDouble() / output.size)
            val biasedMax = max(actualMax - 3 - averageBias, 1.0)
            val columns = minOf(
                jsMathRound(sqrt(approxCharHeights * biasedMax * outputLength) / biasedMax).toInt(),
                floor((breakLength - indentationLvl).toDouble() / actualMax).toInt(),
                compact * 4,
                15,
            )
            if (columns <= 1) return output
            val grouped = ArrayList<String>()
            val maxLineLength = ArrayList<Int>()
            for (i in 0 until columns) {
                var lineLength = 0
                var j = i
                while (j < output.size) {
                    if (j < outputLength && dataLen[j] > lineLength) lineLength = dataLen[j]
                    j += columns
                }
                maxLineLength.add(lineLength + separatorSpace)
            }
            var padStart = true
            if (array != null) {
                for (i in 0 until output.size) {
                    if (array.items.getOrNull(i) !is Double) {
                        padStart = false
                        break
                    }
                }
            }
            var i = 0
            while (i < outputLength) {
                val end = minOf(i + columns, outputLength)
                val line = StringBuilder()
                var j = i
                while (j < end - 1) {
                    val cell = output[j] + ", "
                    line.append(if (padStart) cell.padStart(maxLineLength[j - i]) else cell.padEnd(maxLineLength[j - i]))
                    j++
                }
                if (padStart) line.append(output[j].padStart(maxLineLength[j - i] - separatorSpace)) else line.append(output[j])
                grouped.add(line.toString())
                i += columns
            }
            if (MAX_ARRAY_DISPLAY < output.size) grouped.add(output[outputLength])
            return grouped
        }
        return output
    }
}

// ---- strings ------------------------------------------------------------------------------------------------------------

/** `strEscape`: the text of a string as a quoted literal, single quotes unless the string needs others. */
internal fun quote(s: String): String {
    var quote = '\''
    if (s.contains('\'')) {
        if (!s.contains('"')) quote = '"' else if (!s.contains('`') && !s.contains("\${")) quote = '`'
    }
    val sb = StringBuilder(s.length + 2).append(quote)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        val code = c.code
        when {
            c == quote && quote == '\'' -> sb.append("\\'")
            c == '\\' -> sb.append("\\\\")
            code < 32 || (code in 127..159) -> sb.append(
                when (c) {
                    '\b' -> "\\b"
                    '\t' -> "\\t"
                    '\n' -> "\\n"
                    '\u000C' -> "\\f"
                    '\r' -> "\\r"
                    else -> "\\x" + code.toString(16).uppercase().padStart(2, '0')
                },
            )
            c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate() -> {
                sb.append(c).append(s[i + 1])
                i++
            }
            c.isHighSurrogate() || c.isLowSurrogate() -> sb.append("\\u").append(code.toString(16))
            else -> sb.append(c)
        }
        i++
    }
    return sb.append(quote).toString()
}

// ---- console.log ---------------------------------------------------------------------------------------------------------

/** `util.inspect(value)` with Node's defaults. */
internal fun Realm.inspect(value: Any?, depth: Int? = 2): String = Inspector(this, depth).inspect(value)

/**
 * `util.format(...args)`, what `console.log` writes: a first string argument is a template (`%s %d %i %f %j %o %O %c %%`),
 * the other arguments follow separated by spaces, strings as they are and everything else as [inspect] prints it.
 */
internal fun Realm.formatLog(args: List<Any?>): String {
    val first = args.arg(0)
    var a = 0
    val sb = StringBuilder()
    var join = ""
    if (first is String) {
        if (args.size == 1) return first
        var lastPos = 0
        var i = 0
        while (i < first.length - 1) {
            if (first[i] == '%') {
                val next = first[++i]
                if (a + 1 != args.size) {
                    val text: String? = when (next) {
                        's' -> {
                            val v = args[++a]
                            if (v is Double) formatLogNumber(v) else if (v !is JsObject || v is JsFunction || !hasBuiltInToString(v)) toStr(v) else inspect(v, 0)
                        }
                        'j' -> jsonForLog(args[++a])
                        'd' -> formatLogNumber(toNumber(args[++a]))
                        'O' -> inspect(args[++a])
                        'o' -> inspect(args[++a], 4)
                        'i' -> formatLogNumber(jsParseInt(toStr(args[++a]), 10))
                        'f' -> formatLogNumber(parseFloat(toStr(args[++a])))
                        'c' -> {
                            a++
                            ""
                        }
                        '%' -> {
                            sb.append(first, lastPos, i)
                            lastPos = i + 1
                            null
                        }
                        else -> null
                    }
                    if (text != null) {
                        if (lastPos != i - 1) sb.append(first, lastPos, i - 1)
                        sb.append(text)
                        lastPos = i + 1
                    }
                } else if (next == '%') {
                    sb.append(first, lastPos, i)
                    lastPos = i + 1
                }
            }
            i++
        }
        if (lastPos != 0) {
            a++
            join = " "
            if (lastPos < first.length) sb.append(first, lastPos, first.length)
        }
    }
    while (a < args.size) {
        val v = args[a]
        sb.append(join).append(if (v is String) v else inspect(v))
        join = " "
        a++
    }
    return sb.toString()
}

private fun formatLogNumber(d: Double): String = if (d == 0.0 && 1.0 / d < 0) "-0" else jsNumberToString(d)

private fun Realm.hasBuiltInToString(value: JsObject): Boolean {
    val toString = value.get("toString")
    if (toString !is JsFunction && toString !is JsFn) return true
    if (value.hasOwn("toString")) return false
    var pointer: JsObject? = value.prototype
    while (pointer != null && !pointer.hasOwn("toString")) pointer = pointer.prototype
    val ctor = pointer?.getOwnProp("constructor")?.value
    return ctor is JsFunction && ctor.name in BUILT_IN_CONSTRUCTORS
}

private fun Realm.jsonForLog(value: Any?): String = try {
    stringifyJson(this, value, Undefined, Undefined) as? String ?: "undefined"
} catch (e: cosic.script.next.js.JsThrow) {
    val m = (e.value as? JsObject)?.get("message")
    if (m is String && m.startsWith("Converting circular structure to JSON")) "[Circular]" else throw e
}
