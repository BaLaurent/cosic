package cosic.io.serial

import cosic.EvalReport
import cosic.engineOf
import cosic.evaluateOk
import cosic.renderFrames
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private fun waitFor(what: String, millis: Long = 5000, condition: () -> Boolean) {
    val deadline = System.nanoTime() + millis * 1_000_000
    while (!condition()) {
        check(System.nanoTime() < deadline) { "timed out waiting for $what" }
        Thread.sleep(5)
    }
}

/** A port that records what is written and when, and fails on demand. */
private class FakePort : SerialPort {
    val writes = CopyOnWriteArrayList<Pair<Long, String>>()

    @Volatile
    var failWith: IOException? = null

    @Volatile
    var closed = false

    override fun write(bytes: ByteArray) {
        failWith?.let { throw it }
        writes += System.nanoTime() to String(bytes)
    }

    override fun close() {
        closed = true
    }

    val texts get() = writes.map { it.second }
}

/** An opener that answers at once with [port], or later through [pending]. */
private class FakeOpener(var port: SerialPort? = null, val async: Boolean = false) : SerialPortOpener {
    val opened = CopyOnWriteArrayList<SerialRoute>()
    val pending = CopyOnWriteArrayList<(SerialPort?) -> Unit>()

    override fun open(route: SerialRoute, result: (SerialPort?) -> Unit) {
        opened += route
        if (async) pending += result else result(port)
    }
}

class SerialOutputTest {
    @Test
    fun aMessageIsWrittenWhenItIsDueAndNotBefore() {
        val port = FakePort()
        val out = SerialOutput(FakeOpener(port))
        out.prepare(SerialRoute())
        val start = System.nanoTime()
        assertTrue(out.send("default", "late".toByteArray(), start + 150_000_000))
        assertTrue(out.send("default", "early".toByteArray(), start + 20_000_000))
        waitFor("both writes") { port.writes.size == 2 }
        assertEquals(listOf("early", "late"), port.texts, "in the order of their times")
        assertTrue(port.writes[0].first - start >= 20_000_000)
        assertTrue(port.writes[1].first - start >= 150_000_000)
        out.close()
    }

    @Test
    fun aMessageForAPortThatIsNotOpenIsDropped() {
        val opener = FakeOpener(async = true)
        val out = SerialOutput(opener)
        assertFalse(out.send("default", "x".toByteArray(), 0), "nothing was prepared")
        out.prepare(SerialRoute())
        assertFalse(out.send("default", "x".toByteArray(), 0), "the user has not answered yet")
        val port = FakePort()
        opener.pending.single()(port)
        assertTrue(out.isOpen("default"))
        assertTrue(out.send("default", "y".toByteArray(), 0))
        waitFor("the write") { port.writes.isNotEmpty() }
        assertEquals(listOf("y"), port.texts)
        out.close()
    }

    @Test
    fun preparingAgainDoesNotOpenAnotherPort() {
        val opener = FakeOpener(FakePort())
        val out = SerialOutput(opener)
        repeat(3) { out.prepare(SerialRoute()) }
        assertEquals(1, opener.opened.size)
        out.prepare(SerialRoute(name = "other"))
        assertEquals(listOf("default", "other"), opener.opened.map { it.name })
        out.close()
    }

    @Test
    fun aFailedWriteClosesThePortAndTheNextPrepareOpensItAgain() {
        val first = FakePort().apply { failWith = IOException("unplugged") }
        val opener = FakeOpener(first)
        val lines = CopyOnWriteArrayList<String>()
        val out = SerialOutput(opener, { lines += it })
        out.prepare(SerialRoute())
        out.send("default", "a".toByteArray(), 0)
        waitFor("the report") { lines.isNotEmpty() }
        waitFor("the port to be closed") { first.closed }
        assertEquals(listOf("serial write failed: unplugged"), lines)
        assertFalse(out.send("default", "b".toByteArray(), 0))
        val second = FakePort()
        opener.port = second
        out.prepare(SerialRoute())
        assertEquals(2, opener.opened.size)
        assertTrue(out.send("default", "c".toByteArray(), 0))
        waitFor("the write") { second.writes.isNotEmpty() }
        assertEquals(listOf("c"), second.texts)
        out.close()
    }

    @Test
    fun aPortThatCouldNotBeOpenedIsReportedAndTriedAgainByTheNextPrepare() {
        val opener = FakeOpener(null)
        val lines = CopyOnWriteArrayList<String>()
        val out = SerialOutput(opener, { lines += it })
        out.prepare(SerialRoute(name = "p"))
        assertEquals(listOf("serial port 'p' could not be opened"), lines)
        out.prepare(SerialRoute(name = "p"))
        assertEquals(2, opener.opened.size)
        out.close()
    }

    @Test
    fun closeClosesThePortsAndDropsWhatWaits() {
        val port = FakePort()
        val out = SerialOutput(FakeOpener(port))
        out.prepare(SerialRoute())
        out.send("default", "never".toByteArray(), System.nanoTime() + 60_000_000_000)
        out.close()
        assertTrue(port.closed)
        assertFalse(out.send("default", "x".toByteArray(), 0))
        assertEquals(emptyList(), port.texts)
    }

    @Test
    fun aProgramSendsItsHapsToThePortAndSilencesThem() {
        val port = FakePort()
        val out = SerialOutput(FakeOpener(port))
        val engine = engineOf()
        engine.outputs.serial = out
        val report = engine.evaluateOk("""s("bd sd").n("<1 2>").serial()""")
        assertTrue(report.warnings.isEmpty())
        assertTrue(out.isOpen("default"), ".serial() prepares the port when the program is evaluated")
        engine.play()
        val audio = engine.renderFrames(96_000)
        assertEquals(0f, audio.maxOf { kotlin.math.abs(it) }, "serial haps are dominant: no default sound")
        waitFor("two messages") { port.writes.size >= 2 }
        assertEquals(listOf("s:bdn:1", "s:sdn:1"), port.texts.take(2))
        engine.outputs.shutdown()
        out.close()
    }

    @Test
    fun theArgumentsGiveTheRoute() {
        val opener = FakeOpener(FakePort())
        val out = SerialOutput(opener)
        val engine = engineOf()
        engine.outputs.serial = out
        engine.evaluateOk("""s("bd").serial(9600, true, true, 'board')""")
        assertEquals(listOf(SerialRoute(9600, true, true, "board")), opener.opened)
        assertIs<EvalReport.Failed>(engine.evaluate("""s("bd").serial('fast')"""))
        engine.outputs.shutdown()
        out.close()
    }

    @Test
    fun withoutASerialOutputTheHapsAreReportedAndNothingCrashes() {
        val engine = engineOf()
        engine.evaluateOk("""s("bd").serial()""")
        engine.play()
        engine.renderFrames(96_000)
        waitFor("the error line") { engine.console.entries.any { it.message == "[getSchedule] error: serial output is not available" } }
        engine.outputs.shutdown()
    }

    @Test
    fun actionMessagesCarryTheirCrc() {
        val port = FakePort()
        val out = SerialOutput(FakeOpener(port))
        val engine = engineOf()
        engine.outputs.serial = out
        engine.evaluateOk("""s("bd").withValue(v => ({action: "go"})).serial(115200, true)""")
        engine.play()
        engine.renderFrames(96_000)
        waitFor("a message") { port.writes.isNotEmpty() }
        val bytes = port.texts.first().toByteArray(Charsets.ISO_8859_1)
        assertEquals(0x7C.toByte(), bytes[bytes.size - 4])
        assertEquals(0x3B.toByte(), bytes.last())
        engine.outputs.shutdown()
        out.close()
    }
}
