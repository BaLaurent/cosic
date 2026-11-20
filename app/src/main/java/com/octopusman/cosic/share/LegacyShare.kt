package com.octopusman.cosic.share

import cosic.io.ShareLink
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What asking for the code of a short link (`https://strudel.cc/?J01s5i1J0200`) gave. */
internal sealed interface LegacyResult {
    /** The code of the link. */
    data class Found(val code: String) : LegacyResult

    /** The server has no code under that key. */
    data object NotFound : LegacyResult

    /** The server could not be asked, or did not answer well: [message] says why. */
    data class Failed(val message: String) : LegacyResult
}

/**
 * Gets the code of the short links the web REPL used to make. Their key is not the code but a key into a table of the REPL's server, which is asked as
 * the web REPL asks it (`initCode` of website/src/repl/util.mjs): a GET of [ShareLink.legacyRequestUrl] with the public key of the REPL as `apikey` and as `Authorization: Bearer`.
 * The row that comes back holds the code. The links that carry their code (`https://strudel.cc/#...`) need none of this: see [ShareLink.parse].
 */
internal class LegacyShareResolver(
    private val urlOf: (String) -> String = ShareLink::legacyRequestUrl,
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 15_000,
    private val maxBytes: Int = 1 shl 20,
) {
    /** Asks for the code under [id]. Blocks: not for the main thread. */
    fun resolve(id: String): LegacyResult {
        val connection = try {
            URL(urlOf(id)).openConnection() as HttpURLConnection
        } catch (e: IOException) {
            return LegacyResult.Failed(e.message ?: e.toString())
        }
        try {
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.setRequestProperty("apikey", ShareLink.LEGACY_KEY)
            connection.setRequestProperty("Authorization", "Bearer ${ShareLink.LEGACY_KEY}")
            connection.setRequestProperty("Accept", "application/json")
            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) return LegacyResult.Failed("HTTP $status")
            val body = connection.inputStream.use { stream ->
                val bytes = readAtMost(stream, maxBytes + 1)
                if (bytes.size > maxBytes) return LegacyResult.Failed("the answer is too long")
                bytes.toString(Charsets.UTF_8)
            }
            val code = ShareLink.legacyCode(body)
            return if (code == null) LegacyResult.NotFound else LegacyResult.Found(code)
        } catch (e: IOException) {
            return LegacyResult.Failed(e.message ?: e.toString())
        } finally {
            connection.disconnect()
        }
    }

    /** [resolve] on the thread for blocking work. */
    suspend fun fetch(id: String): LegacyResult = withContext(Dispatchers.IO) { resolve(id) }

    /** Up to [limit] bytes of [stream] (`readNBytes` needs API 33, the app runs from 26). */
    private fun readAtMost(stream: java.io.InputStream, limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (out.size() < limit) {
            val n = stream.read(buffer, 0, minOf(buffer.size, limit - out.size()))
            if (n < 0) break
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }
}
