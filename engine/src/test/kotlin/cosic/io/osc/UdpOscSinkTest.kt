package cosic.io.osc

import cosic.FrameClock
import cosic.OscSink
import cosic.SpeechSink
import cosic.engineOf
import cosic.renderFrames
import cosic.evaluateOk
import cosic.audio.SR
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketTimeoutException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertContains
import kotlin.test.assertTrue

private fun receive(socket: DatagramSocket, millis: Int = 3000): ByteArray? {
    socket.soTimeout = millis
    val buffer = ByteArray(4096)
    val packet = DatagramPacket(buffer, buffer.size)
    return try {
        socket.receive(packet)
        buffer.copyOf(packet.length)
    } catch (_: SocketTimeoutException) {
        null
    }
}

/** The UDP sink against a socket of this process, and `.osc()` and `.speak()` through an engine. */
class UdpOscSinkTest {
    @Test
    fun aMessageArrivesAsABundleWhoseTimeTagIsTheTimeTheFrameIsHeard() {
        DatagramSocket(0, java.net.InetAddress.getLoopbackAddress()).use { server ->
            val base = System.nanoTime() + 250_000_000
            val sink = UdpOscSink({ FrameClock { frame -> base + frame * 1_000_000_000L / 48_000 } }, defaultPort = { server.localPort })
            val logged = CopyOnWriteArrayList<String>()
            val args = listOf<Any?>("s", "bd", "gain", 0.5)
            sink.send(null, null, "/dirt/play", args, 24_000) // half a second after the base
            val bytes = receive(server)!!
            val expectedMs = (base + 500_000_000) / 1e6 + (System.currentTimeMillis() - System.nanoTime() / 1e6)
            // the same bundle, to the precision of the time that passed between the two clock reads
            val reference = OscCodec.encodeBundle(expectedMs, "/dirt/play", args)
            assertEquals(reference.size, bytes.size)
            assertEquals(hex(reference).substring(0, 16), hex(bytes).substring(0, 16), "#bundle")
            assertEquals(hex(reference).substring(32), hex(bytes).substring(32), "the message")
            fun seconds(b: ByteArray) = (0 until 4).fold(0L) { acc, i -> (acc shl 8) or (b[8 + i].toLong() and 0xFF) }
            assertTrue(Math.abs(seconds(reference) - seconds(bytes)) <= 1, "the second of the time tag") // a second boundary may fall between the reads
            assertTrue(logged.isEmpty())
            sink.close()
        }
    }

    @Test
    fun theHostAndPortOfTheHapOverrideTheDefaults() {
        DatagramSocket(0, java.net.InetAddress.getLoopbackAddress()).use { server ->
            val sink = UdpOscSink({ FrameClock { System.nanoTime() } }, defaultHost = { "192.0.2.1" }, defaultPort = { 9 })
            sink.send("127.0.0.1", server.localPort, "/dirt/play", listOf("s", "hh"), 0)
            val bytes = receive(server)!!
            assertEquals("#bundle", String(bytes, 0, 7))
            sink.close()
        }
    }

    @Test
    fun aMessageWhoseTimeIsNotKnownIsDroppedWithALine() {
        val lines = CopyOnWriteArrayList<String>()
        DatagramSocket(0, java.net.InetAddress.getLoopbackAddress()).use { server ->
            val sink = UdpOscSink({ FrameClock { null } }, defaultPort = { server.localPort }, log = { lines.add(it) })
            sink.send(null, null, "/dirt/play", listOf("s", "bd"), 0)
            assertNull(receive(server, 300))
            assertEquals(listOf("[osc] clockbridge not ready"), lines)
            val noClock = UdpOscSink({ null }, defaultPort = { server.localPort }, log = { lines.add(it) })
            noClock.send(null, null, "/dirt/play", listOf("s", "bd"), 0)
            assertEquals(2, lines.size)
        }
    }

    @Test
    fun aPortOutOfRangeAndAnUnknownHostAreLines() {
        val lines = CopyOnWriteArrayList<String>()
        val sink = UdpOscSink({ FrameClock { System.nanoTime() } }, log = { lines.add(it) })
        sink.send(null, 70000, "/dirt/play", listOf("s", "bd"), 0)
        assertEquals("[osc] port out of range: 70000", lines.single())
        sink.send("no-such-host.invalid", 57120, "/dirt/play", listOf("s", "bd"), 0)
        val deadline = System.nanoTime() + 10_000_000_000L
        while (lines.size < 2 && System.nanoTime() < deadline) Thread.sleep(20)
        assertContains(lines[1], "no-such-host.invalid:57120")
        sink.close()
    }

    @Test
    fun theSinkStartsAgainAfterItWasClosed() {
        DatagramSocket(0, java.net.InetAddress.getLoopbackAddress()).use { server ->
            val sink = UdpOscSink({ FrameClock { System.nanoTime() } }, defaultPort = { server.localPort })
            sink.send(null, null, "/dirt/play", listOf("s", "a"), 0)
            assertTrue(receive(server) != null)
            sink.close()
            sink.send(null, null, "/dirt/play", listOf("s", "b"), 0)
            assertTrue(receive(server) != null)
            sink.close()
        }
    }

    // ------------------------------------------------------------------------------------------ through the engine

    private fun oscSink(into: MutableList<Sent>) = object : OscSink {
        override fun send(host: String?, port: Int?, address: String, args: List<Any?>, frame: Long) {
            into.add(Sent(host, port, address, args, frame))
        }
    }

    private fun speechSink(f: (String, String, Any?) -> Unit) = object : SpeechSink {
        override fun speak(text: String, lang: String, voice: Any?) = f(text, lang, voice)
    }

    private class Sent(val host: String?, val port: Int?, val address: String, val args: List<Any?>, val frame: Long)

    private fun waitFor(what: String, millis: Long = 5000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + millis * 1_000_000
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timed out waiting for $what" }
            Thread.sleep(5)
        }
    }

    @Test
    fun osc_sendsTheHapsOfThePatternAndTheyDoNotSound() {
        val engine = engineOf()
        val sent = CopyOnWriteArrayList<Sent>()
        engine.outputs.osc = oscSink(sent)
        engine.evaluateOk("""s("bd*2").n("<1 2>").oschost("10.0.0.7").oscport(9000).osc()""")
        engine.play()
        val out = engine.renderFrames(96_000)
        waitFor("two messages") { sent.size >= 2 }
        assertEquals(0f, cosic.audio.peak(out), "the haps are not played")
        val first = sent[0]
        assertEquals("/dirt/play", first.address)
        assertEquals("10.0.0.7", first.host)
        assertEquals(9000, first.port)
        assertEquals(listOf("cps", 0.5, "cycle", 0.0, "delta", 1.0, "s", "bd", "n", 1.0, "oschost", "10.0.0.7", "oscport", 9000.0), first.args)
        // the second hap of the cycle is half a cycle (one second) later: 48000 frames at 48 kHz and cps 0.5 is half a cycle
        assertEquals(48_000L, sent[1].frame - first.frame)
        engine.outputs.shutdown()
    }

    @Test
    fun osc_asAFunctionTakesAPattern() {
        val engine = engineOf()
        val sent = CopyOnWriteArrayList<Sent>()
        engine.outputs.osc = oscSink(sent)
        engine.evaluateOk("""all(osc); s("bd")""")
        engine.play()
        engine.renderFrames(48_000)
        waitFor("a message") { sent.isNotEmpty() }
        assertNull(sent[0].host)
        engine.outputs.shutdown()
    }

    @Test
    fun aHapThatCannotBeSentIsAnErrorLineAndPlaybackGoesOn() {
        val engine = engineOf()
        val sent = CopyOnWriteArrayList<Sent>()
        engine.outputs.osc = oscSink(sent)
        engine.evaluateOk("""s("bd").n("x").osc()""")
        engine.play()
        engine.renderFrames(48_000)
        waitFor("the error line") { engine.console.entries.any { it.message.startsWith("[getSchedule] error: ") } }
        assertEquals("[getSchedule] error: cannot parse as numeral: \"x\"", engine.console.entries.last { it.message.startsWith("[getSchedule]") }.message)
        assertTrue(sent.isEmpty())
        assertNull(engine.snapshot.runtimeError)
        engine.outputs.shutdown()
    }

    @Test
    fun withoutADeviceTheErrorIsStrudelsOwn() {
        val engine = engineOf()
        engine.evaluateOk("""s("bd").osc()""")
        engine.play()
        engine.renderFrames(48_000)
        waitFor("the error line") { engine.console.entries.any { it.message.contains("Could not connect to OSC server") } }
        engine.outputs.shutdown()
    }

    @Test
    fun theOscTargetSendsEveryHapAndSilencesTheEngine() {
        val engine = engineOf()
        val sent = CopyOnWriteArrayList<Sent>()
        engine.outputs.osc = oscSink(sent)
        engine.outputs.oscTarget = true
        engine.evaluateOk("""s("bd hh")""")
        engine.play()
        val out = engine.renderFrames(96_000)
        waitFor("two messages") { sent.size >= 2 }
        assertEquals(0f, cosic.audio.peak(out))
        assertEquals(listOf("bd", "hh"), sent.take(2).map { it.args[7] })
        engine.outputs.shutdown()
    }

    @Test
    fun speak_saysTheValueOfEveryHapWithTheLanguageAndTheVoice() {
        val engine = engineOf()
        class Said(val text: String, val lang: String, val voice: Any?)
        val said = CopyOnWriteArrayList<Said>()
        engine.outputs.speech = speechSink { text, lang, voice -> said.add(Said(text, lang, voice)) }
        engine.evaluateOk(""""hello world".speak("en", 2)""")
        engine.play()
        val out = engine.renderFrames(96_000)
        waitFor("two utterances") { said.size >= 2 }
        assertEquals(listOf("hello", "world"), said.take(2).map { it.text })
        assertEquals(listOf("en", "en"), said.take(2).map { it.lang })
        assertEquals(listOf<Any?>(2.0, 2.0), said.take(2).map { it.voice })
        assertEquals(0f, cosic.audio.peak(out), "speak takes the haps away from the audio")
        engine.outputs.shutdown()
    }

    @Test
    fun speak_takesAPatternOfLanguagesAndAVoiceByName() {
        val engine = engineOf()
        val said = CopyOnWriteArrayList<Triple<String, String, Any?>>()
        engine.outputs.speech = speechSink { text, lang, voice -> said.add(Triple(text, lang, voice)) }
        engine.evaluateOk(""""a b".speak("<en fr>", "Samantha")""")
        engine.play()
        engine.renderFrames(96_000)
        waitFor("two utterances") { said.size >= 2 }
        assertEquals(listOf(Triple("a", "en", "Samantha"), Triple("b", "en", "Samantha")), said.take(2))
        engine.outputs.shutdown()
    }

    @Test
    fun speak_saysAMapAsJavaScriptWould() {
        val engine = engineOf()
        val said = CopyOnWriteArrayList<String>()
        engine.outputs.speech = speechSink { text, _, _ -> said.add(text) }
        engine.evaluateOk("""s("bd").speak("en", 0)""")
        engine.play()
        engine.renderFrames(48_000)
        waitFor("an utterance") { said.isNotEmpty() }
        assertEquals("[object Object]", said[0])
        engine.outputs.shutdown()
    }

    @Test
    fun speak_asAFunctionIsCurried() {
        val engine = engineOf()
        val said = CopyOnWriteArrayList<String>()
        engine.outputs.speech = speechSink { text, _, _ -> said.add(text) }
        engine.evaluateOk("""speak("en", 0)("hi")""")
        engine.play()
        engine.renderFrames(48_000)
        waitFor("an utterance") { said.isNotEmpty() }
        assertEquals("hi", said[0])
        engine.outputs.shutdown()
    }
}
