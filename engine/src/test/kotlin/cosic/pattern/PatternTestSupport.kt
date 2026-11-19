package cosic.pattern

internal fun r(num: Long, den: Long = 1): Rational = Rational(num, den)

internal fun span(begin: Rational, end: Rational): TimeSpan = TimeSpan(begin, end)

internal fun cycleSpan(cycle: Long): TimeSpan = TimeSpan(Rational(cycle), Rational(cycle + 1))

/** `value@wholeBegin-wholeEnd`, with `|partBegin-partEnd` appended when the part is smaller than the whole. */
internal fun Hap<*>.show(): String {
    val w = whole?.let { "${it.begin}-${it.end}" } ?: "~"
    return if (whole != null && whole == part) "$value@$w" else "$value@$w|${part.begin}-${part.end}"
}

/** Haps of [span] in a deterministic order (part begin, then value text), rendered with [show]. */
internal fun <T> Pattern<T>.shown(span: TimeSpan): List<String> =
    query(span).sortedWith(compareBy<Hap<T>>({ it.part.begin }, { it.part.end }, { it.value.toString() })).map { it.show() }

/** Haps of one whole cycle, rendered with [show]. */
internal fun <T> Pattern<T>.shownCycle(cycle: Long = 0): List<String> = shown(cycleSpan(cycle))

/** Values of the haps of one cycle, in time order. */
internal fun <T> Pattern<T>.cycleValues(cycle: Long = 0): List<T> =
    query(cycleSpan(cycle)).sortedWith(compareBy<Hap<T>>({ it.part.begin }, { it.value.toString() })).map { it.value }
