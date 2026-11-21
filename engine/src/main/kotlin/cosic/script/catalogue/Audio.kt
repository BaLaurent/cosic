package cosic.script.catalogue

import cosic.audio.DEFAULT_MAX_POLYPHONY
import cosic.audio.sources.WarpModes
import cosic.pattern.CosicError
import cosic.pattern.Undefined
import cosic.script.js.PropFlags

/*
 * The functions of a program that change the sound engine itself (packages/superdough/superdough.mjs:33-86, 185-241, 272-319): `setMaxPolyphony`,
 * `setMultiChannelOrbits`, `setGainCurve`, `initAudio`, and the default values of the controls (`setDefaultValue`, `setDefaultValues`,
 * `getDefaultValue`, `resetDefaultValues`, `setDefault`, `resetDefaults`, `setVersionDefaults`), with the constants superdough exports (`DEFAULT_MAX_POLYPHONY`,
 * `maxPolyphony`, `multiChannelOrbits`, `Warpmode`, `noises`). What they change is the engine's [cosic.audio.AudioSettings] (`ctx.effects.audio`): like the module
 * variables of superdough it outlives the evaluation of a program, and a new session (another project) starts from the factory values.
 *
 * In Strudel `maxPolyphony` and `multiChannelOrbits` are `let` exports, and `evalScope` copies the values they have when it runs into the global scope, so a
 * program that reads them always sees those, never what `setMaxPolyphony` set; they are constants here too.
 */
internal fun registerAudio() = catalogue("Audio") {
    // setMaxPolyphony(polyphony): parseInt(polyphony) ?? 128
    function(listOf("setMaxPolyphony"), 1) { ctx ->
        ctx.effects.audio.setMaxPolyphony(ctx.kernelArg(0))
        Undefined
    }

    // setMultiChannelOrbits(bool): bool == true
    function(listOf("setMultiChannelOrbits"), 1) { ctx ->
        ctx.effects.audio.setMultiChannelOrbits(ctx.kernelArg(0))
        Undefined
    }

    // setGainCurve(fn): every gain of every event goes through it
    function(listOf("setGainCurve"), 1) { ctx ->
        ctx.effects.audio.setGainCurve(ctx.kernelArg(0))
        Undefined
    }

    // applyGainCurve(val): what a gain becomes; the value itself until a program sets a curve
    function(listOf("applyGainCurve"), 1) { ctx ->
        try {
            ctx.effects.audio.current.applyGainCurve(ctx.kernelArg(0))
        } catch (e: CosicError) {
            ctx.fail(e.message)
        }
    }

    // initAudio({ maxPolyphony, multiChannelOrbits, ... }): the two settings; the audio device and the worklets are not a program's to choose here
    function(listOf("initAudio"), 0) { ctx ->
        val options = ctx.kernelArg(0)
        if (options == null) ctx.fail("Cannot destructure property 'disableWorklets' of 'options' as it is null.")
        val given = options as? Map<*, *>
        val audio = ctx.effects.audio
        // `maxPolyphony` is not defaulted there: an option that is missing is `setMaxPolyphony(undefined)`, no limit
        audio.setMaxPolyphony(given?.get("maxPolyphony") ?: Undefined)
        audio.setMultiChannelOrbits(if (given != null && given.containsKey("multiChannelOrbits") && given["multiChannelOrbits"] !== Undefined) given["multiChannelOrbits"] else false)
        Undefined
    }

    // setDefaultValue(key, value), setDefaultValues({ key: value }), getDefaultValue(key), resetDefaultValues()
    function(listOf("setDefaultValue"), 2) { ctx ->
        ctx.effects.audio.setDefaultValue(ctx.kernelArg(0), ctx.kernelArg(1))
        Undefined
    }
    function(listOf("setDefaultValues"), 1) { ctx ->
        val values = when (val v = ctx.kernelArg(0)) {
            null, Undefined -> ctx.fail("Cannot convert undefined or null to object")
            is Map<*, *> -> v
            is List<*> -> v.withIndex().associate { (i, item) -> i.toString() to item } // Object.keys(array)
            else -> emptyMap<String, Any?>()
        }
        ctx.effects.audio.setDefaultValues(values)
        Undefined
    }
    function(listOf("getDefaultValue"), 1) { ctx -> ctx.effects.audio.getDefaultValue(ctx.kernelArg(0)) }
    function(listOf("resetDefaultValues"), 0) { ctx ->
        ctx.effects.audio.resetDefaultValues()
        Undefined
    }

    // setDefault(control, value): the base value every reset goes back to; resetDefaults() puts the base values back to superdough's own
    function(listOf("setDefault"), 2) { ctx ->
        ctx.effects.audio.setDefault(ctx.kernelArg(0), ctx.kernelArg(1))
        Undefined
    }
    function(listOf("resetDefaults"), 0) { ctx ->
        ctx.effects.audio.resetDefaults()
        Undefined
    }

    // setVersionDefaults(version): resetDefaultValues(), then fanchor 0.5 for '1.0'
    function(listOf("setVersionDefaults"), 1) { ctx ->
        ctx.effects.audio.setVersionDefaults(ctx.kernelArg(0))
        Undefined
    }

    // the constants superdough exports
    global("DEFAULT_MAX_POLYPHONY") { DEFAULT_MAX_POLYPHONY.toDouble() }
    global("maxPolyphony") { DEFAULT_MAX_POLYPHONY.toDouble() }
    global("multiChannelOrbits") { false }
    global("noises") { bridge -> bridge.realm.newArray(listOf("pink", "white", "brown", "crackle")) }

    // Warpmode: Object.freeze({ NONE: 0, ASYM: 1, ... }), the names of the modes of the wavetable oscillator's `warpmode` control
    global("Warpmode") { bridge ->
        bridge.realm.newObject().also { o ->
            for ((name, mode) in WarpModes.NAMES) o.defineOwn(name, mode, PropFlags.ENUMERABLE)
            o.extensible = false
        }
    }
}
