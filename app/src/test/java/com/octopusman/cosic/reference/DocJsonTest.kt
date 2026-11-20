package com.octopusman.cosic.reference

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The documentation cards the engine carries (`docs.json`), and the HTML of their texts. */
class DocJsonTest {
    private val cards = DocJson.cards

    @Test
    fun theCardsAreStrudelsDocumentation() {
        assertTrue(cards.size > 500, "${cards.size} cards")
        val fast = cards.first { it.name == "fast" }
        assertEquals(listOf("density"), fast.synonyms)
        assertEquals("density", fast.synonymsText)
        assertEquals("<p>Speed up a pattern by the given factor. Used by \"*\" in mini notation.</p>".replace("\"", "&quot;"), fast.description)
        assertEquals(listOf(DocParam("factor", listOf("number", "Pattern"), "<p>speed up factor</p>")), fast.params)
        assertTrue(fast.examples.isNotEmpty())
        assertEquals("\$: " + fast.examples[0].code, fast.examples[0].snippet, "the example is a line that plays, and gets the label of a program with labels")
    }

    @Test
    fun theCardsStrudelHidesAreThereButMarked() {
        val hidden = cards.filter { it.hidden }
        assertTrue(hidden.isNotEmpty())
        assertTrue(hidden.any { it.name == "queryArc" })
        assertFalse(cards.first { it.name == "fast" }.hidden)
    }

    @Test
    fun noCardIsOfAFunctionNoProgramCanCall() {
        val names = cards.map { it.name }.toSet()
        for (name in listOf("MidiInput", "OLAProcessor", "csoundm", "applyGradualLowpass", "_euclidRot")) assertFalse(name in names, name)
    }

    @Test
    fun aSynonymsCardIsTheOneOfItsFunctionUnderTheOtherName() {
        val fast = cards.first { it.name == "fast" }
        val density = fast.asSynonym("density")
        assertEquals("density", density.name)
        assertEquals(listOf("fast"), density.synonyms)
        assertEquals(fast.description, density.description)
        assertEquals(fast.params, density.params)
        val multi = DocEntry("sound", synonyms = listOf("s", "snd")).asSynonym("s")
        assertEquals(listOf("sound", "snd"), multi.synonyms)
    }

    @Test
    fun aWordIsLookedUpByNameThenBySynonym() {
        val index = DocJson.index
        assertEquals("fast", index.lookup("fast")?.name)
        // `wtatt` is a synonym of `wtattack`, and no card of its own
        val wtatt = assertNotNull(index.lookup("wtatt"))
        assertEquals("wtatt", wtatt.name)
        assertEquals(listOf("wtattack"), wtatt.synonyms)
        // `density` is a synonym of `fast` and a control of its own: a name is found before a synonym, as in the tooltip of the web editor
        assertEquals(emptyList(), index.lookup("density")?.synonyms?.filter { it == "fast" })
        assertEquals("queryArc", index.lookup("queryArc")?.name, "the card of a name that is not suggested is found all the same")
        assertNull(index.lookup("nonsense"))
        assertNull(index.lookup(""))
    }

    @Test
    fun theCardAtTheCursorIsThatOfTheWordOrOfTheCallItIsIn() {
        val index = DocJson.index
        fun at(code: String): String? = index.cardAtCursor(code.replace("|", ""), code.indexOf('|'))?.name
        assertEquals("fast", at("s(\"bd\").fa|st(2)"))
        assertEquals("fast", at("s(\"bd\").fast|(2)"))
        assertEquals("fast", at("s(\"bd\").fast(2|)"), "inside the call")
        assertEquals("s", at("s(|\"bd\")"))
        assertEquals("wtatt", at("note(\"c\").wtat|t(0.1)"), "a synonym")
        assertNull(at("|  "))
        assertNull(at("nonsense(|)"))
    }

    @Test
    fun whenTwoCardsHaveOneNameTheFirstIsFound() {
        val index = DocIndex(listOf(DocEntry("choose", description = "first"), DocEntry("choose", description = "second")))
        assertEquals("first", index.lookup("choose")?.description)
    }

    // ---- the HTML of the texts ------------------------------------------------------------------------------------------------

    @Test
    fun aParagraphWithCodeAndEntities() {
        val blocks = DocHtml.parse("<p>Speed up by <code>factor</code>. Used by &quot;*&quot; &amp; more &lt;-</p>")
        assertEquals(
            listOf(
                DocBlock.Paragraph(
                    listOf(
                        DocSpan("Speed up by "),
                        DocSpan("factor", code = true),
                        DocSpan(". Used by \"*\" & more <-"),
                    ),
                ),
            ),
            blocks,
        )
    }

    @Test
    fun aListHasItsItemsWithBulletsOrNumbers() {
        val blocks = DocHtml.parse("<p>Selects:</p>\n<ul>\n<li>for samples</li>\n<li>for <em>scales</em></li>\n</ul>\n<ol>\n<li>one</li>\n<li>two</li>\n</ol>")
        assertEquals(
            listOf(
                DocBlock.Paragraph(listOf(DocSpan("Selects:"))),
                DocBlock.Item("\u2022", listOf(DocSpan("for samples"))),
                DocBlock.Item("\u2022", listOf(DocSpan("for "), DocSpan("scales", italic = true))),
                DocBlock.Item("1.", listOf(DocSpan("one"))),
                DocBlock.Item("2.", listOf(DocSpan("two"))),
            ),
            blocks,
        )
    }

    @Test
    fun aLinkKeepsItsAddress() {
        val blocks = DocHtml.parse("<p>See <a href=\"https://strudel.cc/learn/input-output/\">MIDI &amp; OSC in the docs</a></p>")
        assertEquals(
            listOf(DocBlock.Paragraph(listOf(DocSpan("See "), DocSpan("MIDI & OSC in the docs", href = "https://strudel.cc/learn/input-output/")))),
            blocks,
        )
    }

    @Test
    fun whiteSpaceIsCollapsedAsInABrowser() {
        val blocks = DocHtml.parse("<p>Dynamics Compressor.\nMore   info\t<strong>here</strong> </p>\n\n<p>  next </p>")
        assertEquals(
            listOf(
                DocBlock.Paragraph(listOf(DocSpan("Dynamics Compressor. More info "), DocSpan("here", bold = true))),
                DocBlock.Paragraph(listOf(DocSpan("next"))),
            ),
            blocks,
        )
    }

    @Test
    fun aBlockOfCodeKeepsItsLines() {
        val blocks = DocHtml.parse("<p>Example:</p><pre class=\"x\"><code>s(\"bd\")\n  .fast(2)</code></pre>")
        assertEquals(listOf(DocBlock.Paragraph(listOf(DocSpan("Example:"))), DocBlock.Code("s(\"bd\")\n  .fast(2)")), blocks)
    }

    @Test
    fun aQuotationIsMarked() {
        val blocks = DocHtml.parse("<blockquote><p>quoted</p></blockquote><p>plain</p>")
        assertEquals(listOf(DocBlock.Paragraph(listOf(DocSpan("quoted")), quote = true), DocBlock.Paragraph(listOf(DocSpan("plain")))), blocks)
    }

    @Test
    fun textWithoutTagsIsAParagraphAndUnknownTagsLeaveTheirText() {
        assertEquals(listOf(DocBlock.Paragraph(listOf(DocSpan("just text")))), DocHtml.parse("just text"))
        assertEquals(listOf(DocBlock.Paragraph(listOf(DocSpan("a b")))), DocHtml.parse("<span>a</span> <font>b</font>"))
        assertEquals(emptyList(), DocHtml.parse(""))
        assertEquals("x < y", DocHtml.plainText("<p>x &lt; y</p>"))
    }

    @Test
    fun entitiesThatAreNumbersAreDecodedAndUnknownOnesStay() {
        assertEquals("A\u00e9\uD83C\uDFB5&nope;", DocHtml.decode("&#65;&#xE9;&#x1F3B5;&nope;"))
    }

    @Test
    fun everyTextOfEveryCardBecomesTextWithoutMarkup() {
        for (card in cards) {
            val texts = listOf(card.description) + card.params.map { it.description }
            for (html in texts) {
                val plain = DocHtml.plainText(html)
                assertFalse(Regex("</?[a-zA-Z][^>]*>").containsMatchIn(plain), "markup is left in the text of ${card.name}: $plain")
                assertFalse(Regex("&(?:quot|amp|lt|gt|nbsp);").containsMatchIn(plain), "an entity is left in the text of ${card.name}: $plain")
            }
            if (card.description.isNotBlank()) assertTrue(DocHtml.parse(card.description).isNotEmpty(), "the description of ${card.name} has no text")
        }
    }
}
