package cosic.io.midi

import cosic.CosicEngine
import cosic.EvalReport
import cosic.MidiSink
import cosic.RemoteJson
import cosic.audio.SR
import cosic.audio.peak
import cosic.engineOf
import cosic.evaluateOk
import cosic.renderFrames
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val CYCLE = 96_000 // frames per cycle at cps 0.5 and 48 kHz
private const val START = 2_400L // the first frame of a program that plays: after the start delay of 50 ms

private fun waitFor(what: String, millis: Long = 5000, condition: () -> Boolean) {
    val deadline = System.nanoTime() + millis * 1_000_000
    while (!condition()) {
        check(System.nanoTime() < deadline) { "timed out waiting for $what" }
        Thread.sleep(5)
    }
}

/** A MIDI sink that keeps what it is given. */
private class RecordingSink(private val names: List<String>) : MidiSink {
    class Sent(val output: Int, val bytes: List<Int>, val frame: Long, val offsetMs: Double) {
        override fun toString() = "$output:" + bytes.joinToString(" ") { "%02X".format(it) } + "@$frame+$offsetMs"
    }

    val sent = CopyOnWriteArrayList<Sent>()

    override fun outputNames(): List<String> = names

    override fun send(output: Int, bytes: ByteArray, frame: Long, offsetMs: Double) {
        sent.add(Sent(output, bytes.map { it.toInt() and 0xFF }, frame, offsetMs))
    }
}

private fun CosicEngine.lines(): List<String> = console.entries.map { it.message }

/** `.midi()`, `midin`, `midikeys`, `midimaps` and `defaultmidimap` in programs, through the whole engine: language, scheduler, output bus, sinks. */
class MidiProgramTest {
    private fun engineWithMidi(vararg outputs: String): Pair<CosicEngine, RecordingSink> {
        val engine = engineOf()
        val sink = RecordingSink(outputs.toList().ifEmpty { listOf("IAC Driver Bus 1") })
        engine.outputs.midi = sink
        // the audio device has said when the frames are heard
        engine.clock.beginSession(System.nanoTime(), 0, SR)
        return engine to sink
    }

    @Test
    fun theEventsOfAPatternAreSentAsMidiAndDoNotSound() {
        val (engine, sink) = engineWithMidi()
        engine.evaluateOk("""note("c4 e4").s("sine").midi()""")
        engine.play()
        val audio = engine.renderFrames(CYCLE)
        waitFor("the messages of the first two events") { sink.sent.size >= 5 }
        assertEquals(0f, peak(audio), "a dominant hook takes the sound away")
        // the first event begins at cycle 0, so a Start goes with it; the note off comes 1 ms before its length (a second) is over
        assertEquals(
            listOf("0:90 3C 72@$START+0.0", "0:80 3C 40@$START+999.0", "0:FA@$START+0.0", "0:90 40 72@${START + 48_000}+0.0", "0:80 40 40@${START + 48_000}+999.0"),
            sink.sent.take(5).map { it.toString() },
        )
        engine.outputs.shutdown()
    }

    @Test
    fun thePortIsChosenByNameOrNumberOrFromTheOptions() {
        val (engine, sink) = engineWithMidi("IAC Driver Bus 1", "Arturia KeyStep 32")
        engine.evaluateOk(
            """
            $: note("c4").midi('Arturia')
            $: note("d4").midi(0)
            $: note("e4").midi({port: 'Arturia', midichannel: 3})
            $: note("f4").midi()
            """.trimIndent(),
        )
        engine.play()
        engine.renderFrames(CYCLE / 2)
        waitFor("four notes on") { sink.sent.count { it.bytes[0] and 0xF0 == 0x90 } >= 4 }
        val ons = sink.sent.filter { it.bytes[0] and 0xF0 == 0x90 }
        assertEquals(mapOf(0x3C to 1, 0x3E to 0, 0x40 to 1, 0x41 to 0), ons.associate { it.bytes[1] to it.output })
        assertEquals(0x92, ons.single { it.bytes[1] == 0x40 }.bytes[0], "midichannel 3 of the options")
        engine.outputs.shutdown()
    }

    @Test
    fun theMessagesOfEveryControlGoThroughTheLanguage() {
        val (engine, sink) = engineWithMidi()
        engine.evaluateOk(
            """
            $: s("x").ccn(74).ccv(.5).midichan(2).midi()
            $: s("x").control([7, 1]).midi()
            $: s("x").progNum(10).midi()
            $: s("x").midibend(1).midi()
            $: s("x").miditouch(.5).midi()
            $: s("x").sysex([119, [1, 2]]).midi()
            $: s("x").nrpnn([1, 8]).nrpv(123).midi()
            """.trimIndent(),
        )
        engine.play()
        engine.renderFrames(CYCLE)
        waitFor("the messages") { sink.sent.size >= 14 }
        val sent = sink.sent.map { it.bytes.joinToString(" ") { b -> "%02X".format(b) } }
        for (expected in listOf("B1 4A 40", "B0 07 7F", "C0 0A", "E0 7F 7F", "D0 40", "F0 77 01 02 F7", "B0 63 01", "B0 06 7B")) assertContains(sent, expected)
        engine.outputs.shutdown()
    }

    @Test
    fun aMidimapTurnsControlsIntoControlChangesBeforeTheNote() {
        val (engine, sink) = engineWithMidi()
        engine.evaluateOk(
            """
            defaultmidimap({ lpf: 74 })
            $: note("c4").lpf(0.5).midi()
            """.trimIndent(),
        )
        engine.play()
        engine.renderFrames(CYCLE / 2)
        waitFor("three messages") { sink.sent.size >= 3 }
        assertEquals(listOf("B0 4A 40", "90 3C 72", "80 3C 40"), sink.sent.take(3).map { it.bytes.joinToString(" ") { b -> "%02X".format(b) } })
        engine.outputs.shutdown()
    }

    @Test
    fun midimapsGivesMappingsByNameAndFetchesFilesWithTheRemoteOfTheEngine() {
        val (engine, sink) = engineWithMidi()
        val asked = ArrayList<String>()
        engine.remote = RemoteJson { url ->
            asked.add(url)
            mapOf("fromweb" to mapOf("lpf" to 20.0))
        }
        engine.evaluateOk(
            """
            await midimaps({ mine: { lpf: 74 } })
            await midimaps('github:someone/repo')
            $: note("c4").lpf(0.5).midimap('mine').midi()
            $: note("d4").lpf(0.5).midimap('fromweb').midi()
            """.trimIndent(),
        )
        assertEquals(listOf("https://raw.githubusercontent.com/someone/repo/main/midimap.json"), asked)
        engine.play()
        engine.renderFrames(CYCLE / 2)
        waitFor("the control changes") { sink.sent.count { it.bytes[0] == 0xB0 } >= 2 }
        assertEquals(setOf("B0 4A 40", "B0 14 40"), sink.sent.filter { it.bytes[0] == 0xB0 }.map { it.bytes.joinToString(" ") { b -> "%02X".format(b) } }.toSet())
        engine.outputs.shutdown()
    }

    @Test
    fun anErrorOfTheHandlerIsAnErrorLineAndKeepsWhatCameBefore() {
        val (engine, sink) = engineWithMidi()
        engine.evaluateOk("""$: note("c4").progNum(200).midi()""")
        engine.play()
        engine.renderFrames(CYCLE / 2)
        waitFor("the error line") { "[getSchedule] error: expected progNum (program change) to be a number between 0 and 127" in engine.lines() }
        // the note was sent before the program change threw
        assertEquals(listOf("90 3C 72", "80 3C 40"), sink.sent.map { it.bytes.joinToString(" ") { b -> "%02X".format(b) } })
        engine.outputs.shutdown()
    }

    @Test
    fun withoutASinkTheProgramSaysMidiIsNotEnabled() {
        val engine = engineOf()
        engine.clock.beginSession(System.nanoTime(), 0, SR)
        engine.evaluateOk("""note("c4").midi()""")
        engine.play()
        engine.renderFrames(CYCLE / 2)
        waitFor("the line") { "Midi not enabled" in engine.lines() }
        engine.outputs.shutdown()
    }

    @Test
    fun aHapWhoseTimeIsNotKnownYetIsSkipped() {
        val (engine, sink) = engineWithMidi()
        engine.clock.reset()
        engine.evaluateOk("""note("c4").midi()""")
        engine.play()
        engine.renderFrames(CYCLE / 2)
        waitFor("the line") { "[midi] clockbridge not ready" in engine.lines() }
        assertEquals(emptyList(), sink.sent)
        engine.outputs.shutdown()
    }

    @Test
    fun aPortThatIsNotThereIsLoggedAndNothingIsSent() {
        val (engine, sink) = engineWithMidi("IAC Driver Bus 1", "Arturia KeyStep 32")
        engine.evaluateOk("""note("c4").midi('nope')""")
        engine.play()
        engine.renderFrames(CYCLE / 2)
        waitFor("the line") { engine.lines().any { it.startsWith("[midi] midiport \"nope\" not found!") } }
        assertContains(engine.lines(), "[midi] midiport \"nope\" not found! available: 'IAC Driver Bus 1', 'Arturia KeyStep 32'")
        assertEquals(emptyList(), sink.sent)
        engine.outputs.shutdown()
    }

    @Test
    fun aPatternAsThePortIsAnErrorWithStrudelsHint() {
        val (engine, _) = engineWithMidi("Arturia KeyStep 32")
        val report = engine.evaluate("""note("c4").midi("<0 1>")""")
        assertIs<EvalReport.Failed>(report)
        assertContains(
            report.toString(),
            ".midi does not accept Pattern input for midiport. Make sure to pass device name with single quotes. Example: .midi('Arturia KeyStep 32')",
        )
    }

    @Test
    fun theFirstProgramToUseMidiSaysWhichDevicesThereAre() {
        val (engine, _) = engineWithMidi("IAC Driver Bus 1", "Arturia KeyStep 32")
        engine.evaluateOk("""note("c4").midi()""")
        assertContains(engine.lines(), "Midi enabled! Using \"IAC Driver Bus 1\". Also available: 'Arturia KeyStep 32'")
        engine.evaluateOk("""note("d4").midi()""")
        assertEquals(1, engine.lines().count { it.startsWith("Midi enabled!") }, "only the first program announces the devices")
    }

    @Test
    fun midinGivesTheValuesOfAControlChange() {
        val engine = engineOf()
        val t = testHub(listOf("Arturia KeyStep 32"))
        engine.outputs.midiHub.source = t.source
        engine.outputs.midiHub.scheduler = t.timer.schedule
        engine.evaluateOk(
            """
            const cc = await midin('Arturia')
            $: note("c4").lpf(cc(1).range(0, 1000)).onSchedule((hap) => { console.log('cutoff ' + hap.value.cutoff) }, false)
            """.trimIndent(),
        )
        assertEquals(setOf(cosic.InputKind.MIDI_IN), engine.program!!.inputsUsed)
        t.timer.flush() // two seconds later the input listens
        engine.outputs.midiHub.onMessage("Arturia KeyStep 32", byteArrayOf(0xB0.toByte(), 1, 127))
        engine.play()
        engine.renderFrames(CYCLE / 2)
        waitFor("the cutoff of the first event") { engine.lines().any { it.startsWith("cutoff ") } }
        assertEquals("cutoff 1000", engine.lines().first { it.startsWith("cutoff ") })
        engine.outputs.shutdown()
    }

    @Test
    fun aKeyPlayedOnAKeyboardSoundsWhileTheProgramPlays() {
        val engine = engineOf()
        val t = testHub(listOf("Keys"))
        engine.outputs.midiHub.source = t.source
        engine.evaluateOk(
            """
            const kb = await midikeys('Keys')
            $: kb().s("sine")
            """.trimIndent(),
        )
        engine.play()
        assertEquals(0f, peak(engine.renderFrames(SR / 2)), "no key, no sound")
        engine.outputs.midiHub.onMessage("Keys", byteArrayOf(0x90.toByte(), 69, 100))
        val heard = engine.renderFrames(SR / 2)
        assertTrue(peak(heard) > 0.01f, "the key sounds")
        // made to sound at once (10 ms and a block), not at the next chunk the scheduler queries, which is 100 ms ahead
        val firstFrame = heard.indexOfFirst { kotlin.math.abs(it) > 0.001f } / 2
        assertTrue(firstFrame in 0..3_000, "the key sounds after $firstFrame frames")
        // it sounds once: the note is a second long (cps 0.5, a note of half a cycle), then nothing
        engine.renderFrames(SR)
        assertEquals(0f, peak(engine.renderFrames(SR / 2)).let { if (it < 0.001f) 0f else it }, "and then it is over")
        engine.outputs.shutdown()
    }
}
