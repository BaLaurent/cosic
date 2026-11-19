package cosic.player

import cosic.HighlightEvent
import cosic.SourceRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class HighlightLogTest {
    private fun event(start: Long, end: Long = start + 100) = HighlightEvent(start, end, listOf(SourceRange(0, 1)))

    private fun HighlightLog.starts() = snapshot().map { it.startFrame }

    @Test
    fun keepsEventsInTheOrderTheyWereAdded() {
        val log = HighlightLog()
        log.add(event(30))
        log.add(event(10))
        log.add(event(20))
        assertEquals(listOf(30L, 10L, 20L), log.starts())
    }

    @Test
    fun expireDropsEventsThatEndedAtOrBeforeTheCutoff() {
        val log = HighlightLog()
        log.add(event(0, end = 100))
        log.add(event(50, end = 500))
        log.add(event(60, end = 101))
        log.expire(100)
        assertEquals(listOf(50L, 60L), log.starts(), "an event ending exactly at the cutoff is gone, one ending after it stays")
    }

    @Test
    fun expireKeepsLongEventsThatStartedEarlyEvenWhenLaterOnesAreGone() {
        val log = HighlightLog()
        log.add(event(0, end = 1000))
        log.add(event(10, end = 20))
        log.expire(500)
        assertEquals(listOf(0L), log.starts())
    }

    @Test
    fun holdsAtMostFiveHundredAndTwelveEventsAndDropsTheOldest() {
        val log = HighlightLog()
        for (i in 0 until 600) log.add(event(i.toLong()))
        val starts = log.starts()
        assertEquals(512, starts.size)
        assertEquals(88L, starts.first())
        assertEquals(599L, starts.last())
    }

    @Test
    fun clearEmptiesTheLog() {
        val log = HighlightLog()
        log.add(event(1))
        log.clear()
        assertTrue(log.snapshot().isEmpty())
    }

    @Test
    fun theSnapshotIsSharedWhileNothingChangesAndNeverMutatesAfterwards() {
        val log = HighlightLog()
        log.add(event(1))
        val first = log.snapshot()
        assertSame(first, log.snapshot())
        log.expire(0) // nothing ends that early
        assertSame(first, log.snapshot(), "an expire that removes nothing is not a change")
        log.add(event(2))
        assertNotSame(first, log.snapshot())
        assertEquals(listOf(1L), first.map { it.startFrame }, "a published snapshot stays as it was")
    }
}
