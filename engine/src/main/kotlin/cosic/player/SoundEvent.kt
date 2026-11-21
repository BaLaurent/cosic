package cosic.player

import cosic.audio.AudioSettings
import cosic.audio.DEFAULT_VOICE_RELEASE
import cosic.audio.LIST_KEYS
import cosic.audio.MAX_FX
import cosic.audio.ModKind
import cosic.audio.ModSpec
import cosic.audio.Param
import cosic.audio.ParamSet
import cosic.audio.STRING_KEYS
import cosic.audio.VoiceParams
import cosic.audio.VoiceSource
import cosic.audio.sources.asSourceControls
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
 *
 * With a [kabel] factory, every chain whose controls hold a `workletSrc` (`K(...)`, `worklet(...)`) gets the program that runs it, made in the order superdough
 * sends them to the worklets (the `FX(...)` elements, then the event) and with the frequency `sFreq` stands for always that of the event. A program that does not
 * compile is a [cosic.pattern.CosicError], the event's own error.
 *
 * With [settings] that are not the ones superdough starts with (a program called `setGainCurve`, `setDefaultValue`...) every chain is built as `superdough()` builds
 * it from them: a control the event leaves unset takes the current default value, and the gains go through the gain curve (see [applyDefaults]); the release
 * the chain lives for takes its default from them too. A gain curve of the program runs here, once for each gain of each chain.
 */
internal fun voiceParams(
    controls: ControlMap,
    source: VoiceSource,
    durationSec: Double,
    cps: Double,
    cycle: Double,
    kabel: cosic.kabel.KabelFactory? = null,
    settings: AudioSettings? = null,
): VoiceParams {
    val state = settings?.current?.takeUnless { it.pristine }
    val fxElements = (controls["FX"] as? List<*>)?.filterIsInstance<Map<*, *>>().orEmpty()
    if (fxElements.size > MAX_FX) logger("only the first $MAX_FX FX(...) elements are used, ${fxElements.size - MAX_FX} dropped", "warning", null)
    val fx = ArrayList<ParamSet>(fxElements.size.coerceAtMost(MAX_FX))
    val mods = ArrayList<ModSpec>()
    val eventControls = if (kabel != null) controls.asSourceControls() else null
    for (i in 0 until fxElements.size.coerceAtMost(MAX_FX)) {
        @Suppress("UNCHECKED_CAST")
        val element = fxElements[i] as Map<String, Any?>
        fx.add(paramSetOf(element, kabel?.create(element["workletSrc"], element["workletInputs"], eventControls!!), state, main = false))
        addModulators(element, i.toString(), mods)
    }
    val main = paramSetOf(controls, kabel?.create(controls["workletSrc"], controls["workletInputs"], eventControls!!), state, main = true)
    addModulators(controls, "main", mods)
    val defaultRelease = state?.number("release")?.takeUnless { it.isNaN() } ?: DEFAULT_VOICE_RELEASE
    return VoiceParams(source, durationSec, cps, cycle, main, fx, mods, defaultRelease = defaultRelease)
}

/** [ParamSet] of one control map, with the kabelsalat program [kabel] of its chain when it has one; see [voiceParams]. */
internal fun paramSetOf(
    controls: Map<String, Any?>,
    kabel: cosic.kabel.KabelInstance? = null,
    state: AudioSettings.Snapshot? = null,
    main: Boolean = true,
): ParamSet {
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
    if (state != null) {
        applyDefaults(values, state, main)
        if (main && lists?.containsKey("channels") != true) {
            val channels = defaultChannels(values, state)
            if (channels != null) {
                if (lists == null) lists = HashMap()
                lists["channels"] = channels
            }
        }
        if (values[Param.distorttype.ordinal].isNaN() && strings?.containsKey("distorttype") != true) {
            val named = state.default("distorttype")
            if (named is String && jsStringToNumber(named).isNaN()) {
                if (strings == null) strings = HashMap()
                strings["distorttype"] = named
            }
        }
    }
    return ParamSet(values, strings ?: emptyMap(), lists ?: emptyMap(), kabel)
}

/** The controls that fall back to a default value in every chain: `getDefaultValue(key)` of the per-chain destructuring of superdough.mjs:614-627. */
private val CHAIN_DEFAULTS = arrayOf(Param.phaserdepth, Param.delayfeedback, Param.delaysync, Param.distorttype)

/** The controls that go through the gain curve in every chain (superdough.mjs:628-633): `gain` is the one that [nanFallback]s as well. */
private val CHAIN_CURVED = arrayOf(Param.velocity, Param.shapevol, Param.distortvol, Param.tremolodepth)

/** The controls only the event itself has, which go through the curve with their defaults (`dry`, which has none, is curved only when set). */
private val MAIN_CURVED = arrayOf(Param.postgain, Param.delay, Param.busgain)

/**
 * What `superdough()` does with the settings before it builds a chain, for the controls in [values] (NaN: unset) of one chain, the main one when
 * [main] or an `FX(...)` element: the destructuring defaults of superdough.mjs:491-523 and 614-627 (`getDefaultValue`), and `applyGainCurve` on the gains
 * (537-539, 628-633, 996). Per chain: `gain` (`nanFallback(gain, 1)`, so a default that is no number counts as 1), `velocity`, `shapevol`, `distortvol`,
 * `tremolodepth`; the main chain, whose event is the one `superdough()` destructures, also `postgain`, `delay` (an element's `delay` is not curved),
 * `busgain` and `dry`, which has no default and is only curved when set; and takes the defaults of `orbit` and `i`.
 */
private fun applyDefaults(values: DoubleArray, s: AudioSettings.Snapshot, main: Boolean) {
    for (p in CHAIN_DEFAULTS) defaulted(values, p, s)
    if (main) {
        defaulted(values, Param.orbit, s)
        defaulted(values, Param.i, s)
    }
    var gain = values[Param.gain.ordinal]
    if (gain.isNaN()) gain = s.number("gain")
    if (gain.isNaN()) gain = 1.0
    values[Param.gain.ordinal] = if (s.hasCurve) s.curve(gain) else gain
    for (p in CHAIN_CURVED) {
        defaulted(values, p, s)
        if (s.hasCurve && !values[p.ordinal].isNaN()) values[p.ordinal] = s.curve(values[p.ordinal])
    }
    if (main) {
        for (p in MAIN_CURVED) {
            defaulted(values, p, s)
            if (s.hasCurve && !values[p.ordinal].isNaN()) values[p.ordinal] = s.curve(values[p.ordinal])
        }
        if (s.hasCurve && !values[Param.dry.ordinal].isNaN()) values[Param.dry.ordinal] = s.curve(values[Param.dry.ordinal])
    } else {
        defaulted(values, Param.delay, s)
    }
}

/** [p] takes the current default value of its control when the event sets none and the default is a number. */
private fun defaulted(values: DoubleArray, p: Param, s: AudioSettings.Snapshot) {
    if (!values[p.ordinal].isNaN()) return
    val d = s.number(p.key)
    if (!d.isNaN()) values[p.ordinal] = d
}

/**
 * The output channels of an event that sets none, when the default is not `[1, 2]` and nothing else decides them: `multiChannelOrbits && orbit > 0 ? [orbit * 2 - 1, orbit * 2] :
 * getDefaultValue('channels')` (superdough.mjs:527-529). Null when the orbit's own channels or the usual ones apply.
 */
private fun defaultChannels(values: DoubleArray, s: AudioSettings.Snapshot): DoubleArray? {
    val list = s.numbers("channels") ?: return null
    if (list.size == 2 && list[0] == 1.0 && list[1] == 2.0) return null
    return if (s.multiChannelOrbits && values[Param.orbit.ordinal] > 0.0) null else list
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
