package cosic.pattern

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.truncate

/*
 * Randomness (`signal.mjs`): the random number generator, `rand` and the signals made from it, choosing from lists, and
 * the functions that drop or apply things at random (`degradeBy`, `sometimes`, `shuffle`, ...).
 *
 * Nothing here is random: every number is a function of a point in time and a seed, so a pattern plays the same every
 * time, and the same moment of two patterns gets the same number unless one of them has a `seed`. A signal reads its seed
 * from the controls of the query (`randSeed`, set by `seed(n)`).
 *
 * Functions that other patterns are built from take their arguments raw, as the function inside of Strudel's `register`
 * does; `_name` is the form without patternification. The catalogue entries are in script/catalogue/Random.kt. The
 * functions of other categories (`segment`, `range`, `zoom`, `repeatCycles`, `add`, `mul`, `when`) are called through
 * the registry, like a program would call them, so their catalogue is the one that counts.
 */

// ---- the generator ----------------------------------------------------------------------------------------------------

/** Which algorithm makes the random numbers: Strudel's original one, or the more uniform one of `useRNG('precise')`. */
internal enum class RngMode { LEGACY, PRECISE }

/**
 * The generator in use (`useRNG`). It is global, like it is in Strudel, but every program starts with the default
 * ([reset] is called when one is evaluated), so a `useRNG` in one program does not leak into the next.
 */
internal object Rng {
    @Volatile
    var mode: RngMode = RngMode.LEGACY

    fun reset() {
        mode = RngMode.LEGACY
    }
}

/** The key of the controls of a query that holds the seed (`seed(n)`). */
internal const val RAND_SEED_KEY = "randSeed"

private const val TWO_POW_29 = 536870912.0
private const val TWO_POW_32 = 4294967296.0

/** `useRNG(mode)`: `'legacy'` (the default) or any other text for the precise generator. Gives back [mode], as it does. */
internal fun useRNG(mode: Any? = "legacy"): Any? {
    val chosen = if (mode === Undefined) "legacy" else mode
    Rng.mode = if (chosen == "legacy") RngMode.LEGACY else RngMode.PRECISE
    return chosen
}

// The legacy generator: 300 cycles are stretched over the range of 2^29 and an xorshift scrambles the result.

private fun xorwise(x: Int): Int {
    val a = (x shl 13) xor x
    val b = (a shr 17) xor a
    return (b shl 5) xor b
}

private fun legacySeed(x: Double): Int {
    val q = x / 300.0
    return xorwise(jsToInt32(truncate((q - truncate(q)) * TWO_POW_29)))
}

private fun legacyRand(seed: Int): Double = (seed % 536870912).toDouble() / TWO_POW_29

/** `t + seed`, the way JavaScript adds them: a missing seed is 0, and a text seed is appended to the text of the time. */
private fun timePlusSeed(t: Double, seed: Any?): Double = when (seed) {
    is Double -> t + seed
    null, Undefined -> t
    is Number -> t + seed.toDouble()
    is Rational -> t + seed.toDouble()
    is Boolean -> t + (if (seed) 1.0 else 0.0)
    else -> jsStringToNumber(jsNumberToString(t) + jsToString(seed))
}

// The precise generator: a murmur hash of the time (to 2^-29 of a cycle), the index of the number and the seed.

private fun murmurFinalizer(x0: Int): Int {
    var x = x0
    x = x xor (x ushr 16)
    x *= 0x85ebca6b.toInt()
    x = x xor (x ushr 13)
    x *= 0xc2b2ae35.toInt()
    x = x xor (x ushr 16)
    return x
}

private fun decorrelate(bigT: Double, i: Int, seed: Int): Int {
    val low = jsToInt32(bigT)
    val high = jsToInt32(floor(bigT / TWO_POW_32))
    var key = low xor ((high xor 0x85ebca6b.toInt()) * 0xc2b2ae35.toInt())
    key = key xor ((i xor 0x7f4a7c15) * 0x9e3779b9.toInt())
    key = key xor ((seed xor 0x165667b1) * 0x27d4eb2d)
    return key
}

private fun preciseRand(bigT: Double, i: Int, seed: Any?): Double {
    val h = murmurFinalizer(decorrelate(bigT, i, jsToInt32(jsToNumber(seed))))
    return (h.toLong() and 0xFFFFFFFFL).toDouble() / TWO_POW_32
}

/** The random number in 0..1 at time [t] (`getRandsAtTime(t, 1, seed)`); a missing [seed] is 0. */
internal fun getRandAtTime(t: Double, seed: Any? = null): Double = when (Rng.mode) {
    RngMode.LEGACY -> abs(legacyRand(legacySeed(timePlusSeed(t, seed))))
    RngMode.PRECISE -> preciseRand(floor(t * TWO_POW_29), 0, seed)
}

/**
 * [n] random numbers at time [t] (`getRandsAtTime(t, n, seed)` for any n but 1, which gives a single number). The legacy
 * generator's numbers are signed, between -1 and 1.
 */
internal fun getRandsAtTime(t: Double, n: Int, seed: Any? = null): DoubleArray {
    val count = max(n, 0)
    return when (Rng.mode) {
        RngMode.LEGACY -> {
            var s = legacySeed(timePlusSeed(t, seed))
            DoubleArray(count) {
                val r = legacyRand(s)
                s = xorwise(s)
                r
            }
        }
        RngMode.PRECISE -> {
            val bigT = floor(t * TWO_POW_29)
            DoubleArray(count) { preciseRand(bigT, it, seed) }
        }
    }
}

/** How many values a program may ask a generator for at once, as a whole number of iterations: `for (i = 0; i < n; i++)`. */
private fun valueCount(n: Double, name: String): Int {
    if (!(n > 0.0)) return 0
    if (n > MAX_COUNT) throw CosicError("'$name' expects a number up to $MAX_COUNT, got ${jsNumberToString(n)}")
    return ceil(n).toInt()
}

/** `getRandsAtTime(t, n = 1, seed = 0)` as a program calls it: a number for `n === 1`, any other count gives a list. */
internal fun jsGetRandsAtTime(t: Any?, n: Any?, seed: Any?): Any {
    val time = jsToNumber(t)
    if (n === Undefined || (n is Number && n.toDouble() == 1.0)) return getRandAtTime(time, seed)
    return getRandsAtTime(time, valueCount(jsToNumber(n), "getRandsAtTime"), seed).toList()
}

// ---- random signals ---------------------------------------------------------------------------------------------------

/** A continuous pattern of random numbers between 0 and 1. */
internal val rand: Pattern = signal { t, c -> getRandAtTime(t.toDouble(), c[RAND_SEED_KEY]) }

/** Random numbers between -1 and 1. */
internal val rand2: Pattern = rand.toBipolarValues()

/** `1 - rand`: what `undegradeBy` tests against. */
private val randInverse: Pattern = rand.fmap { 1.0 - jsToNumber(it) }

/** Random booleans, true with probability [p] (`_brandBy`). */
internal fun _brandBy(p: Any?): Pattern = rand.fmap { x -> jsLess(x, p) == true }

/** Random booleans with a patterned probability (`brandBy`). */
internal fun brandBy(pPat: Any?): Pattern = reify(pPat).fmap { p -> _brandBy(p) }.innerJoin()

/** Random booleans, true half of the time. */
internal val brand: Pattern = _brandBy(0.5)

/** Random whole numbers from 0 up to, not including, [i] (`_irand`). */
internal fun _irand(i: Any?): Pattern {
    val n = jsToNumber(i)
    return rand.fmap { x -> truncate(jsToNumber(x) * n) }
}

/** Random whole numbers from 0 up to, not including, the numbers of [ipat] (`irand`). */
internal fun irand(ipat: Any?): Pattern = reify(ipat).fmap { i -> _irand(i) }.innerJoin()

/** Veltkamp's constant, 2^27 + 1: it splits a double into two halves that multiply without a rounding error. */
private const val VELTKAMP = 134217729.0

/** The rounding error of the product [p] of [a] and [b] (Dekker's `twoProduct`), so that `p + error` is exactly `a * b`. */
private fun productError(a: Double, b: Double, p: Double): Double {
    val ca = VELTKAMP * a
    val ah = ca - (ca - a)
    val al = a - ah
    val cb = VELTKAMP * b
    val bh = cb - (cb - b)
    val bl = b - bh
    return ((ah * bh - p) + ah * bl + al * bh) + al * bl
}

/**
 * `6 * x ** 5 - 15 * x ** 4 + 10 * x ** 3`, with the powers rounded to the nearest number. V8 computes `**` with the C
 * library's `pow`, which gives the nearest number all but a few times in a thousand (checked against Node on thousands of
 * numbers), while the fdlibm `pow` that `StrictMath` has is off by one in the last digit for one number in ten. The
 * powers are made of products that are computed with their rounding errors (a number and the error it leaves, a "double
 * double") and rounded once at the end.
 */
private fun smootherStep(x: Double): Double {
    if (x != 0.0 && !(abs(x) > 1e-50 && abs(x) < 1e50)) {
        return 6.0 * StrictMath.pow(x, 5.0) - 15.0 * StrictMath.pow(x, 4.0) + 10.0 * StrictMath.pow(x, 3.0)
    }
    val x2 = x * x
    val x2Error = productError(x, x, x2)
    val x3Hi = x2 * x
    val x3 = x3Hi + (productError(x2, x, x3Hi) + x2Error * x)
    val x4Hi = x2 * x2
    val x4Error = productError(x2, x2, x4Hi) + 2.0 * x2 * x2Error
    val x4 = x4Hi + x4Error
    val x5Hi = x4Hi * x
    val x5 = x5Hi + (productError(x4Hi, x, x5Hi) + x4Error * x)
    return 6.0 * x5 - 15.0 * x4 + 10.0 * x3
}

private fun perlinAt(t: Double, seed: Any?): Double {
    val ta = floor(t)
    val ra = getRandAtTime(ta, seed)
    val rb = getRandAtTime(ta + 1.0, seed)
    return ra + smootherStep(t - ta) * (rb - ra)
}

private fun berlinAt(t: Double, seed: Any?): Double {
    val previous = floor(t)
    val next = previous + 1.0
    val bottom = getRandAtTime(previous, seed)
    val height = getRandAtTime(next, seed)
    val top = bottom + height
    val percent = (t - previous) / (next - previous)
    return (bottom + percent * (top - bottom)) / 2.0
}

/** Smooth random numbers between 0 and 1: the random numbers at whole cycles, joined by smootherstep curves. */
internal val perlin: Pattern = signal { t, c -> perlinAt(t.toDouble(), c[RAND_SEED_KEY]) }

/** Random numbers between 0 and 1 in ridges: a straight line from each cycle's random number up to its sum with the next one's, halved. */
internal val berlin: Pattern = signal { t, c -> berlinAt(t.toDouble(), c[RAND_SEED_KEY]) }

/** A signal of lists of [n] random numbers between 0 and 1 (`randL`). Asking for exactly one is an error, as it is in Strudel. */
internal fun randL(n: Any?): Pattern = signal { t, c ->
    val seed = c[RAND_SEED_KEY]
    jsFn(1) { a ->
        val rands = jsGetRandsAtTime(t, a.getOrElse(0) { Undefined }, seed)
        if (rands !is List<*>) throw CosicError("getRandsAtTime(...).map is not a function")
        rands.map { abs(it as Double) }
    }
}.appLeft(reify(n))

/**
 * A pattern of the numbers 0 to n-1 in an order that is random but different each cycle, each once per cycle
 * (`randrun`). It gives them one at a time, n per cycle.
 */
internal fun randrun(n: Any?): Pattern = signal { t, c ->
    val size = jsToNumber(n)
    val count = valueCount(size, "randrun")
    val seed = c[RAND_SEED_KEY]
    // without the half, the first cycle would always be 0, 1, 2, 3...
    val time = t.floor().toDouble() + 0.5
    val rands = if (size == 1.0) doubleArrayOf(getRandAtTime(time, seed)) else getRandsAtTime(time, count, seed)
    val order = Array(rands.size) { it }
    java.util.Arrays.sort(order) { a, b -> (if (rands[a] > rands[b]) 1 else 0) - (if (rands[a] < rands[b]) 1 else 0) }
    val position = jsMod((t.cyclePos() * Rational.of(n)).floor().toDouble(), size)
    if (position >= 0.0 && position < order.size && position == floor(position)) order[position.toInt()].toDouble() else Undefined
}.callMethod("segment", n)

/** `pat` with the seed of the random signals in it replaced by `func(seed)` (`withSeed`); `seed` is `undefined` when there is none. */
internal fun withSeed(func: (Any?) -> Any?, pat: Pattern): Pattern = Pattern({ state ->
    val current = if (state.controls.containsKey(RAND_SEED_KEY)) state.controls[RAND_SEED_KEY] else Undefined
    pat.query(state.setControls(mapOf(RAND_SEED_KEY to func(current))))
}, pat.steps)

// ---- shuffling --------------------------------------------------------------------------------------------------------

/** `[...Array(n).keys()].length`: the number of slices, which `Array(n)` insists is a whole number. */
private fun sliceCount(n: Any?, name: String): Int {
    val size = jsToNumber(n)
    if (size < 0.0 || size != floor(size) || size > TWO_POW_32 - 1.0) throw CosicError("Invalid array length")
    if (size > MAX_COUNT) throw CosicError("'$name' expects a number up to $MAX_COUNT, got ${jsNumberToString(size)}")
    return size.toInt()
}

/** Cuts [pat] into [n] slices and plays them one after the other in the order [ipat] gives, one slice per step. */
private fun rearrangeWith(ipat: Pattern, n: Any?, pat: Pattern, name: String): Pattern {
    val count = sliceCount(n, name)
    val divisor = if (count == 0) Rational.ONE else Rational.of(n)
    val slices = List(count) { i -> pat.callMethod("zoom", Rational(i.toLong()) / divisor, Rational(i + 1L) / divisor) }
    return ipat.fmap { i ->
        val index = jsToNumber(i)
        if (!(index >= 0.0 && index < slices.size && index == floor(index))) {
            throw CosicError("Cannot read properties of undefined (reading 'repeatCycles')")
        }
        slices[index.toInt()].callMethod("repeatCycles", n)._fast(n)
    }.innerJoin()
}

/** Cuts [this] into [n] slices and plays them in a random order, each slice once per cycle. */
internal fun Pattern._shuffle(n: Any?): Pattern = rearrangeWith(randrun(n), n, this, "shuffle")

/** Cuts [this] into [n] slices and plays slices picked at random, so some may play twice and some not at all. */
internal fun Pattern._scramble(n: Any?): Pattern =
    rearrangeWith(_irand(n).callMethod("segment", n), n, this, "scramble")

// ---- choosing ---------------------------------------------------------------------------------------------------------

/**
 * `__chooseWith(pat, xs)`: a pattern of patterns. [pat], numbers in 0..1, picks which of [xs] (patterns, or values that
 * are made patterns) is the one at each moment.
 */
internal fun rawChooseWith(pat: Pattern, xs: List<Any?>): Pattern {
    val choices = xs.map(::reify)
    if (choices.isEmpty()) return silence
    val last = choices.size - 1.0
    return pat.callMethod("range", 0.0, choices.size.toDouble()).fmap { i ->
        val key = min(max(floor(jsToNumber(i)), 0.0), last)
        if (key.isNaN()) Undefined else choices[key.toInt()]
    }
}

/** Plays whichever of [xs] the numbers of [pat] (0..1) pick; the structure comes from [pat]. */
internal fun chooseWith(pat: Pattern, xs: List<Any?>): Pattern = rawChooseWith(pat, xs).outerJoin()

/** Like [chooseWith], but the structure comes from the values that are chosen. */
internal fun chooseInWith(pat: Pattern, xs: List<Any?>): Pattern = rawChooseWith(pat, xs).innerJoin()

/** Chooses at random from [xs]. */
internal fun choose(xs: List<Any?>): Pattern = chooseWith(rand, xs)

internal fun chooseIn(xs: List<Any?>): Pattern = chooseInWith(rand, xs)

/** Picks one of [xs] at random each cycle. */
internal fun chooseCycles(xs: List<Any?>): Pattern = chooseInWith(rand.callMethod("segment", 1.0), xs)

private fun pairItem(pair: Any?, index: Int): Any? =
    (pair as? List<*> ?: throw CosicError("expected [value, weight] pairs")).getOrElse(index) { Undefined }

/**
 * `_wchooseWith(pat, ...pairs)`: a pattern of the values of [pairs] (`[value, weight]`), chosen by the numbers of [pat] in 0..1
 * with a probability proportional to the weights, which may themselves be patterns.
 */
private fun weightedChooseWith(pat: Pattern, pairs: List<Any?>): Pattern {
    val values = pairs.map { reify(pairItem(it, 0)) }
    var total: Pattern = pure(0.0)
    val cumulative = ArrayList<Pattern>()
    for (pair in pairs) {
        total = total.callMethod("add", pairItem(pair, 1))
        cumulative.add(total)
    }
    val weights = sequenceP(cumulative)
    val sum = total
    return pat.bind { r ->
        weights.fmap { ws ->
            jsFn(1) { a ->
                val find = a[0]
                val index = (ws as List<*>).indexOfFirst { x -> jsLess(find, x) == true }
                if (index < 0) Undefined else values[index]
            }
        }.appLeft(sum.callMethod("mul", r))
    }
}

/** Chooses from the values of [pairs] (`[value, weight]`) with the probabilities the weights give. */
internal fun wchoose(pairs: List<Any?>): Pattern = weightedChooseWith(rand, pairs).outerJoin()

/** Picks one of the values of [pairs] (`[value, weight]`) each cycle, with the probabilities the weights give. */
internal fun wchooseCycles(pairs: List<Any?>): Pattern = weightedChooseWith(rand.callMethod("segment", 1.0), pairs).innerJoin()

// ---- dropping events and applying functions at random -----------------------------------------------------------------

/**
 * Keeps the events of [this] whose value of [withPat] (a pattern, or a number) is greater than [x]: [withPat] is read
 * at the start of each event.
 */
internal fun Pattern._degradeByWith(withPat: Any?, x: Any?): Pattern {
    val test = if (withPat is Pattern) withPat else pure(withPat)
    return fmap { a -> jsFn(1) { a } }.appLeft(test.filterValues { v -> jsLess(x, v) == true })
}

/** Removes the share [x] (0..1) of the events at random. */
internal fun Pattern._degradeBy(x: Any?): Pattern = _degradeByWith(rand, x).setSteps(steps)

/** Removes about half of the events at random. */
internal fun Pattern._degrade(): Pattern = _degradeBy(0.5).setSteps(steps)

/**
 * The other half of [_degradeBy]: keeps the events whose `1 - rand` is greater than [x], which are the ones `_degradeBy(1 - x)` removes.
 * `sometimesBy(x, f)` plays `f` of `_undegradeBy(1 - x)` next to `_degradeBy(x)`, so each event is in exactly one of the two.
 */
internal fun Pattern._undegradeBy(x: Any?): Pattern = _degradeByWith(randInverse, x).setSteps(steps)

internal fun Pattern._undegrade(): Pattern = _undegradeBy(0.5).setSteps(steps)

/** Applies [func] to a random share of the events, the probability [patx] being a number or a pattern of numbers. */
internal fun Pattern._sometimesBy(patx: Any?, func: Any?): Pattern {
    val pat = this
    return reify(patx).fmap { x ->
        stack(pat._degradeBy(x), callFn(func, pat._undegradeBy(1.0 - jsToNumber(x))))
    }.innerJoin()
}

/** Applies [func] to a random half of the events. */
internal fun Pattern._sometimes(func: Any?): Pattern = _sometimesBy(0.5, func)

/** Applies [func] on a random share of the cycles, whole cycles at a time. */
internal fun Pattern._someCyclesBy(patx: Any?, func: Any?): Pattern {
    val pat = this
    return reify(patx).fmap { x ->
        stack(
            pat._degradeByWith(rand.callMethod("segment", 1.0), x),
            callFn(func, pat._degradeByWith(randInverse.callMethod("segment", 1.0), 1.0 - jsToNumber(x))),
        )
    }.innerJoin()
}

internal fun Pattern._someCycles(func: Any?): Pattern = _someCyclesBy(0.5, func)

// ---- mini-notation ----------------------------------------------------------------------------------------------------

/** How far the random signal of each `?` or `|` of a mini-notation string is moved, per its number, so that they do not all agree. */
internal const val MINI_RAND_OFFSET = 0.0003

/** The random signal of the [seed]th `?` or `|` of a mini-notation string: `rand` moved [MINI_RAND_OFFSET] cycles earlier per seed. */
internal fun miniRand(seed: Int): Pattern = rand._early(MINI_RAND_OFFSET * seed)
