package com.octopusman.cosic.visual

import kotlin.math.PI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CanvasMathTest {
    @Test
    fun anUnparsableStyleKeepsThePreviousOne() {
        val kept = acceptStyle("not a colour", "#123456", 0xFF123456.toInt())
        assertEquals("#123456" to 0xFF123456.toInt(), kept)
        assertEquals("red" to 0xFFFF0000.toInt(), acceptStyle("red", "#123456", 0xFF123456.toInt()))
        assertEquals("rgba(0,0,255,0.5)" to 0x800000FF.toInt(), acceptStyle("rgba(0,0,255,0.5)", "", 0))
    }

    @Test
    fun globalAlphaMultipliesTheAlphaOfTheColour() {
        assertEquals(0x80FF0000.toInt(), withGlobalAlpha(0xFFFF0000.toInt(), 0.5))
        assertEquals(0x40FF0000.toInt(), withGlobalAlpha(0x80FF0000.toInt(), 0.5))
        assertEquals(0x00FF0000, withGlobalAlpha(0xFFFF0000.toInt(), 0.0))
        assertEquals(0xFF00FF00.toInt(), withGlobalAlpha(0xFF00FF00.toInt(), 1.0))
    }

    @Test
    fun globalAlphaAndLineWidthIgnoreNonsense() {
        assertEquals(0.3, acceptAlpha(1.5, 0.3))
        assertEquals(0.3, acceptAlpha(Double.NaN, 0.3))
        assertEquals(0.3, acceptAlpha(-0.1, 0.3))
        assertEquals(0.7, acceptAlpha(0.7, 0.3))
        assertEquals(2.0, acceptLineWidth(0.0, 2.0))
        assertEquals(2.0, acceptLineWidth(-1.0, 2.0))
        assertEquals(2.0, acceptLineWidth(Double.POSITIVE_INFINITY, 2.0))
        assertEquals(3.0, acceptLineWidth(3.0, 2.0))
    }

    @Test
    fun cssOfWritesBackTheColour() {
        assertEquals("#ff0000", cssOf(0xFFFF0000.toInt()))
        assertEquals("rgba(255, 0, 0, 0.502)", cssOf(0x80FF0000.toInt()))
        assertEquals("rgba(0, 0, 0, 0)", cssOf(0))
    }

    @Test
    fun fontsAreReadFromTheShorthand() {
        assertEquals(FontSpec(12f, "monospace", FontFamilyKind.MONOSPACE), FontSpec.parse("12px monospace"))
        assertEquals(FontSpec(10f, "sans-serif", FontFamilyKind.SANS_SERIF), FontSpec.parse("10px sans-serif"))
        assertEquals(FontSpec(14f, "Fira Code", FontFamilyKind.OTHER, bold = true), FontSpec.parse("bold 14px \"Fira Code\", monospace"))
        assertEquals(FontSpec(9.5f, "serif", FontFamilyKind.SERIF, italic = true), FontSpec.parse("italic 9.5px/1.2 serif"))
        assertEquals(FontSpec(11f, "Verdana", FontFamilyKind.OTHER), FontSpec.parse("11px Verdana, sans-serif"))
        assertTrue(FontSpec.parse("600 10px serif")!!.bold)
    }

    @Test
    fun aFontWithoutSizeOrFamilyIsIgnored() {
        assertNull(FontSpec.parse("monospace"))
        assertNull(FontSpec.parse("12px"))
        assertNull(FontSpec.parse("12pt monospace"))
        assertNull(FontSpec.parse(""))
        assertNull(FontSpec.parse("wide 12px monospace"))
        assertNotNull(FontSpec.parse("  12px   monospace "))
    }

    @Test
    fun wideTextIsSqueezedAndZeroMaxWidthDrawsNothing() {
        assertEquals(1.0, textSqueeze(50.0, null))
        assertEquals(1.0, textSqueeze(50.0, 80.0))
        assertEquals(0.5, textSqueeze(100.0, 50.0))
        assertNull(textSqueeze(100.0, 0.0))
        assertNull(textSqueeze(100.0, -3.0))
        assertNull(textSqueeze(100.0, Double.NaN))
    }

    @Test
    fun theBaselineMovesWithTheTextBaseline() {
        assertEquals(0f, baselineShift("alphabetic", 10f, 3f))
        assertEquals(10f, baselineShift("top", 10f, 3f))
        assertEquals(3.5f, baselineShift("middle", 10f, 3f))
        assertEquals(-3f, baselineShift("bottom", 10f, 3f))
    }

    @Test
    fun arcsSweepClockwise() {
        assertEquals(2 * PI, arcSweepRadians(0.0, 2 * PI))
        assertEquals(2 * PI, arcSweepRadians(0.0, 10 * PI))
        assertEquals(PI / 2, arcSweepRadians(0.0, PI / 2), 1e-12)
        // from 3/2 pi round to 1/2 pi: clockwise that is one half turn
        assertEquals(PI, arcSweepRadians(3 * PI / 2, PI / 2), 1e-12)
        // from 0 to -1/2 pi: three quarters of a turn the long way round
        assertEquals(3 * PI / 2, arcSweepRadians(0.0, -PI / 2), 1e-12)
        assertEquals(0.0, arcSweepRadians(1.0, 1.0))
    }

    @Test
    fun lineToWithoutACurrentPointStartsASubpath() {
        val t = PathTracker()
        assertEquals(listOf<PathStep>(PathStep.MoveTo(3.0, 4.0)), t.lineTo(3.0, 4.0))
        assertEquals(listOf<PathStep>(PathStep.LineTo(5.0, 6.0)), t.lineTo(5.0, 6.0))
        t.begin()
        assertEquals(listOf<PathStep>(PathStep.MoveTo(1.0, 1.0)), t.lineTo(1.0, 1.0))
    }

    @Test
    fun nonFiniteCoordinatesAreIgnored() {
        val t = PathTracker()
        assertEquals(emptyList(), t.moveTo(Double.NaN, 1.0))
        assertEquals(emptyList(), t.lineTo(1.0, Double.POSITIVE_INFINITY))
        assertEquals(emptyList(), t.arc(0.0, 0.0, 1.0, Double.NaN, 1.0))
        // still no current point, so a line starts a subpath
        assertEquals(listOf<PathStep>(PathStep.MoveTo(1.0, 1.0)), t.lineTo(1.0, 1.0))
    }

    @Test
    fun anArcWithNoCurrentPointStartsAtItsFirstPoint() {
        val t = PathTracker()
        // a full turn is two half turns (Skia's arcTo of 360 degrees draws nothing)
        val turn = t.arc(10.0, 10.0, 5.0, 0.0, 2 * PI)
        assertEquals(2, turn.size)
        val first = turn[0] as PathStep.Arc
        assertTrue(first.forceMove)
        assertEquals(0.0, first.startDegrees)
        assertEquals(180.0, first.sweepDegrees, 1e-9)
        val half = turn[1] as PathStep.Arc
        assertEquals(false, half.forceMove)
        assertEquals(180.0, half.startDegrees, 1e-9)
        assertEquals(180.0, half.sweepDegrees, 1e-9)
        val second = t.arc(10.0, 10.0, 5.0, PI, 3 * PI / 2).single() as PathStep.Arc
        assertEquals(false, second.forceMove) // joined with a line from the current point
        assertEquals(90.0, second.sweepDegrees, 1e-9)
    }

    @Test
    fun anArcOfRadiusZeroIsAPointAndANegativeRadiusDrawsNothing() {
        val t = PathTracker()
        assertEquals(listOf<PathStep>(PathStep.MoveTo(7.0, 3.0)), t.arc(7.0, 3.0, 0.0, 0.0, 1.0))
        assertEquals(emptyList(), t.arc(7.0, 3.0, -1.0, 0.0, 1.0))
    }

    @Test
    fun closePathNeedsASubpath() {
        val t = PathTracker()
        assertEquals(emptyList(), t.close())
        t.moveTo(0.0, 0.0)
        assertEquals(listOf<PathStep>(PathStep.Close), t.close())
    }

    @Test
    fun bitmapsAreScaledByTheDensityAndBounded() {
        val p = BitmapPlan.of(500.0, 60.0, 2.5f)
        assertEquals(1250, p.pixelWidth)
        assertEquals(150, p.pixelHeight)
        assertEquals(2.5f, p.scaleX)
        assertEquals(2.5f, p.scaleY)
        val big = BitmapPlan.of(10_000.0, 10.0, 1f)
        assertEquals(BitmapPlan.MAX_SIDE, big.pixelWidth)
        assertEquals(0.4096f, big.scaleX)
        val tiny = BitmapPlan.of(0.0, Double.NaN, 0f)
        assertEquals(1, tiny.pixelWidth)
        assertEquals(1, tiny.pixelHeight)
        // a fractional size rounds the bitmap up and the scale makes it exact
        val frac = BitmapPlan.of(100.3, 10.0, 1f)
        assertEquals(101, frac.pixelWidth)
        assertEquals((101.0 / 100.3).toFloat(), frac.scaleX)
    }

    @Test
    fun scrollingMovesWholePixelsWithinTheBitmap() {
        val p = BitmapPlan.of(100.0, 20.0, 2f)
        assertEquals(10, scrollPixels(5.0, p))
        assertEquals(0, scrollPixels(0.0, p))
        assertEquals(0, scrollPixels(-4.0, p))
        assertEquals(0, scrollPixels(Double.NaN, p))
        assertEquals(200, scrollPixels(500.0, p))
    }
}
