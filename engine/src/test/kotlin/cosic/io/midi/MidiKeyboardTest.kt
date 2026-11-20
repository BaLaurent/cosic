package cosic.io.midi

import cosic.mini.m
import cosic.pattern.CosicError
import cosic.pattern.Hap
import cosic.pattern.QueryContext
import cosic.pattern.Rational
import cosic.pattern.TimeSpan
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `midikeys`: the notes a keyboard plays, against what the real `midikeys` of the pinned `midi.mjs` makes of the same messages (the recording of `midi-oracle.mjs`). */
class MidiKeyboardTest {
    private val inputNames = MidiVectors.root.getValue("input").jsonObject.getValue("inputs").jsonArray.map { it.jsonPrimitive.content }

    private fun keyboardOf(t: TestHub, spec: Any?): Result<MidiKeyboard> = runCatching {
        val key = cosic.pattern.jsToString(spec)
        val input = t.hub.input(spec, announce = true)
        val device = input.initialDevice ?: throw CosicError("[midi] Midi device \"$key\" not found.. connected devices: ${midiDeviceNames(t.hub.inputNames())}")
        t.hub.keyboard(key).also { it.listenTo(device) }
    }

    private fun notes(keyboard: MidiKeyboard, noteLength: Any?, scheduler: Boolean = false): List<Hap> {
        val pattern = keyboard.pattern(noteLength)
        val query = { pattern.queryArc(Rational.ZERO, Rational.ONE) }
        return if (scheduler) QueryContext.asScheduler(query) else query()
    }

    @Test
    fun notesAreWhatStrudelsMidikeysGives() {
        val scenarios = MidiVectors.list("midikeys")
        assertTrue(scenarios.size >= 4)
        for (scenario in scenarios) {
            val name = scenario.getValue("name").jsonPrimitive.content
            val spec = MidiVectors.value(scenario.getValue("input"))
            val t = testHub(inputNames)
            t.hub.isPlaying = { scenario.getValue("started").jsonPrimitive.content == "true" }
            val keyboard = keyboardOf(t, spec)
            val expectedError = scenario["error"]?.takeIf { it.toString() != "null" }?.jsonPrimitive?.content
            if (expectedError != null) {
                assertEquals(expectedError, keyboard.exceptionOrNull()?.message, name)
                continue
            }
            val device = if (spec is Double) inputNames[spec.toInt()] else spec as String
            for (m in scenario.getValue("messages").jsonArray) t.hub.onMessage(device, midiBytes(m.jsonArray))
            for (q in scenario.getValue("queries").jsonArray.map { it.jsonObject }) {
                val noteLength = q.getValue("noteLength").let { v ->
                    val raw = MidiVectors.value(v)
                    if (raw is String) m(raw, 0) else if (raw === cosic.pattern.Undefined) 0.5 else raw
                }
                val expected = q.getValue("haps").jsonArray.map { it.jsonObject }
                val got = notes(keyboard.getOrThrow(), noteLength)
                assertEquals(expected.size, got.size, "$name: notes for ${q["noteLength"]}")
                for ((i, e) in expected.withIndex()) {
                    assertEquals(MidiVectors.value(e.getValue("value")), got[i].value, "$name: note $i")
                    val length = e.getValue("length").jsonPrimitive.content.toDouble()
                    assertTrue(abs(length - got[i].whole!!.duration.toDouble()) < 1e-12, "$name: length of note $i is $length")
                }
            }
        }
    }

    @Test
    fun aKeyIsMadeToSoundAtOnceAndTheNotesGoWhenTheSchedulerHasThem() {
        val t = testHub(listOf("Keys"))
        val calls = ArrayList<Triple<Double, (Hap) -> Boolean, ((Boolean) -> Unit)?>>()
        t.hub.attach(trigger = { latency, match, done -> calls.add(Triple(latency, match, done)) }, isPlaying = { true })
        val keyboard = keyboardOf(t, "Keys").getOrThrow()
        t.hub.onMessage("Keys", byteArrayOf(0x90.toByte(), 60, 100))
        assertEquals(1, calls.size)
        assertEquals(0.01, calls[0].first)
        // the keyboard's own notes match, the ones of other inputs and of the program's other patterns do not
        val own = notes(keyboard, 0.5).single()
        assertTrue(calls[0].second(own))
        assertFalse(calls[0].second(own.withValue { cosic.pattern.Undefined }))
        assertFalse(calls[0].second(own.withValue { mapOf("midikey" to "Other_60") }))
        // not played yet: the notes wait
        assertEquals(1, notes(keyboard, 0.5).size)
        calls[0].third!!.invoke(false)
        assertEquals(1, notes(keyboard, 0.5).size)
        calls[0].third!!.invoke(true)
        assertEquals(0, notes(keyboard, 0.5).size)
    }

    @Test
    fun theSchedulerTakesTheNotesOnceWhenItFindsThem() {
        val t = testHub(listOf("Keys"))
        t.hub.isPlaying = { true }
        val keyboard = keyboardOf(t, "Keys").getOrThrow()
        t.hub.onMessage("Keys", byteArrayOf(0x90.toByte(), 60, 100))
        t.hub.onMessage("Keys", byteArrayOf(0x90.toByte(), 64, 100))
        assertEquals(2, notes(keyboard, 0.5, scheduler = true).size)
        assertEquals(0, notes(keyboard, 0.5, scheduler = true).size)
    }

    @Test
    fun aNoteHappensWhereTheQueryBegins() {
        val t = testHub(listOf("Keys"))
        t.hub.isPlaying = { true }
        val keyboard = keyboardOf(t, "Keys").getOrThrow()
        t.hub.onMessage("Keys", byteArrayOf(0x90.toByte(), 60, 100))
        val hap = keyboard.pattern(0.25).queryArc(Rational(7, 16), Rational(8, 16)).single()
        assertEquals(TimeSpan(Rational(7, 16), Rational(11, 16)), hap.whole)
        assertEquals(hap.whole, hap.part)
        assertTrue(hap.hasOnset())
    }

    @Test
    fun keysAreIgnoredBeforeTheProgramPlaysAndNoteOffsEver() {
        val t = testHub(listOf("Keys"))
        val keyboard = keyboardOf(t, "Keys").getOrThrow()
        t.hub.onMessage("Keys", byteArrayOf(0x90.toByte(), 60, 100))
        assertEquals(0, notes(keyboard, 0.5).size)
        t.hub.isPlaying = { true }
        t.hub.onMessage("Keys", byteArrayOf(0x80.toByte(), 60, 0))
        t.hub.onMessage("Keys", byteArrayOf(0x90.toByte(), 60, 0))
        t.hub.onMessage("Keys", byteArrayOf(0xB0.toByte(), 7, 5))
        assertEquals(0, notes(keyboard, 0.5).size)
    }

    @Test
    fun aKeyboardListensToTheDeviceItWasGivenAndOnlyThat() {
        val t = testHub(listOf("Keys", "Pads"))
        t.hub.isPlaying = { true }
        val keyboard = keyboardOf(t, "Keys").getOrThrow()
        t.hub.onMessage("Pads", byteArrayOf(0x90.toByte(), 60, 100))
        assertEquals(0, notes(keyboard, 0.5).size)
        t.hub.onMessage("Keys", byteArrayOf(0x9F.toByte(), 60, 100))
        val value = notes(keyboard, 0.5).single().value as Map<*, *>
        assertEquals(listOf("midikey", "note", "velocity", "midichan"), value.keys.toList())
        assertEquals("Keys_60", value["midikey"])
        assertEquals(16.0, value["midichan"])
    }

    @Test
    fun aKeyboardThatIsNotThereIsAnError() {
        val t = testHub(listOf("Keys"))
        val e = assertFailsWith<CosicError> { keyboardOf(t, "Nowhere").getOrThrow() }
        assertEquals("[midi] Midi device \"Nowhere\" not found.. connected devices: 'Keys'", e.message)
        assertNull(runCatching { keyboardOf(testHub(listOf("Keys")), "Keys").getOrThrow() }.exceptionOrNull())
    }
}
