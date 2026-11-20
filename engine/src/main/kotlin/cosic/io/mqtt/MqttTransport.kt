package cosic.io.mqtt

import java.io.BufferedInputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import javax.net.SocketFactory
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** A connection to a broker whose [input] and [output] carry the MQTT bytes themselves (a WebSocket's framing is already dealt with). */
internal interface MqttTransport : Closeable {
    val input: InputStream
    val output: OutputStream

    /** Whether the connection has been [close]d from this side. */
    val closed: Boolean

    /** How long a read waits for data before it fails; 0 is for ever. */
    fun setReadTimeoutMillis(millis: Int)
}

/** MQTT straight over a socket. */
internal class TcpTransport(private val socket: Socket) : MqttTransport {
    override val input: InputStream = BufferedInputStream(socket.getInputStream())
    override val output: OutputStream = socket.getOutputStream()

    @Volatile
    override var closed = false
        private set

    override fun setReadTimeoutMillis(millis: Int) {
        socket.soTimeout = millis
    }

    override fun close() {
        closed = true
        runCatching { socket.close() }
    }
}

/** MQTT in binary WebSocket frames over [raw], opened with the handshake of RFC 6455 (which this constructor performs, blocking). */
internal class WsTransport(private val raw: Socket, uri: MqttUri.Ws, private val random: SecureRandom) : MqttTransport {
    private val writer: WsFraming.FrameWriter
    override val input: InputStream
    override val output: OutputStream

    @Volatile
    override var closed = false
        private set

    init {
        val rawOut = raw.getOutputStream()
        val rawIn = BufferedInputStream(raw.getInputStream())
        val key = WsFraming.newKey(random)
        rawOut.write(WsFraming.handshakeRequest(uri, key))
        rawOut.flush()
        WsFraming.readHandshake(rawIn, key)
        writer = WsFraming.FrameWriter(rawOut, random)
        input = WsFraming.MessageInput(rawIn) { opcode, payload -> runCatching { writer.write(opcode, payload) } }
        output = object : OutputStream() {
            override fun write(b: Int) = write(byteArrayOf(b.toByte()))

            override fun write(b: ByteArray, off: Int, len: Int) = writer.write(WsFraming.OP_BINARY, b.copyOfRange(off, off + len))
        }
    }

    override fun setReadTimeoutMillis(millis: Int) {
        raw.soTimeout = millis
    }

    override fun close() {
        closed = true
        runCatching { writer.write(WsFraming.OP_CLOSE, ByteArray(0)) }
        runCatching { raw.close() }
    }
}

/** Opens sockets to brokers; replaceable in tests. */
internal fun interface MqttSocketOpener {
    fun open(host: String, port: Int, tls: Boolean, timeoutMillis: Int): Socket
}

/**
 * Plain sockets, and for TLS the default `SSLSocketFactory` with the host name verified (`endpointIdentificationAlgorithm = "HTTPS"`) and the
 * host given for SNI.
 */
internal object DefaultSocketOpener : MqttSocketOpener {
    override fun open(host: String, port: Int, tls: Boolean, timeoutMillis: Int): Socket {
        val plain = SocketFactory.getDefault().createSocket()
        plain.tcpNoDelay = true
        plain.connect(InetSocketAddress(host, port), timeoutMillis)
        plain.soTimeout = timeoutMillis
        if (!tls) return plain
        return try {
            val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(plain, host, port, true) as SSLSocket
            ssl.sslParameters = ssl.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
            ssl.startHandshake()
            ssl
        } catch (e: Exception) {
            runCatching { plain.close() }
            throw e
        }
    }
}

/** Opens the transport [uri] names: a socket (TLS where the scheme says so) and, for a WebSocket, its handshake. */
internal fun openTransport(uri: MqttUri, timeoutMillis: Int, random: SecureRandom, opener: MqttSocketOpener = DefaultSocketOpener): MqttTransport {
    val socket = opener.open(uri.host, uri.port, uri.tls, timeoutMillis)
    return try {
        socket.soTimeout = timeoutMillis
        when (uri) {
            is MqttUri.Tcp -> TcpTransport(socket)
            is MqttUri.Ws -> WsTransport(socket, uri, random)
        }
    } catch (e: Exception) {
        runCatching { socket.close() }
        throw e
    }
}
