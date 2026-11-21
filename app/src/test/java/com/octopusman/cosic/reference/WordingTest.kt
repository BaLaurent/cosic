package com.octopusman.cosic.reference

import cosic.script.reference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The documentation texts come from the JSDoc of the environment the app is modelled on; what the app shows of them never names it. */
class WordingTest {
    private fun named(text: String) = text.contains("strudel", ignoreCase = true)

    @Test
    fun theNameBecomesTheAppsOwn() {
        assertEquals("in Cosic. The default", "in Strudel. The default".inAppWords())
        assertEquals("Cosic mini notation and Cosic's note frequency", "Strudel mini notation and Strudel's note frequency".inAppWords())
    }

    @Test
    fun anAddressOfTheOtherWebSiteIsLeftOutWithItsIntroduction() {
        assertEquals("Uses dough as the audio engine.", "Uses dough as the audio engine. more info at https://dough.strudel.cc.".inAppWords())
        assertEquals("See  for more", "See https://strudel.cc/learn/ for more".inAppWords())
    }

    @Test
    fun textWithoutTheNameIsAsItWas() {
        val text = "Speed up a pattern by the given factor. Used by \"*\" in mini notation."
        assertEquals(text, text.inAppWords())
    }

    @Test
    fun noDocumentationCardShowsTheName() {
        val shown = ArrayList<String>()
        for (card in DocJson.cards) {
            shown.add(DocHtml.plainText(card.description))
            for (param in card.params) shown.add(DocHtml.plainText(param.description))
        }
        val offending = shown.filter { named(it) }
        assertTrue(offending.isEmpty(), "cards show the name: ${offending.take(3)}")
        assertTrue(shown.any { it.contains("Cosic") }, "the cards that named it say the app's name instead")
    }

    @Test
    fun noReferenceEntryShowsTheName() {
        val offending = reference().filter { named(it.doc.inAppWords()) }.map { it.name }
        assertEquals(emptyList(), offending)
        assertFalse(reference().isEmpty())
    }

    @Test
    fun theLinksOfACardKeepTheirTargets() {
        // an address is no text of the card: the link of "alignment" still goes where the documentation says
        val blocks = DocHtml.parse("""<p>combining (aka <a href="https://strudel.cc/technical-manual/alignment/">alignment</a>) in Strudel.</p>""")
        val spans = (blocks.single() as DocBlock.Paragraph).spans
        assertEquals("https://strudel.cc/technical-manual/alignment/", spans.single { it.href != null }.href)
        assertEquals("combining (aka alignment) in Cosic.", spans.joinToString("") { it.text })
    }
}
