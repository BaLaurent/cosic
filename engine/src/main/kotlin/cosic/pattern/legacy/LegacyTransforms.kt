package cosic.pattern.legacy

import cosic.pattern.*

/**
 * Time and structure transformations. Operations that take a function (`inside`, `off`, ...) apply it
 * once, when the pattern is built; operations that only choose between ready-made patterns (`every`,
 * `chunk`, ...) take the already transformed pattern as `applied`.
 */

/** Chooses a pattern per cycle; the chosen pattern is queried with the original, unshifted time. */
internal fun perCycle(choose: (Long) -> Pattern): Pattern =
    Pattern { state -> choose(state.span.begin.floor()).query(state) }.splitQueries()

/**
 * Plays all [pats] at the same time. Unlike Strudel's `stack`, an error while querying one of them ends the query, which is
 * what the language before Strudel's reports to the user.
 */
internal fun legacyStack(pats: List<Pattern>): Pattern = when (pats.size) {
    0 -> silence
    1 -> pats[0]
    else -> Pattern { state -> pats.flatMap { it.query(state) } }
}

internal fun legacyStack(vararg pats: Pattern): Pattern = legacyStack(pats.toList())

// ---- time -----------------------------------------------------------------------------

/**
 * Plays [factor] times faster; zero is silence and a negative factor also reverses. Throws [CosicError] when the
 * factor asks for more than [MAX_COUNT] events per cycle.
 */
internal fun Pattern.fast(factor: Rational): Pattern {
    if (Math.abs(factor.toDouble()) > MAX_COUNT) throw CosicError("Too fast: at most $MAX_COUNT events per cycle")
    return when {
        factor.isZero() -> silence
        factor.signum() < 0 -> fast(-factor).rev()
        factor == Rational.ONE -> this
        else -> withQueryTime { it * factor }.withHapTime { it / factor }
    }
}

internal fun Pattern.slow(factor: Rational): Pattern =
    if (factor.isZero()) silence else fast(Rational.ONE / factor)

internal fun Pattern.early(by: Rational): Pattern =
    if (by.isZero()) this else withQueryTime { it + by }.withHapTime { it - by }

internal fun Pattern.late(by: Rational): Pattern = early(-by)

/** Reverses every cycle: each hap is mirrored around the middle of its cycle. */
internal fun Pattern.rev(): Pattern = Pattern { state ->
    val axis = Rational(2 * state.span.begin.floor() + 1)
    fun mirror(s: TimeSpan) = TimeSpan(axis - s.end, axis - s.begin)
    query(state.setSpan(mirror(state.span))).map { it.withSpan(::mirror) }
}.splitQueries()

/** Shifts the pattern one step further to the left each cycle, wrapping after [n] cycles. */
internal fun Pattern.iter(n: Int): Pattern = when {
    n == 0 -> this
    n < 0 -> iterBack(-n)
    else -> perCycle { c -> early(Rational(Math.floorMod(c, n.toLong()), n.toLong())) }
}

/** Like [iter], shifting to the right. */
internal fun Pattern.iterBack(n: Int): Pattern = when {
    n == 0 -> this
    n < 0 -> iter(-n)
    else -> perCycle { c -> late(Rational(Math.floorMod(c, n.toLong()), n.toLong())) }
}

/** Splits every hap's whole into [n] equal repeats. */
internal fun Pattern.ply(n: Int): Pattern = splitWholes(n) { value, _ -> value }

/**
 * Splits every hap's whole into [n] equal wholes, turning the value of piece `i` into `f(value, i)`.
 * A hap without a whole is split over its part.
 */
internal fun Pattern.splitWholes(n: Int, f: (Any?, Int) -> Any?): Pattern {
    if (n <= 0) return silence
    val count = Rational(n.toLong())
    return Pattern { state ->
        val out = ArrayList<Hap>()
        for (hap in query(state)) {
            val whole = hap.wholeOrPart()
            val width = (whole.end - whole.begin) / count
            if (width.isZero()) {
                out.add(hap.withValue { f(it, 0) })
                continue
            }
            val first = ((hap.part.begin - whole.begin) / width).floor().coerceIn(0L, n - 1L)
            val last = maxOf(first, (-((whole.begin - hap.part.end) / width).floor()) - 1).coerceIn(0L, n - 1L)
            chargeQuery(last - first + 1)
            for (i in first..last) {
                val piece = TimeSpan(whole.begin + width * Rational(i), whole.begin + width * Rational(i + 1))
                val part = hap.part.intersection(piece) ?: continue
                out.add(Hap(piece, part, f(hap.value, i.toInt()), hap.context))
            }
        }
        out
    }
}

/** Applies [f] to the pattern slowed down by [n], then speeds the result back up. */
internal fun Pattern.inside(n: Rational, f: (Pattern) -> Pattern): Pattern = f(slow(n)).fast(n)

/** Applies [f] to the pattern sped up by [n], then slows the result back down. */
internal fun Pattern.outside(n: Rational, f: (Pattern) -> Pattern): Pattern = f(fast(n)).slow(n)

/**
 * Takes the haps of [applied] that start inside `[begin, end)` of their cycle, and the haps of this pattern
 * that start outside it.
 */
internal fun Pattern.within(begin: Rational, end: Rational, applied: Pattern): Pattern {
    fun inWindow(hap: Hap): Boolean {
        val pos = hap.wholeOrPart().begin.cyclePos()
        return pos >= begin && pos < end
    }
    return legacyStack(applied.filterHaps(::inWindow), filterHaps { !inWindow(it) })
}

/** Delays the events in the second half of each of [n] slots per cycle by [shift] of a slot. */
internal fun Pattern.swingBy(shift: Rational, n: Rational): Pattern =
    inside(n) { slot -> slot.within(HALF, Rational.ONE, slot.late(shift)) }

/** Plays the pattern together with a copy delayed by [by] that [f] has transformed. */
internal fun Pattern.off(by: Rational, f: (Pattern) -> Pattern): Pattern = legacyStack(this, f(late(by)))

internal fun Pattern.superimpose(f: (Pattern) -> Pattern): Pattern = legacyStack(this, f(this))

private val HALF = Rational(1, 2)

// ---- structure ------------------------------------------------------------------------

/** Plays this pattern's values with the structure of the truthy haps of [structure]. */
internal fun Pattern.struct(structure: Pattern): Pattern =
    structure.filterValues(::jsTruthy).zipLeft(this) { _, value -> value }

/** Keeps this pattern's structure, but silences the parts where [mask] is not truthy. */
internal fun Pattern.mask(mask: Pattern): Pattern =
    zipLeft(mask.filterValues(::jsTruthy)) { value, _ -> value }

/** Samples this pattern [n] times per cycle. */
internal fun Pattern.segment(n: Rational): Pattern = pure(true).fast(n).zipLeft(this) { _, value -> value }

/** Applies [applied] on the first cycle of every [n] cycles. */
internal fun Pattern.every(n: Int, applied: Pattern): Pattern =
    if (n <= 0) this else perCycle { c -> if (Math.floorMod(c, n.toLong()) == 0L) applied else this }

/** Applies [applied] on the last cycle of every [n] cycles. */
internal fun Pattern.lastOf(n: Int, applied: Pattern): Pattern =
    if (n <= 0) this else perCycle { c -> if (Math.floorMod(c, n.toLong()) == n - 1L) applied else this }

/** Applies [applied] to one of [n] equal slices of the cycle, moving to the next slice each cycle. */
internal fun Pattern.chunk(n: Int, applied: Pattern): Pattern {
    if (n <= 0) return this
    return perCycle { c ->
        val slice = Math.floorMod(c, n.toLong())
        within(Rational(slice, n.toLong()), Rational(slice + 1, n.toLong()), applied)
    }
}

// ---- constructors the old mini-notation and `arrange` are built on ------------------------------------------

/** Like `fastcat`, but each step takes a share of the cycle proportional to its weight. */
internal fun timecat(steps: List<Pair<Rational, Pattern>>): Pattern {
    val total = steps.fold(Rational.ZERO) { sum, step -> sum + step.first }
    if (total.signum() <= 0) return silence
    var begin = Rational.ZERO
    return legacyStack(
        steps.map { (weight, pat) ->
            val end = begin + weight
            val slice = pat.compress(begin / total, end / total)
            begin = end
            slice
        },
    )
}

/**
 * Squeezes each cycle of this pattern into `[begin, end)` of the cycle, leaving the rest silent. Parts are
 * clipped to the slice; wholes may stick out of it.
 */
internal fun Pattern.compress(begin: Rational, end: Rational): Pattern {
    if (begin >= end || begin < Rational.ZERO || end > Rational.ONE) return silence
    val width = end - begin
    return Pattern { state ->
        val cycle = Rational(state.span.begin.floor())
        val slice = TimeSpan(cycle + begin, cycle + end)
        val inside = state.span.intersection(slice)
        if (inside == null) {
            emptyList()
        } else {
            val inner = inside.withTime { cycle + (it - slice.begin) / width }
            query(state.setSpan(inner)).mapNotNull { hap ->
                val outer = hap.withSpan { s -> s.withTime { t -> slice.begin + (t - cycle) * width } }
                val part = outer.part.intersection(slice)
                if (part == null) null else Hap(outer.whole, part, outer.value, outer.context)
            }
        }
    }.splitQueries()
}
