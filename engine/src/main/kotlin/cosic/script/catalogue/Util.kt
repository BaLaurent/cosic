package cosic.script.catalogue

import cosic.pattern.CosicError
import cosic.pattern.Hap
import cosic.pattern.JsFn
import cosic.pattern.MAX_COUNT
import cosic.pattern.Pattern
import cosic.pattern.Rational
import cosic.pattern.Undefined
import cosic.pattern.callFn
import cosic.pattern.drawLine
import cosic.pattern.freqToMidi
import cosic.pattern.getAccidentalsOffset
import cosic.pattern.getFreq
import cosic.pattern.getFrequency
import cosic.pattern.getSoundIndex
import cosic.pattern.isNote
import cosic.pattern.isNoteWithOctave
import cosic.pattern.jsFn
import cosic.pattern.jsLooseEq
import cosic.pattern.jsMod
import cosic.pattern.jsNumberToString
import cosic.pattern.jsRotate
import cosic.pattern.jsSliceIndex
import cosic.pattern.jsSplitAt
import cosic.pattern.jsToNumber
import cosic.pattern.jsToPrimitive
import cosic.pattern.jsToString
import cosic.pattern.jsTruthy
import cosic.pattern.jsTypeof
import cosic.pattern.jsonStringify
import cosic.pattern.midi2note
import cosic.pattern.midiToFreq
import cosic.pattern.nanFallback
import cosic.pattern.noteToMidi
import cosic.pattern.parseFractional
import cosic.pattern.parseNumeral
import cosic.pattern.stringifyValues
import cosic.pattern.tokenizeNote
import cosic.pattern.valueToMidi
import cosic.script.Realm
import cosic.script.js.JsFunction
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * The plain functions of util.mjs that a program can call (every export of that file is a global of Strudel's scope):
 * the note and frequency conversions, the list helpers, the functions that make functions (`pipe`, `compose`, `curry`,
 * `numeralArgs`...), the encoding of code into a link, and `drawLine`. The conversions are those of `pattern/Music.kt`
 * and `pattern/JsValue.kt`; what a function does with a value that is not the kind it is meant for follows JavaScript,
 * which is why most of the code below reads values with the `js...` helpers.
 *
 * Not here: `ClockCollator`, `getCurrentKeyboardState` and `keyAlias`, which belong to the timing and the keyboard of
 * the host. `uniqsort` does not sort its argument in place (a list handed to a native function is a copy).
 */
internal fun registerUtil() = catalogue("Util") {
    registerNotes()
    registerLists()
    registerFunctionMakers()
    registerEncoding()
    registerDrawLine()
}

// ---- notes and frequencies -----------------------------------------------------------------------------------------

private fun CatalogueBuilder.registerNotes() {
    function(listOf("isNoteWithOctave"), 1) { ctx -> isNoteWithOctave(ctx.kernelArg(0)) }
    function(listOf("isNote"), 1) { ctx -> isNote(ctx.kernelArg(0)) }
    function(listOf("tokenizeNote"), 1) { ctx ->
        val tokens = tokenizeNote(ctx.kernelArg(0))
        if (tokens == null) emptyList<Any?>() else listOf(tokens.first, tokens.second, tokens.third ?: Undefined)
    }
    function(listOf("getAccidentalsOffset"), 1) { ctx -> accidentalsOffset(ctx.kernelArg(0)) }
    function(listOf("noteToMidi"), 1) { ctx ->
        val note = ctx.kernelArg(0)
        val defaultOctave = ctx.kernelArg(1)
        // a note without an octave is in the default one: (octave + 1) * 12 + the pitch, with the octave 0 plus what is missing
        if (defaultOctave === Undefined || tokenizeNote(note)?.third != null) noteToMidi(note) else noteToMidi(note, 0) + 12 * jsToNumber(defaultOctave)
    }
    function(listOf("midiToFreq"), 1) { ctx -> midiToFreq(jsToNumber(ctx.kernelArg(0))) }
    function(listOf("freqToMidi"), 1) { ctx -> freqToMidi(jsToNumber(ctx.kernelArg(0))) }
    function(listOf("valueToMidi"), 2) { ctx ->
        val fallback = ctx.kernelArg(1)
        valueToMidi(ctx.kernelArg(0), if (jsTruthy(fallback)) jsToNumber(fallback) else null)
    }
    function(listOf("midi2note"), 1) { ctx ->
        // `pcs[n % 12]` is undefined for an index that is no whole number from 0 to 11, and `undefined + octave` is NaN
        val n = jsToNumber(ctx.kernelArg(0))
        val index = n % 12
        if (index >= 0 && index == Math.floor(index)) midi2note(n) else Double.NaN
    }
    function(listOf("getEventOffsetMs"), 2) { ctx -> (jsToNumber(ctx.kernelArg(0)) - jsToNumber(ctx.kernelArg(1))) * 1000 }
    function(listOf("getFrequency", "getFreq"), 1) { ctx -> getFrequency(ctx.kernelArg(0)) }
    function(listOf("getSoundIndex"), 2) { ctx -> getSoundIndex(ctx.kernelArg(0), jsToNumber(ctx.kernelArg(1)).toInt()).toDouble() }
    function(listOf("getPlayableNoteValue"), 1) { ctx -> playableNoteValue(ctx.kernelArg(0)) }
    function(listOf("sol2note"), 1) { ctx -> sol2note(jsToNumber(ctx.kernelArg(0)), ctx.kernelArg(1)) }
    function(listOf("parseNumeral"), 1) { ctx -> parseNumeral(ctx.kernelArg(0)) }
    function(listOf("parseFractional"), 1) { ctx -> parseFractional(ctx.kernelArg(0)) }
}

/** `accidentals?.split('').reduce((o, char) => o + accs[char], 0) || 0`: a character that is no accidental makes the sum NaN, which is 0. */
private fun accidentalsOffset(accidentals: Any?): Double = when (accidentals) {
    null, Undefined -> 0.0
    is String -> if (accidentals.all { it in "#bsf" }) getAccidentalsOffset(accidentals).toDouble() else 0.0
    else -> throw CosicError("accidentals.split is not a function")
}

private val SOLFEGGIO = listOf("Do", "Reb", "Re", "Mib", "Mi", "Fa", "Solb", "Sol", "Lab", "La", "Sib", "Si")
private val INDIAN = listOf("Sa", "Re", "Ga", "Ma", "Pa", "Dha", "Ni")
private val GERMAN = listOf("C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Hb", "H")
private val BYZANTINE = listOf("Ni", "Pab", "Pa", "Voub", "Vou", "Ga", "Dib", "Di", "Keb", "Ke", "Zob", "Zo")
private val JAPANESE = listOf("I", "Ro", "Ha", "Ni", "Ho", "He", "To")
private val ENGLISH = listOf("C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Bb", "B")

/** `sol2note(n, notation = 'letters')`: the name of MIDI note [n] in one of the notations, `NaN` where the notation has no name for its pitch. */
private fun sol2note(n: Double, notation: Any?): Any {
    val names = when (if (notation === Undefined) "letters" else notation) {
        "solfeggio" -> SOLFEGGIO
        "indian" -> INDIAN
        "german" -> GERMAN
        "byzantine" -> BYZANTINE
        "japanese" -> JAPANESE
        else -> ENGLISH
    }
    val index = n % 12
    val name = if (index >= 0 && index == Math.floor(index) && index < names.size) names[index.toInt()] else return Double.NaN
    return name + jsNumberToString(Math.floor(n / 12) - 1)
}

/** `getPlayableNoteValue(hap)`: the note a hap plays, as the frequency of a number or as the name of a note. */
private fun playableNoteValue(hap: Any?): Any? {
    val value: Any?
    val context: Any?
    when (hap) {
        is Hap -> {
            value = hap.value
            context = hap.context.extra
        }
        is Map<*, *> -> {
            value = if (hap.containsKey("value")) hap["value"] else Undefined
            context = if (hap.containsKey("context")) hap["context"] else Undefined
        }
        null, Undefined -> throw CosicError("Cannot destructure property 'value' of 'hap' as it is ${jsToString(hap)}.")
        else -> {
            value = Undefined
            context = Undefined
        }
    }
    var note = value
    if (jsTypeof(note) == "object" && note !is List<*>) {
        val map = note as? Map<*, *> ?: throw CosicError("Cannot read properties of null (reading 'note')")
        // note.note || note.n || note.value
        note = when {
            jsTruthy(map["note"]) -> map["note"]
            jsTruthy(map["n"]) -> map["n"]
            else -> if (map.containsKey("value")) map["value"] else Undefined
        }
        if (note === Undefined) throw CosicError("cannot find a playable note for ${jsonStringify(value) ?: "undefined"}")
    }
    if (note is Number) {
        val type = (context as? Map<*, *>)?.get("type") ?: if (context is Map<*, *>) Undefined else throw CosicError("Cannot read properties of ${jsToString(context)} (reading 'type')")
        // in Strudel the frequency is made of the value of the hap, not of the note that was found in it
        return if (type != "frequency") midiToFreq(jsToNumber(value)) else value
    }
    if (note !is String || !isNote(note)) throw CosicError("not a note: ${jsonStringify(note) ?: "undefined"}")
    return note
}

// ---- lists and numbers ----------------------------------------------------------------------------------------------

/** JavaScript's `a + b`: a string if either side reads as one, otherwise the sum of the numbers. */
private fun jsPlus(a: Any?, b: Any?): Any {
    val x = jsToPrimitive(a)
    val y = jsToPrimitive(b)
    return if (x is String || y is String) jsToString(x) + jsToString(y) else jsToNumber(x) + jsToNumber(y)
}

private fun Any?.asArray(method: String): List<*> = this as? List<*> ?: throw CosicError(
    if (this == null || this === Undefined) "Cannot read properties of ${jsToString(this)} (reading '$method')" else "${jsTypeof(this)}.$method is not a function",
)

private fun CatalogueBuilder.registerLists() {
    function(listOf("_mod"), 2) { ctx -> jsMod(jsToNumber(ctx.kernelArg(0)), jsToNumber(ctx.kernelArg(1))) }
    function(listOf("averageArray"), 1) { ctx ->
        val list = ctx.kernelArg(0).asArray("reduce")
        if (list.isEmpty()) throw CosicError("Reduce of empty array with no initial value")
        jsToNumber(list.reduce { a, b -> jsPlus(a, b) }) / list.size
    }
    function(listOf("nanFallback"), 1) { ctx ->
        val fallback = ctx.kernelArg(1)
        nanFallback(ctx.kernelArg(0), if (fallback === Undefined) 0.0 else fallback)
    }
    function(listOf("clamp"), 3) { ctx ->
        minOf(maxOf(jsToNumber(ctx.kernelArg(0)), jsToNumber(ctx.kernelArg(1))), jsToNumber(ctx.kernelArg(2)))
    }
    function(listOf("id"), 1) { ctx -> ctx.arg(0) }
    function(listOf("constant"), 2) { ctx -> ctx.arg(0) }
    function(listOf("rotate"), 2) { ctx ->
        // `arr.slice(n).concat(arr.slice(0, n))`: an `undefined` end of the second slice is the end of the array
        val n = ctx.kernelArg(1)
        when (val arr = ctx.kernelArg(0)) {
            is List<*> -> if (n === Undefined) arr + arr else jsRotate(arr, jsToNumber(n))
            is String -> if (n === Undefined) arr + arr else jsSliceIndex(jsToNumber(n), arr.length).let { arr.substring(it) + arr.substring(0, it) }
            else -> throw CosicError(if (arr == null || arr === Undefined) "Cannot read properties of ${jsToString(arr)} (reading 'slice')" else "arr.slice is not a function")
        }
    }
    function(listOf("splitAt"), 2) { ctx ->
        // `[value.slice(0, index), value.slice(index)]`: an `undefined` index is the whole array twice
        val index = ctx.kernelArg(0)
        when (val value = ctx.kernelArg(1)) {
            is List<*> -> if (index === Undefined) listOf(value, value) else jsSplitAt(jsToNumber(index), value).toList()
            is String -> if (index === Undefined) listOf(value, value) else jsSliceIndex(jsToNumber(index), value.length).let { listOf(value.substring(0, it), value.substring(it)) }
            else -> throw CosicError(if (value == null || value === Undefined) "Cannot read properties of ${jsToString(value)} (reading 'slice')" else "value.slice is not a function")
        }
    }
    function(listOf("zipWith"), 3) { ctx ->
        val f = ctx.kernelArg(0)
        val xs = ctx.kernelArg(1).asArray("map")
        val ys = ctx.kernelArg(2)
        xs.mapIndexed { i, n ->
            val other = if (ys is List<*>) ys.getOrElse(i) { Undefined } else throw CosicError("Cannot read properties of ${jsToString(ys)} (reading '$i')")
            callFn(f, n, other)
        }
    }
    function(listOf("pairs"), 1) { ctx ->
        val xs = ctx.kernelArg(0)
        val items = when (xs) {
            is List<*> -> xs
            is String -> xs.map { it.toString() }
            else -> throw CosicError("Cannot read properties of ${jsToString(xs)} (reading 'length')")
        }
        (0 until items.size - 1).map { listOf(items[it], items[it + 1]) }
    }
    function(listOf("removeUndefineds"), 1) { ctx -> ctx.kernelArg(0).asArray("filter").filter { it != null && it !== Undefined } }
    function(listOf("flatten"), 1) { ctx ->
        val arr = ctx.kernelArg(0) as? List<*> ?: throw CosicError("Spread syntax requires ...iterable[Symbol.iterator] to be a function")
        arr.flatMap { if (it is List<*>) it else listOf(it) }
    }
    function(listOf("listRange"), 2) { ctx ->
        val min = ctx.kernelArg(0)
        val length = jsToNumber(ctx.kernelArg(1)) - jsToNumber(min) + 1
        if (length > MAX_COUNT) throw CosicError("listRange: at most $MAX_COUNT entries, got ${jsNumberToString(length)}")
        // `Array.from({ length }, (_, i) => i + min)`
        (0 until (if (length.isNaN() || length < 1) 0 else Math.floor(length).toInt())).map { jsPlus(it.toDouble(), min) }
    }
    function(listOf("uniq"), 1) { ctx ->
        // `seen.hasOwn(item)` is not a function (`Object.hasOwn` is), so Strudel's `uniq` only works for an empty list
        val list = ctx.kernelArg(0).asArray("filter")
        if (list.isNotEmpty()) throw CosicError("seen.hasOwn is not a function")
        emptyList<Any?>()
    }
    function(listOf("uniqsort"), 1) { ctx ->
        // `a.sort().filter((item, pos, ary) => !pos || item != ary[pos - 1])`: sorted by the text of the values, `undefined` last
        val sorted = ctx.kernelArg(0).asArray("sort").sortedWith(compareBy<Any?> { it === Undefined }.thenBy { if (it === Undefined) "" else jsToString(it) })
        sorted.filterIndexed { pos, item -> pos == 0 || !jsLooseEq(item, sorted[pos - 1]) }
    }
    function(listOf("uniqsortr"), 1) { ctx ->
        // the same for fractions: sorted by `compare`, and an entry stays when it is not equal to the one before it
        val list = ctx.kernelArg(0).asArray("sort")
        val fractions = list.map { it as? Rational ?: throw CosicError("x.compare is not a function") }
        val sorted = fractions.sorted()
        sorted.filterIndexed { pos, item -> pos == 0 || item != sorted[pos - 1] }
    }
    function(listOf("stringifyValues"), 1) { ctx ->
        val value = ctx.kernelArg(0)
        if (jsTypeof(value) == "object") stringifyValues(value, jsTruthy(ctx.kernelArg(1))) else value
    }
    function(listOf("cycleToSeconds"), 2) { ctx -> jsToNumber(ctx.kernelArg(0)) / jsToNumber(ctx.kernelArg(1)) }
    function(listOf("getPerformanceTimeSeconds"), 0) { System.nanoTime() / 1.0e9 }
    function(listOf("objectMap"), 2) { ctx ->
        val fn = ctx.kernelArg(1)
        when (val obj = ctx.kernelArg(0)) {
            is List<*> -> obj.mapIndexed { i, v -> callFn(fn, v, i.toDouble(), obj) }
            is Map<*, *> -> {
                val out = LinkedHashMap<String, Any?>()
                var i = 0
                for ((k, v) in obj) out[k.toString()] = callFn(fn, v, k, (i++).toDouble())
                out
            }
            else -> throw CosicError(if (obj == null || obj === Undefined) "Cannot convert undefined or null to object" else "Cannot read properties of ${jsToString(obj)}")
        }
    }
}

// ---- functions that make functions ---------------------------------------------------------------------------------------

/** `args.map(mapFn)`: the function is called with the value, its index and all the values. */
private fun mapArgs(mapFn: Any?, args: List<Any?>): List<Any?> = args.mapIndexed { i, v -> callFn(mapFn, v, i.toDouble(), args) }

private fun CatalogueBuilder.registerFunctionMakers() {
    // pipe(f, g, h)(x) is f(g(h(x))); with no functions it is the identity
    function(listOf("pipe"), 0) { ctx -> pipe(ctx.args.map { ctx.bridge.toKernel(it) }) }
    function(listOf("compose"), 0) { ctx -> pipe(ctx.args.map { ctx.bridge.toKernel(it) }.reversed()) }
    function(listOf("mapArgs"), 2) { ctx ->
        val fn = ctx.kernelArg(0)
        val mapFn = ctx.kernelArg(1)
        jsFn(0) { args -> callFn(fn, *mapArgs(mapFn, args).toTypedArray()) }
    }
    function(listOf("numeralArgs"), 1) { ctx ->
        val fn = ctx.kernelArg(0)
        jsFn(0) { args -> callFn(fn, *args.map { parseNumeral(it) }.toTypedArray()) }
    }
    function(listOf("fractionalArgs"), 1) { ctx ->
        val fn = ctx.kernelArg(0)
        jsFn(0) { args -> callFn(fn, *args.map { parseFractional(it) }.toTypedArray()) }
    }
    function(listOf("curry"), 2) { ctx ->
        val func = ctx.arg(0)
        val given = ctx.arg(2)
        val arity = if (given !== Undefined) {
            jsToNumber(given)
        } else {
            val length = (func as? JsFunction)?.length ?: throw ctx.realm.typeError("Cannot read properties of ${jsToString(func)} (reading 'length')")
            length.toDouble()
        }
        curry(ctx.realm, func, ctx.arg(1), arity)
    }
}

/** `pipe(...funcs)`: `funcs.reduce((f, g) => (...args) => f(g(...args)), x => x)`. */
private fun pipe(funcs: List<Any?>): JsFn {
    if (funcs.isEmpty()) return jsFn(1) { args -> args.getOrElse(0) { Undefined } }
    return jsFn(0) { args ->
        var x = callFn(funcs.last(), *args.toTypedArray())
        for (i in funcs.size - 2 downTo 0) x = callFn(funcs[i], x)
        // the identity at the start of the reduction
        x
    }
}

/**
 * `curry(func, overload, arity = func.length)` of util.mjs: a function that collects arguments over any number of calls and calls
 * [func] once it has [arity] of them (or more). [overload] is called with each function it makes and the arguments collected
 * so far, which is how `chordBass.transpose(2)` gets its methods.
 */
private fun curry(realm: Realm, func: Any?, overload: Any?, arity: Double): Any? {
    val self = arrayOfNulls<Any>(1)
    val curried = realm.functionAt("curried", 0) { thisArg, args, _ ->
        if (args.size >= arity) {
            realm.callFunction(func, thisArg, args)
        } else {
            val partial = realm.functionAt("partial", 0) { thisArg2, args2, _ -> realm.callFunction(self[0], thisArg2, args + args2) }
            if (jsTruthy(overload)) realm.callFunction(overload, Undefined, listOf(partial, realm.newArray(args)))
            partial
        }
    }
    self[0] = curried
    if (jsTruthy(overload)) realm.callFunction(overload, Undefined, listOf(curried, realm.newArray(emptyList())))
    return curried
}

// ---- code in a link --------------------------------------------------------------------------------------------------------

/** What `TextEncoder` makes of text: UTF-8, with U+FFFD for a surrogate that has no partner. */
private fun utf8Bytes(text: String): ByteArray {
    val sb = StringBuilder(text.length)
    var i = 0
    while (i < text.length) {
        val c = text[i]
        when {
            c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate() -> {
                sb.append(c).append(text[i + 1])
                i++
            }
            c.isSurrogate() -> sb.append('\uFFFD')
            else -> sb.append(c)
        }
        i++
    }
    return sb.toString().toByteArray(StandardCharsets.UTF_8)
}

private fun unicodeToBase64(text: String): String = Base64.getEncoder().encodeToString(utf8Bytes(text))

/** `atob` and `TextDecoder`: whitespace is ignored, padding is optional, anything else that is not base64 is an error. */
private fun base64ToUnicode(base64: String): String {
    var data = base64.filter { it !in " \t\n\u000C\r" }
    if (data.length % 4 == 0) data = data.removeSuffix("==").removeSuffix("=")
    if (data.length % 4 == 1 || data.any { !(it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '+' || it == '/') }) {
        throw CosicError("The string to be decoded is not correctly encoded.")
    }
    val bytes = Base64.getDecoder().decode(data + "=".repeat((4 - data.length % 4) % 4))
    return String(bytes, StandardCharsets.UTF_8)
}

/** `encodeURIComponent`: everything but letters, digits and ``-_.!~*'()`` becomes the percent-encoded bytes of its UTF-8. */
private fun encodeURIComponent(text: String): String = buildString {
    for (b in utf8Bytes(text)) {
        val c = (b.toInt() and 0xFF).toChar()
        if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c in "-_.!~*'()") append(c) else append('%').append("%02X".format(b.toInt() and 0xFF))
    }
}

/** `decodeURIComponent`: the percent-encoded UTF-8 bytes are decoded, a malformed sequence is a `URIError`. */
private fun decodeURIComponent(text: String): String {
    val out = java.io.ByteArrayOutputStream()
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c == '%') {
            val hex = text.substring(i + 1, minOf(i + 3, text.length))
            if (hex.length < 2 || hex.any { Character.digit(it, 16) < 0 }) throw CosicError("URI malformed")
            out.write(hex.toInt(16))
            i += 3
        } else {
            out.write(c.toString().toByteArray(StandardCharsets.UTF_8))
            i++
        }
    }
    val decoder = StandardCharsets.UTF_8.newDecoder()
    return try {
        decoder.decode(java.nio.ByteBuffer.wrap(out.toByteArray())).toString()
    } catch (_: java.nio.charset.CharacterCodingException) {
        throw CosicError("URI malformed")
    }
}

private fun CatalogueBuilder.registerEncoding() {
    function(listOf("unicodeToBase64"), 1) { ctx -> unicodeToBase64(jsToString(ctx.kernelArg(0))) }
    function(listOf("base64ToUnicode"), 1) { ctx -> base64ToUnicode(jsToString(ctx.kernelArg(0))) }
    function(listOf("code2hash"), 1) { ctx -> encodeURIComponent(unicodeToBase64(jsToString(ctx.kernelArg(0)))) }
    function(listOf("hash2code"), 1) { ctx -> base64ToUnicode(decodeURIComponent(jsToString(ctx.kernelArg(0)))) }
}

// ---- drawLine --------------------------------------------------------------------------------------------------------------

private fun CatalogueBuilder.registerDrawLine() {
    // drawLine(pat, chars = 60): the pattern as a string
    function(listOf("drawLine"), 1) { ctx ->
        val pat = ctx.kernelArg(0) as? Pattern ?: throw CosicError("pat.queryArc is not a function")
        val chars = ctx.kernelArg(1)
        drawLine(pat, if (chars === Undefined) 60.0 else jsToNumber(chars))
    }
    // pat.drawLine(): writes it to the console and returns the pattern
    method(listOf("drawLine"), 0) { ctx ->
        val pat = ctx.bridge.receiverOf(ctx.self, "drawLine")
        ctx.host.log(drawLine(pat))
        pat
    }
}
