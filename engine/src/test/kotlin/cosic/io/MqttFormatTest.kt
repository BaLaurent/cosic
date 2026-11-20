package cosic.io

import cosic.io.mqtt.MqttRoute
import cosic.pattern.Undefined
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What a hap becomes as an MQTT message, with the cases of the plan and the behaviour of `mqtt.mjs`. */
class MqttFormatTest {
    private fun route(topic: String? = "/strudel-pattern", addMeta: Boolean = true) = MqttRoute("tcp://h", topic = topic, addMeta = addMeta)

    private fun send(value: Any?, route: MqttRoute = route(), onDrop: (String) -> Unit = {}) =
        mqttMessage(value, route, 0.5, 0.5, onDrop)?.let { it.topic to it.payload.decodeToString() }

    @Test
    fun aMapIsJsonWithTheMeta() {
        assertEquals(
            "/strudel-pattern" to """{"s":"sax","speed":2,"duration":0.5,"cps":0.5}""",
            send(linkedMapOf("s" to "sax", "speed" to 2.0)),
        )
    }

    @Test
    fun aStringIsSentAsItIs() {
        assertEquals("/strudel-pattern" to "hello", send("hello"))
    }

    @Test
    fun theTopicComesFromTheValueWhenTheRouteHasNone() {
        assertEquals(
            "/a/b" to """{"topic":["a","b"],"n":1,"duration":0.5,"cps":0.5}""",
            send(linkedMapOf("topic" to listOf("a", "b"), "n" to 1.0), route(topic = null)),
        )
        assertEquals("/x" to """{"topic":"x","duration":0.5,"cps":0.5}""", send(linkedMapOf("topic" to "x"), route(topic = null)))
    }

    @Test
    fun theTopicOfTheRouteWinsOverTheValue() {
        assertEquals("/strudel-pattern", send(linkedMapOf("topic" to "x"))!!.first)
    }

    @Test
    fun withoutMetaNothingIsAdded() {
        assertEquals("/strudel-pattern" to """{"s":"sax"}""", send(linkedMapOf("s" to "sax"), route(addMeta = false)))
    }

    @Test
    fun metaReplacesInPlaceAndRoutingDataIsLeftOut() {
        assertEquals(
            "/strudel-pattern" to """{"duration":0.5,"s":"a","cps":0.5}""",
            send(linkedMapOf("duration" to 9.0, "_cosic_engine" to "dough", "s" to "a", "cps" to 1.0)),
        )
    }

    @Test
    fun aListIsAMapWithIndexKeys() {
        assertEquals("/strudel-pattern" to """{"0":"a","1":2,"duration":0.5,"cps":0.5}""", send(listOf("a", 2.0)))
    }

    @Test
    fun otherValuesAreDropped() {
        val reasons = ArrayList<String>()
        assertNull(send(5.0) { reasons.add(it) })
        assertNull(send(true) { reasons.add(it) })
        assertNull(send(null) { reasons.add(it) })
        assertNull(send(Undefined) { reasons.add(it) })
        assertEquals(4, reasons.size)
        assertTrue(reasons.all { "only maps and strings" in it })
    }

    @Test
    fun topicsThatCannotBePublishedAreDropped() {
        val reasons = ArrayList<String>()
        assertNull(send(linkedMapOf("s" to "a"), route(topic = null)) { reasons.add(it) })
        assertNull(send("x", route(topic = "")) { reasons.add(it) })
        assertNull(send("x", route(topic = "/a/+")) { reasons.add(it) })
        assertNull(send("x", route(topic = "/a/#")) { reasons.add(it) })
        assertNull(send("x", route(topic = "/a\u0000b")) { reasons.add(it) })
        assertEquals(5, reasons.size)
    }

    @Test
    fun textIsUtf8() {
        val m = mqttMessage("héllo €", route(), 1.0, 1.0)!!
        assertEquals("héllo €", m.payload.decodeToString())
        assertEquals(10, m.payload.size)
    }
}
