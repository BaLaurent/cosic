package com.octopusman.cosic.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.octopusman.cosic.reference.InsertText
import kotlin.test.Test
import kotlin.test.assertEquals

class EntryEditsTest {
    private fun at(text: String, cursor: Int) = TextFieldValue(text, TextRange(cursor))

    @Test
    fun aNameGoesAtTheCursorInsideTheCurrentLine() {
        assertEquals(at("s(\"piano\")", 8), at("s(\"\")", 3).withEntry(InsertText("piano", 5)))
    }

    @Test
    fun aCallLeavesTheCursorBetweenItsParentheses() {
        assertEquals(at("s(\"bd\").fast()", 13), at("s(\"bd\")", 7).withEntry(InsertText(".fast()", 6)))
    }

    @Test
    fun aMethodTypedAfterADotDoesNotGetASecondDot() {
        assertEquals(at("s(\"bd\").fast()", 13), at("s(\"bd\").", 8).withEntry(InsertText(".fast()", 6)))
    }

    @Test
    fun theEntryReplacesTheSelection() {
        assertEquals(at("s(\"kick\")", 7), TextFieldValue("s(\"bd\")", TextRange(3, 5)).withEntry(InsertText("kick", 4)))
    }
}
