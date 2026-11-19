package cosic.pattern

/**
 * What a program finally plays: sound parameters by name, only the ones the program set. Numbers are
 * `Double`; `s` and `bank` are `String`. The keys are the canonical control names, never their aliases:
 * `s`, `n`, `note`, `bank`, `gain`, `velocity`, `postgain`, `pan`, `speed`, `begin`, `end`, `cut`, `clip`,
 * `attack`, `decay`, `sustain`, `release`, `lpf`, `lpq`, `hpf`, `hpq`, `bpf`, `bpq`, `shape`, `crush`,
 * `coarse`, `delay`, `delaytime`, `delayfeedback`, `room`, `size`. A sound written `bd:3` arrives as `s = bd`
 * and `n = 3`, and `note` is always a MIDI number (note names are converted).
 */
internal typealias ControlMap = Map<String, Any>

/** Whether a pattern value counts as "on": non-zero numbers, strings other than `f`/`false`/`0`, `true`. */
internal fun truthy(value: Any): Boolean = when (value) {
    is Double -> value != 0.0
    is String -> value != "f" && value != "false" && value != "0"
    is Boolean -> value
    else -> true
}

/** `bd:3` is sound `bd`, sample number 3; a name without a numeric suffix is kept whole. */
internal fun soundControl(name: String): ControlMap {
    val colon = name.lastIndexOf(':')
    if (colon > 0) {
        val suffix = name.substring(colon + 1)
        if (NUMBER_SUFFIX.matches(suffix)) return mapOf("s" to name.substring(0, colon), "n" to suffix.toDouble())
    }
    return mapOf("s" to name)
}

private val NUMBER_SUFFIX = Regex("-?(\\d+\\.?\\d*|\\.\\d+)")

/** A pattern value as a control map: maps stay as they are, numbers become notes and strings sounds. */
@Suppress("UNCHECKED_CAST")
internal fun asControlMap(value: Any): ControlMap = when (value) {
    is Map<*, *> -> value as ControlMap
    is Double -> mapOf("note" to value)
    is String -> soundControl(value)
    else -> throw CosicError("Expected sounds or notes, got '$value'")
}

/** Sets the given controls on every value. */
internal fun Pattern<Any>.withControls(vararg entries: Pair<String, Any>): Pattern<Any> =
    fmap { asControlMap(it) + entries }

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
internal fun arith(op: ArithOp, left: Any, right: Any): Any = when {
    left is Map<*, *> && right is Map<*, *> -> combine(op, asControlMap(left), asControlMap(right))
    left is Map<*, *> -> change(op, asControlMap(left), numeric(op, right))
    right is Map<*, *> -> combine(op, asControlMap(numeric(op, left)), asControlMap(right))
    else -> op.apply(numeric(op, left), numeric(op, right))
}

private fun numeric(op: ArithOp, value: Any): Double = when (value) {
    is Double -> value
    is String -> noteToMidi(value)?.toDouble() ?: throw CosicError("'${op.word}' expects a number, got '$value'")
    else -> throw CosicError("'${op.word}' expects a number, got '$value'")
}

private fun change(op: ArithOp, map: ControlMap, by: Double): ControlMap {
    val key = when {
        "note" in map -> "note"
        "n" in map -> "n"
        else -> throw CosicError("${op.word}: nothing numeric to change")
    }
    return map + (key to op.apply(map.getValue(key) as Double, by))
}

private fun combine(op: ArithOp, left: ControlMap, right: ControlMap): ControlMap {
    val out = LinkedHashMap(left)
    for ((key, value) in right) {
        val current = left[key]
        out[key] = if (current is Double && value is Double) op.apply(current, value) else value
    }
    return out
}
