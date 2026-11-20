package cosic.pattern

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import cosic.script.catalogue.registerKernel
import kotlin.test.assertNull

/** Steps (the number of steps per cycle a pattern knows it has) and `stepcat`, checked against what Strudel itself answers. */
class StepsTest {
    init {
        registerKernel()
    }

    private val a = pure("a")
    private val b = pure("b")
    private val c = pure("c")

    @Test
    fun stepsOfTheConstructors() {
        assertEquals(r(1), pure(1.0).steps)
        assertEquals(r(1), silence.steps)
        assertEquals(r(0), nothing.steps)
        assertNull(stack().steps)
        assertEquals(r(3), fastcat(a, b, c).steps)
        assertEquals(r(2), fastcat(a, fastcat(b, c)).steps)
        assertEquals(r(2), fastcat("a", listOf("b", "c")).steps)
        assertEquals(r(6), stack(fastcat(a, b), fastcat(a, b, c)).steps)
        assertEquals(r(6), slowcat(fastcat(a, b), fastcat(a, b, c)).steps)
        assertEquals(r(0), Rational.lcmOf(listOf(r(0), r(3))))
    }

    @Test
    fun aSingleEntryKeepsItsOwnSteps() {
        assertEquals(r(5), fastcat(fastcat("a", "b", "c", "d", "e")).steps)
        assertEquals(r(2), slowcat(fastcat(a, b)).steps)
    }

    @Test
    fun stackIgnoresPatternsWithoutSteps() {
        assertEquals(r(3), stack(fastcat(0.0, 1.0, 2.0), fastcat(3.0, 4.0).setSteps(null)).steps)
        assertEquals(r(6), stack(fastcat(0.0, 1.0, 2.0), fastcat(3.0, 4.0)).steps)
    }

    @Test
    fun theTimeFunctionsKeepTheStepsOfThePattern() {
        val p = fastcat(a, b, c)
        assertEquals(r(3), p._fast(2).steps)
        assertEquals(r(3), p.callMethod("fast", 2.0).steps)
        assertEquals(r(3), p.callMethod("fast", fastcat(1.0, 2.0)).steps)
        assertEquals(r(3), p._slow(2).steps)
        assertEquals(r(3), p._early(0.25).steps)
        assertEquals(r(3), p._late(0.25).steps)
        assertEquals(r(3), p._focus(0.25, 0.75).steps)
        assertEquals(r(3), silence.setSteps(r(3))._fast(0).steps, "fast(0) is silence with the steps of the pattern")
    }

    @Test
    fun theFunctionsThatCutOrJoinLoseTheSteps() {
        val p = fastcat(a, b, c)
        assertNull(p._compress(0.25, 0.75).steps)
        assertNull(p._fastGap(2).steps)
        assertNull(p.callMethod("slow", fastcat(1.0, 2.0)).steps)
        assertNull(p.callMethod("focus", 0.25, fastcat(0.75, 1.0)).steps)
    }

    @Test
    fun aPatternWithoutStepsStaysWithoutThem() {
        val p = fastcat(a, b, c).setSteps(null)
        assertNull(p._fast(2).steps)
        assertNull(p._expand(2).steps)
        assertNull(p._extend(2).steps)
    }

    @Test
    fun slowByZeroIsPlainSilence() {
        assertEquals(r(1), fastcat(a, b, c)._slow(0.0).steps)
        assertEquals(emptyList(), fastcat(a, b, c)._slow(0.0).firstLines())
    }

    @Test
    fun expandMultipliesTheSteps() {
        val p = fastcat(a, b, c)
        assertEquals(r(6), p._expand(2).steps)
        assertEquals(r(6), p.callMethod("expand", 2.0).steps)
        assertEquals(r(15), p.callMethod("expand", fastcat(2.0, 3.0)).steps)
        assertEquals(r(6), p.callMethod("s_expand", 2.0).steps)
    }

    @Test
    fun extendPlaysFasterAndExpands() {
        val p = fastcat(a, b)
        assertEquals(r(4), p._extend(2).steps)
        assertEquals(p._fast(2).firstLines(), p._extend(2).firstLines())
        assertEquals(r(6), fastcat(a, b, c).callMethod("extend", 2.0).steps)
        assertEquals(r(15), fastcat(a, b, c).callMethod("extend", fastcat(2.0, 3.0)).steps)
    }

    @Test
    fun expandTwoThingsInHalf() {
        assertEquals(
            stepcat(listOf(fastcat(0.0, 1.0, 2.0, 3.0), fastcat(0.0, 1.0, 2.0, 3.0)._expand(0.5))).firstLines(),
            fastcat(0.0, 1.0, 2.0, 3.0).callMethod("expand", 1.0, 0.5).firstLines(),
        )
        assertEquals(r(6), fastcat(0.0, 1.0, 2.0, 3.0).callMethod("expand", 1.0, 0.5).steps)
    }

    // ---- stepcat ----------------------------------------------------------------------

    @Test
    fun stepcatGivesEachPatternAShareProportionalToItsSteps() {
        val p = stepcat(listOf(fastcat("a", "b", "c", "d"), fastcat("e", "f")))
        assertEquals(
            listOf(
                "[ 0/1 → 1/6 | a ]", "[ 1/6 → 1/3 | b ]", "[ 1/3 → 1/2 | c ]",
                "[ 1/2 → 2/3 | d ]", "[ 2/3 → 5/6 | e ]", "[ 5/6 → 1/1 | f ]",
            ),
            p.firstLines(),
        )
        assertEquals(r(6), p.steps)
    }

    @Test
    fun stepcatOfListsUsesTheGivenTimes() {
        val p = stepcat(listOf(listOf(3.0, pure("e3")), listOf(1.0, pure("g3"))))
        assertEquals(listOf("[ 0/1 → 3/4 | e3 ]", "[ 3/4 → 1/1 | g3 ]"), p.firstLines())
        assertEquals(r(4), p.steps)
    }

    @Test
    fun stepcatSkipsEntriesOfTimeZero() {
        assertEquals(
            listOf("[ 0/1 → 1/3 | b ]", "[ 1/3 → 1/1 | c ]"),
            stepcat(listOf(listOf(0.0, a), listOf(1.0, b), listOf(2.0, c))).firstLines(),
        )
        assertEquals(r(0), stepcat(listOf(listOf(0.0, a), listOf(0.0, b))).steps)
        assertEquals(r(1), stepcat(listOf(listOf(1.0, a), listOf(0.0, b))).steps)
    }

    @Test
    fun stepcatGivesAnUndefinedTimeTheAverageOfTheOthers() {
        val p = stepcat(listOf(listOf(Undefined, a), listOf(2.0, b), listOf(4.0, c)))
        assertEquals(listOf("[ 0/1 → 1/3 | a ]", "[ 1/3 → 5/9 | b ]", "[ 5/9 → 1/1 | c ]"), p.firstLines())
        assertEquals(r(9), p.steps)
    }

    @Test
    fun stepcatWithNoTimeAtAllIsFastcat() {
        assertEquals(
            listOf("[ 0/1 → 1/2 | a ]", "[ 1/2 → 1/1 | b ]"),
            stepcat(listOf(listOf(Undefined, a), listOf(Undefined, b))).firstLines(),
        )
    }

    @Test
    fun stepcatOfOneEntryTakesItsTimeAsSteps() {
        assertEquals(r(3), stepcat(listOf(listOf(3.0, a))).steps)
        assertEquals(r(3), stepcat(listOf(listOf(3.0, a.setSteps(r(5))))).steps)
        assertEquals(listOf("[ 0/1 → 1/1 | a ]"), stepcat(listOf(listOf(3.0, a))).firstLines())
    }

    @Test
    fun stepcatOfNothingIsNothing() {
        assertEquals(r(0), stepcat(emptyList()).steps)
        assertEquals(emptyList(), stepcat(emptyList()).firstLines())
    }

    @Test
    fun stepcatTakesPlainValuesAndCountsAPatternWithoutStepsAsOne() {
        assertEquals(r(2), stepcat(listOf("a", "b")).steps)
        assertEquals(r(4), stepcat(listOf(a, b.setSteps(r(3)))).steps)
        assertEquals(r(2), stepcat(listOf(a.setSteps(null), b.setSteps(null))).steps)
    }

    @Test
    fun stepcatTimesMayBeFractions() {
        assertEquals(r(2), stepcat(listOf(listOf(1.5, a), listOf(0.5, b))).steps)
        assertEquals(r(1), stepcat(listOf(listOf(r(1, 3), a), listOf(r(2, 3), b))).steps)
        assertEquals(listOf("[ 0/1 → 3/4 | a ]", "[ 3/4 → 1/1 | b ]"), stepcat(listOf(listOf(1.5, a), listOf(0.5, b))).firstLines())
    }

    @Test
    fun stepcatRejectsATimeThatIsNotANumber() {
        assertEquals("Invalid argument", assertFailsWith<CosicError> { stepcat(listOf(listOf("x", a))) }.message)
    }

    @Test
    fun stepcatWorksOnTheNextCycleToo() {
        val p = stepcat(listOf(slowcat("a", "b"), fastcat("c", "d")))
        assertEquals(listOf("[ 1/1 → 4/3 | b ]", "[ 4/3 → 5/3 | c ]", "[ 5/3 → 2/1 | d ]").size, p.lines(1, 2).size)
    }
}
