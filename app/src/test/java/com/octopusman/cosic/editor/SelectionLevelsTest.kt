package com.octopusman.cosic.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertEquals

class SelectionLevelsTest {
    private fun at(text: String, cursor: Int) = TextFieldValue(text, TextRange(cursor))

    private fun selected(text: String, start: Int, end: Int) = TextFieldValue(text, TextRange(start, end))

    /** The selections [expandSelection] visits from a cursor at [cursor], until it stops. */
    private fun climb(text: String, cursor: Int): List<Pair<Int, Int>> {
        val steps = mutableListOf<Pair<Int, Int>>()
        var value = at(text, cursor)
        while (true) {
            val next = value.expandSelection()
            if (next == value) return steps
            steps += next.selection.min to next.selection.max
            value = next
        }
    }

    @Test
    fun expandClimbsFromAWordToTheCall() {
        assertEquals(listOf(2 to 4, 2 to 7, 0 to 8), climb("s(bd sd)", 3))
    }

    @Test
    fun expandClimbsInsideAString() {
        // "bd" · contents · the string with its quotes · the call
        assertEquals(listOf(3 to 5, 3 to 8, 2 to 9, 0 to 10), climb("s(\"bd sd\")", 4))
    }

    @Test
    fun expandStopsAtTheArgument() {
        // f(a b, c): a · the argument "a b" · the contents of the parentheses · the call
        assertEquals(listOf(2 to 3, 2 to 5, 2 to 8, 0 to 9), climb("f(a b, c)", 2))
    }

    @Test
    fun aCommaInAStringDoesNotSplitTheArguments() {
        assertEquals(listOf(3 to 4, 3 to 6, 2 to 7, 2 to 10, 0 to 11), climb("f(\"a,b\", c)", 3))
    }

    @Test
    fun expandReachesTheLineThenTheWholeText() {
        assertEquals(listOf(4 to 5, 4 to 7, 0 to 7), climb("a b\nc d", 5))
    }

    @Test
    fun wrappingTheExpandedArgumentGivesTheQuotedString() {
        val expanded = at("s(bd sd)", 3).expandSelection().expandSelection()
        assertEquals(selected("s(bd sd)", 2, 7), expanded)
        assertEquals(at("s(\"bd sd\")", 9), expanded.withSymbol("\""))
    }

    @Test
    fun shrinkWalksBackDownTheLevelsExpandVisited() {
        val text = "s(bd sd)"
        val ladder = SelectionLadder()
        var value = ladder.expand(at(text, 3))
        value = ladder.expand(value)
        assertEquals(selected(text, 2, 7), value)
        value = ladder.shrink(value)
        assertEquals(selected(text, 2, 4), value)
        value = ladder.shrink(value)
        assertEquals(at(text, 3), value)
        assertEquals(value, ladder.shrink(value))
    }

    @Test
    fun shrinkDoesNothingOnASelectionTheLadderDidNotMake() {
        val ladder = SelectionLadder()
        ladder.expand(at("s(bd sd)", 3))
        assertEquals(selected("s(bd sd)", 0, 1), ladder.shrink(selected("s(bd sd)", 0, 1)))
    }
}
