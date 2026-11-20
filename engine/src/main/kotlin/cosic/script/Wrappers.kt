package cosic.script

import cosic.SourceRange
import cosic.pattern.CosicError
import cosic.pattern.Hap
import cosic.pattern.HapContext
import cosic.pattern.Pattern
import cosic.pattern.Rational
import cosic.pattern.State
import cosic.pattern.TimeSpan
import cosic.pattern.Undefined
import cosic.pattern.jsTruthy
import cosic.pattern.wholeCycle
import cosic.script.js.JsArray
import cosic.script.js.JsObject
import cosic.script.js.PropFlags
import cosic.script.js.describe
import cosic.script.js.toNumber
import cosic.script.js.toStr
import cosic.script.js.stdlib.JsInspectable

/*
 * The objects a program sees for the things the pattern kernel is made of. The kernel has its own classes (`Pattern`,
 * `Hap`, `TimeSpan`, `Rational`, `State`); a program gets these wrappers, which are objects with the members the
 * originals have (pattern.mjs, hap.mjs, timespan.mjs, fraction.mjs, state.mjs), and the bridge ([Bridge]) converts at
 * every call between the two worlds. Each wrapper holds its kernel value; a pattern is as immutable in a program as it is
 * in the kernel, so the only wrapper with state is [HapValue], whose `value` a program may change in place.
 */

/** A pattern, with `Pattern.prototype` of its realm as prototype. */
internal class PatternValue(@JvmField val bridge: Bridge, @JvmField val pattern: Pattern) : JsObject(bridge.realm.patternPrototype), JsInspectable {
    override val className: String get() = "Pattern"

    override fun inspectText(): String? = "Pattern"
}

/** A fraction (`Fraction` of fraction.js, extended as in fraction.mjs). `valueOf()` is its value as a number, so `t % 1` and `Math.pow(t, 2)` work on it. */
internal class FractionValue(@JvmField val bridge: Bridge, @JvmField val value: Rational) : JsObject(bridge.fractionPrototype), JsInspectable {
    override val className: String get() = "Fraction"

    override fun inspectText(): String? = value.show()
}

/** A time span `[begin, end)` of fractions. */
internal class TimeSpanValue(@JvmField val bridge: Bridge, @JvmField val span: TimeSpan) : JsObject(bridge.timeSpanPrototype), JsInspectable {
    override val className: String get() = "TimeSpan"

    override fun inspectText(): String? = span.show()
}

/** What a pattern is asked: the span and the named controls. */
internal class StateValue(@JvmField val bridge: Bridge, @JvmField val state: State) : JsObject(bridge.statePrototype) {
    override val className: String get() = "State"
}

private object Unset

/**
 * An event. `hap.value` is the one member that is shared with the program: the value is made into an object when it is
 * first read, and a program may change it in place (`withHap(x => { x.value.s = 'piano'; return x })`), so the kernel hap
 * is rebuilt from what the program holds ([toHap]) when it is handed back. A hap the program never looked into is the
 * kernel's hap unchanged, hooks and all.
 */
internal class HapValue(@JvmField val bridge: Bridge, private val base: Hap) : JsObject(bridge.hapPrototype), JsInspectable {
    private var wholeJs: Any? = Unset
    private var partJs: Any? = Unset
    private var valueJs: Any? = Unset
    private var contextJs: Any? = Unset
    private var statefulJs: Boolean? = null

    override val className: String get() = "Hap"

    override fun inspectText(): String? = toHap().show(true)

    var whole: Any?
        get() {
            if (wholeJs === Unset) wholeJs = base.whole?.let { TimeSpanValue(bridge, it) } ?: Undefined
            return wholeJs
        }
        set(v) {
            wholeJs = v
        }

    var part: Any?
        get() {
            if (partJs === Unset) partJs = TimeSpanValue(bridge, base.part)
            return partJs
        }
        set(v) {
            partJs = v
        }

    var value: Any?
        get() {
            if (valueJs === Unset) valueJs = bridge.toJs(base.value)
            return valueJs
        }
        set(v) {
            valueJs = v
        }

    var context: Any?
        get() {
            if (contextJs === Unset) contextJs = bridge.contextToJs(base.context)
            return contextJs
        }
        set(v) {
            contextJs = v
        }

    var stateful: Boolean
        get() = statefulJs ?: base.stateful
        set(v) {
            statefulJs = v
        }

    /** The kernel hap this one stands for: the original when nothing was read or changed, else rebuilt from the parts. */
    fun toHap(): Hap {
        if (wholeJs === Unset && partJs === Unset && valueJs === Unset && contextJs === Unset && statefulJs == null) return base
        val w = if (wholeJs === Unset) base.whole else bridge.spanOrNull(wholeJs)
        val p = if (partJs === Unset) base.part else bridge.spanOf(partJs)
        val v = if (valueJs === Unset) base.value else bridge.toKernel(valueJs)
        val c = if (contextJs === Unset) base.context else bridge.contextFromJs(contextJs, base.context)
        return Hap(w, p, v, c, statefulJs ?: base.stateful)
    }
}

// ---- installing the classes ---------------------------------------------------------------------------------------------

/** Makes the prototypes and constructors of `Fraction`, `TimeSpan`, `Hap` and `State` in the realm of [bridge]. */
internal fun installWrapperClasses(bridge: Bridge) {
    installFraction(bridge)
    installTimeSpan(bridge)
    installHap(bridge)
    installState(bridge)
}

private fun Bridge.receiverError(self: Any?, type: String, method: String) =
    realm.typeError("$type.prototype.$method called on incompatible receiver ${realm.describe(self)}")

private fun Bridge.fraction(v: Any?): Rational = try {
    Rational.of(toKernel(v))
} catch (e: CosicError) {
    throw realm.typeError("Invalid argument: ${realm.describe(v)} is not a fraction")
}

private fun installFraction(bridge: Bridge) {
    val realm = bridge.realm
    val proto = bridge.fractionPrototype

    fun self(v: Any?, method: String): Rational = (v as? FractionValue)?.value ?: throw bridge.receiverError(v, "Fraction", method)

    fun method(name: String, length: Int, impl: (Rational, List<Any?>) -> Any?) =
        realm.defineMethod(proto, name, length) { thisArg, args -> impl(self(thisArg, name), args) }

    fun arg(args: List<Any?>, i: Int): Rational = bridge.fraction(args.getOrElse(i) { Undefined })

    proto.defineAccessor("s", { self(it, "s").signum().let { sign -> if (sign < 0) -1.0 else 1.0 } })
    proto.defineAccessor("n", { numeratorAndDenominator(self(it, "n")).first })
    proto.defineAccessor("d", { numeratorAndDenominator(self(it, "d")).second })

    method("valueOf", 0) { r, _ -> r.toDouble() }
    method("toString", 0) { r, _ -> r.toJsString() }
    method("toFraction", 0) { r, _ -> r.show() }
    method("show", 0) { r, _ -> r.show() }
    method("add", 1) { r, a -> FractionValue(bridge, r + arg(a, 0)) }
    method("sub", 1) { r, a -> FractionValue(bridge, r - arg(a, 0)) }
    method("mul", 1) { r, a -> FractionValue(bridge, r * arg(a, 0)) }
    method("div", 1) { r, a -> FractionValue(bridge, r / arg(a, 0)) }
    method("mod", 1) { r, a -> FractionValue(bridge, r.mod(arg(a, 0))) }
    method("neg", 0) { r, _ -> FractionValue(bridge, -r) }
    method("abs", 0) { r, _ -> FractionValue(bridge, r.abs()) }
    method("floor", 0) { r, _ -> FractionValue(bridge, Rational(r.floor())) }
    method("ceil", 0) { r, _ -> FractionValue(bridge, -Rational((-r).floor())) }
    method("lt", 1) { r, a -> r < arg(a, 0) }
    method("gt", 1) { r, a -> r > arg(a, 0) }
    method("lte", 1) { r, a -> r <= arg(a, 0) }
    method("gte", 1) { r, a -> r >= arg(a, 0) }
    method("eq", 1) { r, a -> r.compareTo(arg(a, 0)) == 0 }
    method("equals", 1) { r, a -> r.compareTo(arg(a, 0)) == 0 }
    method("compare", 1) { r, a -> r.compareTo(arg(a, 0)).coerceIn(-1, 1).toDouble() }
    method("min", 1) { r, a -> FractionValue(bridge, r.min(arg(a, 0))) }
    method("max", 1) { r, a -> FractionValue(bridge, r.max(arg(a, 0))) }
    method("sam", 0) { r, _ -> FractionValue(bridge, r.sam()) }
    method("nextSam", 0) { r, _ -> FractionValue(bridge, r.nextSam()) }
    method("cyclePos", 0) { r, _ -> FractionValue(bridge, r.cyclePos()) }
    method("wholeCycle", 0) { r, _ -> TimeSpanValue(bridge, r.wholeCycle()) }

    val constructor = realm.constructor(
        "Fraction", 2, proto,
        call = { _, args -> FractionValue(bridge, newFraction(bridge, args)) },
        construct = { args -> FractionValue(bridge, newFraction(bridge, args)) },
    )
    realm.defineGlobal("Fraction", constructor)
}

/** `Fraction(x)`, `Fraction(numerator, denominator)`. */
private fun newFraction(bridge: Bridge, args: List<Any?>): Rational {
    val first = bridge.fraction(args.getOrElse(0) { Undefined })
    return if (args.size >= 2) first / bridge.fraction(args[1]) else first
}

/** The absolute numerator and the denominator of [r] as numbers, read from how it shows (`-3/4`). */
private fun numeratorAndDenominator(r: Rational): Pair<Double, Double> {
    val text = r.show()
    val slash = text.indexOf('/')
    return Math.abs(text.substring(0, slash).toDouble()) to text.substring(slash + 1).toDouble()
}

private fun installTimeSpan(bridge: Bridge) {
    val realm = bridge.realm
    val proto = bridge.timeSpanPrototype

    fun self(v: Any?, method: String): TimeSpan = (v as? TimeSpanValue)?.span ?: throw bridge.receiverError(v, "TimeSpan", method)

    fun method(name: String, length: Int, impl: (TimeSpan, List<Any?>) -> Any?) =
        realm.defineMethod(proto, name, length) { thisArg, args -> impl(self(thisArg, name), args) }

    fun time(f: Any?): (Rational) -> Rational = { t -> bridge.fraction(bridge.callJs(f, FractionValue(bridge, t))) }

    proto.defineAccessor("begin", { FractionValue(bridge, self(it, "begin").begin) })
    proto.defineAccessor("end", { FractionValue(bridge, self(it, "end").end) })
    proto.defineAccessor("duration", { FractionValue(bridge, self(it, "duration").duration) })

    method("show", 0) { s, _ -> s.show() }
    method("toString", 0) { s, _ -> s.show() }
    method("midpoint", 0) { s, _ -> FractionValue(bridge, s.midpoint()) }
    method("spanCycles", 0) { s, _ -> realm.newArray(s.spanCycles().map { TimeSpanValue(bridge, it) }) }
    method("cycleArc", 0) { s, _ -> TimeSpanValue(bridge, s.cycleArc()) }
    method("withTime", 1) { s, a -> TimeSpanValue(bridge, s.withTime(time(a.getOrElse(0) { Undefined }))) }
    method("withEnd", 1) { s, a -> TimeSpanValue(bridge, s.withEnd(time(a.getOrElse(0) { Undefined }))) }
    method("withCycle", 1) { s, a -> TimeSpanValue(bridge, s.withCycle(time(a.getOrElse(0) { Undefined }))) }
    method("intersection", 1) { s, a -> s.intersection(bridge.spanOf(a.getOrElse(0) { Undefined }))?.let { TimeSpanValue(bridge, it) } ?: Undefined }
    method("intersection_e", 1) { s, a -> TimeSpanValue(bridge, s.intersectionE(bridge.spanOf(a.getOrElse(0) { Undefined }))) }
    method("equals", 1) { s, a -> s == bridge.spanOf(a.getOrElse(0) { Undefined }) }

    val constructor = realm.constructor(
        "TimeSpan", 2, proto,
        call = { _, args -> TimeSpanValue(bridge, newTimeSpan(bridge, args)) },
        construct = { args -> TimeSpanValue(bridge, newTimeSpan(bridge, args)) },
    )
    realm.defineGlobal("TimeSpan", constructor)
}

private fun newTimeSpan(bridge: Bridge, args: List<Any?>): TimeSpan =
    TimeSpan(bridge.fraction(args.getOrElse(0) { Undefined }), bridge.fraction(args.getOrElse(1) { Undefined }))

private fun installHap(bridge: Bridge) {
    val realm = bridge.realm
    val proto = bridge.hapPrototype

    fun self(v: Any?, method: String): HapValue = v as? HapValue ?: throw bridge.receiverError(v, "Hap", method)

    fun method(name: String, length: Int, impl: (HapValue, List<Any?>) -> Any?) =
        realm.defineMethod(proto, name, length) { thisArg, args -> impl(self(thisArg, name), args) }

    fun number(args: List<Any?>, i: Int): Double = realm.toNumber(args.getOrElse(i) { Undefined })

    proto.defineAccessor("whole", { self(it, "whole").whole }, { h, v -> self(h, "whole").whole = v })
    proto.defineAccessor("part", { self(it, "part").part }, { h, v -> self(h, "part").part = v })
    proto.defineAccessor("value", { self(it, "value").value }, { h, v -> self(h, "value").value = v })
    proto.defineAccessor("context", { self(it, "context").context }, { h, v -> self(h, "context").context = v })
    proto.defineAccessor("stateful", { self(it, "stateful").stateful }, { h, v -> self(h, "stateful").stateful = jsTruthy(v) })
    proto.defineAccessor("duration", { FractionValue(bridge, self(it, "duration").toHap().duration) })
    proto.defineAccessor("endClipped", { FractionValue(bridge, self(it, "endClipped").toHap().endClipped) })

    method("show", 1) { h, a -> h.toHap().show(jsTruthy(a.getOrElse(0) { Undefined })) }
    method("showWhole", 1) { h, a -> h.toHap().showWhole(jsTruthy(a.getOrElse(0) { Undefined })) }
    method("toString", 0) { h, _ -> h.toHap().show() }
    method("hasOnset", 0) { h, _ -> h.toHap().hasOnset() }
    method("hasTag", 1) { h, a -> h.toHap().hasTag(realm.toStr(a.getOrElse(0) { Undefined })) }
    method("wholeOrPart", 0) { h, _ -> TimeSpanValue(bridge, h.toHap().wholeOrPart()) }
    method("withValue", 1) { h, a ->
        val f = a.getOrElse(0) { Undefined }
        HapValue(bridge, h.toHap().withValue { bridge.toKernel(bridge.callJs(f, bridge.toJs(it))) })
    }
    method("withSpan", 1) { h, a ->
        val f = a.getOrElse(0) { Undefined }
        HapValue(bridge, h.toHap().withSpan { bridge.spanOf(bridge.callJs(f, TimeSpanValue(bridge, it))) })
    }
    method("spanEquals", 1) { h, a -> h.toHap().spanEquals(bridge.hapOf(a.getOrElse(0) { Undefined })) }
    method("setContext", 1) { h, a ->
        val hap = h.toHap()
        HapValue(bridge, hap.setContext(bridge.contextFromJs(a.getOrElse(0) { Undefined }, hap.context)))
    }
    method("combineContext", 1) { h, a -> bridge.contextToJs(h.toHap().combineContext(bridge.hapOf(a.getOrElse(0) { Undefined }))) }
    method("ensureObjectValue", 0) { h, _ -> h.toHap().ensureObjectValue().let { h } }
    method("isActive", 1) { h, a -> h.toHap().isActive(number(a, 0)) }
    method("isInPast", 1) { h, a -> h.toHap().isInPast(number(a, 0)) }
    method("isInNearPast", 2) { h, a -> h.toHap().isInNearPast(number(a, 0), number(a, 1)) }
    method("isInFuture", 1) { h, a -> h.toHap().isInFuture(number(a, 0)) }
    method("isInNearFuture", 2) { h, a -> h.toHap().isInNearFuture(number(a, 0), number(a, 1)) }
    method("isWithinTime", 2) { h, a -> h.toHap().isWithinTime(number(a, 0), number(a, 1)) }

    val constructor = realm.constructor(
        "Hap", 5, proto,
        call = { _, args -> newHap(bridge, args) },
        construct = { args -> newHap(bridge, args) },
    )
    realm.defineGlobal("Hap", constructor)
}

/** `new Hap(whole, part, value, context = {}, stateful = false)`. */
private fun newHap(bridge: Bridge, args: List<Any?>): HapValue {
    fun arg(i: Int): Any? = args.getOrElse(i) { Undefined }
    val context = if (arg(3) === Undefined) HapContext.EMPTY else bridge.contextFromJs(arg(3), HapContext.EMPTY)
    return HapValue(bridge, Hap(bridge.spanOrNull(arg(0)), bridge.spanOf(arg(1)), bridge.toKernel(arg(2)), context, jsTruthy(arg(4))))
}

private fun installState(bridge: Bridge) {
    val realm = bridge.realm
    val proto = bridge.statePrototype

    fun self(v: Any?, method: String): State = (v as? StateValue)?.state ?: throw bridge.receiverError(v, "State", method)

    fun method(name: String, length: Int, impl: (State, List<Any?>) -> Any?) =
        realm.defineMethod(proto, name, length) { thisArg, args -> impl(self(thisArg, name), args) }

    proto.defineAccessor("span", { TimeSpanValue(bridge, self(it, "span").span) })
    proto.defineAccessor("controls", { bridge.toJs(self(it, "controls").controls) })

    method("setSpan", 1) { s, a -> StateValue(bridge, s.setSpan(bridge.spanOf(a.getOrElse(0) { Undefined }))) }
    method("withSpan", 1) { s, a ->
        val f = a.getOrElse(0) { Undefined }
        StateValue(bridge, s.withSpan { bridge.spanOf(bridge.callJs(f, TimeSpanValue(bridge, it))) })
    }
    method("setControls", 1) { s, a -> StateValue(bridge, s.setControls(bridge.controlsOf(a.getOrElse(0) { Undefined }))) }

    val constructor = realm.constructor(
        "State", 2, proto,
        call = { _, args -> newState(bridge, args) },
        construct = { args -> newState(bridge, args) },
    )
    realm.defineGlobal("State", constructor)
}

private fun newState(bridge: Bridge, args: List<Any?>): StateValue {
    val controls = args.getOrElse(1) { Undefined }
    return StateValue(bridge, State(bridge.spanOf(args.getOrElse(0) { Undefined }), if (controls === Undefined) emptyMap() else bridge.controlsOf(controls)))
}

// ---- context conversion --------------------------------------------------------------------------------------------------

/** The object a program sees for a [HapContext]: `{locations: [{start: {offset}, end: {offset}}], tags, ...the other keys}`. */
internal fun Bridge.contextToJs(c: HapContext): JsObject {
    val o = realm.newObject()
    o.defineOwn("locations", realm.newArray(c.locations.map { locationToJs(it) }))
    c.tags?.let { o.defineOwn("tags", realm.newArray(it)) }
    for ((key, value) in c.extra) o.defineOwn(key, toJs(value))
    return o
}

private fun Bridge.locationToJs(range: SourceRange): JsObject {
    fun position(offset: Int): JsObject = realm.newObject().also { it.defineOwn("offset", offset.toDouble()) }
    return realm.newObject().also {
        it.defineOwn("start", position(range.start))
        it.defineOwn("end", position(range.end))
    }
}

/** The [HapContext] a program's object stands for. Hooks cannot be made by a program, so [base]'s stay. */
internal fun Bridge.contextFromJs(v: Any?, base: HapContext): HapContext {
    if (v !is JsObject) throw realm.typeError("A hap context must be an object, not ${realm.describe(v)}")
    var locations = base.locations
    var tags = base.tags
    val extra = LinkedHashMap<String, Any?>()
    for (key in v.ownEnumerableKeys()) {
        val value = v.get(key)
        when (key) {
            "locations" -> locations = if (value is JsArray) value.items.mapNotNull(::rangeOf) else emptyList()
            "tags" -> tags = if (value is JsArray) value.items.map { realm.toStr(it) } else null
            else -> extra[key] = toKernel(value)
        }
    }
    return HapContext(locations, extra, tags, base.hooks, base.dominant)
}

private fun rangeOf(location: Any?): SourceRange? {
    if (location !is JsObject) return null
    val start = (location.get("start") as? JsObject)?.get("offset") as? Double ?: return null
    val end = (location.get("end") as? JsObject)?.get("offset") as? Double ?: return null
    return SourceRange(start.toInt(), end.toInt())
}

internal fun Bridge.hapOf(v: Any?): Hap = (v as? HapValue)?.toHap() ?: throw realm.typeError("${realm.describe(v)} is not a hap")
