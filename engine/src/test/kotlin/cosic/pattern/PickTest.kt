package cosic.pattern

import cosic.script.catalogue.registerPick
import cosic.script.evaluateProgram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `pick.mjs`: choosing patterns with a pattern. The hap strings are what Strudel prints for the same programs (the documented
 * examples and parity/extra-snippets/2.7.json are compared with it by ParityTest); the tests here pin the cases that matter by
 * hand and the errors, which Strudel can only log.
 */
class PickTest {
    init {
        registerPick()
    }

    private fun lines(code: String, cycles: Long = 1): List<String> = evaluateProgram(code).pattern.lines(0, cycles)

    // ---- pick ------------------------------------------------------------------------------------------------------

    @Test
    fun pickChoosesAPatternFromAList() {
        assertEquals(
            listOf(
                "[ 0/1 → 1/2 | a ]", "[ 1/2 → 1/1 | b ]",
                "[ 1/1 → 2/1 | c ]",
                "[ 2/1 → 7/3 | d ]", "[ 7/3 → 8/3 | e ]", "[ 8/3 → 3/1 | f ]",
            ),
            lines(""""<0 1 2>".pick(["a b", "c", "d e f"])""", 3),
        )
    }

    @Test
    fun anIndexPastTheEndSticksToTheLastEntryAndANegativeOneToTheFirst() {
        assertEquals(lines(""""0 1".pick(["a", "b"])"""), lines(""""0 5".pick(["a", "b"])"""))
        assertEquals(lines("""pure(0).pick(["a", "b"])"""), lines("""pure(-3).pick(["a", "b"])"""))
    }

    @Test
    fun anIndexIsRounded() {
        assertEquals(lines(""""0 1 1".pick(["a", "b"])"""), lines(""""0.4 0.5 1.4".pick(["a", "b"])"""))
    }

    @Test
    fun pickmodWrapsAroundInsteadOfSticking() {
        assertEquals(
            listOf("[ 0/1 → 1/1 | a ]", "[ 1/1 → 3/2 | b ]", "[ 3/2 → 2/1 | b ]", "[ 2/1 → 3/1 | c ]", "[ 3/1 → 4/1 | a ]"),
            lines(""""<0 1 2 3 4>".pickmod(["a", "b b", "c"])""", 4),
        )
        assertEquals(lines(""""<0 1 2 0 1>".pick(["a", "b b", "c"])""", 5), lines(""""<0 1 2 3 4>".pickmod(["a", "b b", "c"])""", 5))
    }

    @Test
    fun anObjectIsLookedUpByName() {
        assertEquals(
            listOf("[ 0/1 → 1/2 | x ]", "[ 1/2 → 1/1 | x ]", "[ 1/1 → 3/2 | y ]", "[ 3/2 → 2/1 | z ]"),
            lines(""""<a b>".pick({a: "x*2", b: "y z"})""", 2),
        )
        // the number 0 is the name "0"
        assertEquals(listOf("[ 0/1 → 1/1 | a ]"), lines("""pure(0).pick({0: "a"})"""))
    }

    @Test
    fun theEntriesMayBePlainValuesOrPatterns() {
        // a string in single quotes is a plain value, which repeats every cycle
        assertEquals(listOf("[ 0/1 → 1/1 | a b ]", "[ 1/1 → 2/1 | c ]"), lines(""""<0 1>".pick(['a b', 'c'])""", 2))
        assertEquals(listOf("[ 0/1 → 1/2 | s:bd ]", "[ 1/2 → 1/1 | s:sd ]"), lines(""""<0 1>".pick([s("bd sd"), s("hh*3")])"""))
        // the inner pattern gives the wholes: each event is a part of the cycle-long events of the entries
        assertEquals(
            listOf("[ (0/1 → 1/2) ⇝ 1/1 | true ]", "[ 0/1 ⇜ (1/2 → 1/1) | false ]"),
            lines(""""0 1".pick([pure(true), pure(false)])"""),
        )
    }

    @Test
    fun anEmptyLookupIsSilence() {
        assertEquals(emptyList(), lines(""""0 1".pick([])"""))
        assertEquals(emptyList(), lines(""""a".pick({})"""))
        assertEquals(emptyList(), lines(""""0".pickmod([])"""))
    }

    @Test
    fun aValueWithNoEntryIsAnErrorWhenThePatternIsQueried() {
        for (code in listOf(
            """pure('c').pick({a: "x", b: "y"})""",
            """pure(-1).pickmod(["a", "b"])""",
            """pure(-1).inhabitmod(["a", "b"])""",
            """pure(NaN).pick(["a", "b"])""",
        )) {
            val pattern = evaluateProgram(code).pattern
            assertFailsWith<CosicError>(code) { pattern.queryArc(Rational(0), Rational(1)) }
        }
    }

    @Test
    fun theGlobalPickTakesTheOldArgumentOrderToo() {
        val expected = lines(""""<0 1>".pick(["a b", "c"])""", 2)
        assertEquals(expected, lines("""pick(["a b", "c"], "<0 1>")""", 2))
        assertEquals(expected, lines("""pick("<0 1>", ["a b", "c"])""", 2))
    }

    // ---- the joins -------------------------------------------------------------------------------------------------

    @Test
    fun pickOutKeepsTheStructureOfThePickingPattern() {
        assertEquals(
            listOf("[ (0/1 → 1/3) ⇝ 1/2 | a ]", "[ 0/1 ⇜ (1/3 → 1/2) | b ]", "[ 1/2 → 1/1 | d ]"),
            lines(""""0 1".pickOut(["a b c", "d"])"""),
        )
        // pickmodOut wraps around, pickOut sticks to the end
        assertEquals(lines(""""0 1".pickOut(["a b c", "d"])"""), lines(""""0 3".pickmodOut(["a b c", "d"])"""))
        assertEquals(lines(""""0 1".pickOut(["a b c", "d"])"""), lines(""""0 9".pickOut(["a b c", "d"])"""))
    }

    @Test
    fun inhabitSqueezesACycleOfTheChosenPatternIntoTheEvent() {
        val expected = listOf(
            "[ 0/1 → 1/4 | a ]", "[ 1/4 → 1/2 | b ]",
            "[ 1/2 → 7/12 | c ]", "[ 7/12 → 2/3 | d ]", "[ 2/3 → 3/4 | e ]",
            "[ 3/4 → 7/8 | a ]", "[ 7/8 → 1/1 | b ]",
        )
        assertEquals(expected, lines(""""0 [1 0]".inhabit(["a b", "c d e"])"""))
        assertEquals(expected, lines(""""0 [1 0]".pickSqueeze(["a b", "c d e"])"""))
        // the mod variants wrap around, and pickSqueeze is the same as inhabit
        assertEquals(expected, lines(""""0 [3 4]".inhabitmod(["a b", "c d e"])"""))
        assertEquals(expected, lines(""""0 [3 4]".pickmodSqueeze(["a b", "c d e"])"""))
        // the patterns of an object are named by the events
        assertEquals(
            lines(""""a [b a]".inhabit({a: "x y", b: "z"})"""),
            lines("""let a = "x y"; let b = "z"; "a [b a]".inhabit({a, b})"""),
        )
    }

    @Test
    fun pickRestartStartsTheChosenPatternWithTheEventAndPickResetWithTheCycle() {
        // the chosen pattern is "<a b> c": in the second cycle restart plays its first cycle again, reset plays the one that goes with the cycle
        assertEquals(
            listOf("[ 0/1 → 1/2 | a ]", "[ 1/2 → 1/1 | d ]", "[ 1/1 → 3/2 | a ]", "[ 3/2 → 2/1 | d ]"),
            lines(""""<0 1>*2".pickRestart(["<a b> c", "d <e f>"])""", 2),
        )
        assertEquals(
            listOf("[ 0/1 → 1/2 | a ]", "[ 1/2 → 1/1 | d ]", "[ 1/1 → 3/2 | b ]", "[ 3/2 → 2/1 | d ]"),
            lines(""""<0 1>*2".pickReset(["<a b> c", "d <e f>"])""", 2),
        )
        // the mod variants wrap around
        assertEquals(
            lines(""""0 [1 0]".pickRestart(["<a b> c", "d"])""", 2),
            lines(""""0 [3 0]".pickmodRestart(["<a b> c", "d"])""", 2),
        )
        assertEquals(
            lines(""""0 [1 0]".pickReset(["<a b> c", "d"])""", 2),
            lines(""""0 [3 0]".pickmodReset(["<a b> c", "d"])""", 2),
        )
    }

    // ---- pickF -----------------------------------------------------------------------------------------------------

    @Test
    fun pickFAppliesTheFunctionTheIndexPicks() {
        val expected = listOf(
            "[ 0/1 → 1/2 | s:hh ]", "[ 1/2 → 1/1 | s:bd ]",
            "[ 1/1 → 5/4 | s:bd ]", "[ 5/4 → 3/2 | s:hh ]", "[ 3/2 → 7/4 | s:bd ]", "[ 7/4 → 2/1 | s:hh ]",
        )
        assertEquals(expected, lines("""s("bd hh").pickF("<0 1>", [rev, fast(2)])""", 2))
        // an object picks by name, and an arrow function is a function like any other
        assertEquals(expected, lines("""s("bd hh").pickF("<a b>", {a: rev, b: x => x.fast(2)})""", 2))
        // the plain method takes the pattern of indices as it is: `fast(2)` is the function picked by "1"
        assertEquals(
            listOf("[ 0/1 → 1/4 | s:bd ]", "[ 1/4 → 1/2 | s:hh ]", "[ 1/2 → 3/4 | s:bd ]", "[ 3/4 → 1/1 | s:hh ]"),
            lines("""s("bd hh")._pickF("1", [rev, fast(2)])"""),
        )
    }

    @Test
    fun pickmodFWrapsAroundTheFunctions() {
        assertEquals(
            lines("""s("bd hh").pickF("<0 1 1 0>", [rev, fast(2)])""", 4),
            lines("""s("bd hh").pickmodF("<0 1 3 2>", [rev, fast(2)])""", 4),
        )
    }

    // ---- squeeze ---------------------------------------------------------------------------------------------------

    @Test
    fun squeezePutsACycleOfTheChosenPatternIntoEachEvent() {
        assertEquals(
            listOf("[ 0/1 → 1/2 | a ]", "[ 1/2 → 1/1 | b ]", "[ 1/1 → 2/1 | c ]"),
            lines("""squeeze("<0 1>", ["a b", "c"])""", 2),
        )
        // an index is rounded and wraps around, whether it is too big or negative
        assertEquals(lines("""squeeze("0 1 2", ["a", "b b", "c"])"""), lines("""squeeze("3 4 5", ["a", "b b", "c"])"""))
        assertEquals(lines("""squeeze(pure(2), ["a", "b b", "c"])"""), lines("""squeeze(pure(-1), ["a", "b b", "c"])"""))
        assertEquals(emptyList(), lines("""squeeze("0", [])"""))
    }

    @Test
    fun squeezeNeedsAPatternAndAList() {
        assertEquals("pat.fmap is not a function", assertFailsWith<CosicError> { evaluateProgram("""squeeze('0', ["a"])""") }.message)
        assertEquals(
            "Cannot read properties of undefined (reading 'map')",
            assertFailsWith<CosicError> { evaluateProgram("""squeeze("0 1")""") }.message,
        )
    }

    // ---- the catalogue ---------------------------------------------------------------------------------------------

    @Test
    fun everyNameIsRegisteredWithItsArity() {
        for ((name, arity) in listOf(
            "pick" to 2, "pickmod" to 2, "pickOut" to 2, "pickmodOut" to 2, "pickRestart" to 2, "pickmodRestart" to 2,
            "pickReset" to 2, "pickmodReset" to 2, "inhabit" to 2, "pickSqueeze" to 2, "inhabitmod" to 2, "pickmodSqueeze" to 2,
            "pickF" to 3, "pickmodF" to 3,
        )) {
            val registered = assertNotNull(Registry.lookup(name), name)
            assertEquals(arity, registered.arity, name)
            assertEquals(RegisteredKind.REGISTER, registered.kind, name)
            assertTrue(registered.hasPlain, name)
        }
    }

    @Test
    fun theMethodsTakeThePatternOrThePlainValueOfTheirFirstArgument() {
        // at the level of the kernel a string is a plain value: there is no mini-notation to read "b c" with
        val picked = pure(1.0).callMethod("pick", listOf("a", "b c"))
        assertEquals(listOf("[ 0/1 → 1/1 | b c ]"), picked.lines(0, 1))
        // `pat._pick(lookup)` has no patterns to join
        val plain = Registry.lookup("pick")!!.plain(pure(1.0), listOf(listOf("a", "b c")))
        assertEquals(picked.lines(0, 1), (plain as Pattern).lines(0, 1))
    }
}
