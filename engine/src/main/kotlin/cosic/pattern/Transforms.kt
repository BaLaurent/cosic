package cosic.pattern

/**
 * Time and structure transformations. Operations that take a function (`inside`, `off`, ...) apply it
 * once, when the pattern is built; operations that only choose between ready-made patterns (`every`,
 * `chunk`, ...) take the already transformed pattern as `applied`.
 */

/** Chooses a pattern per cycle; the chosen pattern is queried with the original, unshifted time. */
internal fun <T> perCycle(choose: (Long) -> Pattern<T>): Pattern<T> =
    Pattern<T> { span -> choose(span.begin.floor()).query(span) }.splitQueries()

// ---- time -----------------------------------------------------------------------------

/**
 * Plays [factor] times faster; zero is silence and a negative factor also reverses. Throws [CosicError] when the
 * factor asks for more than [MAX_COUNT] events per cycle.
 */
internal fun <T> Pattern<T>.fast(factor: Rational): Pattern<T> {
    if (Math.abs(factor.toDouble()) > MAX_COUNT) throw CosicError("Too fast: at most $MAX_COUNT events per cycle")
    return when {
        factor.num == 0L -> silence
        factor.num < 0L -> fast(-factor).rev()
        factor == Rational.ONE -> this
        else -> withQueryTime { it * factor }.withHapTime { it / factor }
    }
}

internal fun <T> Pattern<T>.slow(factor: Rational): Pattern<T> =
    if (factor.num == 0L) silence else fast(Rational.ONE / factor)

internal fun <T> Pattern<T>.early(by: Rational): Pattern<T> =
    if (by.num == 0L) this else withQueryTime { it + by }.withHapTime { it - by }

internal fun <T> Pattern<T>.late(by: Rational): Pattern<T> = early(-by)

/** Reverses every cycle: each hap is mirrored around the middle of its cycle. */
internal fun <T> Pattern<T>.rev(): Pattern<T> = Pattern<T> { span ->
    val axis = Rational(2 * span.begin.floor() + 1)
    fun mirror(s: TimeSpan) = TimeSpan(axis - s.end, axis - s.begin)
    query(mirror(span)).map { it.withSpans(::mirror) }
}.splitQueries()

/** Shifts the pattern one step further to the left each cycle, wrapping after [n] cycles. */
internal fun <T> Pattern<T>.iter(n: Int): Pattern<T> = when {
    n == 0 -> this
    n < 0 -> iterBack(-n)
    else -> perCycle { c -> early(Rational(Math.floorMod(c, n.toLong()), n.toLong())) }
}

/** Like [iter], shifting to the right. */
internal fun <T> Pattern<T>.iterBack(n: Int): Pattern<T> = when {
    n == 0 -> this
    n < 0 -> iter(-n)
    else -> perCycle { c -> late(Rational(Math.floorMod(c, n.toLong()), n.toLong())) }
}

/** Splits every hap's whole into [n] equal repeats. */
internal fun <T> Pattern<T>.ply(n: Int): Pattern<T> = splitWholes(n) { value, _ -> value }

/**
 * Splits every hap's whole into [n] equal wholes, turning the value of piece `i` into `f(value, i)`.
 * A hap without a whole is split over its part.
 */
internal fun <T, R> Pattern<T>.splitWholes(n: Int, f: (T, Int) -> R): Pattern<R> {
    if (n <= 0) return silence
    val count = Rational(n.toLong())
    return Pattern { span ->
        val out = ArrayList<Hap<R>>()
        for (hap in query(span)) {
            val whole = hap.wholeOrPart()
            val width = (whole.end - whole.begin) / count
            if (width.num == 0L) {
                out.add(hap.mapValue { f(it, 0) })
                continue
            }
            val first = ((hap.part.begin - whole.begin) / width).floor().coerceIn(0L, n - 1L)
            val last = maxOf(first, (-((whole.begin - hap.part.end) / width).floor()) - 1).coerceIn(0L, n - 1L)
            chargeQuery(last - first + 1)
            for (i in first..last) {
                val piece = TimeSpan(whole.begin + width * Rational(i), whole.begin + width * Rational(i + 1))
                val part = hap.part.intersect(piece) ?: continue
                out.add(Hap(piece, part, f(hap.value, i.toInt()), hap.locations))
            }
        }
        out
    }
}

/** Applies [f] to the pattern slowed down by [n], then speeds the result back up. */
internal fun <T> Pattern<T>.inside(n: Rational, f: (Pattern<T>) -> Pattern<T>): Pattern<T> = f(slow(n)).fast(n)

/** Applies [f] to the pattern sped up by [n], then slows the result back down. */
internal fun <T> Pattern<T>.outside(n: Rational, f: (Pattern<T>) -> Pattern<T>): Pattern<T> = f(fast(n)).slow(n)

/**
 * Takes the haps of [applied] that start inside `[begin, end)` of their cycle, and the haps of this pattern
 * that start outside it.
 */
internal fun <T> Pattern<T>.within(begin: Rational, end: Rational, applied: Pattern<T>): Pattern<T> {
    fun inWindow(hap: Hap<*>): Boolean {
        val pos = hap.wholeOrPart().begin.cyclePos()
        return pos >= begin && pos < end
    }
    return stack(applied.filterHaps(::inWindow), filterHaps { !inWindow(it) })
}

/** Delays the events in the second half of each of [n] slots per cycle by [shift] of a slot. */
internal fun <T> Pattern<T>.swingBy(shift: Rational, n: Rational): Pattern<T> =
    inside(n) { slot -> slot.within(HALF, Rational.ONE, slot.late(shift)) }

/** Plays the pattern together with a copy delayed by [by] that [f] has transformed. */
internal fun <T> Pattern<T>.off(by: Rational, f: (Pattern<T>) -> Pattern<T>): Pattern<T> = stack(this, f(late(by)))

internal fun <T> Pattern<T>.superimpose(f: (Pattern<T>) -> Pattern<T>): Pattern<T> = stack(this, f(this))

private val HALF = Rational(1, 2)

// ---- structure ------------------------------------------------------------------------

/** Plays this pattern's values with the structure of the truthy haps of [structure]. */
internal fun <T> Pattern<T>.struct(structure: Pattern<Any>): Pattern<T> =
    structure.filterValues(::truthy).appLeft(this) { _, value -> value }

/** Keeps this pattern's structure, but silences the parts where [mask] is not truthy. */
internal fun <T> Pattern<T>.mask(mask: Pattern<Any>): Pattern<T> =
    appLeft(mask.filterValues(::truthy)) { value, _ -> value }

/** Samples this pattern [n] times per cycle. */
internal fun <T> Pattern<T>.segment(n: Rational): Pattern<T> = pure(true).fast(n).appLeft(this) { _, value -> value }

/** Applies [applied] on the first cycle of every [n] cycles. */
internal fun <T> Pattern<T>.every(n: Int, applied: Pattern<T>): Pattern<T> =
    if (n <= 0) this else perCycle { c -> if (Math.floorMod(c, n.toLong()) == 0L) applied else this }

/** Applies [applied] on the last cycle of every [n] cycles. */
internal fun <T> Pattern<T>.lastOf(n: Int, applied: Pattern<T>): Pattern<T> =
    if (n <= 0) this else perCycle { c -> if (Math.floorMod(c, n.toLong()) == n - 1L) applied else this }

/** Applies [applied] to one of [n] equal slices of the cycle, moving to the next slice each cycle. */
internal fun <T> Pattern<T>.chunk(n: Int, applied: Pattern<T>): Pattern<T> {
    if (n <= 0) return this
    return perCycle { c ->
        val slice = Math.floorMod(c, n.toLong())
        within(Rational(slice, n.toLong()), Rational(slice + 1, n.toLong()), applied)
    }
}
