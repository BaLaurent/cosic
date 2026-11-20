package com.octopusman.cosic.console

/** A stretch of a console line: plain text, or a link to [href]. */
internal data class LinePart(val text: String, val href: String? = null)

/**
 * What the console of the web REPL makes of the text of a line (`linkify` of website/src/repl/components/panel/ConsoleTab.jsx): an address that starts with
 * `http://`, `https://` or `ftp://` is a link, so is one that starts with `www.` (without a `/` in front of it, which would make it part of an address
 * already found) and an e-mail address (a link to `mailto:`). The web REPL rewrites the text as HTML; here the three patterns find the stretches, in that
 * order, and what an earlier one found is not looked at by a later one, so that the address of `http://user@host.com` is not cut in two.
 */
internal object Linkify {
    private val ADDRESS = Regex("""(\b(https?|ftp)://[-A-Z0-9+&@#/%?=~_|!:,.;]*[-A-Z0-9+&@#/%=~_|])""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
    private val WWW = Regex("""(^|[^/])(www\.[\S]+(\b|$))""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))
    private val EMAIL = Regex("""(([a-zA-Z0-9\-_.])+@[a-zA-Z_]+?(\.[a-zA-Z]{2,6})+)""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE))

    /** [text] in order as plain stretches and links. */
    fun parts(text: String): List<LinePart> {
        val found = ArrayList<Triple<Int, Int, String>>() // from, to, href
        fun free(from: Int, to: Int) = found.none { from < it.second && to > it.first }
        for (m in ADDRESS.findAll(text)) found.add(Triple(m.range.first, m.range.last + 1, m.value))
        for (m in WWW.findAll(text)) {
            val link = m.groups[2]!!
            val from = link.range.first
            val to = link.range.last + 1
            if (free(from, to)) found.add(Triple(from, to, "http://${link.value}"))
        }
        for (m in EMAIL.findAll(text)) {
            val from = m.range.first
            val to = m.range.last + 1
            if (free(from, to)) found.add(Triple(from, to, "mailto:${m.value}"))
        }
        found.sortBy { it.first }
        val parts = ArrayList<LinePart>()
        var at = 0
        for ((from, to, href) in found) {
            if (from > at) parts.add(LinePart(text.substring(at, from)))
            parts.add(LinePart(text.substring(from, to), href))
            at = to
        }
        if (at < text.length) parts.add(LinePart(text.substring(at)))
        return parts
    }
}
