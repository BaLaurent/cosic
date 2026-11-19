package cosic.script

import cosic.pattern.cycleSpan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class ReferenceSnippetTest {
    private fun entry(name: String) = reference().first { it.name == name }

    @Test
    fun aPatternExampleIsLabelledSoItPlaysNextToOtherLabelledPatterns() {
        val euclid = entry("euclid")

        assertEquals("\$: ${euclid.example}", euclid.snippet)
    }

    @Test
    fun anExampleThatAlreadyHasALabelIsInsertedAsIs() {
        assertEquals(entry("silence").example, entry("silence").snippet)
        assertEquals(entry("setcpm").example, entry("setcpm").snippet)
    }

    @Test
    fun aSettingIsInsertedAsIsBecauseItIsNotAPattern() {
        assertEquals("hush()", entry("hush").snippet)
    }

    @Test
    fun everySnippetEvaluatesAfterAnExistingLabelledPattern() {
        for (entry in reference()) {
            val program = try {
                evaluateProgram("\$: s(\"hh\")\n${entry.snippet}")
            } catch (e: Exception) {
                fail("snippet of '${entry.name}' failed: ${e.message} -- ${entry.snippet}")
            }
            program.pattern.query(cycleSpan(0))
        }
    }

    @Test
    fun aMethodSnippetStillSoundsNextToAnExistingLabelledPattern() {
        val program = evaluateProgram("\$: s(\"hh\")\n${entry("euclid").snippet}")

        val sounds = program.pattern.query(cycleSpan(0)).map { it.value["s"] }.toSet()

        assertTrue("bd" in sounds, "the inserted euclid pattern is silent: $sounds")
    }
}
