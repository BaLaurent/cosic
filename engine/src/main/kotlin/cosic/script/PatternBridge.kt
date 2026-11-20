package cosic.script

import cosic.SourceRange
import cosic.pattern.CosicError
import cosic.pattern.Hap
import cosic.pattern.HapContext
import cosic.pattern.JsFn
import cosic.pattern.Pattern
import cosic.pattern.Rational
import cosic.pattern.Registered
import cosic.pattern.ScheduleHook
import cosic.pattern.State
import cosic.pattern.TOO_DEEP
import cosic.pattern.TOO_DENSE
import cosic.pattern.TimeSpan
import cosic.pattern.Undefined
import cosic.pattern.alignHook
import cosic.pattern.errorLogger
import cosic.pattern.jsTruthy
import cosic.pattern.patternOf
import cosic.script.js.JsArray
import cosic.script.js.JsFunction
import cosic.script.js.JsObject
import cosic.script.js.NativeFunction
import cosic.script.js.Prop
import cosic.script.js.PropFlags
import cosic.script.js.describe
import cosic.script.js.toStr
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * The bridge between the pattern kernel and the interpreter of one [Realm] (K3, K4 of the parity plan).
 *
 * The kernel works on its own values (`Pattern`, `Hap`, `Rational`, `List`, `Map`, `JsFn`); a program works on objects
 * ([JsObject], [JsArray], [JsFunction]) and primitives. The bridge converts at every crossing:
 *
 *  - [toKernel] (program to kernel): `Double`, `String`, `Boolean`, `null` and `undefined` stay; a pattern, fraction, span,
 *    hap or state wrapper becomes the kernel object it holds; an array becomes a `List`, a plain object a `LinkedHashMap`
 *    in the order JavaScript lists its keys (K1), a function a [JsFnAdapter] that converts what the kernel passes to it.
 *  - [toJs] (kernel to program): the reverse.
 *
 * Every function of the [Registry] is made available two ways (K4): as a global (curried when it is a registered function,
 * so `fast(2)` is a function awaiting the pattern) and as a method of `Pattern.prototype`; operators carry their
 * alignments (`pat.add.out(...)`). Patterns that native calls return are wrapped in [attributing], so an error raised
 * while the scheduler queries them points at the call of the program that made them.
 */
internal class Bridge private constructor(@JvmField val realm: Realm) {
    @JvmField val fractionPrototype: JsObject = realm.newSharedObject()
    @JvmField val timeSpanPrototype: JsObject = realm.newSharedObject()
    @JvmField val hapPrototype: JsObject = realm.newSharedObject()
    @JvmField val statePrototype: JsObject = realm.newSharedObject()

    /** The object `register` and `evalScope` fill: it inherits the global object, so everything that is global is in it too. */
    @JvmField val strudelScope: JsObject = realm.newSharedObject(realm.global)

    init {
        installWrapperClasses(this)
        installPatternClass(this)
        realm.defineGlobal("strudelScope", strudelScope)
    }

    // ---- values -------------------------------------------------------------------------------------------------------

    fun wrap(pattern: Pattern): PatternValue = PatternValue(this, pattern)

    /** A value of the program as the kernel wants it (see the class description). */
    fun toKernel(v: Any?): Any? = toKernel(v, 0)

    private fun toKernel(v: Any?, depth: Int): Any? = when (v) {
        null, Undefined, is Boolean, is Double, is String -> v
        is PatternValue -> v.pattern
        is FractionValue -> v.value
        is TimeSpanValue -> v.span
        is HapValue -> v.toHap()
        is StateValue -> v.state
        is KernelFunction -> v.fn
        is JsFunction -> JsFnAdapter(this, v)
        is JsArray -> {
            if (depth >= MAX_NESTING) throw nestingError()
            val out = ArrayList<Any?>(v.items.size)
            for (item in v.items) out.add(toKernel(item, depth + 1))
            out
        }
        is JsObject -> if (v.className == "Object") {
            if (depth >= MAX_NESTING) throw nestingError()
            val keys = v.ownEnumerableKeys()
            val out = LinkedHashMap<String, Any?>(keys.size * 2)
            for (key in keys) out[key] = toKernel(v.get(key), depth + 1)
            out
        } else {
            v
        }
        is Number -> v.toDouble()
        else -> v
    }

    /** A value of the kernel as the program sees it (see the class description). */
    fun toJs(v: Any?): Any? = when (v) {
        null, Undefined, is Boolean, is Double, is String -> v
        is Pattern -> wrap(v)
        is Rational -> FractionValue(this, v)
        is TimeSpan -> TimeSpanValue(this, v)
        is Hap -> HapValue(this, v)
        is State -> StateValue(this, v)
        is JsFnAdapter -> v.fn
        is JsObject -> v
        is JsFn -> KernelFunction(this, "", v)
        is List<*> -> realm.newArray(v.map { toJs(it) })
        is Map<*, *> -> {
            val o = realm.newObject()
            for ((key, value) in v) o.defineOwn(key.toString(), toJs(value))
            o
        }
        is Number -> v.toDouble()
        else -> v
    }

    /**
     * What a native function hands back to the program: [toJs], and a pattern is attributed to the call at [site] (null for
     * a call native code makes), so that an error thrown while it is queried is reported at that call.
     */
    fun result(v: Any?, site: Node?): Any? =
        if (v is Pattern) wrap(if (site == null) v else attributing(v, site.range)) else toJs(v)

    /** `f(...args)` for a function of the program or the kernel; a value that is not a function is a `TypeError`. */
    fun callJs(f: Any?, vararg args: Any?): Any? = realm.callFunction(f, Undefined, args.asList())

    fun spanOf(v: Any?): TimeSpan = (v as? TimeSpanValue)?.span ?: throw realm.typeError("${realm.describe(v)} is not a time span")

    /** A span, or null for `undefined` and `null` (the whole of a continuous hap). */
    fun spanOrNull(v: Any?): TimeSpan? = if (v === Undefined || v == null) null else spanOf(v)

    fun patternArg(v: Any?): Pattern = (v as? PatternValue)?.pattern ?: throw realm.typeError("${realm.describe(v)} is not a pattern")

    /** The control map a program's object stands for (`State.controls`). */
    fun controlsOf(v: Any?): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return toKernel(v) as? Map<String, Any?> ?: throw realm.typeError("${realm.describe(v)} is not an object")
    }

    fun hapsToJs(haps: List<Hap>): JsArray = realm.newArray(haps.map { HapValue(this, it) })

    fun hapsOf(v: Any?): List<Hap> {
        if (v !is JsArray) throw realm.typeError("${realm.describe(v)} is not an array of haps")
        return v.items.map { hapOf(it) }
    }

    private fun nestingError() = CosicError("A value in a pattern nests too deeply, or contains itself")

    // ---- the catalogue ---------------------------------------------------------------------------------------------------

    /** Makes [reg] a global function and a method of `Pattern.prototype` (see the class description); [global] false leaves out the global. */
    fun installRegistered(reg: Registered, global: Boolean = true) {
        val name = reg.name
        if (global) realm.defineGlobal(name, globalFunction(reg))
        val proto = realm.patternPrototype
        if (reg.alignable) {
            proto.defineAccessor(name, { self -> AlignedFunction(this, reg, self) }, null, PropFlags.CONFIGURABLE)
        } else if (reg.hasMethod) {
            proto.defineOwn(name, methodFunction(reg), PropFlags.HIDDEN)
        }
        if (reg.hasPlain) proto.defineOwn("_$name", plainFunction(reg), PropFlags.HIDDEN)
    }

    fun globalFunction(reg: Registered): NativeFunction =
        realm.functionAt(reg.name, 0) { _, args, site -> result(reg.global(args.map { toKernel(it) }), site) }

    fun methodFunction(reg: Registered): NativeFunction =
        realm.functionAt(reg.name, 0) { self, args, site -> result(reg.method(receiverOf(self, reg.name), args.map { toKernel(it) }), site) }

    private fun plainFunction(reg: Registered): NativeFunction =
        realm.functionAt("_" + reg.name, 0) { self, args, site -> result(reg.plain(receiverOf(self, reg.name), args.map { toKernel(it) }), site) }

    /** The pattern a method works on; a method called on anything else (`Pattern.prototype.fast.call(3)`) is a `TypeError`. */
    fun receiverOf(self: Any?, name: String): Pattern =
        (self as? PatternValue)?.pattern
            ?: throw realm.typeError("Pattern.prototype.$name called on incompatible receiver ${realm.describe(self)}")

    /**
     * The JavaScript `register(name, func, patternify = true, preserveSteps = false, join = x => x.innerJoin())`
     * (pattern.mjs:2017-2111): makes `Pattern.prototype[name]` (and `_name` when `func` has more than the pattern as a
     * parameter) of this realm and returns the curried function. Unlike the entries of the [Registry] it belongs to this
     * realm alone, and it is not a global (`strudelScope[name]` holds it).
     */
    fun registerUser(args: List<Any?>): Any? {
        fun arg(i: Int): Any? = args.getOrElse(i) { Undefined }
        val name = arg(0)
        if (name is PatternValue) {
            throw CosicError("Name argument for register is a pattern, try using single quotes ('name') instead of double quotes (\"name\")")
        }
        if (name is JsArray) {
            val result = realm.newObject()
            for (item in name.items) result.defineOwn(realm.toStr(item), registerUser(listOf(item, arg(1), arg(2), arg(3), arg(4))))
            return result
        }
        val func = arg(1)
        val arity = when (func) {
            is JsFunction -> func.length
            is JsFn -> func.arity
            else -> throw realm.typeError("${realm.describe(func)} is not a function")
        }
        val patternify = if (arg(2) === Undefined) true else jsTruthy(arg(2))
        val preserveSteps = jsTruthy(arg(3))
        val joinFunction = arg(4)
        val join: (Pattern) -> Pattern = if (joinFunction === Undefined) {
            { it.innerJoin() }
        } else {
            { p -> patternOf(toKernel(callJs(joinFunction, wrap(p)))) }
        }
        val key = realm.toStr(name)
        val reg = Registered(listOf(key), arity, patternify, preserveSteps, join, { a -> toKernel(callJs(func, *a.map { toJs(it) }.toTypedArray())) })
        val proto = realm.patternPrototype
        proto.defineOwn(key, methodFunction(reg), PropFlags.HIDDEN)
        if (reg.hasPlain) proto.defineOwn("_$key", plainFunction(reg), PropFlags.HIDDEN)
        val curried = globalFunction(reg)
        strudelScope.defineOwn(key, curried)
        return curried
    }

    companion object {
        private const val MAX_NESTING = 64

        private val bridges = WeakHashMap<Realm, WeakReference<Bridge>>()

        /**
         * The bridge of [realm], made on first use. The map holds the bridge weakly: everything the realm made refers to
         * it, so it lives as long as the realm does, and a realm nobody uses any more can be collected.
         */
        fun of(realm: Realm): Bridge = synchronized(bridges) {
            bridges[realm]?.get() ?: Bridge(realm).also { bridges[realm] = WeakReference(it) }
        }
    }
}

internal val Realm.bridge: Bridge get() = Bridge.of(this)

// ---- functions across the boundary -----------------------------------------------------------------------------------------

/**
 * A function of the program as the kernel calls it: the arguments the kernel passes are converted for the program and the
 * result for the kernel. [arity] is `fn.length`, which `register` reads. The program gets [fn] itself back when the kernel
 * hands the function to it again ([Bridge.toJs]).
 */
internal class JsFnAdapter(private val bridge: Bridge, @JvmField val fn: JsFunction) : JsFn {
    override val arity: Int get() = fn.length

    override fun call(args: List<Any?>): Any? {
        val converted = ArrayList<Any?>(args.size)
        for (a in args) converted.add(bridge.toJs(a))
        return bridge.toKernel(fn.call(Undefined, converted))
    }
}

/** A function of the kernel as the program calls it (a partially applied function, `fast(2)`): the reverse of [JsFnAdapter]. */
internal class KernelFunction(private val bridge: Bridge, name: String, @JvmField val fn: JsFn) :
    JsFunction(bridge.realm, name, fn.arity) {
    override fun call(thisArg: Any?, args: List<Any?>): Any? = callAt(thisArg, args, null)

    override fun callAt(thisArg: Any?, args: List<Any?>, site: Node?): Any? =
        bridge.result(fn.call(args.map { bridge.toKernel(it) }), site)
}

/**
 * `pat.add`: the method, and, as properties of it, the same operator with each alignment, `pat.add.out(x)` (pattern.mjs:
 * 1389-1432). The properties are made when they are read.
 */
internal class AlignedFunction(private val bridge: Bridge, private val reg: Registered, private val self: Any?) :
    JsFunction(bridge.realm, reg.name, 0) {
    override fun call(thisArg: Any?, args: List<Any?>): Any? = callAt(thisArg, args, null)

    override fun callAt(thisArg: Any?, args: List<Any?>, site: Node?): Any? =
        bridge.result(reg.method(bridge.receiverOf(self, reg.name), args.map { bridge.toKernel(it) }), site)

    override fun getOwnProp(key: String): Prop? {
        if (key in ALIGNMENTS) return Prop(alignment(key), PropFlags.HIDDEN)
        return super.getOwnProp(key)
    }

    override fun ownKeys(): List<String> = ALIGNMENTS + super.ownKeys()

    private fun alignment(how: String): NativeFunction = bridge.realm.functionAt(how, 0) { _, args, site ->
        val hook = alignHook ?: throw bridge.realm.typeError("alignment '$how' is not available")
        bridge.result(hook(bridge.receiverOf(self, reg.name), reg.name, how, args.map { bridge.toKernel(it) }), site)
    }

    private companion object {
        val ALIGNMENTS = listOf("in", "out", "mix", "squeeze", "squeezein", "squeezeout", "reset", "restart", "poly")
    }
}

/**
 * [pattern] with the errors it throws while it is queried given [range] (when they have none): the place of the call of
 * the program that made the pattern. Steps and the constant value a pattern may hold are kept, so `register` still
 * treats `"2"` as the number it is.
 */
internal fun attributing(pattern: Pattern, range: SourceRange): Pattern = Pattern(
    { state ->
        try {
            pattern.query(state)
        } catch (e: CosicError) {
            throw e.withRange(range)
        }
    },
    pattern.steps, pattern.pureValue, pattern.pureLoc,
)

// ---- the members of a pattern that are not registered functions --------------------------------------------------------------

/** `Pattern` and the methods of `Pattern.prototype` that pattern.mjs defines in the class itself (not with `register`). */
private fun installPatternClass(bridge: Bridge) {
    val realm = bridge.realm
    val proto = realm.patternPrototype

    fun method(name: String, length: Int, impl: (Pattern, List<Any?>, Node?) -> Any?) {
        proto.defineOwn(
            name,
            realm.functionAt(name, length) { self, args, site -> bridge.result(impl(bridge.receiverOf(self, name), args, site), site) },
            PropFlags.HIDDEN,
        )
    }

    fun arg(args: List<Any?>, i: Int): Any? = args.getOrElse(i) { Undefined }

    fun hap(h: Hap): HapValue = HapValue(bridge, h)

    fun fraction(v: Any?): Rational = try {
        Rational.of(bridge.toKernel(v))
    } catch (e: CosicError) {
        throw realm.typeError("${realm.describe(v)} is not a fraction")
    }

    /** A function of the program called with kernel values, giving a kernel value. */
    fun call(f: Any?, vararg args: Any?): Any? = bridge.toKernel(bridge.callJs(f, *args.map { bridge.toJs(it) }.toTypedArray()))

    fun patternCall(f: Any?, vararg args: Any?): Pattern {
        val r = call(f, *args)
        return r as? Pattern ?: throw realm.typeError("${realm.describe(bridge.toJs(r))} is not a pattern")
    }

    proto.defineOwn("_Pattern", true, PropFlags.HIDDEN)
    proto.defineAccessor("_steps", { self -> (self as? PatternValue)?.pattern?.steps?.let { FractionValue(bridge, it) } ?: Undefined }, null, PropFlags.CONFIGURABLE)
    proto.defineAccessor("hasSteps", { self -> (self as? PatternValue)?.pattern?.hasSteps ?: false }, null, PropFlags.CONFIGURABLE)
    proto.defineAccessor("__pure", { self -> bridge.toJs((self as? PatternValue)?.pattern?.pureValue ?: Undefined) }, null, PropFlags.CONFIGURABLE)

    // functor, applicative, monad
    method("withValue", 1) { p, a, _ -> val f = arg(a, 0); p.withValue { call(f, it) } }
    method("fmap", 1) { p, a, _ -> val f = arg(a, 0); p.fmap { call(f, it) } }
    method("withState", 1) { p, a, _ ->
        val f = arg(a, 0)
        p.withState { s ->
            (bridge.callJs(f, StateValue(bridge, s)) as? StateValue)?.state ?: throw realm.typeError("withState: the function must return a state")
        }
    }
    method("appWhole", 2) { p, a, _ ->
        val f = arg(a, 0)
        p.appWhole(
            { x, y -> bridge.spanOrNull(bridge.callJs(f, x?.let { TimeSpanValue(bridge, it) } ?: Undefined, y?.let { TimeSpanValue(bridge, it) } ?: Undefined)) },
            bridge.patternArg(arg(a, 1)),
        )
    }
    method("appBoth", 1) { p, a, _ -> p.appBoth(bridge.patternArg(arg(a, 0))) }
    method("appLeft", 1) { p, a, _ -> p.appLeft(bridge.patternArg(arg(a, 0))) }
    method("appRight", 1) { p, a, _ -> p.appRight(bridge.patternArg(arg(a, 0))) }
    method("bind", 1) { p, a, _ -> val f = arg(a, 0); p.bind { patternCall(f, it) } }
    method("join", 0) { p, _, _ -> p.join() }
    method("outerBind", 1) { p, a, _ -> val f = arg(a, 0); p.outerBind { patternCall(f, it) } }
    method("outerJoin", 0) { p, _, _ -> p.outerJoin() }
    method("innerBind", 1) { p, a, _ -> val f = arg(a, 0); p.innerBind { patternCall(f, it) } }
    method("innerJoin", 0) { p, _, _ -> p.innerJoin() }
    method("resetJoin", 1) { p, a, _ -> p.resetJoin(jsTruthy(arg(a, 0))) }
    method("restartJoin", 0) { p, _, _ -> p.restartJoin() }
    method("squeezeJoin", 0) { p, _, _ -> p.squeezeJoin() }
    method("squeezeBind", 1) { p, a, _ -> val f = arg(a, 0); p.squeezeBind { patternCall(f, it) } }
    method("polyJoin", 0) { p, _, _ -> p.polyJoin() }
    method("polyBind", 1) { p, a, _ -> val f = arg(a, 0); p.polyBind { patternCall(f, it) } }
    method("stepJoin", 0) { p, _, _ -> p.stepJoin() }
    method("stepBind", 1) { p, a, _ -> val f = arg(a, 0); p.stepBind { patternCall(f, it) } }

    // steps
    method("setSteps", 1) { p, a, _ -> p.setSteps(if (arg(a, 0) === Undefined || arg(a, 0) == null) null else fraction(arg(a, 0))) }
    method("withSteps", 1) { p, a, _ -> val f = arg(a, 0); p.withSteps { r -> fraction(bridge.callJs(f, FractionValue(bridge, r))) } }

    // querying and haps
    method("queryArc", 3) { p, a, _ ->
        val controls = if (arg(a, 2) === Undefined) emptyMap() else bridge.controlsOf(arg(a, 2))
        bridge.hapsToJs(
            try {
                p.queryArc(fraction(arg(a, 0)), fraction(arg(a, 1)), controls)
            } catch (e: CosicError) {
                // queryArc of pattern.mjs logs a failing query and answers nothing; running out of budget is no such failure.
                if (e.message == TOO_DENSE || e.message == TOO_DEEP) throw e
                errorLogger(e)
                emptyList()
            },
        )
    }
    method("firstCycle", 1) { p, a, _ -> bridge.hapsToJs(p.firstCycle(jsTruthy(arg(a, 0)))) }
    method("splitQueries", 0) { p, _, _ -> p.splitQueries() }
    method("withQuerySpan", 1) { p, a, _ -> val f = arg(a, 0); p.withQuerySpan { bridge.spanOf(bridge.callJs(f, TimeSpanValue(bridge, it))) } }
    method("withQuerySpanMaybe", 1) { p, a, _ ->
        val f = arg(a, 0)
        p.withQuerySpanMaybe { bridge.spanOrNull(bridge.callJs(f, TimeSpanValue(bridge, it))) }
    }
    method("withQueryTime", 1) { p, a, _ -> val f = arg(a, 0); p.withQueryTime { fraction(bridge.callJs(f, FractionValue(bridge, it))) } }
    method("withHapSpan", 1) { p, a, _ -> val f = arg(a, 0); p.withHapSpan { bridge.spanOf(bridge.callJs(f, TimeSpanValue(bridge, it))) } }
    method("withHapTime", 1) { p, a, _ -> val f = arg(a, 0); p.withHapTime { fraction(bridge.callJs(f, FractionValue(bridge, it))) } }
    method("withHaps", 1) { p, a, _ ->
        val f = arg(a, 0)
        p.withHaps { haps, state -> bridge.hapsOf(bridge.callJs(f, bridge.hapsToJs(haps), StateValue(bridge, state))) }
    }
    method("withHap", 1) { p, a, _ ->
        val f = arg(a, 0)
        p.withHap { h -> bridge.hapOf(bridge.callJs(f, hap(h))) }
    }
    method("filterHaps", 1) { p, a, _ -> val f = arg(a, 0); p.filterHaps { jsTruthy(bridge.callJs(f, hap(it))) } }
    method("filterValues", 1) { p, a, _ -> val f = arg(a, 0); p.filterValues { jsTruthy(bridge.callJs(f, bridge.toJs(it))) } }
    method("removeUndefineds", 0) { p, _, _ -> p.removeUndefineds() }
    method("onsetsOnly", 0) { p, _, _ -> p.onsetsOnly() }
    method("discreteOnly", 0) { p, _, _ -> p.discreteOnly() }
    method("sortHapsByPart", 0) { p, _, _ -> p.sortHapsByPart() }
    method("asNumber", 0) { p, _, _ -> p.asNumber() }

    // context
    method("setContext", 1) { p, a, _ -> p.setContext(bridge.contextFromJs(arg(a, 0), HapContext.EMPTY)) }
    method("withContext", 1) { p, a, _ ->
        val f = arg(a, 0)
        p.withContext { c -> bridge.contextFromJs(bridge.callJs(f, bridge.contextToJs(c)), c) }
    }
    method("stripContext", 0) { p, _, _ -> p.stripContext() }

    // scheduling
    method("onSchedule", 2) { p, a, _ ->
        val f = arg(a, 0)
        val dominant = if (arg(a, 1) === Undefined) true else jsTruthy(arg(a, 1))
        p.onSchedule(ScheduleHook { sh -> bridge.callJs(f, hap(sh.hap), sh.targetTimeSec, sh.cps) }, dominant)
    }
    method("log", 2) { p, a, _ ->
        val func = arg(a, 0)
        if (func === Undefined) {
            p.log()
        } else {
            p.log(
                func = { sh -> realm.toStr(bridge.callJs(func, hap(sh.hap), sh.targetTimeSec, sh.cps)) },
                getData = { sh -> bridge.toKernel(callGetData(bridge, arg(a, 1), sh.hap)) },
            )
        }
    }
    method("logValues", 1) { p, a, _ ->
        val func = arg(a, 0)
        if (func === Undefined) p.logValues() else p.logValues { realm.toStr(bridge.callJs(func, bridge.toJs(it))) }
    }

    val constructor = realm.constructor(
        "Pattern", 2, proto,
        call = { _, args -> bridge.wrap(newPattern(bridge, args)) },
        construct = { args -> bridge.wrap(newPattern(bridge, args)) },
    )
    realm.defineGlobal("Pattern", constructor)
}

/** `getData` of `log`: a function of the hap that gives the data the console shows with the line, `{hap}` by default. */
private fun callGetData(bridge: Bridge, getData: Any?, hap: Hap): Any? =
    if (getData === Undefined) bridge.toJs(hap) else bridge.callJs(getData, HapValue(bridge, hap))

/** `new Pattern(query, steps)`: [query] is a function from a state to an array of haps. */
private fun newPattern(bridge: Bridge, args: List<Any?>): Pattern {
    val query = args.getOrElse(0) { Undefined }
    val steps = args.getOrElse(1) { Undefined }
    return Pattern(
        { state -> bridge.hapsOf(bridge.callJs(query, StateValue(bridge, state))) },
        if (steps === Undefined || steps == null) null else Rational.of(bridge.toKernel(steps)),
    )
}
