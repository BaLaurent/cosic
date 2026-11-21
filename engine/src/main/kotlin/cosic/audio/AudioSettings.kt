package cosic.audio

import cosic.pattern.CosicError
import cosic.pattern.JsFn
import cosic.pattern.Undefined
import cosic.pattern.jsLooseEq
import cosic.pattern.jsParseInt
import cosic.pattern.jsToNumber
import cosic.pattern.jsToString

/**
 * What a program changes about the sound engine itself, which superdough keeps in module variables (superdough.mjs:33-86, 185-241):
 * the polyphony limit (`setMaxPolyphony`), whether orbits have output channels of their own (`setMultiChannelOrbits`), the function every
 * gain goes through (`setGainCurve`) and the default values of the controls (`setDefaultValue`, `setDefaultValues`, `getDefaultValue`,
 * `resetDefaultValues`, `setDefault`, `resetDefaults`, `setVersionDefaults`). One per engine, shared by the functions that set it
 * (`ctx.effects.audio` of the catalogue), the sound resolver and `voiceParams`, which read it, and the mixer, which takes the polyphony and
 * the channels from it (see [onChange]).
 *
 * Like Strudel's module variables these live from one evaluation to the next: a program that sets a gain curve keeps it for the programs that
 * follow, until one sets another, and so do the polyphony and the default values (except that, as in Strudel's web REPL, every successful
 * evaluation ends with `setVersionDefaults(@version)`, which takes the live default values back to the base ones: see [setVersionDefaults]).
 * [reset] is what opening another project does: it forgets everything, as reloading the page does in Strudel.
 *
 * What a program wrote is read as one [Snapshot], so an event is built from one consistent state even while another thread evaluates; the setters
 * replace it as a whole.
 */
internal class AudioSettings {
    @Volatile
    private var state: Snapshot = Snapshot.FACTORY

    /** The state now. */
    val current: Snapshot get() = state

    /**
     * Told after every change, from the thread that made it. The engine hands the new polyphony and channel setting to the mixer (which the audio
     * thread owns) from here.
     */
    @Volatile
    var onChange: (() -> Unit)? = null

    // ---- polyphony, orbits, gain curve ---------------------------------------------------------------------------------------

    /**
     * `setMaxPolyphony(polyphony)`: `maxPolyphony = parseInt(polyphony) ?? DEFAULT_MAX_POLYPHONY`. `parseInt` gives NaN for what is no number
     * (`setMaxPolyphony()` included), and `??` keeps a NaN, so the comparison of the voice count with it never holds and nothing is ever stolen.
     * The mixer holds the value to what its pool can sound (see [Snapshot.voiceLimit]).
     */
    fun setMaxPolyphony(polyphony: Any?) = update { it.copy(maxPolyphony = jsParseInt(polyphony)) }

    /** `setMultiChannelOrbits(bool)`: `multiChannelOrbits = bool == true`. */
    fun setMultiChannelOrbits(flag: Any?) = update { it.copy(multiChannelOrbits = jsLooseEq(flag, true)) }

    /**
     * `setGainCurve(newGainCurveFunc)`: stores whatever it is given, as Strudel does. A value that is no function makes every event that uses it fail
     * with `gainCurveFunc is not a function`, which is what calling it does there.
     */
    fun setGainCurve(curve: Any?) = update { it.copy(gainCurve = curve) }

    // ---- default values --------------------------------------------------------------------------------------------------------

    /** `setDefaultValue(key, value)`: `defaultControls.set(key, value)`, the value the controls of an event fall back to now. */
    fun setDefaultValue(key: Any?, value: Any?) = update { it.copy(live = it.live + (mapKey(key) to value)) }

    /** `getDefaultValue(key)`: `defaultControls.get(key)`; `undefined` for a key that was never set. */
    fun getDefaultValue(key: Any?): Any? = state.default(key)

    /** `setDefaultValues(obj)`: [setDefaultValue] for every key of the object (or index of the array) [values]. */
    fun setDefaultValues(values: Map<*, *>) = update { s ->
        var live = s.live
        for ((key, value) in values) live = live + (mapKey(key) to value)
        s.copy(live = live)
    }

    /** `resetDefaultValues()`: `defaultControls = new Map(Object.entries(defaultDefaultValues))`: the live values go back to the base ones. */
    fun resetDefaultValues() = update { it.copy(live = LinkedHashMap<Any?, Any?>(it.base)) }

    /**
     * `setDefault(control, value)`: `defaultDefaultValues[control] = value`: changes the base values, which [resetDefaultValues] (and so every
     * evaluation) returns to, and which the live values only follow after that.
     */
    fun setDefault(control: Any?, value: Any?) = update { it.copy(base = it.base + (jsToString(control) to value)) }

    /** `resetDefaults()`: the base values go back to the ones superdough starts with; the live values are left alone. */
    fun resetDefaults() = update { it.copy(base = FACTORY_DEFAULTS) }

    /**
     * `setVersionDefaults(version)`: [resetDefaultValues], then `fanchor` 0.5 when [version] is the string `1.0`. Nothing reads that default in the
     * pinned superdough, so it only shows in `getDefaultValue('fanchor')`. The engine calls it after every successful evaluation with the
     * `@version` of the program's comments, as the web REPL does (`setVersionDefaultsFrom`).
     */
    fun setVersionDefaults(version: Any?) = update {
        val reset = LinkedHashMap<Any?, Any?>(it.base)
        if (version is String && version == "1.0") reset["fanchor"] = 0.5
        it.copy(live = reset)
    }

    /** Forgets everything, as a new session (another project) starts: the factory polyphony, channels, gain curve and defaults. */
    fun reset() = update { Snapshot.FACTORY }

    @Synchronized
    private fun update(change: (Snapshot) -> Snapshot) {
        val next = change(state)
        if (next === state) return
        state = next
        onChange?.invoke()
    }

    /** One consistent state of the settings. Never changes; a setter makes a new one. */
    class Snapshot internal constructor(
        /** `maxPolyphony` as `parseInt` made it: NaN for no limit. */
        val maxPolyphony: Double,
        val multiChannelOrbits: Boolean,
        /** [IDENTITY] or what the program passed to `setGainCurve`. */
        val gainCurve: Any?,
        /** `defaultControls`: what `getDefaultValue` reads. */
        internal val live: Map<Any?, Any?>,
        /** `defaultDefaultValues`: what `resetDefaultValues` makes the live ones. */
        internal val base: Map<String, Any?>,
    ) {
        /** True when the settings are the ones superdough starts with: the voices need nothing applied to them. */
        val pristine: Boolean = gainCurve === IDENTITY && (live as Map<*, *>) == FACTORY_DEFAULTS

        /** True when gains go through a function of the program. */
        val hasCurve: Boolean get() = gainCurve !== IDENTITY

        /** The voices the mixer lets sound at once: [maxPolyphony] held to what its pool can hold, and the most of them for a limit that is no number. */
        val voiceLimit: Int
            get() = if (maxPolyphony.isNaN()) MAX_POLYPHONY_LIMIT else maxPolyphony.coerceIn(1.0, MAX_POLYPHONY_LIMIT.toDouble()).toInt()

        internal fun copy(
            maxPolyphony: Double = this.maxPolyphony,
            multiChannelOrbits: Boolean = this.multiChannelOrbits,
            gainCurve: Any? = this.gainCurve,
            live: Map<Any?, Any?> = this.live,
            base: Map<String, Any?> = this.base,
        ) = Snapshot(maxPolyphony, multiChannelOrbits, gainCurve, live, base)

        /** `getDefaultValue(key)`. */
        fun default(key: Any?): Any? = if (live.containsKey(mapKey(key))) live[mapKey(key)] else Undefined

        /** The default of [key] as a number, NaN when there is none or it reads as none. */
        fun number(key: String): Double = when (val v = default(key)) {
            is Double, is String, is Boolean -> jsToNumber(v)
            else -> Double.NaN
        }

        /** The default of [key] as a list of numbers (`channels`), null when it is no list. */
        fun numbers(key: String): DoubleArray? = (default(key) as? List<*>)?.let { list -> DoubleArray(list.size) { jsToNumber(list[it]) } }

        /**
         * `applyGainCurve(val)` (superdough.mjs:68-70): [v] itself unless the program set a gain curve, and then whatever the curve makes of it. A curve that
         * is no function fails with `gainCurveFunc is not a function`, as calling it does.
         */
        fun applyGainCurve(v: Any?): Any? {
            val f = gainCurve
            if (f === IDENTITY) return v
            if (f !is JsFn) throw CosicError("gainCurveFunc is not a function")
            return f.call(listOf(v))
        }

        /** [applyGainCurve] of a gain of an event, as a number. */
        fun curve(v: Double): Double = if (gainCurve === IDENTITY) v else jsToNumber(applyGainCurve(v))

        companion object {
            internal val FACTORY = Snapshot(DEFAULT_MAX_POLYPHONY.toDouble(), false, IDENTITY, LinkedHashMap<Any?, Any?>(FACTORY_DEFAULTS), FACTORY_DEFAULTS)
        }
    }

    internal companion object {
        /** The gain curve nobody set: `(val) => val`. */
        val IDENTITY = Any()

        /**
         * `defaultDefaultValues` (superdough.mjs:185-207), what superdough starts with. Only the keys `superdough()` reads through `getDefaultValue` do
         * anything: `s`, `gain`, `postgain`, `channels`, `phaserdepth`, `shapevol`, `distortvol`, `distorttype`, `delay`, `busgain`, `delayfeedback`,
         * `delaysync`, `orbit`, `i`, `velocity`, `fft`, `tremolodepth` and `release`. `density`, `byteBeatExpression` and `tremolophase` are
         * listed in superdough and read by nothing, here too.
         */
        val FACTORY_DEFAULTS: Map<String, Any?> = linkedMapOf(
            "s" to "triangle",
            "gain" to 0.8,
            "postgain" to 1.0,
            "density" to ".03",
            "channels" to listOf(1.0, 2.0),
            "phaserdepth" to 0.75,
            "shapevol" to 1.0,
            "distortvol" to 1.0,
            "distorttype" to 0.0,
            "delay" to 0.0,
            "busgain" to 1.0,
            "byteBeatExpression" to "0",
            "delayfeedback" to 0.5,
            "delaysync" to 3.0 / 16.0,
            "orbit" to 1.0,
            "i" to 1.0,
            "velocity" to 1.0,
            "fft" to 8.0,
            "tremolodepth" to 1.0,
            "tremolophase" to 0.0,
            "release" to 0.01,
        )

        /** The key of a `Map` entry as JavaScript compares them (SameValueZero): `-0` is `0`. */
        fun mapKey(key: Any?): Any? = if (key is Double && key == 0.0) 0.0 else key
    }
}
