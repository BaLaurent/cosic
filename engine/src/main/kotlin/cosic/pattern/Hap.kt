package cosic.pattern

import cosic.SourceRange

/** A half-open stretch of time `[begin, end)` in cycles. Zero-width spans (`begin == end`) are allowed. */
internal data class TimeSpan(val begin: Rational, val end: Rational) {

    /** Splits the span at every integer cycle boundary. A zero-width span is returned as is. */
    fun spanCycles(): List<TimeSpan> {
        if (begin >= end) return listOf(this)
        var next = Rational(begin.floor() + 1)
        if (next >= end) return listOf(this)
        val pieces = ArrayList<TimeSpan>()
        var from = begin
        while (next < end) {
            pieces.add(TimeSpan(from, next))
            from = next
            next = Rational(from.floor() + 1)
        }
        pieces.add(TimeSpan(from, end))
        return pieces
    }

    /**
     * The overlap of the two spans, or null when they do not overlap. A span that only touches the
     * other at the end of a non-zero-width span does not count; a zero-width span counts when it lies
     * inside the other one.
     */
    fun intersect(other: TimeSpan): TimeSpan? {
        val b = maxOf(begin, other.begin)
        val e = minOf(end, other.end)
        if (b > e) return null
        if (b == e) {
            if (b == end && begin < end) return null
            if (b == other.end && other.begin < other.end) return null
        }
        return TimeSpan(b, e)
    }

    fun withTime(f: (Rational) -> Rational): TimeSpan = TimeSpan(f(begin), f(end))

    fun midpoint(): Rational = (begin + end) / TWO

    private companion object {
        val TWO = Rational(2)
    }
}

/**
 * One event of a pattern. [whole] is the full extent of the event (null for continuous signals) and
 * [part] the fragment of it that was asked for; the event has an onset only when the part starts where
 * the whole does. [locations] are the source ranges of the code that produced it, used for highlighting.
 */
internal data class Hap<out T>(
    val whole: TimeSpan?,
    val part: TimeSpan,
    val value: T,
    val locations: List<SourceRange> = emptyList(),
) {
    fun hasOnset(): Boolean = whole != null && whole.begin == part.begin

    fun wholeOrPart(): TimeSpan = whole ?: part

    fun <R> mapValue(f: (T) -> R): Hap<R> = Hap(whole, part, f(value), locations)

    /** Applies [f] to the whole and to the part. */
    fun withSpans(f: (TimeSpan) -> TimeSpan): Hap<T> = Hap(whole?.let(f), f(part), value, locations)
}
