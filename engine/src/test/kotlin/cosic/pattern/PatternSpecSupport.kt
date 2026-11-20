package cosic.pattern

internal fun r(num: Long, den: Long = 1): Rational = Rational(num, den)

internal fun span(begin: Rational, end: Rational): TimeSpan = TimeSpan(begin, end)

internal fun cycleSpan(cycle: Long): TimeSpan = TimeSpan(Rational(cycle), Rational(cycle + 1))

/** The haps in [span], asked without controls. */
internal fun Pattern.query(span: TimeSpan): List<Hap> = query(State(span))

/** A fraction as the legacy tests write it: a whole number as it is (`0`, `3`), anything else as `n/d`. */
internal fun Rational.compact(): String = if (isInteger()) "${floor()}" else show()

/** `value@wholeBegin-wholeEnd`, with `|partBegin-partEnd` appended when the part is smaller than the whole. */
internal fun Hap.plain(): String {
    val w = whole?.let { "${it.begin.compact()}-${it.end.compact()}" } ?: "~"
    return if (whole != null && whole == part) "$value@$w" else "$value@$w|${part.begin.compact()}-${part.end.compact()}"
}

/** Haps of [span] in a deterministic order (part begin, then value text), rendered with [plain]. */
internal fun Pattern.shown(span: TimeSpan): List<String> =
    query(span).sortedWith(compareBy<Hap>({ it.part.begin }, { it.part.end }, { it.value.toString() })).map { it.plain() }

/** Haps of one whole cycle, rendered with [plain]. */
internal fun Pattern.shownCycle(cycle: Long = 0): List<String> = shown(cycleSpan(cycle))

/** Values of the haps of one cycle, in time order. */
internal fun Pattern.cycleValues(cycle: Long = 0): List<Any?> =
    query(cycleSpan(cycle)).sortedWith(compareBy<Hap>({ it.part.begin }, { it.value.toString() })).map { it.value }

/** What Strudel's snapshot tests print: `queryArc` of the pattern sorted by part, every hap as `show(true)`. */
internal fun Pattern.lines(begin: Long, end: Long): List<String> =
    sortHapsByPart().queryArc(Rational(begin), Rational(end)).map { it.show(true) }

/** [lines] of the first cycle. */
internal fun Pattern.firstLines(): List<String> = lines(0, 1)

/** A function of one argument for the places where a hap holds a function. */
internal fun fn1(body: (Any?) -> Any?): JsFn = jsFn(1) { body(it[0]) }
