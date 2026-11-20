package cosic.io.mqtt

import java.security.SecureRandom
import java.util.PriorityQueue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport

/**
 * Where `.mqtt()` sends to and as whom: the arguments of the method (`mqtt.mjs`). A route is plain data; the [MqttOutput] keeps one connection per
 * [key]. [client] is the `client` a program gave (null: the device makes `cosic-<n>` once for the connection); [topic] null takes the topic of
 * each hap (`topic` of its value). [latencySec] is added to the time a hap sounds at, [addMeta] adds `duration` and `cps` to a map value.
 */
class MqttRoute(
    val host: String,
    val client: String? = null,
    val username: String? = null,
    val password: String? = null,
    val topic: String? = null,
    val latencySec: Double = 0.0,
    val addMeta: Boolean = true,
) {
    /** `host-client`, which two routes that share a connection have in common (`connections[key]` of `mqtt.mjs`, with the same `undefined`). */
    val key: String get() = "$host-${client ?: "undefined"}"
}

/** A message ready to go out: the topic and the bytes (UTF-8 text, as Paho sends a string). */
class MqttMessage(val topic: String, val payload: ByteArray)

/**
 * The broker connections of the app, as the engine's hooks see them. Both calls only enqueue: they come from the output dispatcher, which must not
 * wait for a socket. [send] is told when the message is due, in `System.nanoTime()` units (the time its hap sounds at plus the latency of its
 * route); the message waits for that time and is dropped if its connection is not up then.
 */
interface MqttOutput {
    /** Connects the route (in the background) if its connection is not there yet, or is no longer up: `.mqtt(...)` at evaluation. */
    fun prepare(route: MqttRoute)

    fun send(route: MqttRoute, message: MqttMessage, dueNanos: Long)

    /** Closes every connection and forgets what was waiting to be sent. */
    fun stopAll()
}

/** Remembers the password of a broker account. */
interface MqttCredentials {
    fun load(host: String, user: String): String?

    fun save(host: String, user: String, password: String)

    fun clear(host: String, user: String)
}

/** Asks the user for the password of [user] at [host]; [onResult] gets it, or null when none was given. May answer from any thread, once. */
interface PasswordPrompt {
    fun request(host: String, user: String, onResult: (String?) -> Unit)
}

/**
 * The implementation of [MqttOutput] over [MqttPublisher]s: a thread of its own (`cosic-mqtt`, started by the first call and gone after a few
 * idle seconds) keeps the connections and sends each message when it is due; connecting takes a thread of its own per attempt, so a slow broker
 * stalls nothing else. The password of an account that has a username but no password comes from [credentials], else from [prompt]; it is saved
 * when the broker accepts it and forgotten when the broker refuses it.
 *
 * What differs from the browser: a connection that has died is made again on the next evaluation (Paho keeps the dead one, and so does Strudel's
 * table of connections, until the page is reloaded), and a message for a connection that is not up when it is due is dropped.
 */
class MqttDevice internal constructor(
    private val credentials: MqttCredentials?,
    private val prompt: PasswordPrompt?,
    private val log: (String) -> Unit,
    private val nanoTime: () -> Long,
    private val connectTimeoutSec: Int,
    private val opener: MqttSocketOpener,
    private val random: SecureRandom,
    private val clientNumber: () -> Int,
) : MqttOutput {
    constructor(credentials: MqttCredentials?, prompt: PasswordPrompt?, log: (String) -> Unit) : this(
        credentials, prompt, log, System::nanoTime, 30, DefaultSocketOpener, SecureRandom(), { (Math.random() * 1_000_000).toInt() },
    )

    private sealed interface Command {
        class Prepare(val route: MqttRoute) : Command

        class Send(val route: MqttRoute, val message: MqttMessage, val due: Long, val seq: Long) : Command

        class Connected(val key: String, val attempt: Long, val publisher: MqttPublisher, val fromStore: Boolean) : Command

        class Failed(val key: String, val attempt: Long, val reason: String) : Command

        class Password(val key: String, val attempt: Long, val password: String?) : Command

        object StopAll : Command
    }

    /** What the I/O thread knows of one connection. */
    private class Connection(var route: MqttRoute, val clientId: String) {
        var attempt = 0L
        var publisher: MqttPublisher? = null
        var connecting = false
        var username: String? = null
        var password: String? = null
    }

    private val queue = ConcurrentLinkedQueue<Command>()
    private val running = AtomicBoolean(false)
    private val seq = AtomicLong(0)
    private val sent = AtomicLong(0)
    private val droppedCount = AtomicLong(0)

    @Volatile
    private var thread: Thread? = null

    /** Messages written to a broker so far. */
    val sentCount: Long get() = sent.get()

    /** Messages that were due when their connection was not up, whose write failed, or that [stopAll] cleared. */
    val droppedCountValue: Long get() = droppedCount.get()

    override fun prepare(route: MqttRoute) = offer(Command.Prepare(route))

    override fun send(route: MqttRoute, message: MqttMessage, dueNanos: Long) =
        offer(Command.Send(route, message, dueNanos, seq.getAndIncrement()))

    override fun stopAll() = offer(Command.StopAll)

    private fun offer(c: Command) {
        queue.add(c)
        if (running.compareAndSet(false, true)) {
            val t = Thread(::loop, "cosic-mqtt")
            t.isDaemon = true
            thread = t
            t.start()
        } else {
            thread?.let(LockSupport::unpark)
        }
    }

    // ---- the I/O thread: everything below runs there, except the connect threads, which only post commands back ------------------------

    private val connections = HashMap<String, Connection>()
    private val pending = PriorityQueue(compareBy<Command.Send> { it.due }.thenBy { it.seq })

    private fun loop() {
        var idleSince = nanoTime()
        while (true) {
            var worked = false
            while (true) {
                val c = queue.poll() ?: break
                worked = true
                guarded { handle(c) }
            }
            val now = nanoTime()
            while (pending.isNotEmpty() && pending.peek().due <= now) {
                worked = true
                val s = pending.poll()
                guarded { deliver(s) }
            }
            val wallNow = System.currentTimeMillis()
            var alive = false
            for (c in connections.values) {
                val p = c.publisher
                if (p != null && p.isConnected) {
                    alive = true
                    p.tickKeepAlive(wallNow)
                } else if (c.connecting) {
                    alive = true
                }
            }
            if (worked || alive || pending.isNotEmpty()) {
                idleSince = now
            } else if (now - idleSince > IDLE_EXIT_NANOS) {
                // Nothing to do for a while: leave, unless a call came in while deciding to (it then started a thread, or is still ours).
                connections.clear()
                running.set(false)
                if (queue.isEmpty() || !running.compareAndSet(false, true)) return
                continue
            }
            val wait = pending.peek()?.let { minOf(it.due - now, TICK_NANOS) } ?: TICK_NANOS
            if (wait > 0 && queue.isEmpty()) LockSupport.parkNanos(this, wait)
        }
    }

    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            log("mqtt: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private fun handle(c: Command) {
        when (c) {
            is Command.Prepare -> prepareOnThread(c.route)
            is Command.Send -> if (pending.size >= MAX_PENDING) droppedCount.incrementAndGet() else pending.add(c)
            is Command.Connected -> {
                val conn = connections[c.key]
                if (conn == null || conn.attempt != c.attempt) {
                    c.publisher.close()
                    return
                }
                conn.connecting = false
                conn.publisher = c.publisher
                val user = conn.username
                val password = conn.password
                if (user != null && password != null && conn.route.password == null && !c.fromStore) credentials?.save(conn.route.host, user, password)
                log("Connected to mqtt broker")
            }
            is Command.Failed -> {
                val conn = connections[c.key]
                if (conn == null || conn.attempt != c.attempt) return
                conn.connecting = false
                val user = conn.username
                if (user != null && conn.password != null && conn.route.password == null) credentials?.clear(conn.route.host, user)
                log("Connection failed: ${c.reason}")
            }
            is Command.Password -> {
                val conn = connections[c.key]
                if (conn == null || conn.attempt != c.attempt) return
                if (c.password == null) {
                    conn.connecting = false
                    log("Connection failed: no password given for ${conn.username} at ${conn.route.host}")
                } else {
                    conn.password = c.password
                    startConnect(conn, fromStore = false)
                }
            }
            Command.StopAll -> {
                for (conn in connections.values) {
                    conn.attempt++
                    conn.publisher?.close()
                }
                connections.clear()
                droppedCount.addAndGet(pending.size.toLong())
                pending.clear()
            }
        }
    }

    private fun prepareOnThread(route: MqttRoute) {
        val existing = connections[route.key]
        if (existing != null && (existing.connecting || existing.publisher?.isConnected == true)) return
        try {
            MqttUri.parse(route.host)
        } catch (e: MqttException) {
            log("Connection failed: ${e.message}")
            return
        }
        val conn = existing ?: Connection(route, route.client ?: ("cosic-" + clientNumber())).also { connections[route.key] = it }
        conn.route = route
        conn.publisher?.close()
        conn.publisher = null
        conn.attempt++
        conn.connecting = true
        conn.username = route.username?.takeIf { it.isNotEmpty() }
        conn.password = if (conn.username == null) null else route.password
        var fromStore = false
        val user = conn.username
        if (user != null && conn.password == null) {
            val stored = credentials?.load(route.host, user)
            if (stored != null) {
                conn.password = stored
                fromStore = true
            } else if (prompt != null) {
                val key = route.key
                val attempt = conn.attempt
                prompt.request(route.host, user) { pw -> offer(Command.Password(key, attempt, pw)) }
                return
            }
        }
        startConnect(conn, fromStore)
    }

    private fun startConnect(conn: Connection, fromStore: Boolean) {
        val config = MqttClientConfig(
            MqttUri.parse(conn.route.host), conn.clientId, conn.username, conn.password,
            connectTimeoutSec = connectTimeoutSec, random = random, opener = opener,
        )
        val key = conn.route.key
        val attempt = conn.attempt
        val t = Thread({
            val publisher = MqttPublisher(config, log)
            try {
                publisher.connect()
                offer(Command.Connected(key, attempt, publisher, fromStore))
            } catch (e: Exception) {
                publisher.close()
                offer(Command.Failed(key, attempt, e.message ?: e.javaClass.simpleName))
            }
        }, "cosic-mqtt-connect")
        t.isDaemon = true
        t.start()
    }

    private fun deliver(s: Command.Send) {
        val publisher = connections[s.route.key]?.publisher
        if (publisher == null || !publisher.isConnected) {
            droppedCount.incrementAndGet()
            return
        }
        try {
            publisher.publish(s.message.topic, s.message.payload)
            sent.incrementAndGet()
        } catch (e: Exception) {
            droppedCount.incrementAndGet()
            log("mqtt send failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    private companion object {
        const val IDLE_EXIT_NANOS = 3_000_000_000L
        const val TICK_NANOS = 500_000_000L
        const val MAX_PENDING = 20_000
    }
}
