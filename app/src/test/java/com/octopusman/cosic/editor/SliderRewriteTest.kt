package com.octopusman.cosic.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import cosic.script.tokenize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SliderRewriteTest {
    private fun end(text: String, start: Int) = literalEnd(text, tokenize(text), start)

    @Test
    fun theLiteralEndsWhereItsNumberEnds() {
        assertEquals(26, end("$: s(\"bd\").gain(slider(0.5, 0, 1))", 23))
    }

    @Test
    fun aSignAndTheSpacesAfterItAreSkipped() {
        assertEquals(11, end("slider(-0.5, -1, 1)", 7))
        assertEquals(12, end("slider(- 0.5)", 7))
        assertEquals(11, end("slider(+1.5)", 7))
    }

    @Test
    fun otherNumberFormsAreNumbersToo() {
        assertEquals(9, end("slider(.5)", 7))
        assertEquals(10, end("slider(1e3, 0, 1e4)", 7))
        assertEquals(11, end("slider(0x10)", 7))
    }

    @Test
    fun noNumberHereMeansNoLiteral() {
        assertNull(end("slider(x, 0, 1)", 7))
        // inside a number
        assertNull(end("$: s(\"bd\").gain(slider(0.5, 0, 1))", 24))
        assertNull(end("slider(1)", 99))
        assertNull(end("slider(1)", -1))
        assertNull(end("slider(-", 7))
    }

    @Test
    fun aNumberInsideAStringIsNoLiteral() {
        assertNull(end("slider(\"0.5\")", 8))
    }

    @Test
    fun replacingTheLiteralMakesTheEditAndMovesTheCaret() {
        val value = TextFieldValue("slider(0.5, 0, 1)", TextRange(17))
        val rewrite = replaceLiteral(value, 7, 3, "0.75")
        assertEquals("slider(0.75, 0, 1)", rewrite.value.text)
        assertEquals(TextEdit(7, 10, 11), rewrite.edit)
        assertEquals(TextRange(18), rewrite.value.selection)
        // a caret inside the number goes behind the new one
        assertEquals(TextRange(11), replaceLiteral(value.copy(selection = TextRange(8)), 7, 3, "0.75").value.selection)
        // a caret in front of it stays
        assertEquals(TextRange(3), replaceLiteral(value.copy(selection = TextRange(3)), 7, 3, "0.75").value.selection)
    }

    @Test
    fun aShorterNumberPullsTheTextAfterItBack() {
        val rewrite = replaceLiteral(TextFieldValue("slider(0.123, 0, 1)", TextRange(19)), 7, 5, "1")
        assertEquals("slider(1, 0, 1)", rewrite.value.text)
        assertEquals(TextRange(15), rewrite.value.selection)
    }
}
