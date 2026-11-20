package com.octopusman.cosic.widgets

import cosic.SliderConfig
import cosic.SourceRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class SliderEditsTest {
    private fun spec(from: Int, to: Int, value: String?, min: Double = 0.0, max: Double = 1.0, step: Double? = null) =
        SliderConfig(from, to, value, min, max, step, "slider_$from")

    @Test
    fun theLiteralIsReplacedByTheNewValue() {
        val (text, range) = SliderEdits.apply("slider(0.5, 0, 1)", spec(7, 10, "0.5"), 3, "0.75")
        assertEquals("slider(0.75, 0, 1)", text)
        assertEquals(SourceRange(7, 11), range)
    }

    @Test
    fun aLongerOrShorterLiteralMovesNothingBeforeIt() {
        val (text, range) = SliderEdits.apply("slider(0.123, 0, 1)", spec(7, 12, "0.123"), 5, "1")
        assertEquals("slider(1, 0, 1)", text)
        assertEquals(SourceRange(7, 8), range)
        val (next, nextRange) = SliderEdits.apply(text, spec(7, 12, "0.123"), nextLen(range), "0.25")
        assertEquals("slider(0.25, 0, 1)", next)
        assertEquals(SourceRange(7, 11), nextRange)
    }

    private fun nextLen(range: SourceRange) = range.end - range.start

    @Test
    fun theSignOfANegativeNumberIsPartOfWhatIsReplaced() {
        val (text, range) = SliderEdits.apply("slider(-0.5, -1, 1)", spec(7, 11, "-0.5", -1.0, 1.0), 4, "0.25")
        assertEquals("slider(0.25, -1, 1)", text)
        assertEquals(SourceRange(7, 11), range)
    }

    @Test
    fun anArgumentThatMovedIsEditedWhereItIs() {
        val (text, range) = SliderEdits.applyAt("n(1)\nslider(0.5)", 12, 3, "0.7")
        assertEquals("n(1)\nslider(0.7)", text)
        assertEquals(SourceRange(12, 15), range)
    }

    @Test
    fun aRangeOutsideTheTextIsRefused() {
        assertFailsWith<IllegalArgumentException> { SliderEdits.applyAt("slider(1)", 7, 5, "2") }
        assertFailsWith<IllegalArgumentException> { SliderEdits.applyAt("slider(1)", -1, 1, "2") }
    }

    @Test
    fun theLengthOfTheLiteralIsReadFromTheCode() {
        assertEquals(3, SliderEdits.literalLength("slider(0.5, 0, 1)", 7))
        assertEquals(4, SliderEdits.literalLength("slider(-0.5, -1, 1)", 7))
        assertEquals(3, SliderEdits.literalLength("slider(- 1, 0, 1)", 7))
        assertEquals(2, SliderEdits.literalLength("slider(+1)", 7))
        assertEquals(3, SliderEdits.literalLength("slider(1e3, 0, 1e4)", 7))
        assertEquals(4, SliderEdits.literalLength("slider(0x10)", 7))
        assertEquals(1, SliderEdits.literalLength("slider(1)", 7))
        assertEquals(2, SliderEdits.literalLength("slider(.5)", 7))
    }

    @Test
    fun noLengthIsGivenWhereNoNumberIs() {
        assertNull(SliderEdits.literalLength("slider(x, 0, 1)", 7))
        assertNull(SliderEdits.literalLength("slider(-x)", 7))
        assertNull(SliderEdits.literalLength("slider(- )", 7))
        assertNull(SliderEdits.literalLength("slider(", 7))
        assertNull(SliderEdits.literalLength("slider(1)", -1))
        assertNull(SliderEdits.literalLength("slider(\"1\")", 7))
    }

    @Test
    fun theLengthStopsAtTheEndOfTheNumber() {
        assertEquals(3, SliderEdits.literalLength("slider(0.5+1)", 7))
        assertEquals(1, SliderEdits.literalLength("slider(1).gain(2)", 7))
    }
}
