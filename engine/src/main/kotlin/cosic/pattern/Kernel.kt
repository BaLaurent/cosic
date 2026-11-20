package cosic.pattern

/*
 * The kernel functions of Strudel's pattern.mjs that the rest of the pattern library stands on: the time functions
 * (`fast`, `slow`, `early`, `late`, `compress`, `fastGap`, `focus`), the step functions (`expand`, `extend`, `stepcat`)
 * and the helpers `stepJoin` uses. Their arguments are "raw" (a number, a fraction, a string...), exactly what `func`
 * of `register` gets; the `_name` forms are the plain methods `pat._fast(2)`. The catalogue entries that make them
 * callable from programs are in script/catalogue/Kernel.kt.
 */

/**
 * The pattern played [factor] times faster. A number 0 is silence; a negative factor is not handled (a query then
 * ends before it begins, so the pattern has no haps). The steps stay those of the pattern.
 */
internal fun Pattern._fast(factor: Any?): Pattern {
    if (factor is Number && factor.toDouble() == 0.0) return silence.setSteps(steps)
    val f = Rational.of(factor)
    return withQueryTime { it * f }.withHapTime { it / f }.setSteps(steps)
}

/** The pattern played [factor] times slower; a number 0 is silence. */
internal fun Pattern._slow(factor: Any?): Pattern {
    if (factor is Number && factor.toDouble() == 0.0) return silence
    return _fast(Rational.ONE / Rational.of(factor))
}

/** The pattern shifted [offset] cycles earlier. The steps stay those of the pattern. */
internal fun Pattern._early(offset: Any?): Pattern {
    val o = Rational.of(offset)
    return withQueryTime { it + o }.withHapTime { it - o }.setSteps(steps)
}

/** The pattern shifted [offset] cycles later. The steps stay those of the pattern. */
internal fun Pattern._late(offset: Any?): Pattern = _early(Rational.ZERO - Rational.of(offset)).setSteps(steps)

/**
 * Squeezes each cycle of the pattern into the part from [b] to [e] of the cycle, leaving a gap around it: silence unless
 * `0 <= b <= e <= 1`. Equal [b] and [e] is a `Division by Zero`.
 */
internal fun Pattern._compress(b: Any?, e: Any?): Pattern {
    val begin = Rational.of(b)
    val end = Rational.of(e)
    if (begin > end || begin > Rational.ONE || end > Rational.ONE || begin < Rational.ZERO || end < Rational.ZERO) return silence
    return _fastGap(Rational.ONE / (end - begin))._late(begin)
}

internal fun Pattern._compressSpan(span: TimeSpan): Pattern = _compress(span.begin, span.end)

/**
 * Plays each cycle [factor] times faster, but only once: the rest of the cycle is a gap. Events that would run into the
 * next cycle are cut off at its start and keep their whole.
 */
internal fun Pattern._fastGap(factor: Any?): Pattern {
    // The factor is read at query time, like the arithmetic in Strudel is.
    val f by lazy(LazyThreadSafetyMode.PUBLICATION) { Rational.of(factor) }
    fun min1(x: Rational): Rational = if (x < Rational.ONE) x else Rational.ONE
    val queryFor = { span: TimeSpan ->
        val cycle = span.begin.sam()
        val bpos = min1((span.begin - cycle) * f)
        val epos = min1((span.end - cycle) * f)
        // Strudel compares `bpos >= 1` as numbers (a Fraction becomes its double), to drop zero-width queries at the start of the next cycle.
        if (bpos.toDouble() >= 1.0) null else TimeSpan(cycle + bpos, cycle + epos)
    }
    val moveHap = { hap: Hap ->
        val begin = hap.part.begin
        val end = hap.part.end
        val cycle = begin.sam()
        val beginPos = min1((begin - cycle) / f)
        val endPos = min1((end - cycle) / f)
        val newPart = TimeSpan(cycle + beginPos, cycle + endPos)
        val newWhole = hap.whole?.let { w -> TimeSpan(newPart.begin - (begin - w.begin) / f, newPart.end + (w.end - end) / f) }
        Hap(newWhole, newPart, hap.value, hap.context)
    }
    return withQuerySpanMaybe(queryFor).withHap(moveHap).splitQueries()
}

/**
 * Like [_compress], but the cycle is stretched over the span from [b] to [e] without cutting it off or leaving a gap
 * (the pattern outside that span still plays, around it): `pat._early(b.sam())._fast(1 / (e - b))._late(b)`.
 */
internal fun Pattern._focus(b: Any?, e: Any?): Pattern {
    val begin = Rational.of(b)
    val end = Rational.of(e)
    return _early(begin.sam())._fast(Rational.ONE / (end - begin))._late(begin)
}

internal fun Pattern._focusSpan(span: TimeSpan): Pattern = _focus(span.begin, span.end)

/** The same pattern with its steps multiplied by [factor]; a pattern without steps stays without. */
internal fun Pattern._expand(factor: Any?): Pattern = withSteps { it * Rational.of(factor) }

/** The pattern played [factor] times faster and expanded by [factor], so that it takes up [factor] times the steps. */
internal fun Pattern._extend(factor: Any?): Pattern = _fast(factor)._expand(factor)

// ---- stepcat -----------------------------------------------------------------------------------------------------

private fun isUndefinedTime(x: Any?): Boolean = x == null || x === Undefined

/**
 * Plays the patterns one after the other in one cycle, each for a time proportional to its steps. An entry is a
 * pattern (taking up its steps, or 1 when it has none) or a list `[time, pattern]`. Entries whose time is undefined
 * take the average of the others; a lone entry becomes the pattern with that many steps; entries of time 0 are left out.
 */
internal fun stepcat(timepats: List<Any?>): Pattern {
    if (timepats.isEmpty()) return nothing
    val items = timepats.map { x ->
        if (x is List<*>) x.toMutableList<Any?>() else mutableListOf((x as? Pattern)?.steps ?: Rational.ONE, x)
    }
    fun time(item: List<Any?>): Any? = item.getOrElse(0) { Undefined }
    fun pat(item: List<Any?>): Any? = item.getOrElse(1) { Undefined }
    var times = items.map { time(it) }
    if (times.any(::isUndefinedTime)) {
        val known = times.filterNot(::isUndefinedTime)
        if (known.isEmpty()) return fastcat(items.map { pat(it) })
        val average = known.fold(Rational.ZERO) { a, b -> a + Rational.of(b) } / Rational(known.size.toLong())
        times = times.map { if (isUndefinedTime(it)) average else it }
    }
    if (items.size == 1) {
        val result = reify(pat(items[0]))
        return result.withSteps { Rational.of(times[0]) }
    }
    val total = times.fold(Rational.ZERO) { a, b -> a + Rational.of(b) }
    var begin = Rational.ZERO
    val pats = ArrayList<Pattern>()
    for ((i, item) in items.withIndex()) {
        val t = Rational.of(times[i])
        if (t.isZero()) continue
        val end = begin + t
        pats.add(reify(pat(item))._compress(begin / total, end / total))
        begin = end
    }
    return stack(pats).setSteps(total)
}

/**
 * `_retime` of Strudel as it effectively works (its callbacks mistake indices for patterns, so the totals it computes are
 * always undefined): every `[time, pattern]` becomes `[pattern's steps, pattern]`, with an undefined time for a pattern
 * that has no steps.
 */
internal fun _retime(timedHaps: List<List<Any?>>): List<List<Any?>> = timedHaps.map { x ->
    val pat = x[1] as Pattern
    listOf(pat.steps ?: Undefined, pat)
}

/**
 * Cuts the first cycle into slices at every begin and end of a part of [haps] (whose values are patterns): each
 * `[duration, pattern]` is the stack of the patterns of the haps that cover the slice, with the context of those haps added.
 */
internal fun _slices(haps: List<Hap>): List<List<Any?>> {
    val breakpoints = haps.flatMap { listOf(it.part.begin, it.part.end) }
    val unique = (listOf(Rational.ZERO, Rational.ONE) + breakpoints).distinct().sorted()
    return unique.zipWithNext { s0, s1 ->
        val covering = _fitslice(TimeSpan(s0, s1), haps).map { x ->
            patternOf(x.value).withHap { h -> h.setContext(h.combineContext(x)) }
        }
        listOf(s1 - s0, stack(covering))
    }
}

/** The haps of [haps] that overlap [span], cut to it. */
internal fun _fitslice(span: TimeSpan, haps: List<Hap>): List<Hap> = haps.mapNotNull { _match(span, it) }

internal fun _match(span: TimeSpan, hap: Hap): Hap? {
    val sub = span.intersection(hap.part) ?: return null
    return Hap(hap.whole, sub, hap.value, hap.context)
}
