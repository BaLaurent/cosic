package cosic.io.osc

import cosic.pattern.Hap
import cosic.pattern.ScheduledHap
import cosic.pattern.Undefined
import cosic.pattern.isNote
import cosic.pattern.jsMerge
import cosic.pattern.jsToNumber
import cosic.pattern.jsTruthy
import cosic.pattern.jsonStringify
import cosic.pattern.noteToMidi
import cosic.pattern.parseNumeral
import cosic.pattern.tokenizeNote

/**
 * What `.osc()` sends for a hap: the controls of `parseControlsFromHap` (`packages/osc/osc.mjs:36-60`), flattened to `[key, value, key, value, ...]`
 * as `Object.entries(controls).flat()` has them.
 *
 * `cps`, `cycle` (where the whole of the hap begins) and `delta` (its length in seconds) come first, then the controls of the hap in their order
 * (whole-number keys, which JavaScript puts first, before even `cps`). `n` is made a number (`"3"` is 3, a note name its MIDI number; anything
 * else is the error `cannot parse as numeral: "x"`), a `note` gets a `midinote` (a note name in the `octave` of the hap, default 3, or the number), a
 * `roomsize` a `size`, `speed` is divided by the tempo when `unit` is `"c"` (SuperDirt multiplies it again) and `channels` is sent as JSON text.
 * Keys that are set already keep their place; new ones go to the end.
 */
internal object OscControls {
    fun fromHap(h: ScheduledHap): List<Any?> = fromHap(h.hap, h.cps)

    fun fromHap(hap: Hap, cps: Double): List<Any?> {
        hap.ensureObjectValue()
        val cycle = hap.wholeOrPart().begin.toDouble()
        val delta = hap.duration.toDouble() / cps
        val head = linkedMapOf<String, Any?>("cps" to cps, "cycle" to cycle, "delta" to delta)
        val controls = jsMerge(head, objectAssignSource(hap.value))

        if (jsTruthy(controls["n"])) controls["n"] = parseNumeral(controls["n"])
        val note = controls["note"]
        if (controls.containsKey("note") && note !== Undefined) {
            controls["midinote"] = if (isNote(note)) midinoteOf(note, controls["octave"]) else parseNumeral(note)
        }
        if (jsTruthy(controls["roomsize"])) controls["size"] = parseNumeral(controls["roomsize"])
        val speed = controls["speed"]
        if (controls["unit"] == "c" && speed != null && speed !== Undefined) controls["speed"] = jsToNumber(speed) / cps
        val channels = controls["channels"]
        if (controls.containsKey("channels") && channels != null && channels !== Undefined) controls["channels"] = jsonStringify(channels) ?: Undefined

        val flat = ArrayList<Any?>(controls.size * 2)
        for ((key, value) in controls) {
            flat.add(key)
            flat.add(value)
        }
        return flat
    }

    /** `Object.assign`'s view of a hap value: a map's own entries, a list's indices, nothing for `null`. */
    private fun objectAssignSource(value: Any?): Map<String, Any?> = when (value) {
        is Map<*, *> -> value.entries.associate { (k, v) -> k.toString() to v }
        is List<*> -> value.withIndex().associate { (i, v) -> i.toString() to v }
        else -> emptyMap()
    }

    /** `noteToMidi(note, octave || 3)` with the octave a number of any size, not only a whole one. */
    private fun midinoteOf(note: Any?, octave: Any?): Double {
        if (tokenizeNote(note)?.third != null) return noteToMidi(note)
        val o = if (jsTruthy(octave)) jsToNumber(octave) else 3.0
        return noteToMidi(note, 0) + 12 * o
    }
}
