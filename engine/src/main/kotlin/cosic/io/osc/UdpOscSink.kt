package cosic.io.osc

import cosic.FrameClock
import cosic.OscSink
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Sends OSC bundles over UDP to a SuperDirt (the part of `packages/osc/server.js` that talks UDP, with the time stamp of `oscOutput`).
 * Every message goes out in a bundle whose time tag is the system time at which its [frame] is heard, which SuperDirt waits for, so it is sent as
 * soon as it is made, not at the time. The time comes from [clock]; while that does not know it (the audio device has not reported) the message is
 * dropped with `[osc] clockbridge not ready`, as Strudel does.
 *
 * [send] runs on the dispatcher of the output bus and only encodes and queues; a thread of the sink resolves host names (once per name), owns the one
 * socket (an ephemeral local port, like the bridge's) and writes the packets. It ends after a few idle seconds and starts again with the next message.
 * [defaultHost] and [defaultPort] are read for every message that has no `oschost` or `oscport` of its own; [log] gets what went wrong
 * (`[osc] ...`), which the console of the app shows. [close] stops the thread and closes the socket; a message sent afterwards starts them again.
 */
class UdpOscSink(
    private val clock: () -> FrameClock?,
    private val defaultHost: () -> String = { "127.0.0.1" },
    private val defaultPort: () -> Int = { 57120 },
    private val log: (String) -> Unit = {},
    queueCapacity: Int = 512,
) : OscSink, AutoCloseable {
    private class Packet(val host: String, val port: Int, val bytes: ByteArray)

    private val queue = LinkedBlockingQueue<Packet>(queueCapacity)
    private val addresses = ConcurrentHashMap<String, InetAddress>()
    private val lock = Any()
    private var thread: Thread? = null
    private var socket: DatagramSocket? = null

    @Volatile
    private var generation = 0

    /** How many messages were dropped because the sender could not keep up. */
    @Volatile
    var dropped: Long = 0
        private set

    override fun send(host: String?, port: Int?, address: String, args: List<Any?>, frame: Long) {
        val nano = clock()?.nanoTimeAtFrame(frame)
        if (nano == null) {
            log("[osc] clockbridge not ready")
            return
        }
        // the wall clock shifts against the monotonic one when the device sleeps, so the offset is taken again for every message
        val unixMs = nano / 1e6 + (System.currentTimeMillis() - System.nanoTime() / 1e6)
        val bytes = try {
            OscCodec.encodeBundle(unixMs, address, args)
        } catch (e: Exception) {
            log("[osc] ${e.message}")
            return
        }
        val targetPort = port ?: defaultPort()
        if (targetPort !in 1..65535) {
            log("[osc] port out of range: $targetPort")
            return
        }
        if (!queue.offer(Packet((host ?: defaultHost()).trim(), targetPort, bytes))) {
            dropped++
            return
        }
        ensureThread()
    }

    private fun ensureThread() {
        synchronized(lock) {
            if (thread?.isAlive == true) return
            val mine = generation
            val t = Thread({ run(mine) }, "cosic-osc")
            t.isDaemon = true
            thread = t
            t.start()
        }
    }

    private fun run(mine: Int) {
        try {
            while (mine == generation) {
                val packet = queue.poll(IDLE_SECONDS, TimeUnit.SECONDS) ?: break
                try {
                    val target = addresses[packet.host] ?: InetAddress.getByName(packet.host).also { addresses[packet.host] = it }
                    val s = socket ?: DatagramSocket().also { socket = it }
                    s.send(DatagramPacket(packet.bytes, packet.bytes.size, target, packet.port))
                } catch (e: Exception) {
                    log("[osc] ${packet.host}:${packet.port}: ${e.message ?: e.javaClass.simpleName}")
                }
            }
        } catch (_: InterruptedException) {
            // close() stops the thread
        } finally {
            synchronized(lock) {
                if (mine == generation) {
                    socket?.close()
                    socket = null
                    thread = null
                    // a message queued while the thread was leaving needs a thread
                    if (queue.isNotEmpty()) ensureThread()
                }
            }
        }
    }

    override fun close() {
        val t: Thread?
        synchronized(lock) {
            generation++
            t = thread
            thread = null
            socket?.close()
            socket = null
        }
        queue.clear()
        t?.interrupt()
    }

    private companion object {
        const val IDLE_SECONDS = 5L
    }
}
