package cosic.script.catalogue

import cosic.EngineEffects
import cosic.FrameClock
import cosic.io.ConsoleLog
import cosic.io.OutputBus
import cosic.io.mqtt.FakeBroker
import cosic.io.mqtt.MqttDevice
import cosic.io.mqtt.MqttMessage
import cosic.io.mqtt.MqttOutput
import cosic.io.mqtt.MqttRoute
import cosic.pattern.CosicError
import cosic.player.manualBus
import cosic.io.mqtt.waitFor
import cosic.player.queued
import cosic.player.schedulerOf
import cosic.script.ReplSession
import cosic.script.SessionHost
import cosic.script.shown
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** `.mqtt()`, `.robot()`, `move` and `motor` as a program uses them: the route they make, the messages the scheduled haps become, and their errors. */
class MqttCatalogueTest {
    private class Recorder : MqttOutput {
        val prepared = CopyOnWriteArrayList<MqttRoute>()
        val sent = CopyOnWriteArrayList<Triple<MqttRoute, String, Pair<String, Long>>>()

        override fun prepare(route: MqttRoute) {
            prepared.add(route)
        }

        override fun send(route: MqttRoute, message: MqttMessage, dueNanos: Long) {
            sent.add(Triple(route, message.topic, message.payload.decodeToString() to dueNanos))
        }

        override fun stopAll() {}
    }

    private class World(clock: FrameClock? = null) {
        val console = ConsoleLog()
        val bus = OutputBus(console, autoStart = false).also { it.clock = clock }
        val recorder = Recorder().also { bus.mqtt = it }
        val session = ReplSession(SessionHost(effects = EngineEffects(console = console, outputs = bus)))

        fun play(code: String) = session.evaluate(code).pattern

        /** The hooks of the haps of the first [cycles] cycles run through a scheduler on the bus (so on the dispatcher's terms). */
        fun run(code: String, cycles: Int = 1): List<cosic.player.SoundEvent> {
            val scheduler = schedulerOf(play(code), bus)
            scheduler.play(0)
            scheduler.schedule(96_000L * cycles)
            bus.drain()
            return scheduler.queued()
        }
    }

    @Test
    fun theRouteIsMadeAtEvaluationAndPrepared() {
        val w = World()
        w.play("""s("bd").mqtt('user', 'pw', '/topic', 'tcp://broker:1883', 'me', 0.25, false)""")
        val r = w.recorder.prepared.single()
        assertEquals("tcp://broker:1883", r.host)
        assertEquals("me", r.client)
        assertEquals("user", r.username)
        assertEquals("pw", r.password)
        assertEquals("/topic", r.topic)
        assertEquals(0.25, r.latencySec)
        assertEquals(false, r.addMeta)
    }

    @Test
    fun defaultsAreThoseOfMqttMjs() {
        val w = World()
        w.play("""s("bd").mqtt()""")
        val r = w.recorder.prepared.single()
        assertEquals("wss://localhost:8883/", r.host)
        assertEquals(0.0, r.latencySec)
        assertTrue(r.addMeta)
        assertEquals(null, r.topic)
        assertEquals(null, r.username)
    }

    @Test
    fun hapsAreSentNotPlayedAndCarryMeta() {
        val w = World()
        val audio = w.run("""s("bd sd").speed(2).mqtt('', '', '/t', 'tcp://h:1883')""")
        assertEquals(emptyList(), audio, "a dominant hook takes the sound away")
        val sent = w.recorder.sent.take(2) // the look-ahead of the scheduler reaches into the next cycle as well
        assertEquals("/t", sent[0].second)
        // 0.5 cycles at the 0.5 cycles per second of the scheduler: one second
        assertEquals("""{"s":"bd","speed":2,"duration":1,"cps":0.5}""", sent[0].third.first)
        assertEquals("""{"s":"sd","speed":2,"duration":1,"cps":0.5}""", sent[1].third.first)
        assertEquals("", w.recorder.prepared.single().username ?: "")
    }

    @Test
    fun theDueTimeIsTheTimeTheFrameIsHeardPlusTheLatency() {
        val w = World(clock = { frame -> 1_000_000_000L + frame * 1_000_000L })
        w.run("""s("bd sd").mqtt(undefined, undefined, '/t', 'tcp://h', undefined, 0.5)""")
        // the frames of the two onsets are 0 and 48000 (half a cycle at 0.5 cps and 48 kHz) plus the start delay of the scheduler, if any
        val due = w.recorder.sent.take(2).map { it.third.second }
        assertEquals(48_000L * 1_000_000L, due[1] - due[0])
        assertEquals(1_000_000_000L + 500_000_000L, due[0])
    }

    @Test
    fun theTopicOfTheValueIsUsedWithoutOne() {
        val w = World()
        w.run("""n("1").set({topic: ['a', 'b']}).mqtt(undefined, undefined, undefined, 'tcp://h')""")
        assertEquals("/a/b", w.recorder.sent.first().second)
    }

    @Test
    fun aStringValueIsSentAsText() {
        val w = World()
        w.run("""pure('hello').mqtt(undefined, undefined, '/t', 'tcp://h')""")
        assertEquals("hello", w.recorder.sent.first().third.first)
    }

    @Test
    fun robotUsesTheMoveTopicAndTheRobotAddress() {
        val w = World()
        w.play("""s("bd").robot(7)""")
        val r = w.recorder.prepared.single()
        assertEquals("ws://192.168.8.248:9001/mqtt", r.host)
        assertEquals("/move/7", r.topic)
        w.play("""s("bd").robot('arm', 'ws://10.0.0.2:9001/mqtt')""")
        assertEquals("ws://10.0.0.2:9001/mqtt", w.recorder.prepared.last().host)
        assertEquals("/move/arm", w.recorder.prepared.last().topic)
    }

    @Test
    fun moveAndMotorAreControls() {
        val w = World()
        assertEquals(listOf("[ 0/1 → 1/1 | s:bd move:3 motor:0.5 ]"), shown(w.play("""s("bd").move(3).motor(0.5)""")))
        assertEquals(listOf("[ 0/1 → 1/1 | move:1 ]"), shown(w.play("move(1)")))
    }

    @Test
    fun patternArgumentsAreRefused() {
        val w = World()
        val e = assertFailsWith<CosicError> { w.play("""s("bd").mqtt("u")""") }
        assertEquals("mqtt(): arguments must be plain values (use single-quoted strings)", e.message)
    }

    @Test
    fun aBadHostIsAnEvaluationError() {
        val w = World()
        val e = assertFailsWith<CosicError> { w.play("""s("bd").mqtt(undefined, undefined, '/t', 'http://h')""") }
        assertEquals("AMQJS0013E Invalid argument http://h for host.", e.message)
    }

    @Test
    fun withoutADeviceItIsSaidOnTheConsole() {
        val w = World()
        w.bus.mqtt = null
        w.run("""s("bd").mqtt(undefined, undefined, '/t', 'tcp://h')""")
        assertNotNull(w.console.entries.find { "no MQTT output" in it.message })
    }

    @Test
    fun valuesThatCannotBeSentAreReportedAndNothingSounds() {
        val w = World()
        val audio = w.run("""pure(5).mqtt(undefined, undefined, '/t', 'tcp://h')""")
        assertEquals(emptyList(), audio)
        assertEquals(0, w.recorder.sent.size)
        assertNotNull(w.console.entries.find { "only maps and strings" in it.message })
    }

    @Test
    fun aProgramPublishesToABrokerThroughTheDevice() {
        FakeBroker(ws = true).use { broker ->
            val console = ConsoleLog()
            val bus = OutputBus(console, autoStart = false)
            val device = MqttDevice(null, null) { console.log(it) }
            bus.mqtt = device
            try {
                val session = ReplSession(SessionHost(effects = EngineEffects(console = console, outputs = bus)))
                val pattern = session.evaluate("""s("bd sd").mqtt('', '', '/drums', '${broker.uri}', 'prog', 0)""").pattern
                waitFor("the connection") { broker.connections.isNotEmpty() && console.entries.any { it.message == "Connected to mqtt broker" } }
                val scheduler = schedulerOf(pattern, bus)
                scheduler.play(0)
                scheduler.schedule(96_000)
                bus.drain()
                waitFor("two messages") { broker.connections.single().published().size >= 2 }
                val seen = broker.connections.single()
                assertEquals("prog", seen.clientId)
                assertEquals(
                    listOf(
                        "/drums" to """{"s":"bd","duration":1,"cps":0.5}""",
                        "/drums" to """{"s":"sd","duration":1,"cps":0.5}""",
                    ),
                    seen.published().take(2),
                )
            } finally {
                device.stopAll()
            }
        }
    }
}
