package com.octopusman.cosic.editor

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Rows of [row] px and characters of [cell] px, where a line starts after every line break, or where [starts] says. */
private class FakeRows(
    private val shown: String,
    private val cell: Float = 10f,
    private val row: Float = 20f,
    starts: List<Int>? = null,
) : RowGeometry {
    private val lineStarts = starts ?: (listOf(0) + shown.indices.filter { shown[it] == '\n' }.map { it + 1 })

    override val textLength get() = shown.length
    override val lineCount get() = lineStarts.size

    override fun lineForOffset(offset: Int) = lineStarts.indexOfLast { it <= offset }.coerceAtLeast(0)

    override fun lineTop(line: Int) = line * row

    override fun lineBottom(line: Int) = (line + 1) * row

    override fun lineLeft(line: Int) = 0f

    override fun lineRight(line: Int): Float {
        val end = if (line + 1 < lineStarts.size) lineStarts[line + 1] - 1 else shown.length
        return (end - lineStarts[line]) * cell
    }

    override fun charLeft(offset: Int) = (offset - lineStarts[lineForOffset(offset)]) * cell

    override fun charRight(offset: Int) = charLeft(offset) + cell
}

class PlacementTest {
    private val rows3 = InsertionMap.of(5, listOf(Insertion(2, "\n\n\n\n", false, "b")))
    private val shown3 = rows3.apply("ab\ncd")

    @Test
    fun aBlockStartsUnderTheLineOfItsCall() {
        assertEquals("ab\n\n\n\n\ncd", shown3)
        assertEquals(Rect(12f, 28f, 312f, 88f), blockRect(rows3, 0, FakeRows(shown3), Size(300f, 60f), 12f, 8f))
    }

    @Test
    fun aTallBlockMayTakeMoreRowsThanTheNaturalOnes() {
        val map = InsertionMap.of(5, listOf(Insertion(2, "\n".repeat(15), false, "b")))
        val rect = blockRect(map, 0, FakeRows(map.apply("ab\ncd")), Size(336f, 275f), 12f, 8f)
        assertEquals(Rect(12f, 28f, 348f, 303f), rect) // the room is 14 rows = 280, the widget 275
    }

    @Test
    fun aBlockIsNoTallerThanTheRoomItHas() {
        // rows of 15 px: three rows are 45 px, the widget wants 60
        assertEquals(Rect(12f, 23f, 312f, 68f), blockRect(rows3, 0, FakeRows(shown3, row = 15f), Size(300f, 60f), 12f, 8f))
    }

    @Test
    fun aBlockWhoseRowsAreNotInTheLayoutHasNoRect() {
        assertNull(blockRect(rows3, 0, FakeRows("ab\n"), Size(300f, 60f), 12f, 8f))
        assertNull(blockRect(rows3, 5, FakeRows(shown3), Size(300f, 60f), 12f, 8f))
        // a layout that has fewer lines than the text says
        assertNull(blockRect(rows3, 0, FakeRows(shown3, starts = listOf(0, 3, 4)), Size(300f, 60f), 12f, 8f))
    }

    private val gap = "\u00A0".repeat(9)
    private val sliderMap = InsertionMap.of(11, listOf(Insertion(7, gap, true, "s")))
    private val sliderShown = "slider(" + gap + "0.5)"

    @Test
    fun aSliderFillsItsGapAndIsCentredOnTheRow() {
        assertEquals(Rect(82f, 6f, 168f, 30f), sliderRect(sliderMap, 0, FakeRows(sliderShown), 24f, 4f, 24f, 12f, 8f))
    }

    @Test
    fun aGapCutByALineBreakHoldsTheSliderOnTheRowOfTheNumber() {
        // the row of the number starts 5 characters into the gap: 4 spaces of it are on that row, 40 px, 36 px of them without the margin
        assertEquals(Rect(12f, 26f, 48f, 50f), sliderRect(sliderMap, 0, FakeRows(sliderShown, starts = listOf(0, 12)), 24f, 4f, 24f, 12f, 8f))
    }

    @Test
    fun aGapCutByALineBreakThatLeavesTooLittleOnTheRowOfTheNumberHasNoSlider() {
        // only the last two spaces are on the row of the number
        assertNull(sliderRect(sliderMap, 0, FakeRows(sliderShown, starts = listOf(0, 14)), 24f, 4f, 24f, 12f, 8f))
    }

    @Test
    fun aGapThatIsTooNarrowHasNoSlider() {
        assertNull(sliderRect(sliderMap, 0, FakeRows(sliderShown), 24f, 70f, 24f, 12f, 8f))
        assertNull(sliderRect(sliderMap, 3, FakeRows(sliderShown), 24f, 4f, 24f, 12f, 8f))
    }

    @Test
    fun onlyTheWidgetsNearTheViewportAreVisible() {
        val rects = mapOf("near" to Rect(0f, 100f, 300f, 160f), "far" to Rect(0f, 2000f, 300f, 2060f))
        assertEquals(setOf("near"), visibleIds(rects, 0f, 800f, 400f))
        assertEquals(setOf("far"), visibleIds(rects, 1500f, 800f, 400f))
        assertEquals(setOf("near", "far"), visibleIds(rects, 1000f, 800f, 950f))
        assertEquals(emptySet(), visibleIds(emptyMap(), 0f, 800f, 400f))
    }

    @Test
    fun aMarkOnOneRowIsOneBox() {
        // "bd sd hh": sd is at 3..5
        assertEquals(listOf(Rect(30f, 0f, 50f, 20f)), highlightRects(FakeRows("bd sd hh"), 3, 5))
    }

    @Test
    fun aMarkOnTwoRowsIsABoxPerRow() {
        // "abcd\nefgh": the mark runs from c to f
        assertEquals(listOf(Rect(20f, 0f, 40f, 20f), Rect(0f, 20f, 20f, 40f)), highlightRects(FakeRows("abcd\nefgh"), 2, 7))
    }

    @Test
    fun aMarkOverARowInTheMiddleTakesItWhole() {
        // "ab\ncdef\ngh": from b to g
        assertEquals(
            listOf(Rect(10f, 0f, 20f, 20f), Rect(0f, 20f, 40f, 40f), Rect(0f, 40f, 10f, 60f)),
            highlightRects(FakeRows("ab\ncdef\ngh"), 1, 9),
        )
    }

    @Test
    fun anEmptyOrOutOfTextMarkHasNoBox() {
        assertEquals(emptyList(), highlightRects(FakeRows("bd sd"), 3, 3))
        assertEquals(emptyList(), highlightRects(FakeRows("bd sd"), 9, 12))
        assertEquals(listOf(Rect(40f, 0f, 50f, 20f)), highlightRects(FakeRows("bd sd"), 4, 99))
    }
}
