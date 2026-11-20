package cosic.pattern

import cosic.SourceRange

/**
 * `calculateSteps(x)` of Strudel: whether patterns keep track of their number of steps per cycle. Off, `withSteps` does
 * nothing and `stack`, `slowcat` and the like leave the steps of their result undefined.
 */
internal object Steps {
    @Volatile
    var enabled: Boolean = true
}

/** Whether the code running on this thread is a query made by the scheduler (Strudel's `state.controls.cyclist`). */
internal object QueryContext {
    private val flag = object : ThreadLocal<Boolean>() {
        override fun initialValue(): Boolean = false
    }

    val fromScheduler: Boolean get() = flag.get()

    /** Runs [block], which queries patterns the way the scheduler does, so that `fromScheduler` is true inside it. */
    fun <T> asScheduler(block: () -> T): T {
        val outer = flag.get()
        flag.set(true)
        try {
            return block()
        } finally {
            flag.set(outer)
        }
    }
}

/** Where a query that failed in one branch of a `stack` reports it (`errorLogger` of logger.mjs). */
@Volatile
internal var errorLogger: (Throwable) -> Unit = {}

/** `logger(message, type, data)`: what `log()`, `print()` and warnings write to the console. */
@Volatile
internal var logger: (message: String, type: String?, data: Any?) -> Unit = { _, _, _ -> }

/**
 * If set, `reify` turns every string into a pattern with it (the mini-notation parser, in Strudel `setStringParser`).
 * Strings stay strings while it is null.
 */
@Volatile
internal var stringParser: ((String) -> Pattern)? = null

/**
 * A pattern is a function from a [State] (a span of time) to the events ([Hap]s) happening in it, `Pattern` of
 * `pattern.mjs`. Nothing is stored: every operation wraps another pattern's query, so patterns are cheap, immutable
 * and thread-safe. Hap values are untyped (see [Hap]).
 *
 * [steps] is the number of steps per cycle, when it is known. A pattern made by [pure] also remembers the [pureValue]
 * it repeats (and the source [pureLoc] it was written at), which `register` uses to call a function with a plain
 * value instead of a pattern. `null` there means the pattern is not a constant one.
 */
internal class Pattern internal constructor(
    val query: (State) -> List<Hap>,
    val steps: Rational?,
    val pureValue: Any?,
    val pureLoc: SourceRange?,
) {
    constructor(query: (State) -> List<Hap>) : this(query, null, null, null)

    constructor(query: (State) -> List<Hap>, steps: Rational?) : this(query, steps, null, null)

    val hasSteps: Boolean get() = steps != null

    /** The same pattern with the number of steps [s], which is also how the pattern is told it has none (null). */
    fun setSteps(s: Rational?): Pattern = Pattern(query, s, pureValue, pureLoc)

    /** Applies [f] to the steps, if there are any and steps are being tracked. The result is a new pattern. */
    fun withSteps(f: (Rational) -> Rational): Pattern {
        if (!Steps.enabled) return this
        return Pattern(query, steps?.let(f))
    }

    // ---- functor, applicative and monad ----------------------------------------------------------------------------

    /** Applies [f] to the value of every hap. */
    fun withValue(f: (Any?) -> Any?): Pattern = Pattern({ state -> query(state).map { it.withValue(f) } }, steps)

    fun fmap(f: (Any?) -> Any?): Pattern = withValue(f)

    fun withState(f: (State) -> State): Pattern = Pattern { state -> query(f(state)) }

    /**
     * For a pattern of functions: applies the functions to the values of [other] where the parts of their haps
     * overlap, with the wholes chosen by [wholeFunc]. A value that is not a function is an error.
     */
    fun appWhole(wholeFunc: (TimeSpan?, TimeSpan?) -> TimeSpan?, other: Pattern): Pattern = Pattern { state ->
        val funcs = query(state)
        val vals = other.query(state)
        chargeQuery(funcs.size.toLong() * vals.size)
        val out = ArrayList<Hap>()
        for (hf in funcs) {
            for (hv in vals) {
                val s = hf.part.intersection(hv.part) ?: continue
                out.add(Hap(wholeFunc(hf.whole, hv.whole), s, callFn(hf.value, hv.value), hv.combineContext(hf)))
            }
        }
        out
    }

    /** The structure comes from both sides: wholes are intersected. */
    fun appBoth(other: Pattern): Pattern {
        val result = appWhole({ a, b -> if (a == null || b == null) null else a.intersectionE(b) }, other)
        return if (Steps.enabled) result.setSteps(Rational.lcmOf(listOf(other.steps, steps))) else result
    }

    /** The structure comes from this pattern: [other] is queried over the whole of each of its haps. */
    fun appLeft(other: Pattern): Pattern = Pattern({ state ->
        val out = ArrayList<Hap>()
        for (hf in query(state)) {
            val vals = other.query(state.setSpan(hf.wholeOrPart()))
            for (hv in vals) {
                val part = hf.part.intersection(hv.part) ?: continue
                out.add(Hap(hf.whole, part, callFn(hf.value, hv.value), hv.combineContext(hf)))
            }
        }
        out
    }, steps)

    /** The structure comes from [other]. */
    fun appRight(other: Pattern): Pattern = Pattern({ state ->
        val out = ArrayList<Hap>()
        for (hv in other.query(state)) {
            val funcs = query(state.setSpan(hv.wholeOrPart()))
            for (hf in funcs) {
                val part = hf.part.intersection(hv.part) ?: continue
                out.add(Hap(hv.whole, part, callFn(hf.value, hv.value), hv.combineContext(hf)))
            }
        }
        out
    }, other.steps)

    /** [appLeft] for plain two-argument functions instead of a pattern of functions. */
    fun zipLeft(other: Pattern, f: (Any?, Any?) -> Any?): Pattern =
        fmap { a -> jsFn(1) { args -> f(a, args[0]) } }.appLeft(other)

    /**
     * Calls [func] on the value of every hap and plays the pattern it returns over the part of that hap. The wholes
     * are chosen by [choose], from the whole of the outer hap and the one of the inner hap; the parts of the inner
     * haps are not clipped.
     */
    fun bindWhole(choose: (TimeSpan?, TimeSpan?) -> TimeSpan?, func: (Any?) -> Pattern): Pattern = Pattern { state ->
        val out = ArrayList<Hap>()
        for (a in query(state)) {
            for (b in func(a.value).query(state.setSpan(a.part))) {
                out.add(Hap(choose(a.whole, b.whole), b.part, b.value, a.context.combine(b.context)))
            }
        }
        out
    }

    fun bind(f: (Any?) -> Pattern): Pattern =
        bindWhole({ a, b -> if (a == null || b == null) null else a.intersectionE(b) }, f)

    /** Flattens a pattern of patterns, the wholes being the intersections of the outer and inner ones. */
    fun join(): Pattern = bind(::patternOf)

    /** The wholes come from the outer pattern. */
    fun outerBind(f: (Any?) -> Pattern): Pattern = bindWhole({ a, _ -> a }, f).setSteps(steps)

    fun outerJoin(): Pattern = outerBind(::patternOf)

    /** The wholes come from the inner patterns. */
    fun innerBind(f: (Any?) -> Pattern): Pattern = bindWhole({ _, b -> b }, f)

    fun innerJoin(): Pattern = innerBind(::patternOf)

    /**
     * Like [join], but each inner pattern is shifted so that its cycle zero is the cycle the outer hap begins in, or
     * (with [restart]) so that it starts where the outer hap does.
     */
    fun resetJoin(restart: Boolean = false): Pattern = Pattern { state ->
        val out = ArrayList<Hap>()
        for (outer in discreteOnly().query(state)) {
            val outerWhole = outer.whole!!
            val shifted = patternOf(outer.value)._late(if (restart) outerWhole.begin else outerWhole.begin.cyclePos())
            val inner = shifted.query(state)
            chargeQuery(inner.size.toLong())
            for (i in inner) {
                val part = i.part.intersection(outer.part) ?: continue
                out.add(Hap(i.whole?.intersection(outerWhole), part, i.value).setContext(outer.combineContext(i)))
            }
        }
        out
    }

    fun restartJoin(): Pattern = resetJoin(true)

    /**
     * Plays the whole of each inner pattern's cycle inside the whole of its outer hap (not the way `compress` does: the
     * inner cycle is focused on the outer hap, so inner haps that start before or end after it are cut by it).
     */
    fun squeezeJoin(): Pattern = Pattern { state ->
        val out = ArrayList<Hap>()
        for (outer in discreteOnly().query(state)) {
            val innerPat = patternOf(outer.value)._focusSpan(outer.wholeOrPart())
            val inners = innerPat.query(state.setSpan(outer.part))
            chargeQuery(inners.size.toLong())
            for (inner in inners) {
                var whole: TimeSpan? = null
                if (inner.whole != null && outer.whole != null) {
                    whole = inner.whole.intersection(outer.whole) ?: continue
                }
                val part = inner.part.intersection(outer.part) ?: continue
                out.add(Hap(whole, part, inner.value, inner.combineContext(outer)))
            }
        }
        out
    }

    fun squeezeBind(f: (Any?) -> Pattern): Pattern = fmap(f).squeezeJoin()

    /** Flattens a pattern of patterns, stretching each inner pattern so that its steps fit the steps of this one. */
    fun polyJoin(): Pattern {
        val outerSteps = steps
        return fmap { v ->
            val p = patternOf(v)
            val innerSteps = p.steps
            if (outerSteps == null || innerSteps == null) {
                throw CosicError("Cannot read properties of undefined (reading 'div')")
            }
            p._extend(outerSteps / innerSteps)
        }.outerJoin()
    }

    fun polyBind(f: (Any?) -> Pattern): Pattern = fmap(f).polyJoin()

    /**
     * Joins a pattern of patterns by steps: the steps of the inner patterns, taken from the haps of cycle zero, become
     * the steps of the result, which plays them one after the other (`stepcat`).
     */
    fun stepJoin(): Pattern {
        val firstT = stepcat(_retime(_slices(queryArcLogged(Rational.ZERO, Rational.ONE)))).steps
        return Pattern({ state ->
            val shifted = _early(state.span.begin.sam())
            val haps = shifted.query(state.setSpan(TimeSpan(Rational.ZERO, Rational.ONE)))
            stepcat(_retime(_slices(haps))).query(state)
        }, firstT)
    }

    fun stepBind(f: (Any?) -> Pattern): Pattern = fmap(f).stepJoin()

    // ---- querying --------------------------------------------------------------------------------------------------

    /** The haps in the span from [begin] to [end]; [controls] are what `state.controls` holds for the pattern. Errors are not caught. */
    fun queryArc(begin: Rational, end: Rational, controls: Map<String, Any?> = emptyMap()): List<Hap> =
        query(State(TimeSpan(begin, end), controls))

    /** `queryArc` as Strudel does it: a failing query is logged and answers nothing. Running out of budget or stack is not swallowed. */
    internal fun queryArcLogged(begin: Rational, end: Rational): List<Hap> = try {
        queryArc(begin, end)
    } catch (e: CosicError) {
        if (e.message == TOO_DENSE || e.message == TOO_DEEP) throw e
        errorLogger(e)
        emptyList()
    }

    /** The haps of the first cycle, without their context unless [withContext]. */
    fun firstCycle(withContext: Boolean = false): List<Hap> =
        (if (withContext) this else stripContext()).query(State(TimeSpan(Rational.ZERO, Rational.ONE)))

    /** Answers each cycle of a query separately, so the pattern may assume a query never crosses a cycle. */
    fun splitQueries(): Pattern = Pattern { state ->
        val pieces = state.span.spanCycles()
        if (pieces.size == 1) query(state.setSpan(pieces[0])) else pieces.flatMap { query(state.setSpan(it)) }
    }

    fun withQuerySpan(f: (TimeSpan) -> TimeSpan): Pattern = Pattern { state -> query(state.withSpan(f)) }

    /** Like [withQuerySpan], but [f] may answer null, which makes the query answer nothing. */
    fun withQuerySpanMaybe(f: (TimeSpan) -> TimeSpan?): Pattern = Pattern { state ->
        val span = f(state.span)
        if (span == null) emptyList() else query(state.setSpan(span))
    }

    fun withQueryTime(f: (Rational) -> Rational): Pattern = withQuerySpan { it.withTime(f) }

    /** Maps the whole and the part of every hap. */
    fun withHapSpan(f: (TimeSpan) -> TimeSpan): Pattern = Pattern { state -> query(state).map { it.withSpan(f) } }

    fun withHapTime(f: (Rational) -> Rational): Pattern = withHapSpan { it.withTime(f) }

    fun withHaps(f: (List<Hap>, State) -> List<Hap>): Pattern = Pattern({ state -> f(query(state), state) }, steps)

    fun withHap(f: (Hap) -> Hap): Pattern = withHaps { haps, _ -> haps.map(f) }

    // ---- context ---------------------------------------------------------------------------------------------------

    fun setContext(c: HapContext): Pattern = withHap { it.setContext(c) }

    /** Replaces the context of every hap with `f(context)`. A constant pattern stays constant. */
    fun withContext(f: (HapContext) -> HapContext): Pattern {
        val result = withHap { it.setContext(f(it.context)) }
        return if (pureValue != null) Pattern(result.query, result.steps, pureValue, pureLoc) else result
    }

    fun stripContext(): Pattern = withHap { it.setContext(HapContext.EMPTY) }

    /**
     * Adds the source [range] to the locations of every hap, so the editor can highlight the code behind it. A
     * constant pattern with a truthy value (`"c"`, `"1"` but not `"0"`) also remembers [range] as where it was written.
     */
    fun withLoc(range: SourceRange): Pattern {
        val result = withContext { it.copy(locations = it.locations + range) }
        return if (jsTruthy(pureValue)) Pattern(result.query, result.steps, pureValue, range) else result
    }

    // ---- filtering and sorting -------------------------------------------------------------------------------------

    fun filterHaps(t: (Hap) -> Boolean): Pattern = Pattern { state -> query(state).filter(t) }

    fun filterValues(t: (Any?) -> Boolean): Pattern =
        Pattern({ state -> query(state).filter { t(it.value) } }, steps)

    /** Drops the haps whose value is `undefined` or `null`. */
    fun removeUndefineds(): Pattern = filterValues { it != null && it !== Undefined }

    fun onsetsOnly(): Pattern = filterHaps { it.hasOnset() }

    /** Drops the haps without a whole (the continuous ones). */
    fun discreteOnly(): Pattern = filterHaps { it.whole != null }

    /**
     * Sorts the haps by part begin, part end, whole begin and whole end (the sort is stable). Like Strudel, any hap
     * without a whole makes sorting two or more haps fail.
     */
    fun sortHapsByPart(): Pattern = withHaps { haps, _ -> sortedByPart(haps) }

    fun asNumber(): Pattern = fmap(::parseNumeral)

    // ---- scheduling ------------------------------------------------------------------------------------------------

    /**
     * Adds [hook] to every hap, to be run when the hap is scheduled. [dominant] haps (the default) are not sent to
     * the audio output, the hook takes their place.
     */
    fun onSchedule(hook: ScheduleHook, dominant: Boolean = true): Pattern = withHap { hap ->
        val c = hap.context
        hap.setContext(c.copy(hooks = (c.hooks ?: emptyList()) + hook, dominant = c.dominant || dominant))
    }

    /** Logs [func] of every hap as it is scheduled, `[hap] 0/1 → 1/1: value` by default. Not dominant: the hap is still played. */
    fun log(func: (ScheduledHap) -> String = { "[hap] " + it.hap.showWhole(true) }, getData: (ScheduledHap) -> Any? = { it.hap }): Pattern =
        onSchedule({ logger(func(it), null, getData(it)) }, false)

    fun logValues(func: (Any?) -> String = { "[hap] " + stringifyValues(it, true) }): Pattern = log({ func(it.hap.value) })
}

/** The haps in the order `sortHapsByPart` puts them in. */
internal fun sortedByPart(haps: List<Hap>): List<Hap> {
    if (haps.size >= 2 && haps.any { it.whole == null }) {
        throw CosicError("Cannot read properties of undefined (reading 'begin')")
    }
    return haps.sortedWith { a, b ->
        val partBegin = a.part.begin.compareTo(b.part.begin)
        if (partBegin != 0) return@sortedWith partBegin
        val partEnd = a.part.end.compareTo(b.part.end)
        if (partEnd != 0) return@sortedWith partEnd
        val wholeA = a.whole!!
        val wholeB = b.whole!!
        val wholeBegin = wholeA.begin.compareTo(wholeB.begin)
        if (wholeBegin != 0) wholeBegin else wholeA.end.compareTo(wholeB.end)
    }
}

/** The pattern a value is, or an error when it is not one (the `TypeError` of calling `query` on it in JavaScript). */
internal fun patternOf(v: Any?): Pattern =
    v as? Pattern ?: throw CosicError("${stringifyValues(v, true)} is not a pattern")

// ---- constructors ------------------------------------------------------------------------------------------------

/** A pattern with no events that takes up [steps] steps (`undefined` for no steps at all). */
internal fun gap(steps: Any?): Pattern = Pattern({ emptyList() }, if (steps === Undefined) null else Rational.of(steps))

/** No events, one step. */
internal val silence: Pattern = gap(1L)

/** No events and no steps, a pattern that takes up no room in a `stepcat`. */
internal val nothing: Pattern = gap(0L)

/** One event per cycle, lasting the whole cycle. */
internal fun pure(value: Any?): Pattern = Pattern(
    { state ->
        val pieces = state.span.spanCycles()
        val out = ArrayList<Hap>(pieces.size)
        for (piece in pieces) out.add(Hap(piece.begin.wholeCycle(), piece, value))
        out
    },
    Rational.ONE, if (value === Undefined) null else value, null,
)

internal fun isPattern(x: Any?): Boolean = x is Pattern

/** [x] as a pattern: a pattern stays as it is, a string is parsed if there is a [stringParser], anything else repeats every cycle. */
internal fun reify(x: Any?): Pattern {
    if (x is Pattern) return x
    val parse = stringParser
    if (parse != null && x is String) return parse(x)
    return pure(x)
}

/** Builds a pattern of lists from a list of patterns: every hap holds the values of the patterns at that time. */
internal fun sequenceP(pats: List<Pattern>): Pattern {
    var result = pure(emptyList<Any?>())
    for (pat in pats) {
        result = result.bind { list -> pat.fmap { v -> (list as List<*>) + listOf(v) } }
    }
    return result
}

/**
 * Plays all [pats] at the same time; a list entry is a sequence. A pattern that fails to answer a query is logged and
 * answers nothing, unless the query ran out of budget or stack, which ends the whole query.
 */
internal fun stack(pats: List<Any?>): Pattern {
    val ps = pats.map { if (it is List<*>) sequence(it) else reify(it) }
    val query = { state: State ->
        val out = ArrayList<Hap>()
        for (p in ps) {
            try {
                out.addAll(p.query(state))
            } catch (e: CosicError) {
                if (e.message == TOO_DENSE || e.message == TOO_DEEP) throw e
                errorLogger(e)
            } catch (e: Exception) {
                errorLogger(e)
            }
        }
        out
    }
    return Pattern(query, if (Steps.enabled) Rational.lcmOf(ps.map { it.steps }) else null)
}

internal fun stack(vararg pats: Any?): Pattern = stack(pats.asList())

/**
 * One pattern per cycle, in turn. Each pattern counts its own cycles: it sees cycle 0 the first time it is played,
 * cycle 1 the next time, and so on.
 */
internal fun slowcat(pats: List<Any?>): Pattern {
    val ps = pats.map { if (it is List<*>) fastcat(it) else reify(it) }
    if (ps.isEmpty()) return silence
    if (ps.size == 1) return ps[0]
    val n = ps.size.toLong()
    val query = { state: State ->
        val span = state.span
        val cycle = span.begin.floor()
        val chosen = ps[Math.floorMod(cycle, n).toInt()]
        val offset = Rational(cycle - Math.floorDiv(cycle, n))
        chosen.query(state.setSpan(span.withTime { it - offset })).map { hap -> hap.withSpan { s -> s.withTime { t -> t + offset } } }
    }
    val steps = if (Steps.enabled) Rational.lcmOf(ps.map { it.steps }) else null
    return Pattern(query).splitQueries().setSteps(steps)
}

internal fun slowcat(vararg pats: Any?): Pattern = slowcat(pats.asList())

internal fun cat(pats: List<Any?>): Pattern = slowcat(pats)

/** Like [slowcat], but the chosen pattern is queried with the unshifted time: the patterns do not count their own cycles. */
internal fun slowcatPrime(pats: List<Any?>): Pattern {
    if (pats.isEmpty()) return silence
    val ps = pats.map(::reify)
    return Pattern { state ->
        val cycle = Math.floor(state.span.begin.toDouble()).toLong()
        ps[Math.floorMod(cycle, ps.size.toLong()).toInt()].query(state)
    }.splitQueries()
}

/** All [pats] squeezed into one cycle, one after the other. */
internal fun fastcat(pats: List<Any?>): Pattern {
    var result = slowcat(pats)
    if (pats.size > 1) result = result._fast(Rational(pats.size.toLong())).setSteps(Rational(pats.size.toLong()))
    return result
}

internal fun fastcat(vararg pats: Any?): Pattern = fastcat(pats.asList())

internal fun sequence(pats: List<Any?>): Pattern = fastcat(pats)

internal fun sequence(vararg pats: Any?): Pattern = fastcat(pats.asList())
