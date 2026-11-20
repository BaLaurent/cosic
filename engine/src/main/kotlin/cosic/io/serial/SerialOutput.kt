package cosic.io.serial

import java.util.PriorityQueue
import java.util.concurrent.ConcurrentHashMap

/**
 * Where `.serial()` messages go (`outputs.serial`). Implemented by [SerialOutput]; the app gives that one a [SerialPortOpener] for the
 * USB devices it can reach. Called on the output dispatcher, never on the audio thread.
 */
interface SerialSink {
    /**
     * A program that evaluated `.serial(...)` needs the port [SerialRoute.name]: opens it when it is not open yet. Does not wait for the
     * user (the permission dialog of the system, for one): [send] says whether the port is there by then. Called on every evaluation.
     */
    fun prepare(route: SerialRoute)

    /**
     * Writes [bytes] to the port [name] when `System.nanoTime()` reaches [dueNano] (at once when it has passed). Returns false when
     * the port is not open and the message is dropped (Strudel throws a `TypeError` there).
     */
    fun send(name: String, bytes: ByteArray, dueNano: Long): Boolean
}

/** An open serial port. [write] blocks until the bytes are written and throws when the port is gone. */
interface SerialPort {
    fun write(bytes: ByteArray)

    fun close()
}

/**
 * Opens the port for [route] on a device (the app: a USB serial adapter or board, after the user granted access). [result] is called once,
 * on any thread, with the open port or null when none could be opened; the opener says why on its own log.
 */
fun interface SerialPortOpener {
    fun open(route: SerialRoute, result: (SerialPort?) -> Unit)
}

/**
 * The [SerialSink] over a [SerialPortOpener]: keeps the open port of every name, and writes the messages it is given at their time on a
 * thread of its own (which exists while there is something to write, and for three seconds after). A port whose write fails is closed
 * and forgotten; the next [prepare], which every evaluation makes, opens it again.
 */
class SerialOutput(
    private val opener: SerialPortOpener,
    private val log: (String) -> Unit = {},
    private val nanoTime: () -> Long = System::nanoTime,
) : SerialSink {
    private class Slot {
        var port: SerialPort? = null
        var opening = false
    }

    private class Item(val slot: Slot, val port: SerialPort, val bytes: ByteArray, val dueNano: Long, val seq: Long)

    private val slots = ConcurrentHashMap<String, Slot>()
    private val lock = Object()
    private val queue = PriorityQueue<Item>(compareBy<Item> { it.dueNano }.thenBy { it.seq })
    private var seq = 0L
    private var thread: Thread? = null
    private var closed = false

    override fun prepare(route: SerialRoute) {
        val slot = slots.computeIfAbsent(route.name) { Slot() }
        synchronized(slot) {
            if (slot.port != null || slot.opening) return
            slot.opening = true
        }
        opener.open(route) { port ->
            var dropPort: SerialPort? = null
            synchronized(slot) {
                slot.opening = false
                if (port != null && !closed) slot.port = port else dropPort = port
            }
            if (dropPort != null) runCatching { dropPort!!.close() }
            if (port == null) log("serial port '${route.name}' could not be opened")
        }
    }

    override fun send(name: String, bytes: ByteArray, dueNano: Long): Boolean {
        val slot = slots[name] ?: return false
        val port = synchronized(slot) { slot.port } ?: return false
        if (bytes.isEmpty()) return true
        synchronized(lock) {
            if (closed) return false
            queue.add(Item(slot, port, bytes, dueNano, seq++))
            if (thread == null) {
                thread = Thread(::writeLoop, "cosic-serial").apply { isDaemon = true; start() }
            } else {
                lock.notifyAll()
            }
        }
        return true
    }

    /** Whether the port [name] is open. */
    fun isOpen(name: String): Boolean = slots[name]?.let { synchronized(it) { it.port != null } } == true

    /** Closes every port and stops writing; what is queued is dropped. */
    fun close() {
        synchronized(lock) {
            closed = true
            queue.clear()
            lock.notifyAll()
        }
        for (slot in slots.values) {
            val port = synchronized(slot) { slot.port.also { slot.port = null } }
            if (port != null) runCatching { port.close() }
        }
    }

    private fun writeLoop() {
        while (true) {
            val item = nextDue() ?: return
            write(item)
        }
    }

    /** Waits for the next message that is due and takes it; null when the output is closed or has been idle for a while (the thread then ends). */
    private fun nextDue(): Item? = synchronized(lock) {
        var next: Item? = null
        while (next == null) {
            if (closed) {
                thread = null
                break
            }
            val head = queue.peek()
            if (head == null) {
                lock.wait(IDLE_EXIT_MS)
                if (queue.isEmpty()) {
                    thread = null
                    break
                }
                continue
            }
            val wait = head.dueNano - nanoTime()
            if (wait <= 0) next = queue.poll() else lock.wait(wait / 1_000_000, (wait % 1_000_000).toInt())
        }
        next
    }

    private fun write(item: Item) {
        // the port was replaced or closed since the message was queued: it belongs to a device that is gone
        if (synchronized(item.slot) { item.slot.port } !== item.port) return
        try {
            item.port.write(item.bytes)
        } catch (e: Exception) {
            synchronized(item.slot) { if (item.slot.port === item.port) item.slot.port = null }
            log("serial write failed: ${e.message ?: e.javaClass.simpleName}")
            runCatching { item.port.close() }
        }
    }

    private companion object {
        const val IDLE_EXIT_MS = 3000L
    }
}
