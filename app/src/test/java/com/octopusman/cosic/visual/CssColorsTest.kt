package com.octopusman.cosic.visual

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CssColorsTest {
    private fun argb(text: String): Int? = parseCssColor(text)?.toArgb()

    @Test
    fun namedColoursAnyCase() {
        assertEquals(0xFFFF0000.toInt(), argb("red"))
        assertEquals(0xFF663399.toInt(), argb("RebeccaPurple"))
        assertEquals(0xFFF0F8FF.toInt(), argb("  aliceblue "))
        assertEquals(0, argb("transparent"))
    }

    @Test
    fun hexForms() {
        assertEquals(0xFFFFFFFF.toInt(), argb("#fff"))
        assertEquals(0xFF112233.toInt(), argb("#123"))
        assertEquals(0x88112233.toInt(), argb("#1238"))
        assertEquals(0xFFA1B2C3.toInt(), argb("#a1b2c3"))
        assertEquals(0x66ABCDEF.toInt(), argb("#abcdef66"))
        assertEquals(0x6600FF00, argb("#00ff0066"))
    }

    @Test
    fun rgbAndHslForms() {
        assertEquals(0xFF0A141E.toInt(), argb("rgb(10, 20, 30)"))
        assertEquals(0x800A141E.toInt(), argb("rgba(10,20,30,0.5)"))
        assertEquals(0x800A141E.toInt(), argb("rgb(10 20 30 / 50%)"))
        assertEquals(0xFFFF0000.toInt(), argb("rgb(100%, 0%, 0%)"))
        assertEquals(0xFFFF0000.toInt(), argb("hsl(0, 100%, 50%)"))
        assertEquals(0xFF00FF00.toInt(), argb("hsl(120, 100%, 50%)"))
        assertEquals(0xFF0000FF.toInt(), argb("hsl(240deg 100% 50%)"))
        assertEquals(0x80FF0000.toInt(), argb("hsla(0, 100%, 50%, 0.5)"))
        assertEquals(0xFFFFFFFF.toInt(), argb("hsl(10, 0%, 100%)"))
    }

    @Test
    fun nonsenseIsNull() {
        assertNull(parseCssColor(""))
        assertNull(parseCssColor("nothing"))
        assertNull(parseCssColor("#12"))
        assertNull(parseCssColor("#ggg"))
        assertNull(parseCssColor("rgb(1, 2)"))
        assertNull(parseCssColor("rgb(1, 2, x)"))
        assertNull(parseCssColor("lab(1 2 3)"))
    }

    @Test
    fun colourBackToCssHex() {
        assertEquals("#ff8000", Color(0xFFFF8000.toInt()).toCssHex())
        assertEquals("#ff800080", Color(0x80FF8000.toInt()).toCssHex())
        assertEquals("#000000", Color.Black.toCssHex())
    }
}
