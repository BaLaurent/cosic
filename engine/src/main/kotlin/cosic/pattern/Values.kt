package cosic.pattern

import cosic.pattern.legacy.legacyNoteToMidi

/**
 * A control map: sound parameters by name, in JavaScript object key order (K1). Numbers are `Double`, sounds and notes
 * `String`s or `Double`s, as written; the keys are Strudel's canonical control names, never their aliases.
 */
internal typealias ControlMap = Map<String, Any?>

/**
 * `{...a, ...b}`: the entries of [a] followed by those of [b], a key that both have keeping the place it had in [a] and
 * taking the value of [b]. Every control-map merge goes through this function, so that key order stays JavaScript's: whole-number
 * keys first, ascending, then the others in the order they were added.
 */
internal fun jsMerge(a: Map<String, Any?>, b: Map<String, Any?>): LinkedHashMap<String, Any?> {
    val out = LinkedHashMap<String, Any?>(a)
    out.putAll(b)
    return jsOrdered(out)
}

/** [map] with its keys in JavaScript's order; the same map when it has no whole-number keys. */
internal fun jsOrdered(map: LinkedHashMap<String, Any?>): LinkedHashMap<String, Any?> {
    if (map.keys.none { it.isNotEmpty() && it[0] in '0'..'9' && isArrayIndex(it) }) return map
    val out = LinkedHashMap<String, Any?>(map.size * 2)
    for (key in jsKeyOrder(map.keys)) out[key] = map[key]
    return out
}

/**
 * `unionWithObj` of value.mjs: the entries of [a] and [b] together (`{...a, ...b}`), with [func] of the two values for a
 * key that both have. A [b] that is only `{value: x}`, which is what a plain number becomes next to a control map, changes
 * nothing: arithmetic on a control pattern with a plain number only logs a warning and returns [a].
 */
internal fun unionWithObj(a: Map<String, Any?>, b: Map<String, Any?>, func: (Any?, Any?) -> Any?): Map<String, Any?> {
    if (b.size == 1 && b.containsKey("value") && b["value"] !== Undefined) {
        logger("[warn]: Can't do arithmetic on control pattern.", null, null)
        return a
    }
    val out = LinkedHashMap<String, Any?>(a)
    out.putAll(b)
    for (key in a.keys) {
        if (b.containsKey(key)) out[key] = func(a[key], b[key])
    }
    return jsOrdered(out)
}

/** Whether [x] is a JavaScript object that is neither an array nor a fraction (`null` is one). */
private fun isNonArrayObject(x: Any?): Boolean = jsTypeof(x) == "object" && x !is List<*> && x !is Rational

@Suppress("UNCHECKED_CAST")
private fun objectOf(x: Any?): Map<String, Any?> = when (x) {
    is Map<*, *> -> x as Map<String, Any?>
    null -> throw CosicError("Cannot convert undefined or null to object")
    else -> emptyMap()
}

/**
 * `_composeOp` of pattern.mjs: [func] of [a] and [b], unless one of them is a control map, then the values are taken
 * key by key (see [unionWithObj]), a plain value standing in for the map being `{value: x}`.
 */
internal fun composeOp(a: Any?, b: Any?, func: (Any?, Any?) -> Any?): Any? {
    if (isNonArrayObject(a) || isNonArrayObject(b)) {
        val left = if (isNonArrayObject(a)) a else mapOf("value" to a)
        val right = if (isNonArrayObject(b)) b else mapOf("value" to b)
        return unionWithObj(objectOf(left), objectOf(right), func)
    }
    return func(a, b)
}

// ---- the language Cosic had before Strudel's: removed together with the old evaluator (Task 1.2) ------------------------

/** A number as it is written in a program's text: `3`, `-0.5`, `.5` or `2.`. */
internal val NUMBER_TEXT = Regex("-?(\\d+\\.?\\d*|\\.\\d+)")

/**
 * A pattern value as a control map: maps stay as they are, numbers become notes, strings sounds, and lists (`bd:3` of the
 * mini-notation) the controls of `s`: a sound and its sample number.
 */
@Suppress("UNCHECKED_CAST")
internal fun asControlMap(value: Any?): ControlMap = when (value) {
    is Map<*, *> -> value as ControlMap
    is Double -> mapOf("note" to value)
    is String, is List<*> -> Controls.valueOf(checkNotNull(Controls.method("s")), value)
    else -> throw CosicError("Expected sounds or notes, got '${jsToString(value)}'")
}

/** Sets the given controls on every value. */
internal fun Pattern.withControls(vararg entries: Pair<String, Any>): Pattern =
    fmap { jsMerge(asControlMap(it), mapOf(*entries)) }

internal enum class ArithOp(val word: String, val apply: (Double, Double) -> Double) {
    ADD("add", { a, b -> a + b }),
    SUB("sub", { a, b -> a - b }),
    MUL("mul", { a, b -> a * b }),
    DIV("div", { a, b -> a / b }),
}

/**
 * One arithmetic step on two pattern values. Numbers combine as usual and note names count as their MIDI
 * number. A control map and a number change the map's `note`, or else its `n`; two control maps combine the
 * numbers they share and take everything else from the right.
 */
internal fun arith(op: ArithOp, left: Any?, right: Any?): Any = when {
    left is Map<*, *> && right is Map<*, *> -> combine(op, asControlMap(left), asControlMap(right))
    left is Map<*, *> -> change(op, asControlMap(left), numeric(op, right))
    right is Map<*, *> -> combine(op, asControlMap(numeric(op, left)), asControlMap(right))
    else -> op.apply(numeric(op, left), numeric(op, right))
}

private fun numeric(op: ArithOp, value: Any?): Double = when (value) {
    is Double -> value
    is String -> legacyNoteToMidi(value)?.toDouble() ?: throw CosicError("'${op.word}' expects a number, got '$value'")
    else -> throw CosicError("'${op.word}' expects a number, got '${jsToString(value)}'")
}

private fun change(op: ArithOp, map: ControlMap, by: Double): ControlMap {
    val key = when {
        "note" in map -> "note"
        "n" in map -> "n"
        else -> throw CosicError("${op.word}: nothing numeric to change")
    }
    return jsMerge(map, mapOf(key to op.apply(numeric(op, map.getValue(key)), by)))
}

private fun combine(op: ArithOp, left: ControlMap, right: ControlMap): ControlMap {
    val out = LinkedHashMap(left)
    for ((key, value) in right) {
        val current = left[key]
        out[key] = if (current is Double && value is Double) op.apply(current, value) else value
    }
    return jsOrdered(out)
}
