package cosic.pattern

import cosic.SourceRange
import cosic.script.catalogue.registerOperators
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The `register` machinery against Strudel's own (the expected lines come from registering the same functions in `@strudel/core`). */
class RegisterTest {
    private val calls = ArrayList<String>()

    private fun describe(x: Any?) = "${jsTypeof(x)}:${jsToString(x)}"

    private fun Any?.pat() = this as Pattern

    init {
        Registry.register(listOf("t_rf"), 2) { a ->
            calls.add(describe(a[0]))
            a[1].pat().fmap { v -> "$v+${jsToString(a[0])}" }
        }
        Registry.register(listOf("t_rg"), 3) { a ->
            calls.add("${jsToString(a[0])}/${jsToString(a[1])}")
            a[2].pat().fmap { v -> "$v+${jsToString(a[0])}+${jsToString(a[1])}" }
        }
        Registry.register(listOf("t_rk"), 1) { a -> a[0].pat().fmap { v -> "$v!" } }
        Registry.register(listOf("t_rs"), 2, preserveSteps = true) { a -> a[1].pat()._fast(a[0]) }
        Registry.register(listOf("t_rnp"), 2, patternify = false) { a ->
            calls.add("np:" + if (a[0] is Pattern) "pattern" else "other")
            a[1].pat().fmap { v -> "$v~" }
        }
        Registry.register(listOf("t_ro"), 2, join = { it.outerJoin() }) { a -> a[1].pat().fmap { v -> "$v+${jsToString(a[0])}" } }
    }

    private val a = pure("a")

    @Test
    fun constantArgumentsReachTheFunctionAsPlainValues() {
        assertEquals(listOf("[ 0/1 → 1/1 | a+2 ]"), a.callMethod("t_rf", 2.0).firstLines())
        assertEquals(listOf("number:2"), calls)
        assertEquals(listOf("[ 0/1 → 1/1 | a+2+3 ]"), a.callMethod("t_rg", 2.0, 3.0).firstLines())
    }

    @Test
    fun patternedArgumentsAreAppliedWithAppLeftAndJoined() {
        assertEquals(
            listOf("[ (0/1 → 1/2) ⇝ 1/1 | a+1 ]", "[ 0/1 ⇜ (1/2 → 1/1) | a+2 ]"),
            a.callMethod("t_rf", fastcat(1.0, 2.0)).firstLines(),
        )
        calls.clear()
        assertEquals(
            listOf(
                "[ (0/1 → 1/3) ⇝ 1/2 | a+1 ]", "[ 0/1 ⇜ (1/3 → 1/2) | a+2 ]",
                "[ (1/2 → 2/3) ⇝ 1/1 | b+2 ]", "[ 1/2 ⇜ (2/3 → 1/1) | b+3 ]",
            ),
            fastcat("a", "b").callMethod("t_rf", fastcat(1.0, 2.0, 3.0)).firstLines(),
        )
        assertEquals(listOf("number:1", "number:2", "number:3"), calls)
    }

    @Test
    fun severalPatternedArgumentsAreApplied() {
        assertEquals(
            listOf("[ 0/1 → 1/2 | a+1+5 ]", "[ 1/2 → 1/1 | b+2+5 ]"),
            fastcat("a", "b").callMethod("t_rg", fastcat(1.0, 2.0), 5.0).firstLines(),
        )
        assertEquals(listOf("1/5", "2/5"), calls)
        assertEquals(
            listOf("[ (0/1 → 1/2) ⇝ 1/1 | a+1+3 ]", "[ 0/1 ⇜ (1/2 → 1/1) | a+2+4 ]"),
            a.callMethod("t_rg", fastcat(1.0, 2.0), fastcat(3.0, 4.0)).firstLines(),
        )
    }

    @Test
    fun aFunctionOfOnePatternedArgumentSequencesWhatItIsGiven() {
        assertEquals(emptyList(), a.callMethod("t_rf").firstLines(), "no arguments is silence")
        assertEquals(emptyList(), calls)
        assertEquals(
            listOf("[ (0/1 → 1/2) ⇝ 1/1 | a+2 ]", "[ 0/1 ⇜ (1/2 → 1/1) | a+3 ]"),
            a.callMethod("t_rf", 2.0, 3.0).firstLines(),
        )
        assertEquals(listOf("number:2", "number:3"), calls)
    }

    @Test
    fun theNumberOfArgumentsIsChecked() {
        assertEquals(".t_rg() expects 2 inputs but got 1.", assertFailsWith<CosicError> { a.callMethod("t_rg", 1.0) }.message)
        assertEquals(".t_rk() expects 0 inputs but got 1.", assertFailsWith<CosicError> { a.callMethod("t_rk", 1.0) }.message)
        assertEquals(listOf("[ 0/1 → 1/1 | a! ]"), a.callMethod("t_rk").firstLines())
        assertEquals("unknown method 'nope'", assertFailsWith<CosicError> { a.callMethod("nope") }.message)
    }

    @Test
    fun preserveStepsKeepsTheStepsOfThePattern() {
        assertEquals(r(3), fastcat("a", "b", "c").callMethod("t_rs", 2.0).steps)
        assertEquals(r(3), fastcat("a", "b", "c").callMethod("t_rs", fastcat(1.0, 2.0)).steps)
        assertNull(fastcat("a", "b", "c").callMethod("t_rf", fastcat(1.0, 2.0)).steps, "without preserveSteps a joined result has none")
    }

    @Test
    fun withoutPatternifyTheArgumentsAreAllPatterns() {
        assertEquals(listOf("[ 0/1 → 1/1 | a~ ]"), a.callMethod("t_rnp", 2.0).firstLines())
        assertEquals(listOf("np:pattern"), calls)
    }

    @Test
    fun theJoinCanBeChosen() {
        assertEquals(
            listOf("[ 0/1 → 1/3 | a+1 ]", "[ (1/3 → 1/2) ⇝ 2/3 | a+2 ]", "[ 1/3 ⇜ (1/2 → 2/3) | b+2 ]", "[ 2/3 → 1/1 | b+3 ]"),
            fastcat("a", "b").callMethod("t_ro", fastcat(1.0, 2.0, 3.0)).firstLines(),
        )
    }

    @Test
    fun theGlobalFunctionIsCurriedAndTakesThePatternLast() {
        val partial = Registry.call("t_rf", listOf(2.0)) as JsFn
        assertEquals(listOf("[ 0/1 → 1/1 | a+2 ]"), (partial.call(listOf(a)) as Pattern).firstLines())
        assertEquals(listOf("[ 0/1 → 1/1 | a+2 ]"), (Registry.call("t_rf", listOf(2.0, a)) as Pattern).firstLines())
        assertEquals(1, (Registry.call("t_rg", listOf(1.0, 2.0)) as JsFn).arity, "one argument (the pattern) is still missing")
    }

    @Test
    fun thePlainMethodTakesTheArgumentsAsTheyAre() {
        val plain = Registry.lookup("t_rf")!!.plain(a, listOf(2.0)) as Pattern
        assertEquals(listOf("[ 0/1 → 1/1 | a+2 ]"), plain.firstLines())
        assertEquals(listOf("number:2"), calls)
        assertTrue(Registry.lookup("t_rf")!!.hasPlain && !Registry.lookup("t_rk")!!.hasPlain)
    }

    @Test
    fun stringsFunctionsUndefinedAndNullAreArguments() {
        assertEquals(listOf("[ 0/1 → 1/1 | a+zz ]"), a.callMethod("t_rf", "zz").firstLines())
        val f = jsFn(1) { it[0] }
        calls.clear()
        a.callMethod("t_rf", f).firstLines()
        assertTrue(calls.single().startsWith("function:"))
        calls.clear()
        assertEquals(listOf("[ 0/1 → 1/1 | a+undefined ]"), a.callMethod("t_rf", Undefined).firstLines())
        assertEquals(listOf("undefined:undefined"), calls)
        assertEquals(listOf("[ 0/1 → 1/1 | a+null ]"), a.callMethod("t_rf", null).firstLines())
    }

    @Test
    fun theLocationOfAConstantArgumentIsAddedToTheHaps() {
        val arg = pure(2.0).withLoc(SourceRange(4, 5))
        val hap = a.withLoc(SourceRange(0, 1)).callMethod("t_rf", arg).query(cycleSpan(0)).single()
        assertEquals(listOf(SourceRange(0, 1), SourceRange(4, 5)), hap.context.locations)
    }

    @Test
    fun theLaterRegistrationOfANameWins() {
        Registry.register(listOf("t_dup"), 1) { it[0] }
        Registry.register(listOf("t_dup"), 1) { _ -> silence }
        assertEquals(emptyList(), a.callMethod("t_dup").firstLines())
        assertSame(Registry.lookup("t_dup"), Registry.lookup("t_dup"))
    }

    @Test
    fun theOperatorCatalogueSetsTheAlignmentHook() {
        registerOperators()
        val hook = assertNotNull(alignHook)
        val other = pure(10.0)
        assertEquals(a.aligned("add", "out", listOf(other)).firstLines(), hook(a, "add", "out", listOf(other)).firstLines())
    }
}
