package com.octopusman.cosic.visual

import android.graphics.Color
import kotlin.math.PI
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.ext.junit.runners.AndroidJUnit4

/** The canvas rules on a real bitmap: pixels, at a density of 2 (logical px are twice as many pixels) unless said otherwise. */
@RunWith(AndroidJUnit4::class)
class BitmapPaintCanvasTest {
    private fun canvas(w: Double = 100.0, h: Double = 50.0, density: Float = 2f) = BitmapPaintCanvas(w, h, density)

    private fun BitmapPaintCanvas.px(x: Int, y: Int): Int = bitmap.getPixel(x, y)

    private fun BitmapPaintCanvas.opaque(x: Int, y: Int): Boolean = Color.alpha(px(x, y)) > 200

    private fun BitmapPaintCanvas.empty(x: Int, y: Int): Boolean = Color.alpha(px(x, y)) == 0

    @Test
    fun theBitmapHasDensityTimesTheLogicalSize() {
        val c = canvas()
        assertEquals(200, c.bitmap.width)
        assertEquals(100, c.bitmap.height)
        assertEquals(100.0, c.width, 0.0)
    }

    @Test
    fun rectanglesAreInLogicalPixels() {
        val c = canvas()
        c.fillStyle = "#ff0000"
        c.fillRect(10.0, 10.0, 20.0, 20.0)
        assertEquals(Color.RED, c.px(30, 30)) // (15, 15) logical
        assertTrue(c.empty(5, 5))
        assertTrue(c.empty(70, 70)) // outside: logical 35
        c.clearRect(10.0, 10.0, 20.0, 20.0)
        assertTrue(c.empty(30, 30))
    }

    @Test
    fun anUnparsableStyleKeepsThePreviousColour() {
        val c = canvas()
        c.fillStyle = "#00ff00"
        c.fillStyle = "no such colour"
        assertEquals("#00ff00", c.fillStyle)
        c.fillRect(0.0, 0.0, 10.0, 10.0)
        assertEquals(Color.GREEN, c.px(5, 5))
    }

    @Test
    fun globalAlphaMultipliesTheAlphaOfTheColour() {
        val c = canvas(density = 1f)
        c.fillStyle = "rgba(255, 0, 0, 0.5)"
        c.globalAlpha = 0.5
        c.fillRect(0.0, 0.0, 10.0, 10.0)
        val alpha = Color.alpha(c.px(5, 5))
        assertTrue("alpha $alpha is about a quarter", alpha in 56..72)
        c.globalAlpha = 7.0 // ignored
        assertEquals(0.5, c.globalAlpha, 0.0)
    }

    @Test
    fun strokesCoverTheLineWidth() {
        val c = canvas(density = 1f)
        c.strokeStyle = "#0000ff"
        c.lineWidth = 4.0
        c.beginPath()
        c.moveTo(0.0, 20.0)
        c.lineTo(50.0, 20.0)
        c.stroke()
        assertTrue(c.opaque(25, 19))
        assertTrue(c.opaque(25, 21))
        assertTrue(c.empty(25, 16))
        assertTrue(c.empty(25, 24))
    }

    @Test
    fun lineToWithoutACurrentPointStartsAtThatPoint() {
        val c = canvas(density = 1f)
        c.strokeStyle = "#ffffff"
        c.lineWidth = 2.0
        c.beginPath()
        c.lineTo(30.0, 30.0) // android alone would draw from the origin
        c.lineTo(60.0, 30.0)
        c.stroke()
        assertTrue(c.opaque(45, 30))
        assertTrue("nothing from the origin to (30, 30)", c.empty(15, 15))
    }

    @Test
    fun aFullCircleIsFilled() {
        val c = canvas(density = 1f)
        c.fillStyle = "#ff00ff"
        c.beginPath()
        c.arc(25.0, 25.0, 10.0, 0.0, 2 * PI)
        c.fill()
        assertTrue(c.opaque(25, 25))
        assertTrue(c.opaque(33, 25))
        assertTrue(c.empty(25, 40))
        assertTrue(c.empty(36, 36))
    }

    @Test
    fun anArcSweepsClockwiseFromTheStartAngle() {
        val c = canvas(density = 1f)
        c.strokeStyle = "#ffffff"
        c.lineWidth = 3.0
        c.beginPath()
        c.arc(25.0, 25.0, 15.0, 0.0, PI / 2) // from the right to the bottom (y is down)
        c.stroke()
        assertTrue("right", c.opaque(40, 25))
        assertTrue("bottom", c.opaque(25, 40))
        assertTrue("top is not on it", c.empty(25, 10))
        assertTrue("left is not on it", c.empty(10, 25))
    }

    @Test
    fun scrollLeftMovesThePictureAndLeavesTheRightEdgeTransparent() {
        val c = canvas(density = 1f)
        c.fillStyle = "#ff0000"
        c.fillRect(50.0, 0.0, 10.0, 10.0)
        c.fillStyle = "#00ff00"
        c.fillRect(90.0, 0.0, 10.0, 10.0)
        c.scrollLeft(20.0)
        assertEquals(Color.RED, c.px(35, 5))
        assertTrue(c.empty(55, 5))
        assertEquals(Color.GREEN, c.px(75, 5))
        assertTrue("the uncovered right edge is transparent", c.empty(95, 5))
    }

    @Test
    fun textWithAMaxWidthOfZeroDrawsNothingAndWideTextIsSqueezed() {
        val c = canvas(w = 200.0, h = 40.0, density = 1f)
        c.fillStyle = "#ffffff"
        c.font = "20px monospace"
        c.textBaseline = "top"
        c.fillText("MMMMMMMMMM", 0.0, 0.0, 0.0)
        assertTrue((0 until 200).none { x -> (0 until 40).any { y -> !c.empty(x, y) } })
        c.fillText("MMMMMMMMMM", 0.0, 0.0, 50.0)
        var maxX = -1
        var minY = 99
        for (x in 0 until 200) for (y in 0 until 40) if (!c.empty(x, y)) { maxX = maxOf(maxX, x); minY = minOf(minY, y) }
        assertTrue("drew something", maxX > 0)
        assertTrue("squeezed into 50 px (reached $maxX)", maxX <= 51)
        assertTrue("the top baseline puts the text at the top (first row $minY)", minY <= 6)
        assertTrue(c.measureText("MMMMMMMMMM") > 50.0)
    }

    @Test
    fun theFontIsReadFromTheCssString() {
        val c = canvas(density = 1f)
        c.font = "30px monospace"
        val big = c.measureText("MM")
        c.font = "10px monospace"
        val small = c.measureText("MM")
        assertEquals(3.0, big / small, 0.05)
        c.font = "nonsense"
        assertEquals("10px monospace", c.font)
    }
}
