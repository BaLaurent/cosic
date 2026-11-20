package cosic.script

import cosic.pattern.CosicError
import cosic.pattern.Rational
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The reference the app shows: what it lists, and that the examples and snippets it offers work as programs. */
class ReferenceTest {
    private fun entry(name: String) = reference().first { it.name == name }

    @Test
    fun everyEntryIsFullyDescribed() {
        for (entry in reference()) {
            assertTrue(entry.name.isNotBlank(), "blank name")
            assertTrue(entry.category.isNotBlank(), "'${entry.name}' has no category")
            assertTrue(entry.signature.isNotBlank(), "'${entry.name}' has no signature")
            assertTrue(entry.doc.trim().endsWith("."), "doc of '${entry.name}' should be a sentence: '${entry.doc}'")
            assertTrue(entry.example.isNotBlank(), "'${entry.name}' has no example")
        }
    }

    @Test
    fun everyExampleIsASingleLineAndNamesAreUnique() {
        for (entry in reference()) assertFalse('\n' in entry.example, "example of '${entry.name}' spans several lines")
        val names = reference().map { it.name }
        assertEquals(names.size, names.toSet().size)
        assertFalse("samples" in names)
    }

    @Test
    fun aliasesAreListedWithTheirEntry() {
        assertEquals(listOf("firstOf"), entry("every").aliases)
        assertEquals(listOf("sound"), entry("s").aliases)
        assertEquals(listOf("cutoff", "ctf"), entry("lpf").aliases)
    }

    @Test
    fun signaturesShowHowToCallEachEntry() {
        assertEquals(".fast(number)", entry("fast").signature)
        assertEquals(".every(number, function)", entry("every").signature)
        assertEquals("seq(...value)", entry("seq").signature)
        assertEquals("sine", entry("sine").signature)
        assertEquals("setcpm(number)", entry("setcpm").signature)
        assertEquals("s(string)", entry("s").signature)
    }

    @Test
    fun entriesAreGroupedByCategory() {
        val categories = reference().map { it.category }
        val firstSeen = categories.distinct()
        // categories form contiguous runs, so the screen can show them as sections
        assertEquals(firstSeen, categories.fold(mutableListOf<String>()) { runs, c -> if (runs.lastOrNull() != c) runs.add(c); runs })
        for (expected in listOf("Constructors", "Signals", "Settings", "Time", "Structure", "Random", "Values", "Filters")) {
            assertTrue(expected in firstSeen, "missing category $expected")
        }
    }

    @Test
    fun aPatternExampleIsLabelledSoItPlaysNextToOtherLabelledPatterns() {
        assertEquals("\$: ${entry("fast").example}", entry("fast").snippet)
        assertEquals(entry("setcpm").example, entry("setcpm").snippet)
        assertEquals(entry("silence").example, entry("silence").snippet)
    }

    @Test
    fun everyExampleEvaluatesAndPlaysTwoCycles() {
        val failures = ArrayList<String>()
        for (entry in reference()) {
            try {
                evaluateProgram(entry.example).pattern.queryArc(Rational.ZERO, Rational(2))
            } catch (e: CosicError) {
                failures.add("${entry.name}: ${e.message} -- ${entry.example}")
            }
        }
        assertEquals(emptyList(), failures)
    }

    @Test
    fun everySnippetEvaluatesAfterAnExistingLabelledPattern() {
        val failures = ArrayList<String>()
        for (entry in reference()) {
            try {
                evaluateProgram("\$: s(\"hh\")\n${entry.snippet}").pattern.queryArc(Rational.ZERO, Rational(1))
            } catch (e: CosicError) {
                failures.add("${entry.name}: ${e.message} -- ${entry.snippet}")
            }
        }
        assertEquals(emptyList(), failures)
    }

    @Test
    fun everyListedModeIsAScaleTheLanguageAccepts() {
        assertTrue(scaleModes().isNotEmpty())
        for (mode in scaleModes()) evaluateProgram("\$: n(\"0 1\").scale(\"C:$mode\")").pattern.queryArc(Rational.ZERO, Rational.ONE)
    }
}
