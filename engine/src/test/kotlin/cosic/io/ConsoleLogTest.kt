package cosic.io

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The console keeps what the web REPL keeps: 40 lines, repeats counted, bursts of one message dropped (`logger.mjs`, `useLogger.jsx`). */
class ConsoleLogTest {
    private var now = 0L
    private val log = ConsoleLog { now }

    private fun messages() = log.entries.map { it.message }

    @Test
    fun theSameMessageTwiceWithinASecondIsOneLine() {
        log.log("a")
        now = 999
        log.log("a")
        assertEquals(listOf("a"), messages())
        assertEquals(1, log.entries.single().count)
    }

    @Test
    fun theSameMessageAfterASecondRaisesTheCountOfTheLastLine() {
        log.log("a")
        now = 1500
        log.log("a")
        val line = log.entries.single()
        assertEquals("a", line.message)
        assertEquals(2, line.count)
        now = 3000
        log.log("a")
        assertEquals(3, log.entries.single().count)
    }

    @Test
    fun aDroppedRepeatDoesNotStartTheSecondAgain() {
        log.log("a")
        now = 900
        log.log("a") // dropped, and it does not move the time the debounce counts from
        now = 1500
        log.log("a")
        assertEquals(2, log.entries.single().count)
    }

    @Test
    fun aMessageThatRepeatsAnEarlierOneButNotTheLastIsANewLine() {
        log.log("a")
        now = 10
        log.log("b")
        now = 20
        log.log("a")
        assertEquals(listOf("a", "b", "a"), messages())
        assertEquals(listOf(1, 1, 1), log.entries.map { it.count })
    }

    @Test
    fun onlyTheLastFortyLinesAreKept() {
        for (i in 0 until 45) {
            now = i * 10L
            log.log("line $i")
        }
        assertEquals(40, log.entries.size)
        assertEquals("line 5", log.entries.first().message)
        assertEquals("line 44", log.entries.last().message)
    }

    @Test
    fun everyChangeOfALineGivesItANewId() {
        log.log("a")
        val first = log.entries.single().id
        now = 2000
        log.log("a")
        val second = log.entries.single().id
        now = 2010
        log.log("b")
        assertEquals(3, setOf(first, second, log.entries.last().id).size)
    }

    @Test
    fun typeAndColorAreKept() {
        log.log("careful", "warning", "red")
        val line = log.entries.single()
        assertEquals("warning", line.type)
        assertEquals("red", line.color)
        now = 5
        log.log("plain")
        assertNull(log.entries.last().type)
        assertNull(log.entries.last().color)
    }

    @Test
    fun anErrorIsWrittenWithItsOriginAsAnErrorLine() {
        log.error("getSchedule", IllegalStateException("boom"))
        val line = log.entries.single()
        assertEquals("[getSchedule] error: boom", line.message)
        assertEquals("error", line.type)
    }

    @Test
    fun theListOfLinesIsASnapshotThatDoesNotChange() {
        log.log("a")
        val before = log.entries
        now = 10
        log.log("b")
        assertEquals(listOf("a"), before.map { it.message })
        assertEquals(listOf("a", "b"), messages())
    }

    @Test
    fun thereAreNoLinesAtFirst() {
        assertEquals(emptyList(), log.entries)
    }
}
