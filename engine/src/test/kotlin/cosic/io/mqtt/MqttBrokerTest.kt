package cosic.io.mqtt

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What a broker saw of one connection: the decoded CONNECT, and the packets after it as (first byte's type, body). */
internal class Seen {
    val packets = CopyOnWriteArrayList<Pair<Int, ByteArray>>()
    @Volatile var connectFlags = -1
    @Volatile var clientId: String? = null
    @Volatile var username: String? = null
    @Volatile var password: String? = null
    @Volatile var path: String? = null
    @Volatile var protocols: String? = null

    /** The PUBLISH packets as (topic, payload text). */
    fun published(): List<Pair<String, String>> = packets.filter { it.first == 3 }.map { (_, body) ->
        val n = ((body[0].toInt() and 0xFF) shl 8) or (body[1].toInt() and 0xFF)
        body.copyOfRange(2, 2 + n).decodeToString() to body.copyOfRange(2 + n, body.size).decodeToString()
    }

    fun types(): List<Int> = packets.map { it.first }
}

/** A broker for tests: accepts connections on a free port, speaks as much MQTT as a publisher needs, over plain TCP or a WebSocket. */
internal class FakeBroker(private val ws: Boolean, private val connackCode: Int = 0, private val requireUser: String? = null) : Closeable {
    private val server = ServerSocket(0)
    val connections = CopyOnWriteArrayList<Seen>()
    private val sockets = ConcurrentLinkedQueue<Socket>()
    val port: Int get() = server.localPort
    val uri: String get() = (if (ws) "ws://127.0.0.1:" else "tcp://127.0.0.1:") + port + (if (ws) "/mqtt" else "")

    init {
        Thread({
            try {
                while (true) {
                    val s = server.accept()
                    sockets.add(s)
                    Thread({ serve(s) }, "fake-broker").apply { isDaemon = true }.start()
                }
            } catch (_: Exception) {
            }
        }, "fake-broker-accept").apply { isDaemon = true }.start()
    }

    private fun serve(s: Socket) {
        val seen = Seen()
        try {
            val raw = s.getInputStream()
            val out = s.getOutputStream()
            val input: InputStream
            val send: (ByteArray) -> Unit
            if (ws) {
                val head = StringBuilder()
                while (!head.endsWith("\r\n\r\n")) head.append(raw.read().also { if (it < 0) throw EOFException() }.toChar())
                val lines = head.toString().split("\r\n")
                seen.path = lines[0].split(' ')[1]
                val headers = lines.drop(1).filter { ':' in it }.associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
                seen.protocols = headers["sec-websocket-protocol"]
                val accept = WsFraming.acceptFor(headers["sec-websocket-key"]!!)
                out.write(
                    ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n" +
                        "Sec-WebSocket-Protocol: mqtt\r\n\r\n").toByteArray(),
                )
                out.flush()
                input = WsFraming.MessageInput(raw) { _, _ -> }
                send = { b ->
                    out.write(byteArrayOf(0x82.toByte(), b.size.toByte()) + b)
                    out.flush()
                }
            } else {
                input = raw
                send = { b ->
                    out.write(b)
                    out.flush()
                }
            }
            connections.add(seen)
            while (true) {
                val first = input.read()
                if (first < 0) break
                var len = 0
                var shift = 0
                while (true) {
                    val b = input.read()
                    len += (b and 0x7F) shl shift
                    if (b and 0x80 == 0) break
                    shift += 7
                }
                val body = input.readNBytes(len)
                val type = first ushr 4
                if (type == 1) {
                    decodeConnect(seen, body)
                    val refused = connackCode != 0 || (requireUser != null && seen.username != requireUser)
                    send(byteArrayOf(0x20, 2, 0, (if (connackCode != 0) connackCode else if (refused) 4 else 0).toByte()))
                    if (refused) break
                } else {
                    seen.packets.add(type to body)
                    if (type == 12) send(byteArrayOf(0xD0.toByte(), 0))
                }
            }
        } catch (_: Exception) {
        } finally {
            runCatching { s.close() }
        }
    }

    private fun decodeConnect(seen: Seen, body: ByteArray) {
        var p = 2 + 4 + 1 // protocol name, level
        seen.connectFlags = body[p++].toInt() and 0xFF
        p += 2 // keep alive
        fun string(): String {
            val n = ((body[p].toInt() and 0xFF) shl 8) or (body[p + 1].toInt() and 0xFF)
            return body.copyOfRange(p + 2, p + 2 + n).decodeToString().also { p += 2 + n }
        }
        seen.clientId = string()
        if (seen.connectFlags and 0x80 != 0) seen.username = string()
        if (seen.connectFlags and 0x40 != 0) seen.password = string()
    }

    /** Hangs up on every connection. */
    fun dropAll() = sockets.forEach { runCatching { it.close() } }

    override fun close() {
        runCatching { server.close() }
        dropAll()
    }
}

private fun MqttPublisher.publish(topic: String, text: String) = publish(topic, text.encodeToByteArray())

internal fun waitFor(what: String, millis: Long = 5000, condition: () -> Boolean) {
    val deadline = System.nanoTime() + millis * 1_000_000
    while (!condition()) {
        check(System.nanoTime() < deadline) { "timed out waiting for $what" }
        Thread.sleep(5)
    }
}

class MqttBrokerTest {
    private val brokers = ArrayList<FakeBroker>()
    private val publishers = ArrayList<MqttPublisher>()

    private fun broker(ws: Boolean, connackCode: Int = 0, requireUser: String? = null) = FakeBroker(ws, connackCode, requireUser).also(brokers::add)

    private fun publisher(b: FakeBroker, user: String? = null, password: String? = null, clientId: String = "cid") =
        MqttPublisher(MqttClientConfig(MqttUri.parse(b.uri), clientId, user, password, connectTimeoutSec = 5), {}).also(publishers::add)

    @AfterTest
    fun cleanUp() {
        publishers.forEach { it.close() }
        brokers.forEach { it.close() }
    }

    @Test
    fun publishesOverTcpWithCredentials() {
        val b = broker(ws = false)
        val p = publisher(b, "u", "p")
        p.connect()
        assertTrue(p.isConnected)
        p.publish("/t", "hi")
        waitFor("the publish") { b.connections.firstOrNull()?.published()?.isNotEmpty() == true }
        val seen = b.connections.single()
        assertEquals(0xC2, seen.connectFlags)
        assertEquals("cid", seen.clientId)
        assertEquals("u", seen.username)
        assertEquals("p", seen.password)
        assertEquals(listOf("/t" to "hi"), seen.published())
    }

    @Test
    fun publishesOverAWebSocket() {
        val b = broker(ws = true)
        val p = publisher(b)
        p.connect()
        p.publish("/a/b", """{"s":"bd"}""")
        p.publish("/ü", "€")
        waitFor("the publishes") { b.connections.firstOrNull()?.published()?.size == 2 }
        val seen = b.connections.single()
        assertEquals("/mqtt", seen.path)
        assertEquals("mqtt", seen.protocols)
        assertEquals(0x02, seen.connectFlags)
        assertEquals(listOf("/a/b" to """{"s":"bd"}""", "/ü" to "€"), seen.published())
    }

    @Test
    fun aRefusedConnectionSaysWhy() {
        val b = broker(ws = false, connackCode = 5)
        val p = publisher(b)
        val e = assertFailsWith<MqttException> { p.connect() }
        assertEquals("AMQJS0006E Bad Connack return code:5 Connection Refused: not authorized.", e.message)
        assertFalse(p.isConnected)
    }

    @Test
    fun aPingGoesOutOnlyAfterTheKeepAliveTime() {
        val b = broker(ws = false)
        val p = publisher(b)
        p.connect()
        val now = System.currentTimeMillis()
        p.tickKeepAlive(now + 59_000)
        Thread.sleep(100)
        assertEquals(emptyList(), b.connections.single().types())
        p.tickKeepAlive(now + 61_000)
        waitFor("the ping") { b.connections.single().types() == listOf(12) }
    }

    @Test
    fun aPublishCountsAsActivityForTheKeepAlive() {
        val b = broker(ws = true)
        val p = publisher(b)
        p.connect()
        p.publish("/t", "x")
        p.tickKeepAlive(System.currentTimeMillis() + 30_000)
        waitFor("the publish") { b.connections.single().types().isNotEmpty() }
        Thread.sleep(100)
        assertEquals(listOf(3), b.connections.single().types())
    }

    @Test
    fun closingSaysGoodbye() {
        val b = broker(ws = false)
        val p = publisher(b)
        p.connect()
        p.close()
        assertFalse(p.isConnected)
        waitFor("the disconnect") { b.connections.single().types() == listOf(14) }
        assertFailsWith<java.io.IOException> { p.publish("/t", "x") }
    }

    @Test
    fun aBrokerThatHangsUpEndsTheConnection() {
        val b = broker(ws = false)
        val p = publisher(b)
        p.connect()
        b.dropAll()
        waitFor("the loss") { !p.isConnected }
        assertFailsWith<java.io.IOException> { p.publish("/t", "x") }
    }

    // ---- the device -------------------------------------------------------------------------------------------------------------

    private class Credentials(var stored: String? = null) : MqttCredentials {
        val saved = CopyOnWriteArrayList<Triple<String, String, String>>()
        val cleared = CopyOnWriteArrayList<Pair<String, String>>()

        override fun load(host: String, user: String) = stored

        override fun save(host: String, user: String, password: String) {
            saved.add(Triple(host, user, password))
        }

        override fun clear(host: String, user: String) {
            cleared.add(host to user)
        }
    }

    private class Prompt(val answer: String?) : PasswordPrompt {
        val asked = AtomicInteger()

        override fun request(host: String, user: String, onResult: (String?) -> Unit) {
            asked.incrementAndGet()
            Thread { onResult(answer) }.start()
        }
    }

    private val logs = CopyOnWriteArrayList<String>()
    private val devices = ArrayList<MqttDevice>()

    private fun device(credentials: MqttCredentials? = null, prompt: PasswordPrompt? = null) =
        MqttDevice(credentials, prompt, { logs.add(it) }, System::nanoTime, 5, DefaultSocketOpener, SecureRandom(), { 42 }).also(devices::add)

    @AfterTest
    fun stopDevices() = devices.forEach { it.stopAll() }

    private fun MqttDevice.awaitConnected(b: FakeBroker, n: Int = 1) = waitFor("connection $n") { b.connections.size >= n && "Connected to mqtt broker" in logs }

    @Test
    fun theDeviceSendsMessagesInTheOrderTheyAreDueWhenTheyAreDue() {
        val b = broker(ws = true)
        val d = device()
        val route = MqttRoute(b.uri, topic = "/t")
        d.prepare(route)
        d.awaitConnected(b)
        val start = System.nanoTime()
        d.send(route, MqttMessage("/t", "late".toByteArray()), start + 400_000_000)
        d.send(route, MqttMessage("/t", "early".toByteArray()), start + 50_000_000)
        d.send(route, MqttMessage("/t", "past".toByteArray()), start - 1_000_000_000)
        waitFor("three messages") { b.connections.single().published().size == 3 }
        assertEquals(listOf("past", "early", "late"), b.connections.single().published().map { it.second })
        assertTrue(System.nanoTime() - start >= 400_000_000, "the last one waited for its time")
        assertEquals("cosic-42", b.connections.single().clientId)
    }

    @Test
    fun aMessageForAConnectionThatIsNotUpIsDropped() {
        val d = device()
        d.send(MqttRoute("tcp://127.0.0.1:1", topic = "/t"), MqttMessage("/t", "x".toByteArray()), System.nanoTime())
        waitFor("the drop") { d.droppedCountValue == 1L }
        assertEquals(0, d.sentCount)
    }

    @Test
    fun theClientIdOfTheRouteIsUsedAndSharedByTheKey() {
        val b = broker(ws = false)
        val d = device()
        val a = MqttRoute(b.uri, client = "me", topic = "/a")
        val c = MqttRoute(b.uri, client = "me", topic = "/c")
        d.prepare(a)
        d.awaitConnected(b)
        d.prepare(c)
        Thread.sleep(200)
        assertEquals(1, b.connections.size, "one connection for the same host and client")
        assertEquals("me", b.connections.single().clientId)
        assertEquals("${b.uri}-me", a.key)
        assertEquals("tcp://h-undefined", MqttRoute("tcp://h").key)
    }

    @Test
    fun aDeadConnectionIsMadeAgainWhenTheProgramIsEvaluatedAgain() {
        val b = broker(ws = false)
        val d = device()
        val route = MqttRoute(b.uri, topic = "/t")
        d.prepare(route)
        d.awaitConnected(b)
        b.dropAll()
        Thread.sleep(300)
        logs.clear()
        d.prepare(route)
        waitFor("the second connection") { b.connections.size == 2 && "Connected to mqtt broker" in logs }
        d.send(route, MqttMessage("/t", "again".toByteArray()), System.nanoTime())
        waitFor("the message") { b.connections[1].published().isNotEmpty() }
    }

    @Test
    fun aStoredPasswordIsUsedAndForgottenWhenRefused() {
        val b = broker(ws = false, requireUser = "other")
        val creds = Credentials(stored = "secret")
        val d = device(creds)
        d.prepare(MqttRoute(b.uri, username = "me", topic = "/t"))
        waitFor("the refusal") { creds.cleared.isNotEmpty() }
        assertEquals("secret", b.connections.single().password)
        assertEquals(listOf(b.uri to "me"), creds.cleared)
        assertTrue(creds.saved.isEmpty())
    }

    @Test
    fun aPromptedPasswordIsSavedWhenAccepted() {
        val b = broker(ws = true, requireUser = "me")
        val creds = Credentials()
        val prompt = Prompt("hunter2")
        val d = device(creds, prompt)
        d.prepare(MqttRoute(b.uri, username = "me", topic = "/t"))
        waitFor("the save") { creds.saved.isNotEmpty() }
        assertEquals(1, prompt.asked.get())
        assertEquals(Triple(b.uri, "me", "hunter2"), creds.saved.single())
        assertEquals("hunter2", b.connections.single().password)
    }

    @Test
    fun noPasswordGivenMeansNoConnection() {
        val b = broker(ws = false)
        val d = device(Credentials(), Prompt(null))
        d.prepare(MqttRoute(b.uri, username = "me", topic = "/t"))
        waitFor("the report") { logs.any { it.startsWith("Connection failed: no password") } }
        assertTrue(b.connections.isEmpty())
    }

    @Test
    fun aPasswordInTheCodeIsNeverStored() {
        val b = broker(ws = false)
        val creds = Credentials()
        val d = device(creds, Prompt("unused"))
        d.prepare(MqttRoute(b.uri, username = "me", password = "inline", topic = "/t"))
        d.awaitConnected(b)
        assertEquals("inline", b.connections.single().password)
        assertTrue(creds.saved.isEmpty())
    }

    @Test
    fun aBadHostIsReportedNotConnected() {
        val d = device()
        d.prepare(MqttRoute("http://nowhere", topic = "/t"))
        waitFor("the report") { logs.any { "Invalid argument http://nowhere for host" in it } }
    }

    @Test
    fun stopAllClosesAndForgetsWhatWasWaiting() {
        val b = broker(ws = false)
        val d = device()
        val route = MqttRoute(b.uri, topic = "/t")
        d.prepare(route)
        d.awaitConnected(b)
        d.send(route, MqttMessage("/t", "never".toByteArray()), System.nanoTime() + 60_000_000_000)
        d.stopAll()
        waitFor("the disconnect") { 14 in b.connections.single().types() }
        assertEquals(0, b.connections.single().published().size)
    }
}
