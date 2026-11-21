package com.octopusman.cosic.editor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TextEditTest {
    @Test
    fun anInsertionInARunOfTheSameCharacterIsPlacedWhereTheCaretIs() {
        assertEquals(TextEdit(0, 0, 1), TextEdit.between("aa", "aaa", 1))
        assertEquals(TextEdit(2, 2, 3), TextEdit.between("aa", "aaa", 3))
        assertEquals(TextEdit(2, 2, 3), TextEdit.between("aa", "aaa", 99))
    }

    @Test
    fun aClosingParenthesisTypedAfterOneIsAfterIt() {
        assertEquals(TextEdit(4, 4, 5), TextEdit.between("foo()", "foo())", 5))
    }

    @Test
    fun aDeletionInARunIsWhereTheCaretIsAndSoKeepsTheAnchorAfterIt() {
        assertEquals(TextEdit(4, 5, 4), TextEdit.between("foo())", "foo()", 4))
        // a caret at the end can only have deleted the last one
        assertEquals(TextEdit(5, 6, 5), TextEdit.between("foo())", "foo()", 5))
    }

    @Test
    fun aReplacementIsTheRegionBetweenTheCommonEnds() {
        assertEquals(TextEdit(3, 5, 7), TextEdit.between("bd sd hh", "bd XXXX hh", 7))
        assertEquals(TextEdit(1, 2, 2), TextEdit.between("abc", "aXc", 2))
    }

    @Test
    fun clearingAndFillingTheText() {
        assertEquals(TextEdit(0, 5, 0), TextEdit.between("bd sd", "", 0))
        assertEquals(TextEdit(0, 0, 2), TextEdit.between("", "ab", 2))
    }

    @Test
    fun equalTextsAreNoEdit() {
        assertNull(TextEdit.between("same", "same", 2))
        assertNull(TextEdit.between("", "", 0))
    }

    @Test
    fun aPlaceIsCarriedThroughTheEdit() {
        val edit = TextEdit(7, 10, 11)
        assertEquals(listOf(5, 7, 11, 11, 13), listOf(5, 7, 8, 10, 12).map(edit::mapOffset))
        assertEquals(1, edit.delta)
    }

    @Test
    fun theEditAlwaysTurnsTheOldTextIntoTheNewOne() {
        val cases = listOf("", "a", "aa", "ab", "abab", "foo()", "foo())", "$: s(\"bd\")", "x\n\ny", "bd sd hh")
        for (old in cases) for (new in cases) for (cursor in listOf(0, new.length / 2, new.length)) {
            val edit = TextEdit.between(old, new, cursor)
            if (edit == null) {
                assertEquals(old, new)
            } else {
                assertEquals(new, old.replaceRange(edit.start, edit.oldEnd, new.substring(edit.start, edit.newEnd)), "$old -> $new at $cursor")
            }
        }
    }
}
