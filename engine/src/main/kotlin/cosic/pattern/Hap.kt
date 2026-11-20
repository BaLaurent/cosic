package cosic.pattern

import cosic.SourceRange

/** A half-open stretch of time `[begin, end)` in cycles. Zero-width spans (`begin == end`) are allowed (`timespan.mjs`). */
internal data class TimeSpan(val begin: Rational, val end: Rational) {

    /**
     * Splits the span at every integer cycle boundary. A zero-width span is returned as it is, and an inverted span
     * (`begin > end`) has no pieces at all. Counts the pieces against the query budget first, so a span of millions of
     * cycles is refused before they are built.
     */
    fun spanCycles(): List<TimeSpan> {
        if (begin > end) {
            chargeQuery(1)
            return emptyList()
        }
        if (begin == end) {
            chargeQuery(1)
            return listOf(this)
        }
        chargeQuery(Math.subtractExact(end.floor(), begin.floor()) + 1)
        val endSam = end.sam()
        var from = begin
        if (from.sam() == endSam) return listOf(this)
        val pieces = ArrayList<TimeSpan>()
        while (end > from) {
            if (from.sam() == endSam) {
                pieces.add(TimeSpan(from, end))
                break
            }
            val next = from.nextSam()
            pieces.add(TimeSpan(from, next))
            from = next
        }
        return pieces
    }

    val duration: Rational get() = end - begin

    /** The same span shifted to start inside cycle zero. */
    fun cycleArc(): TimeSpan {
        val b = begin.cyclePos()
        return TimeSpan(b, b + duration)
    }

    fun withTime(f: (Rational) -> Rational): TimeSpan = TimeSpan(f(begin), f(end))

    fun withEnd(f: (Rational) -> Rational): TimeSpan = TimeSpan(begin, f(end))

    /** Like [withTime], but with times relative to the start of the cycle the span begins in. */
    fun withCycle(f: (Rational) -> Rational): TimeSpan {
        val sam = begin.sam()
        return TimeSpan(sam + f(begin - sam), sam + f(end - sam))
    }

    /**
     * The overlap of the two spans, or null when they do not overlap. A span that only touches the other at the end
     * of a non-zero-width span does not count; a zero-width span counts when it lies inside the other one.
     */
    fun intersection(o: TimeSpan): TimeSpan? {
        val b = begin.max(o.begin)
        val e = end.min(o.end)
        if (b > e) return null
        if (b == e) {
            if (b == end && begin < end) return null
            if (b == o.end && o.begin < o.end) return null
        }
        return TimeSpan(b, e)
    }

    /** Like [intersection], but the spans must overlap. */
    fun intersectionE(o: TimeSpan): TimeSpan = intersection(o) ?: throw CosicError("TimeSpans do not intersect")

    fun midpoint(): Rational = begin + duration / Rational(2)

    /** `0/1 → 1/2`, with U+2192 as the arrow. */
    fun show(): String = begin.show() + " \u2192 " + end.show()
}

/** The span of the cycle this time is in. */
internal fun Rational.wholeCycle(): TimeSpan {
    val sam = sam()
    return TimeSpan(sam, sam + Rational.ONE)
}

/** What a pattern is asked: the [span] to answer for, and named [controls] (`_cps`, `cyclist`, `randSeed`, `id`...) the pattern may read. */
internal class State(val span: TimeSpan, val controls: Map<String, Any?> = emptyMap()) {
    fun setSpan(span: TimeSpan): State = State(span, controls)

    fun withSpan(f: (TimeSpan) -> TimeSpan): State = State(f(span), controls)

    /** The same state with [c] added to the controls, `{...controls, ...c}`. */
    fun setControls(c: Map<String, Any?>): State = State(span, if (controls.isEmpty()) c else LinkedHashMap(controls).also { it.putAll(c) })
}

/** Called when a hap is about to sound, on the dispatcher thread and never on the audio thread (K8). */
internal fun interface ScheduleHook {
    fun run(hap: ScheduledHap)
}

/**
 * A hap as the scheduler hands it to the hooks of its context: [frame] is the engine frame its whole begins at,
 * [targetTimeSec] the time (in seconds of the engine's clock) it should sound at, [cps] the tempo it plays at, [cycle]
 * the cycle number its whole begins at and [durationCycles] the length of its whole.
 */
internal class ScheduledHap(
    val hap: Hap,
    val frame: Long,
    val targetTimeSec: Double,
    val cps: Double,
    val cycle: Double,
    val durationCycles: Double,
)

/**
 * What a hap remembers about where it came from: the source [locations] of the code behind it (for highlighting), the
 * [tags] set by `tag()`, the [hooks] added by `onSchedule()` with whether they are [dominant] (a dominant hap is not
 * sent to the audio output), and [extra] for any other key a pattern stores (`color`, `type`, ...).
 * `tags` and `hooks` are null while nothing set them.
 */
internal data class HapContext(
    val locations: List<SourceRange> = emptyList(),
    val extra: Map<String, Any?> = emptyMap(),
    val tags: List<String>? = null,
    val hooks: List<ScheduleHook>? = null,
    val dominant: Boolean = false,
) {
    /**
     * `{...this.context, ...b.context, locations: this.locations.concat(b.locations)}`: locations add up, and for every
     * other key the one of [b] wins when it has one (`hap.mjs:155-158`).
     */
    fun combine(b: HapContext): HapContext {
        val locs = if (b.locations.isEmpty()) locations else if (locations.isEmpty()) b.locations else locations + b.locations
        val more = if (b.extra.isEmpty()) extra else if (extra.isEmpty()) b.extra else extra + b.extra
        return HapContext(locs, more, b.tags ?: tags, b.hooks ?: hooks, if (b.hooks != null) b.dominant else dominant)
    }

    companion object {
        val EMPTY = HapContext()
    }
}

/**
 * One event of a pattern (`hap.mjs`). [whole] is the full extent of the event (null for continuous signals) and
 * [part] the fragment of it that was asked for; the event has an onset only when the part starts where the whole
 * does. The [value] is a JavaScript-like value (see K3): `Double`, `String`, `Boolean`, `Rational`, `List`, `Map`,
 * `JsFn`, `Pattern`, `null` or [Undefined]. [stateful] haps hold a function of a state.
 */
internal data class Hap(
    val whole: TimeSpan?,
    val part: TimeSpan,
    val value: Any?,
    val context: HapContext = HapContext.EMPTY,
    val stateful: Boolean = false,
) {
    /** The length of the whole, or a numeric `duration` of a control map, times its numeric `clip`. */
    val duration: Rational
        get() {
            val v = value as? Map<*, *>
            val set = v?.get("duration")
            val base = if (set is Double) Rational.of(set) else {
                val w = whole ?: throw CosicError("Cannot read properties of undefined (reading 'end')")
                w.end - w.begin
            }
            val clip = v?.get("clip")
            return if (clip is Double) base * Rational.of(clip) else base
        }

    /** The end of the sounding part of the event: its onset plus [duration]. */
    val endClipped: Rational
        get() = (whole ?: throw CosicError("Cannot read properties of undefined (reading 'begin')")).begin + duration

    fun isActive(currentTime: Double): Boolean = wholeBegin() <= currentTime && endClipped.toDouble() >= currentTime

    fun isInPast(currentTime: Double): Boolean = currentTime > endClipped.toDouble()

    fun isInNearPast(margin: Double, currentTime: Double): Boolean = currentTime - margin <= endClipped.toDouble()

    fun isInFuture(currentTime: Double): Boolean = currentTime < wholeBegin()

    fun isInNearFuture(margin: Double, currentTime: Double): Boolean =
        currentTime < wholeBegin() && currentTime > wholeBegin() - margin

    fun isWithinTime(min: Double, max: Double): Boolean = wholeBegin() <= max && endClipped.toDouble() >= min

    private fun wholeBegin(): Double =
        (whole ?: throw CosicError("Cannot read properties of undefined (reading 'begin')")).begin.toDouble()

    fun wholeOrPart(): TimeSpan = whole ?: part

    /** The same hap with [f] applied to the whole and the part. Drops [stateful]. */
    fun withSpan(f: (TimeSpan) -> TimeSpan): Hap = Hap(whole?.let(f), f(part), value, context)

    /** The same hap with [f] applied to the value. Drops [stateful]. */
    fun withValue(f: (Any?) -> Any?): Hap = Hap(whole, part, f(value), context)

    /** Whether the hap contains the start of its whole. */
    fun hasOnset(): Boolean = whole != null && whole.begin == part.begin

    fun hasTag(tag: String): Boolean = context.tags?.contains(tag) == true

    /** Runs a stateful hap's function on [state]: the new state and the hap with the resulting value. Other haps leave the state alone. */
    fun resolveState(state: Any?): Pair<Any?, Hap> {
        if (stateful && hasOnset()) {
            val result = (value as? JsFn)?.call(listOf(state)) as? List<*> ?: throw CosicError("Stateful values must be functions")
            return result[0] to Hap(whole, part, result[1], context, false)
        }
        return state to this
    }

    /** Whether the wholes are equal; two continuous haps are. Comparing a continuous with a discrete hap is a `TypeError` in JavaScript. */
    fun spanEquals(o: Hap): Boolean {
        if (whole == null && o.whole == null) return true
        if (whole == null) throw CosicError("Cannot read properties of undefined (reading 'equals')")
        if (o.whole == null) throw CosicError("Cannot read properties of undefined (reading 'begin')")
        return whole == o.whole
    }

    /**
     * What `hap.show(compact)` prints, which is what Strudel's snapshots hold: `[ 0/1 → 1/2 | s:bd ]`. A fragment shows
     * its part in parentheses, with `⇜` after the whole's begin when the part starts later and `⇝` before the whole's
     * end when the part ends earlier. A hap without a whole (a continuous signal) prints `~` followed by the part.
     */
    fun show(compact: Boolean = false): String {
        val spans = if (whole == null) {
            "~" + part.show()
        } else {
            val isWhole = whole.begin == part.begin && whole.end == part.end
            buildString {
                if (whole.begin != part.begin) append(whole.begin.show()).append(" \u21DC ")
                if (!isWhole) append('(')
                append(part.show())
                if (!isWhole) append(')')
                if (whole.end != part.end) append(" \u21DD ").append(whole.end.show())
            }
        }
        return "[ $spans | ${stringifyValues(value, compact)} ]"
    }

    /** `0/1 → 1/1: value`, the text `log()` prints. */
    fun showWhole(compact: Boolean = false): String = "${whole?.show() ?: "~"}: ${stringifyValues(value, compact)}"

    /** The context of the two haps combined, this one's first (see [HapContext.combine]). */
    fun combineContext(b: Hap): HapContext = context.combine(b.context)

    fun setContext(c: HapContext): Hap = Hap(whole, part, value, c)

    /** Throws when the value is not an object (a control map), with the hint Strudel gives. */
    fun ensureObjectValue() {
        if (jsTypeof(value) != "object") {
            throw CosicError("expected hap.value to be an object, but got \"${jsToString(value)}\". Hint: append .note() or .s() to the end")
        }
    }

    override fun toString(): String = show()
}
