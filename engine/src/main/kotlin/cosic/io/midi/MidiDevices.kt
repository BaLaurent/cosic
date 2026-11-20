package cosic.io.midi

import cosic.pattern.CosicError

/** What `getMidiDeviceNamesString` of midi/util.mjs makes of a list of devices: `'a' | 'b'`. */
internal fun midiDeviceNames(names: List<String>): String = names.joinToString(" | ") { "'$it'" }

/** The index of the device the program asked for in [names], from `getDevice` of `packages/midi/util.mjs`. */
internal object MidiDevices {
    /**
     * Looks a device up by index or name, as `getDevice(indexOrName, devices)` does: a number is the position in [names] (null when there is
     * nothing there), a string is the first device whose name contains it (null when none does), and anything else is the default: the
     * first IAC device, or else the first device. Throws the message Strudel does when there is no device to default to.
     */
    fun resolve(spec: Any?, names: List<String>): Int? {
        if (spec is Double) {
            return if (spec >= 0.0 && spec < names.size && spec == Math.floor(spec)) spec.toInt() else null
        }
        if (spec is String) return names.indexOfFirst { it.contains(spec) }.takeIf { it >= 0 }
        val iac = names.indexOfFirst { it.contains("IAC") }
        val index = if (iac >= 0) iac else if (names.isNotEmpty()) 0 else -1
        if (index < 0) throw CosicError("\uD83D\uDD0C No MIDI devices found. Connect a device or enable IAC Driver.")
        return index
    }
}
