package com.octopusman.cosic.audio

import java.io.IOException
import java.net.IDN
import java.net.URL
import java.security.MessageDigest

private const val HEX = "0123456789ABCDEF"

/** The 40 hex digits of the SHA-1 of [text]: the name of the file a URL is cached in. */
internal fun sha1Hex(text: String): String {
    val digest = MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8))
    val out = StringBuilder(digest.size * 2)
    for (b in digest) {
        out.append("0123456789abcdef"[(b.toInt() shr 4) and 15]).append("0123456789abcdef"[b.toInt() and 15])
    }
    return out.toString()
}

/** Characters the WHATWG URL parser percent-encodes in a path: controls, space, `"`, `<`, `>`, `` ` ``, `{`, `}` and everything that is not ASCII. */
private fun escapedInPath(c: Int): Boolean =
    c <= 0x20 || c >= 0x7F || c == '"'.code || c == '<'.code || c == '>'.code || c == '`'.code || c == '{'.code || c == '}'.code

/** Characters it percent-encodes in the query of an http(s) URL: the same without `` ` ``, `{` and `}`, with `'`. */
private fun escapedInQuery(c: Int): Boolean =
    c <= 0x20 || c >= 0x7F || c == '"'.code || c == '<'.code || c == '>'.code || c == '\''.code

private fun StringBuilder.appendEscaped(text: String, escaped: (Int) -> Boolean) {
    var i = 0
    while (i < text.length) {
        val codePoint = text.codePointAt(i)
        i += Character.charCount(codePoint)
        if (!escaped(codePoint)) {
            append(codePoint.toChar())
            continue
        }
        // A lone surrogate is encoded as U+FFFD, as the URL parser's UTF-8 encoder does.
        val bytes = String(Character.toChars(if (codePoint in 0xD800..0xDFFF) 0xFFFD else codePoint)).toByteArray(Charsets.UTF_8)
        for (b in bytes) append('%').append(HEX[(b.toInt() shr 4) and 15]).append(HEX[b.toInt() and 15])
    }
}

/** `.` and `..` as the URL parser reads them, `%2e` included. */
private fun dotKind(segment: String): Int = when (segment.lowercase()) {
    ".", "%2e" -> 1
    "..", ".%2e", "%2e.", "%2e%2e" -> 2
    else -> 0
}

/** The path with its `.` and `..` segments resolved, as `new URL()` does. */
private fun removeDotSegments(path: String): String {
    if (path.isEmpty()) return path
    val segments = path.split('/').drop(1) // the path starts with a slash
    val out = ArrayList<String>(segments.size)
    for ((i, segment) in segments.withIndex()) {
        val last = i == segments.lastIndex
        when (dotKind(segment)) {
            1 -> if (last) out.add("")
            2 -> {
                if (out.isNotEmpty()) out.removeAt(out.lastIndex)
                if (last) out.add("")
            }
            else -> out.add(segment)
        }
    }
    return "/" + out.joinToString("/")
}

/**
 * The URL the network is asked for: what `new URL(url.replace('#', '%23'))` gives in a browser, which is what the sample maps of
 * Strudel are written for (their paths hold raw spaces, `'`, `(`, `)` and `%XX` escapes alike).
 *
 * - Only the first `#` is data (`%23`); a later one starts a fragment, which is never sent.
 * - The path gets the percent-encoding of the URL parser, which encodes space, `"`, `<`, `>`, `` ` ``, `{`, `}`, controls and non-ASCII
 *   characters (as UTF-8) and leaves `%` and the rest (`'`, `(`, `)`, `&`, `+`, `,`, `;`, `=`, `@`, `[`, `]`, `$`, `!`, `*`, `~`, `:`) as
 *   they are, so a path that is encoded already stays what it was. `\` is a `/`, and dot segments are resolved.
 * - What follows the first `?` is the query: it gets the encoding of the query (the path's without `` ` ``, `{`, `}`, with `'`, which is what a
 *   speech URL of shabda needs).
 *
 * Throws [IOException] for anything that is not an absolute `http:` or `https:` URL.
 */
internal fun normalizeUrl(raw: String): URL {
    var text = raw.trim { it <= ' ' }.filterNot { it == '\t' || it == '\n' || it == '\r' }
    val firstHash = text.indexOf('#')
    if (firstHash >= 0) text = text.substring(0, firstHash) + "%23" + text.substring(firstHash + 1)
    val fragment = text.indexOf('#')
    if (fragment >= 0) text = text.substring(0, fragment)

    val schemeEnd = text.indexOf("://")
    if (schemeEnd <= 0) throw IOException("not an absolute URL: $raw")
    val scheme = text.substring(0, schemeEnd).lowercase()
    if (scheme != "http" && scheme != "https") throw IOException("unsupported URL scheme \"$scheme\": $raw")
    val authorityStart = schemeEnd + 3
    var authorityEnd = text.length
    for (i in authorityStart until text.length) {
        if (text[i] == '/' || text[i] == '?' || text[i] == '\\') {
            authorityEnd = i
            break
        }
    }
    val authority = text.substring(authorityStart, authorityEnd)
    if (authority.isEmpty()) throw IOException("URL without a host: $raw")
    val queryStart = text.indexOf('?', authorityEnd).let { if (it < 0) text.length else it }
    val path = removeDotSegments(text.substring(authorityEnd, queryStart).replace('\\', '/'))
    val query = if (queryStart < text.length) text.substring(queryStart + 1) else null

    val out = StringBuilder(text.length + 16)
    out.append(scheme).append("://").append(asciiAuthority(authority))
    out.appendEscaped(path, ::escapedInPath)
    if (query != null) {
        out.append('?')
        out.appendEscaped(query, ::escapedInQuery)
    }
    return try {
        URL(out.toString())
    } catch (e: java.net.MalformedURLException) {
        throw IOException("bad URL: $raw", e)
    }
}

/** The host part of the authority in ASCII: a host with other characters becomes its punycode form. */
private fun asciiAuthority(authority: String): String {
    if (authority.all { it.code < 0x80 }) return authority
    val at = authority.lastIndexOf('@')
    val userInfo = if (at >= 0) authority.substring(0, at + 1) else ""
    val hostPort = authority.substring(at + 1)
    val colon = hostPort.lastIndexOf(':').takeIf { it > 0 && !hostPort.endsWith("]") }
    val host = if (colon != null) hostPort.substring(0, colon) else hostPort
    val port = if (colon != null) hostPort.substring(colon) else ""
    return userInfo + try {
        IDN.toASCII(host)
    } catch (_: IllegalArgumentException) {
        host
    } + port
}

/** The URL a `Location` header sends the client to from [base]: absolute, or relative to it. */
internal fun resolveRedirect(base: URL, location: String): URL {
    val trimmed = location.trim()
    if (Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(trimmed)) return normalizeUrl(trimmed)
    // A relative reference is resolved by the URL class (it does not mind raw spaces), then encoded like any URL.
    val resolved = try {
        URL(base, trimmed.replace(" ", "%20"))
    } catch (e: java.net.MalformedURLException) {
        throw IOException("bad redirect to $location", e)
    }
    return normalizeUrl(resolved.toString())
}

/**
 * Whether a plain `http:` request to [host] is allowed: the machine itself (`localhost`, `127.0.0.1`, the emulator's `10.0.2.2`), private
 * networks (10/8, 172.16/12, 192.168/16), link-local addresses (169.254/16) and `*.local` names. This is the sample server of a developer
 * (`local:`) or one on the LAN; everything else must be `https:`.
 */
internal fun isCleartextAllowed(host: String): Boolean {
    val name = host.lowercase().trimEnd('.')
    if (name == "localhost" || name == "[::1]" || name.endsWith(".local")) return true
    val parts = name.split('.')
    if (parts.size != 4) return false
    val octets = parts.map { part -> part.takeIf { it.isNotEmpty() && it.length <= 3 && it.all { c -> c in '0'..'9' } }?.toInt() ?: return false }
    if (octets.any { it > 255 }) return false
    val (a, b) = octets
    return a == 127 || a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254)
}

/** Throws [IOException] unless a request to [url] may be made: `https:` always, `http:` only to the hosts [isCleartextAllowed] names. */
internal fun requireAllowed(url: URL) {
    when (url.protocol) {
        "https" -> Unit
        "http" -> if (!isCleartextAllowed(url.host)) throw IOException("cleartext http is only allowed for local hosts: $url")
        else -> throw IOException("unsupported URL scheme \"${url.protocol}\": $url")
    }
}
