package cosic.pattern

import kotlin.math.abs
import kotlin.math.ceil

/*
 * Euclidean rhythms: `packages/core/euclid.mjs` of Strudel (ported from the Haskell module of Rohan Drape), and the
 * `_morph` that `euclidish` is built on. The arguments are raw (what `func` of `register` gets): numbers, but anything
 * that JavaScript can do arithmetic on works, as it does there. The catalogue entries are in script/catalogue/Euclid.kt.
 */

// ---- Bjorklund ---------------------------------------------------------------------------------------------------

/**
 * The length of `Array(n)`: anything that is not an integer from 0 up is the `RangeError` of JavaScript. A length above
 * [MAX_COUNT] is refused as well, which is where Cosic deliberately differs: a rhythm of that many steps is not one.
 */
private fun arrayLength(n: Double): Int {
    if (!(n >= 0.0) || n != Math.floor(n) || n.isInfinite()) throw CosicError("Invalid array length")
    if (n > MAX_COUNT) throw tooManySteps(n)
    return n.toInt()
}

private fun tooManySteps(n: Double) = CosicError("Too many steps: at most $MAX_COUNT, got ${jsNumberToString(n)}")

/**
 * Spreads `abs(ons)` hits as evenly as possible over [steps] steps with Bjorklund's algorithm: `bjorklund(3, 8)` is
 * `[1, 0, 0, 1, 0, 0, 1, 0]`. A negative [ons] inverts the rhythm (`bjorklund(-3, 8)` is `[0, 1, 1, 0, 1, 1, 0, 1]`).
 * Unlike [euclidCore] this does not stretch a rhythm over cycles: more hits than steps is an invalid array length, as in
 * Strudel.
 */
internal fun bjorklund(ons: Double, steps: Double): List<Int> {
    val inverted = ons < 0
    val absOns = abs(ons)
    var onCount = arrayLength(absOns)
    var offCount = arrayLength(steps - absOns)
    if (onCount.toLong() + offCount > MAX_COUNT) throw tooManySteps(onCount.toDouble() + offCount)
    var xs: List<List<Int>> = List(onCount) { listOf(1) }
    var ys: List<List<Int>> = List(offCount) { listOf(0) }
    // `_bjorklund`: while both groups have more than one member, glue the shorter one onto the longer. Ties go right.
    while (minOf(onCount, offCount) > 1) {
        if (onCount > offCount) {
            // left: the first `offCount` groups of xs take one group of ys each, the others stay behind
            val joined = List(offCount) { i -> xs[i] + ys[i] }
            val rest = xs.subList(offCount, xs.size)
            val newOns = offCount
            offCount = onCount - offCount
            onCount = newOns
            xs = joined
            ys = rest
        } else {
            // right: every group of xs takes one of the first `onCount` groups of ys, the others stay behind
            val joined = List(onCount) { i -> xs[i] + ys[i] }
            val rest = ys.subList(onCount, ys.size)
            offCount -= onCount
            xs = joined
            ys = rest
        }
    }
    val pattern = ArrayList<Int>(xs.size + ys.size)
    for (group in xs) pattern.addAll(group)
    for (group in ys) pattern.addAll(group)
    return if (inverted) pattern.map { 1 - it } else pattern
}

/** `x === 0`: only the number is zero, not `"0"`, `false` or `[]`. */
private fun isNumberZero(x: Any?): Boolean = x is Number && x.toDouble() == 0.0

/** `_euclidRot`: the rhythm rotated to the right by [rotation] steps (a rotation of 0, NaN or nothing leaves it alone). */
private fun euclidRotBits(pulses: Double, steps: Double, rotation: Any?): List<Int> {
    val bits = bjorklund(pulses, steps)
    return if (jsTruthy(rotation)) jsRotate(bits, -jsToNumber(rotation)) else bits
}

/**
 * `pat.struct(structure)`, which is `pat.keepif.out(structure)`: the events come from [structure] (a pattern of booleans or
 * 0 and 1), where its value is truthy, with the values of this pattern. Its steps are those of [structure].
 */
private fun Pattern.structOut(structure: Pattern): Pattern =
    fmap { a -> jsFn(1) { args -> if (jsTruthy(args[0])) a else Undefined } }.appRight(structure).removeUndefineds()

/** A pattern that plays [bits] one after the other in a cycle. */
private fun bitsPattern(bits: List<Int>): Pattern = fastcat(bits.map { it.toDouble() })

// ---- euclid ------------------------------------------------------------------------------------------------------

/**
 * `_euclidLegato`: like `euclid` with every hit held until the next one, so that there are no gaps. [steps] is a
 * positive number of steps; the rhythm is rotated by [rotation] steps to the right on top of the way it is shifted so
 * that the first hit starts the pattern.
 */
private fun Pattern.euclidLegatoCore(pulses: Double, steps: Double, rotation: Any?): Pattern {
    var bits = bjorklund(pulses, steps)
    // The `gapless` computation below needs the first hit to be the first step.
    val firstOnset = bits.indexOf(1)
    bits = jsRotate(bits, firstOnset.toDouble())
    val gapless = bits.joinToString("").split("1").drop(1).map { listOf((it.length + 1).toDouble(), true) }
    val offset = Rational.of(jsToNumber(rotation) + firstOnset) / Rational.of(abs(steps))
    return structOut(stepcat(gapless)._late(offset))
}

/**
 * `_euclid(pulses, steps, rotation, pat)`: the events of this pattern on the hits of the Euclidean rhythm of [pulses]
 * hits over [steps] steps, rotated to the right by [rotation] steps. A negative number of pulses inverts the rhythm;
 * a negative number of steps makes it legato; more pulses than steps stretch the rhythm over several cycles, each
 * one as long as the number of cycles it takes to hold the hits; no steps or no pulses is silence.
 */
internal fun Pattern.euclidCore(pulses: Any?, steps: Any?, rotation: Any?): Pattern {
    if (isNumberZero(steps) || isNumberZero(pulses)) return silence
    val p = jsToNumber(pulses)
    val s = jsToNumber(steps)
    val cycles = ceil(abs(p) / abs(s))
    val totalSteps = cycles * s
    if (totalSteps < 0) return _fast(cycles).euclidLegatoCore(p, -totalSteps, rotation)._slow(cycles)
    return _fast(cycles).structOut(bitsPattern(euclidRotBits(p, totalSteps, rotation)))._slow(cycles)
}

internal fun Pattern.euclid(pulses: Any?, steps: Any?): Pattern = euclidCore(pulses, steps, 0.0)

internal fun Pattern.euclidRot(pulses: Any?, steps: Any?, rotation: Any?): Pattern = euclidCore(pulses, steps, rotation)

/** Like [euclid] with each hit held until the next one; the same as `euclid` with the steps negated. */
internal fun Pattern.euclidLegato(pulses: Any?, steps: Any?): Pattern = euclidCore(pulses, negate(steps), 0.0)

internal fun Pattern.euclidLegatoRot(pulses: Any?, steps: Any?, rotation: Any?): Pattern = euclidCore(pulses, negate(steps), rotation)

/** `-x` of JavaScript. */
private fun negate(x: Any?): Double = -jsToNumber(x)

/**
 * `bjork`: [euc] is `[pulses, steps, rotation]`; a lone value is the pulses, and steps default to the pulses, the
 * rotation to 0 (when they are left out, not when they are null).
 */
internal fun Pattern.bjork(euc: Any?): Pattern {
    val list = euc as? List<*> ?: listOf(euc)
    val pulses = list.getOrElse(0) { Undefined }
    val steps = list.getOrElse(1) { Undefined }.let { if (it === Undefined) pulses else it }
    val rotation = list.getOrElse(2) { Undefined }.let { if (it === Undefined) 0.0 else it }
    return euclidCore(pulses, steps, rotation)
}

// ---- euclidish ---------------------------------------------------------------------------------------------------

/**
 * `_morph(from, to, by)`: the events at the positions of the hits of [from] (a list of 0 and 1), moved a fraction [by]
 * of the way towards the positions of the hits of [to], each as long as a step of [from]. A hit that starts in a cycle
 * and ends in the next one is cut at the end of its cycle, but keeps its whole. The values are `true`.
 *
 * The distance between two positions is a difference of doubles (Strudel subtracts the fractions' numbers), which
 * [Rational.of] turns back into a fraction, so a distance such as 5/12 - 1/3 is not exact, as in Strudel.
 */
private fun euclidMorph(from: List<Int>, to: List<Int>, byRaw: Any?): Pattern {
    val by = Rational.of(byRaw)
    val duration = Rational.ONE / Rational(from.size.toLong())
    fun positions(list: List<Int>): List<Rational> =
        list.indices.filter { list[it] != 0 }.map { Rational(it.toLong()) / Rational(list.size.toLong()) }
    val toPositions = positions(to)
    val arcs = positions(from).mapIndexed { i, posA ->
        val posB = toPositions.getOrNull(i) ?: throw CosicError("undefined is not iterable (cannot read property Symbol(Symbol.iterator))")
        val begin = by * Rational.of(posB.toDouble() - posA.toDouble()) + posA
        TimeSpan(begin, begin + duration)
    }
    return Pattern { state ->
        val cycle = state.span.begin.sam()
        val cycleArc = state.span.cycleArc()
        val out = ArrayList<Hap>()
        for (whole in arcs) {
            val part = whole.intersection(cycleArc) ?: continue
            out.add(Hap(whole.withTime { it + cycle }, part.withTime { it + cycle }, true))
        }
        out
    }.splitQueries()
}

/**
 * A `euclid` variant that morphs the rhythm from the Euclidean one ([perc] 0) towards an even one ([perc] 1): `sound("bd")
 * .euclidish(3, 8, 0)` is `euclid(3, 8)` and `euclidish(3, 8, 1)` is `"bd bd bd"`. It has [steps] steps.
 */
internal fun Pattern.euclidish(pulses: Any?, steps: Any?, perc: Any?): Pattern {
    val p = jsToNumber(pulses)
    val from = bjorklund(p, jsToNumber(steps))
    val to = List(arrayLength(p)) { 1 }
    return structOut(euclidMorph(from, to, perc)).setSteps(Rational.of(steps))
}
