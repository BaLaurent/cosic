package cosic.pattern

/*
 * Picking patterns with a pattern: `packages/core/pick.mjs` of Strudel. A pattern of indices or names chooses, event by
 * event, which of a list or an object of patterns plays, and the choice is joined back with one of the joins: `pick`
 * keeps the structure of the chosen pattern (inner join), `pickOut` the one of the picking pattern (outer join),
 * `pickRestart` and `pickReset` start the chosen pattern over, `inhabit` squeezes a whole cycle of it into the event.
 * The `mod` variants wrap an index past the end around instead of sticking at the last entry. The catalogue entries are in
 * script/catalogue/Pick.kt.
 */

/** The index [key] stands for in a list of [length] entries, null when `lookup[key]` is `undefined` (NaN, a fraction, out of range). */
private fun indexOf(key: Double, length: Int): Int? =
    if (key >= 0.0 && key < length && key == Math.floor(key)) key.toInt() else null

/** What `lookup[key]` looks at: the entries of a list (by index) or of an object (by name), each one a pattern. */
private sealed interface Lookup {
    val size: Int

    /** `lookup[key]` for the value [key] of the picking pattern, null when that is `undefined`. */
    fun patternFor(key: Any?, modulo: Boolean): Pattern?
}

private class ListLookup(private val patterns: List<Pattern>) : Lookup {
    override val size: Int get() = patterns.size

    override fun patternFor(key: Any?, modulo: Boolean): Pattern? {
        val rounded = jsMathRound(jsToNumber(key))
        // `Math.round(key) % len`, or the rounded index kept between the first and the last entry
        val index = if (modulo) rounded % size else minOf(maxOf(rounded, 0.0), (size - 1).toDouble())
        return indexOf(index, size)?.let { patterns[it] }
    }
}

private class NamedLookup(private val patterns: Map<String, Pattern>) : Lookup {
    override val size: Int get() = patterns.size

    override fun patternFor(key: Any?, modulo: Boolean): Pattern? = patterns[jsToString(key)]
}

/**
 * `objectMap(lookup, reify)`: an entry that is not a pattern becomes one that repeats it. A list is a list, an object its
 * entries by name, a string its characters by index; anything else has no entries (`Object.keys(5)` is empty), and `null`
 * and `undefined` are a `TypeError`.
 */
private fun lookupOf(lookup: Any?): Lookup = when (lookup) {
    is List<*> -> ListLookup(lookup.map { reify(it) })
    is Map<*, *> -> NamedLookup(lookup.entries.associate { (key, value) -> key.toString() to reify(value) })
    is String -> NamedLookup(lookup.indices.associate { it.toString() to reify(lookup[it].toString()) })
    null, Undefined -> throw CosicError("Cannot convert undefined or null to object")
    else -> NamedLookup(emptyMap())
}

/**
 * `_pick(lookup, pat, modulo)`: the pattern of patterns that [pat] chooses from [lookup], a list (indexed by the rounded
 * value, which sticks to the first and last entry unless [modulo]) or an object (indexed by the value itself, as the
 * name of an entry). An empty lookup is silence. A value with no entry in the lookup has no pattern to play: that is an
 * error when the event is reached, where Strudel has `undefined` fail the join and logs it.
 */
internal fun pickPatterns(lookup: Any?, pat: Pattern, modulo: Boolean): Pattern {
    val entries = lookupOf(lookup)
    if (entries.size == 0) return silence
    return pat.fmap { key ->
        entries.patternFor(key, modulo)
            ?: throw CosicError("Cannot pick ${stringifyValues(key, true)}: there is no pattern for it in the lookup")
    }
}

/**
 * `pick` as a function of the global scope: the arguments used to be the other way round (the pattern first, then the
 * list), so a list in the place of [pat] swaps them.
 */
internal fun pickGlobal(lookup: Any?, pat: Any?): Any? {
    val (l, p) = if (pat is List<*>) pat to lookup else lookup to pat
    return Registry.call("pick", listOf(l, p))
}

/** `pat.apply(picked)` for a pattern of functions: each is called with this pattern, and the patterns they return are joined. */
private fun Pattern.applyPicked(picked: Pattern): Pattern = picked.fmap { f -> callFn(f, this) }.innerJoin()

/**
 * `pickF`: [pickPattern] chooses from [lookup], a list or an object of functions, which one to apply to this pattern.
 * Called with a list as the pattern to pick with, the arguments are swapped like those of the global `pick`.
 */
internal fun Pattern.pickF(pickPattern: Any?, lookup: Any?): Pattern {
    val (l, p) = if (pickPattern is List<*>) pickPattern to lookup else lookup to pickPattern
    return applyPicked(pickPatterns(l, reify(p), false).innerJoin())
}

/** `pickmodF`: like [pickF], but an index past the end of the list wraps around. */
internal fun Pattern.pickmodF(pickPattern: Any?, lookup: Any?): Pattern =
    applyPicked(pickPatterns(lookup, reify(pickPattern), true).innerJoin())

/**
 * `squeeze(pat, xs)`: the values of [pat] are indices (rounded, wrapped around) into the list [xs], and each cycle of the
 * chosen pattern is squeezed into the event that chose it. [pat] must be a pattern, and [xs] a list.
 */
internal fun squeeze(pat: Any?, xs: Any?): Pattern {
    val list = xs as? List<*> ?: throw CosicError(
        if (xs == null || xs === Undefined) "Cannot read properties of ${jsToString(xs)} (reading 'map')" else "xs.map is not a function",
    )
    val patterns = list.map { reify(it) }
    if (patterns.isEmpty()) return silence
    val indices = pat as? Pattern ?: throw CosicError("pat.fmap is not a function")
    return indices.fmap { i ->
        val key = jsMod(jsMathRound(jsToNumber(i)), patterns.size.toDouble())
        indexOf(key, patterns.size)?.let { patterns[it] }
            ?: throw CosicError("Cannot squeeze ${stringifyValues(i, true)}: there is no pattern for it in the list")
    }.squeezeJoin()
}
