package cosic.pattern

import cosic.pattern.legacy.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The applicative and monadic combinators of the kernel against the hap strings Strudel itself prints (the expected
 * lines were produced by running the pinned `@strudel/core` on the same expressions).
 */
class JoinsTest {
    private fun fn(body: (Any?) -> Any?) = fn1(body)

    private val ab = fastcat(pure("a"), pure("b"))
    private val oneTwoThree = fastcat(pure(1.0), pure(2.0), pure(3.0))
    private val oneTwo = fastcat(pure(1.0), pure(2.0))

    /** `x + "a"`, `x + "b"`, `x + "c"` in one cycle. */
    private fun abc(x: Any?) = fastcat(pure(jsToString(x) + "a"), pure(jsToString(x) + "b"), pure(jsToString(x) + "c"))

    private val joinedBy = { a: Any?, b: Any? -> jsToString(a) + jsToString(b) }
    private val functions: Pattern = ab.fmap { x -> fn { b -> joinedBy(x, b) } }

    // ---- appLeft, appRight, appBoth ---------------------------------------------------

    @Test
    fun appLeftKeepsTheWholesOfTheLeftAndSplitsTheParts() {
        assertEquals(
            listOf(
                "[ (0/1 → 1/3) ⇝ 1/2 | a1 ]",
                "[ 0/1 ⇜ (1/3 → 1/2) | a2 ]",
                "[ (1/2 → 2/3) ⇝ 1/1 | b2 ]",
                "[ 1/2 ⇜ (2/3 → 1/1) | b3 ]",
            ),
            functions.appLeft(oneTwoThree).firstLines(),
        )
    }

    @Test
    fun appRightKeepsTheWholesOfTheRight() {
        assertEquals(
            listOf(
                "[ 0/1 → 1/3 | a1 ]",
                "[ (1/3 → 1/2) ⇝ 2/3 | a2 ]",
                "[ 1/3 ⇜ (1/2 → 2/3) | b2 ]",
                "[ 2/3 → 1/1 | b3 ]",
            ),
            functions.appRight(oneTwoThree).firstLines(),
        )
    }

    @Test
    fun appBothIntersectsTheWholesAndMultipliesTheSteps() {
        val both = functions.appBoth(oneTwoThree)
        assertEquals(
            listOf("[ 0/1 → 1/3 | a1 ]", "[ 1/3 → 1/2 | a2 ]", "[ 1/2 → 2/3 | b2 ]", "[ 2/3 → 1/1 | b3 ]"),
            both.firstLines(),
        )
        assertEquals(r(6), both.steps)
    }

    @Test
    fun appLeftKeepsTheStepsOfTheLeftAndAppRightThoseOfTheRight() {
        assertEquals(r(2), functions.appLeft(oneTwoThree).steps)
        assertEquals(r(3), functions.appRight(oneTwoThree).steps)
    }

    @Test
    fun appBothOfPatternsWithoutStepsKeepsWhateverStepsThereAre() {
        fun fns(steps: Rational?) = pure(fn { x -> (x as Double) + 1 }).setSteps(steps)
        assertEquals(r(6), fns(r(3)).appBoth(pure(1.0).setSteps(r(2))).steps)
        assertEquals(r(2), fns(null).appBoth(pure(1.0).setSteps(r(2))).steps)
        assertEquals(r(3), fns(r(3)).appBoth(pure(1.0).setSteps(null)).steps)
    }

    @Test
    fun appLeftWithAPlainValueOnTheLeftIsAnError() {
        assertFailsWith<CosicError> { pure(1.0).appLeft(pure(2.0)).query(cycleSpan(0)) }
        assertFailsWith<CosicError> { pure(1.0).appBoth(pure(2.0)).query(cycleSpan(0)) }
    }

    // ---- bind and join ----------------------------------------------------------------

    @Test
    fun outerJoinTakesTheWholesFromTheOuterPattern() {
        assertEquals(
            listOf(
                "[ (0/1 → 1/3) ⇝ 1/2 | 1a ]",
                "[ 0/1 ⇜ (1/3 → 1/2) | 1b ]",
                "[ (1/2 → 2/3) ⇝ 1/1 | 2b ]",
                "[ 1/2 ⇜ (2/3 → 1/1) | 2c ]",
            ),
            oneTwo.fmap { abc(it) }.outerJoin().firstLines(),
        )
    }

    @Test
    fun innerJoinTakesTheWholesFromTheInnerPatterns() {
        assertEquals(
            listOf(
                "[ 0/1 → 1/3 | 1a ]",
                "[ (1/3 → 1/2) ⇝ 2/3 | 1b ]",
                "[ 1/3 ⇜ (1/2 → 2/3) | 2b ]",
                "[ 2/3 → 1/1 | 2c ]",
            ),
            oneTwo.fmap { abc(it) }.innerJoin().firstLines(),
        )
    }

    @Test
    fun joinIntersectsTheWholes() {
        assertEquals(
            listOf("[ 0/1 → 1/3 | 1a ]", "[ 1/3 → 1/2 | 1b ]", "[ 1/2 → 2/3 | 2b ]", "[ 2/3 → 1/1 | 2c ]"),
            oneTwo.fmap { abc(it) }.join().firstLines(),
        )
    }

    @Test
    fun bindIsFmapAndJoin() {
        assertEquals(
            listOf("[ 0/1 → 1/2 | 1a ]", "[ 1/2 → 1/1 | 2b ]"),
            oneTwo.bind { fastcat(pure(jsToString(it) + "a"), pure(jsToString(it) + "b")) }.firstLines(),
        )
    }

    @Test
    fun outerBindKeepsTheStepsOfTheOuterPattern() {
        assertEquals(r(2), oneTwo.outerBind { abc(it) }.steps)
        assertEquals(null, oneTwo.innerBind { abc(it) }.steps)
        assertEquals(null, oneTwo.bind { abc(it) }.steps)
    }

    @Test
    fun sequencePCollectsTheValuesOfPatternsIntoLists() {
        assertEquals(
            listOf("[ 0/1 → 1/2 | 1 2 ]", "[ 1/2 → 1/1 | 1 3 ]"),
            sequenceP(listOf(pure(1.0), fastcat(pure(2.0), pure(3.0)))).firstLines(),
        )
    }

    // ---- squeezeJoin ------------------------------------------------------------------

    @Test
    fun squeezeJoinFitsEachInnerCycleIntoItsOuterHap() {
        assertEquals(
            listOf("[ 0/1 → 1/4 | a1 ]", "[ 1/4 → 1/2 | a2 ]", "[ 1/2 → 3/4 | b1 ]", "[ 3/4 → 1/1 | b2 ]"),
            ab.fmap { x -> fastcat(pure(x.toString() + "1"), pure(x.toString() + "2")) }.squeezeJoin().firstLines(),
        )
        assertEquals(
            listOf(
                "[ 0/1 → 1/6 | a1 ]", "[ 1/6 → 1/3 | a2 ]", "[ 1/3 → 1/2 | a3 ]",
                "[ 1/2 → 7/12 | a1 ]", "[ 7/12 → 2/3 | a2 ]", "[ 2/3 → 3/4 | a3 ]",
                "[ 3/4 → 5/6 | b1 ]", "[ 5/6 → 11/12 | b2 ]", "[ 11/12 → 1/1 | b3 ]",
            ),
            fastcat(pure("a"), fastcat(pure("a"), pure("b")))
                .fmap { x -> fastcat(pure("${x}1"), pure("${x}2"), pure("${x}3")) }.squeezeJoin().firstLines(),
        )
    }

    @Test
    fun squeezeJoinSqueezesToTheCorrectCycle() {
        val inner = slowcat(pure("c0"), pure("c1"), pure("c2"), pure("c3"))
        assertEquals(listOf("[ 3/1 → 4/1 | c3 ]"), pure(inner).squeezeJoin().lines(3, 4))
    }

    @Test
    fun squeezeBindIsFmapAndSqueezeJoin() {
        assertEquals(
            ab.fmap { fastcat(pure("$it!"), pure("$it?")) }.squeezeJoin().firstLines(),
            ab.squeezeBind { fastcat(pure("$it!"), pure("$it?")) }.firstLines(),
        )
    }

    @Test
    fun squeezeJoinDropsContinuousOuterHaps() {
        assertEquals(emptyList(), signal { fastcat(pure("x"), pure("y")) }.squeezeJoin().query(cycleSpan(0)))
    }

    // ---- resetJoin and restartJoin -----------------------------------------------------

    @Test
    fun resetJoinShiftsTheInnerCycleToTheCycleOfTheOuterHap() {
        val outer = slowcat(pure("a"), pure("b")).fmap { x -> slowcat(pure("${x}1"), pure("${x}2")) }
        assertEquals(
            listOf("[ 0/1 → 1/1 | a1 ]", "[ 1/1 → 2/1 | b2 ]", "[ 2/1 → 3/1 | a1 ]", "[ 3/1 → 4/1 | b2 ]"),
            outer.resetJoin().lines(0, 4),
        )
    }

    @Test
    fun resetAndRestartJoinDifferInWhereTheInnerPatternStarts() {
        val outer = ab.fmap { x -> slowcat(pure("${x}1"), pure("${x}2")) }
        assertEquals(
            listOf("[ 0/1 → 1/2 | a1 ]", "[ 1/2 → 1/1 | b1 ]", "[ 1/1 → 3/2 | a2 ]", "[ 3/2 → 2/1 | b2 ]"),
            outer.resetJoin().lines(0, 2),
        )
        assertEquals(
            listOf("[ 0/1 → 1/2 | a1 ]", "[ 1/2 → 1/1 | b1 ]", "[ 1/1 → 3/2 | a1 ]", "[ 3/2 → 2/1 | b1 ]"),
            outer.restartJoin().lines(0, 2),
        )
    }

    // ---- polyJoin and stepJoin ---------------------------------------------------------

    @Test
    fun polyJoinStretchesInnerPatternsToTheStepsOfTheOuterOne() {
        val outer = fastcat(pure(fastcat(pure("a"), pure("b"))), pure(fastcat(pure("c"), pure("d"), pure("e"))))
        assertEquals(listOf("[ 0/1 → 1/2 | a ]", "[ 1/2 → 1/1 | d ]"), outer.polyJoin().firstLines())
        assertEquals(r(2), outer.polyJoin().steps)
    }

    @Test
    fun polyJoinNeedsTheStepsOfBothPatterns() {
        val outer = pure(fastcat(pure("a"), pure("b"))).setSteps(null)
        assertFailsWith<CosicError> { outer.polyJoin().query(cycleSpan(0)) }
    }

    @Test
    fun stepJoinPlaysTheInnerPatternsOneAfterTheOtherByTheirSteps() {
        val outer = fastcat(pure(fastcat(pure("a"), pure("b"))), pure(fastcat(pure("c"), pure("d"), pure("e"))))
        val joined = outer.stepJoin()
        assertEquals(
            listOf("[ 0/1 → 1/5 | a ]", "[ 1/5 → 2/5 | b ]", "[ 2/5 → 3/5 | c ]", "[ 3/5 → 4/5 | d ]", "[ 4/5 → 1/1 | e ]"),
            joined.firstLines(),
        )
        assertEquals(r(5), joined.steps)
    }

    @Test
    fun stepJoinGivesEachInnerPatternItsOwnNumberOfSteps() {
        val two = sequence(pure(pure("a")), pure(pure("b").setSteps(r(2)))).stepJoin()
        assertEquals(listOf("[ 0/1 → 1/3 | a ]", "[ 1/3 → 1/1 | b ]"), two.firstLines())
        assertEquals(r(3), two.steps)
        val half = sequence(pure(pure("a")), pure(pure("b").setSteps(r(1, 2)))).stepJoin()
        assertEquals(listOf("[ 0/1 → 2/3 | a ]", "[ 2/3 → 1/1 | b ]"), half.firstLines())
    }

    @Test
    fun stepJoinLooksAtTheCycleOfTheQuery() {
        val outer = slowcat(pure(fastcat(pure("a"), pure("b"))), pure(pure("c")))
        val joined = outer.stepJoin()
        assertEquals(listOf("[ 0/1 → 1/2 | a ]", "[ 1/2 → 1/1 | b ]"), joined.lines(0, 1))
        assertEquals(listOf("[ 1/1 → 2/1 | c ]"), joined.lines(1, 2))
    }
}
