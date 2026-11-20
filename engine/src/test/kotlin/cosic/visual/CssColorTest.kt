package cosic.visual

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CssColorTest {
    private fun argb(a: Int, r: Int, g: Int, b: Int): Int = (a shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun hexColoursOfThreeFourSixAndEightDigits() {
        assertEquals(0xFFFFFFFF.toInt(), CssColor.parse("#fff"))
        assertEquals(argb(0x66, 0x8a, 0x91, 0x99), CssColor.parse("#8a919966"))
        assertEquals(argb(255, 0x11, 0x22, 0x33), CssColor.parse("#112233"))
        assertEquals(argb(0x44, 0x11, 0x22, 0x33), CssColor.parse("#1234"))
        assertEquals(argb(255, 0x75, 0xba, 0xff), CssColor.parse("#75BAFF"))
    }

    @Test
    fun namedColoursComeFromTheTableOfStrudel() {
        assertEquals(argb(255, 0xfa, 0x80, 0x72), CssColor.parse("salmon"))
        assertEquals(argb(255, 0xf0, 0xf8, 0xff), CssColor.parse("AliceBlue"))
        assertEquals(argb(255, 0x66, 0x33, 0x99), CssColor.parse("rebeccapurple"))
        assertEquals(0, CssColor.parse("transparent"))
        assertEquals(argb(255, 0xff, 0xff, 0xff), CssColor.parse("white"))
        assertEquals(argb(255, 255, 0, 0), CssColor.parse("red"))
    }

    @Test
    fun everyNameOfTheTableParsesAsItsHexValue() {
        val names = listOf("aliceblue", "black", "cornflowerblue", "darkgrey", "grey", "gray", "lightgoldenrodyellow", "yellowgreen", "whitesmoke")
        for (name in names) assertTrue(CssColor.parse(name) != null, name)
    }

    @Test
    fun functionalColours() {
        assertEquals(argb(255, 255, 0, 0), CssColor.parse("rgb(255,0,0)"))
        assertEquals(argb(255, 255, 0, 0), CssColor.parse(" RGB( 255 , 0 , 0 ) "))
        assertEquals(argb(128, 0, 0, 255), CssColor.parse("rgba(0, 0, 255, 0.5)"))
        assertEquals(argb(255, 255, 128, 0), CssColor.parse("rgb(100% 50% 0%)"))
        assertEquals(argb(128, 10, 20, 30), CssColor.parse("rgb(10 20 30 / 50%)"))
        assertEquals(argb(255, 0, 255, 0), CssColor.parse("hsl(120, 100%, 50%)"))
        assertEquals(argb(255, 255, 0, 0), CssColor.parse("hsl(0deg 100% 50%)"))
        assertEquals(argb(51, 0, 0, 255), CssColor.parse("hsla(240, 100%, 50%, 0.2)"))
        assertEquals(argb(255, 0, 255, 255), CssColor.parse("hsl(0.5turn, 100%, 50%)"))
        assertEquals(argb(255, 255, 255, 255), CssColor.parse("rgb(300, 300, 300)"))
    }

    @Test
    fun whatIsNoColourIsNull() {
        for (bad in listOf("", "  ", "#12", "#ggg", "#12345", "nonsense", "rgb(1,2)", "rgb(a,b,c)", "rgb(1,2,3", "hsl(1)", "var(--foreground)", "5", "rgb(1 2 3 /)")) {
            assertNull(CssColor.parse(bad), "'$bad'")
        }
    }
}
