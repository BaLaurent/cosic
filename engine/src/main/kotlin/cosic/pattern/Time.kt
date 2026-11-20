package cosic.pattern

/*
 * The time and structure functions of Strudel's pattern.mjs: `ply`, `inside`, `every`, `zoom`, `rev`, `jux`, `echo`,
 * `iter`, `chunk` and the rest of CORE task T1. Like the kernel functions (Kernel.kt) they take their arguments "raw"
 * (a number, a fraction, a string, a function...), exactly what `func` gets from `register`, and the `_name` forms are
 * the plain methods `pat._ply(2)`. The catalogue entries that make them callable from programs are in
 * script/catalogue/Time.kt.
 *
 * The code follows pattern.mjs line by line (the lines are cited), including its JavaScript arithmetic (`n - 1` on a raw
 * value is `jsToNumber(n) - 1`) and the errors it raises for arguments it was not made for (`every(0, f)` is an
 * `Invalid array length`, `linger(-1)` a `TypeError`). A call of `pat.name(...)` in the source, which finds the method at
 * run time and patternifies its arguments, is a `callMethod("name", ...)` here; a call of `pat._name(...)` is the plain
 * function. Functions that live in other categories (`gain`, `mul`, `struct`) are reached through `callMethod` only; `restart`,
 * which the operators catalogue defines as a method of the class and not as a registered function, through `Pattern.aligned`.
 */

// ---- JavaScript helpers ---------------------------------------------------------------------------------------------------

/** `id` of util.mjs, the default of the function argument of `unjoin`. */
private val ID: JsFn = jsFn(1) { it[0] }

/** `func(...args)` where the program names the callee [name]: anything but a function is V8's `name is not a function`. */
private fun callNamed(name: String, func: Any?, vararg args: Any?): Any? {
    if (func !is JsFn) throw CosicError("$name is not a function")
    return func.call(args.asList())
}

/**
 * The length of `Array.from({length: x})`: `ToLength(x)`, so NaN and anything below 1 is 0 and a fraction is cut off. A length
 * that no array can have is the RangeError `Invalid array length`.
 */
private fun arrayLikeLength(x: Double): Int {
    if (x.isNaN() || x < 1.0) return 0
    val t = Math.floor(x)
    if (t > Int.MAX_VALUE) throw CosicError("Invalid array length")
    return t.toInt()
}

/** The length `Array(x)` accepts: an integer from 0 to 2^32 - 1; anything else (a fraction, a negative number, NaN) is the RangeError `Invalid array length`. */
private fun arrayLength(x: Double): Long {
    if (x.isNaN() || x < 0.0 || x > 4294967295.0 || x != Math.floor(x)) throw CosicError("Invalid array length")
    return x.toLong()
}

/** `listRange(min, max)` of util.mjs: `Array.from({length: max - min + 1}, (_, i) => i + min)`. */
private fun listRange(min: Double, max: Double): List<Double> {
    val length = arrayLikeLength(max - min + 1.0)
    return List(length) { it.toDouble() + min }
}

/** `a < b` as JavaScript evaluates it, which is false when a NaN is involved. */
private fun jsLt(a: Any?, b: Any?): Boolean = jsLess(a, b) == true

/** `a >= b`, false when a NaN is involved. */
private fun jsGte(a: Any?, b: Any?): Boolean = jsLess(a, b) == false

/** `{begin, end}` of a span, or of any object that has them (a plain object of a program is a map here). */
private fun spanBounds(span: Any?): Pair<Any?, Any?> = when (span) {
    is TimeSpan -> span.begin to span.end
    is Map<*, *> -> (span["begin"] ?: Undefined) to (span["end"] ?: Undefined)
    null, Undefined -> throw CosicError("Cannot read properties of ${jsToString(span)} (reading 'begin')")
    else -> Undefined to Undefined
}

/** `Fraction(factor).mulmaybe(steps)`: the steps times [factor], or undefined for a pattern without steps. */
private fun stepsTimes(factor: Any?, steps: Rational?): Rational? {
    val f = Rational.of(factor)
    return steps?.let { f * it }
}

// ---- ply, hurry --------------------------------------------------------------------------------------------------------------

/** Repeats each event [factor] times (`P:2337`). The steps are the steps of the pattern times [factor]. */
internal fun Pattern._ply(factor: Any?): Pattern {
    var result = fmap { x -> pure(x)._fast(factor) }.squeezeJoin()
    if (Steps.enabled) result = result.setSteps(stepsTimes(factor, steps))
    return result
}

/** Plays the pattern [r] times faster and its samples at `speed` [r] (`P:2377`): the speed is multiplied in with the union multiplication of `mul`. */
internal fun Pattern._hurry(r: Any?): Pattern = _fast(r).callMethod("mul", pure(linkedMapOf<String, Any?>("speed" to r)))

// ---- inside, outside, every, when ------------------------------------------------------------------------------------------

/** Applies [f] to the pattern slowed down by [factor], then speeds the result up again (`P:2407`). */
internal fun Pattern._inside(factor: Any?, f: Any?): Pattern = patternOf(callNamed("f", f, _slow(factor)))._fast(factor)

/** Applies [f] to the pattern sped up by [factor], then slows the result down again (`P:2418`). */
internal fun Pattern._outside(factor: Any?, f: Any?): Pattern = patternOf(callNamed("f", f, _fast(factor)))._slow(factor)

/**
 * `slowcatPrime` of `Array(n - 1).fill(pat)` plus `func(pat)` at its end ([applyLast]) or start: cycle `c` plays the pattern
 * with [func] applied when `c` is the last (first) of every `n` cycles. The array is never built, so a huge `n` costs nothing,
 * but `n - 1` must be a length `Array` accepts.
 */
private fun Pattern.everyNth(n: Any?, func: Any?, applyLast: Boolean): Pattern {
    val others = arrayLength(jsToNumber(n) - 1.0)
    val applied = reify(callNamed("func", func, this))
    val total = others + 1
    val appliedAt = if (applyLast) others else 0L
    val plain = this
    return Pattern { state ->
        val cycle = Math.floor(state.span.begin.toDouble()).toLong()
        (if (Math.floorMod(cycle, total) == appliedAt) applied else plain).query(state)
    }.splitQueries()
}

/** Applies [func] in the last of every [n] cycles (`P:2433`). */
internal fun Pattern._lastOf(n: Any?, func: Any?): Pattern = everyNth(n, func, applyLast = true)

/** Applies [func] in the first of every [n] cycles (`P:2462`), which is what `every` does. */
internal fun Pattern._firstOf(n: Any?, func: Any?): Pattern = everyNth(n, func, applyLast = false)

/** `func(pat)` (`P:2475`). */
internal fun Pattern._apply(func: Any?): Any? = callNamed("func", func, this)

/** `applyN(n, func, p)` (`P:2994`): [func] applied to [p] [n] times over. */
internal fun applyN(n: Any?, func: Any?, p: Any?): Any? {
    var result = p
    var i = 0.0
    while (jsLt(i, n)) {
        result = callNamed("func", func, result)
        i += 1.0
    }
    return result
}

/** Plays the pattern at [cpm] cycles per minute, `cpm / 60 / 1` cycles per cycle (`P:2487`; the REPL redefines it with the tempo of the scheduler). */
internal fun Pattern._cpm(cpm: Any?): Pattern = _fast(jsToNumber(cpm) / 60.0 / 1.0)

/** `on ? func(pat) : pat` (`P:2740`): [on] is a plain value, not a pattern, and counts as JavaScript counts it. */
internal fun Pattern._when(on: Any?, func: Any?): Any? = if (jsTruthy(on)) callNamed("func", func, this) else this

/** The pattern with the result of [func] on its pattern delayed by [time] on top of it (`P:2755`). */
internal fun Pattern._off(time: Any?, func: Any?): Pattern = stack(listOf(this, callNamed("func", func, callMethod("late", time))))

/** Every other cycle is played once, twice as fast and a quarter of a cycle late (`P:2770`). */
internal fun Pattern._brak(): Pattern {
    val onOddCycles = jsFn(1) { a -> fastcat(a[0], silence)._late(0.25) }
    return callMethod("when", slowcat(false, true), onOddCycles)
}

/** Swaps true and false in a binary pattern (`P:2719`). */
internal fun Pattern._invert(): Pattern = fmap { x -> !jsTruthy(x) }

/** Silence when [on] reads as a non-zero integer, `Boolean(parseInt(on))`, else the pattern itself (`P:3226`). */
internal fun Pattern._bypass(on: Any?): Pattern = if (jsTruthy(jsParseInt(on))) silence else this

// ---- zoom, bite, linger, segment ---------------------------------------------------------------------------------------

/**
 * Plays the part of the pattern from [s] to [e] of each cycle, stretched over the whole cycle (`P:2541`). The whole and
 * the part of an event are mapped around their own cycle begin. A span that is empty or the wrong way round is silence.
 */
internal fun Pattern._zoom(s: Any?, e: Any?): Pattern {
    val end = Rational.of(e)
    val start = Rational.of(s)
    if (start >= end) return nothing
    val d = end - start
    val newSteps = if (Steps.enabled) steps?.let { it * d } else null
    return withQuerySpan { span -> span.withCycle { t -> t * d + start } }
        .withHapSpan { span -> span.withCycle { t -> (t - start) / d } }
        .splitQueries()
        .setSteps(newSteps)
}

/** `pat.zoom(a.begin, a.end)` (`P:2556`). */
internal fun Pattern._zoomArc(span: Any?): Pattern {
    val (begin, end) = spanBounds(span)
    return callMethod("zoom", begin, end)
}

/**
 * Cuts each cycle into [npat] slices and plays the slices [ipat] names, one after the other (`P:2574`): the haps of [ipat] are
 * the slice numbers, and each slice is squeezed into its hap.
 */
internal fun Pattern._bite(npat: Any?, ipat: Any?): Pattern {
    val slices = ipat as? Pattern ?: throw CosicError("ipat.fmap is not a function")
    val pat = this
    return slices.fmap { i ->
        jsFn(1) { args ->
            val n = Rational.of(args[0])
            val a = Rational.of(i).div(n).mod(Rational.ONE)
            val b = a + Rational.ONE / n
            pat._zoom(a, b)
        }
    }.appLeft(npat as? Pattern ?: throw CosicError("pat_val.query is not a function")).squeezeJoin()
}

/**
 * Plays the cycle the structure [structure] gives to each hap in turn instead of the same cycle (`P:2623`). The
 * structure is read one cycle at a time; the haps whose value is 0 do not count.
 */
internal fun Pattern._isobite(structure: Any?): Pattern {
    val shape = (structure as? Pattern ?: throw CosicError("structure.withQuerySpan is not a function"))
        .withQuerySpan { it.begin.wholeCycle() }
    fun counted(haps: List<Hap>): List<Hap> = haps.filter { !jsLooseEq(it.value, 0.0) }
    val structPat = Pattern { state ->
        val hapsCycle = counted(shape.query(state))
            .sortedWith { a, b ->
                val whole = a.whole ?: throw CosicError("Cannot read properties of undefined (reading 'begin')")
                val other = b.whole ?: throw CosicError("Cannot read properties of undefined (reading 'begin')")
                val difference = whole.begin.toDouble() - other.begin.toDouble()
                if (difference < 0.0) -1 else if (difference > 0.0) 1 else 0
            }
            .mapIndexed { i, hap -> hap.withValue { i.toDouble() } }
        hapsCycle.mapNotNull { hap ->
            state.span.intersection(hap.part)?.let { newPart -> Hap(hap.whole, newPart, hap.value, hap.context) }
        }
    }.splitQueries()
    val stepsPattern = Pattern { state ->
        val count = counted(shape.query(state)).size
        listOf(Hap(state.span.begin.wholeCycle(), state.span, count.toDouble(), HapContext.EMPTY))
    }.splitQueries()
    return callMethod("fast", stepsPattern).callMethod("bite", stepsPattern, structPat)
}

/**
 * Plays the first [t] of each cycle (the last `-t` of it when negative), repeated to fill the cycle (`P:2664`). [t] is a
 * number there, and only a fraction object has the `add` the negative case uses, so a negative number is a `TypeError`.
 */
internal fun Pattern._linger(t: Any?): Pattern {
    if (jsLooseEq(t, 0.0)) return silence
    if (jsLt(t, 0.0)) {
        if (t !is Rational) throw CosicError("t.add is not a function")
        return _zoom(t + Rational.ONE, 1.0)._slow(t)
    }
    return _zoom(0.0, t)._slow(t)
}

/** Samples the pattern [rate] times per cycle: it is cut into that many steps (`P:2687`). */
internal fun Pattern._segment(rate: Any?): Pattern =
    callMethod("struct", pure(true)._fast(rate)).setSteps(Rational.of(rate))

/** Delays the events of the second half of each of [n] slices of the cycle by [swing] of half a slice (`P:2699`). */
internal fun Pattern._swingBy(swing: Any?, n: Any?): Pattern {
    val delays = fastcat(0.0, jsToNumber(swing) / 2.0)
    val late = jsFn(1) { a -> patternOf(a[0]).callMethod("late", delays) }
    return callMethod("inside", n, late)
}

/** `swingBy(1/3, n)` (`P:2709`). */
internal fun Pattern._swing(n: Any?): Pattern = callMethod("swingBy", 1.0 / 3.0, n)

// ---- reversing, pressing -----------------------------------------------------------------------------------------------

/** Reverses every cycle (`P:2784`). */
internal fun Pattern._rev(): Pattern {
    val pat = this
    return Pattern { state ->
        val span = state.span
        val cycle = span.begin.sam()
        val nextCycle = span.begin.nextSam()
        fun reflect(s: TimeSpan): TimeSpan = TimeSpan(cycle + (nextCycle - s.end), cycle + (nextCycle - s.begin))
        pat.query(state.setSpan(reflect(span))).map { hap -> hap.withSpan(::reflect) }
    }.splitQueries()
}

/** Reverses the whole of time (`P:2821`). */
internal fun Pattern._revv(): Pattern {
    val negate = { span: TimeSpan -> TimeSpan(Rational.ZERO - span.end, Rational.ZERO - span.begin) }
    return withQuerySpan(negate).withHapSpan(negate)
}

/** Shifts each event into its timespan by [r] of it, shortening it (`P:2835`). */
internal fun Pattern._pressBy(r: Any?): Pattern = fmap { x -> pure(x).callMethod("compress", r, 1.0) }.squeezeJoin()

/** `pressBy(0.5)` (`P:2847`). */
internal fun Pattern._press(): Pattern = _pressBy(0.5)

/** Applies `rev` to every other cycle (`P:2870`). */
internal fun Pattern._palindrome(): Pattern = callMethod("lastOf", 2.0, jsFn(1) { a -> patternOf(a[0])._rev() })

// ---- jux ---------------------------------------------------------------------------------------------------------------------

/** The `pan` of a hap value, `0.5` when it has none (`elem_or(val, 'pan', 0.5)`); `'pan' in val` is a `TypeError` for anything that is no object. */
private fun panOf(value: Any?): Any? = when (value) {
    is Map<*, *> -> if (value.containsKey("pan")) value["pan"] else 0.5
    null, Undefined, is Number, is String, is Boolean -> throw CosicError("Cannot use 'in' operator to search for 'pan' in ${jsToString(value)}")
    else -> 0.5
}

/** `Object.assign({}, value, {pan})`: a map with its `pan` set, keeping its place when it has one. */
@Suppress("UNCHECKED_CAST")
private fun withPan(value: Any?, pan: Double): Any? {
    val extra = linkedMapOf<String, Any?>("pan" to pan)
    return when (value) {
        is Map<*, *> -> jsMerge(value as Map<String, Any?>, extra)
        is List<*> -> {
            val indexed = LinkedHashMap<String, Any?>()
            value.forEachIndexed { i, x -> indexed[i.toString()] = x }
            jsMerge(indexed, extra)
        }
        else -> extra
    }
}

/**
 * Plays the pattern panned by `-by / 2` from the middle, together with what [func] makes of the pattern panned by `+by / 2`
 * (`P:2887`). 0 is mono, 1 full stereo. The values must be control maps.
 */
internal fun Pattern._juxBy(by: Any?, func: Any?): Pattern {
    val half = jsToNumber(by) / 2.0
    val left = withValue { v -> withPan(v, jsToNumber(panOf(v)) - half) }
    val right = callNamed("func", func, withValue { v -> withPan(v, jsToNumber(panOf(v)) + half) })
    val newSteps = if (Steps.enabled) Rational.lcmOf(listOf(left.steps, (right as? Pattern)?.steps)) else null
    return stack(listOf(left, right)).setSteps(newSteps)
}

/** Like [_juxBy], with the sides swapping every cycle (`P:2908`). */
internal fun Pattern._juxFlipBy(by: Any?, func: Any?): Pattern = callMethod("juxBy", slowcat(by, -jsToNumber(by)), func)

/** `juxBy(1)` (`P:2925`). */
internal fun Pattern._jux(func: Any?): Pattern = _juxBy(1.0, func)

/** `juxFlipBy(1)` (`P:2940`). */
internal fun Pattern._juxFlip(func: Any?): Pattern = _juxFlipBy(1.0, func)

// ---- echo ---------------------------------------------------------------------------------------------------------------------

/** Superimposes [times] copies, each [time] cycles later than the one before, [func] being applied to each with its number (`P:2957`). */
internal fun Pattern._echoWith(times: Any?, time: Any?, func: Any?): Pattern =
    stack(
        listRange(0.0, jsToNumber(times) - 1.0).map { i ->
            callNamed("func", func, _late(Rational.of(time) * Rational.of(i)), i)
        },
    )

/** The gain of copy `i` of an echo: [feedback] to the power of `i`. */
private fun echoGain(feedback: Any?): JsFn =
    jsFn(2) { a -> patternOf(a[0]).callMethod("gain", StrictMath.pow(jsToNumber(feedback), jsToNumber(a[1]))) }

/** `echoWith` with each copy quieter than the one before by [feedback] (`P:2976`); the gain is set, not multiplied. */
internal fun Pattern._echo(times: Any?, time: Any?, feedback: Any?): Pattern = _echoWith(times, time, echoGain(feedback))

/** `echo` with its last two arguments swapped (`P:2990`). */
internal fun Pattern._stut(times: Any?, feedback: Any?, time: Any?): Pattern = _echoWith(times, time, echoGain(feedback))

// ---- ply with a function -----------------------------------------------------------------------------------------------------

/** [func] applied [n] times over to the pattern [p], which a plain value becomes (the function is called as `applyN(i, func, x)` is). */
private fun appliedTimes(i: Double, func: Any?, x: Any?): Any? = applyN(i, func, reify(x))

/** Repeats each event [factor] times, [func] applied once more to each repetition (`P:3014`). */
internal fun Pattern._plyWith(factor: Any?, func: Any?): Pattern {
    var result = fmap { x ->
        cat(listRange(0.0, jsToNumber(factor) - 1.0).map { i -> appliedTimes(i, func, x) })._fast(factor)
    }.squeezeJoin()
    if (Steps.enabled) result = result.setSteps(stepsTimes(factor, steps))
    return result
}

/** Repeats each event [factor] times, [func] getting the event and the number of the repetition (`P:3037`). */
internal fun Pattern._plyForEach(factor: Any?, func: Any?): Pattern {
    var result = fmap { x ->
        val repeats = listOf<Any?>(pure(x)) + listRange(1.0, jsToNumber(factor) - 1.0).map { i -> callNamed("func", func, pure(x), i) }
        cat(repeats)._fast(factor)
    }.squeezeJoin()
    if (Steps.enabled) result = result.setSteps(stepsTimes(factor, steps))
    return result
}

// ---- iter, chunk -------------------------------------------------------------------------------------------------------------

/**
 * Divides the pattern into [times] subdivisions and plays them in order, starting from the next one each cycle; [back] plays
 * them from the end (`_iter`, `P:3057`).
 */
internal fun Pattern._iter(times: Any?, back: Boolean = false): Pattern {
    val t = Rational.of(times)
    return slowcat(
        listRange(0.0, (t - Rational.ONE).toDouble()).map { i ->
            val offset = Rational.of(i) / t
            if (back) callMethod("late", offset) else callMethod("early", offset)
        },
    )
}

/** `iter` the other way round (`P:3085`). */
internal fun Pattern._iterBack(times: Any?): Pattern = _iter(times, back = true)

/** Repeats each cycle [n] times (`P:3103`). */
internal fun Pattern._repeatCycles(n: Any?): Pattern {
    val pat = this
    return Pattern { state ->
        val cycle = state.span.begin.sam()
        val sourceCycle = (cycle / Rational.of(n)).sam()
        val delta = cycle - sourceCycle
        pat.query(state.withSpan { span -> span.withTime { t -> t - delta } })
            .map { hap -> hap.withSpan { span -> span.withTime { t -> t + delta } } }
    }.splitQueries()
}

/** `[true, false, ...]` with [n] entries: `Array(n - 1).fill(false)` with `true` put in front of it. */
private fun chunkMask(n: Any?): List<Any?> {
    val falses = arrayLength(jsToNumber(n) - 1.0)
    if (falses >= Int.MAX_VALUE) throw CosicError("Invalid array length")
    return buildList(falses.toInt() + 1) {
        add(true)
        repeat(falses.toInt()) { add(false) }
    }
}

/**
 * Divides the pattern into [n] parts and applies [func] to one of them in each cycle, from the first to the last (`_chunk`,
 * `P:3129`). [back] goes from the last to the first; [fast] leaves the cycles of the pattern alone, where the others repeat each
 * cycle [n] times so that all the parts of one cycle are played.
 */
private fun Pattern._chunkWith(n: Any?, func: Any?, back: Boolean, fast: Boolean): Pattern {
    val mask = chunkMask(n)
    val bits = sequence(mask)._iter(n, back = !back)
    val source = if (fast) this else _repeatCycles(n)
    return source.callMethod("when", bits, func)
}

internal fun Pattern._chunk(n: Any?, func: Any?): Pattern = _chunkWith(n, func, back = false, fast = false)

internal fun Pattern._chunkBack(n: Any?, func: Any?): Pattern = _chunkWith(n, func, back = true, fast = false)

internal fun Pattern._fastChunk(n: Any?, func: Any?): Pattern = _chunkWith(n, func, back = false, fast = true)

/** `chunk` on a looped subcycle of the pattern (`P:3202`). */
internal fun Pattern._chunkInto(n: Any?, func: Any?): Pattern = into(fastcat(chunkMask(n))._iter(n, back = true), func)

/** `chunkInto` moving backwards through the chunks (`P:3216`). */
internal fun Pattern._chunkBackInto(n: Any?, func: Any?): Pattern = into(fastcat(chunkMask(n))._iter(n)._early(1.0), func)

// ---- ribbon, tag, filter, within -------------------------------------------------------------------------------------------

/**
 * Loops the part of the pattern that starts at [offset] and lasts [cycles] cycles (`P:3253`): `pat.early(offset).restart(pure(1).slow(cycles))`,
 * where `restart` is `keepif` with the `restart` alignment (`P:1503`).
 */
internal fun Pattern._ribbon(offset: Any?, cycles: Any?): Pattern =
    callMethod("early", offset).aligned("keepif", "restart", listOf(pure(1.0).callMethod("slow", cycles)))

/** Gives every hap the tag [tag] (`P:3296`); `hap.hasTag` finds them again. */
internal fun Pattern.tag(tag: Any?): Pattern = withContext { ctx -> ctx.copy(tags = (ctx.tags ?: emptyList()) + jsToString(tag)) }

/** The haps [test] says yes to, which it gets as `array.filter` gives it things: the hap and its index (`P:3308`). */
internal fun Pattern._filter(test: Any?): Pattern = withHaps { haps, _ ->
    if (test !is JsFn) throw CosicError("${stringifyValues(test, true)} is not a function")
    haps.filterIndexed { i, hap -> jsTruthy(test.call(listOf(hap, i.toDouble()))) }
}

/** The haps whose whole begins at a time [test] says yes to (`P:3318`). */
internal fun Pattern._filterWhen(test: Any?): Pattern = callMethod(
    "filter",
    jsFn(1) { a ->
        val hap = a[0] as Hap
        val whole = hap.whole ?: throw CosicError("Cannot read properties of undefined (reading 'begin')")
        callNamed("test", test, whole.begin)
    },
)

/** Applies [fn] to the events that begin between [a] and [b] of a cycle only (`P:3328`). */
internal fun Pattern._within(a: Any?, b: Any?, fn: Any?): Pattern = stack(
    listOf(
        callNamed(
            "fn", fn,
            callMethod("filterWhen", jsFn(1) { args -> (args[0] as Rational).cyclePos().let { jsGte(it, a) && jsGte(b, it) } }),
        ),
        callMethod("filterWhen", jsFn(1) { args -> (args[0] as Rational).cyclePos().let { jsLt(it, a) || jsLt(b, it) } }),
    ),
)

// ---- methods of the class itself (not registered functions) ---------------------------------------------------------------------

/** The results of [funcs] applied to the pattern, played together (`P:856`). */
internal fun Pattern.layer(funcs: List<Any?>): Pattern = stack(funcs.map { callNamed("func", it, this) })

/** The pattern together with the results of [funcs] applied to it (`P:871`). */
internal fun Pattern.superimpose(funcs: List<Any?>): Pattern = stack(listOf<Any?>(this) + funcs.map { callNamed("func", it, this) })

/**
 * Breaks the pattern into a pattern of patterns by the structure of [pieces] (`P:998`): where [pieces] is true, the value is the
 * piece of the pattern that lies under the hap, looped (`ribbon`) and with [func] applied; where it is false, the pattern itself.
 */
internal fun Pattern.unjoin(pieces: Any?, func: Any? = ID): Pattern {
    val pieceHaps = pieces as? Pattern ?: throw CosicError("pieces.withHap is not a function")
    val apply = if (func === Undefined) ID else func
    return pieceHaps.withHap { hap ->
        hap.withValue { v ->
            if (jsTruthy(v)) {
                val whole = hap.whole ?: throw CosicError("Cannot read properties of undefined (reading 'begin')")
                callNamed("func", apply, _ribbon(whole.begin, whole.duration))
            } else {
                this
            }
        }
    }
}

/** Applies [func] to the pieces of the pattern that the true values of [pieces] mark, looped (`P:1016`). */
internal fun Pattern.into(pieces: Any?, func: Any?): Pattern = unjoin(pieces, func).innerJoin()
