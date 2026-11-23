package com.octopusman.cosic.ui

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** What makes a colour a program picked readable on the console: the ratio, and the lightening or darkening that reaches it. */
class ContrastTest {
    private val ink = Color(0xFF171A21)
    private val paper = Color(0xFFE6E6E6)

    @Test
    fun theRatioIsTheOneOfWcag() {
        assertEquals(21f, contrastRatio(Color.Black, Color.White), 0.01f)
        assertEquals(1f, contrastRatio(ink, ink), 0.0001f)
        assertEquals(contrastRatio(Color.Black, Color.White), contrastRatio(Color.White, Color.Black), 0.0001f)
    }

    @Test
    fun aDarkColourOnADarkBackgroundIsLightenedUntilItReads() {
        val navy = Color(0xFF000080)
        assertTrue(contrastRatio(navy, ink) < 4.5f)
        val readable = navy.readableOn(ink)
        assertNotEquals(navy, readable)
        assertTrue(contrastRatio(readable, ink) >= 4.5f, "the ratio is ${contrastRatio(readable, ink)}")
    }

    @Test
    fun aColourThatAlreadyReadsIsLeftAlone() {
        val white = Color(0xFFFFFFFF)
        assertEquals(white, white.readableOn(ink))
        assertEquals(Color(0xFF202020), Color(0xFF202020).readableOn(paper))
    }

    @Test
    fun aLightColourOnALightBackgroundIsDarkenedUntilItReads() {
        val yellow = Color(0xFFFFFF00)
        assertTrue(contrastRatio(yellow, paper) < 4.5f)
        val readable = yellow.readableOn(paper)
        assertTrue(contrastRatio(readable, paper) >= 4.5f, "the ratio is ${contrastRatio(readable, paper)}")
    }
}
