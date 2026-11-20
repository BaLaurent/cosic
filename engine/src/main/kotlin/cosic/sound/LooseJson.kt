package cosic.sound

import cosic.pattern.jsOrdered

/**
 * The one reader for everything the sound library parses: the JSON of sample maps and alias maps, and the JavaScript object
 * literals of soundfont files (`var _tone_0000_JCLive_sf2_file={zones:[{midi:0 ,originalPitch:2400 ,file:'SUQz...' //..`). It reads
 * their common subset:
 * - values: objects, arrays, strings in `"` or `'` with the escapes `\n \t \r \b \f \v \0 \\ \' \" \/ \xXX \uXXXX`, numbers (`-1`, `.5`,
 *   `1e3`), `true`, `false`, `null` and `undefined` (which reads as `null`);
 * - object keys that are quoted, bare identifiers or numbers;
 * - `//` line comments and slash-star block comments anywhere between tokens;
 * - commas that trail or lead (comma-first style) or are doubled: they separate nothing, and an array never has holes.
 *
 * Objects come back as [LinkedHashMap] in JavaScript's key order (whole-number keys first, ascending), arrays as [ArrayList], numbers
 * as [Double]. A text of several megabytes (a soundfont holds its samples inline) is scanned once and every string is copied once.
 * Errors are [IllegalArgumentException]s whose message starts with `line:column`.
 */
internal object LooseJson {
    /** Nesting deeper than this is an error: the text may come from anywhere on the network. */
    private const val MAX_DEPTH = 256

    /**
     * Parses the one value [text] holds. With [allowTrailing] whatever follows the value (the `;` and the code after a soundfont
     * object) is ignored; otherwise only blanks and comments may follow it.
     */
    fun parse(text: String, allowTrailing: Boolean = false): Any? {
        val scanner = Scanner(text)
        scanner.skipBlank()
        val value = scanner.value(0)
        if (!allowTrailing) {
            scanner.skipBlank()
            if (!scanner.atEnd()) scanner.fail("unexpected '${scanner.peek()}' after the value")
        }
        return value
    }

    private class Scanner(private val s: String) {
        private var i = 0
        private val n = s.length

        fun atEnd(): Boolean = i >= n

        fun peek(): Char = s[i]

        fun fail(message: String): Nothing {
            var line = 1
            var column = 1
            for (k in 0 until minOf(i, n)) {
                if (s[k] == '\n') {
                    line++
                    column = 1
                } else {
                    column++
                }
            }
            throw IllegalArgumentException("$line:$column $message")
        }

        /** Skips white space and comments. */
        fun skipBlank() {
            while (i < n) {
                val c = s[i]
                if (c.isWhitespace() || c == '\uFEFF') {
                    i++
                } else if (c == '/' && i + 1 < n && s[i + 1] == '/') {
                    val end = s.indexOf('\n', i)
                    i = if (end < 0) n else end + 1
                } else if (c == '/' && i + 1 < n && s[i + 1] == '*') {
                    val end = s.indexOf("*/", i + 2)
                    if (end < 0) fail("unterminated comment")
                    i = end + 2
                } else {
                    return
                }
            }
        }

        /** Skips blanks and the commas that separate items. */
        private fun skipSeparators() {
            while (true) {
                skipBlank()
                if (i < n && s[i] == ',') i++ else return
            }
        }

        fun value(depth: Int): Any? {
            if (atEnd()) fail("unexpected end of input")
            if (depth > MAX_DEPTH) fail("nested deeper than $MAX_DEPTH levels")
            val c = s[i]
            return when {
                c == '{' -> obj(depth)
                c == '[' -> array(depth)
                c == '"' || c == '\'' -> string(c)
                c == '-' || c == '+' || c == '.' || c in '0'..'9' -> number()
                isIdentifierStart(c) -> literal()
                else -> fail("unexpected '$c'")
            }
        }

        private fun obj(depth: Int): Any {
            i++ // {
            val map = LinkedHashMap<String, Any?>()
            while (true) {
                skipSeparators()
                if (atEnd()) fail("unterminated object")
                if (s[i] == '}') {
                    i++
                    return jsOrdered(map)
                }
                val key = key()
                skipBlank()
                if (atEnd() || s[i] != ':') fail("expected ':' after the key \"$key\"")
                i++
                skipBlank()
                map[key] = value(depth + 1)
                skipBlank()
                if (!atEnd() && s[i] != ',' && s[i] != '}') fail("expected ',' or '}' after the value of \"$key\"")
            }
        }

        private fun array(depth: Int): Any {
            i++ // [
            val list = ArrayList<Any?>()
            while (true) {
                skipSeparators()
                if (atEnd()) fail("unterminated array")
                if (s[i] == ']') {
                    i++
                    return list
                }
                list.add(value(depth + 1))
                skipBlank()
                if (!atEnd() && s[i] != ',' && s[i] != ']') fail("expected ',' or ']' after an array item")
            }
        }

        private fun key(): String {
            val c = s[i]
            return when {
                c == '"' || c == '\'' -> string(c)
                isIdentifierStart(c) -> {
                    val start = i
                    while (i < n && isIdentifierPart(s[i])) i++
                    s.substring(start, i)
                }
                c in '0'..'9' || c == '.' -> {
                    val start = i
                    while (i < n && (s[i] in '0'..'9' || s[i] == '.')) i++
                    s.substring(start, i)
                }
                else -> fail("expected an object key but found '$c'")
            }
        }

        private fun isIdentifierStart(c: Char) = c.isLetter() || c == '_' || c == '$'

        private fun isIdentifierPart(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$'

        private fun literal(): Any? {
            val start = i
            while (i < n && isIdentifierPart(s[i])) i++
            return when (val word = s.substring(start, i)) {
                "true" -> true
                "false" -> false
                "null", "undefined" -> null
                else -> {
                    i = start
                    fail("unexpected '$word'")
                }
            }
        }

        private fun number(): Double {
            val start = i
            if (s[i] == '-' || s[i] == '+') i++
            var digits = 0
            while (i < n && s[i] in '0'..'9') {
                i++
                digits++
            }
            if (i < n && s[i] == '.') {
                i++
                while (i < n && s[i] in '0'..'9') {
                    i++
                    digits++
                }
            }
            if (digits == 0) {
                i = start
                fail("expected a number")
            }
            if (i < n && (s[i] == 'e' || s[i] == 'E')) {
                val mark = i
                i++
                if (i < n && (s[i] == '+' || s[i] == '-')) i++
                if (i < n && s[i] in '0'..'9') {
                    while (i < n && s[i] in '0'..'9') i++
                } else {
                    i = mark
                }
            }
            return s.substring(start, i).toDouble()
        }

        private fun string(quote: Char): String {
            i++ // the opening quote
            val start = i
            var j = i
            while (j < n) {
                val c = s[j]
                if (c == quote) {
                    i = j + 1
                    return s.substring(start, j)
                }
                if (c == '\\') break
                if (c == '\n') {
                    i = j
                    fail("unterminated string")
                }
                j++
            }
            if (j >= n) {
                i = n
                fail("unterminated string")
            }
            // an escape: copy what was read, then go on character by character
            val out = StringBuilder(j - start + 16)
            out.append(s, start, j)
            i = j
            while (true) {
                if (i >= n) fail("unterminated string")
                val c = s[i]
                when {
                    c == quote -> {
                        i++
                        return out.toString()
                    }
                    c == '\n' -> fail("unterminated string")
                    c == '\\' -> escape(out)
                    else -> {
                        // copy the run up to the next special character in one go
                        var k = i + 1
                        while (k < n && s[k] != quote && s[k] != '\\' && s[k] != '\n') k++
                        out.append(s, i, k)
                        i = k
                    }
                }
            }
        }

        private fun escape(out: StringBuilder) {
            i++ // the backslash
            if (i >= n) fail("unterminated string")
            val c = s[i++]
            when (c) {
                'n' -> out.append('\n')
                't' -> out.append('\t')
                'r' -> out.append('\r')
                'b' -> out.append('\b')
                'f' -> out.append('\u000C')
                'v' -> out.append('\u000B')
                '0' -> out.append('\u0000')
                'x' -> out.append(hex(2).toChar())
                'u' -> out.append(hex(4).toChar())
                '\n' -> Unit // a line continuation
                '\r' -> if (i < n && s[i] == '\n') i++
                else -> out.append(c) // \\ \' \" \/ and any other escaped character stand for themselves
            }
        }

        private fun hex(digits: Int): Int {
            if (i + digits > n) fail("bad escape")
            var value = 0
            for (k in 0 until digits) {
                val d = Character.digit(s[i + k], 16)
                if (d < 0) fail("bad escape: '${s[i + k]}' is not a hex digit")
                value = value * 16 + d
            }
            i += digits
            return value
        }
    }
}
