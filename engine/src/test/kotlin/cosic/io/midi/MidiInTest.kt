package cosic.io.midi

import cosic.io.ConsoleLog
import cosic.pattern.CosicError
import cosic.pattern.Rational
import cosic.pattern.Undefined
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A device whose inputs and outputs are lists of names; what is sent to it is kept. */
internal class FakeSource(var inputs: List<String>, val outputs: List<String>) : MidiInputSource {
    val sent = ArrayList<Pair<String, List<Int>>>()

    override fun inputNames(): List<String> = inputs

    override fun sendNow(outputName: String, bytes: ByteArray): Boolean {
        if (outputName !in outputs) return false
        sent.add(outputName to bytes.map { it.toInt() and 0xFF })
        return true
    }
}

internal class MemoryStore(initial: Map<String, String> = emptyMap()) : MidiStateStore {
    val values = LinkedHashMap(initial)

    override fun load(key: String): String? = values[key]

    override fun save(key: String, value: String) {
        values[key] = value
    }
}

/** The timer of a hub that only runs the tasks when a test says so, in the order they were queued. */
internal class ManualTimer {
    val tasks = ArrayList<Pair<Long, () -> Unit>>()
    val schedule: (Long, () -> Unit) -> (() -> Unit) = { delay, task ->
        val entry = delay to task
        tasks.add(entry)
        val cancel: () -> Unit = { tasks.remove(entry) }
        cancel
    }

    /** Runs everything queued, and what that queues in turn. */
    fun flush() {
        while (tasks.isNotEmpty()) tasks.removeAt(0).second()
    }
}

internal class TestHub(val hub: MidiHub, val source: FakeSource, val timer: ManualTimer, val console: ConsoleLog)

internal fun testHub(
    inputs: List<String> = emptyList(),
    outputs: List<String> = emptyList(),
    store: MidiStateStore? = null,
    console: ConsoleLog = ConsoleLog(),
): TestHub {
    val source = FakeSource(inputs, outputs)
    val timer = ManualTimer()
    val hub = MidiHub(console) { outputs }
    hub.source = source
    hub.store = store
    hub.scheduler = timer.schedule
    return TestHub(hub, source, timer, console)
}

internal fun midiBytes(array: JsonArray) = ByteArray(array.size) { array[it].jsonPrimitive.content.toInt().toByte() }

/**
 * `midin`: what the values of an input are after messages, what is kept in storage and sent back to the device, against what the real
 * `packages/midi/{midi,input}.mjs` does (the recording of `midi-oracle.mjs`), plus the cases the recording does not hold.
 */
class MidiInTest {
    private val setup = MidiVectors.root.getValue("input").jsonObject
    private val inputNames = setup.getValue("inputs").jsonArray.map { it.jsonPrimitive.content }
    private val outputNames = setup.getValue("outputs").jsonArray.map { it.jsonPrimitive.content }

    private fun valueAt(state: MidiInState, cc: Any?, chan: Any?): Any? = state.cc(cc, chan).queryArc(Rational.ZERO, Rational.ONE).single().value

    @Test
    fun valuesAreWhatStrudelsMidinGives() {
        val store = MemoryStore(setup.getValue("initialStore").jsonObject.mapValues { it.value.jsonPrimitive.content })
        val t = testHub(inputNames, outputNames, store)
        val scenarios = MidiVectors.list("midin").filter { it.containsKey("input") }
        assertTrue(scenarios.size >= 6)
        for (scenario in scenarios) {
            val name = scenario.getValue("name").jsonPrimitive.content
            val input = MidiVectors.value(scenario.getValue("input"))
            val linesBefore = t.console.entries.size
            t.source.sent.clear()
            val instance = t.hub.input(input, announce = true)
            val logs = t.console.entries.drop(linesBefore).map { it.message }
            assertEquals(scenario.getValue("logs").jsonArray.map { it.jsonPrimitive.content }, logs, "$name: logs")

            fun check(rows: JsonArray, what: String) {
                for (row in rows.map { it.jsonObject }) {
                    val chan = if (row.containsKey("chan")) MidiVectors.value(row.getValue("chan")) else Undefined
                    val cc = MidiVectors.value(row.getValue("cc"))
                    val expected = row.getValue("value")
                    val label = "$name: $what cc=${row["cc"]} chan=${row["chan"]}"
                    if (expected is JsonObject && expected.containsKey("error")) {
                        val e = assertFailsWith<CosicError>(label) { instance.state.cc(cc, chan) }
                        assertEquals(expected.getValue("error").jsonPrimitive.content, e.message, label)
                    } else {
                        assertEquals(MidiVectors.value(expected), valueAt(instance.state, cc, chan), label)
                    }
                }
            }
            check(scenario.getValue("before").jsonArray, "before")

            // the device the input listens to; a message from it is lost until the listening starts, two seconds after the input was opened
            val device = try {
                MidiDevices.resolve(input, inputNames)?.let { inputNames[it] }
            } catch (e: CosicError) {
                null
            }
            if (device != null) for (m in scenario.getValue("beforeListening").jsonArray) t.hub.onMessage(device, midiBytes(m.jsonArray))
            check(scenario.getValue("afterDeaf").jsonArray, "while deaf")

            // the delayed tasks: the stored values go back to the device, and the input starts to listen
            t.source.sent.clear()
            t.timer.flush()
            val sent = scenario.getValue("sent").jsonArray.map { it.jsonObject }
            assertEquals(sent.map { p -> p.getValue("bytes").jsonArray.map { it.jsonPrimitive.content.toInt() } }, t.source.sent.map { it.second }, "$name: the values sent back")
            assertEquals(sent.map { outputNames[it.getValue("device").jsonPrimitive.content.toInt()] }, t.source.sent.map { it.first }, "$name: where they go")

            if (device != null) for (m in scenario.getValue("messages").jsonArray) t.hub.onMessage(device, midiBytes(m.jsonArray))
            check(scenario.getValue("values").jsonArray, "after")
            t.timer.flush()
            val keyName = if (input is String) input else "\u0000"
            assertEquals(
                scenario.getValue("storage").jsonObject.mapValues { it.value.jsonPrimitive.content },
                store.values.filterKeys { it.startsWith("strudel-midin-$keyName-") },
                "$name: storage",
            )
        }
    }

    @Test
    fun theSameInputIsOnePerSpecForTheLifeOfTheHub() {
        val t = testHub(listOf("A", "B"))
        assertTrue(t.hub.input("A", announce = false) === t.hub.input("A", announce = false))
        assertTrue(t.hub.input(1.0, announce = false) !== t.hub.input("B", announce = false))
    }

    @Test
    fun aNoteOrAPitchBendOfTheDeviceSetsASlotLikeAControlChange() {
        val t = testHub(listOf("A"))
        val input = t.hub.input("A", announce = false)
        t.timer.flush()
        t.hub.onMessage("A", byteArrayOf(0x90.toByte(), 60, 127))
        t.hub.onMessage("A", byteArrayOf(0xE2.toByte(), 1, 127))
        assertEquals(1.0, valueAt(input.state, 60.0, Undefined))
        assertEquals(1.0, valueAt(input.state, 1.0, 3.0))
        assertEquals(0.0, valueAt(input.state, 1.0, 1.0))
    }

    @Test
    fun aMessageWithOneDataByteSetsNaNAndSystemMessagesAreNotLookedAt() {
        val t = testHub(listOf("A"))
        val input = t.hub.input("A", announce = false)
        t.timer.flush()
        t.hub.onMessage("A", byteArrayOf(0xC0.toByte(), 5))
        t.hub.onMessage("A", byteArrayOf(0xF8.toByte()))
        t.hub.onMessage("A", byteArrayOf(0xF0.toByte(), 1, 2, 0xF7.toByte()))
        assertTrue((valueAt(input.state, 5.0, Undefined) as Double).isNaN())
        assertEquals(0.0, valueAt(input.state, 248.0, Undefined))
    }

    @Test
    fun messagesOfAnotherDeviceAreNotHeard() {
        val t = testHub(listOf("A", "B"))
        val input = t.hub.input("A", announce = false)
        t.timer.flush()
        t.hub.onMessage("B", byteArrayOf(0xB0.toByte(), 7, 127))
        assertEquals(0.0, valueAt(input.state, 7.0, Undefined))
        t.hub.onMessage("A", byteArrayOf(0xB0.toByte(), 7, 127))
        assertEquals(1.0, valueAt(input.state, 7.0, Undefined))
    }

    @Test
    fun anInputWaitsForItsDeviceAndPicksItUpWhenItComes() {
        val t = testHub(emptyList())
        val input = t.hub.input("Late", announce = true)
        assertTrue(t.console.entries.last().message.startsWith("[midi] Midi enabled! Waiting for device \"Late\"... "))
        t.source.inputs = listOf("Late Synth")
        t.hub.onDevicesChanged()
        assertEquals("[midi] device reconnected: Late Synth", t.console.entries.last().message)
        t.hub.onMessage("Late Synth", byteArrayOf(0xB0.toByte(), 7, 127))
        assertEquals(0.0, valueAt(input.state, 7.0, Undefined), "two seconds have not passed")
        t.timer.flush()
        t.hub.onMessage("Late Synth", byteArrayOf(0xB0.toByte(), 7, 127))
        assertEquals(1.0, valueAt(input.state, 7.0, Undefined))
        t.source.inputs = emptyList()
        t.hub.onDevicesChanged()
        assertEquals("[midi] device disconnected: Late Synth", t.console.entries.last().message)
        t.hub.onMessage("Late Synth", byteArrayOf(0xB0.toByte(), 7, 0))
        assertEquals(1.0, valueAt(input.state, 7.0, Undefined), "nothing is heard while the device is away")
    }

    @Test
    fun theDefaultInputWithNoDeviceIsAnError() {
        val t = testHub(emptyList())
        val e = assertFailsWith<CosicError> { t.hub.input(Undefined, announce = true) }
        assertEquals("\uD83D\uDD0C No MIDI devices found. Connect a device or enable IAC Driver.", e.message)
    }

    @Test
    fun theValuesAreStoredAfterAWhileAndNotForAnInputByNumber() {
        val store = MemoryStore()
        val t = testHub(listOf("A", "B"), store = store)
        t.hub.input("A", announce = false)
        t.hub.input(1.0, announce = false)
        t.timer.flush()
        t.hub.onMessage("A", byteArrayOf(0xB0.toByte(), 7, 127))
        t.hub.onMessage("B", byteArrayOf(0xB0.toByte(), 7, 127))
        assertEquals(emptyMap(), store.values, "nothing is written before the delay")
        t.timer.flush()
        assertEquals(setOf("strudel-midin-A-chanall", "strudel-midin-A-chan1"), store.values.keys)
        assertEquals("{\"7\":1}", store.values["strudel-midin-A-chan1"])
    }

    @Test
    fun aSlotThatIsNotInTheStoreYetStartsAtZero() {
        val t = testHub(listOf("A"), store = MemoryStore(mapOf("strudel-midin-A-chanall" to "not json")))
        val input = t.hub.input("A", announce = false)
        assertEquals(0.0, valueAt(input.state, 1.0, Undefined))
    }
}
