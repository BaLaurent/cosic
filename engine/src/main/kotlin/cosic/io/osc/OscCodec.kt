package cosic.io.osc

import cosic.pattern.CosicError
import cosic.pattern.JsFn
import cosic.pattern.Pattern
import cosic.pattern.Undefined
import cosic.pattern.jsonStringify
import java.io.ByteArrayOutputStream

/** Seconds from 1900 to 1970, which an OSC time tag counts from (`osc.SECS_70YRS`). */
private const val SECS_70YRS = 2208988800L

private const val TWO_32 = 4294967296.0

/**
 * OSC 1.0 encoding as `osc.js` 2.4.5 writes it for the bridge of `@strudel/osc` (`packages/osc/server.js`), which receives the message as
 * JSON over a WebSocket and sends it over UDP. A message is `address`, the type tags (`,` and one tag per argument) and the arguments,
 * every string padded with NULs to a multiple of four bytes:
 *
 * - a number is a 32 bit float (`f`), whatever it is: the JavaScript `3` is `3f`, not an `i`;
 * - a string is `s` (UTF-8; a lone surrogate becomes U+FFFD, as `Buffer.from` does), `true` and `false` are `T` and `F` with no data;
 * - a list is `[`, the tags of its elements, `]`, and its elements' data in place.
 *
 * What cannot be written ends the message with an error, and the bridge drops it: a map (`Can't infer OSC argument type for value`) and, because the JSON
 * hop turns `undefined`, `NaN`, `Infinity` and functions into `null`, anything that is one of those (`Cannot read properties of null (reading 'type')`).
 * [encodeBundle] is the message with the time tag `osc.timeTag(0, unixMs)`, which the server adds when the message has a timestamp.
 */
internal object OscCodec {
    /** The encoded message: [address] and [args]. Throws [CosicError] for an argument that cannot be written (see the description). */
    fun encodeMessage(address: String, args: List<Any?>): ByteArray {
        val out = ByteArrayOutputStream(64 + args.size * 8)
        writeMessage(out, address, args)
        return out.toByteArray()
    }

    /** The message wrapped in a bundle that is due at [unixMs] (milliseconds since 1970, fractions allowed). */
    fun encodeBundle(unixMs: Double, address: String, args: List<Any?>): ByteArray {
        val message = encodeMessage(address, args)
        val out = ByteArrayOutputStream(message.size + 20)
        writeString(out, "#bundle")
        writeTimeTag(out, unixMs)
        writeInt32(out, message.size)
        out.write(message)
        return out.toByteArray()
    }

    /** Throws what [encodeMessage] would throw for [args], without writing anything. */
    fun check(args: List<Any?>) {
        for (arg in args) checkArgument(arg)
    }

    private fun checkArgument(arg: Any?) {
        when (arg) {
            is List<*> -> for (element in arg) checkArgument(element)
            is Boolean, is String -> {}
            is Number -> if (!arg.toDouble().isFinite()) throw nullArgument()
            null, Undefined, is JsFn -> throw nullArgument()
            is Map<*, *> -> throw unknownType(arg)
            is Pattern -> throw unknownType(arg)
            else -> throw unknownType(arg)
        }
    }

    private fun nullArgument() = CosicError("Cannot read properties of null (reading 'type')")

    private fun unknownType(arg: Any) = CosicError("Can't infer OSC argument type for value: ${jsonStringify(arg) ?: arg}")

    private fun writeMessage(out: ByteArrayOutputStream, address: String, args: List<Any?>) {
        if (!address.startsWith("/")) throw CosicError("An OSC message must contain a valid address. Message was: $address")
        check(args)
        writeString(out, address)
        val tags = StringBuilder(",")
        val data = ByteArrayOutputStream(args.size * 8)
        for (arg in args) writeArgument(arg, tags, data)
        writeString(out, tags.toString())
        out.write(data.toByteArray())
    }

    private fun writeArgument(arg: Any?, tags: StringBuilder, data: ByteArrayOutputStream) {
        when (arg) {
            is List<*> -> {
                tags.append('[')
                for (element in arg) writeArgument(element, tags, data)
                tags.append(']')
            }
            is Boolean -> tags.append(if (arg) 'T' else 'F')
            is String -> {
                tags.append('s')
                writeString(data, arg)
            }
            is Number -> {
                tags.append('f')
                // JSON.stringify writes -0 as 0
                val value = arg.toDouble().let { if (it == 0.0) 0.0 else it }
                writeInt32(data, java.lang.Float.floatToRawIntBits(value.toFloat()))
            }
        }
    }

    private fun writeInt32(out: ByteArrayOutputStream, value: Int) {
        out.write(value ushr 24)
        out.write(value ushr 16)
        out.write(value ushr 8)
        out.write(value)
    }

    /** `osc.writeString`: the UTF-8 bytes, a NUL and padding NULs up to a multiple of four bytes. */
    private fun writeString(out: ByteArrayOutputStream, text: String) {
        val bytes = utf8(text)
        out.write(bytes)
        out.write(ByteArray(4 - bytes.size % 4))
    }

    /** `osc.timeTag(0, unixMs)` then `writeTimeTag`: the seconds and the fraction, each cut to 32 bits as `DataView.setInt32` does. */
    private fun writeTimeTag(out: ByteArrayOutputStream, unixMs: Double) {
        // `now = now || Date.now()`: a time of 0 is "now"
        val ms = if (unixMs == 0.0 || unixMs.isNaN()) System.currentTimeMillis().toDouble() else unixMs
        val secs = ms / 1000
        val whole = Math.floor(secs)
        val fraction = secs - whole
        val ntpSecs = whole + SECS_70YRS
        val ntpFracs = Math.floor(TWO_32 * fraction + 0.5) // Math.round
        writeInt32(out, toInt32(ntpSecs))
        writeInt32(out, toInt32(ntpFracs))
    }

    /** ECMAScript `ToInt32` of a finite integral double. */
    private fun toInt32(value: Double): Int {
        if (!value.isFinite()) return 0
        return java.math.BigDecimal(value).toBigInteger().toInt()
    }

    /** UTF-8 with a lone surrogate as U+FFFD (EF BF BD), which is what a Node `Buffer` makes of one. */
    private fun utf8(text: String): ByteArray {
        val out = ByteArrayOutputStream(text.length + 8)
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val code: Int = when {
                Character.isHighSurrogate(c) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1]) ->
                    Character.toCodePoint(c, text[++i])
                Character.isSurrogate(c) -> 0xFFFD
                else -> c.code
            }
            when {
                code < 0x80 -> out.write(code)
                code < 0x800 -> {
                    out.write(0xC0 or (code shr 6))
                    out.write(0x80 or (code and 0x3F))
                }
                code < 0x10000 -> {
                    out.write(0xE0 or (code shr 12))
                    out.write(0x80 or ((code shr 6) and 0x3F))
                    out.write(0x80 or (code and 0x3F))
                }
                else -> {
                    out.write(0xF0 or (code shr 18))
                    out.write(0x80 or ((code shr 12) and 0x3F))
                    out.write(0x80 or ((code shr 6) and 0x3F))
                    out.write(0x80 or (code and 0x3F))
                }
            }
            i++
        }
        return out.toByteArray()
    }
}
