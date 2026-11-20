package cosic.pattern

/*
 * The generators and small combinators of pattern.mjs and signal.mjs that make a pattern out of numbers and lists: `run`,
 * `binary` and its relatives, `base`, `xfade`, `beat`, `morph`, `parray`, `partials`, `phases`, `FX`, `collect`, `hsla` and
 * `hsl`. The catalogue entries that make them callable from programs are in script/catalogue/Generators.kt.
 *
 * Like the functions of Strudel they are built from the others: `run` is `saw.range(0, n).round().segment(n)` and `binaryN` shifts
 * and masks that, so the operators and the time functions are called as the methods programs call (`callMethod`), and a
 * function that a program replaces by registering another with its name is the one that runs.
 */

/**
 * Groups the haps of every query by their wholes: the haps with the same whole become one hap whose value is the list of them
 * (`Pattern.prototype.collect`, pattern.mjs:1042-1046). A hap's whole is compared with that of the first hap of every group.
 * The new hap has the whole and the part of the first hap of its group and no context.
 */
internal fun Pattern.collect(): Pattern = withHaps { haps, _ ->
    val groups = ArrayList<ArrayList<Hap>>()
    for (hap in haps) {
        val group = groups.firstOrNull { hap.spanEquals(it[0]) }
        if (group == null) groups.add(arrayListOf(hap)) else group.add(hap)
    }
    groups.map { Hap(it[0].whole, it[0].part, it.toList<Any?>(), HapContext.EMPTY) }
}

internal object GeneratorFunctions {

    // ---- run and binary (signal.mjs:343-410) -----------------------------------------------------------------------

    /** `run(n)`: the whole numbers from 0 to `n - 1`, `n` of them in a cycle (`saw.range(0, n).round().segment(n)`). */
    fun run(n: Any?): Pattern = saw.callMethod("range", 0.0, n).callMethod("round").callMethod("segment", n)

    /** `binary(n)`: the bits of the number [n], the most significant first, as many as it has (`1 0 1` for 5). */
    fun binary(n: Any?): Pattern = binaryN(n, reify(n).callMethod("log2").callMethod("floor").callMethod("add", 1.0))

    /** `binaryN(n, nBits)`: the bits of [n] as a pattern of `1`s and `0`s, [nBits] of them, the most significant first. */
    fun binaryN(n: Any?, nBits: Any? = 16.0): Pattern {
        val bits = reify(if (nBits === Undefined) 16.0 else nBits)
        // shift and mask, putting the most significant bit first
        val bitPos = run(bits).callMethod("mul", -1.0).callMethod("add", bits.callMethod("sub", 1.0))
        return reify(n).callMethod("segment", bits).callMethod("brshift", bitPos).callMethod("band", pure(1.0))
    }

    /** `binaryL(n)`: [binaryNL] with as many bits as the number has. */
    fun binaryL(n: Any?): Pattern = binaryNL(n, reify(n).callMethod("log2").callMethod("floor").callMethod("add", 1.0))

    /** `binaryNL(n, nBits)`: the bits of [n], [nBits] of them, most significant first, as one list per event instead of one event per bit. */
    fun binaryNL(n: Any?, nBits: Any? = 16.0): Pattern =
        reify(n).withValue { v ->
            jsFn(1) { args ->
                val bits = jsToNumber(args[0])
                val value = jsToInt32(jsToNumber(v))
                val list = ArrayList<Any?>()
                var i = bits - 1
                while (i >= 0) {
                    if (i > MAX_COUNT) throw CosicError(TOO_DENSE)
                    list.add(((value shr (jsToInt32(i) and 31)) and 1).toDouble())
                    i--
                }
                list
            }
        }.appLeft(reify(if (nBits === Undefined) 16.0 else nBits))

    // ---- base (pattern.mjs:4511-4552) -------------------------------------------------------------------------------

    /**
     * `base(n, b, d)`: the digits of [n] in base [b], most significant first, as a sequence; [d] limits it to its last `d` digits
     * (0 for all of them). The three are patterns, and so is the list `n` may be.
     */
    fun base(n: Any?, b: Any?, d: Any?): Pattern {
        val numbers = reify(if (n is List<*>) sequence(n) else n)
        val bases = reify(if (b === Undefined) 10.0 else b)
        val limits = reify(if (d === Undefined) 0.0 else d)
        return limits.withValue { e ->
            bases.withValue { c ->
                numbers.withValue { v -> sequence(digitsOf(v, c, e)) }.squeezeJoin()
            }.squeezeJoin()
        }.squeezeJoin()
    }

    /** The digits of [v] in base [c], most significant first, the last [e] of them if there are more (when [e] is not 0). */
    private fun digitsOf(v: Any?, c: Any?, e: Any?): List<Any?> {
        val radix = jsToNumber(c)
        var digits = ArrayList<Any?>()
        var value: Any? = v
        while (jsLess(0.0, value) == true) {
            // a base of 0, 1 or a fraction never runs out of digits
            if (digits.size >= MAX_COUNT) throw CosicError(TOO_DENSE)
            digits.add(jsToNumber(value) % radix)
            value = Math.floor(jsToNumber(value) / radix)
        }
        digits.reverse()
        if (jsTruthy(e) && jsLess(e, digits.size.toDouble()) == true) {
            digits = ArrayList(digits.subList(jsSliceIndex(-1 * jsToNumber(e), digits.size), digits.size))
        }
        return digits
    }

    // ---- xfade (pattern.mjs:4099-4124) ------------------------------------------------------------------------------

    private fun fadeGain(p: Any?): Double = if (jsLess(p, 0.5) == true) 1.0 else 1.0 - (jsToNumber(p) - 0.5) / 0.5

    /** `xfade(a, pos, b)`: cross-fades from [a] (at `pos` 0) to [b] (at `pos` 1) by multiplying their gains. */
    fun xfade(a: Any?, pos: Any?, b: Any?): Pattern {
        val position = reify(pos)
        val gainA = position.fmap { v -> linkedMapOf<String, Any?>("gain" to fadeGain(v)) }
        val gainB = position.fmap { v -> linkedMapOf<String, Any?>("gain" to fadeGain(1.0 - jsToNumber(v))) }
        return stack(reify(a).callMethod("mul", gainA), reify(b).callMethod("mul", gainB))
    }

    // ---- beat (pattern.mjs:4136-4147) --------------------------------------------------------------------------------

    /** `beat(t, div)`: plays each event for the [t]th of [div] parts of the cycle (`t` is taken modulo `div`). */
    fun beat(t: Any?, div: Any?, pat: Pattern): Pattern {
        val position = Rational.of(t).mod(Rational.of(div))
        val parts = Rational.of(div)
        val begin = position / parts
        val end = (position + Rational.ONE) / parts
        return pat.fmap { x -> pure(x)._compress(begin, end) }.innerJoin()
    }

    // ---- morph (pattern.mjs:4149-4214) -----------------------------------------------------------------------------------

    /**
     * `_morph(from, to, by)`: a pattern of `true`s, one for every true entry of the list [from], moved towards where the matching
     * entry of [to] is by the fraction [by] (0 is [from], 1 is [to]). Both lists have the same number of true entries.
     */
    fun _morph(from: Any?, to: Any?, by: Any?): Pattern {
        val byFraction = Rational.of(by)
        val length = when (from) {
            is List<*> -> from.size
            is String -> from.length
            else -> 0
        }
        val dur = Rational.ONE / Rational(length.toLong())
        fun positions(list: Any?): List<Pair<Rational, Any?>> {
            if (list !is List<*>) throw CosicError("list.entries is not a function")
            val result = ArrayList<Pair<Rational, Any?>>()
            for ((pos, value) in list.withIndex()) {
                if (jsTruthy(value)) result.add(Rational(pos.toLong()) / Rational(list.size.toLong()) to value)
            }
            return result
        }
        val fromPositions = positions(from)
        val toPositions = positions(to)
        val arcs = fromPositions.mapIndexed { i, (posA, _) ->
            val (posB, _) = toPositions.getOrNull(i) ?: throw CosicError("undefined is not iterable (cannot read property Symbol(Symbol.iterator))")
            // the difference of the two positions is a number, which `mul` makes a fraction again
            val begin = byFraction * Rational.of(posB.toDouble() - posA.toDouble()) + posA
            TimeSpan(begin, begin + dur)
        }
        return Pattern({ state ->
            val cycle = state.span.begin.sam()
            val cycleArc = state.span.cycleArc()
            val haps = ArrayList<Hap>()
            for (whole in arcs) {
                val part = whole.intersection(cycleArc) ?: continue
                haps.add(Hap(whole.withTime { it + cycle }, part.withTime { it + cycle }, true))
            }
            haps
        }).splitQueries()
    }

    /** `morph(from, to, by)`: [_morph] of the lists, fractions and patterns of them that come with the patterns of lists. */
    fun morph(from: Any?, to: Any?, by: Any?): Pattern {
        val fromPattern = reify(from)
        val toPattern = reify(to)
        val byPattern = reify(by)
        return fromPattern.innerBind { f -> toPattern.innerBind { t -> byPattern.innerBind { b -> _morph(f, t, b) } } }
    }

    // ---- lists (pattern.mjs:4325-4415) ------------------------------------------------------------------------------------

    /** `parray(pats)`: a pattern of lists, with the value of each of the patterns [pats] at the same time, structure from all of them. */
    fun parray(pats: Any?): Pattern {
        val items: List<Any?> = when (pats) {
            is List<*> -> pats
            is String -> pats.map { it.toString() }
            else -> throw CosicError("pats is not iterable")
        }
        var acc: Pattern = pure(curry(items.size) { it })
        for (p in items) acc = acc.appBoth(reify(p))
        return acc
    }

    /** `_ensureListPattern(list)`: a list is the patterns it holds as lists, anything else the pattern it is. */
    fun ensureListPattern(list: Any?): Pattern = if (list is List<*>) parray(list) else reify(list)

    /** `pat.partials(list)` and `pat.phases(list)`: the control [key] set to [list] (see [ensureListPattern]), the structure being [pat]'s. */
    fun withList(pat: Pattern, key: String, list: Any?): Pattern =
        pat.withValue { v -> jsFn(1) { args -> jsMerge(SampleFunctions.spread(v), linkedMapOf<String, Any?>(key to args[0])) } }
            .appLeft(ensureListPattern(list))

    /** `partials(list)` and `phases(list)` as functions: a pattern of the control [key] with the value the list gives. */
    fun listControl(key: String, list: Any?): Pattern = ensureListPattern(list).callMethod("as", key)

    /** `pat.FX(...effects)`: adds each of the effects (patterns of control maps) to the chain `FX` of the events, in the order they are given in. */
    fun fx(pat: Pattern, effects: List<Any?>): Pattern {
        val chain = parray(effects.map { reify(it) })
        return pat.withValue { v ->
            jsFn(1) { args ->
                val current = SampleFunctions.property(v, "FX").let { if (it == null || it === Undefined) emptyList<Any?>() else it }
                val effect = args[0]
                val joined: Any? = when (current) {
                    is List<*> -> current + (if (effect is List<*>) effect else listOf(effect))
                    is String -> current + jsToString(effect)
                    else -> throw CosicError("currFX.concat is not a function")
                }
                jsMerge(SampleFunctions.spread(v), linkedMapOf<String, Any?>("FX" to joined))
            }
        }.appLeft(chain)
    }

    // ---- colours (pattern.mjs:3267-3282) ------------------------------------------------------------------------------------

    /** `hsla(h, s, l, a)`: the colour of the events, hue in turns, saturation and lightness from 0 to 1 (written as percentages), alpha from 0 to 1. */
    fun hsla(h: Any?, s: Any?, l: Any?, a: Any?, pat: Pattern): Pattern =
        pat.callMethod("color", "hsla(${jsToString(h)}turn,${percent(s)}%,${percent(l)}%,${jsToString(a)})")

    /** `hsl(h, s, l)`: [hsla] without alpha. */
    fun hsl(h: Any?, s: Any?, l: Any?, pat: Pattern): Pattern =
        pat.callMethod("color", "hsl(${jsToString(h)}turn,${percent(s)}%,${percent(l)}%)")

    /** `${x * 100}` */
    private fun percent(x: Any?): String = jsNumberToString(jsToNumber(x) * 100)
}
