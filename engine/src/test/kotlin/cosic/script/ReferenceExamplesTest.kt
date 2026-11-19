package cosic.script

import cosic.pattern.CosicError
import cosic.pattern.cycleSpan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

class ReferenceExamplesTest {
    @Test
    fun everyReferenceExampleEvaluatesWithoutError() {
        for (entry in reference()) {
            val program = try {
                evaluateProgram(entry.example)
            } catch (e: CosicError) {
                fail("example of '${entry.name}' failed: ${e.message} -- ${entry.example}")
            }
            for (cycle in 0L..1L) {
                try {
                    program.pattern.query(cycleSpan(cycle))
                } catch (e: CosicError) {
                    fail("example of '${entry.name}' failed while playing cycle $cycle: ${e.message} -- ${entry.example}")
                }
            }
        }
    }

    @Test
    fun everyExampleIsASingleLine() {
        for (entry in reference()) assertFalse('\n' in entry.example, "example of '${entry.name}' spans several lines")
    }

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
    fun theReferenceListsEveryBuiltinExceptTheUnsupportedSamples() {
        val names = reference().map { it.name }
        assertEquals(Builtins.all.map { it.name }.filter { it != "samples" }, names)
        assertFalse("samples" in names)
        assertEquals(names.size, names.toSet().size)
    }

    @Test
    fun aliasesAreListedWithTheirEntry() {
        val every = reference().first { it.name == "every" }
        assertEquals(listOf("firstOf"), every.aliases)
        assertEquals(listOf("sound"), reference().first { it.name == "s" }.aliases)
        assertEquals(listOf("cutoff", "ctf"), reference().first { it.name == "lpf" }.aliases)
    }

    @Test
    fun signaturesShowHowToCallEachEntry() {
        fun signature(name: String) = reference().first { it.name == name }.signature
        assertEquals(".fast(number)", signature("fast"))
        assertEquals(".rev()", signature("rev"))
        assertEquals(".every(number, function)", signature("every"))
        assertEquals(".layer(...function)", signature("layer"))
        assertEquals("seq(...value)", signature("seq"))
        assertEquals("run(number)", signature("run"))
        assertEquals("sine", signature("sine"))
        assertEquals("setcpm(number)", signature("setcpm"))
        assertEquals("hush()", signature("hush"))
        assertEquals("s(string)", signature("s"))
        assertEquals("gain(number)", signature("gain"))
        assertEquals(".scale(string)", signature("scale"))
        assertEquals(".add(value)", signature("add"))
    }

    @Test
    fun entriesAreGroupedByCategory() {
        val categories = reference().map { it.category }
        val firstSeen = categories.distinct()
        // categories form contiguous runs, so the screen can show them as sections
        assertEquals(firstSeen, categories.fold(mutableListOf<String>()) { runs, c -> if (runs.lastOrNull() != c) runs.add(c); runs })
        for (expected in listOf("Constructors", "Signals", "Settings", "Time", "Structure", "Random", "Values")) {
            assertTrue(expected in firstSeen, "missing category $expected")
        }
        assertNotNull(reference().firstOrNull { it.category == "Filters" })
    }
}
