package com.octopusman.cosic.console

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import cosic.io.ConsoleLog
import cosic.io.LogEntry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What the console shows of a line: the links in it (the patterns of ConsoleTab.jsx), the count after a repeated one, and the lines the engine keeps. */
class ConsoleTest {
    private fun parts(text: String) = Linkify.parts(text)

    @Test
    fun anAddressIsALink() {
        assertEquals(
            listOf(LinePart("see "), LinePart("https://strudel.cc/learn/input-output/", "https://strudel.cc/learn/input-output/"), LinePart(" for more")),
            parts("see https://strudel.cc/learn/input-output/ for more"),
        )
        assertEquals(listOf(LinePart("ftp://files.example.com/a.txt", "ftp://files.example.com/a.txt")), parts("ftp://files.example.com/a.txt"))
        assertEquals(listOf(LinePart("HTTP://X.COM", "HTTP://X.COM")), parts("HTTP://X.COM"))
    }

    @Test
    fun theEndOfAnAddressIsNotThePunctuationAfterIt() {
        assertEquals(
            listOf(LinePart("("), LinePart("https://x.com/a", "https://x.com/a"), LinePart(").")),
            parts("(https://x.com/a)."),
        )
        assertEquals(listOf(LinePart("https://x.com/a?b=c&d=e#f", "https://x.com/a?b=c&d=e#f"), LinePart(" ok")), parts("https://x.com/a?b=c&d=e#f ok"))
    }

    @Test
    fun wwwIsALinkToHttp() {
        assertEquals(listOf(LinePart("go to "), LinePart("www.strudel.cc/x", "http://www.strudel.cc/x")), parts("go to www.strudel.cc/x"))
        assertEquals(listOf(LinePart("www.example.org", "http://www.example.org")), parts("www.example.org"))
    }

    @Test
    fun wwwAfterASlashIsPartOfAnAddressAlreadyFound() {
        assertEquals(listOf(LinePart("https://www.example.org/a", "https://www.example.org/a")), parts("https://www.example.org/a"))
    }

    @Test
    fun anEmailAddressIsALinkToMailto() {
        assertEquals(
            listOf(LinePart("write to "), LinePart("sam.tagada@example.com", "mailto:sam.tagada@example.com"), LinePart(" please")),
            parts("write to sam.tagada@example.com please"),
        )
    }

    @Test
    fun theAddressInAnAddressWithAUserIsNotCutInTwo() {
        assertEquals(listOf(LinePart("https://user@example.com/x", "https://user@example.com/x")), parts("https://user@example.com/x"))
    }

    @Test
    fun aLineWithoutLinksIsOnePart() {
        assertEquals(listOf(LinePart("[eval] code updated")), parts("[eval] code updated"))
        assertEquals(emptyList(), parts(""))
    }

    @Test
    fun severalLinksOfSeveralKindsComeInOrder() {
        val text = "a www.one.com b https://two.com c me@three.org d"
        assertEquals(
            listOf(
                LinePart("a "),
                LinePart("www.one.com", "http://www.one.com"),
                LinePart(" b "),
                LinePart("https://two.com", "https://two.com"),
                LinePart(" c "),
                LinePart("me@three.org", "mailto:me@three.org"),
                LinePart(" d"),
            ),
            parts(text),
        )
        assertEquals(text, parts(text).joinToString("") { it.text }, "nothing is lost")
    }

    // ---- the line as text ---------------------------------------------------------------------------------------------------

    private fun entry(message: String, type: String? = null, count: Int = 1, color: String? = null) = LogEntry(1, message, type, count, color)

    @Test
    fun aRepeatedLineHasItsCountAfterIt() {
        assertEquals("[cyclist] start", lineText(entry("[cyclist] start"), Color.White, Color.Blue).text)
        assertEquals("[cyclist] start (3)", lineText(entry("[cyclist] start", count = 3), Color.White, Color.Blue).text)
    }

    @Test
    fun aLinkInALineOpensItsAddress() {
        val text = lineText(entry("see https://strudel.cc and more"), Color.White, Color.Blue)
        val links = text.getLinkAnnotations(0, text.length)
        assertEquals(1, links.size)
        assertEquals("https://strudel.cc", (links[0].item as LinkAnnotation.Url).url)
        assertEquals("https://strudel.cc", text.text.substring(links[0].start, links[0].end))
    }

    @Test
    fun aHighlightIsUnderlinedAndAnErrorIsNot() {
        val highlight = lineText(entry("Link copied to clipboard!", type = "highlight"), Color.White, Color.Blue)
        assertTrue(highlight.spanStyles.any { it.item.textDecoration != null }, "a highlight is underlined")
        val error = lineText(entry("[eval] error: boom", type = "error"), Color.White, Color.Blue)
        assertTrue(error.spanStyles.none { it.item.textDecoration != null })
    }

    // ---- the engine's console -----------------------------------------------------------------------------------------------

    @Test
    fun theConsoleKeepsTheLastFortyLinesAndCountsRepeats() {
        var now = 0L
        val console = ConsoleLog { now }
        for (i in 1..45) {
            console.log("line $i")
            now += 2000
        }
        assertEquals(40, console.entries.size)
        assertEquals("line 6", console.entries.first().message)
        console.log("again")
        now += 2000
        console.log("again")
        assertEquals(2, console.entries.last().count)
        assertEquals("again", lineText(console.entries.last().copy(count = 1), Color.White, Color.Blue).text)
        assertEquals("again (2)", lineText(console.entries.last(), Color.White, Color.Blue).text)
    }
}
