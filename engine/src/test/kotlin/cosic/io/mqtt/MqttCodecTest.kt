package cosic.io.mqtt

import java.io.ByteArrayInputStream
import java.io.EOFException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

internal fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

internal fun bytesOf(hex: String): ByteArray = hex.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

/** The packets of MQTT 3.1.1 that an output writes and reads, against the vectors of the plan and the bytes Paho writes (`tools/strudel-ref/mqtt-oracle.mjs`). */
class MqttCodecTest {
    private val paho: JsonObject = Json.parseToJsonElement(
        javaClass.getResourceAsStream("/mqtt/paho.json")!!.readBytes().decodeToString(),
    ).jsonObject

    private fun JsonObject.str(key: String): String? = get(key)?.takeIf { it !is JsonNull }?.jsonPrimitive?.content

    @Test
    fun publishOfTwoCharactersTopicAndTwoBytes() {
        assertEquals("30 06 00 02 2f 74 68 69".replace(" ", ""), hex(MqttCodec.publish("/t", "hi".toByteArray())))
    }

    @Test
    fun connectWithoutAndWithCredentials() {
        assertEquals("100d00044d5154540402003c000163", hex(MqttCodec.connect("c", null, null, 60)))
        assertEquals("101300044d51545404c2003c000163000175000170", hex(MqttCodec.connect("c", "u", "p", 60)))
        // a password is only written next to a username
        assertEquals("100d00044d5154540402003c000163", hex(MqttCodec.connect("c", null, "p", 60)))
    }

    @Test
    fun pingAndDisconnect() {
        assertEquals("c000", hex(MqttCodec.pingReq()))
        assertEquals("e000", hex(MqttCodec.disconnect()))
    }

    @Test
    fun remainingLengthVarints() {
        val cases = mapOf(
            0 to "00", 127 to "7f", 128 to "8001", 16383 to "ff7f", 16384 to "808001",
            2_097_151 to "ffff7f", 2_097_152 to "80808001", 268_435_455 to "ffffff7f",
        )
        for ((n, expected) in cases) assertEquals(expected, hex(MqttCodec.encodeRemainingLength(n)), "length $n")
        assertFailsWith<IllegalArgumentException> { MqttCodec.encodeRemainingLength(268_435_456) }
    }

    @Test
    fun connectsOfPaho() {
        val connects = paho["connect"]!!.jsonArray
        assertTrue(connects.size >= 5)
        for (c in connects.map { it.jsonObject }) {
            assertEquals(listOf("mqtt"), c["protocols"]!!.jsonArray.map { it.jsonPrimitive.content })
            val ours = MqttCodec.connect(c.str("clientId")!!, c.str("userName"), c.str("password"), 60)
            assertEquals(c.str("hex"), hex(ours), "client ${c.str("clientId")} user ${c.str("userName")}")
        }
    }

    @Test
    fun publishesOfPaho() {
        for (p in paho["publish"]!!.jsonArray.map { it.jsonObject }) {
            assertEquals(p.str("hex"), hex(MqttCodec.publish(p.str("topic")!!, p.str("payload")!!.toByteArray())), "topic ${p.str("topic")}")
        }
    }

    @Test
    fun publishLengthsOfPaho() {
        for (p in paho["publishLengths"]!!.jsonArray.map { it.jsonObject }) {
            val n = p.str("payloadBytes")!!.toInt()
            val ours = MqttCodec.publish("/t", ByteArray(n) { 'x'.code.toByte() })
            assertEquals(p.str("totalBytes")!!.toInt(), ours.size, "payload $n")
            assertEquals(p.str("head"), hex(ours.copyOf(6)), "payload $n")
        }
    }

    @Test
    fun connackAndPingResp() {
        val ack = MqttCodec.readPacket(ByteArrayInputStream(bytesOf("20020100")))
        assertIs<MqttPacket.ConnAck>(ack)
        assertTrue(ack.sessionPresent)
        assertEquals(0, ack.returnCode)
        assertIs<MqttPacket.PingResp>(MqttCodec.readPacket(ByteArrayInputStream(bytesOf("d000"))))
        val other = MqttCodec.readPacket(ByteArrayInputStream(bytesOf("3003616263")))
        assertIs<MqttPacket.Other>(other)
        assertEquals(3, other.type)
    }

    @Test
    fun malformedInputIsReported() {
        assertFailsWith<EOFException> { MqttCodec.readPacket(ByteArrayInputStream(ByteArray(0))) }
        assertFailsWith<EOFException> { MqttCodec.readPacket(ByteArrayInputStream(bytesOf("2002"))) }
        assertFailsWith<MqttException> { MqttCodec.readPacket(ByteArrayInputStream(bytesOf("30808080808001"))) }
    }

    @Test
    fun connackReturnCodesHavePahoWording() {
        assertEquals("AMQJS0006E Bad Connack return code:4 Connection Refused: bad user name or password.", MqttCodec.connackError(4).message)
        assertEquals(6, MqttCodec.connackError(5).code)
    }

    @Test
    fun uris() {
        assertEquals(MqttUri.Ws("h", 8883, "/", true, "h:8883"), MqttUri.parse("wss://h:8883/"))
        assertEquals(MqttUri.Ws("192.168.8.248", 9001, "/mqtt", false, "192.168.8.248:9001"), MqttUri.parse("ws://192.168.8.248:9001/mqtt"))
        assertEquals(MqttUri.Ws("h", 443, "/", true, "h"), MqttUri.parse("wss://h"))
        assertEquals(MqttUri.Ws("::1", 9001, "/x", false, "[::1]:9001"), MqttUri.parse("ws://[::1]:9001/x"))
        assertEquals(MqttUri.Tcp("h", 1883, false), MqttUri.parse("tcp://h"))
        assertEquals(MqttUri.Tcp("h", 8883, true), MqttUri.parse("ssl://h"))
        assertEquals(MqttUri.Tcp("broker", 1884, false), MqttUri.parse("tcp://broker:1884"))
        val e = assertFailsWith<MqttException> { MqttUri.parse("http://h") }
        assertEquals("AMQJS0013E Invalid argument http://h for host.", e.message)
        assertFailsWith<MqttException> { MqttUri.parse("wss://h:99999/") }
        assertFailsWith<MqttException> { MqttUri.parse("localhost") }
    }
}
