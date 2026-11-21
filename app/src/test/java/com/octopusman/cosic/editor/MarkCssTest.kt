package com.octopusman.cosic.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MarkCssTest {
    @Test
    fun anOutlineHasItsColourAndWidth() {
        assertEquals(MarkOutline(Color(0xFFFF0000), 2f), parseMarkCss("outline: solid 2px red").outline)
        assertNull(parseMarkCss("outline: solid 2px red").span)
    }

    @Test
    fun theWordsOfAnOutlineComeInAnyOrder() {
        assertEquals(MarkOutline(Color(0xFFFFFF00), 3f), parseMarkCss("outline: 3px solid #ff0").outline)
        assertEquals(MarkOutline(Color(0xFF00FF00), 1f, OutlineStyle.DASHED), parseMarkCss("outline: #0f0 dashed 1px").outline)
    }

    @Test
    fun aColourWithSpacesInItIsOneWord() {
        assertEquals(Color(0xFF0000FF), parseMarkCss("outline: solid 2px rgb(0, 0, 255)").outline?.color)
    }

    @Test
    fun anOutlineWithoutAStyleOrWithNoneIsNoOutline() {
        assertNull(parseMarkCss("outline: 2px red").outline)
        assertNull(parseMarkCss("outline: none").outline)
        assertNull(parseMarkCss("outline: solid 2px red; outline: none").outline)
    }

    @Test
    fun anOutlineWithoutAColourIsInTheColourOfTheText() {
        assertEquals(MarkOutline(null, 3f), parseMarkCss("outline: solid").outline)
    }

    @Test
    fun aTextDecorationStylesTheText() {
        val style = parseMarkCss("text-decoration: underline")
        assertEquals(TextDecoration.Underline, style.span?.textDecoration)
        assertNull(style.outline)
        assertEquals(TextDecoration.LineThrough, parseMarkCss("text-decoration: line-through").span?.textDecoration)
        assertEquals(TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough)), parseMarkCss("text-decoration: underline line-through").span?.textDecoration)
    }

    @Test
    fun severalDeclarationsMakeOneStyle() {
        val style = parseMarkCss("color:#0f0;font-weight:bold")
        assertEquals(SpanStyle(color = Color(0xFF00FF00), fontWeight = FontWeight.Bold), style.span)
        assertNull(style.outline)
    }

    @Test
    fun backgroundsWeightsAndSlantAreUnderstood() {
        assertEquals(Color(0xFFFFFF00), parseMarkCss("background: yellow").span?.background)
        assertEquals(Color(0xFFFF0000), parseMarkCss("background-color: #f00").span?.background)
        assertEquals(FontWeight(700), parseMarkCss("font-weight: 700").span?.fontWeight)
        assertEquals(FontWeight.Normal, parseMarkCss("font-weight: normal").span?.fontWeight)
        assertNull(parseMarkCss("font-weight: 950").span)
        assertEquals(FontStyle.Italic, parseMarkCss("font-style: italic").span?.fontStyle)
    }

    @Test
    fun lengthsAreTextSizes() {
        assertEquals(2.sp, parseMarkCss("letter-spacing: 2px").span?.letterSpacing)
        assertEquals(0.5.em, parseMarkCss("letter-spacing: 0.5em").span?.letterSpacing)
        assertEquals(20.sp, parseMarkCss("font-size: 20px").span?.fontSize)
        assertNull(parseMarkCss("font-size: large").span)
    }

    @Test
    fun whatIsNotUnderstoodIsIgnored() {
        assertEquals(MarkStyle(null, null), parseMarkCss("bogus:1"))
        assertEquals(MarkStyle(null, null), parseMarkCss(""))
        assertEquals(MarkStyle(null, null), parseMarkCss("color"))
        assertEquals(MarkStyle(null, null), parseMarkCss("color: notacolour"))
        // a declaration that is ignored does not hide the next one
        assertEquals(Color(0xFFFF0000), parseMarkCss("bogus: 1; color: red").span?.color)
    }

    @Test
    fun aMarkWithoutMarkcssIsAnOutlineOf2pxInItsColour() {
        val styles = MarkStyles()
        val white = Color.White
        assertEquals(MarkStyle(null, MarkOutline(white, 2f)), styleOf(ActiveHighlight(cosic.SourceRange(0, 2), null, null), white, styles))
        assertEquals(MarkStyle(null, MarkOutline(Color(0xFFFF6347), 2f)), styleOf(ActiveHighlight(cosic.SourceRange(0, 2), "tomato", null), white, styles))
        // a colour that is none: the foreground
        assertEquals(MarkStyle(null, MarkOutline(white, 2f)), styleOf(ActiveHighlight(cosic.SourceRange(0, 2), "nonsense", null), white, styles))
        // an empty markcss is no markcss
        assertEquals(MarkStyle(null, MarkOutline(white, 2f)), styleOf(ActiveHighlight(cosic.SourceRange(0, 2), null, ""), white, styles))
    }

    @Test
    fun markcssReplacesTheWholeDefaultStyle() {
        val styles = MarkStyles()
        val highlight = ActiveHighlight(cosic.SourceRange(0, 2), "tomato", "color: red")
        val style = styleOf(highlight, Color.White, styles)
        assertNull(style.outline) // no outline in the markcss: no outline at all
        assertNotNull(style.span)
        assertEquals(listOf(MarkSpan(highlight.range, SpanStyle(color = Color(0xFFFF0000)))), textStyledHighlights(listOf(highlight), styles))
        // an outline-only markcss styles no text
        assertEquals(emptyList(), textStyledHighlights(listOf(highlight.copy(markcss = "outline: solid 1px red")), styles))
    }
}
