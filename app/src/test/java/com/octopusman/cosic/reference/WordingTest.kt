package com.octopusman.cosic.reference

import cosic.script.reference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The documentation texts are reworded where they are made (`tools/reference/build-reference.mjs`); what the app shows of them never names the environment it is modelled on. */
class WordingTest {
    private fun named(text: String) = text.contains("strudel", ignoreCase = true)

    @Test
    fun noDocumentationCardShowsTheName() {
        val shown = ArrayList<String>()
        for (card in DocJson.cards) {
            shown.add(DocHtml.plainText(card.description))
            for (param in card.params) shown.add(DocHtml.plainText(param.description))
        }
        val offending = shown.filter { named(it) }
        assertTrue(offending.isEmpty(), "cards show the name: ${offending.take(3)}")
    }

    @Test
    fun noReferenceEntryShowsTheName() {
        val offending = reference().filter { named(it.doc) }.map { it.name }
        assertTrue(offending.isEmpty(), "reference entries show the name: $offending")
    }

    @Test
    fun theLinksOfACardKeepTheirTargets() {
        val blocks = DocHtml.parse("""<p>combining (aka <a href="https://example.org/technical-manual/alignment/">alignment</a>) here.</p>""")
        val spans = (blocks.single() as DocBlock.Paragraph).spans
        assertEquals("https://example.org/technical-manual/alignment/", spans.single { it.href != null }.href)
        assertEquals("combining (aka alignment) here.", spans.joinToString("") { it.text })
    }
}
