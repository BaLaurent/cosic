package cosic.pattern

import cosic.script.ReplSession
import cosic.script.catalogue.registerOperators
import cosic.script.evaluateProgram
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The operators (`add`, `set`, `lt`, ...), their alignments, `setDefaultJoin` and the numeric functions (`range`, `round`, ...)
 * against what the pinned `@strudel/core` prints: the expected hap strings and values were produced by running it (the
 * numbers of `log2` and `exp` are those of V8, to the last digit). The breadth of it, every operator under every alignment,
 * is in the parity snippets `extra-snippets/2.2.json`.
 */
class OperatorsSpecTest {
    companion object {
        init {
            registerOperators()
        }
    }

    // the process-wide default alignment is shared with every other test of the JVM: start and end with the default one
    @BeforeTest
    @AfterTest
    fun restoreTheDefaultAlignment() = Alignment.reset()

    private fun value(op: String, a: Any?, b: Any?): String =
        pure(a).callMethod(op, pure(b)).firstLines().single().substringAfter("| ").removeSuffix(" ]")

    private fun <T> warnings(block: () -> T): Pair<T, List<String>> {
        val saved = logger
        val messages = ArrayList<String>()
        logger = { message, _, _ -> messages.add(message) }
        try {
            return block() to messages
        } finally {
            logger = saved
        }
    }

    private fun program(code: String, cycles: Long = 1): List<String> = evaluateProgram(code).pattern.lines(0, cycles)

    private fun left() = fastcat(slowcat(1.0, 2.0), 3.0, 4.0)

    private fun right() = fastcat(10.0, slowcat(20.0, 30.0))

    // ---- the table of CORE T2 ------------------------------------------------------------------------------------------

    @Test
    fun theValuesOfTheSpecTable() {
        assertEquals(listOf("[ 0/1 → 1/2 | 60 ]", "[ 1/2 → 1/1 | 64 ]"), fastcat(pure("c3"), pure("e3")).callMethod("add", 12.0).firstLines())
        assertEquals(listOf("[ 0/1 → 1/1 | n:11 ]"), pure(mapOf("n" to 1.0)).callMethod("add", pure(mapOf("n" to 10.0))).firstLines())
        assertEquals("7", value("sub", 10.0, 3.0))
        assertEquals("2", value("mod", -1.0, 3.0))
        assertEquals("0", value("and", 0.0, 5.0))
        assertEquals("5", value("or", 0.0, 5.0))
        assertEquals("1", value("band", 5.0, 3.0))
        assertEquals("2", value("blshift", 1.0, 33.0))
        assertEquals("true", value("lt", 1.0, 2.0))
        assertEquals(listOf("[ 0/1 → 1/1 | 46 ]"), pure(0.5).callMethod("range", 40.0, 52.0).firstLines())
        assertEquals(listOf("[ 0/1 → 1/1 | -7 ]"), pure(10.0).aligned("sub", "poly", listOf(pure(3.0))).firstLines())
        assertEquals(listOf("[ 0/1 → 1/1 | 1 ]"), pure(1.0).callMethod("round").firstLines())
        assertEquals(3.0, jsMathRound(2.5))
        assertEquals(-2.0, jsMathRound(-2.5))
        assertEquals(0.0, jsMathRound(0.49999999999999994))
    }

    @Test
    fun anArithmeticOperatorOnAControlPatternAndANumberWarnsAndChangesNothing() {
        val (haps, messages) = warnings { pure(mapOf("s" to "bd")).callMethod("add", 2.0).firstLines() }
        assertEquals(listOf("[ 0/1 → 1/1 | s:bd ]"), haps)
        assertEquals(listOf("[warn]: Can't do arithmetic on control pattern."), messages)
        assertEquals(listOf("[ 0/1 → 1/1 | n:1 ]"), warnings { pure(mapOf("n" to 1.0)).callMethod("add", 5.0).firstLines() }.first)
    }

    @Test
    fun structKeepsTheTrueValuesInJavaScriptsSenseOfTrue() {
        // "f" and "false" are strings, and every string that is not empty is true
        val words = fastcat(pure("t"), pure("f"), pure("false"), pure("x"))
        assertEquals(
            listOf("[ 0/1 → 1/4 | a ]", "[ 1/4 → 1/2 | a ]", "[ 1/2 → 3/4 | a ]", "[ 3/4 → 1/1 | a ]"),
            pure("a").callMethod("struct", words).firstLines(),
        )
        val numbers = fastcat(1.0, 0.0, 1.0, 1.0)
        assertEquals(listOf("[ 0/1 → 1/4 | a ]", "[ 1/2 → 3/4 | a ]", "[ 3/4 → 1/1 | a ]"), pure("a").callMethod("struct", numbers).firstLines())
        assertEquals(listOf("[ (0/1 → 1/2) ⇝ 1/1 | a ]"), pure("a").callMethod("mask", fastcat(1.0, 0.0)).firstLines())
        // several arguments are one sequence, an array too
        assertEquals(
            listOf("[ 0/1 → 1/4 | a ]", "[ 1/2 → 3/4 | a ]", "[ 3/4 → 1/1 | a ]"),
            pure("a").callMethod("struct", fastcat(1.0, 0.0), fastcat(1.0, 1.0)).firstLines(),
        )
        assertEquals(listOf("[ 0/1 → 1/3 | a ]", "[ 2/3 → 1/1 | a ]"), pure("a").callMethod("struct", listOf(1.0, 0.0, 1.0)).firstLines())
    }

    @Test
    fun theOtherStructureOperatorsAndTheGlobalsOfStructAndMask() {
        assertEquals(
            listOf("[ 0/1 → 1/3 | a ]", "[ 1/3 → 2/3 | a ]", "[ 2/3 → 1/1 | a ]"),
            program("pure('a').structAll(sequence(1, 0, 1))"),
        )
        assertEquals(
            listOf("[ (0/1 → 1/3) ⇝ 1/2 | a ]", "[ 0/1 ⇜ (1/3 → 1/2) | a ]", "[ (1/2 → 2/3) ⇝ 1/1 | b ]", "[ 1/2 ⇜ (2/3 → 1/1) | b ]"),
            program("sequence('a', 'b').maskAll(sequence(1, 0, 1))"),
        )
        val structured = listOf("[ 0/1 → 1/3 | a ]", "[ 2/3 → 1/1 | a ]")
        assertEquals(structured, program("struct(sequence(1, 0, 1), pure('a'))"))
        assertEquals(structured, program("struct(sequence(1, 0, 1))(pure('a'))"))
        assertEquals(structured, program("pure('a').struct(sequence(1, 0, 1))"))
        assertEquals(listOf("[ 0/1 → 1/4 | a ]", "[ 1/4 → 1/2 | b ]"), program("mask(sequence(1, 0), sequence('a', 'b', 'c', 'd'))"))
    }

    // ---- values -------------------------------------------------------------------------------------------------------

    @Test
    fun arithmeticAndBitwiseOperatorsDoWhatJavaScriptDoes() {
        for ((op, operands, expected) in NUMERIC) {
            assertEquals(expected, value(op, operands.first, operands.second), "$op(${operands.first}, ${operands.second})")
        }
    }

    @Test
    fun comparisonAndLogicOperatorsDoWhatJavaScriptDoes() {
        for ((op, expected) in COMPARISONS) {
            for ((i, operands) in COMPARE_PAIRS.withIndex()) {
                assertEquals(expected[i], value(op, operands.first, operands.second), "$op(${operands.first}, ${operands.second})")
            }
        }
    }

    @Test
    fun aWordThatIsNotANumberOrANoteCannotBeAdded() {
        val error = assertFailsWith<CosicError> { pure("bd").callMethod("add", 1.0).firstLines() }
        assertEquals("cannot parse as numeral: \"bd\"", error.message)
        assertEquals("cannot parse as numeral: \"[object Object]\"", assertFailsWith<CosicError> { program("n(1)._add(10)") }.message)
        assertEquals("b is not a function", assertFailsWith<CosicError> { fastcat(1.0, 2.0).callMethod("func", 5.0).firstLines() }.message)
    }

    @Test
    fun controlMapsAreMergedKeyByKeyInJavaScriptsKeyOrder() {
        assertEquals(listOf("[ 0/1 → 1/1 | s:bd n:11 gain:2 ]"), pure(mapOf("s" to "bd", "n" to 1.0)).callMethod("add", pure(mapOf("n" to 10.0, "gain" to 2.0))).firstLines())
        assertEquals(listOf("[ 0/1 → 1/1 | n:11 s:x ]"), pure(mapOf("n" to 1.0)).callMethod("add", pure(mapOf("s" to "x", "n" to 10.0))).firstLines())
        assertEquals(listOf("[ 0/1 → 1/1 | value:5 n:1 ]"), pure(5.0).callMethod("add", pure(mapOf("n" to 1.0))).firstLines())
        assertEquals(listOf("[ 0/1 → 1/1 | s:b n:1 ]"), pure(mapOf("s" to "a", "n" to 1.0)).callMethod("set", pure(mapOf("s" to "b"))).firstLines())
        assertEquals(listOf("[ 0/1 → 1/1 | s:a n:2 ]"), pure(mapOf("s" to "a")).callMethod("keep", pure(mapOf("s" to "b", "n" to 2.0))).firstLines())
        assertEquals(listOf("[ 0/1 → 1/1 | n:10 gain:1.5 ]"), pure(mapOf("n" to 2.0, "gain" to 3.0)).callMethod("mul", pure(mapOf("n" to 5.0, "gain" to 0.5))).firstLines())
        assertEquals(listOf("[ 0/1 → 1/1 | n:12 ]"), pure(mapOf("n" to "c4")).callMethod("sub", pure(mapOf("n" to "c3"))).firstLines())
        // keepif never merges: it keeps the left value, or nothing
        assertEquals(listOf("[ 0/1 → 1/1 | s:a ]"), pure(mapOf("s" to "a")).callMethod("keepif", pure(true)).firstLines())
        assertEquals(emptyList(), pure(mapOf("s" to "a")).callMethod("keepif", pure(0.0)).firstLines())
    }

    // ---- alignments ---------------------------------------------------------------------------------------------------

    @Test
    fun everyAlignmentOfAnOperator() {
        val expected = mapOf(
            "in" to listOf("[ 0/1 → 1/3 | 11 ]", "[ (1/3 → 1/2) ⇝ 2/3 | 13 ]", "[ 1/3 ⇜ (1/2 → 2/3) | 23 ]", "[ 2/3 → 1/1 | 24 ]", "[ 1/1 → 4/3 | 12 ]", "[ (4/3 → 3/2) ⇝ 5/3 | 13 ]", "[ 4/3 ⇜ (3/2 → 5/3) | 33 ]", "[ 5/3 → 2/1 | 34 ]"),
            "out" to listOf("[ (0/1 → 1/3) ⇝ 1/2 | 11 ]", "[ 0/1 ⇜ (1/3 → 1/2) | 13 ]", "[ (1/2 → 2/3) ⇝ 1/1 | 23 ]", "[ 1/2 ⇜ (2/3 → 1/1) | 24 ]", "[ (1/1 → 4/3) ⇝ 3/2 | 12 ]", "[ 1/1 ⇜ (4/3 → 3/2) | 13 ]", "[ (3/2 → 5/3) ⇝ 2/1 | 33 ]", "[ 3/2 ⇜ (5/3 → 2/1) | 34 ]"),
            "mix" to listOf("[ 0/1 → 1/3 | 11 ]", "[ 1/3 → 1/2 | 13 ]", "[ 1/2 → 2/3 | 23 ]", "[ 2/3 → 1/1 | 24 ]", "[ 1/1 → 4/3 | 12 ]", "[ 4/3 → 3/2 | 13 ]", "[ 3/2 → 5/3 | 33 ]", "[ 5/3 → 2/1 | 34 ]"),
            "squeeze" to listOf("[ 0/1 → 1/6 | 11 ]", "[ 1/6 → 1/3 | 21 ]", "[ 1/3 → 1/2 | 13 ]", "[ 1/2 → 2/3 | 23 ]", "[ 2/3 → 5/6 | 14 ]", "[ 5/6 → 1/1 | 24 ]", "[ 1/1 → 7/6 | 12 ]", "[ 7/6 → 4/3 | 32 ]", "[ 4/3 → 3/2 | 13 ]", "[ 3/2 → 5/3 | 33 ]", "[ 5/3 → 11/6 | 14 ]", "[ 11/6 → 2/1 | 34 ]"),
            "squeezeout" to listOf("[ 0/1 → 1/6 | 11 ]", "[ 1/6 → 1/3 | 13 ]", "[ 1/3 → 1/2 | 14 ]", "[ 1/2 → 2/3 | 21 ]", "[ 2/3 → 5/6 | 23 ]", "[ 5/6 → 1/1 | 24 ]", "[ 1/1 → 7/6 | 12 ]", "[ 7/6 → 4/3 | 13 ]", "[ 4/3 → 3/2 | 14 ]", "[ 3/2 → 5/3 | 32 ]", "[ 5/3 → 11/6 | 33 ]", "[ 11/6 → 2/1 | 34 ]"),
            "reset" to listOf("[ 0/1 → 1/3 | 11 ]", "[ 1/3 → 1/2 | 13 ]", "[ 1/2 → 5/6 | 21 ]", "[ 5/6 → 1/1 | 23 ]", "[ 1/1 → 4/3 | 12 ]", "[ 4/3 → 3/2 | 13 ]", "[ 3/2 → 11/6 | 32 ]", "[ 11/6 → 2/1 | 33 ]"),
            "restart" to listOf("[ 0/1 → 1/3 | 11 ]", "[ 1/3 → 1/2 | 13 ]", "[ 1/2 → 5/6 | 21 ]", "[ 5/6 → 1/1 | 23 ]", "[ 1/1 → 4/3 | 11 ]", "[ 4/3 → 3/2 | 13 ]", "[ 3/2 → 11/6 | 31 ]", "[ 11/6 → 2/1 | 33 ]"),
            "poly" to listOf("[ 0/1 → 1/3 | 11 ]", "[ 1/3 → 2/3 | 23 ]", "[ 2/3 → 1/1 | 14 ]", "[ 1/1 → 4/3 | 32 ]", "[ 4/3 → 5/3 | 13 ]", "[ 5/3 → 2/1 | 24 ]"),
        )
        for ((how, lines) in expected) {
            assertEquals(lines, left().aligned("add", how, listOf(right())).lines(0, 2), "add.$how")
        }
        // the same through the method of the operator and through the accessor of a program
        assertEquals(expected.getValue("out"), program("sequence(slowcat(1, 2), 3, 4).add.out(sequence(10, slowcat(20, 30)))", 2))
        assertEquals(expected.getValue("squeezeout"), program("sequence(slowcat(1, 2), 3, 4).add.squeezeout(sequence(10, slowcat(20, 30)))", 2))
    }

    @Test
    fun squeezeinIsAnotherNameOfSqueeze() {
        assertEquals(left().aligned("add", "squeeze", listOf(right())).lines(0, 1), left().aligned("add", "squeezein", listOf(right())).lines(0, 1))
        assertEquals(
            listOf("[ 0/1 → 1/6 | 11 ]", "[ 1/6 → 1/3 | 21 ]", "[ 1/3 → 1/2 | 13 ]", "[ 1/2 → 2/3 | 23 ]", "[ 2/3 → 5/6 | 14 ]", "[ 5/6 → 1/1 | 24 ]"),
            program("sequence(slowcat(1, 2), 3, 4).add.squeezein(sequence(10, slowcat(20, 30)))"),
        )
    }

    @Test
    fun polyAndSqueezeoutTakeTheirOperandsTheOtherWayRound() {
        assertEquals(listOf("[ 0/1 → 1/3 | 9 ]", "[ 1/3 → 2/3 | 17 ]", "[ 2/3 → 1/1 | 6 ]"), left().aligned("sub", "poly", listOf(right())).firstLines())
        assertEquals(
            listOf("[ 0/1 → 1/6 | -9 ]", "[ 1/6 → 1/3 | -7 ]", "[ 1/3 → 1/2 | -6 ]", "[ 1/2 → 2/3 | -19 ]", "[ 2/3 → 5/6 | -17 ]", "[ 5/6 → 1/1 | -16 ]"),
            left().aligned("sub", "squeezeout", listOf(right())).firstLines(),
        )
        // a control map keeps the key order of the operand the operator got first
        assertEquals(
            listOf("[ 0/1 → 1/2 | n:1 s:a ]", "[ 1/2 → 1/1 | n:2 x:5 s:b ]"),
            fastcat(mapOf("s" to "a"), mapOf("s" to "b")).aligned("set", "poly", listOf(fastcat(mapOf("n" to 1.0), mapOf("n" to 2.0, "x" to 5.0), mapOf("n" to 3.0)))).firstLines(),
        )
    }

    @Test
    fun theArgumentsOfAnAlignmentAreOneSequence() {
        // add.out(10, 20) adds seq(10, 20): the structure comes from it
        assertEquals(
            fastcat(1.0, 2.0, 3.0).aligned("add", "out", listOf(fastcat(10.0, 20.0))).firstLines(),
            fastcat(1.0, 2.0, 3.0).aligned("add", "out", listOf(10.0, 20.0)).firstLines(),
        )
        assertEquals(emptyList(), fastcat(1.0, 2.0).aligned("add", "out", emptyList()).firstLines())
        assertFailsWith<CosicError> { fastcat(1.0).aligned("add", "sideways", listOf(2.0)) }
    }

    @Test
    fun everyAlignmentGivesItsResultTheStepsStrudelGives() {
        // pattern.mjs:805-834: `in` keeps the steps of the pattern, `out` those of the other one, `mix` their least common multiple,
        // `poly` the pattern's; the joins that squeeze and restart have none (left has 3 steps, right has 2)
        val steps = mapOf("in" to 3L, "out" to 2L, "mix" to 6L, "poly" to 3L)
        for (op in listOf("add", "keepif", "keep", "set")) {
            for (how in listOf("in", "out", "mix", "squeeze", "squeezeout", "reset", "restart", "poly")) {
                assertEquals(steps[how]?.let { Rational(it) }, left().aligned(op, how, listOf(right())).steps, "$op.$how")
            }
        }
        assertEquals(Rational(3), pure("a").callMethod("struct", fastcat(1.0, 0.0, 1.0)).steps)
        assertEquals(Rational(2), fastcat("a", "b").callMethod("mask", fastcat(1.0, 0.0, 1.0)).steps)
        assertEquals(Rational(3), fastcat(0.0, 0.5, 1.0).callMethod("range", 10.0, 20.0).steps)
        assertEquals(Rational(3), fastcat(0.0, 0.5, 1.0).callMethod("round").steps)
        assertNull(fastcat(0.0, 0.5, 1.0).callMethod("range", slowcat(10.0, 20.0), 30.0).steps)
    }

    @Test
    fun theAlignmentMethodsOfASetAreThoseOfAProgram() {
        val a = fastcat(mapOf("s" to "a"), mapOf("s" to "b"))
        val b = fastcat(mapOf("n" to 1.0), mapOf("n" to 2.0), mapOf("n" to 3.0))
        val expected = mapOf(
            "in" to listOf("[ (0/1 → 1/3) ⇝ 1/2 | s:a n:1 ]", "[ 0/1 ⇜ (1/3 → 1/2) | s:a n:2 ]", "[ (1/2 → 2/3) ⇝ 1/1 | s:b n:2 ]", "[ 1/2 ⇜ (2/3 → 1/1) | s:b n:3 ]"),
            "out" to listOf("[ 0/1 → 1/3 | s:a n:1 ]", "[ (1/3 → 1/2) ⇝ 2/3 | s:a n:2 ]", "[ 1/3 ⇜ (1/2 → 2/3) | s:b n:2 ]", "[ 2/3 → 1/1 | s:b n:3 ]"),
            "mix" to listOf("[ 0/1 → 1/3 | s:a n:1 ]", "[ 1/3 → 1/2 | s:a n:2 ]", "[ 1/2 → 2/3 | s:b n:2 ]", "[ 2/3 → 1/1 | s:b n:3 ]"),
            "squeeze" to listOf("[ 0/1 → 1/6 | s:a n:1 ]", "[ 1/6 → 1/3 | s:a n:2 ]", "[ 1/3 → 1/2 | s:a n:3 ]", "[ 1/2 → 2/3 | s:b n:1 ]", "[ 2/3 → 5/6 | s:b n:2 ]", "[ 5/6 → 1/1 | s:b n:3 ]"),
            "squeezeout" to listOf("[ 0/1 → 1/6 | s:a n:1 ]", "[ 1/6 → 1/3 | s:b n:1 ]", "[ 1/3 → 1/2 | s:a n:2 ]", "[ 1/2 → 2/3 | s:b n:2 ]", "[ 2/3 → 5/6 | s:a n:3 ]", "[ 5/6 → 1/1 | s:b n:3 ]"),
        )
        for ((how, lines) in expected) {
            assertEquals(lines, a.aligned("set", how, listOf(b)).firstLines(), "set.$how")
            assertEquals(lines, program("sequence(s('a'), s('b')).$how(sequence(n(1), n(2), n(3)))"), "pat.$how()")
        }
        assertEquals(
            listOf("[ 0/1 → 1/2 | n:1 s:a ]", "[ 1/2 → 1/1 | n:2 gain:5 s:b ]"),
            program("sequence(s('a'), s('b')).poly(sequence(n(1), n(2).gain(5), n(3)))"),
        )
        // several arguments are a sequence
        assertEquals(listOf("[ 0/1 → 1/2 | s:a n:1 ]", "[ 1/2 → 1/1 | s:b n:2 ]"), program("sequence(s('a'), s('b')).out(sequence(n(1)), n(2))"))
        assertEquals(emptyList(), program("sequence(s('a'), s('b')).out()"))
    }

    // ---- setDefaultJoin -----------------------------------------------------------------------------------------------

    @Test
    fun setDefaultJoinChangesWhatTheOperatorsAndTheControlsDo() {
        val inLines = listOf("[ 0/1 → 1/3 | 11 ]", "[ (1/3 → 1/2) ⇝ 2/3 | 13 ]", "[ 1/3 ⇜ (1/2 → 2/3) | 23 ]", "[ 2/3 → 1/1 | 24 ]")
        val mixLines = listOf("[ 0/1 → 1/3 | 11 ]", "[ 1/3 → 1/2 | 13 ]", "[ 1/2 → 2/3 | 23 ]", "[ 2/3 → 1/1 | 24 ]")
        val outLines = listOf("[ (0/1 → 1/3) ⇝ 1/2 | 11 ]", "[ 0/1 ⇜ (1/3 → 1/2) | 13 ]", "[ (1/2 → 2/3) ⇝ 1/1 | 23 ]", "[ 1/2 ⇜ (2/3 → 1/1) | 24 ]")
        assertEquals("in", Alignment.default)
        assertEquals(inLines, left().callMethod("add", right()).firstLines())
        setDefaultJoin("mix")
        assertEquals(mixLines, left().callMethod("add", right()).firstLines())
        setDefaultJoin("OUT") // read in lower case
        assertEquals(outLines, left().callMethod("add", right()).firstLines())
        setDefaultJoin("foo") // not an alignment
        setDefaultJoin(Undefined)
        setDefaultJoin(null)
        setDefaultJoin("squeezein") // not one either
        assertEquals(outLines, left().callMethod("add", right()).firstLines())
        // the global form of an operator and `set` (which every control goes through) follow it too
        assertEquals(outLines, Registry.call("add", listOf(right(), left())).let { (it as Pattern).firstLines() })
        val controls = fastcat(mapOf("s" to "a"), mapOf("s" to "b")).setWith(fastcat(mapOf("n" to 1.0), mapOf("n" to 2.0), mapOf("n" to 3.0)))
        assertEquals(listOf("[ 0/1 → 1/3 | s:a n:1 ]", "[ (1/3 → 1/2) ⇝ 2/3 | s:a n:2 ]", "[ 1/3 ⇜ (1/2 → 2/3) | s:b n:2 ]", "[ 2/3 → 1/1 | s:b n:3 ]"), controls.firstLines())
        Alignment.reset()
        assertEquals(inLines, left().callMethod("add", right()).firstLines())
    }

    @Test
    fun setDefaultJoinNeedsAString() {
        for (bad in listOf(1.0, true, listOf("mix"), pure("mix"))) {
            assertEquals("alignment?.toLowerCase is not a function", assertFailsWith<CosicError> { setDefaultJoin(bad) }.message)
        }
        assertEquals("in", Alignment.default)
    }

    @Test
    fun everyProgramStartsWithTheDefaultAlignment() {
        val session = ReplSession.create()
        session.evaluate("setDefaultJoin('out')\nsilence")
        // what the program chose stays while its patterns are queried (their functions may use the operators then)
        assertEquals("out", Alignment.default)
        // Strudel keeps it until the page is reloaded; here the next program does not inherit it, so that a program means what it says
        val next = session.evaluate("sequence(slowcat(1, 2), 3, 4).add(sequence(10, slowcat(20, 30)))").pattern.lines(0, 1)
        assertEquals(left().aligned("add", "in", listOf(right())).lines(0, 1), next)
        assertEquals("in", Alignment.default)
        // within a program it takes effect from the line that sets it
        val within = "sequence(1, 2).add(sequence(10, 20, 30))\nsetDefaultJoin('mix')\nsequence(1, 2).add(sequence(10, 20, 30))"
        assertEquals(fastcat(1.0, 2.0).aligned("add", "mix", listOf(fastcat(10.0, 20.0, 30.0))).lines(0, 1), program(within))
        assertEquals("alignment?.toLowerCase is not a function", assertFailsWith<CosicError> { evaluateProgram("setDefaultJoin(\"mix\")") }.message)
    }

    // ---- the plain methods and the curried globals --------------------------------------------------------------------

    @Test
    fun thePlainMethodsApplyTheOperatorToEveryValueWithTheArgumentAsItIs() {
        assertEquals(listOf("[ 0/1 → 1/2 | 11 ]", "[ 1/2 → 1/1 | 12 ]"), program("sequence(1, 2)._add(10)"))
        assertEquals(listOf("[ 0/1 → 1/2 | undefined ]", "[ 1/2 → 1/1 | undefined ]"), program("sequence(1, 2)._keepif(0)"))
        assertEquals(listOf("[ 0/1 → 1/2 | x ]", "[ 1/2 → 1/1 | x ]"), program("sequence(1, 2)._set('x')"))
        assertEquals(listOf("[ 0/1 → 1/2 | 2 ]", "[ 1/2 → 1/1 | 4 ]"), program("sequence(1, 2)._func(x => x * 2)"))
    }

    @Test
    fun theOperatorsAreCurriedGlobals() {
        val expected = listOf("[ 0/1 → 1/2 | 3 ]", "[ 1/2 → 1/1 | 4 ]")
        assertEquals(expected, program("add(2, sequence(1, 2))"))
        assertEquals(expected, program("add(2)(sequence(1, 2))"))
        assertEquals(expected, (Registry.call("add", listOf(2.0, fastcat(1.0, 2.0))) as Pattern).firstLines())
        assertEquals(expected, ((Registry.call("add", listOf(2.0)) as JsFn).call(listOf(fastcat(1.0, 2.0))) as Pattern).firstLines())
        assertEquals(listOf("[ 0/1 → 1/2 | 11 ]", "[ 1/2 → 1/1 | 12 ]"), program("withValue(x => x + 10, sequence(1, 2))"))
        assertEquals(listOf("[ 0/1 → 1/2 | 11 ]", "[ 1/2 → 1/1 | 12 ]"), program("withValue(x => x + 10)(sequence(1, 2))"))
    }

    // ---- the numeric functions ----------------------------------------------------------------------------------------

    @Test
    fun roundFloorAndCeilReadNoteNamesAndNumbersInText() {
        val values = listOf(0.5, 1.5, 2.5, -0.5, -1.5, -2.5, 3.7, -2.2, 0.49999999999999994, "c3", "2.5", "e4", true)
        val expected = mapOf(
            "round" to listOf("1", "2", "3", "0", "-1", "-2", "4", "-2", "0", "48", "3", "64", "1"),
            "floor" to listOf("0", "1", "2", "-1", "-2", "-3", "3", "-3", "0", "48", "2", "64", "1"),
            "ceil" to listOf("1", "2", "3", "0", "-1", "-2", "4", "-2", "1", "48", "3", "64", "1"),
            "log2" to listOf("-1", "0.5849625007211562", "1.3219280948873624", "NaN", "NaN", "NaN", "1.8875252707415875", "NaN", "-1.0000000000000002", "5.584962500721156", "1.3219280948873624", "6", "0"),
        )
        for ((fn, results) in expected) {
            for ((i, v) in values.withIndex()) {
                assertEquals(results[i], pure(v).callMethod(fn).firstLines().single().substringAfter("| ").removeSuffix(" ]"), "$fn($v)")
            }
        }
    }

    @Test
    fun theBipolarFunctionsDoJavaScriptsArithmeticOnStrings() {
        assertEquals(
            listOf("[ 0/1 → 1/4 | -1 ]", "[ 1/4 → 1/2 | -0.5 ]", "[ 1/2 → 3/4 | 0 ]", "[ 3/4 → 1/1 | 1 ]"),
            fastcat(0.0, 0.25, 0.5, 1.0).callMethod("toBipolar").firstLines(),
        )
        assertEquals(
            listOf("[ 0/1 → 1/4 | 0 ]", "[ 1/4 → 1/2 | 0.25 ]", "[ 1/2 → 3/4 | 0.5 ]", "[ 3/4 → 1/1 | 1 ]"),
            fastcat(-1.0, -0.5, 0.0, 1.0).callMethod("fromBipolar").firstLines(),
        )
        // `"0.5" + 1` is "0.51": a string stays a string until the division
        assertEquals(listOf("[ 0/1 → 1/3 | 0.255 ]", "[ 1/3 → 2/3 | NaN ]", "[ 2/3 → 1/1 | -5.5 ]"), fastcat("0.5", "c3", "-1").callMethod("fromBipolar").firstLines())
        assertEquals(listOf("[ 0/1 → 1/3 | 0 ]", "[ 1/3 → 2/3 | NaN ]", "[ 2/3 → 1/1 | -3 ]"), fastcat("0.5", "c3", "-1").callMethod("toBipolar").firstLines())
    }

    @Test
    fun rangeIsTwoOperatorsUnderTheDefaultAlignment() {
        assertEquals(
            listOf("[ 0/1 → 1/4 | 10 ]", "[ 1/4 → 1/2 | 12.5 ]", "[ 1/2 → 3/4 | 15 ]", "[ 3/4 → 1/1 | 20 ]"),
            fastcat(0.0, 0.25, 0.5, 1.0).callMethod("range", 10.0, 20.0).firstLines(),
        )
        assertEquals(
            listOf("[ 0/1 → 1/4 | 10 ]", "[ 1/4 → 1/2 | 12.5 ]", "[ 1/2 → 3/4 | 15 ]", "[ 3/4 → 1/1 | 20 ]"),
            fastcat(-1.0, -0.5, 0.0, 1.0).callMethod("range2", 10.0, 20.0).firstLines(),
        )
        assertEquals(listOf("[ 0/1 → 1/3 | 10 ]", "[ 1/3 → 2/3 | 15 ]", "[ 2/3 → 1/1 | 20 ]"), fastcat(0.0, 0.5, 1.0).callMethod("range", "10", "20").firstLines())
        // a patterned bound: every cycle has its own range
        assertEquals(
            listOf("[ 0/1 → 1/3 | 10 ]", "[ 1/3 → 2/3 | 20 ]", "[ 2/3 → 1/1 | 30 ]", "[ 1/1 → 4/3 | 20 ]", "[ 4/3 → 5/3 | 25 ]", "[ 5/3 → 2/1 | 30 ]"),
            fastcat(0.0, 0.5, 1.0).callMethod("range", slowcat(10.0, 20.0), 30.0).lines(0, 2),
        )
        // the default alignment decides how the bounds meet the structure of the pattern
        setDefaultJoin("mix")
        assertEquals(
            listOf("[ 0/1 → 1/3 | 10 ]", "[ (1/3 → 1/2) ⇝ 2/3 | 55 ]", "[ 1/3 ⇜ (1/2 → 2/3) | 60 ]", "[ 2/3 → 1/1 | 100 ]"),
            fastcat(0.0, 0.5, 1.0).callMethod("range", fastcat(10.0, 20.0), 100.0).firstLines(),
        )
    }

    @Test
    fun rangexIsAnExponentialRangeThatPrintsLikeV8() {
        for ((t, expected) in RANGEX_500_4000) {
            assertEquals("[ 0/1 → 1/1 | ${jsNumberToString(expected)} ]", pure(t).callMethod("rangex", 500.0, 4000.0).firstLines().single(), "t=$t")
        }
        for ((t, expected) in RANGEX_20_20000) {
            assertEquals("[ 0/1 → 1/1 | ${jsNumberToString(expected)} ]", pure(t).callMethod("rangex", 20.0, 20000.0).firstLines().single(), "t=$t")
        }
    }

    @Test
    fun ratioDividesTheFirstNumberOfAListByTheOthers() {
        val expected = mapOf(
            listOf(5.0, 4.0) to "1.25",
            listOf(1.0, 2.0, 4.0) to "0.125",
            listOf("5") to "5",
            emptyList<Any?>() to "undefined",
            listOf("9", "3") to "3",
        )
        for ((list, text) in expected) {
            assertEquals("[ 0/1 → 1/1 | $text ]", pure(list).callMethod("ratio").firstLines().single(), "ratio($list)")
        }
        assertEquals("[ 0/1 → 1/1 | 3 ]", pure(3.0).callMethod("ratio").firstLines().single())
    }

    @Test
    fun theNumericFunctionsTakeNoArguments() {
        assertEquals(".round() expects 0 inputs but got 1.", assertFailsWith<CosicError> { pure(1.0).callMethod("round", 2.0) }.message)
        assertEquals(".range() expects 2 inputs but got 1.", assertFailsWith<CosicError> { pure(1.0).callMethod("range", 2.0) }.message)
    }

    // ---- Math, the way V8 does it ---------------------------------------------------------------------------------------

    @Test
    fun log2IsExactForPowersOfTwoAndPrintsLikeV8() {
        for ((x, expected) in LOG2_VECTORS + LOG2_SPECIAL) {
            val actual = jsLog2(x)
            assertTrue(actual.toRawBits() == expected.toRawBits() || (actual.isNaN() && expected.isNaN()), "log2($x) is $actual, V8 says $expected")
        }
        for (k in -1074..1023) assertEquals(k.toDouble(), jsLog2(Math.pow(2.0, k.toDouble())), "log2(2^$k)")
    }
}

// ---- what the pinned @strudel/core and V8 answer (generated by running them) --------------------------------------------

private val COMPARE_PAIRS: List<Pair<Any?, Any?>> = listOf(1.0 to 2.0, 2.0 to 2.0, 3.0 to 2.0, "a" to "b", "b" to "a", "10" to 9.0, "10" to "9", 1.0 to "1", 0.0 to "", true to 1.0, 0.0 to 5.0, 5.0 to 0.0, "" to "x", Double.NaN to Double.NaN)
private val COMPARISONS = mapOf(
    "lt" to listOf("true", "false", "false", "true", "false", "false", "true", "false", "false", "false", "true", "false", "true", "false"),
    "gt" to listOf("false", "false", "true", "false", "true", "true", "false", "false", "false", "false", "false", "true", "false", "false"),
    "lte" to listOf("true", "true", "false", "true", "false", "false", "true", "true", "true", "true", "true", "false", "true", "false"),
    "gte" to listOf("false", "true", "true", "false", "true", "true", "false", "true", "true", "true", "false", "true", "false", "false"),
    "eq" to listOf("false", "true", "false", "false", "false", "false", "false", "true", "true", "true", "false", "false", "false", "false"),
    "eqt" to listOf("false", "true", "false", "false", "false", "false", "false", "false", "false", "false", "false", "false", "false", "false"),
    "ne" to listOf("true", "false", "true", "true", "true", "true", "true", "false", "false", "false", "true", "true", "true", "true"),
    "net" to listOf("true", "false", "true", "true", "true", "true", "true", "true", "true", "true", "true", "true", "true", "true"),
    "and" to listOf("2", "2", "2", "b", "a", "9", "9", "1", "0", "1", "0", "0", "", "NaN"),
    "or" to listOf("1", "2", "3", "a", "b", "10", "10", "1", "", "true", "5", "5", "x", "NaN"),
)
private val NUMERIC: List<Triple<String, Pair<Any?, Any?>, String>> = listOf(
    Triple("add", 1.0 to 2.0, "3"),
    Triple("sub", 1.0 to 2.0, "-1"),
    Triple("mul", 3.0 to 4.0, "12"),
    Triple("div", 1.0 to 4.0, "0.25"),
    Triple("mod", -7.0 to 3.0, "2"),
    Triple("mod", 7.0 to -3.0, "-2"),
    Triple("mod", 5.5 to 2.0, "1.5"),
    Triple("pow", 2.0 to 10.0, "1024"),
    Triple("pow", 2.0 to 0.5, "1.4142135623730951"),
    Triple("pow", -8.0 to 0.3333333333333333, "NaN"),
    Triple("band", 5.0 to 3.0, "1"),
    Triple("band", -1.0 to 255.0, "255"),
    Triple("bor", 5.0 to 3.0, "7"),
    Triple("bxor", 5.0 to 3.0, "6"),
    Triple("blshift", 1.0 to 33.0, "2"),
    Triple("blshift", 1.0 to 31.0, "-2147483648"),
    Triple("brshift", -16.0 to 2.0, "-4"),
    Triple("brshift", 256.0 to 33.0, "128"),
    Triple("band", 4294967295.0 to 255.0, "255"),
    Triple("add", "c3" to "e3", "100"),
    Triple("sub", "c4" to "c3", "12"),
    Triple("add", "3" to "4", "7"),
    Triple("mul", "1.5" to 2.0, "3"),
    Triple("div", 1.0 to 0.0, "Infinity"),
    Triple("div", 0.0 to 0.0, "NaN"),
    Triple("mod", 5.0 to 0.0, "NaN"),
    Triple("add", true to 1.0, "2"),
    Triple("add", "e" to "f#4", "118"),
)

private val LOG2_VECTORS: List<Pair<Double, Double>> = listOf(
        1.0 to 0.0,
        2.0 to 1.0,
        3.0 to 1.584962500721156,
        5.0 to 2.321928094887362,
        7.0 to 2.807354922057604,
        10.0 to 3.321928094887362,
        100.0 to 6.643856189774724,
        1000.0 to 9.965784284662087,
        1024.0 to 10.0,
        0.5 to -1.0,
        0.1 to -3.321928094887362,
        0.3 to -1.7369655941662063,
        0.3333333333333333 to -1.5849625007211563,
        65535.0 to 15.999977986052736,
        65536.0 to 16.0,
        3.5 to 1.8073549220576042,
        1e-10 to -33.219280948873624,
        1e+21 to 69.7604899926346,
        1e+300 to 996.5784284662087,
        5e-324 to -1074.0,
        2.2250738585072014e-308 to -1022.0,
        123456789.0 to 26.879430932860473,
        536870912.0 to 29.0,
        4503599627370496.0 to 52.0,
        3486784401.0 to 31.699250014423125,
        1000000000000000.0 to 49.82892142331043,
        0.9999999999999999 to -1.6017132519074588e-16,
        1.0000000000000002 to 3.203426503814917e-16,
        440.0 to 8.78135971352466,
        8.0 to 3.0,
        16.0 to 4.0,
        1e-300 to -996.5784284662087,
        255.0 to 7.994353436858858,
        4000.0 to 11.965784284662087,
        500.0 to 8.965784284662087,
        1.5e-323 to -1072.415037499279,
        1e-320 to -1063.0170064253057,
        37268522401804.766 to 45.08302285469942,
        1.3159932273476428e+21 to 70.15664205701391,
        1.5675734193958658e-11 to -35.89267602860111,
        2951691015.8427954 to 31.45889456141364,
        505404544.84335005 to 28.912863396855037,
        6.194542922482296e-22 to -70.45142025354932,
        6.878879304177255e-10 to -30.437107406330508,
        2.1037251096986407e-13 to -42.11211903117918,
        521316103.8502943 to 28.957583185666458,
        3.740022251841164e+21 to 71.66353684631046,
        14876.729756622177 to 13.860769803681983,
        1065085503209331.5 to 49.919890675455996,
        0.005094040021050697 to -7.616973990549672,
        8060954578426580000.0 to 62.80565640082216,
        26889551.190247405 to 24.68054233980135,
        393.3962218483371 to 8.619839291908601,
        2.2566795835981195e-22 to -71.90821649662618,
        127014064281456430000.0 to 66.78355015337215,
        40.725749868935374 to 5.347869357720037,
        298619259.7398381 to 28.1537319755931,
        3.771097520366623e-9 to -27.982368395012877,
        32723.11075798453 to 14.998022281306966,
        353663221008.76953 to 38.36358523840859,
        374097524639.37537 to 38.44462346353288,
    )
private val LOG2_SPECIAL: List<Pair<Double, Double>> = listOf(0.0 to Double.NEGATIVE_INFINITY, 0.0 to Double.NEGATIVE_INFINITY, -1.0 to Double.NaN, Double.POSITIVE_INFINITY to Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY to Double.NaN, Double.NaN to Double.NaN)
private val RANGEX_500_4000: List<Pair<Double, Double>> = listOf(0.0 to 499.99999999999983, 0.1 to 615.5722066724579, 0.25 to 840.8964152537139, 0.3 to 933.0329915368072, 0.5 to 1414.2135623730946, 0.7 to 2143.546925072586, 0.75 to 2378.4142300054427, 0.9 to 3249.009585424945, 1.0 to 3999.9999999999995)
private val RANGEX_20_20000: List<Pair<Double, Double>> = listOf(0.0 to 19.999999999999996, 0.1 to 39.905246299377595, 0.25 to 112.46826503806975, 0.3 to 158.86564694485625, 0.5 to 632.4555320336755, 0.7 to 2517.850823588332, 0.75 to 3556.558820077843, 0.9 to 10023.744672545448, 1.0 to 19999.999999999985)
