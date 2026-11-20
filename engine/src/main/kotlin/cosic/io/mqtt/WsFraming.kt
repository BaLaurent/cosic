package cosic.io.mqtt

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** RFC 6455, the part of it a client that carries MQTT needs: the opening handshake and masked binary frames out, any frame in. */
internal object WsFraming {
    const val OP_CONTINUATION = 0x0
    const val OP_TEXT = 0x1
    const val OP_BINARY = 0x2
    const val OP_CLOSE = 0x8
    const val OP_PING = 0x9
    const val OP_PONG = 0xA

    private const val GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
    private const val MAX_FRAME = 1 shl 24
    private const val MAX_HEADER_BYTES = 16 * 1024

    /** `Sec-WebSocket-Accept` for [key] (RFC 6455 §1.3). */
    fun acceptFor(key: String): String =
        Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + GUID).toByteArray(Charsets.US_ASCII)))

    fun newKey(random: SecureRandom): String = Base64.getEncoder().encodeToString(ByteArray(16).also(random::nextBytes))

    /** The `GET` that opens the connection, asking for the `mqtt` subprotocol. */
    fun handshakeRequest(uri: MqttUri.Ws, key: String): ByteArray = (
        "GET ${uri.path} HTTP/1.1\r\n" +
            "Host: ${uri.hostHeader}\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Key: $key\r\n" +
            "Sec-WebSocket-Version: 13\r\n" +
            "Sec-WebSocket-Protocol: mqtt\r\n\r\n"
        ).toByteArray(Charsets.US_ASCII)

    /**
     * Reads the answer to [handshakeRequest] and checks it: status 101, `Upgrade: websocket`, the accept value for [key] and the subprotocol `mqtt`.
     * Reads byte by byte up to the blank line, so what the server sends after it stays in [input].
     */
    fun readHandshake(input: InputStream, key: String) {
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val b = input.read()
            if (b < 0) throw EOFException("connection closed in the WebSocket handshake")
            head.append(b.toChar())
            if (head.length > MAX_HEADER_BYTES) throw MqttException(7, "AMQJS0007E Socket error:handshake answer too long.")
        }
        val lines = head.toString().split("\r\n")
        val status = lines[0].split(' ').getOrNull(1)
        if (status != "101") throw MqttException(7, "AMQJS0007E Socket error:WebSocket handshake refused (${lines[0]}).")
        val headers = HashMap<String, String>()
        for (line in lines.drop(1)) {
            val colon = line.indexOf(':')
            if (colon > 0) headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }
        if (headers["upgrade"]?.lowercase() != "websocket") throw MqttException(7, "AMQJS0007E Socket error:no WebSocket upgrade.")
        if (headers["sec-websocket-accept"] != acceptFor(key)) throw MqttException(7, "AMQJS0007E Socket error:wrong Sec-WebSocket-Accept.")
        val protocols = headers["sec-websocket-protocol"]?.split(',')?.map { it.trim() }.orEmpty()
        if ("mqtt" !in protocols) throw MqttException(7, "AMQJS0007E Socket error:the server does not speak the mqtt subprotocol.")
    }

    /** One frame a client sends: FIN set, masked with [mask] (four bytes). */
    fun encodeClientFrame(opcode: Int, payload: ByteArray, mask: ByteArray): ByteArray {
        require(mask.size == 4)
        val out = ByteArrayOutputStream(payload.size + 14)
        out.write(0x80 or opcode)
        when {
            payload.size < 126 -> out.write(0x80 or payload.size)
            payload.size <= 0xFFFF -> {
                out.write(0x80 or 126)
                out.write(payload.size ushr 8)
                out.write(payload.size)
            }
            else -> {
                out.write(0x80 or 127)
                val n = payload.size.toLong()
                for (shift in 56 downTo 0 step 8) out.write((n ushr shift).toInt())
            }
        }
        out.write(mask)
        val masked = ByteArray(payload.size) { (payload[it].toInt() xor mask[it and 3].toInt()).toByte() }
        out.write(masked)
        return out.toByteArray()
    }

    class Frame(val fin: Boolean, val opcode: Int, val payload: ByteArray)

    /** Reads one frame; a masked one (a server must not send them) is unmasked all the same. Frames above 16 MiB are a [MqttException]. */
    fun readFrame(input: InputStream): Frame {
        val b0 = input.read()
        if (b0 < 0) throw EOFException("connection closed")
        val b1 = input.read()
        if (b1 < 0) throw EOFException("connection closed in a frame")
        var length = (b1 and 0x7F).toLong()
        if (length == 126L) length = (readByte(input).toLong() shl 8) or readByte(input).toLong()
        else if (length == 127L) {
            length = 0
            repeat(8) { length = (length shl 8) or readByte(input).toLong() }
        }
        if (length < 0 || length > MAX_FRAME) throw MqttException(7, "AMQJS0007E Socket error:frame of $length bytes.")
        val mask = if (b1 and 0x80 != 0) ByteArray(4) { readByte(input).toByte() } else null
        val payload = ByteArray(length.toInt())
        var read = 0
        while (read < payload.size) {
            val n = input.read(payload, read, payload.size - read)
            if (n < 0) throw EOFException("connection closed in a frame")
            read += n
        }
        if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i and 3].toInt()).toByte()
        return Frame(b0 and 0x80 != 0, b0 and 0x0F, payload)
    }

    private fun readByte(input: InputStream): Int = input.read().also { if (it < 0) throw EOFException("connection closed in a frame") }

    /**
     * The bytes of the data frames of a WebSocket as a stream (continuations joined; each frame is one chunk of the MQTT byte stream). Pings are
     * answered with a pong through [replies] and a close with a close, after which the stream is at its end.
     */
    class MessageInput(private val input: InputStream, private val replies: (opcode: Int, payload: ByteArray) -> Unit) : InputStream() {
        private var current = ByteArray(0)
        private var at = 0
        private var ended = false

        override fun read(): Int {
            if (!fill()) return -1
            return current[at++].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (!fill()) return -1
            val n = minOf(len, current.size - at)
            current.copyInto(b, off, at, at + n)
            at += n
            return n
        }

        private fun fill(): Boolean {
            while (at >= current.size) {
                if (ended) return false
                val frame = try {
                    readFrame(input)
                } catch (e: EOFException) {
                    ended = true
                    return false
                }
                when (frame.opcode) {
                    OP_BINARY, OP_TEXT, OP_CONTINUATION -> {
                        current = frame.payload
                        at = 0
                    }
                    OP_PING -> replies(OP_PONG, frame.payload)
                    OP_CLOSE -> {
                        replies(OP_CLOSE, frame.payload.copyOf(minOf(2, frame.payload.size)))
                        ended = true
                        return false
                    }
                }
            }
            return true
        }
    }

    /** Writes [frame]s to [out] masked with fresh random masks. */
    class FrameWriter(private val out: OutputStream, private val random: SecureRandom) {
        @Synchronized
        fun write(opcode: Int, payload: ByteArray) {
            val mask = ByteArray(4).also(random::nextBytes)
            out.write(encodeClientFrame(opcode, payload, mask))
            out.flush()
        }
    }
}
