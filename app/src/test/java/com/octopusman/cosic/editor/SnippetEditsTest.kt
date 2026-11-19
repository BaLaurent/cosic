package com.octopusman.cosic.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertEquals

class SnippetEditsTest {
    private fun at(text: String, cursor: Int) = TextFieldValue(text, TextRange(cursor))

    @Test
    fun snippetGoesOnAnEmptyLineAsIs() {
        assertEquals(at("a\nfast(2)", 9), at("a\n", 2).withSnippet("fast(2)"))
    }

    @Test
    fun snippetInAnEmptyTextIsJustTheSnippet() {
        assertEquals(at("s(\"bd\")", 7), at("", 0).withSnippet("s(\"bd\")"))
    }

    @Test
    fun snippetAfterCodeStartsANewLine() {
        assertEquals(at("setcpm(30)\ns(\"bd\")", 18), at("setcpm(30)", 10).withSnippet("s(\"bd\")"))
    }

    @Test
    fun snippetBeforeCodeIsFollowedByANewLine() {
        assertEquals(at("s(\"bd\")\nsetcpm(30)", 7), at("setcpm(30)", 0).withSnippet("s(\"bd\")"))
    }

    @Test
    fun snippetInTheMiddleOfALineGetsALineOfItsOwn() {
        assertEquals(at("a\nx\nb", 3), at("ab", 1).withSnippet("x"))
    }

    @Test
    fun snippetReplacesTheSelection() {
        assertEquals(at("x", 1), TextFieldValue("old", TextRange(0, 3)).withSnippet("x"))
    }

    @Test
    fun anImeCompositionIsDropped() {
        val composing = TextFieldValue("ab", TextRange(2), composition = TextRange(0, 2))

        assertEquals(null, composing.withSnippet("x").composition)
    }
}
