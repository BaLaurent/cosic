package cosic.io.midi

import cosic.pattern.CosicError
import cosic.pattern.Undefined
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * What `.midi()` sends, against what the real `packages/midi/midi.mjs` of the pinned checkout sends for the same haps (the recording of
 * `tools/strudel-ref/midi-oracle.mjs`: the real webmidi 3.1.12 and @strudel/core, with ports that record the bytes and the time stamp
 * they are given): every control, the checks and their messages, the rounding, the channels, the mappings, the order of messages and
 * the messages that were sent before an error ended the handler.
 */
class MidiVectorsTest {
    private val outputs: List<String> = MidiVectors.root.getValue("outputs").jsonArray.map { it.jsonPrimitive.content }

    private fun maps(defined: Boolean): MidiMaps {
        val maps = MidiMaps()
        if (defined) {
            val setup = MidiVectors.root.getValue("maps").jsonObject
            maps.define("default", MidiVectors.value(setup.getValue("default")))
            for ((name, mapping) in setup.getValue("named").jsonObject) maps.define(name, MidiVectors.value(mapping))
        }
        return maps
    }

    private fun describe(r: MidiEncodeResult) = "device=${r.device} packets=${r.packets} logs=${r.logs} error=${r.error}"

    @Test
    fun everyMessageIsWhatStrudelSends() {
        val noMaps = maps(false)
        val withMaps = maps(true)
        val cases = MidiVectors.list("encode")
        assertTrue(cases.size > 700, "the recording has ${cases.size} cases")
        val failures = ArrayList<String>()
        for (case in cases) {
            val expected = case.getValue("expected").jsonObject
            val config = try {
                MidiConfig.from(MidiVectors.value(case.getValue("port")), MidiVectors.value(case.getValue("options")), outputs.firstOrNull())
            } catch (e: CosicError) {
                failures.add("${case.getValue("name")}: the config threw '${e.message}'")
                continue
            }
            val result = MidiEncoder.encode(scheduledHap(case), config, if (case.getValue("maps").jsonPrimitive.content == "defined") withMaps else noMaps, outputs)
            val problem = compare(expected, result)
            if (problem != null) failures.add("${case.getValue("name")} [${case.getValue("value")}] port=${case.getValue("port")} options=${case.getValue("options")}: $problem")
        }
        if (failures.isNotEmpty()) fail("${failures.size} of ${cases.size} cases differ from Strudel:\n" + failures.take(25).joinToString("\n"))
    }

    private fun compare(expected: JsonObject, actual: MidiEncodeResult): String? {
        val packets = expected.getValue("packets").jsonArray.map { it.jsonObject }
        val expectedError = expected["error"]?.let { if (it.toString() == "null") null else it.jsonPrimitive.content }
        if (expectedError != actual.error) return "error '$expectedError' but got '${actual.error}' (${describe(actual)})"
        val expectedLogs = expected.getValue("logs").jsonArray.map { it.jsonPrimitive.content }
        if (expectedLogs != actual.logs) return "logs $expectedLogs but got ${actual.logs}"
        if (packets.size != actual.packets.size) return "${packets.size} packets ${packets.map { it["bytes"] }} but got ${describe(actual)}"
        for ((i, p) in packets.withIndex()) {
            val bytes = p.getValue("bytes").jsonArray.map { it.jsonPrimitive.content.toInt() }
            val got = actual.packets[i].bytes.map { it.toInt() and 0xFF }
            if (bytes != got) return "packet $i is $bytes but got $got (${describe(actual)})"
            if (p.getValue("device").jsonPrimitive.content.toInt() != actual.device) return "packet $i goes to ${p["device"]} but got ${actual.device}"
            val offset = p.getValue("offsetMs").jsonPrimitive
            val gotOffset = actual.packets[i].offsetMs
            if (offset.isString) {
                if (!gotOffset.isNaN()) return "packet $i is due at once but got $gotOffset"
            } else if (abs(offset.content.toDouble() - gotOffset) > 1e-6 || gotOffset.isNaN()) {
                return "packet $i is due at ${offset.content} but got $gotOffset"
            }
        }
        return null
    }

    @Test
    fun devicesAreFoundTheWayStrudelFindsThem() {
        val cases = MidiVectors.list("devices")
        assertTrue(cases.size >= 90)
        for (case in cases) {
            val names = case.getValue("names").jsonArray.map { it.jsonPrimitive.content }
            val spec = MidiVectors.value(case.getValue("spec"))
            val expected = case.getValue("expected").jsonObject
            val label = "spec=${case.getValue("spec")} names=$names"
            if (expected.containsKey("error")) {
                val e = assertFailsWith<CosicError>(label) { MidiDevices.resolve(spec, names) }
                assertEquals(expected.getValue("error").jsonPrimitive.content, e.message, label)
            } else {
                val index = expected.getValue("index").let { if (it.toString() == "null") null else it.jsonPrimitive.content.toInt() }
                assertEquals(index, MidiDevices.resolve(spec, names), label)
            }
        }
    }

    @Test
    fun theDevicesOfTheExamples() {
        val names = listOf("IAC Driver Bus 1", "Arturia KeyStep 32")
        assertEquals(0, MidiDevices.resolve(Undefined, names))
        assertEquals(0, MidiDevices.resolve(null, names))
        assertEquals(1, MidiDevices.resolve("Arturia", names))
        assertEquals(1, MidiDevices.resolve(1.0, names))
        assertNull(MidiDevices.resolve("nope", names))
        val e = assertFailsWith<CosicError> { MidiDevices.resolve(Undefined, emptyList()) }
        assertEquals("\uD83D\uDD0C No MIDI devices found. Connect a device or enable IAC Driver.", e.message)
    }

    @Test
    fun theControlChangeNamesAreWebMidisTables() {
        val tables = MidiVectors.root.getValue("ccNames").jsonObject
        val modern = tables.getValue("modern").jsonArray.associate { it.jsonArray[1].jsonPrimitive.content to it.jsonArray[0].jsonPrimitive.content.toInt() }
        val legacy = tables.getValue("legacy").jsonObject.mapValues { it.value.jsonPrimitive.content.toInt() }
        assertEquals(128, modern.size)
        assertEquals(modern, MidiCcNames.modern)
        assertEquals(legacy, MidiCcNames.legacy)
    }

    @Test
    fun theNumbersOfTheSpecification() {
        // the vectors of the plan's table (default config, cps 0.5, a hap that starts in cycle 1 and lasts one cycle)
        fun sent(value: Map<String, Any?>): String {
            val whole = cosic.pattern.TimeSpan(cosic.pattern.Rational(1), cosic.pattern.Rational(2))
            val hap = cosic.pattern.Hap(whole, whole, value)
            val sh = cosic.pattern.ScheduledHap(hap, 0, 0.0, 0.5, 1.0, 1.0)
            val r = MidiEncoder.encode(sh, MidiConfig.from(Undefined, Undefined, null), MidiMaps(), listOf("out"))
            assertNull(r.error)
            return r.packets.joinToString(", ") { it.toString() }
        }
        assertEquals("90 3C 72, 80 3C 40 @1999.0", sent(mapOf("note" to "c4", "midichan" to 1.0)))
        assertEquals("B0 4A 40", sent(mapOf("ccn" to 74.0, "ccv" to 0.5)))
        assertEquals("E0 4C 59", sent(mapOf("midibend" to 0.4)))
        assertEquals("E0 00 40", sent(mapOf("midibend" to 0.0)))
        assertEquals("E0 00 00", sent(mapOf("midibend" to -1.0)))
        assertEquals("E0 7F 7F", sent(mapOf("midibend" to 1.0)))
        assertEquals("D0 40", sent(mapOf("miditouch" to 0.5)))
        assertEquals("C0 0A", sent(mapOf("progNum" to 10.0)))
        assertEquals("F0 77 01 02 03 04 F7", sent(mapOf("sysexid" to 119.0, "sysexdata" to listOf(1.0, 2.0, 3.0, 4.0))))
        assertEquals("F0 00 20 32 01 F7", sent(mapOf("sysexid" to listOf(0.0, 32.0, 50.0), "sysexdata" to listOf(1.0))))
        assertEquals("B0 63 01, B0 62 08, B0 06 7B, B0 65 7F, B0 64 7F", sent(mapOf("nrpnn" to listOf(1.0, 8.0), "nrpv" to 123.0)))
        assertEquals("F8", sent(mapOf("midicmd" to "clock")))
    }
}
