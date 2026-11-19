package com.octopusman.cosic.editor

import cosic.SourceRange
import kotlin.test.Test
import kotlin.test.assertEquals

class HighlightMapperTest {
    private fun map(old: String, new: String, vararg ranges: SourceRange) =
        HighlightMapper(old).map(ranges.toList(), new)

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
    fun prefixAndSuffixNeverOverlapOnRepeatedCharacters() {
        // The last "a" counts as the deleted one.
        assertEquals(listOf(SourceRange(0, 1), SourceRange(1, 2)), map("aaa", "aa", SourceRange(0, 1), SourceRange(1, 2), SourceRange(2, 3)))
    }

    @Test
    fun clearingTheTextDropsEveryRange() {
        assertEquals(emptyList(), map("bd sd", "", bd, sd))
    }

    @Test
    fun theSameMapperServesLaterTextsToo() {
        val mapper = HighlightMapper("bd sd")

        assertEquals(listOf(SourceRange(4, 6)), mapper.map(listOf(bd), "// \nbd sd"))
        assertEquals(listOf(bd), mapper.map(listOf(bd), "bd sd hh"))
    }
}
