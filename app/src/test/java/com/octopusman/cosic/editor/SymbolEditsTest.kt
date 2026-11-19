package com.octopusman.cosic.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertEquals

class SymbolEditsTest {
    private fun at(text: String, cursor: Int) = TextFieldValue(text, TextRange(cursor))

    private fun selected(text: String, start: Int, end: Int) = TextFieldValue(text, TextRange(start, end))

    @Test
    fun plainSymbolGoesAtTheCursor() {
        assertEquals(at("s.", 2), at("s", 1).withSymbol("."))
    }

    @Test
    fun plainSymbolReplacesTheSelection() {
        assertEquals(at("a_c", 2), selected("abc", 1, 2).withSymbol("_"))
    }

    @Test
    fun tabInsertsTwoSpaces() {
        assertEquals(at("a  b", 3), at("ab", 1).withSymbol("  "))
    }

    @Test
    fun everyOpeningSymbolInsertsItsPairWithTheCursorBetween() {
        assertEquals(at("s\"\"", 2), at("s", 1).withSymbol("\""))
        assertEquals(at("s()", 2), at("s", 1).withSymbol("("))
        assertEquals(at("s[]", 2), at("s", 1).withSymbol("["))
        assertEquals(at("s{}", 2), at("s", 1).withSymbol("{"))
        assertEquals(at("s<>", 2), at("s", 1).withSymbol("<"))
    }

    @Test
    fun closingSymbolsAreNotPaired() {
        assertEquals(at("s)", 2), at("s", 1).withSymbol(")"))
        assertEquals(at("s>", 2), at("s", 1).withSymbol(">"))
    }

    @Test
    fun pairWrapsTheSelectionAndLandsAfterTheClosingSymbol() {
        assertEquals(at("\"bd sd\"", 7), selected("bd sd", 0, 5).withSymbol("\""))
        assertEquals(at("x [b c] y", 7), selected("x b c y", 2, 5).withSymbol("["))
    }

    @Test
    fun aBackwardsSelectionWrapsTooAndIsConsumed() {
        assertEquals(at("<ab>", 4), selected("ab", 2, 0).withSymbol("<"))
    }

    @Test
    fun anyImeCompositionIsDropped() {
        val composing = TextFieldValue("ab", TextRange(2), composition = TextRange(0, 2))

        assertEquals(null, composing.withSymbol("!").composition)
    }
}
