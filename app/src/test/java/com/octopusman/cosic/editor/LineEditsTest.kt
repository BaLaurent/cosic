package com.octopusman.cosic.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertEquals

class LineEditsTest {
    private fun at(text: String, cursor: Int) = TextFieldValue(text, TextRange(cursor))

    private fun selected(text: String, start: Int, end: Int) = TextFieldValue(text, TextRange(start, end))

    @Test
    fun duplicateLandsTheCursorInTheCopy() {
        assertEquals(at("a\na\nb", 2), at("a\nb", 0).duplicateLines())
        assertEquals(at("a\nb\nb", 5), at("a\nb", 3).duplicateLines())
    }

    @Test
    fun duplicateRepeatsEveryTouchedLine() {
        assertEquals(selected("a\nb\na\nb", 4, 7), selected("a\nb", 0, 3).duplicateLines())
    }

    @Test
    fun aSelectionEndingAfterALineBreakDoesNotTouchTheNextLine() {
        assertEquals(selected("b\na\nc", 2, 4), selected("a\nb\nc", 0, 2).moveLinesDown())
    }

    @Test
    fun moveUpSwapsWithThePreviousLineAndKeepsTheCursorOnIt() {
        assertEquals(at("b\na", 0), at("a\nb", 2).moveLinesUp())
        assertEquals(selected("b\nc\na", 0, 3), selected("a\nb\nc", 2, 5).moveLinesUp())
    }

    @Test
    fun moveDownSwapsWithTheNextLineAndKeepsTheCursorOnIt() {
        assertEquals(at("b\na", 2), at("a\nb", 0).moveLinesDown())
    }

    @Test
    fun movesAtTheEdgesDoNothing() {
        assertEquals(at("a\nb", 0), at("a\nb", 0).moveLinesUp())
        assertEquals(at("a\nb", 3), at("a\nb", 3).moveLinesDown())
    }

    @Test
    fun deleteRemovesTheLineAndItsBreak() {
        assertEquals(at("b", 0), at("a\nb", 0).deleteLines())
        assertEquals(at("a", 1), at("a\nb", 2).deleteLines())
        assertEquals(at("", 0), at("a", 1).deleteLines())
        assertEquals(at("c", 0), selected("a\nb\nc", 0, 3).deleteLines())
    }

    @Test
    fun commentPutsTheMarkerAtTheSharedIndentAndSkipsBlankLines() {
        assertEquals(at("// a", 4), at("a", 1).toggleLineComment())
        assertEquals(selected("// a\n\n// b", 3, 10), selected("a\n\nb", 0, 4).toggleLineComment())
        assertEquals(at("  // a\n  b", 6), at("  a\n  b", 3).toggleLineComment())
    }

    @Test
    fun commentingAMixedBlockCommentsEveryLine() {
        assertEquals("// // a\n// b", selected("// a\nb", 0, 6).toggleLineComment().text)
    }

    @Test
    fun uncommentRemovesTheMarkerAndOneSpace() {
        assertEquals(at("a", 1), at("// a", 4).toggleLineComment())
        assertEquals(at("  a", 3), at("  // a", 6).toggleLineComment())
        assertEquals("a\nb", selected("// a\n//b", 0, 8).toggleLineComment().text)
    }
}
