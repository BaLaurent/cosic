package cosic.pattern

/*
 * `lfo`, `env` and `bmod` of controls.mjs (3036-3287): they do not make sound by themselves, they attach to the control set just
 * before them a modulator, as a nested object in the control map (`s("saw").lpf(500).lfo({rate: 4})` gives
 * `lfo:{0:{control:cutoff rate:4} __ids:{}}`), which the audio engine builds an oscillator, envelope or bus read from.
 */

/** The three kinds of modulator, which are also the keys of the control map that hold them. */
internal val MODULATOR_KEYS = listOf("lfo", "env", "bmod")

/**
 * The short names of the options of each modulator (`registerSubControls`): `{r: 4}` is `{rate: 4}`. Keys are compared in
 * lower case, so `{SC: x}` is a `subControl`.
 */
private val SUB_CONTROL_ALIASES: Map<String, Map<String, String>> = buildMap {
    fun register(control: String, vararg subControls: List<String>) {
        val aliasMap = HashMap<String, String>()
        for (names in subControls) for (alias in names) aliasMap[alias.lowercase()] = names[0]
        put(control, aliasMap)
    }
    register(
        "lfo",
        listOf("control", "c"), listOf("subControl", "sc"), listOf("rate", "r"), listOf("depth", "dep", "dr"), listOf("depthabs", "da"),
        listOf("dcoffset", "dc"), listOf("shape", "sh"), listOf("skew", "sk"), listOf("curve", "cu"), listOf("sync", "s"),
        listOf("retrig", "rt"), listOf("fxi"),
    )
    register(
        "env",
        listOf("control", "c"), listOf("subControl", "sc"), listOf("attack", "att", "a"), listOf("decay", "dec", "d"),
        listOf("sustain", "sus", "s"), listOf("release", "rel", "r"), listOf("depth", "dep", "dr"), listOf("depthabs", "da"),
        listOf("acurve", "ac"), listOf("dcurve", "dc"), listOf("rcurve", "rc"), listOf("fxi"),
    )
    register(
        "bmod",
        listOf("bus", "b"), listOf("control", "c"), listOf("subControl", "sc"), listOf("depth", "dep", "dr"), listOf("depthabs", "da"),
        listOf("dc"), listOf("fxi"),
    )
}

private fun mainSubControlName(control: String, subKey: String): String =
    SUB_CONTROL_ALIASES[control]?.get(subKey.lowercase()) ?: subKey

/** What flows through the chain of `appLeft`s that `modulate` builds: the control map and the id of the modulator being set up. */
private class Modulation(val v: MutableMap<String, Any?>, val id: Any?)

/**
 * `pat.modulate(type, config, idPat)`: sets the options of the modulator of kind [type] (`lfo`, `env` or `bmod`) that the
 * entries of [config] describe, one pattern per option. The modulator belongs to the control set last on the value (it
 * becomes its `control`) unless `control` says otherwise; [idPat] numbers it, by default one after the last. Like the
 * original, it adds to the control map in place.
 */
internal fun Pattern.modulate(type: String, config: Any?, idPat: Any?): Pattern {
    if (type !in MODULATOR_KEYS) {
        logger("[core] Modulation type $type not found. Please use one of 'lfo', 'env', 'bmod'", null, null)
        return this
    }
    // { control: undefined, ...config }
    val options = LinkedHashMap<String, Any?>()
    options["control"] = Undefined
    if (config is Map<*, *>) for ((key, value) in config) options[key.toString()] = value

    // The first modulation of the first value sets the control that the modulator defaults to, for the rest to share.
    var defaultValue: Any? = Undefined
    var output = fmap { v -> jsFn(1) { a -> Modulation(mutableValue(type, v), a.getOrElse(0) { Undefined }) } }.appLeft(reify(idPat))
    for (rawKey in jsKeyOrder(options.keys)) {
        val key = mainSubControlName(type, rawKey)
        output = output.fmap { modulation ->
            jsFn(1) { a ->
                val (v, id0) = (modulation as Modulation).let { it.v to it.id }
                val c = a.getOrElse(0) { Undefined }
                if (defaultValue === Undefined) {
                    // pat.gain(0.5).lfo({..}) is a gain LFO: the control set just before this one
                    val last = jsKeyOrder(v.keys).lastOrNull()
                    var control: Any? = if (last == null) Undefined else Controls.canonicalName(last)
                    if (control is String && control in MODULATOR_KEYS) {
                        val ids = (v[control] as? Map<*, *>)?.get("__ids") as? Set<*>
                            ?: throw CosicError("Cannot read properties of undefined (reading '__ids')")
                        control = "${control}_${jsToString(ids.lastOrNull() ?: Undefined)}"
                    }
                    defaultValue = control
                }
                var t = v[type]
                if (t == null || t === Undefined) {
                    t = LinkedHashMap<String, Any?>().also { it["__ids"] = LinkedHashSet<Any?>() }
                    v[type] = t
                }
                @Suppress("UNCHECKED_CAST")
                val table = t as MutableMap<String, Any?>
                @Suppress("UNCHECKED_CAST")
                val ids = table["__ids"] as MutableSet<Any?>
                val id = if (id0 == null || id0 === Undefined) ids.size.toDouble() else id0
                val idKey = jsToString(id)
                val current = table[idKey]
                if (current == null || current === Undefined) table[idKey] = LinkedHashMap<String, Any?>().also { it["control"] = defaultValue }
                ids.add(id)
                if (c !== Undefined) {
                    @Suppress("UNCHECKED_CAST")
                    val entry = table[idKey] as MutableMap<String, Any?>
                    entry[key] = if (key == "control" || key == "subControl") canonicalOf(c) else c
                }
                Modulation(v, id)
            }
        }.appLeft(reify(options[rawKey]))
    }
    return output.fmap { (it as Modulation).v }
}

/** `getControlName(c)` for a value that may not be a name: anything but a string stays as it is. */
private fun canonicalOf(c: Any?): Any? = if (c is String) Controls.canonicalName(c) else c

/** The control map of a hap value as a map that can be added to in place (the original does that to the value itself). */
@Suppress("UNCHECKED_CAST")
private fun mutableValue(type: String, v: Any?): MutableMap<String, Any?> = when {
    v is MutableMap<*, *> -> v as MutableMap<String, Any?>
    v is Map<*, *> -> LinkedHashMap(v as Map<String, Any?>)
    else -> throw CosicError("Cannot create property '$type' on ${jsTypeof(v)} '${jsToString(v)}'")
}
