package cosic.io

import cosic.sound.LooseJson
import java.net.URLEncoder
import java.util.Base64
import java.util.Locale

/**
 * The links of the web REPL, `https://strudel.cc/#ImMzIGUzIg%3D%3D`: the code of a program, written as UTF-8, in base64, escaped for a URL, after
 * the `#`. The REPL makes one with its Share button and reads the one it was opened with; this is both of those (`code2hash` and `hash2code` of
 * packages/core/util.mjs:354-383, and `initCode` of website/src/repl/util.mjs:26-50 that reads the address). What a link that cannot be read
 * gives is what the runtime of a browser does with it: `decodeURIComponent` and `atob` throw, `TextDecoder` replaces the bytes that are not UTF-8
 * (`engine/src/test/resources/share/oracle.json` holds what Node says about 450 of them).
 *
 * Older links are short, `https://strudel.cc/?J01s5i1J0200`: the code is on a server of the web REPL, which the app asks with [legacyRequestUrl].
 */
object ShareLink {
    /** Where the web REPL lives: [encode] makes links to it. */
    const val BASE_URL = "https://strudel.cc/"

    /** The hosts whose links [parse] reads (and the app opens): the web REPL and the address it had before. */
    val HOSTS: List<String> = listOf("strudel.cc", "strudel.tidalcycles.org")

    /** The server short links ask for their code, `supabase` of website/src/repl/util.mjs:12-17. */
    const val LEGACY_URL = "https://pidxdsxphlhzjnzmifth.supabase.co/rest/v1/code_v1"

    /**
     * The key the web REPL sends to that server (the public `anon` key of its source, util.mjs:15-18), as `apikey` and as
     * `Authorization: Bearer`.
     */
    const val LEGACY_KEY =
        "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InBpZHhkc3hwaGxoempuem1pZnRoIiwicm9sZSI6ImFub24iLCJpYXQiOjE2NTYyMzA1NTYsImV4cCI6MTk3MTgwNjU1Nn0.bqlw7802fsWRnqU5BLYtmXk_k-D1VFmbkHMywWc15NM"

    /** What a pasted text or a link that was opened holds. */
    sealed interface Imported {
        /** The code of a program. */
        data class Code(val text: String) : Imported

        /** A short link: [id] is the key of the code on the server (see [legacyRequestUrl]). */
        data class Legacy(val id: String) : Imported

        /** A link of the web REPL that holds no program, or one that cannot be read. */
        data object None : Imported
    }

    /** `code2hash(code)`: [code] as the part of a link after the `#`. */
    fun hash(code: String): String =
        Base64.getEncoder().encodeToString(utf8(code)).replace("+", "%2B").replace("/", "%2F").replace("=", "%3D")

    /** The link to [code] in the web REPL. */
    fun encode(code: String): String = "$BASE_URL#${hash(code)}"

    /**
     * The link the Share button of the REPL makes: [prebake] (the prebake script of its settings, which it includes by default) and a line
     * break go before the [code]. Without a script it is [encode].
     */
    fun forSharing(code: String, prebake: String?): String = encode(if (prebake == null) code else "$prebake\n$code")

    /**
     * `hash2code(hash)`: the code a [hash] stands for, or null where the REPL cannot read it (it throws: a character that is not base64, a
     * `%` that does not begin an escape). A byte order mark in front of the code is dropped, and bytes that are not UTF-8 are replaced
     * by U+FFFD, as the `TextDecoder` of a browser does.
     */
    fun decodeHash(hash: String): String? {
        val escaped = decodeUriComponent(hash) ?: return null
        val bytes = atob(escaped) ?: return null
        return decodeUtf8(bytes)
    }

    /**
     * What [input] is: the text of a link of the web REPL, a bare `#...` fragment, or code. The REPL reads its address in this order
     * (`initCode`): a fragment that is not empty is the code, else the first parameter of the query is the key of a short link. A text
     * that is not an address is taken as code as it is, so that sharing code from another app imports it. An address of another site, or of
     * another page of the web REPL (its pages have an anchor after a `#`, which is no code), is [Imported.None], and so is a text of nothing.
     */
    fun parse(input: String): Imported {
        val text = input.trim()
        if (text.isEmpty()) return Imported.None
        if (text.startsWith("#")) return codeOf(text.substring(1).substringBefore('#'))
        val rest = addressWithoutScheme(text) ?: return Imported.Code(input)
        val authorityEnd = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
        val host = rest.substring(0, authorityEnd).substringAfterLast('@').substringBefore(':').lowercase(Locale.ROOT)
        if (host.removePrefix("www.") !in HOSTS) return Imported.None
        val afterHost = rest.substring(authorityEnd)
        val path = afterHost.substringBefore('?').substringBefore('#')
        if (path != "" && path != "/") return Imported.None
        val hashIndex = afterHost.indexOf('#')
        // `href.split('#')[1]`: what is between the first and the second `#`
        val fragment = if (hashIndex < 0) "" else afterHost.substring(hashIndex + 1).substringBefore('#')
        if (fragment.isNotEmpty()) return codeOf(fragment)
        // `href.split('?')[1].split('#')[0].split('&')[0]`
        val id = afterHost.substringAfter('?', "").substringBefore('?').substringBefore('#').substringBefore('&')
        return if (id.isEmpty()) Imported.None else Imported.Legacy(id)
    }

    /** The request that gives the code of a short link: GET it with the headers `apikey` and `Authorization: Bearer` of [LEGACY_KEY]. */
    fun legacyRequestUrl(id: String): String = "$LEGACY_URL?select=code&hash=eq.${URLEncoder.encode(id, "UTF-8")}"

    /** The code in the answer to [legacyRequestUrl] (`[{"code": "..."}]`): that of its first row, or null when the server has none. */
    fun legacyCode(body: String): String? {
        val rows = try {
            LooseJson.parse(body) as? List<*>
        } catch (e: IllegalArgumentException) {
            null
        }
        return ((rows?.firstOrNull() as? Map<*, *>)?.get("code")) as? String
    }

    private fun codeOf(fragment: String): Imported = decodeHash(fragment)?.let { Imported.Code(it) } ?: Imported.None

    /** The address after its scheme, for an address of `http` or `https` (or one of the hosts written without it), else null. */
    private fun addressWithoutScheme(text: String): String? {
        val scheme = SCHEME.find(text)
        if (scheme != null) return text.substring(scheme.range.last + 1)
        return if (BARE_HOST.containsMatchIn(text)) text else null
    }

    private val SCHEME = Regex("^https?://", RegexOption.IGNORE_CASE)
    private val BARE_HOST = Regex("^(?:www\\.)?(?:${HOSTS.joinToString("|") { Regex.escape(it) }})(?:[/?#]|$)", RegexOption.IGNORE_CASE)

    // ---- what the browser's runtime does ------------------------------------------------------------------------------

    /** `new TextEncoder().encode(text)`: UTF-8, where a surrogate that has no partner is U+FFFD. */
    internal fun utf8(text: String): ByteArray {
        var wellFormed: StringBuilder? = null
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val paired = Character.isHighSurrogate(c) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1])
            if (paired) {
                wellFormed?.append(c)?.append(text[i + 1])
                i += 2
                continue
            }
            if (Character.isSurrogate(c)) {
                if (wellFormed == null) wellFormed = StringBuilder(text.length).append(text, 0, i)
                wellFormed.append('\uFFFD')
            } else {
                wellFormed?.append(c)
            }
            i++
        }
        return (wellFormed?.toString() ?: text).toByteArray(Charsets.UTF_8)
    }

    /**
     * `decodeURIComponent(text)`: every `%XX` becomes its byte, and the bytes of a character must be a valid UTF-8 sequence. Null where it throws
     * a `URIError`: a `%` without two hexadecimal digits after it, or bytes that are not UTF-8 (a lead byte alone, an overlong sequence, a surrogate,
     * a character beyond U+10FFFF).
     */
    internal fun decodeUriComponent(text: String): String? {
        if (text.indexOf('%') < 0) return text
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c != '%') {
                out.append(c)
                i++
                continue
            }
            val lead = escapedByte(text, i) ?: return null
            i += 3
            if (lead < 0x80) {
                out.append(lead.toChar())
                continue
            }
            val length = when {
                lead and 0xE0 == 0xC0 -> 2
                lead and 0xF0 == 0xE0 -> 3
                lead and 0xF8 == 0xF0 -> 4
                else -> return null
            }
            var codePoint = lead and (0xFF shr (length + 1))
            repeat(length - 1) {
                val next = escapedByte(text, i) ?: return null
                if (next and 0xC0 != 0x80) return null
                codePoint = (codePoint shl 6) or (next and 0x3F)
                i += 3
            }
            val shortest = when (length) { 2 -> 0x80; 3 -> 0x800; else -> 0x10000 }
            if (codePoint < shortest || codePoint > 0x10FFFF || codePoint in 0xD800..0xDFFF) return null
            out.appendCodePoint(codePoint)
        }
        return out.toString()
    }

    /** The byte of the escape `%XX` at [at], or null when there is no `%` there or the two characters after it are not hexadecimal digits. */
    private fun escapedByte(text: String, at: Int): Int? {
        if (at + 2 >= text.length || text[at] != '%') return null
        val high = hexDigit(text[at + 1])
        val low = hexDigit(text[at + 2])
        return if (high < 0 || low < 0) null else high * 16 + low
    }

    private fun hexDigit(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }

    /**
     * `atob(text)` in its forgiving form (the HTML standard): white space is ignored, up to two `=` at the end of a text whose length is a
     * multiple of four are dropped, and what is left must be of the alphabet `A-Za-z0-9+/` and not one character more than a multiple of four
     * long. The bits that do not make a whole byte are dropped. Null where `atob` throws an `InvalidCharacterError`.
     */
    internal fun atob(text: String): ByteArray? {
        val data = StringBuilder(text.length)
        for (c in text) if (c != ' ' && c != '\t' && c != '\n' && c != '\u000C' && c != '\r') data.append(c)
        var length = data.length
        if (length % 4 == 0) {
            var dropped = 0
            while (dropped < 2 && length > 0 && data[length - 1] == '=') {
                length--
                dropped++
            }
        }
        if (length % 4 == 1) return null
        val out = java.io.ByteArrayOutputStream(length * 3 / 4)
        var buffer = 0
        var bits = 0
        for (i in 0 until length) {
            val value = base64Value(data[i])
            if (value < 0) return null
            buffer = (buffer shl 6) or value
            bits += 6
            if (bits == 24) {
                out.write(buffer shr 16)
                out.write((buffer shr 8) and 0xFF)
                out.write(buffer and 0xFF)
                buffer = 0
                bits = 0
            }
        }
        when (bits) {
            12 -> out.write(buffer shr 4)
            18 -> {
                out.write(buffer shr 10)
                out.write((buffer shr 2) and 0xFF)
            }
        }
        return out.toByteArray()
    }

    private fun base64Value(c: Char): Int = when (c) {
        in 'A'..'Z' -> c - 'A'
        in 'a'..'z' -> c - 'a' + 26
        in '0'..'9' -> c - '0' + 52
        '+' -> 62
        '/' -> 63
        else -> -1
    }

    /**
     * `new TextDecoder().decode(bytes)`: the decoder of the Encoding standard, which writes one U+FFFD for every maximal part of a character
     * that is cut short or not UTF-8, and drops a byte order mark in front.
     */
    internal fun decodeUtf8(bytes: ByteArray): String {
        val out = StringBuilder(bytes.size)
        var codePoint = 0
        var needed = 0
        var seen = 0
        var lower = 0x80
        var upper = 0xBF
        var i = 0
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xFF
            if (needed == 0) {
                when (b) {
                    in 0x00..0x7F -> out.append(b.toChar())
                    in 0xC2..0xDF -> {
                        needed = 1
                        codePoint = b and 0x1F
                    }
                    in 0xE0..0xEF -> {
                        if (b == 0xE0) lower = 0xA0
                        if (b == 0xED) upper = 0x9F
                        needed = 2
                        codePoint = b and 0x0F
                    }
                    in 0xF0..0xF4 -> {
                        if (b == 0xF0) lower = 0x90
                        if (b == 0xF4) upper = 0x8F
                        needed = 3
                        codePoint = b and 0x07
                    }
                    else -> out.append('\uFFFD')
                }
                i++
                continue
            }
            if (b !in lower..upper) {
                // the character is cut short: one replacement for what came, and the byte starts over
                codePoint = 0
                needed = 0
                seen = 0
                lower = 0x80
                upper = 0xBF
                out.append('\uFFFD')
                continue
            }
            lower = 0x80
            upper = 0xBF
            seen++
            codePoint = (codePoint shl 6) or (b and 0x3F)
            i++
            if (seen == needed) {
                out.appendCodePoint(codePoint)
                codePoint = 0
                needed = 0
                seen = 0
            }
        }
        if (needed != 0) out.append('\uFFFD')
        return if (out.isNotEmpty() && out[0] == '\uFEFF') out.substring(1) else out.toString()
    }
}
