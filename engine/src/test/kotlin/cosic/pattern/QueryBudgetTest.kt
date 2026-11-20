package cosic.pattern

import cosic.pattern.legacy.*
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** The budget that keeps one query of a pattern from building millions of spans and events. */
class QueryBudgetTest {
    private fun tooDense(block: () -> Unit) = assertEquals(TOO_DENSE, assertFailsWith<CosicError> { block() }.message)

    @Test
    fun queriesOutsideABudgetAreNotLimited() {
        assertEquals(100_000, span(r(0), r(100_000)).spanCycles().size)
    }

    @Test
    fun aSpanOfTooManyCyclesIsRefusedBeforeAnyPieceIsBuilt() {
        // Its pieces would never fit in memory, so trying to build them would end in an OutOfMemoryError.
        tooDense { withQueryBudget { span(r(0), r(Long.MAX_VALUE / 2)).spanCycles() } }
    }

    @Test
    fun theBudgetCoversEverythingOneQueryDoes() {
        withQueryBudget { repeat(4) { span(r(0), r(10_000)).spanCycles() } }
        tooDense { withQueryBudget { repeat(6) { span(r(0), r(10_000)).spanCycles() } } }
    }

    @Test
    fun everySpanCostsAtLeastOneUnitSoAnEndlessChainOfSmallQueriesRunsOut() {
        tooDense { withQueryBudget { repeat(60_000) { span(r(0), r(1, 2)).spanCycles() } } }
    }

    @Test
    fun everyBudgetStartsFull() {
        repeat(3) { withQueryBudget { span(r(0), r(40_000)).spanCycles() } }
    }

    @Test
    fun aBudgetEndsWhenItsQueryDoesEvenIfTheQueryFailed() {
        tooDense { withQueryBudget { span(r(0), r(1_000_000)).spanCycles() } }
        assertEquals(100_000, span(r(0), r(100_000)).spanCycles().size, "the limit is gone again")
    }

    @Test
    fun aBudgetInsideAnotherOneEndsWithoutLiftingTheOuterLimit() {
        tooDense {
            withQueryBudget {
                withQueryBudget { span(r(0), r(10)).spanCycles() }
                span(r(0), r(1_000_000)).spanCycles()
            }
        }
    }

    @Test
    fun splittingEventsIsChargedBeforeTheyAreBuilt() {
        val chunk = span(r(0), r(1, 16))
        // 625 events, each split into another 10 000 pieces
        tooDense { withQueryBudget { pure("a").ply(10_000).ply(10_000).query(chunk) } }
        assertEquals(625 * 10, withQueryBudget { pure("a").ply(10_000).ply(10).query(chunk) }.size)
    }

    @Test
    fun theBudgetBelongsToTheThreadThatOpenedIt() {
        var elsewhere: Int? = null
        withQueryBudget {
            span(r(0), r(40_000)).spanCycles()
            // another thread has no budget, and does not use up this one
            thread { elsewhere = span(r(0), r(100_000)).spanCycles().size }.join()
            span(r(0), r(5_000)).spanCycles()
        }
        assertEquals(100_000, elsewhere)
    }

    @Test
    fun runningOutOfMemoryOrStackIsReportedAsAnErrorOfTheProgram() {
        tooDense { reportingExhaustion { throw OutOfMemoryError() } }
        assertEquals(TOO_DEEP, assertFailsWith<CosicError> { reportingExhaustion { throw StackOverflowError() } }.message)
        assertNull(assertFailsWith<CosicError> { reportingExhaustion { throw OutOfMemoryError() } }.range)
        assertEquals(7, reportingExhaustion { 7 })
    }
}
