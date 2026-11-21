package com.octopusman.cosic.editor

import kotlin.test.Test
import kotlin.test.assertEquals

class InlineSliderTest {
    private fun seed(initial: Double?, min: Double = 0.0, max: Double = 1.0, step: Double? = null) = SliderSeed("slider_7", 7, min, max, step, initial)

    @Test
    fun theThumbIsAtTheNumberThePlayingPatternReadsThenAtTheNumberInTheCodeThenInTheMiddle() {
        assertEquals(0.75, sliderThumb(seed(0.5), 0.75))
        assertEquals(0.5, sliderThumb(seed(0.5), null))
        assertEquals(-0.5, sliderThumb(seed(-0.5, -1.0, 1.0), null))
        assertEquals(5.0, sliderThumb(seed(null, 0.0, 10.0), null))
    }

    @Test
    fun theThumbIsAlwaysInsideTheBounds() {
        assertEquals(1.0, sliderThumb(seed(5.0), null))
        assertEquals(-1.0, sliderThumb(seed(0.0, -1.0, 1.0), -9.0))
        assertEquals(0.5, sliderThumb(seed(0.5), Double.NaN))
        // bounds written the wrong way round
        assertEquals(0.25, sliderThumb(seed(0.25, 1.0, 0.0), null))
    }

    @Test
    fun aTouchMeansTheNumberUnderTheMiddleOfTheThumb() {
        // a control of 100 px with a thumb of 14 px radius 7: the middle of the thumb goes from 7 to 93
        assertEquals(0.0, sliderValueAt(0f, 100f, 7f, 0.0, 1.0))
        assertEquals(0.0, sliderValueAt(7f, 100f, 7f, 0.0, 1.0))
        assertEquals(0.5, sliderValueAt(50f, 100f, 7f, 0.0, 1.0))
        assertEquals(1.0, sliderValueAt(93f, 100f, 7f, 0.0, 1.0))
        assertEquals(1.0, sliderValueAt(400f, 100f, 7f, 0.0, 1.0))
        assertEquals(-1.0, sliderValueAt(-30f, 100f, 7f, -1.0, 1.0))
        assertEquals(200.0, sliderValueAt(50f, 100f, 7f, 100.0, 300.0))
    }

    @Test
    fun aControlTooNarrowForItsThumbIsAtTheLowerBound() {
        assertEquals(2.0, sliderValueAt(5f, 10f, 7f, 2.0, 4.0))
    }

    @Test
    fun theFractionIsHowFarAlongTheTrackTheNumberIs() {
        assertEquals(0.5f, sliderFraction(0.5, 0.0, 1.0))
        assertEquals(0f, sliderFraction(-5.0, 0.0, 1.0))
        assertEquals(1f, sliderFraction(5.0, 0.0, 1.0))
        assertEquals(0.75f, sliderFraction(0.5, 1.0, -1.0))
        assertEquals(0f, sliderFraction(1.0, 1.0, 1.0))
    }
}
