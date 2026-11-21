package com.octopusman.cosic.reference

/** A piece of the text of a card with one style: bold or italic, a snippet of [code], or a link to [href]. */
internal data class DocSpan(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val href: String? = null,
)

/** A block of the description of a card. */
internal sealed interface DocBlock {
    /** A paragraph; [quote] when it is in a quotation. */
    data class Paragraph(val spans: List<DocSpan>, val quote: Boolean = false) : DocBlock

    /** An item of a list, which starts with its [marker] (a bullet, or the number of an item of a numbered list). */
    data class Item(val marker: String, val spans: List<DocSpan>) : DocBlock

    /** Code as it is written, line breaks and indentation kept. */
    data class Code(val text: String) : DocBlock
}

/**
 * The HTML the JSDoc of Strudel makes of its descriptions, as the blocks a card shows. The HTML is what JSDoc's markdown plugin writes: paragraphs
 * (`p`), lists (`ul`, `ol`, `li`), quotations (`blockquote`), code in a line (`code`) and in a block (`pre`), emphasis (`em`, `strong`) and links (`a`), with the entities
 * `&quot; &lt; &gt; &amp;`. Any other tag is left out and its text kept; white space is collapsed as in a browser.
 */
internal object DocHtml {
    fun parse(html: String): List<DocBlock> = Parser(html).run()

    /** [html] as the text it shows (`stripHtml` of Strudel's autocomplete, with the white space collapsed). */
    fun plainText(html: String): String = parse(html).joinToString(" ") { block ->
        when (block) {
            is DocBlock.Paragraph -> block.spans.joinToString("") { it.text }
            is DocBlock.Item -> block.spans.joinToString("") { it.text }
            is DocBlock.Code -> block.text
        }
    }.trim()

    private class ListState(val ordered: Boolean) {
        var count = 0
    }

    private class Parser(private val html: String) {
        private val blocks = ArrayList<DocBlock>()
        private var spans = ArrayList<DocSpan>()
        private val lists = ArrayList<ListState>()
        private var marker: String? = null
        private var quote = 0
        private var bold = 0
        private var italic = 0
        private var code = 0
        private val links = ArrayList<String?>()
        private var pre = false
        private val preText = StringBuilder()

        fun run(): List<DocBlock> {
            var i = 0
            while (i < html.length) {
                if (html[i] == '<') {
                    val end = html.indexOf('>', i)
                    if (end > i) {
                        tag(html.substring(i + 1, end))
                        i = end + 1
                        continue
                    }
                }
                val next = html.indexOf('<', i + 1).let { if (it < 0) html.length else it }
                text(decode(html.substring(i, next)))
                i = next
            }
            flush()
            return blocks
        }

        private fun text(raw: String) {
            if (pre) {
                preText.append(raw)
                return
            }
            // white space is one space, and none at the start of a block
            val out = StringBuilder(raw.length)
            var space = spans.isEmpty() || spans.last().text.endsWith(" ") || spans.last().text.endsWith("\n")
            for (c in raw) {
                if (c == ' ' || c == '\n' || c == '\t' || c == '\r' || c == '\u000C') {
                    if (!space) out.append(' ')
                    space = true
                } else {
                    out.append(c)
                    space = false
                }
            }
            if (out.isNotEmpty()) add(out.toString())
        }

        /** [text] in the style at this point of the HTML: the span before it when that has the same style, else a span of its own. */
        private fun add(text: String) {
            val style = DocSpan(text, bold > 0, italic > 0, code > 0, links.lastOrNull())
            val last = spans.lastOrNull()
            if (last != null && last.copy(text = text) == style) spans[spans.lastIndex] = last.copy(text = last.text + text) else spans.add(style)
        }

        private fun tag(inner: String) {
            val closing = inner.startsWith("/")
            val body = inner.removePrefix("/").trim().removeSuffix("/").trim()
            val name = body.takeWhile { !it.isWhitespace() }.lowercase()
            when (name) {
                "p", "div" -> flush()
                "ul", "ol" -> {
                    flush()
                    if (closing) {
                        if (lists.isNotEmpty()) lists.removeAt(lists.lastIndex)
                    } else {
                        lists.add(ListState(name == "ol"))
                    }
                }
                "li" -> {
                    flush()
                    if (closing) {
                        marker = null
                    } else {
                        val list = lists.lastOrNull()
                        marker = if (list != null && list.ordered) "${++list.count}." else "\u2022"
                    }
                }
                "blockquote" -> {
                    flush()
                    quote = maxOf(0, quote + if (closing) -1 else 1)
                }
                "pre" -> {
                    if (closing) {
                        if (pre) blocks.add(DocBlock.Code(preText.toString().trim('\n')))
                        preText.setLength(0)
                        pre = false
                    } else {
                        flush()
                        pre = true
                    }
                }
                "br" -> if (!pre) add("\n")
                "strong", "b" -> bold = maxOf(0, bold + if (closing) -1 else 1)
                "em", "i" -> italic = maxOf(0, italic + if (closing) -1 else 1)
                "code" -> code = maxOf(0, code + if (closing) -1 else 1)
                "a" -> if (closing) {
                    if (links.isNotEmpty()) links.removeAt(links.lastIndex)
                } else {
                    links.add(attribute(body, "href"))
                }
            }
        }

        /** The block that has been collected, when there is text in it. */
        private fun flush() {
            while (spans.isNotEmpty() && spans.last().text.isBlank()) spans.removeAt(spans.lastIndex)
            if (spans.isNotEmpty()) {
                val last = spans.last()
                spans[spans.lastIndex] = last.copy(text = last.text.trimEnd())
                val item = marker
                blocks.add(if (item != null) DocBlock.Item(item, spans) else DocBlock.Paragraph(spans, quote > 0))
            }
            spans = ArrayList()
        }

        private fun attribute(tag: String, name: String): String? {
            val match = Regex("""\b$name\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""", RegexOption.IGNORE_CASE).find(tag) ?: return null
            return decode(match.groupValues[1].ifEmpty { match.groupValues[2].ifEmpty { match.groupValues[3] } })
        }
    }

    private val ENTITY = Regex("&(?:#(\\d+)|#[xX]([0-9a-fA-F]+)|([a-zA-Z]+));")

    private val NAMED = mapOf("quot" to "\"", "amp" to "&", "lt" to "<", "gt" to ">", "apos" to "'", "nbsp" to "\u00a0")

    /** [text] with the entities written as the characters they stand for; one that is unknown stays as it is. */
    internal fun decode(text: String): String {
        if ('&' !in text) return text
        return ENTITY.replace(text) { m ->
            val decimal = m.groupValues[1]
            val hex = m.groupValues[2]
            val named = m.groupValues[3]
            when {
                decimal.isNotEmpty() -> decimal.toIntOrNull()?.takeIf { it in 1..0x10FFFF }?.let { String(Character.toChars(it)) } ?: m.value
                hex.isNotEmpty() -> hex.toIntOrNull(16)?.takeIf { it in 1..0x10FFFF }?.let { String(Character.toChars(it)) } ?: m.value
                else -> NAMED[named] ?: m.value
            }
        }
    }
}
