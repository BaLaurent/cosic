package com.octopusman.cosic.editor

import cosic.SourceRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HighlightMapperTest {
    /** The ranges as they are after the one edit that turns [old] into [new]. */
    private fun map(old: String, new: String, vararg ranges: SourceRange) = between(old, new).map(ranges.toList())

    /** A mapper for the one edit that turns [old] into [new] (the caret at the end of the new text). */
    private fun between(old: String, new: String) = HighlightMapper().also { it.onEdit(TextEdit.between(old, new, new.length)) }

    // "bd sd": bd = 0..2, sd = 3..5
    private val bd = SourceRange(0, 2)
    private val sd = SourceRange(3, 5)

    @Test
    fun unchangedTextKeepsEveryRange() {
        assertEquals(listOf(bd, sd), map("bd sd", "bd sd", bd, sd))
    }

    @Test
    fun noRangesStayNoRanges() {
        assertEquals(emptyList(), map("bd sd", "bd sd x"))
    }

    @Test
    fun editsAfterARangeKeepIt() {
        assertEquals(listOf(bd, sd), map("bd sd", "bd sd hh", bd, sd))
    }

    @Test
    fun insertionAtTheStartShiftsEveryRange() {
        assertEquals(listOf(SourceRange(2, 4), SourceRange(5, 7)), map("bd sd", "a bd sd", bd, sd))
    }

    @Test
    fun deletionBeforeARangeShiftsItBack() {
        assertEquals(listOf(SourceRange(0, 2), SourceRange(3, 5)), map("a bd sd", "bd sd", SourceRange(2, 4), SourceRange(5, 7)))
    }

    @Test
    fun insertionInsideARangeDropsIt() {
        assertEquals(listOf(bd), map("bd sd", "bd sXd", bd, sd))
    }

    @Test
    fun insertionRightAtTheStartOfARangeShiftsIt() {
        assertEquals(listOf(bd, SourceRange(4, 6)), map("bd sd", "bd Xsd", bd, sd))
    }

    @Test
    fun insertionRightAtTheEndOfARangeKeepsIt() {
        assertEquals(listOf(bd, sd), map("bd sd", "bd sdx", bd, sd))
    }

    @Test
    fun replacementDropsOnlyTheRangesInTheChangedRegion() {
        val hh = SourceRange(6, 8)
        assertEquals(listOf(bd, hh), map("bd sd hh", "bd XX hh", bd, sd, hh))
    }

    @Test
    fun replacementOfADifferentLengthShiftsTheRangesAfterIt() {
        val hh = SourceRange(6, 8)
        assertEquals(listOf(bd, SourceRange(8, 10)), map("bd sd hh", "bd XXXX hh", bd, sd, hh))
    }

    @Test
    fun theLastOfARepeatedCharacterCountsAsTheDeletedOne() {
        assertEquals(listOf(SourceRange(0, 1), SourceRange(1, 2)), map("aaa", "aa", SourceRange(0, 1), SourceRange(1, 2), SourceRange(2, 3)))
    }

    @Test
    fun clearingTheTextDropsEveryRange() {
        assertEquals(emptyList(), map("bd sd", "", bd, sd))
    }

    @Test
    fun editsFarApartKeepWhatIsBetweenThem() {
        // two sliders dragged, one in each of two lines: the step between them still sounds and is still marked
        val old = "a(slider(0.5))\nb(\"bd sd\")\nc(slider(0.25))"
        assertEquals("0.5", old.substring(9, 12))
        assertEquals("bd", old.substring(18, 20))
        assertEquals("0.25", old.substring(35, 39))
        val mapper = HighlightMapper()
        mapper.onEdit(TextEdit(9, 12, 15)) // 0.5 -> 0.5001
        mapper.onEdit(TextEdit(38, 42, 41)) // 0.25 (now at 38..42) -> 0.2
        assertEquals(SourceRange(21, 23), mapper.map(SourceRange(18, 20)))
        // a single changed region from the first edit to the second would have dropped it
        assertEquals(null, between(old, "a(slider(0.5001))\nb(\"bd sd\")\nc(slider(0.2))").map(SourceRange(18, 20)))
    }

    @Test
    fun theEditsAreCarriedOneAfterTheOther() {
        val mapper = HighlightMapper()
        mapper.onEdit(TextEdit(0, 0, 3)) // three characters typed at the start: bd is at 3..5
        mapper.onEdit(TextEdit(4, 4, 5)) // one typed inside it
        assertNull(mapper.map(bd))

        val kept = HighlightMapper()
        kept.onEdit(TextEdit(0, 0, 3)) // sd is at 6..8
        kept.onEdit(TextEdit(2, 3, 2)) // one of the typed characters deleted again
        assertEquals(SourceRange(5, 7), kept.map(sd))

        val atTheEnd = HighlightMapper()
        atTheEnd.onEdit(TextEdit(0, 0, 3))
        atTheEnd.onEdit(TextEdit(5, 5, 6)) // typed right behind bd, which is at 3..5
        assertEquals(SourceRange(3, 5), atTheEnd.map(bd))
    }

    @Test
    fun noEditIsNoChange() {
        val mapper = HighlightMapper()
        mapper.onEdit(null)
        assertEquals(listOf(bd, sd), mapper.map(listOf(bd, sd)))
        assertEquals(false, mapper.hasEdits)
        mapper.onEdit(TextEdit(0, 0, 1))
        assertEquals(true, mapper.hasEdits)
    }
}
