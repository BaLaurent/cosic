package cosic.pattern

import cosic.SourceRange

/**
 * A pattern is a pure function from a span of time to the events ([Hap]s) happening in it. Nothing is
 * stored: every operation wraps another pattern's query, so patterns are cheap, immutable and thread-safe.
 *
 * Values are `Any`: `Double`, `String`, `Boolean` or a [ControlMap].
 */
internal class Pattern<out T>(val query: (TimeSpan) -> List<Hap<T>>) {

    fun <R> fmap(f: (T) -> R): Pattern<R> = Pattern { span -> query(span).map { it.mapValue(f) } }

    fun withQuerySpan(f: (TimeSpan) -> TimeSpan): Pattern<T> = Pattern { span -> query(f(span)) }

    fun withQueryTime(f: (Rational) -> Rational): Pattern<T> = withQuerySpan { it.withTime(f) }

    /** Maps the whole and the part of every hap. */
    fun withHapSpan(f: (TimeSpan) -> TimeSpan): Pattern<T> = Pattern { span -> query(span).map { it.withSpans(f) } }

    fun withHapTime(f: (Rational) -> Rational): Pattern<T> = withHapSpan { it.withTime(f) }

    /** Answers each cycle of a query separately, so the pattern may assume a query never crosses a cycle. */
    fun splitQueries(): Pattern<T> = Pattern { span ->
        val pieces = span.spanCycles()
        if (pieces.size == 1) query(pieces[0]) else pieces.flatMap { query(it) }
    }

    fun filterHaps(keep: (Hap<T>) -> Boolean): Pattern<T> = Pattern { span -> query(span).filter(keep) }

    fun filterValues(keep: (T) -> Boolean): Pattern<T> = filterHaps { keep(it.value) }

    /** Adds source [extra] locations to every hap, so the editor can highlight the code behind it. */
    fun withLocations(extra: List<SourceRange>): Pattern<T> =
        Pattern { span -> query(span).map { it.copy(locations = it.locations + extra) } }

    /**
     * Combines two patterns with the structure (wholes) coming from this one: for every hap here, the other
     * pattern is queried over that hap's whole, and each of its haps overlapping the part adds a combined hap.
     */
    fun <U, R> appLeft(other: Pattern<U>, f: (T, U) -> R): Pattern<R> = Pattern { span ->
        val out = ArrayList<Hap<R>>()
        for (a in query(span)) {
            for (b in other.query(a.wholeOrPart())) {
                val part = a.part.intersect(b.part) ?: continue
                out.add(Hap(a.whole, part, f(a.value, b.value), concat(a.locations, b.locations)))
            }
        }
        out
    }
}

/**
 * Flattens a pattern of patterns: every outer hap's pattern is queried over the outer hap's part, and the
 * inner haps keep their own wholes. Locations are the inner ones followed by the outer ones.
 */
internal fun <T> Pattern<Pattern<T>>.innerJoin(): Pattern<T> = Pattern { span ->
    val out = ArrayList<Hap<T>>()
    for (outer in query(span)) {
        for (inner in outer.value.query(outer.part)) {
            val part = inner.part.intersect(outer.part)?.intersect(span) ?: continue
            out.add(Hap(inner.whole, part, inner.value, concat(inner.locations, outer.locations)))
        }
    }
    out
}

private fun concat(a: List<SourceRange>, b: List<SourceRange>): List<SourceRange> = when {
    b.isEmpty() -> a
    a.isEmpty() -> b
    else -> a + b
}

// ---- constructors ---------------------------------------------------------------------

internal val silence: Pattern<Nothing> = Pattern { emptyList() }

/** One event per cycle, lasting the whole cycle. */
internal fun <T> pure(value: T): Pattern<T> = Pattern { span ->
    span.spanCycles().map { piece ->
        val cycle = piece.begin.floor()
        Hap(TimeSpan(Rational(cycle), Rational(cycle + 1)), piece, value)
    }
}

internal fun <T> stack(pats: List<Pattern<T>>): Pattern<T> = when (pats.size) {
    0 -> silence
    1 -> pats[0]
    else -> Pattern { span -> pats.flatMap { it.query(span) } }
}

internal fun <T> stack(vararg pats: Pattern<T>): Pattern<T> = stack(pats.toList())

/**
 * One pattern per cycle, in turn. Each pattern counts its own cycles: it sees cycle 0 the first time it is
 * played, cycle 1 the next time, and so on.
 */
internal fun <T> slowcat(pats: List<Pattern<T>>): Pattern<T> {
    if (pats.isEmpty()) return silence
    if (pats.size == 1) return pats[0]
    val n = pats.size.toLong()
    return Pattern<T> { span ->
        val cycle = span.begin.floor()
        val offset = Rational(cycle - Math.floorDiv(cycle, n))
        val chosen = pats[Math.floorMod(cycle, n).toInt()]
        chosen.query(span.withTime { it - offset }).map { hap -> hap.withSpans { s -> s.withTime { t -> t + offset } } }
    }.splitQueries()
}

internal fun <T> slowcat(vararg pats: Pattern<T>): Pattern<T> = slowcat(pats.toList())

/** All patterns squeezed into one cycle, one after the other. */
internal fun <T> fastcat(pats: List<Pattern<T>>): Pattern<T> = when (pats.size) {
    0 -> silence
    1 -> pats[0]
    else -> slowcat(pats).fast(Rational(pats.size.toLong()))
}

internal fun <T> fastcat(vararg pats: Pattern<T>): Pattern<T> = fastcat(pats.toList())

/** Like [fastcat], but each step takes a share of the cycle proportional to its weight. */
internal fun <T> timecat(steps: List<Pair<Rational, Pattern<T>>>): Pattern<T> {
    val total = steps.fold(Rational.ZERO) { sum, step -> sum + step.first }
    if (total.num <= 0) return silence
    var begin = Rational.ZERO
    return stack(
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
internal fun <T> Pattern<T>.compress(begin: Rational, end: Rational): Pattern<T> {
    if (begin >= end || begin < Rational.ZERO || end > Rational.ONE) return silence
    val width = end - begin
    return Pattern<T> { span ->
        val cycle = Rational(span.begin.floor())
        val slice = TimeSpan(cycle + begin, cycle + end)
        val inside = span.intersect(slice)
        if (inside == null) {
            emptyList()
        } else {
            val inner = inside.withTime { cycle + (it - slice.begin) / width }
            query(inner).mapNotNull { hap ->
                val outer = hap.withSpans { s -> s.withTime { t -> slice.begin + (t - cycle) * width } }
                val part = outer.part.intersect(slice)
                if (part == null) null else Hap(outer.whole, part, outer.value, outer.locations)
            }
        }
    }.splitQueries()
}
