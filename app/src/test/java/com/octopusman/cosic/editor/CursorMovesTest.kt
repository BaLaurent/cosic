package com.octopusman.cosic.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertEquals

class CursorMovesTest {
    private fun at(text: String, cursor: Int) = TextFieldValue(text, TextRange(cursor))

    private fun selected(text: String, start: Int, end: Int) = TextFieldValue(text, TextRange(start, end))

    // offsets: s0 (1 "2 b3 d4 ' '5 s6 d7 "8 )9, length 10; stops 0 1 2 3 5 6 8 9 10
    private val call = "s(\"bd sd\")"

    @Test
    fun charMoveStopsAtTheEnds() {
        assertEquals(at("ab", 0), at("ab", 0).moveCursor(-1))
        assertEquals(at("ab", 2), at("ab", 2).moveCursor(1))
        assertEquals(at("ab", 1), at("ab", 0).moveCursor(1))
        assertEquals(at("ab", 1), at("ab", 2).moveCursor(-1))
    }

    @Test
    fun charMoveCollapsesASelectionAtItsEdge() {
        assertEquals(at("abcd", 1), selected("abcd", 1, 3).moveCursor(-1))
        assertEquals(at("abcd", 3), selected("abcd", 1, 3).moveCursor(1))
        assertEquals(at("abcd", 3), selected("abcd", 3, 1).moveCursor(1))
    }

    @Test
    fun jumpStopsBetweenTheParenAndTheString() {
        assertEquals(at(call, 3), at(call, 2).jumpToken(1))
        assertEquals(at(call, 1), at(call, 2).jumpToken(-1))
    }

    @Test
    fun jumpStopsAtTheEdgesOfEachWordInsideAString() {
        assertEquals(at(call, 5), at(call, 3).jumpToken(1))
        assertEquals(at(call, 6), at(call, 5).jumpToken(1))
        assertEquals(at(call, 8), at(call, 6).jumpToken(1))
        assertEquals(at(call, 9), at(call, 8).jumpToken(1))
        assertEquals(at(call, 6), at(call, 8).jumpToken(-1))
        assertEquals(at(call, 2), at(call, 3).jumpToken(-1))
    }

    @Test
    fun jumpStopsRightAfterAWordFollowedByAnOperatorInAString() {
        // "bd*2": stops 0 1 3 4 5 6; from after the quote, the next stop is between bd and *
        assertEquals(at("\"bd*2\"", 3), at("\"bd*2\"", 1).jumpToken(1))
    }

    @Test
    fun jumpStaysAtTheEndsOfTheText() {
        assertEquals(at(call, 10), at(call, 10).jumpToken(1))
        assertEquals(at(call, 0), at(call, 0).jumpToken(-1))
    }

    @Test
    fun jumpStartsFromTheEdgeOfASelection() {
        assertEquals(at(call, 9), selected(call, 3, 8).jumpToken(1))
        assertEquals(at(call, 2), selected(call, 3, 8).jumpToken(-1))
    }
}
