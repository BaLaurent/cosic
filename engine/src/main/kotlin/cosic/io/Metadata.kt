package cosic.io

import cosic.pattern.jsKeyOrder
import cosic.script.isJsWhitespace
import cosic.script.isNewLine

/**
 * What the comments of a program say about it: `// @title Awesome song`, `/* @by Sam */`, or a title in quotes as the first comment
 * (`// "Awesome song"`). A port of `getMetadata` (website/src/metadata_parser.js, which test/metadata.test.mjs runs), the way the REPL titles
 * its page; `engine/src/test/resources/share/oracle.json` holds what it makes of 120 programs, which this must make of them too.
 *
 * Give [parse] to [cosic.CosicEngine.metadataParser] and every program that is evaluated reports its metadata in [cosic.ProgramInfo.metadata].
 *
 * The comments are found as the regular expression of the original finds them, which knows nothing of strings: `"http://x"` starts a comment.
 * The tags of [ALLOW_MANY] collect lists (split at commas and line breaks, empty items dropped, a tag written again adds to its list); any
 * other tag is one text, spaces collapsed, which a tag written again continues after a space. A tag of the REPL's `version` is kept like the rest.
 * Tags that are keys of JavaScript's `Object.prototype` (`constructor`, `toString`, ...) are ordinary tags here; in the original they inherit the
 * function and make a text of it.
 */
object Metadata {
    /** The tags that make lists (`ALLOW_MANY` of the original). */
    val ALLOW_MANY: Set<String> = setOf("by", "url", "genre", "license", "tag")

    /**
     * The tags of [code], in the order JavaScript lists the keys of an object: a [String] for most, a list of strings for those of
     * [ALLOW_MANY]. A program of nothing (null) has none.
     */
    fun parse(code: String?): Map<String, Any> {
        val comments = commentsOf(code ?: "")
        val tags = LinkedHashMap<String, Any>()

        // `[prefix, title] = (comments[0] || '').split('"')`: a title in quotes, when nothing comes before the first quote
        val first = comments.firstOrNull() ?: ""
        val quote = first.indexOf('"')
        if (quote >= 0 && first.substring(0, quote).jsTrim().isEmpty()) tags["title"] = first.substring(quote + 1).substringBefore('"')

        for (comment in comments) {
            for (part in comment.split('@').drop(1)) {
                // `[tag, value] = part.split(/ (.*)/s)`: the tag ends at the first space (and only a space)
                val space = part.indexOf(' ')
                val tag = (if (space < 0) part else part.substring(0, space)).jsTrim()
                var value = if (space < 0) "" else part.substring(space + 1)
                value = collapse(value) { it == ' ' }.jsTrim()
                val before = tags[tag]
                if (tag in ALLOW_MANY) {
                    val list = value.split(',', '\n').map { it.jsTrim() }.filter { it.isNotEmpty() }
                    @Suppress("UNCHECKED_CAST")
                    tags[tag] = if (before != null) (before as List<String>) + list else list
                } else {
                    value = collapse(value, ::isJsWhitespace)
                    tags[tag] = if (before != null) "$before $value" else value
                }
            }
        }
        return jsKeyOrder(tags.keys).associateWithTo(LinkedHashMap()) { tags.getValue(it) }
    }

    /**
     * The text of every comment in order, trimmed: `code.matchAll(/\/\*([\s\S]*?)\*\/|\/\/(.*)$/gm)`. A block comment runs to the first
     * `*` and `/` after its opening; one that is never closed is no comment. A line comment runs to the end of its line, and the line ends
     * where JavaScript's `.` stops: at a line feed, a carriage return, U+2028 or U+2029.
     */
    private fun commentsOf(code: String): List<String> {
        val comments = ArrayList<String>()
        var i = 0
        while (i < code.length - 1) {
            if (code[i] == '/') {
                if (code[i + 1] == '*') {
                    val end = code.indexOf("*/", i + 2)
                    if (end >= 0) {
                        comments.add(code.substring(i + 2, end).jsTrim())
                        i = end + 2
                        continue
                    }
                } else if (code[i + 1] == '/') {
                    var end = i + 2
                    while (end < code.length && !isNewLine(code[end])) end++
                    comments.add(code.substring(i + 2, end).jsTrim())
                    i = end
                    continue
                }
            }
            i++
        }
        return comments
    }

    /** [text] with every run of the characters [isBlank] accepts made one space (`replaceAll(/ +/g, ' ')`, `replaceAll(/\s+/g, ' ')`). */
    private inline fun collapse(text: String, isBlank: (Char) -> Boolean): String {
        val out = StringBuilder(text.length)
        var inRun = false
        for (c in text) {
            if (isBlank(c)) {
                if (!inRun) out.append(' ')
                inRun = true
            } else {
                out.append(c)
                inRun = false
            }
        }
        return out.toString()
    }

    /** `String.prototype.trim` of JavaScript: white space and line terminators at both ends. */
    private fun String.jsTrim(): String = trim { isJsWhitespace(it) }
}
