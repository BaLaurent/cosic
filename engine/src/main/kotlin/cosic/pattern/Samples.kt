package cosic.pattern

/*
 * The sample functions of pattern.mjs (3891-4089 of the pinned commit): `chop`, `striate`, `loopAt`, `loopAtCps`, `slice`,
 * `splice` and `fit`. They shape the control maps of sample events (`begin`, `end`, `speed`, `unit`, `_slices`); the catalogue
 * entries that make them callable from programs are in script/catalogue/SampleFunctions.kt.
 *
 * Their arguments are what `func` of `register` gets (a number, a string, a list, or for the functions registered without
 * patternification a pattern), their results are patterns. Everything the JavaScript does to the plain values it is given
 * (`n[i]`, `i / n`, `'begin' in v`, `{...a, ...b}`) is done as JavaScript does it, so a value that is not what the
 * function expects (a string where a control map should be) fails with the same message and a number that is not a whole
 * number slices the same way. `scrub`, the other function of the list, is `scrub` of controls.mjs: it is registered with the controls.
 */
internal object SampleFunctions {

    // ---- the JavaScript the functions are written in ------------------------------------------------------------------

    /** `x instanceof Object`: false for `null`, `undefined`, numbers, strings and booleans. */
    fun isObject(x: Any?): Boolean = !(x == null || x === Undefined || x is Number || x is String || x is Boolean)

    /** `key in x`; a primitive on the right of `in` is a `TypeError`. */
    @Suppress("UNCHECKED_CAST")
    fun hasProperty(key: String, x: Any?): Boolean = when (x) {
        is Map<*, *> -> (x as Map<String, Any?>).containsKey(key)
        is List<*> -> key == "length" || (isArrayIndex(key) && key.toLong() < x.size)
        null, Undefined, is Number, is String, is Boolean ->
            throw CosicError("Cannot use 'in' operator to search for '$key' in ${jsToString(x)}")
        else -> false
    }

    /** `x.key` for the values a pattern holds; reading a property of `null` or `undefined` is a `TypeError`. */
    @Suppress("UNCHECKED_CAST")
    fun property(x: Any?, key: String): Any? = when {
        x is Map<*, *> -> if ((x as Map<String, Any?>).containsKey(key)) x[key] else Undefined
        x is List<*> && key == "length" -> x.size.toDouble()
        x is List<*> && isArrayIndex(key) -> if (key.toLong() < x.size) x[key.toInt()] else Undefined
        x is String && key == "length" -> x.length.toDouble()
        x == null -> throw CosicError("Cannot read properties of null (reading '$key')")
        x === Undefined -> throw CosicError("Cannot read properties of undefined (reading '$key')")
        else -> Undefined
    }

    /** `{...x}`: the own enumerable properties of [x]. A map is itself, a list or a string has its elements as index keys, anything else nothing. */
    @Suppress("UNCHECKED_CAST")
    fun spread(x: Any?): Map<String, Any?> = when (x) {
        is Map<*, *> -> x as Map<String, Any?>
        is List<*> -> LinkedHashMap<String, Any?>().also { out -> x.forEachIndexed { i, v -> out[i.toString()] = v } }
        is String -> LinkedHashMap<String, Any?>().also { out -> x.forEachIndexed { i, c -> out[i.toString()] = c.toString() } }
        else -> emptyMap()
    }

    /** `a + b`: a string if either operand is one (a list or a map reads as its text), a number otherwise. */
    fun plus(a: Any?, b: Any?): Any {
        val x = jsToPrimitive(a)
        val y = jsToPrimitive(b)
        return if (x is String || y is String) jsToString(x) + jsToString(y) else jsToNumber(x) + jsToNumber(y)
    }

    /** `array[index]`: `undefined` unless [index] reads as the index of an element. */
    private fun elementAt(list: List<*>, index: Any?): Any? {
        val key = if (index is Number) jsNumberToString(index.toDouble()) else jsToString(index)
        if (isArrayIndex(key) && key.toLong() < list.size) return list[key.toInt()]
        return Undefined
    }

    /**
     * How many entries `Array.from({ length: n }, ...)` makes: `n` read as a length (NaN, zero and negative numbers are none,
     * a fraction is cut down). More than [MAX_COUNT] is [TOO_DENSE], like any other count that would build millions of events.
     */
    fun arrayLength(n: Any?): Int {
        val d = jsToNumber(n)
        if (d.isNaN() || d < 1.0) return 0
        if (d > MAX_COUNT) throw CosicError(TOO_DENSE)
        return Math.floor(d).toInt()
    }

    /** The tempo a query was made at (`state.controls._cps`), or [Undefined] when the query did not say. */
    private fun cpsOf(state: State): Any? = state.controls["_cps"] ?: Undefined

    /** `state.controls._cps || 1` */
    private fun cpsOrOne(state: State): Double = cpsOf(state).let { if (jsTruthy(it)) jsToNumber(it) else 1.0 }

    private fun noWhole(): CosicError = CosicError("Cannot read properties of undefined (reading 'duration')")

    // ---- chop and striate (pattern.mjs:3906-3940) -----------------------------------------------------------------

    /** `{ begin: i / n, end: (i + 1) / n }` for every slice of [n]: the slices are as many as `Array.from({ length: n })` makes, but the divisions use `n` as it is. */
    private fun sliceObjects(n: Any?): List<LinkedHashMap<String, Any?>> {
        val total = jsToNumber(n)
        return (0 until arrayLength(n)).map { i ->
            linkedMapOf<String, Any?>("begin" to i.toDouble() / total, "end" to (i + 1).toDouble() / total)
        }
    }

    /** `Fraction(n).mulmaybe(pat._steps)`, which is undefined when steps are not counted or the pattern has none. */
    private fun stepsTimes(n: Any?, pat: Pattern): Rational? {
        if (!Steps.enabled) return null
        val times = Rational.of(n)
        return pat.steps?.let { times * it }
    }

    /**
     * `merge(a, b)` of `chop`: [slice] with its begin and end moved into the part of the sample [a] already selects, if it
     * selects one (it has both a `begin` and an `end`), then `Object.assign({}, a, slice)`. A `begin` without an `end` is
     * overwritten, not nested.
     */
    private fun chopMerge(a: Any?, slice: Map<String, Any?>): Map<String, Any?> {
        var merged = slice
        if (hasProperty("begin", a) && hasProperty("end", a) && property(a, "begin") !== Undefined && property(a, "end") !== Undefined) {
            val begin = property(a, "begin")
            val d = jsToNumber(property(a, "end")) - jsToNumber(begin)
            merged = linkedMapOf(
                "begin" to plus(begin, jsToNumber(slice["begin"]) * d),
                "end" to plus(begin, jsToNumber(slice["end"]) * d),
            )
        }
        return jsMerge(spread(a), merged)
    }

    /** Cuts each event into [n] parts, each with its own `begin` and `end`, played one after the other inside the event (`chop`). */
    fun chop(n: Any?, pat: Pattern): Pattern {
        val slices = sliceObjects(n)
        return pat.squeezeBind { o -> sequence(slices.map { slice -> chopMerge(o, slice) }) }.setSteps(stepsTimes(n, pat))
    }

    /** Cuts each event into [n] parts and plays the first part of every event, then the second part of every event, and so on (`striate`). `begin` and `end` are overwritten. */
    fun striate(n: Any?, pat: Pattern): Pattern {
        val slicePat = slowcat(sliceObjects(n))
        return pat.setWith(slicePat)._fast(n).setSteps(stepsTimes(n, pat))
    }

    // ---- loopAt and loopAtCps (pattern.mjs:3952-3962, 4087-4089) --------------------------------------------------

    /**
     * `_loopAt(factor, pat, cps = 0.5)`: the pattern slowed down by [factor] cycles, its samples played at the speed that makes
     * them take that long at the tempo [cps] (`speed` of `(1 / factor) * cps`, `unit: 'c'`). No [cps] (`undefined`) is 0.5.
     */
    fun loopAtCps(factor: Any?, pat: Pattern, cps: Any? = Undefined): Pattern {
        val tempo = if (cps === Undefined) 0.5 else cps
        return pat.callMethod("speed", (1.0 / jsToNumber(factor)) * jsToNumber(tempo)).callMethod("unit", "c").callMethod("slow", factor)
    }

    /** `loopAt`: [loopAtCps] with the tempo of the query (`state.controls._cps`), so that it is known only once the pattern is played. */
    fun loopAt(factor: Any?, pat: Pattern): Pattern {
        val steps = pat.steps?.let { it / Rational.of(factor) }
        return Pattern({ state -> loopAtCps(factor, pat, cpsOf(state)).query(state) }, steps)
    }

    // ---- slice and splice (pattern.mjs:3979-4049) ------------------------------------------------------------------

    private fun patternOrFail(x: Any?, name: String, method: String): Pattern =
        x as? Pattern ?: throw CosicError("$name.$method is not a function")

    /**
     * `slice(npat, ipat, opat)`: for every event of [ipat], the sample [opat] plays from the slice `i` of `n` of it
     * (`begin` and `end`, `_slices: n`). An `n` that is a list names the slice points itself: slice `i` runs from `n[i]` to `n[i + 1]`.
     * The control map of [opat] (a value that is not a map is a sound, `{ s: o }`) comes last, so what it has already set stays.
     * The structure is [ipat]'s. The arguments are patterns: the function is registered without patternification.
     */
    fun slice(npat: Any?, ipat: Any?, opat: Any?): Pattern =
        patternOrFail(npat, "npat", "innerBind").innerBind { n ->
            patternOrFail(ipat, "ipat", "outerBind").outerBind { i ->
                patternOrFail(opat, "opat", "outerBind").outerBind { o ->
                    val sample: Any? = if (isObject(o)) o else linkedMapOf<String, Any?>("s" to o)
                    val next = plus(i, 1.0)
                    val begin = if (n is List<*>) elementAt(n, i) else jsToNumber(i) / jsToNumber(n)
                    val end = if (n is List<*>) elementAt(n, next) else jsToNumber(next) / jsToNumber(n)
                    pure(jsMerge(linkedMapOf("begin" to begin, "end" to end, "_slices" to n), spread(sample)))
                }
            }
        }.setSteps((ipat as? Pattern)?.steps)

    /**
     * `splice(npat, ipat, opat)`: [slice], with every slice sped up or down to take as long as its step (`speed` of
     * `cps / n / duration`, times a speed it has, `unit: 'c'`). A `speed` or `unit` the sample has already stays. The tempo is that
     * of the query (`state.controls._cps`), 1 when it is not known.
     */
    fun splice(npat: Any?, ipat: Any?, opat: Any?): Pattern {
        val sliced = slice(reify(npat), reify(ipat), reify(opat))
        return Pattern({ state ->
            val cps = cpsOrOne(state)
            sliced.query(state).map { hap ->
                hap.withValue { v ->
                    val whole = hap.whole ?: throw noWhole()
                    val speed = property(v, "speed").let { if (jsTruthy(it)) it else 1.0 }
                    val spliced = linkedMapOf<String, Any?>(
                        "speed" to (cps / jsToNumber(property(v, "_slices")) / whole.duration.toDouble()) * jsToNumber(speed),
                        "unit" to "c",
                    )
                    jsMerge(spliced, spread(v))
                }
            }
        }, (ipat as? Pattern)?.steps)
    }

    // ---- fit (pattern.mjs:4060-4073) ------------------------------------------------------------------------------------

    /**
     * Makes each sample take as long as its event (`speed` of `cps / duration`, times the part of the sample the event selects
     * with its `begin` and `end`; `unit: 'c'`). The tempo is that of the query, 1 when it is not known.
     */
    fun fit(pat: Pattern): Pattern = pat.withHaps { haps, state ->
        val cps = cpsOrOne(state)
        haps.map { hap ->
            hap.withValue { v ->
                val end = if (hasProperty("end", v)) property(v, "end") else 1.0
                val begin = if (hasProperty("begin", v)) property(v, "begin") else 0.0
                val sliceDuration = jsToNumber(end) - jsToNumber(begin)
                val whole = hap.whole ?: throw noWhole()
                jsMerge(spread(v), linkedMapOf<String, Any?>("speed" to (cps / whole.duration.toDouble()) * sliceDuration, "unit" to "c"))
            }
        }
    }
}
