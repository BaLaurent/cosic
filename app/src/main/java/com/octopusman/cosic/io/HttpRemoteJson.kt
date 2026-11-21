package com.octopusman.cosic.io

import cosic.RemoteJson
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

private const val TIMEOUT_MS = 10_000
private const val MAX_BYTES = 4 * 1024 * 1024

/**
 * Fetches the JSON a program asks for by address (`midimaps('github:user/repo')` reads `midimap.json` there) over HTTP(S): one GET, ten seconds to connect and
 * ten to read, at most [maxBytes] of answer. Blocks, so it is for the threads the engine evaluates and plays on, never the main thread. Null when the address is
 * no http(s) address, the server does not answer 200 in time, the answer is too long or no JSON object.
 *
 * The values are the ones a program's own objects hold: numbers are [Double]s, objects are insertion-ordered maps in the key order of JavaScript (integer-like
 * keys first, ascending), arrays are lists, `null` is null.
 */
class HttpRemoteJson(
    private val connectTimeoutMs: Int = TIMEOUT_MS,
    private val readTimeoutMs: Int = TIMEOUT_MS,
    private val maxBytes: Int = MAX_BYTES,
) : RemoteJson {
    override fun fetch(url: String): Map<String, Any?>? {
        val address = try {
            URL(url)
        } catch (e: IOException) {
            return null
        }
        if (address.protocol != "http" && address.protocol != "https") return null
        val connection = try {
            address.openConnection() as HttpURLConnection
        } catch (e: IOException) {
            return null
        }
        try {
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.setRequestProperty("Accept", "application/json")
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val bytes = connection.inputStream.use { readAtMost(it, maxBytes + 1) }
            if (bytes.size > maxBytes) return null
            return parseObject(bytes.toString(Charsets.UTF_8))
        } catch (e: IOException) {
            return null
        } finally {
            connection.disconnect()
        }
    }

    private fun readAtMost(stream: java.io.InputStream, limit: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (out.size() < limit) {
            val n = stream.read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    companion object {
        private val json = Json

        /** The object [text] is, as the values of a program; null when it is not JSON or no object. */
        fun parseObject(text: String): Map<String, Any?>? {
            val element = try {
                json.parseToJsonElement(text)
            } catch (e: SerializationException) {
                return null
            } catch (e: IllegalArgumentException) {
                return null
            }
            @Suppress("UNCHECKED_CAST")
            return (element as? JsonObject)?.let { values(it) as Map<String, Any?> }
        }

        /** [element] as the value `JSON.parse` would make of it. */
        internal fun values(element: JsonElement): Any? = when (element) {
            is JsonNull -> null
            is JsonPrimitive -> when {
                element.isString -> element.content
                element.content == "true" -> true
                element.content == "false" -> false
                else -> element.content.toDouble()
            }
            is JsonArray -> element.mapTo(ArrayList(element.size)) { values(it) }
            is JsonObject -> ordered(element)
        }

        /** The entries of [obj] in the order a JavaScript object lists them: the keys that are array indices first, by number, then the others as they were written. */
        private fun ordered(obj: JsonObject): LinkedHashMap<String, Any?> {
            val indices = obj.keys.filter { isArrayIndex(it) }.sortedBy { it.toLong() }
            val result = LinkedHashMap<String, Any?>(obj.size * 2)
            for (key in indices) result[key] = values(obj.getValue(key))
            for ((key, value) in obj) if (!isArrayIndex(key)) result[key] = values(value)
            return result
        }

        /** An array index: the canonical text of an integer from 0 to 2^32 - 2. */
        private fun isArrayIndex(key: String): Boolean {
            if (key.isEmpty() || key.length > 10) return false
            if (key.length > 1 && key[0] == '0') return false
            if (!key.all { it in '0'..'9' }) return false
            return key.toLong() < 4_294_967_295L
        }
    }
}
