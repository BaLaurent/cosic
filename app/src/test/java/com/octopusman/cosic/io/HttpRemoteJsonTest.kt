package com.octopusman.cosic.io

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HttpRemoteJsonTest {
    private val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).also { it.start() }
    private val base get() = "http://127.0.0.1:${server.address.port}"

    @AfterTest
    fun stop() {
        server.stop(0)
    }

    private fun serve(path: String, status: Int = 200, body: String, contentType: String = "application/json") {
        server.createContext(path) { exchange ->
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", contentType)
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    @Test
    fun anObjectIsFetchedAsTheValuesOfAProgram() {
        serve("/map.json", body = """{"cutoff": [1, 74, "x"], "gain": 0.5, "on": true, "none": null, "nested": {"a": 1}}""")
        val map = HttpRemoteJson().fetch("$base/map.json")!!
        assertEquals(listOf("cutoff", "gain", "on", "none", "nested"), map.keys.toList())
        assertEquals(listOf(1.0, 74.0, "x"), map["cutoff"])
        assertEquals(0.5, map["gain"])
        assertEquals(true, map["on"])
        assertTrue(map.containsKey("none") && map["none"] == null)
        assertEquals(mapOf("a" to 1.0), map["nested"])
    }

    @Test
    fun keysThatAreIndicesComeFirstInAscendingOrderAsInAnObject() {
        val map = HttpRemoteJson.parseObject("""{"b": 1, "10": 2, "a": 3, "2": 4, "01": 5}""")!!
        assertEquals(listOf("2", "10", "b", "a", "01"), map.keys.toList())
    }

    @Test
    fun aKeyWrittenTwiceKeepsItsFirstPlaceAndTheLastValue() {
        val map = HttpRemoteJson.parseObject("""{"a": 1, "b": 2, "a": 3}""")!!
        assertEquals(listOf("a", "b"), map.keys.toList())
        assertEquals(3.0, map["a"])
    }

    @Test
    fun numbersAreAlwaysDoubles() {
        val map = HttpRemoteJson.parseObject("""{"i": 3, "f": 1.5, "e": 1e3, "n": -0}""")!!
        assertEquals(3.0, map["i"])
        assertEquals(1.5, map["f"])
        assertEquals(1000.0, map["e"])
        assertEquals(-0.0, map["n"])
    }

    @Test
    fun anAnswerThatIsNoObjectIsNothing() {
        assertNull(HttpRemoteJson.parseObject("[1, 2]"))
        assertNull(HttpRemoteJson.parseObject("\"x\""))
        assertNull(HttpRemoteJson.parseObject("{not json"))
        assertNull(HttpRemoteJson.parseObject(""))
    }

    @Test
    fun aStatusOtherThan200IsNothing() {
        serve("/gone.json", status = 404, body = "{}")
        assertNull(HttpRemoteJson().fetch("$base/gone.json"))
    }

    @Test
    fun anAnswerThatIsTooLongIsNothing() {
        serve("/big.json", body = """{"a": "${"x".repeat(5_000)}"}""")
        assertNull(HttpRemoteJson(maxBytes = 1_000).fetch("$base/big.json"))
        assertEquals(setOf("a"), HttpRemoteJson(maxBytes = 10_000).fetch("$base/big.json")?.keys)
    }

    @Test
    fun anAddressThatIsNoHttpAddressIsNothing() {
        assertNull(HttpRemoteJson().fetch("file:///etc/passwd"))
        assertNull(HttpRemoteJson().fetch("github:user/repo"))
        assertNull(HttpRemoteJson().fetch("not an address"))
    }

    @Test
    fun aServerThatIsNotThereIsNothing() {
        val port = server.address.port
        server.stop(0)
        assertNull(HttpRemoteJson(connectTimeoutMs = 500, readTimeoutMs = 500).fetch("http://127.0.0.1:$port/x.json"))
    }
}
