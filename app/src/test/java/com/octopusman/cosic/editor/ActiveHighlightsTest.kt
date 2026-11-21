package com.octopusman.cosic.editor

import cosic.HighlightEvent
import cosic.SourceRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ActiveHighlightsTest {
    private val bd = SourceRange(0, 2)
    private val sd = SourceRange(3, 5)
    private val hh = SourceRange(6, 8)

    private fun event(start: Long, end: Long, vararg ranges: SourceRange, color: String? = null, markcss: String? = null) =
        HighlightEvent(start, end, ranges.toList(), color, markcss)

    @Test
    fun onlyTheEventsThatSoundAtThePlayheadAreActive() {
        val events = listOf(event(0, 100, bd), event(100, 200, sd), event(300, 400, hh))
        assertEquals(listOf(ActiveHighlight(sd, null, null)), activeHighlights(events, 150))
        assertEquals(emptyList(), activeHighlights(events, 250))
        assertEquals(emptyList(), activeHighlights(events, 450))
    }

    @Test
    fun bothEndsOfAnEventCount() {
        val events = listOf(event(100, 200, bd))
        assertEquals(listOf(ActiveHighlight(bd, null, null)), activeHighlights(events, 100))
        assertEquals(listOf(ActiveHighlight(bd, null, null)), activeHighlights(events, 200))
        assertEquals(emptyList(), activeHighlights(events, 99))
        assertEquals(emptyList(), activeHighlights(events, 201))
    }

    @Test
    fun aPlaceTwoEventsShareTakesTheStyleOfTheOneThatBeganLast() {
        val events = listOf(
            event(0, 300, bd, color = "red"),
            event(100, 300, bd, color = "blue", markcss = "color: blue"),
            event(50, 300, bd, color = "green"),
        )
        assertEquals(listOf(ActiveHighlight(bd, "blue", "color: blue")), activeHighlights(events, 200))
    }

    @Test
    fun anEqualStartKeepsTheFirstEvent() {
        val events = listOf(event(100, 300, bd, color = "red"), event(100, 300, bd, color = "blue"))
        assertEquals(listOf(ActiveHighlight(bd, "red", null)), activeHighlights(events, 200))
    }

    @Test
    fun oneEventMarksEveryPlaceItHas() {
        val events = listOf(event(0, 100, bd, hh, color = "tomato"))
        assertEquals(listOf(ActiveHighlight(bd, "tomato", null), ActiveHighlight(hh, "tomato", null)), activeHighlights(events, 50))
    }

    @Test
    fun placesComeInTheOrderTheyFirstAppear() {
        val events = listOf(event(0, 100, sd), event(0, 100, bd), event(10, 100, sd, color = "later"))
        assertEquals(listOf(sd, bd), activeHighlights(events, 50).map { it.range })
        assertEquals("later", activeHighlights(events, 50).first().color)
    }

    @Test
    fun noEventsMeanNoHighlights() {
        assertTrue(activeHighlights(emptyList(), 10).isEmpty())
    }

    @Test
    fun placesAreWhereTheTextHasThemNow() {
        val events = listOf(event(0, 100, bd, color = "red"), event(0, 100, sd, color = "blue", markcss = "color: blue"))
        val shifted = activeHighlights(events, 50) { _, range -> SourceRange(range.start + 2, range.end + 2) }
        assertEquals(listOf(ActiveHighlight(SourceRange(2, 4), "red", null), ActiveHighlight(SourceRange(5, 7), "blue", "color: blue")), shifted)
    }

    @Test
    fun aPlaceThatIsGoneHasNoMark() {
        val events = listOf(event(0, 100, bd, color = "red"), event(0, 100, sd, color = "blue"))
        assertEquals(listOf(ActiveHighlight(bd, "red", null)), activeHighlights(events, 50) { _, range -> range.takeIf { it == bd } })
    }

    @Test
    fun eventsOfTwoProgramsShareAPlaceWhenTheyAreInTheSamePlaceOfTheTextNow() {
        val old = event(0, 100, SourceRange(0, 2), color = "old")
        val new = event(50, 100, SourceRange(4, 6), color = "new")
        // the old program's text had two characters less in front: its (0,2) is the new text's (2,4)... and the new program's (4,6) is (4,6)
        val place = { e: HighlightEvent, r: SourceRange -> if (e === old) SourceRange(r.start + 2, r.end + 2) else r }
        assertEquals(listOf(ActiveHighlight(SourceRange(2, 4), "old", null), ActiveHighlight(SourceRange(4, 6), "new", null)), activeHighlights(listOf(old, new), 60, place))
        // the same place after the mapping: the one that began last wins
        val same = { _: HighlightEvent, _: SourceRange -> SourceRange(1, 2) }
        assertEquals(listOf(ActiveHighlight(SourceRange(1, 2), "new", null)), activeHighlights(listOf(old, new), 60, same))
    }
}
