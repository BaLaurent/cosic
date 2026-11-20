package cosic.io.mqtt

import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/*
 * MQTT 3.1.1, as much of it as an output needs: CONNECT, PUBLISH at QoS 0, PINGREQ and DISCONNECT to write, CONNACK and PINGRESP to read.
 * The bytes are those Paho writes (the client `packages/mqtt/mqtt.mjs` uses; checked against it by `tools/strudel-ref/mqtt-oracle.mjs`).
 * Not built: the fall-back to MQTT 3.1 that Paho makes when a broker refuses 3.1.1 (`mqttVersionExplicit` is unset in mqtt.mjs), QoS 1 and 2,
 * subscriptions and a will message.
 */

/** A failed connection or protocol error. [code] and the text are Paho's (`AMQJS0006E Bad Connack return code:4 ...`) where Paho has one. */
internal class MqttException(val code: Int, text: String, cause: Throwable? = null) : IOException(text, cause)

/** Where a broker is: over a WebSocket (`ws://`, `wss://`, as Paho) or a plain socket (`tcp://`, `ssl://`; Cosic's addition). */
internal sealed interface MqttUri {
    val host: String
    val port: Int
    val tls: Boolean

    data class Tcp(override val host: String, override val port: Int, override val tls: Boolean) : MqttUri

    /** [hostHeader] is the `Host` of the handshake: the host (in brackets for IPv6), and the port unless it is the default of the scheme. */
    data class Ws(
        override val host: String,
        override val port: Int,
        val path: String,
        override val tls: Boolean,
        val hostHeader: String,
    ) : MqttUri

    companion object {
        /**
         * Reads [text]. `ws://` and `wss://` follow Paho's pattern (host or `[ipv6]`, optional port, then a path; a missing path is read as `/`,
         * which Paho refuses); `tcp://` has port 1883 and `ssl://` 8883 by default. Anything else is a [MqttException] the way Paho words it.
         */
        fun parse(text: String): MqttUri {
            val m = PATTERN.matchEntire(text) ?: throw MqttException(13, "AMQJS0013E Invalid argument $text for host.")
            val scheme = m.groupValues[1].lowercase()
            val host = m.groups[4]?.value ?: m.groupValues[2]
            if (host.isEmpty()) throw MqttException(13, "AMQJS0013E Invalid argument $text for host.")
            val portText = m.groupValues[6]
            val path = m.groupValues[7].ifEmpty { "/" }
            fun port(default: Int): Int {
                if (portText.isEmpty()) return default
                val p = portText.toIntOrNull()
                if (p == null || p !in 0..65535) throw MqttException(13, "AMQJS0013E Invalid argument $text for host.")
                return p
            }
            return when (scheme) {
                "ws", "wss" -> {
                    val tls = scheme == "wss"
                    val port = port(if (tls) 443 else 80)
                    val bracketed = if (host.contains(':')) "[$host]" else host
                    val default = if (tls) 443 else 80
                    Ws(host, port, path, tls, if (port == default) bracketed else "$bracketed:$port")
                }
                "tcp", "mqtt" -> Tcp(host, port(1883), false)
                else -> Tcp(host, port(8883), true) // ssl, mqtts
            }
        }

        private val PATTERN = Regex("""^(wss?|tcp|ssl|mqtts?)://((\[(.+)])|([^/:]+?))(?::(\d+))?(/.*)?$""", RegexOption.IGNORE_CASE)
    }
}

internal sealed interface MqttPacket {
    class ConnAck(val sessionPresent: Boolean, val returnCode: Int) : MqttPacket

    object PingResp : MqttPacket

    class Other(val type: Int, val body: ByteArray) : MqttPacket
}

internal object MqttCodec {
    private const val CONNECT = 0x10
    private const val PUBLISH = 0x30
    private const val PINGREQ = 0xC0
    private const val DISCONNECT = 0xE0
    private const val MAX_REMAINING = 268_435_455
    private const val MAX_INCOMING = 1 shl 20

    /** The meaning of a CONNACK return code, in Paho's words. */
    private val CONNACK_TEXT = listOf(
        "Connection Accepted",
        "Connection Refused: unacceptable protocol version",
        "Connection Refused: identifier rejected",
        "Connection Refused: server unavailable",
        "Connection Refused: bad user name or password",
        "Connection Refused: not authorized",
    )

    /** `AMQJS0006E Bad Connack return code:N text.` */
    fun connackError(returnCode: Int): MqttException =
        MqttException(6, "AMQJS0006E Bad Connack return code:$returnCode ${CONNACK_TEXT.getOrNull(returnCode)}.")

    /**
     * CONNECT with a clean session. A password is only written next to a username, and `username != null` gives the flag even for an empty
     * name, as Paho does for any defined one.
     */
    fun connect(clientId: String, username: String?, password: String?, keepAliveSec: Int): ByteArray {
        val id = utf8(clientId)
        val user = username?.let(::utf8)
        val pass = if (username != null) password?.let(::utf8) else null
        var flags = 0x02
        if (user != null) flags = flags or 0x80
        if (pass != null) flags = flags or 0x40
        val body = ByteArray(10 + 2 + id.size + (user?.let { 2 + it.size } ?: 0) + (pass?.let { 2 + it.size } ?: 0))
        var p = 0
        for (b in intArrayOf(0x00, 0x04, 'M'.code, 'Q'.code, 'T'.code, 'T'.code, 0x04, flags)) body[p++] = b.toByte()
        body[p++] = (keepAliveSec ushr 8).toByte()
        body[p++] = keepAliveSec.toByte()
        p = putString(body, p, id)
        if (user != null) p = putString(body, p, user)
        if (pass != null) putString(body, p, pass)
        return packet(CONNECT, body)
    }

    /** PUBLISH at QoS 0, not retained, not a duplicate. */
    fun publish(topic: String, payload: ByteArray): ByteArray {
        val t = utf8(topic)
        val body = ByteArray(2 + t.size + payload.size)
        putString(body, 0, t)
        payload.copyInto(body, 2 + t.size)
        return packet(PUBLISH, body)
    }

    fun pingReq(): ByteArray = byteArrayOf(PINGREQ.toByte(), 0)

    fun disconnect(): ByteArray = byteArrayOf(DISCONNECT.toByte(), 0)

    /** The remaining-length field: seven bits per byte, low group first, the high bit set when another byte follows. */
    fun encodeRemainingLength(n: Int): ByteArray {
        require(n in 0..MAX_REMAINING) { "remaining length $n out of range" }
        val out = ByteArray(4)
        var x = n
        var i = 0
        do {
            var digit = x and 0x7F
            x = x ushr 7
            if (x > 0) digit = digit or 0x80
            out[i++] = digit.toByte()
        } while (x > 0)
        return out.copyOf(i)
    }

    /** Reads one packet. EOF at its start is an [EOFException]; a remaining length of more than four bytes or more than a MiB is a [MqttException]. */
    fun readPacket(input: InputStream): MqttPacket {
        val first = input.read()
        if (first < 0) throw EOFException("connection closed")
        var length = 0
        var shift = 0
        var digits = 0
        while (true) {
            val b = input.read()
            if (b < 0) throw EOFException("connection closed in a packet")
            length += (b and 0x7F) shl shift
            digits++
            if (b and 0x80 == 0) break
            shift += 7
            if (digits == 4) throw MqttException(5, "AMQJS0005E Internal error. Error Message: malformed remaining length")
        }
        if (length > MAX_INCOMING) throw MqttException(5, "AMQJS0005E Internal error. Error Message: packet of $length bytes")
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) throw EOFException("connection closed in a packet")
            read += n
        }
        val type = first ushr 4
        return when {
            type == 2 && length == 2 -> MqttPacket.ConnAck(body[0].toInt() and 1 != 0, body[1].toInt() and 0xFF)
            type == 13 -> MqttPacket.PingResp
            else -> MqttPacket.Other(type, body)
        }
    }

    private fun packet(first: Int, body: ByteArray): ByteArray {
        val len = encodeRemainingLength(body.size)
        val out = ByteArray(1 + len.size + body.size)
        out[0] = first.toByte()
        len.copyInto(out, 1)
        body.copyInto(out, 1 + len.size)
        return out
    }

    private fun utf8(s: String): ByteArray {
        val bytes = s.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 65535) { "string of ${bytes.size} bytes does not fit an MQTT string" }
        return bytes
    }

    /** A length-prefixed string at [at]; the position after it. */
    private fun putString(into: ByteArray, at: Int, bytes: ByteArray): Int {
        into[at] = (bytes.size ushr 8).toByte()
        into[at + 1] = bytes.size.toByte()
        bytes.copyInto(into, at + 2)
        return at + 2 + bytes.size
    }
}
