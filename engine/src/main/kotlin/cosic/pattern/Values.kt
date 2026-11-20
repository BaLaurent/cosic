package cosic.pattern

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
