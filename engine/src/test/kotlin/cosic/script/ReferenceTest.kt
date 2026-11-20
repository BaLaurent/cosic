package cosic.script

import cosic.io.InputState
import cosic.pattern.CosicError
import cosic.pattern.Rational
import cosic.pattern.logger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The reference the app shows: the text is Strudel's JSDoc (tools/reference/build-reference.mjs), the list is what the engine can call,
 * and the examples and snippets it offers must work as programs.
 */
class ReferenceTest {
    // the examples of `keyDown` and `whenKey` read the keyboard, which the app provides: here an empty one
    private val inputs = InputState()

    @BeforeTest
    fun aKeyboardIsThere() = inputs.bindToSignals()

    @AfterTest
    fun noKeyboardAgain() = inputs.unbindFromSignals()

    private fun entry(name: String) = reference().first { it.name == name }

    @Test
    fun everyEntryIsFullyDescribed() {
        for (entry in reference()) {
            assertTrue(entry.name.isNotBlank(), "blank name")
            assertTrue(entry.category.isNotBlank(), "'${entry.name}' has no category")
            assertTrue(entry.signature.isNotBlank(), "'${entry.name}' has no signature")
            assertTrue(Regex("[.!?…]$").containsMatchIn(entry.doc.trim()), "doc of '${entry.name}' should be a sentence: '${entry.doc}'")
            assertFalse('\n' in entry.doc, "doc of '${entry.name}' spans several lines")
            assertTrue(entry.example.isNotBlank(), "'${entry.name}' has no example")
        }
    }

    @Test
    fun readingTheWorldLeavesTheKernelLogsWithTheSessionThatPlays() {
        val session = ReplSession()
        session.evaluate("silence")
        World.read()
        logger("a line of the reference test", null, null)
        assertEquals(listOf("a line of the reference test"), session.host.recentLines().map { it.message })
    }

    @Test
    fun namesAreUniqueAndAliasesAreFunctionsOfTheEngineToo() {
        val names = reference().map { it.name }
        assertEquals(names.size, names.toSet().size)
        val world = World.read()
        val aliases = reference().flatMap { it.aliases }
        assertEquals(aliases.size, aliases.toSet().size, "an alias belongs to one entry")
        for (entry in reference()) {
            for (alias in entry.aliases) {
                assertTrue(world.defines(alias), "'$alias', an alias of '${entry.name}', is not defined")
                assertFalse(alias in names, "'$alias', an alias of '${entry.name}', is an entry of its own")
            }
        }
        assertFalse("samples" in names && !World.read().isFunction("samples"), "samples is listed while no program can call it")
    }

    @Test
    fun theTextIsStrudelsJsdoc() {
        val fast = entry("fast")
        // `density` is a synonym of `fast` in its JSDoc and a control of its own (crackle noise): the control is the entry
        assertFalse("density" in fast.aliases)
        assertEquals("Sound", entry("density").category)
        assertEquals("Speed up a pattern by the given factor.", fast.doc)
        assertEquals("s(\"bd hh sd hh\").fast(2) // s(\"[bd hh sd hh]*2\")", fast.example)
        // a marker paragraph and a marker sentence are folded into the sentence, as Strudel's own `(Deprecated) Wave shaping distortion.`
        assertEquals("(Experimental) Contracts the step size of the pattern by the given factor.", entry("contract").doc)
        assertEquals("(Deprecated) Like echo, but the last 2 parameters are flipped.", entry("stut").doc)
        // `0 .. 1` is a range, no end of a sentence
        assertTrue(entry("range").doc.contains("0 .. 1"), entry("range").doc)
    }

    @Test
    fun aliasesAreListedWithTheirEntry() {
        assertEquals(listOf("sound"), entry("s").aliases)
        assertTrue("cutoff" in entry("lpf").aliases && "ctf" in entry("lpf").aliases, entry("lpf").aliases.toString())
        assertTrue("sparsity" in entry("slow").aliases, entry("slow").aliases.toString())
    }

    @Test
    fun signaturesShowHowToCallEachEntry() {
        assertEquals(".fast(factor)", entry("fast").signature)
        assertEquals(".every(n, func)", entry("every").signature)
        assertEquals(".lpf(frequency)", entry("lpf").signature)
        assertEquals("cat(...items)", entry("cat").signature)
        assertEquals("seq(...)", entry("seq").signature)
        assertEquals("s(sound)", entry("s").signature)
        assertEquals(".rev()", entry("rev").signature)
        assertEquals(".add(...)", entry("add").signature)
        assertEquals("sine", entry("sine").signature)
        assertEquals("silence", entry("silence").signature)
        assertEquals("setcpm(cpm)", entry("setcpm").signature)
        // a function without a parameter the JSDoc names, but called with some by its example
        assertEquals(".ply(...)", entry("ply").signature)
    }

    @Test
    fun anEntryIsCalledAsAValueExactlyWhenItIsOne() {
        val world = World.read()
        for (entry in reference()) {
            val value = '(' !in entry.signature
            assertEquals(value, world.isValue(entry.name) && !world.isMethod(entry.name), "the signature of '${entry.name}' is ${entry.signature}")
        }
    }

    @Test
    fun entriesAreGroupedByCategory() {
        val categories = reference().map { it.category }
        val firstSeen = categories.distinct()
        // categories form contiguous runs, so the screen can show them as sections
        assertEquals(firstSeen, categories.fold(mutableListOf<String>()) { runs, c -> if (runs.lastOrNull() != c) runs.add(c); runs })
        for (expected in listOf("Combining patterns", "Signals & generators", "Time", "Conditional", "Random", "Value modifiers", "Tonal", "Samples", "Filters")) {
            assertTrue(expected in firstSeen, "missing category $expected")
        }
        assertEquals("Time", entry("fast").category)
        assertEquals("Random", entry("degradeBy").category)
        assertEquals("Conditional", entry("every").category)
        assertEquals("Filters", entry("lpf").category)
        assertEquals("Delay, reverb & orbits", entry("room").category)
    }

    @Test
    fun aPatternExampleIsLabelledSoItPlaysNextToOtherLabelledPatterns() {
        assertEquals("\$: ${entry("fast").example}", entry("fast").snippet)
        // the final expression is labelled, what comes before it is not
        assertEquals("// The Cuban tresillo pattern.\n\$: note(\"c3\").euclid(3,8)", entry("euclid").snippet)
        // a program with labels already is kept as it is
        assertEquals(entry("duckonset").example, entry("duckonset").snippet)
        // an example that plays nothing has nothing to label
        assertEquals(entry("silence").example, entry("silence").snippet)
        for (entry in reference()) {
            assertTrue(entry.snippet == entry.example || entry.snippet.replaceFirst("\$: ", "") == entry.example, "the snippet of '${entry.name}' is not its example with a label")
        }
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

    @Test
    fun theReferenceListsWhatTheEngineImplementsOfStrudelsDocumentation() {
        assertNotNull(reference().firstOrNull { it.name == "fast" })
        val world = World.read()
        // every documented function a program can call is listed, in the order of the data, except the ones whose example cannot run yet
        assertEquals(documented.filter { it.excluded == null && world.defines(it.name) }.map { it.name }, reference().map { it.name })
        // a function that gets implemented adds an entry: this number only goes up
        assertTrue(reference().size > 300, "only ${reference().size} functions are listed")
    }
}
