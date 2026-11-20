package cosic.pattern

import cosic.script.catalogue.Catalogue
import org.junit.After
import org.junit.Before
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * `timeline` of impure.mjs while the scheduler plays it. What a query that is not the scheduler's gives (the offset is the start of the
 * timeline hap, nothing is remembered) is compared with Strudel in parity/extra-snippets/2.0.json; the memory of the timelines cannot be
 * seen there, since Strudel's snapshots only ask without the scheduler's controls, so it is tested here from the code:
 * the scheduler's queries carry the control `cyclist`.
 */
class TimelineTest {
    init {
        Catalogue.installAll()
    }

    @Before
    @After
    fun forgetTheTimelines() = Timelines.reset()

    /** `<0 1 2 3>`: the value tells which cycle of the pattern is playing. */
    private val cycles: Pattern = slowcat(listOf(0.0, 1.0, 2.0, 3.0).map { pure(it) })

    private val scheduler = mapOf<String, Any?>("cyclist" to "cyclist")

    private fun Pattern.ask(begin: Rational, end: Rational, controls: Map<String, Any?>): List<String> =
        query(State(TimeSpan(begin, end), controls)).map { it.show(true) }

    private fun Pattern.cycle(n: Long, controls: Map<String, Any?> = scheduler) = ask(Rational(n), Rational(n + 1), controls)

    @Test
    fun aQueryThatIsNotTheSchedulersRemembersNothing() {
        val on2 = cycles._timeline(2.0)
        // the pattern is played from the start of the cycle that is asked for, whenever it is asked
        assertEquals(listOf("[ 5/1 → 6/1 | 0 ]"), on2.cycle(5, emptyMap()))
        assertEquals(listOf("[ 6/1 → 7/1 | 0 ]"), on2.cycle(6, emptyMap()))
        assertEquals(listOf("[ 0/1 → 1/1 | 0 ]"), on2.cycle(0, emptyMap()))
    }

    @Test
    fun theSchedulerKeepsTheOffsetOfATimelineItHasSeen() {
        val on2 = cycles._timeline(2.0)
        assertEquals(listOf("[ 0/1 → 1/1 | 0 ]"), on2.cycle(0))
        assertEquals(listOf("[ 1/1 → 2/1 | 1 ]"), on2.cycle(1))
        assertEquals(listOf("[ 2/1 → 3/1 | 2 ]"), on2.cycle(2))
        // another evaluation of the same timeline number goes on where the timeline was, however the pattern changed
        assertEquals(listOf("[ 3/1 → 4/1 | 3 ]"), cycles._timeline(2.0).cycle(3))
        // and a timeline the scheduler has not seen starts when it is first asked for
        assertEquals(listOf("[ 3/1 → 4/1 | 0 ]"), cycles._timeline(7.0).cycle(3))
    }

    @Test
    fun aTimelineSeenOverHalfwayThroughItsEventStartsAtTheEndOfIt() {
        // asked from 3/4 of the cycle on, the timeline hap [0, 1) is first seen after its midpoint: the pattern is cued up for the next cycle
        assertEquals(listOf("[ 0/1 ⇜ (3/4 → 1/1) | 3 ]"), cycles._timeline(2.0).ask(Rational(3, 4), Rational.ONE, scheduler))
        assertEquals(listOf("[ 1/1 → 2/1 | 0 ]"), cycles._timeline(2.0).cycle(1))
        assertEquals(listOf("[ 2/1 → 3/1 | 1 ]"), cycles._timeline(2.0).cycle(2))
    }

    @Test
    fun theNegativeOfATimelineStartsItOver() {
        assertEquals(listOf("[ 0/1 → 1/1 | 0 ]"), cycles._timeline(2.0).cycle(0))
        assertEquals(listOf("[ 1/1 → 2/1 | 1 ]"), cycles._timeline(2.0).cycle(1))
        // -2 is a timeline of its own, which begins now, and takes timeline 2 with it
        assertEquals(listOf("[ 2/1 → 3/1 | 0 ]"), cycles._timeline(-2.0).cycle(2))
        assertEquals(listOf("[ 3/1 → 4/1 | 1 ]"), cycles._timeline(-2.0).cycle(3))
        // 2 again begins now, and takes -2 away
        assertEquals(listOf("[ 4/1 → 5/1 | 0 ]"), cycles._timeline(2.0).cycle(4))
        assertEquals(listOf("[ 5/1 → 6/1 | 1 ]"), cycles._timeline(2.0).cycle(5))
        assertEquals(listOf("[ 6/1 → 7/1 | 0 ]"), cycles._timeline(-2.0).cycle(6))
    }

    @Test
    fun timelineZeroIsThePatternAsItIsAndNeverMoves() {
        assertEquals(listOf("[ 5/1 → 6/1 | 1 ]"), cycles._timeline(0.0).cycle(5))
        assertEquals(listOf("[ 6/1 → 7/1 | 2 ]"), cycles._timeline(0.0).cycle(6))
    }

    @Test
    fun theTimelinesAreNamedByTheirNumberAsJavaScriptWritesIt() {
        // "2" and 2 are the same property of the object impure.mjs keeps them in
        assertEquals(listOf("[ 0/1 → 1/1 | 0 ]"), cycles._timeline(2.0).cycle(0))
        assertEquals(listOf("[ 1/1 → 2/1 | 1 ]"), cycles._timeline("2").cycle(1))
    }

    @Test
    fun resetStateAndResetTimelinesMakeEveryTimelineStartOver() {
        for (name in listOf("reset_timelines", "reset_state")) {
            val on2 = cycles._timeline(2.0)
            assertEquals(listOf("[ 0/1 → 1/1 | 0 ]"), on2.cycle(0), name)
            assertEquals(listOf("[ 1/1 → 2/1 | 1 ]"), on2.cycle(1), name)
            assertSame(Undefined, Registry.call(name, emptyList()), name)
            assertEquals(listOf("[ 2/1 → 3/1 | 0 ]"), on2.cycle(2), name)
            Timelines.reset()
        }
    }

    @Test
    fun theTimelineNumbersAreAPatternThatMayChange() {
        // 1 in the first cycle and 2 in the second: each begins where it is first seen
        val changing = cycles._timeline(slowcat(listOf(pure(1.0), pure(2.0))))
        assertEquals(listOf("[ 0/1 → 1/1 | 0 ]"), changing.cycle(0))
        assertEquals(listOf("[ 1/1 → 2/1 | 0 ]"), changing.cycle(1))
        // timeline 1 went on from cycle 0 meanwhile
        assertEquals(listOf("[ 2/1 → 3/1 | 2 ]"), changing.cycle(2))
    }

    @Test
    fun theStepsOfThePatternAreKept() {
        assertEquals(Rational(3), fastcat(listOf(pure("a"), pure("b"), pure("c")))._timeline(1.0).steps)
    }
}
