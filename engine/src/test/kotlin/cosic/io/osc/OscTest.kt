package cosic.io.osc

import cosic.OscSink
import cosic.io.ConsoleLog
import cosic.io.OutputBus
import cosic.pattern.CosicError
import cosic.pattern.Hap
import cosic.pattern.Rational
import cosic.pattern.ScheduledHap
import cosic.pattern.TimeSpan
import cosic.pattern.Undefined
import cosic.pattern.jsonStringify
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

internal fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

internal fun unhex(text: String): ByteArray = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

/** A value of a golden case: JSON, with `{"$undefined":1}`, `{"$nan":1}` and `{"$inf":1}` for what JSON cannot say. */
internal fun revive(e: JsonElement): Any? = when (e) {
    is JsonNull -> null
    is JsonArray -> e.map(::revive)
    is JsonObject -> when {
        e.keys == setOf("\$undefined") -> Undefined
        e.keys == setOf("\$nan") -> Double.NaN
        e.keys == setOf("\$inf") -> Double.POSITIVE_INFINITY
        else -> LinkedHashMap<String, Any?>().also { m -> for ((k, v) in e) m[k] = revive(v) }
    }
    is JsonPrimitive -> when {
        e.isString -> e.content
        e.booleanOrNull != null -> e.booleanOrNull
        else -> e.content.toDouble()
    }
}

private val golden: JsonObject by lazy {
    Json.parseToJsonElement(OscTest::class.java.getResourceAsStream("/osc/golden.json")!!.readBytes().decodeToString()).jsonObject
}

private class RecordingSink : OscSink {
    class Sent(val host: String?, val port: Int?, val address: String, val args: List<Any?>, val frame: Long)

    val sent = ArrayList<Sent>()

    override fun send(host: String?, port: Int?, address: String, args: List<Any?>, frame: Long) {
        sent.add(Sent(host, port, address, args, frame))
    }
}

/** The bytes of `/dirt/play` as `osc.js` 2.4.5 writes them for the pinned `@strudel/osc` (tools/strudel-ref/osc-oracle.mjs makes the golden file). */
class OscTest {
    @Test
    fun theBundleOfTheDocumentedHapIsTheDocumentedBytes() {
        val hap = ScheduledHap(Hap(TimeSpan(Rational.ZERO, Rational.ONE), TimeSpan(Rational.ZERO, Rational.ONE), linkedMapOf<String, Any?>("s" to "bd")), 0, 0.0, 0.5, 0.0, 1.0)
        val bytes = OscCodec.encodeBundle(1_700_000_000_500.0, "/dirt/play", OscControls.fromHap(hap))
        assertEquals(
            "2362756e646c6500" + "e8fe6f8080000000" + "00000040" + "2f646972742f706c617900" + "00" + "2c73667366736673730000" + "00" +
                "63707300" + "3f000000" + "6379636c65000000" + "00000000" + "64656c7461000000" + "40000000" + "73000000" + "62640000",
            hex(bytes),
        )
        assertEquals(84, bytes.size)
    }

    @Test
    fun everyHapOfTheOracleGivesTheSameControlsAndTheSameBytes() {
        val cases = golden.getValue("cases").jsonArray.map { it.jsonObject }
        assertTrue(cases.size > 30)
        for (c in cases) {
            val name = c.getValue("name").jsonPrimitive.content
            val whole = c["whole"]?.takeIf { it !is JsonNull }?.jsonArray?.let { TimeSpan(Rational.of(it[0].jsonPrimitive.content), Rational.of(it[1].jsonPrimitive.content)) }
            val part = c["part"]?.takeIf { it !is JsonNull }?.jsonArray?.let { TimeSpan(Rational.of(it[0].jsonPrimitive.content), Rational.of(it[1].jsonPrimitive.content)) } ?: whole!!
            val cps = c.getValue("cps").jsonPrimitive.content.toDouble()
            val unixMs = c.getValue("unixMs").jsonPrimitive.content.toDouble()
            val scheduled = ScheduledHap(Hap(whole, part, revive(c.getValue("value"))), 4800, 0.1, cps, 0.0, 1.0)

            val controlsError = c["controlsError"]?.jsonPrimitive?.content
            if (controlsError != null) {
                assertEquals(controlsError, assertFailsWith<CosicError>(name) { OscControls.fromHap(scheduled) }.message, name)
                continue
            }
            val args = OscControls.fromHap(scheduled)
            assertEquals(c.getValue("args").jsonPrimitive.content, jsonStringify(args), "$name: the arguments")

            val sendError = c["sendError"]?.jsonPrimitive?.content
            if (sendError != null) {
                val thrown = assertFailsWith<CosicError>(name) { OscCodec.encodeBundle(unixMs, "/dirt/play", args) }
                assertEquals(sendError, thrown.message!!.lines().first().take(sendError.length), name)
                continue
            }
            assertEquals(c.getValue("bytes").jsonPrimitive.content, hex(OscCodec.encodeBundle(unixMs, "/dirt/play", args)), "$name: the bundle")

            // and what the hook hands to the device: the same arguments, and where to
            val sink = RecordingSink()
            val bus = OutputBus(ConsoleLog(), autoStart = false)
            bus.osc = sink
            OscHook(bus).run(scheduled)
            val sent = sink.sent.single()
            assertEquals("/dirt/play", sent.address, name)
            assertEquals(4800, sent.frame, name)
            assertEquals(jsonStringify(args), jsonStringify(sent.args), name)
            assertEquals(c["host"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content, sent.host, "$name: host")
            assertEquals(c["port"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.toInt(), sent.port, "$name: port")
        }
    }

    @Test
    fun theMessagesOfTheCodecAreTheBytesOfOscJs() {
        for (c in golden.getValue("codec").jsonArray.map { it.jsonObject }) {
            val name = c.getValue("name").jsonPrimitive.content
            val args = revive(Json.parseToJsonElement(c.getValue("args").jsonPrimitive.content)) as List<*>
            val error = c["error"]?.jsonPrimitive?.content
            if (error != null) {
                val thrown = assertFailsWith<CosicError>(name) { OscCodec.encodeMessage("/dirt/play", args) }
                assertEquals(error.take(30), thrown.message!!.take(30), name)
                assertFailsWith<CosicError>(name) { OscCodec.check(args) }
            } else {
                assertEquals(c.getValue("message").jsonPrimitive.content, hex(OscCodec.encodeMessage("/dirt/play", args)), name)
                OscCodec.check(args)
            }
        }
    }

    @Test
    fun anAddressWithoutASlashIsNotAMessage() {
        assertFailsWith<CosicError> { OscCodec.encodeMessage("dirt", emptyList()) }
    }

    @Test
    fun aTimeOfZeroIsNowAsInOscJs() {
        val bytes = OscCodec.encodeBundle(0.0, "/a", emptyList())
        val seconds = ((bytes[8].toLong() and 0xFF) shl 24) or ((bytes[9].toLong() and 0xFF) shl 16) or ((bytes[10].toLong() and 0xFF) shl 8) or (bytes[11].toLong() and 0xFF)
        assertTrue(Math.abs(seconds - (System.currentTimeMillis() / 1000 + 2208988800L)) <= 2)
    }

    @Test
    fun aHapWithoutADeviceIsAnErrorForTheConsole() {
        val bus = OutputBus(ConsoleLog(), autoStart = false)
        val hap = ScheduledHap(Hap(TimeSpan(Rational.ZERO, Rational.ONE), TimeSpan(Rational.ZERO, Rational.ONE), linkedMapOf<String, Any?>("s" to "bd")), 0, 0.0, 1.0, 0.0, 1.0)
        assertEquals("Could not connect to OSC server. Is it running?", assertFailsWith<CosicError> { OscHook(bus).run(hap) }.message)
    }
}
