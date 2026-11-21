package cosic.player

import cosic.audio.LIST_KEYS
import cosic.audio.MAX_FX
import cosic.audio.ModKind
import cosic.audio.ModSpec
import cosic.audio.Param
import cosic.audio.ParamSet
import cosic.audio.STRING_KEYS
import cosic.audio.VoiceParams
import cosic.audio.VoiceSource
import cosic.pattern.ControlMap
import cosic.pattern.Undefined
import cosic.pattern.jsStringToNumber
import cosic.pattern.jsNumberToString
import cosic.pattern.jsToNumber
import cosic.pattern.jsToString
import cosic.pattern.logger

/** A voice to start at engine frame [startFrame]. */
internal class SoundEvent(val startFrame: Long, val voice: VoiceParams)

/**
 * The raw controls of one event as voice parameters (`superdough(value, t, hapDuration, cps, cycle)`): the controls of the event itself
 * ([VoiceParams.main]), those of each element of its `FX(...)` list ([VoiceParams.fx], at most [MAX_FX]; further elements are dropped
 * with a warning) and the `lfo`, `env` and `bmod` modulators of all of them, with the [source] and the length of the event in seconds. `s`, `n` and
 * `bank` only choose the source and are not parameters.
 *
 * What a control becomes: a number is a [Param] when the name is one (unknown names are ignored); a string is a text control when the name is
 * in [STRING_KEYS] and otherwise read as a number if it looks like one; a boolean is 1 or 0; a list control ([LIST_KEYS]) is kept as a list, a number
 * as a list of one. `note`, `freq` and `octave` are not parameters: the sound that plays the event reads them from the control map, as superdough's sources do
 * (a `note` that is a name and no note is an error of the sound lookup, see [SoundResolver]). A `gain` that is not a number counts as 1 (`nanFallback(gain, 1)`). Controls that
 * were not set stay NaN, so the voice applies its own defaults.
 */
internal fun voiceParams(controls: ControlMap, source: VoiceSource, durationSec: Double, cps: Double, cycle: Double): VoiceParams {
    val main = paramSetOf(controls)
    val fxElements = (controls["FX"] as? List<*>)?.filterIsInstance<Map<*, *>>().orEmpty()
    if (fxElements.size > MAX_FX) logger("only the first $MAX_FX FX(...) elements are used, ${fxElements.size - MAX_FX} dropped", "warning", null)
    val fx = ArrayList<ParamSet>(fxElements.size.coerceAtMost(MAX_FX))
    val mods = ArrayList<ModSpec>()
    for (i in 0 until fxElements.size.coerceAtMost(MAX_FX)) {
        @Suppress("UNCHECKED_CAST")
        val element = fxElements[i] as Map<String, Any?>
        fx.add(paramSetOf(element))
        addModulators(element, i.toString(), mods)
    }
    addModulators(controls, "main", mods)
    return VoiceParams(source, durationSec, cps, cycle, main, fx, mods)
}

/** [ParamSet] of one control map; see [voiceParams]. */
internal fun paramSetOf(controls: Map<String, Any?>): ParamSet {
    val values = DoubleArray(Param.COUNT) { Double.NaN }
    var strings: HashMap<String, String>? = null
    var lists: HashMap<String, DoubleArray>? = null
    for ((key, value) in controls) {
        val param = Param.byKey[key]
        if (key == "analyze") {
            analyzeIdOf(value)?.let {
                if (strings == null) strings = HashMap()
                strings[key] = it
            }
            continue
        }
        if (key in LIST_KEYS) {
            val list = when (value) {
                is List<*> -> DoubleArray(value.size) { jsToNumber(value[it]) }
                null, Undefined -> null
                else -> doubleArrayOf(jsToNumber(value))
            }
            if (list != null) {
                if (lists == null) lists = HashMap()
                lists[key] = list
                if (param != null && value !is List<*>) values[param.ordinal] = list[0]
            }
            continue
        }
        when (value) {
            is Double -> if (param != null) values[param.ordinal] = value
            is Boolean -> if (param != null) values[param.ordinal] = if (value) 1.0 else 0.0
            is String -> {
                if (key in STRING_KEYS) {
                    if (strings == null) strings = HashMap()
                    strings[key] = value
                } else if (param != null) {
                    val n = jsStringToNumber(value)
                    if (!n.isNaN()) values[param.ordinal] = n
                }
            }
        }
    }
    // gain = applyGainCurve(nanFallback(gain, 1)): a gain that is no number is 1, not the default 0.8
    val rawGain = controls["gain"]
    if (rawGain != null && rawGain !== Undefined && values[Param.gain.ordinal].isNaN()) values[Param.gain.ordinal] = 1.0
    return ParamSet(values, strings ?: emptyMap(), lists ?: emptyMap())
}

/**
 * The id of the analyser that `analyze: [value]` names, as a string: the key of the voice's tap in [cosic.io.ScopeTaps], which the visuals read under `String(analyze)`
 * (`1` for the plain methods, the widget id for `_scope`). Null when the control turns no analyser on: superdough's `if (analyze)` is false for 0, NaN, `false` and the empty string.
 */
internal fun analyzeIdOf(value: Any?): String? = when (value) {
    is Double -> if (value == 0.0 || value.isNaN()) null else jsNumberToString(value)
    is Boolean -> if (value) "true" else null
    is String -> value.ifEmpty { null }
    else -> null
}

/** The `lfo`, `env` and `bmod` entries of [element], in the order `superdough()` connects them, as [ModSpec]s living in chain [key]. */
private fun addModulators(element: Map<String, Any?>, key: String, out: MutableList<ModSpec>) {
    for (kind in ModKind.entries) {
        val table = element[kind.name.lowercase()] as? Map<*, *> ?: continue
        val ids = table["__ids"] as? Collection<*> ?: continue
        for (id in ids) {
            val idKey = jsToString(id)
            val entry = table[idKey] as? Map<*, *> ?: continue
            val control = entry["control"] as? String ?: continue
            val numbers = LinkedHashMap<String, Double>()
            val strings = LinkedHashMap<String, String>()
            var fxi = key
            for ((optionKey, option) in entry) {
                val name = optionKey as? String ?: continue
                if (name == "control" || name == "subControl") continue
                if (name == "fxi") {
                    if (option != null && option !== Undefined) fxi = jsToString(option)
                    continue
                }
                when (option) {
                    is Double -> numbers[name] = option
                    is Boolean -> numbers[name] = if (option) 1.0 else 0.0
                    is String -> strings[name] = option
                }
            }
            out.add(ModSpec(kind, idKey, fxi, control, entry["subControl"] as? String, numbers, strings))
        }
    }
}
