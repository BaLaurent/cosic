package cosic.io.midi

import cosic.pattern.Controls
import cosic.pattern.CosicError
import cosic.pattern.Undefined
import cosic.pattern.jsTypeof

/**
 * The registry of MIDI mappings, `midicontrolMap` of `packages/midi/midi.mjs`: names of mappings, each a table from a control to the control
 * change it is sent as. `defaultmidimap` fills the one called `default`, which `.midi()` uses when no `midimap` is set; `midimaps` fills the
 * others. A mapping stays until the program replaces it, across evaluations, as it does in a browser tab.
 *
 * Written by the thread that evaluates a program and read by the dispatcher, so every change publishes a new immutable table.
 */
internal class MidiMaps {
    @Volatile
    private var table: Map<String, Map<String, Any?>> = emptyMap()

    /** `midicontrolMap.set(name, unifyMapping(mapping))`: the controls are renamed to their canonical names and a bare number becomes `{ccn: number}`. */
    @Synchronized
    fun define(name: String, mapping: Any?) {
        val unified = unifyMapping(mapping)
        table = LinkedHashMap(table).also { it[name] = unified }
    }

    /** `midicontrolMap.has(name)`: only a string can be the name of a mapping. */
    fun has(name: Any?): Boolean = name is String && table.containsKey(name)

    fun get(name: String): Map<String, Any?>? = table[name]

    /** The names in the order the mappings were first defined in (`[...midicontrolMap.keys()]`). */
    fun names(): List<String> = table.keys.toList()

    companion object {
        /** `unifyMapping` of midi.mjs. */
        fun unifyMapping(mapping: Any?): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            for ((key, value) in jsEntries(mapping)) {
                out[Controls.canonicalName(key)] = if (value is Double) linkedMapOf<String, Any?>("ccn" to value) else value
            }
            return out
        }

        /** `Object.entries(value)`: the own enumerable string-keyed properties of a program value. `null` and `undefined` have none to give. */
        fun jsEntries(value: Any?): List<Pair<String, Any?>> = when {
            value == null || value === Undefined -> throw CosicError("Cannot convert undefined or null to object")
            value is Map<*, *> -> value.entries.map { (k, v) -> k.toString() to v }
            value is List<*> -> value.mapIndexed { i, v -> i.toString() to v }
            value is String -> value.mapIndexed { i, c -> i.toString() to c.toString() }
            else -> emptyList()
        }

        /** Whether `typeof map === 'object'`, which decides if `midimaps(map)` takes the argument as a table of mappings. */
        fun isObject(value: Any?): Boolean = jsTypeof(value) == "object"
    }
}
