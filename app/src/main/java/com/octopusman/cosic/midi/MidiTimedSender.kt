package com.octopusman.cosic.midi

import java.util.PriorityQueue
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** The sender wakes up this long before a message is due, for the time it takes to hand it over. */
private const val EARLY_NANOS = 300_000L

/** The thread ends when it has had nothing to send for this long; the next message starts it again. */
private const val IDLE_EXIT_NANOS = 3_000_000_000L

/** Never sleep longer than this without looking at the queue again. */
private const val MAX_WAIT_NANOS = 500_000_000L

/** Messages waiting for their time, at most; a device that never takes them must not fill the memory. */
private const val MAX_QUEUED = 8192

/**
 * Sends MIDI messages at the time they are due. A message is queued with the system time (`System.nanoTime()`) it belongs to; a thread of its
 * own waits for the earliest, gives it to [deliver] when its time has come (a little earlier: [EARLY_NANOS]), and sends one that is already late
 * at once, in order. The framework's own time stamps are advisory (the receiver is not told to wait), so the waiting is done here.
 *
 * Messages with the same time keep the order they were queued in. Nothing is dropped when the program stops: the messages that are queued
 * go out when they are due, which is what a browser does with the messages it has handed to its MIDI port (a note off is not lost).
 *
 * [deliver] runs on the sender's thread; one that throws loses only its message, the sender goes on (a failing port is the service's business).
 * [onThreadStart] runs on that thread when it starts, for its priority.
 */
internal class MidiTimedSender<P>(
    private val deliver: (port: P, bytes: ByteArray) -> Unit,
    private val nanoTime: () -> Long = System::nanoTime,
    private val onThreadStart: () -> Unit = {},
) {
    private class Item<P>(val port: P, val bytes: ByteArray, val due: Long, val sequence: Long)

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val queue = PriorityQueue<Item<P>>(compareBy<Item<P>> { it.due }.thenBy { it.sequence })
    private var sequence = 0L
    private var thread: Thread? = null

    /** Number of messages dropped because too many were waiting. */
    @Volatile
    var dropped = 0L
        private set

    /** Queues [bytes] for [port], due at the system time [dueNanos]. A time that has passed means now. */
    fun send(port: P, bytes: ByteArray, dueNanos: Long) {
        lock.withLock {
            if (queue.size >= MAX_QUEUED) {
                dropped++
                return
            }
            queue.add(Item(port, bytes, dueNanos, sequence++))
            if (thread == null) {
                val t = Thread(::run, "cosic-midi-send")
                t.isDaemon = true
                thread = t
                t.start()
            } else {
                changed.signal()
            }
        }
    }

    /** How many messages are waiting. */
    val pending: Int get() = lock.withLock { queue.size }

    /** Forgets the messages that wait (the app is closing its devices). */
    fun clear() {
        lock.withLock { queue.clear() }
    }

    /** Forgets the messages that wait for [port] (it was closed). */
    fun clear(port: P) {
        lock.withLock { queue.removeAll { it.port == port } }
    }

    private fun run() {
        onThreadStart()
        var idleSince = nanoTime()
        while (true) {
            val item: Item<P>? = lock.withLock {
                val head = queue.peek()
                if (head == null) {
                    if (nanoTime() - idleSince >= IDLE_EXIT_NANOS) {
                        thread = null
                        return
                    }
                    changed.awaitNanos(MAX_WAIT_NANOS)
                    null
                } else {
                    val untilDue = head.due - EARLY_NANOS - nanoTime()
                    if (untilDue <= 0) {
                        queue.poll()
                    } else {
                        changed.awaitNanos(minOf(untilDue, MAX_WAIT_NANOS))
                        null
                    }
                }
            }
            if (item != null) {
                idleSince = nanoTime()
                try {
                    deliver(item.port, item.bytes)
                } catch (e: RuntimeException) {
                    // the message is lost, the sender goes on
                }
            }
        }
    }
}
