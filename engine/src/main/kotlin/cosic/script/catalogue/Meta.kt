package cosic.script.catalogue

import cosic.io.OutputBus
import cosic.pattern.Pattern
import cosic.pattern.Registered
import cosic.pattern.ScheduleHook
import cosic.pattern.Undefined
import cosic.pattern.jsNumberToString

/*
 * The settings of the editor that a program can change: `theme`, `fontFamily` and `fontSize` of the web REPL (`settingPatterns` of
 * website/src/settings.mjs:149-162). Each is `register(key, (value, pat) => pat.onSchedule(() => { settingsMap.setKey(key, value) }, false))`: a
 * function of patterns that changes nothing about the pattern, and whose hook sets the setting when a hap is scheduled, so
 *
 *     $: s("bd*4").fontSize(24)
 *
 * makes the editor's text larger as soon as the pattern plays. The hook is not dominant: the haps sound. Where the setting goes is the [OutputBus.settings]
 * of the engine; with none, nothing happens.
 *
 * The functions belong to the realm of a session, like any other that a program makes with `register`: the hook needs the output bus of that session, which
 * only the realm knows. `backgroundImage` of the same file of Strudel is one of the canvas stubs of the visuals (ENV D-4, `Visuals.kt`).
 */

/** The names of the settings a program can change. */
private val SETTINGS = listOf("theme", "fontFamily", "fontSize")

internal fun registerMeta() = catalogue("Meta") {
    for (key in SETTINGS) {
        // A global of the realm that also installs the registered function (global, method and `_` form) with the output bus of the realm's session.
        global(key) { bridge ->
            val outputs = bridge.realm.host.effects.outputs
            val registered = Registered(listOf(key), 2, true, false, { it.innerJoin() }, { a -> settingPattern(outputs, key, a[0], a[1] as Pattern) })
            bridge.installRegistered(registered)
            bridge.globalFunction(registered)
        }
    }
}

/** `pat.onSchedule(() => { setKey(key, value) }, false)`: [pat] with a hook that gives [value] to the settings of [outputs]. */
private fun settingPattern(outputs: OutputBus, key: String, value: Any?, pat: Pattern): Pattern {
    // `value = Array.isArray(value) ? value.join(' ') : value`
    val setting: Any? = if (value is List<*>) value.joinToString(" ") { if (it == null || it === Undefined) "" else if (it is Double) jsNumberToString(it) else it.toString() } else value
    return pat.onSchedule(ScheduleHook { outputs.settings?.set(key, setting) }, false)
}
