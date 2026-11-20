package cosic.pattern

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/*
 * `xen`, `withBase`, `ftranspose`, `tuning` and `tune` of @strudel/xen (packages/xen/{xen,tune,tunejs}.mjs): patterns of scale
 * degrees become patterns of frequencies, for any scale of the tune.js list (resources/cosic/xen/tunings.tsv), any equal
 * division of the octave (`31edo`), the `12ji` preset, or a list of ratios.
 */

/** `Array.isArray(x) ? x[i] : undefined`: the `i`-th entry of a list, `undefined` when it is not there. */
private fun List<*>.at(i: Int): Any? = if (i < size) this[i] else Undefined

private fun Map<String, Any?>.getJs(key: String): Any? = if (containsKey(key)) get(key) else Undefined

private fun isNullish(v: Any?): Boolean = v == null || v === Undefined

/** The own properties an object spread or destructuring reads: a map as it is, a list by its indices. */
@Suppress("UNCHECKED_CAST")
private fun objectOf(v: Any?): Map<String, Any?> = when (v) {
    is Map<*, *> -> v as Map<String, Any?>
    is List<*> -> v.indices.associateTo(LinkedHashMap()) { it.toString() to v[it] }
    else -> emptyMap()
}

/** The numbers of a list, each read as `Number(x)`; null for what is not a list (`x.length` is `undefined`, so every lookup misses). */
private fun numberList(v: Any?): List<Double>? = (v as? List<*>)?.map { jsToNumber(it) }

/** `list[i]` for a number `i`, which is `undefined` (here NaN, since the caller multiplies it) unless it is a whole number inside the list. */
private fun List<Double>.lookup(i: Double): Double {
    if (i != Math.floor(i) || i < 0 || i >= size) return Double.NaN
    return this[i.toInt()]
}

internal object Xen {
    /** The base frequency of every scale: 220 Hz. */
    const val DEFAULT_BASE = 220.0

    private val EDO_NAME = Regex("[1-9]+[0-9]*edo")
    private const val MAX_EDO = 1_000_000

    private val PRESETS: Map<String, List<Double>> = mapOf(
        "12ji" to listOf(
            1.0 / 1, 16.0 / 15, 9.0 / 8, 6.0 / 5, 5.0 / 4, 4.0 / 3, 45.0 / 32, 3.0 / 2, 8.0 / 5, 5.0 / 3, 16.0 / 9, 15.0 / 8,
        ),
    )

    /** The scales of tune.js: name to frequencies (`TuningList`), read from the resource on first use. */
    private val tunings: Map<String, DoubleArray> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        val stream = Xen::class.java.getResourceAsStream("/cosic/xen/tunings.tsv")
            ?: throw CosicError("the list of tunings is missing")
        val map = HashMap<String, DoubleArray>(4096)
        stream.bufferedReader(Charsets.UTF_8).useLines { lines ->
            for (line in lines) {
                val tab = line.indexOf('\t')
                if (tab <= 0) continue
                val freqs = line.substring(tab + 1).split(' ')
                map[line.substring(0, tab)] = DoubleArray(freqs.size) { freqs[it].toDouble() }
            }
        }
        map
    }

    /** `TuningList[name].frequencies`, null for a name the list does not have. */
    fun tuning(name: String): DoubleArray? = tunings[name]

    fun isEdo(name: Any?): Boolean = EDO_NAME.matches(jsToString(name))

    /** `edo('31edo')`: the ratios of the steps of an equal division of the octave, `2^(i/31)` (xen.mjs:11-17). */
    fun edo(name: Any?): List<Double> {
        val text = jsToString(name)
        if (!EDO_NAME.matches(text)) throw CosicError("not an edo scale: \"$text\"")
        val divisions = text.removeSuffix("edo").toIntOrNull()?.takeIf { it <= MAX_EDO } ?: throw CosicError("Invalid array length")
        return List(divisions) { StrictMath.pow(2.0, it.toDouble() / divisions) }
    }

    /** `Tune.loadScale`: the ratios of every note but the last (the octave) to the first. */
    fun ratiosOf(freqs: List<Double>): List<Double> = List(maxOf(freqs.size - 1, 0)) { freqs[it] / freqs[0] }

    /**
     * A scale as a list of ratios: an edo name, a preset, a name of the tune.js list, or a list of ratios (xen.mjs:35-54);
     * its frequencies in base 220. [indices] keeps only those degrees (unused by Strudel itself).
     */
    fun getXenScale(scale: Any?, indices: List<Int>? = null): List<Double> {
        val ratios: List<Double> = when {
            scale is String -> when {
                isEdo(scale) -> edo(scale)
                PRESETS.containsKey(scale) -> PRESETS.getValue(scale)
                tunings.containsKey(scale) -> ratiosOf(tunings.getValue(scale).asList())
                else -> throw CosicError("unknown scale name: \"$scale\"")
            }
            scale is List<*> -> scale.map { jsToNumber(it) }
            else -> throw CosicError("scale.map is not a function")
        }
        val scaled = ratios.map { it * DEFAULT_BASE }
        return if (indices == null) scaled else scaled.filterIndexed { i, _ -> i in indices }
    }

    /** The [offset]-th degree of a scale (counted from [index]), going up an octave each time it wraps (xen.mjs:56-60). */
    fun xenOffset(scale: List<Double>, offset: Double, index: Double = 0.0): Double {
        val i = jsMod(index + offset, scale.size.toDouble())
        val oct = Math.floor(offset / scale.size)
        return scale.lookup(i) * StrictMath.pow(2.0, oct)
    }

    /** `parseFloat(f.toPrecision(10))` (xen.mjs:62). */
    fun trimFreq(f: Double): Double =
        if (f.isNaN() || f.isInfinite()) f else BigDecimal(f).round(MathContext(10, RoundingMode.HALF_UP)).toDouble()
}

/** The `tune` object of tune.js with a loaded scale, its tonic being 1 (tune.mjs:27-37). */
internal class Tune(private val scale: List<Double>) {
    /** `tune.note(step)` in frequency mode: the ratio of a scale step to the tonic, cut to 11 decimals (tunejs.js:67-103). */
    fun note(stepIn: Any?): Double {
        val step = jsToNumber(stepIn)
        val octave = Math.floor(step / scale.size)
        var degree = step % scale.size
        while (degree < 0) degree += scale.size
        var freq = 1.0 * scale.lookup(degree)
        freq *= StrictMath.pow(2.0, octave)
        return Math.floor(freq * 100000000000.0) / 100000000000.0
    }

    companion object {
        /** `new Tune()` with [scale] loaded: a name of the tune.js list or a non-empty list of numbers; null when it is neither. */
        fun of(scale: Any?): Tune? = when {
            scale is String -> Xen.tuning(scale)?.let { Tune(Xen.ratiosOf(it.asList())) }
            scale is List<*> && scale.isNotEmpty() && scale.all { it is Number && !it.toDouble().isNaN() } ->
                Tune(Xen.ratiosOf(scale.map { (it as Number).toDouble() }))
            else -> null
        }
    }
}

/** `parray`: a pattern of the lists the values of the [pats] make together, the structure coming from all of them (pattern.mjs:4325). */
private fun parray(pats: List<Any?>): Pattern {
    var acc = pure(curry(pats.size) { it })
    for (p in pats) acc = acc.appBoth(reify(p))
    return acc
}

/** The ratio a list of numbers stands for: `[3, 2]` is 3/2, a longer list is divided from left to right (`xen.mjs:109`). */
private fun reduceRatio(y: Any?): Any? {
    val list = y as? List<*> ?: throw CosicError("y.reduce is not a function")
    if (list.isEmpty()) throw CosicError("Reduce of empty array with no initial value")
    var acc = list[0]
    for (k in 1 until list.size) acc = jsToNumber(acc) / jsToNumber(list[k])
    return acc
}

private fun scaleHaps(haps: List<Hap>, j: Int): Hap = haps.getOrNull(j) ?: throw CosicError("Cannot read properties of undefined (reading 'value')")

/**
 * `pat.xen(scales)` (xen.mjs:102-195): each hap of a pattern of control maps with `i` set (the scale degree) becomes the same
 * hap with `freq` in place of `i`. [scales] is a pattern of scale names, of lists of ratios, or a list of patterns of ratios
 * (`"3:2"` is 3/2) whose position in the list is chosen by `i` too.
 */
internal fun Pattern.xen(scales: Pattern): Pattern {
    val pat = this
    return Pattern { state ->
        val out = ArrayList<Hap>()
        for (hap in pat.query(state)) {
            val hVal = hap.value
            if (jsTypeof(hVal) != "object") {
                if (hVal === Undefined) throw CosicError("Cannot read properties of undefined (reading 'i')")
                throw CosicError("Expected hap to have control 'i' set, but received undefined, try wrapping input in i()")
            }
            if (hVal == null) throw CosicError("Cannot destructure property 'i' of 'hVal' as it is null.")
            val values = objectOf(hVal)
            val i = values.getJs("i")
            val other = LinkedHashMap(values).also { it.remove("i") }
            val span = hap.wholeOrPart().intersection(state.span) ?: throw CosicError("TimeSpans do not intersect")
            val st = State(span, state.controls)

            val sc = scales.query(st)
            if (sc.isEmpty()) throw CosicError("Cannot read properties of undefined (reading 'value')")
            val first = sc[0].value
            val isStr = first is String
            val isRawScale = first is List<*> && first.all { it is Number }

            fun withFreq(freq: Double): LinkedHashMap<String, Any?> = jsMerge(other, linkedMapOf("freq" to Xen.trimFreq(freq)))

            if (!isStr && !isRawScale) {
                // a list of patterns, one per scale degree, each of them a ratio
                val list = when {
                    first is List<*> -> first
                    isNullish(first) -> throw CosicError("Cannot read properties of ${if (first == null) "null" else "undefined"} (reading 'length')")
                    else -> throw CosicError("${jsToString(first)} is not iterable")
                }
                val scaleByIndices = list.mapIndexed { j, p ->
                    (p as? Pattern ?: throw CosicError("sc[j].query is not a function")).query(st)
                }
                val sc2 = parray(list).withValue { x -> (x as List<*>).map(::reduceRatio) }.query(st)
                val index = jsMod(jsToNumber(i), list.size.toDouble())
                val atIndex = if (index == Math.floor(index) && index >= 0 && index < scaleByIndices.size) scaleByIndices[index.toInt()]
                else throw CosicError("Cannot read properties of undefined (reading 'length')")
                for (j in atIndex.indices) {
                    val scale = Xen.getXenScale(scaleHaps(sc2, j).value)
                    val freq = Xen.xenOffset(scale, parseNumeral(i))
                    out.add(Hap(hap.whole, hap.part, withFreq(freq), hap.combineContext(atIndex[j])))
                }
            } else if (isRawScale) {
                val scale = Xen.getXenScale(first)
                out.add(hap.copy(value = withFreq(Xen.xenOffset(scale, parseNumeral(i)))))
            } else {
                // named scales
                for (named in sc) {
                    val name = named.value
                    val scale = Xen.getXenScale(name)
                    val freq = Xen.xenOffset(scale, parseNumeral(i))
                    var context = hap.combineContext(named)
                    if (Xen.isEdo(name)) context = context.copy(extra = context.extra + ("edoSize" to jsToString(name).removeSuffix("edo")))
                    out.add(Hap(hap.whole, hap.part, withFreq(freq), context))
                }
            }
        }
        out
    }
}

/**
 * `pat.withBase(base)` (xen.mjs:214-235): a pattern of frequencies tuned to a base of 220 Hz (or `[base, originalBase]`)
 * gets another base. A hap without a `freq` stays as it is; the result is not rounded.
 */
internal fun Pattern.withBase(b: Any?): Pattern {
    val base: Any?
    val originalBase: Any?
    if (b is List<*>) {
        base = b.at(0)
        originalBase = b.at(1)
    } else {
        base = b
        originalBase = Xen.DEFAULT_BASE
    }
    return withHaps { haps, _ ->
        haps.map { hap ->
            val hVal = hap.value
            val isObject = jsTypeof(hVal) == "object"
            if (isObject && hVal == null) throw CosicError("Cannot read properties of null (reading 'freq')")
            val freq = if (isObject) objectOf(hVal).getJs("freq") else hVal
            if (!jsTruthy(freq)) return@map hap
            val scaled = jsToNumber(freq) * jsToNumber(base) / jsToNumber(originalBase)
            hap.copy(value = if (isObject) jsMerge(objectOf(hVal), linkedMapOf("freq" to scaled)) else linkedMapOf("freq" to scaled))
        }
    }
}

/**
 * `pat.ftranspose(amt)` (xen.mjs:270-300): moves frequencies up by [amt] steps of an equal division of the octave: the size
 * of `[steps, edoSize]`, else the one `xen("31edo")` left in the hap's context, else 12. Like the JavaScript, the size that is
 * found first is kept for every later hap of this call.
 */
internal fun Pattern.ftranspose(amt: Any?): Pattern {
    var edoSize: Any? = Undefined
    val numSteps: Any?
    if (amt is List<*>) {
        edoSize = amt.at(1)
        numSteps = amt.at(0)
    } else {
        numSteps = amt
    }
    return withHaps { haps, _ ->
        haps.map { hap ->
            val hVal = hap.value
            val isObject = jsTypeof(hVal) == "object"
            if (isObject && hVal == null) throw CosicError("Cannot destructure property 'freq' of 'hVal' as it is null.")
            val values = if (isObject) objectOf(hVal) else linkedMapOf("freq" to hVal)
            val inContext = hap.context.extra["edoSize"]
            if (isNullish(edoSize) && !isNullish(inContext)) edoSize = inContext else if (isNullish(edoSize)) edoSize = 12.0
            val freq = Xen.trimFreq(jsToNumber(values.getJs("freq")) * StrictMath.pow(2.0, jsToNumber(numSteps) / jsToNumber(edoSize)))
            val value: Any? = if (isObject) {
                val others = LinkedHashMap(values).also { it.remove("freq") }
                jsMerge(others, linkedMapOf("freq" to freq))
            } else freq
            hap.copy(value = value, context = hap.context.copy(extra = hap.context.extra + ("edoSize" to edoSize)))
        }
    }
}

/** `pat.tuning(ratios)` (xen.mjs:303-308): each value is a step of the scale [ratios], played in base 1. */
internal fun Pattern.tuning(ratios: Any?): Pattern {
    val scale = numberList(ratios)
    return withHap { hap ->
        val offset = parseNumeral(hap.value)
        hap.copy(value = if (scale == null) Double.NaN else Xen.xenOffset(scale, offset))
    }
}

/**
 * `pat.tune(scale)` (tune.mjs, deprecated): the values of a pattern of control maps with `i` set become the ratios of that
 * degree of [scale] (a name of the tune.js list or a list of frequencies).
 */
internal fun Pattern.tune(scale: Any?): Pattern {
    val tune = Tune.of(scale)
        ?: throw CosicError("not a valid tune.js scale name: \"${jsToString(scale)}\". See http://abbernie.github.io/tune/scales.html")
    return withHap { hap ->
        val value = hap.value
        if (jsTypeof(value) != "object") {
            if (value === Undefined) throw CosicError("Cannot read properties of undefined (reading 'i')")
            throw CosicError("Expected hap to have control 'i' set, but received undefined, try wrapping input in i()")
        }
        if (value == null) throw CosicError("Cannot read properties of null (reading 'i')")
        hap.copy(value = tune.note(objectOf(value).getJs("i")))
    }
}
