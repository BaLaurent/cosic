package com.octopusman.cosic.widgets

import cosic.SliderConfig
import kotlin.test.Test
import kotlin.test.assertEquals

class SliderCardTest {
    private fun spec(value: String?, min: Double = 0.0, max: Double = 1.0, step: Double? = null) =
        SliderConfig(7, 10, value, min, max, step, "slider_7")

    @Test
    fun aMoveIsSnappedAndTheNumberIsWhatTheCodeSays() {
        val change = sliderChange(spec("0.5"), 0.123456)
        assertEquals("0.123", change.text)
        assertEquals(0.123, change.number)
        val stepped = sliderChange(spec("1", 0.0, 10.0, 0.5), 3.3)
        assertEquals("3.5", stepped.text)
        assertEquals(3.5, stepped.number)
    }

    @Test
    fun aMoveStaysInsideTheBounds() {
        assertEquals("1", sliderChange(spec("0.5"), 7.0).text)
        assertEquals(-1.0, sliderChange(spec("0", -1.0, 1.0), -3.0).number)
    }

    @Test
    fun noNegativeZeroIsWritten() {
        assertEquals("0", sliderChange(spec("0.5", -1.0, 1.0), -0.0001).text)
    }

    @Test
    fun theThumbStartsAtTheValueOfTheBankThenAtTheLiteralThenInTheMiddle() {
        assertEquals(0.75, sliderThumbValue(spec("0.5"), 0.75))
        assertEquals(0.5, sliderThumbValue(spec("0.5"), null))
        assertEquals(-0.5, sliderThumbValue(spec("-0.5", -1.0, 1.0), null))
        assertEquals(5.0, sliderThumbValue(spec(null, 0.0, 10.0), null))
        assertEquals(0.0, sliderThumbValue(spec("x", -1.0, 1.0), "not a number"))
    }

    @Test
    fun theThumbIsAlwaysInsideTheBounds() {
        assertEquals(1.0, sliderThumbValue(spec("5"), null))
        assertEquals(0.0, sliderThumbValue(spec("0.5"), Double.NaN).let { it - 0.5 })
        assertEquals(-1.0, sliderThumbValue(spec("0", -1.0, 1.0), -9.0))
    }
}
