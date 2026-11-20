package cosic.script

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What the autocomplete may offer: a name a program can use, and the documentation cards it is offered with (`docs.json`). */
class IsDefinedTest {
    @Test
    fun aFunctionOfPatternsAGlobalAndAValueAreDefined() {
        for (name in listOf("fast", "stack", "note", "s", "lpf", "sine", "silence", "setcpm", "hush", "register", "theme", "fontSize")) {
            assertTrue(isDefined(name), name)
        }
    }

    @Test
    fun aNameThatIsNoFunctionIsNot() {
        for (name in listOf("", "nonsense", "FAST", "fast2", "shiftInputBuffers", "MidiInput")) assertFalse(isDefined(name), name)
    }

    @Test
    fun everyFunctionTheReferenceListsIsDefined() {
        for (entry in reference()) {
            assertTrue(isDefined(entry.name), entry.name)
            for (alias in entry.aliases) assertTrue(isDefined(alias), alias)
        }
    }

    @Test
    fun theCardsHoldEveryFunctionOfTheReference() {
        val cards = cardNames()
        assertTrue(cards.size > 500, "the cards of Strudel's documentation: ${cards.size}")
        val missing = reference().map { it.name }.filter { it !in cards }
        assertEquals(emptyList(), missing, "functions that have a reference entry but no card")
    }

    @Test
    fun theCardsLeaveOutWhatAProgramCannotCall() {
        val cards = cardNames()
        for (name in listOf("MidiInput", "OLAProcessor", "csoundm", "applyGradualLowpass", "_euclidRot", "Pattern")) {
            assertFalse(name in cards, "$name has a card")
        }
    }

    @Test
    fun aCardHasItsExamplesReadyToInsert() {
        val text = javaClass.getResourceAsStream("/cosic/reference/docs.json")!!.bufferedReader().readText()
        // the example of `fast` is a line that plays, so its snippet has the label a program with labels needs
        assertTrue("\"snippet\": \"\$: " in text || "\"snippet\":\"\$: " in text, "a snippet has the label of a program with labels")
    }

    private fun cardNames(): Set<String> {
        val text = javaClass.getResourceAsStream("/cosic/reference/docs.json")!!.bufferedReader().readText()
        return Regex("\\{\"name\":\\s*\"([^\"]+)\"").findAll(text).map { it.groupValues[1] }.toSet()
    }
}
