package com.octopusman.cosic.share

import com.sun.net.httpserver.HttpServer
import cosic.io.ShareLink
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The short links of the web REPL: the request is the one the REPL makes, and every kind of answer has its result. */
class LegacyShareTest {
    private class Seen(val uri: String, val headers: Map<String, String>)

    private val seen = CopyOnWriteArrayList<Seen>()
    private var status = 200
    private var body = "[]"

    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0).apply {
        createContext("/rest/v1/code_v1") { exchange ->
            seen += Seen(exchange.requestURI.toString(), exchange.requestHeaders.entries.associate { it.key.lowercase() to it.value.joinToString(",") })
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        start()
    }

    private val resolver = LegacyShareResolver(urlOf = { id ->
        "http://127.0.0.1:${server.address.port}" + ShareLink.legacyRequestUrl(id).substringAfter("supabase.co")
    })

    @AfterTest
    fun stop() = server.stop(0)

    @Test
    fun theRequestIsTheOneOfTheWebRepl() {
        body = "[{\"code\":\"s(\\\"bd\\\")\"}]"
        assertEquals(LegacyResult.Found("s(\"bd\")"), resolver.resolve("J01s5i1J0200"))
        val request = seen.single()
        assertEquals("/rest/v1/code_v1?select=code&hash=eq.J01s5i1J0200", request.uri)
        assertEquals(ShareLink.LEGACY_KEY, request.headers["apikey"])
        assertEquals("Bearer ${ShareLink.LEGACY_KEY}", request.headers["authorization"])
    }

    @Test
    fun aKeyTheServerDoesNotHaveIsNotFound() {
        body = "[]"
        assertEquals(LegacyResult.NotFound, resolver.resolve("nope"))
    }

    @Test
    fun anAnswerThatIsNotARowIsNotFound() {
        body = "{\"message\":\"nope\"}"
        assertEquals(LegacyResult.NotFound, resolver.resolve("x"))
    }

    @Test
    fun aServerErrorFails() {
        status = 503
        body = ""
        val result = resolver.resolve("x")
        assertIs<LegacyResult.Failed>(result)
        assertTrue("503" in result.message, result.message)
    }

    @Test
    fun anAnswerThatIsTooLongFails() {
        body = "[{\"code\":\"" + "a".repeat(5000) + "\"}]"
        val result = LegacyShareResolver(
            urlOf = { "http://127.0.0.1:${server.address.port}/rest/v1/code_v1?select=code&hash=eq.$it" },
            maxBytes = 1000,
        ).resolve("x")
        assertIs<LegacyResult.Failed>(result)
    }

    @Test
    fun noServerFails() {
        val port = server.address.port
        server.stop(0)
        val result = LegacyShareResolver(urlOf = { "http://127.0.0.1:$port/rest/v1/code_v1?select=code&hash=eq.$it" }, connectTimeoutMs = 500).resolve("x")
        assertIs<LegacyResult.Failed>(result)
    }
}
