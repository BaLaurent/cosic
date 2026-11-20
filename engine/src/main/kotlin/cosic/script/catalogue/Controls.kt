package cosic.script.catalogue

import cosic.pattern.ControlDomain
import cosic.pattern.ControlSpec
import cosic.pattern.Controls
import cosic.pattern.CosicError
import cosic.pattern.MODULATOR_KEYS
import cosic.pattern.Pattern
import cosic.pattern.Registry
import cosic.pattern.Undefined
import cosic.pattern.callMethod
import cosic.pattern.jsOrdered
import cosic.pattern.jsToString
import cosic.pattern.jsTruthy
import cosic.pattern.modulate
import cosic.pattern.pure
import cosic.pattern.reg2
import cosic.pattern.reify
import cosic.pattern.setWith
import cosic.script.Bridge
import cosic.script.js.JsArray
import cosic.script.js.JsObject
import cosic.script.js.NativeFunction
import cosic.script.js.PropFlags
import cosic.script.js.toNumber
import cosic.script.js.toStr

/*
 * The controls of `controls.mjs` (K5 of the parity plan): one function per name of [Controls], generated from its table, as a
 * method of patterns (`s("bd").lpf(800)`) and as a global function (`lpf(800)`, a pattern of its own), with the namespaces
 * Strudel has: `o` is a method only, `fmh1` is not the same function as a method and as a global, `ds` is not a control at all.
 * Also the functions of the file that are not registrations: `as`, `adsr`, `ad`, `ds`, `ar`, `control`, `sysex`, `scrub`, `lfo`,
 * `env`, `bmod` and `modulate` (see [cosic.pattern.modulate]), the eight distortion shortcuts of pattern.mjs (`soft`, `hard`, ...),
 * and what programs can use to make controls of their own: `createParam`, `createParams`, `registerControl`, `registerMultiControl`,
 * `isControlName` and `getControlName`.
 */

/** The `i`-th element of a list value: `undefined` when it is not there, as `const [a, b] = list` gives. */
private fun List<*>.at(i: Int): Any? = if (i < size) this[i] else Undefined

/** `Array.isArray(x) ? x : [x]` */
private fun asList(x: Any?): List<*> = x as? List<*> ?: listOf(x)

/** `pat.<control>(value)`, called as a method; the control must exist. */
private fun Pattern.control(name: String, value: Any?): Pattern =
    Controls.applyControl(checkNotNull(Controls.method(name)) { "no control '$name'" }, value, this)

internal fun registerControls() {
    val methodOnly = ArrayList<String>()
    val globalOnly = ArrayList<String>()
    for (name in (Controls.methodNames() + Controls.globalNames()).sorted()) {
        val method = Controls.method(name)
        val global = Controls.global(name)
        when {
            method != null && global != null -> registerControl(name, global, method)
            method != null -> methodOnly.add(name)
            else -> globalOnly.add(name)
        }
    }
    registerNonControls()
    catalogue("Controls") {
        for (name in methodOnly) {
            val spec = checkNotNull(Controls.method(name))
            method(listOf(name), 1) { ctx -> Controls.applyControl(spec, ctx.kernelArg(0), ctx.bridge.receiverOf(ctx.self, name)) }
        }
        for (name in globalOnly) {
            val spec = checkNotNull(Controls.global(name))
            function(listOf(name), 2) { ctx -> Controls.applyControl(spec, ctx.kernelArg(0), ctx.kernelArg(1)) }
        }
        // `export const { color, colour } = registerControl(['color', 'colour'])`: the second name is not registered, so it is an export that is undefined.
        global("colour") { Undefined }
        modulatorMethods()
        programControls()
    }
}

/**
 * A control that is both a method and a global function. Called as a global it takes a value and, optionally, the pattern to
 * set it on (`s("bd", pat)`); called as a method the receiver is the pattern. The method and the function are those of
 * their own registration, which differ for `fmh1` and `fmi1`.
 */
private fun registerControl(name: String, global: ControlSpec, method: ControlSpec) {
    Registry.dual(
        listOf(name),
        global = { a -> Controls.applyControl(global, a.getOrElse(0) { Undefined }, a.getOrNull(1)) },
        method = { pat, a -> Controls.applyControl(method, a.getOrElse(0) { Undefined }, pat) },
    )
}

// ---- the functions of controls.mjs that are not registrations ---------------------------------------------------------------

private fun registerNonControls() {
    // as(mapping): the values of the pattern, a list or a single value, named after the controls of the mapping
    Registry.reg2("as") { mapping, pat ->
        val names = asList(mapping)
        pat.fmap { v ->
            val values = asList(v)
            val out = LinkedHashMap<String, Any?>()
            for (i in names.indices) {
                val value = values.at(i)
                if (value !== Undefined) out[jsToString(canonicalName(names[i]))] = value
            }
            jsOrdered(out)
        }
    }

    // adsr([a, d, s, r]) sets all four, so a missing entry clears what was set before; the others set what they have
    Registry.reg2("adsr") { t, pat ->
        val l = asList(t)
        pat.setWith(pure(linkedMapOf("attack" to l.at(0), "decay" to l.at(1), "sustain" to l.at(2), "release" to l.at(3))))
    }
    Registry.reg2("ad") { t, pat ->
        val l = asList(t)
        val attack = l.at(0)
        pat.control("attack", attack).control("decay", l.at(1).let { if (it === Undefined) attack else it })
    }
    Registry.reg2("ds") { t, pat ->
        val l = asList(t)
        pat.setWith(pure(linkedMapOf("decay" to l.at(0), "sustain" to l.at(1).let { if (it === Undefined) 0.0 else it })))
    }
    Registry.reg2("ar") { t, pat ->
        val l = asList(t)
        val attack = l.at(0)
        pat.setWith(pure(linkedMapOf("attack" to attack, "release" to l.at(1).let { if (it === Undefined) attack else it })))
    }

    Registry.reg2("control") { args, pat ->
        if (args !is List<*>) throw CosicError("control expects an array of [ccn, ccv]")
        pat.control("ccn", args.at(0)).control("ccv", args.at(1))
    }
    Registry.reg2("sysex") { args, pat ->
        if (args !is List<*>) throw CosicError("sysex expects an array of [id, data]")
        pat.control("sysexid", args.at(0)).control("sysexdata", args.at(1))
    }

    // scrub(begin, [begin, speed]): plays the sample from the position (and at the speed) of the pattern, cut at the end of each step
    Registry.register(listOf("scrub"), 2, patternify = false) { a ->
        val beginPat = a[0] as? Pattern ?: throw CosicError("beginPat.outerBind is not a function")
        beginPat.outerBind { v ->
            val l = asList(v)
            val speedMultiplier = l.at(1).let { if (it === Undefined) 1.0 else it }
            val speed = Controls.applyControl(checkNotNull(Controls.method("speed")), speedMultiplier, null)
            (a[1] as Pattern).control("begin", l.at(0)).callMethod("mul", speed).control("clip", 1.0)
        }
    }

    // soft(amount) is distort([amount, 1, "soft"]), and so on
    for (name in DISTORTION_SHORTCUTS) {
        val distort = checkNotNull(Controls.method("distort"))
        fun shortcut(args: Any?, pat: Any?): Pattern {
            val argsPat = reify(args).fmap { v -> if (v is List<*>) v + name else listOf(v, 1.0, name) }
            return Controls.applyControl(distort, argsPat, if (jsTruthy(pat)) pat else pure(LinkedHashMap<String, Any?>()))
        }
        Registry.dual(
            listOf(name),
            global = { a -> shortcut(a.getOrElse(0) { Undefined }, a.getOrNull(1)) },
            method = { pat, a -> shortcut(a.getOrElse(0) { Undefined }, pat) },
        )
    }

    // lfo(config) is pure({}).lfo(config)
    for (type in MODULATOR_KEYS) {
        Registry.dual(
            listOf(type),
            global = { a -> pure(LinkedHashMap<String, Any?>()).modulate(type, a.getOrNull(0), Undefined) },
            method = { pat, a -> pat.modulate(type, a.getOrElse(0) { Undefined }, a.getOrElse(1) { Undefined }) },
        )
    }
}

private val DISTORTION_SHORTCUTS = listOf("soft", "hard", "cubic", "diode", "asym", "fold", "sinefold", "chebyshev")

/** `getControlName(x)` for any value: only a string can be a control name. */
private fun canonicalName(x: Any?): Any? = if (x is String) Controls.canonicalName(x) else x

// ---- what a program can do with controls --------------------------------------------------------------------------------------

private fun CatalogueBuilder.modulatorMethods() {
    method(listOf("modulate"), 3) { ctx ->
        ctx.bridge.receiverOf(ctx.self, "modulate").modulate(jsToString(ctx.kernelArg(0)), ctx.kernelArg(1), ctx.kernelArg(2))
    }
}

/** The function a control is: `func(value, pat)` of `createParam`, which `Pattern.prototype[name]` is a method of. */
private fun controlFunction(bridge: Bridge, spec: ControlSpec): NativeFunction =
    bridge.realm.functionAt(spec.name, 2) { _, args, site ->
        bridge.result(Controls.applyControl(spec, bridge.toKernel(args.getOrElse(0) { Undefined }), bridge.toKernel(args.getOrNull(1))), site)
    }

/** `Pattern.prototype[spec.name]`: the control as a method of this realm's patterns. */
private fun defineMethod(bridge: Bridge, spec: ControlSpec): NativeFunction {
    val method = bridge.realm.functionAt(spec.name, 1) { self, args, site ->
        val pat = bridge.receiverOf(self, spec.name)
        bridge.result(Controls.applyControl(spec, bridge.toKernel(args.getOrElse(0) { Undefined }), pat), site)
    }
    bridge.realm.patternPrototype.defineOwn(spec.name, method, PropFlags.HIDDEN)
    return method
}

private fun namesOf(bridge: Bridge, names: Any?): Pair<List<String>, Boolean> {
    val keys = asNames(bridge, names)
    if (keys.isEmpty()) throw bridge.realm.typeError("Cannot read properties of undefined (reading 'length')")
    return keys to (names is JsArray)
}

private fun CatalogueBuilder.programControls() {
    // createParam(names): a control that is not registered as a control name
    function(listOf("createParam"), 1) { ctx ->
        val (keys, multi) = namesOf(ctx.bridge, ctx.arg(0))
        val spec = ControlSpec(keys, multi, emptyList(), emptySet(), ControlDomain.UNDOCUMENTED, 0)
        defineMethod(ctx.bridge, spec)
        controlFunction(ctx.bridge, spec)
    }
    function(listOf("createParams"), 0) { ctx ->
        val out = ctx.realm.newObject()
        for (name in ctx.args) {
            val key = ctx.realm.toStr(name)
            val spec = ControlSpec(listOf(key), false, emptyList(), emptySet(), ControlDomain.UNDOCUMENTED, 0)
            defineMethod(ctx.bridge, spec)
            out.defineOwn(key, controlFunction(ctx.bridge, spec))
        }
        out
    }
    // registerControl(names, ...aliases): the function under its name and under every alias, and every one of them a control name from now on
    function(listOf("registerControl"), 1) { ctx ->
        val (keys, multi) = namesOf(ctx.bridge, ctx.arg(0))
        val aliases = ctx.args.drop(1).map { ctx.realm.toStr(it) }
        customControl(ctx.bridge, keys, multi, aliases, ctx.realm.newObject())
    }
    // registerMultiControl(names, maxControls, ...aliases): `fm`, `fm2`, ... as the file makes its FM controls
    function(listOf("registerMultiControl"), 2) { ctx ->
        val names = asNames(ctx.bridge, ctx.arg(0))
        val max = ctx.realm.toNumber(ctx.arg(1)).let { if (it.isNaN()) 0 else it.toInt() }
        val aliases = ctx.args.drop(2).map { ctx.realm.toStr(it) }
        val bag = ctx.realm.newObject()
        for (i in 1..max) {
            val (theseNames, theseAliases) = if (i == 1) {
                names to (aliases + aliases.map { "${it}1" } + names.map { "${it}1" })
            } else {
                names.map { "$it$i" } to aliases.map { "$it$i" }
            }
            customControl(ctx.bridge, theseNames, true, theseAliases, bag)
        }
        bag
    }
    function(listOf("isControlName"), 1) { ctx -> (ctx.arg(0) as? String)?.let { Controls.isControlName(it) } ?: false }
    function(listOf("getControlName"), 1) { ctx -> ctx.arg(0).let { if (it is String) Controls.canonicalName(it) else it } }
}

private fun asNames(bridge: Bridge, names: Any?): List<String> = if (names is JsArray) names.items.map { bridge.realm.toStr(it) } else listOf(bridge.realm.toStr(names))

/** One `registerControl` call: the control under its name and its aliases in [bag], on `Pattern.prototype`, and as control names. */
private fun customControl(bridge: Bridge, keys: List<String>, multi: Boolean, aliases: List<String>, bag: JsObject): JsObject {
    val spec = Controls.registerCustom(keys, multi, aliases)
    val func = controlFunction(bridge, spec)
    val method = defineMethod(bridge, spec)
    bag.defineOwn(spec.name, func)
    for (alias in aliases) {
        bag.defineOwn(alias, func)
        bridge.realm.patternPrototype.defineOwn(alias, method, PropFlags.HIDDEN)
    }
    return bag
}
