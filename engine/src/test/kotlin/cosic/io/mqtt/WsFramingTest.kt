package cosic.io.mqtt

import java.io.ByteArrayInputStream
import java.io.EOFException
import java.security.SecureRandom
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** RFC 6455 as a client of a broker needs it, with the vectors of the RFC. */
class WsFramingTest {
    @Test
    fun acceptKeyOfTheRfc() {
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", WsFraming.acceptFor("dGhlIHNhbXBsZSBub25jZQ=="))
    }

    @Test
    fun maskedFrameOfTheRfc() {
        val mask = bytesOf("37fa213d")
        assertEquals("818537fa213d7f9f4d5158", hex(WsFraming.encodeClientFrame(WsFraming.OP_TEXT, "Hello".toByteArray(), mask)))
        assertEquals("828537fa213d7f9f4d5158", hex(WsFraming.encodeClientFrame(WsFraming.OP_BINARY, "Hello".toByteArray(), mask)))
    }

    @Test
    fun longPayloadsUseTheExtendedLengths() {
        val mask = bytesOf("00000000")
        val medium = WsFraming.encodeClientFrame(WsFraming.OP_BINARY, ByteArray(300), mask)
        assertEquals("82fe012c00000000", hex(medium.copyOf(8)))
        assertEquals(8 + 300, medium.size)
        val large = WsFraming.encodeClientFrame(WsFraming.OP_BINARY, ByteArray(70_000), mask)
        assertEquals("82ff000000000001117000000000", hex(large.copyOf(14)))
    }

    @Test
    fun framesWeWriteAreReadBack() {
        val random = SecureRandom()
        for (size in listOf(0, 1, 125, 126, 127, 300, 65_535, 65_536, 100_000)) {
            val payload = ByteArray(size) { (it * 7).toByte() }
            val mask = ByteArray(4).also(random::nextBytes)
            val frame = WsFraming.readFrame(ByteArrayInputStream(WsFraming.encodeClientFrame(WsFraming.OP_BINARY, payload, mask)))
            assertEquals(WsFraming.OP_BINARY, frame.opcode)
            assertContentEquals(payload, frame.payload, "size $size")
        }
    }

    @Test
    fun serverFramesAreUnmaskedAndJoined() {
        // two data frames (the second a continuation) and a ping in between: the stream is their payloads, the ping is answered
        val bytes = bytesOf("0203616263" + "890170" + "8003646566")
        val replies = ArrayList<Pair<Int, String>>()
        val input = WsFraming.MessageInput(ByteArrayInputStream(bytes)) { op, payload -> replies.add(op to hex(payload)) }
        assertEquals("abcdef", input.readBytes().decodeToString())
        assertEquals(listOf(WsFraming.OP_PONG to "70"), replies)
    }

    @Test
    fun aCloseFrameEndsTheStreamAndIsAnswered() {
        val replies = ArrayList<Int>()
        val input = WsFraming.MessageInput(ByteArrayInputStream(bytesOf("880203e8"))) { op, _ -> replies.add(op) }
        assertEquals(-1, input.read())
        assertEquals(listOf(WsFraming.OP_CLOSE), replies)
    }

    @Test
    fun theHandshakeIsCheckedStrictly() {
        val key = "dGhlIHNhbXBsZSBub25jZQ=="
        fun answer(status: String = "101 Switching Protocols", accept: String = WsFraming.acceptFor(key), protocol: String? = "mqtt") =
            ("HTTP/1.1 $status\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n" +
                (protocol?.let { "Sec-WebSocket-Protocol: $it\r\n" } ?: "") + "\r\n").toByteArray()
        WsFraming.readHandshake(ByteArrayInputStream(answer()), key)
        assertFailsWith<MqttException> { WsFraming.readHandshake(ByteArrayInputStream(answer(status = "403 Forbidden")), key) }
        assertFailsWith<MqttException> { WsFraming.readHandshake(ByteArrayInputStream(answer(accept = "nope")), key) }
        assertFailsWith<MqttException> { WsFraming.readHandshake(ByteArrayInputStream(answer(protocol = null)), key) }
        assertFailsWith<MqttException> { WsFraming.readHandshake(ByteArrayInputStream(answer(protocol = "mqttv3.1")), key) }
        assertFailsWith<EOFException> { WsFraming.readHandshake(ByteArrayInputStream("HTTP/1.1 101".toByteArray()), key) }
    }

    @Test
    fun theHandshakeStopsAtTheBlankLine() {
        val key = "dGhlIHNhbXBsZSBub25jZQ=="
        val head = "HTTP/1.1 101 x\r\nUpgrade: websocket\r\nSec-WebSocket-Accept: ${WsFraming.acceptFor(key)}\r\nSec-WebSocket-Protocol: mqtt\r\n\r\n"
        val input = ByteArrayInputStream(head.toByteArray() + bytesOf("8100"))
        WsFraming.readHandshake(input, key)
        assertEquals(2, input.available())
    }

    @Test
    fun theRequestAsksForTheMqttSubprotocol() {
        val text = WsFraming.handshakeRequest(MqttUri.parse("ws://h:9001/mqtt") as MqttUri.Ws, "KEY").decodeToString()
        assertEquals(
            "GET /mqtt HTTP/1.1\r\nHost: h:9001\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: KEY\r\n" +
                "Sec-WebSocket-Version: 13\r\nSec-WebSocket-Protocol: mqtt\r\n\r\n",
            text,
        )
    }
}
