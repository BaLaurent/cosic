package cosic.pattern

/*
 * The stepwise functions of Strudel's pattern.mjs: the ones that work with the number of steps per cycle a pattern knows
 * it has (`pace`, `take`, `drop`, `shrink`, `tour`, `zip`, ...), the combinators that line patterns up by steps
 * (`polymeter`, `stepalt`, `stackLeft`, ...), and `arrange`/`seqPLoop`, which lay patterns out over several cycles.
 * `stepcat`, `expand`, `extend` and the `stepJoin` they stand on are part of the kernel (Kernel.kt, Pattern.kt).
 *
 * The functions take their arguments "raw" (a number, a fraction, a list), exactly what `func` of Strudel's `register`
 * gets; `Pattern._take(i)` is `Pattern.prototype._take` there. The catalogue entries that make them callable from
 * programs are in script/catalogue/Stepwise.kt, and they do what `register` does around them.
 *
 * Quirks of the original are kept (the line references are to packages/core/pattern.mjs at the pinned commit): the
 * comparisons with `0` in `shrinklist` that a fraction can never satisfy, `grow` reading a list amount as a fraction, the
 * `TypeError`s of the stack functions for a pattern without steps. What the port does differently:
 *  - `setSteps` returns a copy instead of changing the pattern, so the shared `silence` and `nothing` are never touched;
 *  - `seqPLoop` completes a part given as `[stop, pattern]` in a copy, not in the array the program passed in;
 *  - a count that would make the loops of `stepalt` and `shrinklist` build more than [MAX_COUNT] entries is [TOO_DENSE].
 */

// ---- values of a program, as JavaScript reads them ------------------------------------------------------------------

/** `TypeError: Cannot read properties of <undefined|null> (reading '<property>')`, what reading a property of nothing says. */
private fun cannotRead(property: String, of: Any? = Undefined): CosicError =
    CosicError("Cannot read properties of ${jsToString(of)} (reading '$property')")

/** `x.hasSteps`: a pattern says so, any other value has no such property, and `null` and `undefined` cannot be read. */
private fun hasStepsProperty(x: Any?): Boolean {
    if (x is Pattern) return x.hasSteps
    if (x == null || x === Undefined) throw cannotRead("hasSteps", x)
    return false
}

/** `x._steps`: the steps of a pattern; `undefined` (null here) for any other value, and `null` and `undefined` cannot be read. */
private fun stepsProperty(x: Any?): Rational? {
    if (x is Pattern) return x.steps
    if (x == null || x === Undefined) throw cannotRead("_steps", x)
    return null
}

/** `pat.<method>`, for a value that must be a pattern to have it: anything else is `TypeError: x.<method> is not a function`. */
private fun patternWithMethod(x: Any?, name: String, method: String): Pattern {
    if (x is Pattern) return x
    if (x == null || x === Undefined) throw cannotRead(method, x)
    throw CosicError("$name.$method is not a function")
}

/** `a + b` of two values of a program: strings concatenate, anything else adds as numbers. */
private fun jsPlus(a: Any?, b: Any?): Any? {
    val x = jsToPrimitive(a)
    val y = jsToPrimitive(b)
    return if (x is String || y is String) jsToString(x) + jsToString(y) else jsToNumber(x) + jsToNumber(y)
}

/**
 * The element [i] of a value that is destructured as an array (`[cycles, pattern]`): lists and strings (of characters) are
 * iterable, anything else is a `TypeError` worded as V8 words it.
 */
private fun element(list: Any?, i: Int): Any? {
    if (list is String) return if (i < list.length) list[i].toString() else Undefined
    if (list !is List<*>) {
        val what = when {
            list === Undefined -> "undefined"
            list == null -> "object null"
            list is Number -> "number ${jsToString(list)}"
            list is Boolean -> "boolean $list"
            list is JsFn -> "function"
            else -> "object"
        }
        throw CosicError("$what is not iterable (cannot read property Symbol(Symbol.iterator))")
    }
    return if (i < list.size) list[i] else Undefined
}

// ---- pace ---------------------------------------------------------------------------------------------------------------

/**
 * Speeds the pattern up or down so that it has [target] steps per cycle (`pace`, `steps`; pattern.mjs:3403). A pattern
 * without steps stays as it is, one with none (0 steps) is [nothing].
 */
internal fun Pattern._pace(target: Any?): Pattern {
    val current = steps ?: return this
    if (current.isZero()) return nothing
    return _fast(Rational.of(target) / current).setSteps(Rational.of(target))
}

// ---- polymeter ----------------------------------------------------------------------------------------------------------

/**
 * `_sequenceCount` (pattern.mjs:1950): a list is a sequence of the sequences of its elements and has as many steps as it
 * has elements (an empty list has none, a list of one is its element); anything else is one step.
 */
internal fun _sequenceCount(x: Any?): Pair<Pattern, Int> {
    if (x is List<*>) {
        if (x.isEmpty()) return silence to 0
        if (x.size == 1) return _sequenceCount(x[0])
        return fastcat(x.map { _sequenceCount(it).first }) to x.size
    }
    return reify(x) to 1
}

/**
 * The old `polymeter` of lists (pattern.mjs:3414): each of [args] (a list, or a pattern) is repeated, or slowed down, so
 * that it has [steps] steps per cycle (the steps of the first when [steps] is 0), and they are stacked.
 */
internal fun _polymeterListSteps(steps: Any?, args: List<Any?>): Pattern {
    val seqs = args.map { _sequenceCount(it) }
    if (seqs.isEmpty()) return silence
    val wanted = if (jsLooseEq(steps, 0.0)) seqs[0].second.toDouble() else steps
    val pats = ArrayList<Pattern>()
    for ((pat, count) in seqs) {
        if (count == 0) continue
        pats.add(if (jsLooseEq(wanted, count.toDouble())) pat else pat._fast(Rational.of(wanted) / Rational(count.toLong())))
    }
    return stack(pats)
}

/**
 * Lines the steps of the patterns up so that they all fit the cycle: each is paced to the least common multiple of their
 * steps (`polymeter`, `pm`; pattern.mjs:3447). A first argument that is a list is the old behaviour of
 * [_polymeterListSteps]. Arguments without steps (strings included) are left out.
 */
internal fun polymeter(args: List<Any?>): Pattern {
    if (args.firstOrNull() is List<*>) return _polymeterListSteps(0.0, args)
    val pats = args.filter { hasStepsProperty(it) }.map { it as Pattern }
    if (pats.isEmpty()) return silence
    val steps = Rational.lcmOf(pats.map { it.steps }) ?: return silence
    if (steps.isZero()) return nothing
    return stack(pats.map { it._pace(steps) }).setSteps(steps)
}

// ---- stepalt ------------------------------------------------------------------------------------------------------------

/**
 * Like `stepcat`, but an argument that is a list takes turns: the result alternates between its elements, one per repeat
 * (`stepalt(["bd cp", "mt"], "bd")` is `"bd cp bd mt bd"`; pattern.mjs:3537). The repeats go on until every list has come
 * round together, the least common multiple of their lengths.
 */
internal fun stepalt(args: List<Any?>): Pattern {
    val groups = args.map { if (it is List<*>) it.map(::reify) else listOf(reify(it)) }
    val cycles = Rational.lcmOf(groups.map { Rational(it.size.toLong()) })
    val result = ArrayList<Pattern>()
    if (cycles != null) {
        if (cycles.toDouble() * groups.size > MAX_COUNT) throw CosicError(TOO_DENSE)
        var cycle = 0
        while (Rational(cycle.toLong()) < cycles) {
            for (group in groups) result.add(if (group.isEmpty()) silence else group[cycle % group.size])
            cycle++
        }
    }
    val kept = result.filter { it.steps?.let { s -> s.signum() > 0 } == true }
    val steps = kept.fold(Rational.ZERO) { sum, p -> sum + checkNotNull(p.steps) }
    return stepcat(kept).setSteps(steps)
}

// ---- take, drop and friends ---------------------------------------------------------------------------------------------

/**
 * Takes [i] steps from the start of the pattern, dropping the rest, or from its end when [i] is negative (`take`, `s_add`;
 * pattern.mjs:3570). More steps than there are is the pattern itself.
 */
internal fun Pattern._take(i: Any?): Pattern {
    val current = steps ?: return nothing
    if (current.signum() <= 0) return nothing
    var amount = Rational.of(i)
    if (amount.isZero()) return nothing
    val flip = amount.signum() < 0
    if (flip) amount = amount.abs()
    val fraction = amount / current
    if (fraction.signum() <= 0) return nothing
    if (fraction >= Rational.ONE) return this
    return if (flip) callMethod("zoom", Rational.ONE - fraction, 1.0) else callMethod("zoom", 0.0, fraction)
}

/** Drops [i] steps from the start of the pattern, or from its end when [i] is negative (`drop`, `s_sub`; pattern.mjs:3614). */
internal fun Pattern._drop(i: Any?): Pattern {
    val current = steps ?: return nothing
    val amount = Rational.of(i)
    return if (amount.signum() < 0) _take(current + amount) else _take(Rational.ZERO - (current - amount))
}

/**
 * Like `extend` (played faster and expanded by [factor]), but the cycles of the pattern are repeated first, so that a pattern
 * that differs from cycle to cycle (`<a b>`) plays each of its cycles [factor] times in a row instead of [factor] different
 * cycles in one (`replicate`; pattern.mjs:3655).
 */
internal fun Pattern._replicate(factor: Any?): Pattern = callMethod("repeatCycles", factor)._fast(factor)._expand(factor)

/** The same pattern with its steps divided by [factor]; the opposite of `expand` (`contract`, `s_contract`; pattern.mjs:3679). */
internal fun Pattern._contract(factor: Any?): Pattern = withSteps { it / Rational.of(factor) }

// ---- shrink and grow ----------------------------------------------------------------------------------------------------

/**
 * The patterns that `shrink` plays one after the other (`shrinklist`, `s_taperlist`; pattern.mjs:3683): this pattern with
 * [amount] steps dropped from its start, then another [amount], and so on. A negative amount drops from the end. [amount] can
 * be a list `[amount, times]` that also says how many patterns there are; without it there are as many as there are steps.
 * A pattern without steps is the only one.
 *
 * Like the original, a `times` of 0 is checked with `===` and the amount is not (`amountv === 0` is false for a fraction),
 * so an amount of 0 gives `times` copies of the whole pattern.
 */
internal fun Pattern._shrinklist(amount: Any?): List<Pattern> {
    val total = steps ?: return listOf(this)
    val pair = amount as? List<*>
    val times: Any? = if (pair != null) (if (pair.size > 1) pair[1] else Undefined) else total
    var amountv = Rational.of(if (pair != null) (if (pair.isNotEmpty()) pair[0] else Undefined) else amount)
    if (jsStrictEq(times, 0.0)) return listOf(this)
    val ranges = ArrayList<Pair<Rational, Rational>>()
    var i = 0L
    if (amountv.signum() > 0) {
        val segment = Rational.ONE / total * amountv
        while (jsLess(i.toDouble(), times) == true) {
            val start = segment * Rational(i)
            if (start > Rational.ONE) break
            if (ranges.size >= MAX_COUNT) throw CosicError(TOO_DENSE)
            ranges.add(start to Rational.ONE)
            i++
        }
    } else {
        amountv = Rational.ZERO - amountv
        val segment = Rational.ONE / total * amountv
        while (jsLess(i.toDouble(), times) == true) {
            val end = Rational.ONE - segment * Rational(i)
            if (end.signum() < 0) break
            if (ranges.size >= MAX_COUNT) throw CosicError(TOO_DENSE)
            ranges.add(Rational.ZERO to end)
            i++
        }
    }
    return ranges.map { (from, to) -> callMethod("zoom", from, to) }
}

/** `shrinklist` the other way round: the patterns that `grow` plays (`growlist`; pattern.mjs:3724). */
internal fun Pattern._growlist(amount: Any?): List<Pattern> = _shrinklist(amount).reversed()

/** The global `shrinklist(amount, pat)`, which is `pat.shrinklist(amount)` (pattern.mjs:3722). */
internal fun shrinklist(amount: Any?, pat: Any?): List<Pattern> = patternWithMethod(pat, "pat", "shrinklist")._shrinklist(amount)

/** The global `growlist(amount, pat)`, which is `pat.growlist(amount)` (pattern.mjs:3727). */
internal fun growlist(amount: Any?, pat: Any?): List<Pattern> = patternWithMethod(pat, "pat", "growlist")._growlist(amount)

/** The steps of [list] together, as `shrink` and `grow` set them on their result. A pattern without steps counts as none. */
private fun stepsOf(list: List<Pattern>): Rational = list.fold(Rational.ZERO) { sum, p -> sum + (p.steps ?: Rational.ZERO) }

/**
 * Plays the pattern, then the pattern without its first [amount] steps, then without its first 2 * [amount], until there is
 * nothing left (`shrink`, `s_taper`; pattern.mjs:3751). A negative amount drops from the end.
 */
internal fun Pattern._shrink(amount: Any?): Pattern {
    if (!hasSteps) return nothing
    val list = _shrinklist(amount)
    return stepcat(list).setSteps(stepsOf(list))
}

/**
 * The opposite of `shrink`: the pattern grows by [amount] steps at a time until all of it plays (`grow`; pattern.mjs:3790).
 * Its amount is negated with `Fraction(0).sub(amount)`, so a list `[amount, times]` is read as the fraction
 * `amount/times`.
 */
internal fun Pattern._grow(amount: Any?): Pattern {
    if (!hasSteps) return nothing
    val list = _shrinklist(Rational.ZERO - Rational.of(amount)).reversed()
    return stepcat(list).setSteps(stepsOf(list))
}

// ---- tour and zip -------------------------------------------------------------------------------------------------------

/**
 * `args[0].tour(...args.drop(1))`, the global `tour(pat, ...many)` and the method (pattern.mjs:3823-3827): inserts the pattern
 * into the list [args] of patterns, first at its end, then one place further to its start each time, then at its front, with
 * the steps of all of them put together. The last one repeats the list as it is. An entry of the list that is itself a list
 * is spread into the end of the result by `concat`, as in the original.
 */
internal fun tour(args: List<Any?>): Pattern {
    val self = patternWithMethod(args.firstOrNull() ?: Undefined, "pat", "tour")
    val many = args.drop(1)
    val out = ArrayList<Any?>()
    for (i in many.indices) {
        val at = many.size - i
        out.addAll(many.subList(0, at))
        out.add(self)
        out.addAll(many.subList(at, many.size))
    }
    out.add(self)
    for (x in many) if (x is List<*>) out.addAll(x) else out.add(x)
    return stepcat(out)
}

/**
 * Plays one step of each pattern in turn, round and round, as a single dense cycle (`zip`; pattern.mjs:3850): the first step
 * of the first pattern, the first step of the second, ..., the second step of the first. Patterns without steps are left out.
 */
internal fun zip(args: List<Any?>): Pattern {
    val pats = args.filter { hasStepsProperty(it) }.map { it as Pattern }
    val zipped = slowcat(pats.map { it._slow(it.steps) })
    val steps = Rational.lcmOf(pats.map { it.steps })
    return zipped._fast(steps ?: Undefined).setSteps(steps)
}

// ---- the stack functions that line patterns up by steps ------------------------------------------------------------------

/**
 * `_stackWith` (pattern.mjs:1678): [pats] (a list is a sequence) are stacked after [func] has padded them to the number of
 * steps of the longest. A pattern without steps cannot be padded: a `TypeError` (`pat._steps.eq`), as it is for the first one
 * when steps are calculated (`left.maximum`).
 */
private fun stackWith(pats: List<Any?>, func: (steps: Rational?, pats: List<Pattern>) -> List<Pattern>): Pattern {
    val patterns = pats.map { if (it is List<*>) sequence(it) else reify(it) }
    if (patterns.isEmpty()) return silence
    if (patterns.size == 1) return patterns[0]
    val steps = if (Steps.enabled) maxSteps(patterns.map { it.steps }) else null
    return stack(func(steps, patterns))
}

/** `left.maximum(...right)`: the largest of the steps, an undefined one counting as 0; the first cannot be undefined. */
private fun maxSteps(all: List<Rational?>): Rational {
    val left = all.firstOrNull() ?: throw cannotRead("maximum")
    return all.drop(1).fold(left) { max, other -> (other ?: Rational.ZERO).max(max) }
}

/** [pat] padded by [pad] with the silence it lacks to have [steps] steps: as it is when it has as many already. */
private fun padded(steps: Rational?, pat: Pattern, pad: (padding: Pattern) -> Pattern): Pattern {
    val own = pat.steps ?: throw cannotRead("eq")
    if (steps != null && own == steps) return pat
    val total = steps ?: throw cannotRead("sub")
    return pad(gap(total - own))
}

/** Aligns the patterns to the left, padding the shorter ones with silence at their end (`stackLeft`; pattern.mjs:1703). */
internal fun stackLeft(pats: List<Any?>): Pattern =
    stackWith(pats) { steps, ps -> ps.map { pat -> padded(steps, pat) { padding -> stepcat(listOf(pat, padding)) } } }

/** Aligns the patterns to the right, padding the shorter ones with silence in front (`stackRight`; pattern.mjs:1722). */
internal fun stackRight(pats: List<Any?>): Pattern =
    stackWith(pats) { steps, ps -> ps.map { pat -> padded(steps, pat) { padding -> stepcat(listOf(padding, pat)) } } }

/** Centres the patterns, padding the shorter ones with half the missing silence on each side (`stackCenter`; pattern.mjs:1741). */
internal fun stackCenter(pats: List<Any?>): Pattern = stackWith(pats) { steps, ps ->
    ps.map { pat ->
        val own = pat.steps ?: throw cannotRead("eq")
        if (steps != null && own == steps) {
            pat
        } else {
            val missing = steps ?: throw cannotRead("sub")
            val g = gap((missing - own) / Rational(2))
            stepcat(listOf(g, pat, g))
        }
    }
}

/**
 * The stack function [by] names, applied to [pats] (`stackBy`; pattern.mjs:1774). [by] is a pattern of the names `centre`,
 * `left`, `right`, `expand` (a plain stack) and `repeat` (a polymeter of them all, paced to the steps of the longest),
 * `stackBy("<left right>", a, b)` picking one per cycle. The functions are chosen with `inhabit`, which squeezes each
 * choice into the hap that names it.
 */
internal fun stackBy(by: Any?, pats: List<Any?>): Pattern {
    val steps = maxSteps(pats.map { stepsProperty(it) })
    val lookup = mapOf<String, JsFn>(
        "centre" to jsFn(0) { stackCenter(it) },
        "left" to jsFn(0) { stackLeft(it) },
        "right" to jsFn(0) { stackRight(it) },
        "expand" to jsFn(0) { stack(it) },
        "repeat" to jsFn(0) { polymeter(it)._pace(steps) },
    )
    val chosen = patternWithMethod(by, "by", "inhabit").fmap { key -> lookup[jsToString(key)]?.let(::pure) ?: Undefined }
    return chosen.squeezeJoin().fmap { func -> callFn(func, *pats.toTypedArray()) }.innerJoin().setSteps(steps)
}

/** `stackBy("repeat", ...pats)`: the patterns as a polymeter paced to the steps of the longest (`stackRepeat`; pattern.mjs:1770). */
internal fun stackRepeat(pats: List<Any?>): Pattern = stackBy(reify("repeat"), pats)

// ---- layouts over several cycles ----------------------------------------------------------------------------------------

/**
 * Plays each of [sections], a list `[cycles, pattern]`, for that many cycles, one after the other, and then starts over
 * (`arrange`; pattern.mjs:1875). The pattern plays at its own speed for each of its cycles: `[2, "a b"]` is two cycles of
 * `a b`.
 */
internal fun arrange(sections: List<Any?>): Pattern {
    val total = sections.fold<Any?, Any?>(0.0) { sum, section -> jsPlus(sum, element(section, 0)) }
    val fitted = sections.map { section ->
        val cycles = element(section, 0)
        val pattern = patternWithMethod(element(section, 1), "section", "fast")
        listOf(cycles, pattern._fast(cycles))
    }
    return stepcat(fitted)._slow(total)
}

/**
 * Like `arrange`, but each of [parts] says when it starts and when it stops rather than how long it lasts: `[start, stop,
 * pattern]`, so that patterns can overlap (`seqPLoop`; pattern.mjs:1893). A part of two, `[stop, pattern]`, starts where the
 * one before it stopped. The loop is as long as the stop of the last part.
 */
internal fun seqPLoop(parts: List<Any?>): Pattern {
    var total: Any? = Rational.ZERO
    val full = parts.map { part ->
        if (part == null || part === Undefined) throw cannotRead("length", part)
        // `part.length == 2` and `part.unshift(total)`; only a list has them, any other value has no `length` and no `part[1]`.
        val completed: Any? = if (part is List<*> && part.size == 2) listOf(total) + part else part
        total = if (completed is List<*>) element(completed, 1) else Undefined
        completed
    }
    val stacked = stack(
        full.map { part ->
            val start = element(part, 0)
            val stop = element(part, 1)
            pure(reify(element(part, 2)))._compress(Rational.of(start) / Rational.of(total), Rational.of(stop) / Rational.of(total))
        },
    )
    return stacked._slow(total).innerJoin()
}
