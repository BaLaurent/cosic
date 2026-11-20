package cosic.pattern

import kotlin.math.ceil
import kotlin.math.floor

/*
 * The operator family of pattern.mjs (CORE Task T2): `set keep keepif add sub mul div mod pow band bor bxor blshift brshift
 * lt gt lte gte eq eqt ne net and or func` (COMPOSERS, pattern.mjs:1125-1387), the alignments that combine two patterns
 * (`in out mix squeeze squeezeout reset restart poly`, `_setupAlignments`, pattern.mjs:1389-1437 with the `_op*` methods
 * of pattern.mjs:805-834), `setDefaultJoin`, and the numeric functions `round floor log2 ceil toBipolar fromBipolar range
 * rangex range2 ratio` (pattern.mjs:2128-2249).
 *
 * The catalogue (`script/catalogue/Operators.kt`) registers all of it; this file is the pattern side.
 */

/** `numeralArgs(fn)` of util.mjs:227: both values are read as numbers first (see [parseNumeral]: a note name is its MIDI number, any other word an error). */
private fun numeralArgs(fn: (Double, Double) -> Double): (Any?, Any?) -> Any? = { a, b ->
    val x = parseNumeral(a)
    fn(x, parseNumeral(b))
}

/**
 * What each operator does to two plain values: the `op` of `COMPOSERS`, in the order the object lists them. Control maps are
 * handled before an operator is called (see [composeOp]), so the operators only ever see the values inside them.
 */
internal val OPS: Map<String, (Any?, Any?) -> Any?> = buildMap<String, (Any?, Any?) -> Any?> {
    put("set") { _, b -> b }
    put("keep") { a, _ -> a }
    put("keepif") { a, b -> if (jsTruthy(b)) a else Undefined }
    put("add", numeralArgs { a, b -> a + b })
    put("sub", numeralArgs { a, b -> a - b })
    put("mul", numeralArgs { a, b -> a * b })
    put("div", numeralArgs { a, b -> a / b })
    put("mod", numeralArgs(::jsMod))
    put("pow", numeralArgs { a, b -> Math.pow(a, b) })
    put("band", numeralArgs { a, b -> (jsToInt32(a) and jsToInt32(b)).toDouble() })
    put("bor", numeralArgs { a, b -> (jsToInt32(a) or jsToInt32(b)).toDouble() })
    put("bxor", numeralArgs { a, b -> (jsToInt32(a) xor jsToInt32(b)).toDouble() })
    // the shift count is the low five bits of the right operand, which `shl` and `shr` read the same way
    put("blshift", numeralArgs { a, b -> (jsToInt32(a) shl jsToInt32(b)).toDouble() })
    put("brshift", numeralArgs { a, b -> (jsToInt32(a) shr jsToInt32(b)).toDouble() })
    put("lt") { a, b -> jsLess(a, b) == true }
    put("gt") { a, b -> jsLess(b, a) == true }
    put("lte") { a, b -> jsLess(b, a) == false }
    put("gte") { a, b -> jsLess(a, b) == false }
    put("eq") { a, b -> jsLooseEq(a, b) }
    put("eqt") { a, b -> jsStrictEq(a, b) }
    put("ne") { a, b -> !jsLooseEq(a, b) }
    put("net") { a, b -> !jsStrictEq(a, b) }
    put("and") { a, b -> if (jsTruthy(a)) b else a }
    put("or") { a, b -> if (jsTruthy(a)) a else b }
    put("func") { a, b -> if (b is JsFn) b.call(listOf(a)) else throw CosicError("b is not a function") }
}

/** The names `setDefaultJoin` accepts and the alignments an operator has as a property, `pat.add.out(...)` (`ALIGNMENTS`, pattern.mjs:1440). */
private val ALIGNMENT_KEYS = listOf("in", "out", "mix", "squeeze", "squeezeout", "reset", "restart", "poly")

/**
 * The alignments of the operators: how two patterns with their own structure become one. `pat.add(x)` is `pat.add.in(x)`
 * unless [default] says otherwise; every operator has all eight (and `squeezein`, another name for `squeeze`) as
 * `pat.add.out(x)`.
 */
internal object Alignment {
    /**
     * `DEFAULT_ALIGNMENT` of pattern.mjs:1439, what `pat.add(x)` does: `in`, unless `setDefaultJoin` changed it. Like Strudel's
     * it is global to the process; a new session starts with [reset] (Strudel's own tests do the same after every test).
     */
    @Volatile
    var default: String = "in"

    fun reset() {
        default = "in"
    }

    /**
     * `pat[op][how](...args)` (pattern.mjs:1411-1430): [op] of the values of [pat] and of the pattern the [args] make together
     * (they are a sequence, so `.add.out(1, 2)` adds `seq(1, 2)`), the structure being decided by [how]. The `keepif` operator
     * (`struct`, `mask`, `reset`, `restart`) throws the haps it removes away instead of combining their values.
     */
    fun apply(pat: Pattern, op: String, how: String, args: List<Any?>): Pattern {
        val opFn = OPS[op] ?: throw CosicError("'$op' is not an operator")
        val keepif = op == "keepif"
        val other = sequence(listOf(args))
        val combine: (Any?, Any?) -> Any? = if (keepif) opFn else { a, b -> composeOp(a, b, opFn) }
        val result = when (how) {
            "in" -> pat.zipLeft(other, combine)
            "out" -> pat.fmap { a -> jsFn(1) { combine(a, it[0]) } }.appRight(other)
            "mix" -> pat.fmap { a -> jsFn(1) { combine(a, it[0]) } }.appBoth(other)
            "squeeze", "squeezein" -> pat.fmap { a -> other.fmap { b -> combine(a, b) } }.squeezeJoin()
            "squeezeout" -> other.fmap { a -> pat.fmap { b -> combine(b, a) } }.squeezeJoin()
            "reset" -> other.fmap { b -> pat.fmap { a -> combine(a, b) } }.resetJoin()
            "restart" -> other.fmap { b -> pat.fmap { a -> combine(a, b) } }.restartJoin()
            // the operands are the other way round: `op(other, this)` (pattern.mjs:831-834)
            "poly" -> pat.fmap { b -> other.fmap { a -> combine(a, b) } }.polyJoin()
            else -> throw CosicError("alignment '$how' is not available")
        }
        return if (keepif) result.removeUndefineds() else result
    }
}

/** `pat[op][how](...args)`, see [Alignment.apply]. */
internal fun Pattern.aligned(op: String, how: String, args: List<Any?>): Pattern = Alignment.apply(this, op, how, args)

/**
 * `setDefaultJoin(alignment)` (pattern.mjs:1525-1531): the alignment `pat.add(x)` and the controls (`s("bd").n(x)`) use from
 * now on. The name is read in lower case; one that is not an alignment, and `undefined`, change nothing.
 */
internal fun setDefaultJoin(alignment: Any?) {
    val key = when (alignment) {
        null, Undefined -> return
        is String -> alignment.lowercase()
        else -> throw CosicError("alignment?.toLowerCase is not a function")
    }
    if (key != Alignment.default && key in ALIGNMENT_KEYS) Alignment.default = key
}

/**
 * The operator [op] as a function of a value and a pattern, curried: `add(1, pat)` and `add(1)(pat)` are `reify(pat).add(1)`
 * (`export const add = curry((a, b) => reify(b).add(a))`, pattern.mjs:1976-2000).
 */
internal fun operatorGlobal(op: String): JsFn = curry(2) { a -> Alignment.apply(reify(a[1]), op, Alignment.default, listOf(a[0])) }

// ---- the numeric functions -------------------------------------------------------------------------------------------

/** `a - b` of two JavaScript values. */
private fun jsMinus(a: Any?, b: Any?): Double = jsToNumber(a) - jsToNumber(b)

/** `a + b`: a number, or the text of both when either is a string (so `"2" + 1` is `"21"`). */
private fun jsPlus(a: Any?, b: Any?): Any {
    val x = jsToPrimitive(a)
    val y = jsToPrimitive(b)
    return if (x is String || y is String) jsToString(x) + jsToString(y) else jsToNumber(x) + jsToNumber(y)
}

/** `Math.round` of every value, read as a number (`round`, pattern.mjs:2128). */
internal fun Pattern.round(): Pattern = asNumber().fmap { jsMathRound(it as Double) }

/** `Math.floor` (`floor`, pattern.mjs:2142). */
internal fun Pattern.floor(): Pattern = asNumber().fmap { floor(it as Double) }

/** `Math.ceil` (`ceil`, pattern.mjs:2159). */
internal fun Pattern.ceil(): Pattern = asNumber().fmap { ceil(it as Double) }

/** `Math.log2` (`log2`, pattern.mjs:2146). */
internal fun Pattern.log2(): Pattern = asNumber().fmap { jsLog2(it as Double) }

/** Values from 0 to 1 as values from -1 to 1: `x * 2 - 1` (`toBipolar`, pattern.mjs:2169). */
internal fun Pattern.toBipolar(): Pattern = fmap { jsToNumber(it) * 2 - 1 }

/** Values from -1 to 1 as values from 0 to 1: `(x + 1) / 2` (`fromBipolar`, pattern.mjs:2180). */
internal fun Pattern.fromBipolar(): Pattern = fmap { jsToNumber(jsPlus(it, 1.0)) / 2 }

/**
 * Values from 0 to 1 as values from [min] to [max]: `pat.mul(max - min).add(min)`, two operators under the default alignment,
 * not one `fmap` (`range`, pattern.mjs:2196). A signal that is asked for a span over a cycle boundary answers in pieces.
 */
internal fun Pattern._range(min: Any?, max: Any?): Pattern = callMethod("mul", jsMinus(max, min)).callMethod("add", min)

/** Like [_range], along an exponential curve: the range of the logarithms, then `Math.exp` (`rangex`, pattern.mjs:2212). */
internal fun Pattern._rangex(min: Any?, max: Any?): Pattern =
    _range(StrictMath.log(jsToNumber(min)), StrictMath.log(jsToNumber(max))).fmap { StrictMath.exp(jsToNumber(it)) }

/** Values from -1 to 1 as values from [min] to [max] (`range2`, pattern.mjs:2227). */
internal fun Pattern._range2(min: Any?, max: Any?): Pattern = fromBipolar()._range(min, max)

/** A list value (`"5:4"` is `[5, 4]`) is its first number divided by the others; any other value stays as it is (`ratio`, pattern.mjs:2242). */
internal fun Pattern.ratio(): Pattern = fmap { v ->
    if (v !is List<*>) {
        v
    } else if (v.isEmpty()) {
        Undefined
    } else {
        v.drop(1).fold(v[0]) { acc, n -> jsToNumber(acc) / jsToNumber(n) }
    }
}

// ---- Math.log2 --------------------------------------------------------------------------------------------------------

/**
 * `Math.log2` as V8 computes it (`ieee754::log2`, FreeBSD's e_log2.c): exact for powers of two, which `ln(x) / ln(2)` is
 * not (it is off by an ulp for a third of the integers), and what JavaScript prints.
 */
internal fun jsLog2(x0: Double): Double {
    var x = x0
    var bits = x.toRawBits()
    var hx = (bits ushr 32).toInt()
    val lx = bits.toInt()
    var k = 0
    if (hx < 0x00100000) { // x < 2**-1022
        if (((hx and 0x7fffffff) or lx) == 0) return Double.NEGATIVE_INFINITY // log(+-0) = -inf
        if (hx < 0) return Double.NaN // log(-#) = NaN
        k -= 54
        x *= TWO54 // a subnormal number, scaled up
        bits = x.toRawBits()
        hx = (bits ushr 32).toInt()
    }
    if (hx >= 0x7ff00000) return x + x
    if (hx == 0x3ff00000 && lx == 0) return 0.0 // log(1) = +0
    k += (hx shr 20) - 1023
    hx = hx and 0x000fffff
    val i = (hx + 0x95f64) and 0x100000
    x = Double.fromBits(((hx or (i xor 0x3ff00000)).toLong() shl 32) or (x.toRawBits() and 0xffffffffL)) // normalize x or x/2
    k += i shr 20
    val y = k.toDouble()
    val f = x - 1.0
    val hfsq = 0.5 * f * f
    val r = kLog1p(f)
    var hi = f - hfsq
    hi = Double.fromBits(hi.toRawBits() and (-1L shl 32)) // the low word cleared
    val lo = (f - hi) - hfsq + r
    var valHi = hi * IVLN2HI
    var valLo = (lo + hi) * IVLN2LO + lo * IVLN2HI
    // spadd(val_hi, val_lo, y)
    val w = y + valHi
    valLo += (y - w) + valHi
    valHi = w
    return valLo + valHi
}

private const val TWO54 = 1.80143985094819840000e+16
private const val IVLN2HI = 1.44269504072144627571e+00
private const val IVLN2LO = 1.67517131648865118353e-10

private const val LG1 = 6.666666666666735130e-01
private const val LG2 = 3.999999999940941908e-01
private const val LG3 = 2.857142874366239149e-01
private const val LG4 = 2.222219843214978396e-01
private const val LG5 = 1.818357216161805012e-01
private const val LG6 = 1.531383769920937332e-01
private const val LG7 = 1.479819860511658591e-01

/** `k_log1p` of fdlibm: `log(1 + f) - f + f*f/2` for `f` in about `[-0.2929, 0.4142]`. */
private fun kLog1p(f: Double): Double {
    val s = f / (2.0 + f)
    val z = s * s
    val w = z * z
    val t1 = w * (LG2 + w * (LG4 + w * LG6))
    val t2 = z * (LG1 + w * (LG3 + w * (LG5 + w * LG7)))
    val r = t2 + t1
    val hfsq = 0.5 * f * f
    return s * (hfsq + r)
}
