package cosic.io.mqtt

import java.io.Closeable
import java.io.IOException
import java.security.SecureRandom

/** How to reach a broker and who to be there. A null [username] means no authentication; [password] only counts next to a username. */
internal class MqttClientConfig(
    val uri: MqttUri,
    val clientId: String,
    val username: String?,
    val password: String?,
    val keepAliveSec: Int = 60,
    val connectTimeoutSec: Int = 30,
    val random: SecureRandom = SecureRandom(),
    val opener: MqttSocketOpener = DefaultSocketOpener,
)

/**
 * A client that publishes at QoS 0: CONNECT and wait for CONNACK, [publish]es, PINGREQ when nothing was sent for the keep-alive time
 * ([tickKeepAlive], which the owner calls now and then), DISCONNECT on [close]. A thread of its own reads what the broker sends (PINGRESP, a
 * WebSocket ping) and notices the broker hanging up. No reconnecting: a publisher whose connection is gone stays [isConnected] false.
 * Calls are made from one thread at a time except [close] and [isConnected].
 */
internal class MqttPublisher(private val config: MqttClientConfig, private val log: (String) -> Unit = {}) : Closeable {
    private val lock = Any()
    private var transport: MqttTransport? = null

    @Volatile
    private var connected = false

    @Volatile
    private var lastSentMillis = 0L

    val isConnected: Boolean get() = connected

    /** The packets written so far. */
    @Volatile
    var packetsSent = 0L
        private set

    /** Opens the connection and waits for the broker to accept it, at most [MqttClientConfig.connectTimeoutSec] seconds. Throws [MqttException] or [IOException]. */
    fun connect() {
        val timeout = config.connectTimeoutSec * 1000
        val t = openTransport(config.uri, timeout, config.random, config.opener)
        try {
            t.output.write(MqttCodec.connect(config.clientId, config.username, config.password, config.keepAliveSec))
            t.output.flush()
            val answer = MqttCodec.readPacket(t.input)
            if (answer !is MqttPacket.ConnAck) throw MqttException(5, "AMQJS0005E Internal error. Error Message: expected CONNACK, got ${answer.javaClass.simpleName}")
            if (answer.returnCode != 0) throw MqttCodec.connackError(answer.returnCode)
            t.setReadTimeoutMillis(0)
        } catch (e: Throwable) {
            t.close()
            throw e
        }
        synchronized(lock) { transport = t }
        lastSentMillis = System.currentTimeMillis()
        connected = true
        Thread({ readLoop(t) }, "cosic-mqtt-reader").apply { isDaemon = true }.start()
    }

    private fun readLoop(t: MqttTransport) {
        try {
            while (true) MqttCodec.readPacket(t.input)
        } catch (e: IOException) {
            if (!t.closed) log("mqtt connection to ${config.uri.host} lost: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            connected = false
            t.close()
        }
    }

    /** Publishes one message, not retained. Throws [IOException] when the connection is gone. */
    fun publish(topic: String, payload: ByteArray) = write(MqttCodec.publish(topic, payload))

    /** Sends a PINGREQ when nothing was sent for the keep-alive time as of [nowMs] (wall-clock milliseconds). */
    fun tickKeepAlive(nowMs: Long) {
        if (!connected || config.keepAliveSec <= 0) return
        if (nowMs - lastSentMillis >= config.keepAliveSec * 1000L) {
            runCatching { write(MqttCodec.pingReq(), nowMs) }
        }
    }

    private fun write(packet: ByteArray, nowMs: Long = System.currentTimeMillis()) {
        synchronized(lock) {
            val t = transport
            if (!connected || t == null) throw IOException("not connected")
            try {
                t.output.write(packet)
                t.output.flush()
            } catch (e: IOException) {
                connected = false
                t.close()
                throw e
            }
            lastSentMillis = nowMs
            packetsSent++
        }
    }

    override fun close() {
        val t: MqttTransport?
        synchronized(lock) {
            t = transport
            transport = null
            if (connected && t != null) runCatching { t.output.write(MqttCodec.disconnect()); t.output.flush() }
            connected = false
        }
        t?.close()
    }
}
