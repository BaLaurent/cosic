package cosic.io.serial

import cosic.pattern.CosicError
import cosic.pattern.Undefined
import cosic.pattern.jsToString

/*
 * What `.serial()` writes to the port for a hap (`@strudel/serial`, packages/serial/serial.mjs:70-112), as bytes.
 */

/** The `.serial(br, sendcrc, singlecharids, name)` arguments: the port [name], its [baudRate] and how messages are written. */
data class SerialRoute(
    val baudRate: Int = DEFAULT_BAUD_RATE,
    val sendCrc: Boolean = false,
    val singleCharIds: Boolean = false,
    val name: String = DEFAULT_PORT_NAME,
) {
    companion object {
        const val DEFAULT_BAUD_RATE = 115200
        const val DEFAULT_PORT_NAME = "default"
    }
}

/** `crc16` of serial.mjs:49-64 (CRC-16/CCITT-FALSE over the UTF-16 code units of [s]); 0 for the empty string. */
internal fun crc16CcittFalse(s: String): Int {
    if (s.isEmpty()) return 0
    var crc = 0xffff
    for (c in s) {
        crc = crc xor (c.code shl 8)
        repeat(8) { crc = if ((crc and 0x8000) != 0) (crc shl 1) xor 0x1021 else crc shl 1 }
    }
    return crc and 0xffff
}

/** `TextEncoder.encode`: UTF-8, a lone surrogate becomes U+FFFD (a Kotlin `toByteArray` would write `?`). */
internal fun encodeUtf8(text: String): ByteArray {
    var clean: StringBuilder? = null
    for (i in text.indices) {
        val c = text[i]
        val lone = (c.isHighSurrogate() && (i + 1 >= text.length || !text[i + 1].isLowSurrogate())) ||
            (c.isLowSurrogate() && (i == 0 || !text[i - 1].isHighSurrogate()))
        if (lone && clean == null) clean = StringBuilder(text.length).append(text, 0, i)
        clean?.append(if (lone) '\uFFFD' else c)
    }
    return (clean?.toString() ?: text).toByteArray(Charsets.UTF_8)
}

private fun charAt0(v: Any?, what: String): String {
    if (v !is String) throw CosicError("$what.charAt is not a function")
    return if (v.isEmpty()) "" else v.substring(0, 1)
}

/**
 * The bytes the hap value [value] is sent as. A control map with an `action` is written `action(key:value,key:value)` (the first
 * character of the action and of each key with `singleCharIds`), followed by `|`, the high and low byte of the CRC and `;` when
 * `sendCrc` is on and the CRC is not zero; a map without an `action` is every `key:value` joined without a separator; a list is its
 * `index:value` entries the same way; anything else is `String(value)`. The routing keys of the engine (`_cosic_...`) are not
 * part of the value a program sees and are left out. `undefined` gives no bytes. `null` is a `TypeError`, as in JavaScript (`'action' in null`).
 */
internal fun serialBytes(value: Any?, route: SerialRoute): ByteArray {
    var chk = 0
    val message: String
    when {
        value == null -> throw CosicError("Cannot use 'in' operator to search for 'action' in null")
        value === Undefined -> message = ""
        value is Map<*, *> -> {
            val entries = value.entries.filter { !(it.key as String).startsWith("_cosic_") }
            if (entries.any { it.key == "action" }) {
                var action = value["action"]
                if (route.singleCharIds) action = charAt0(action, "action")
                val sb = StringBuilder(jsToString(action)).append('(')
                var first = true
                for ((k, v) in entries) {
                    if (k == "action") continue
                    if (!first) sb.append(',')
                    first = false
                    sb.append(if (route.singleCharIds) charAt0(k, "key") else k).append(':').append(jsToString(v))
                }
                message = sb.append(')').toString()
                if (route.sendCrc) chk = crc16CcittFalse(message)
            } else {
                val sb = StringBuilder()
                for ((k, v) in entries) sb.append(k).append(':').append(jsToString(v))
                message = sb.toString()
            }
        }
        value is List<*> -> {
            val sb = StringBuilder()
            value.forEachIndexed { i, v -> sb.append(i).append(':').append(jsToString(v)) }
            message = sb.toString()
        }
        else -> message = jsToString(value)
    }
    val encoded = encodeUtf8(message)
    if (chk == 0) return encoded
    val out = encoded.copyOf(encoded.size + 4)
    out[encoded.size] = 0x7C
    out[encoded.size + 1] = (chk shr 8 and 0xff).toByte()
    out[encoded.size + 2] = (chk and 0xff).toByte()
    out[encoded.size + 3] = 0x3B
    return out
}
