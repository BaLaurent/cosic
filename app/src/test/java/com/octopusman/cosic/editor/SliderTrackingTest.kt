package com.octopusman.cosic.editor

import cosic.SliderConfig
import cosic.SourceRange
import kotlin.test.Test
import kotlin.test.assertSame
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SliderTrackingTest {
    private fun spec(text: String, literal: String, min: Double = 0.0, max: Double = 1.0): SliderConfig {
        val from = text.indexOf(literal)
        return SliderConfig(from, from + literal.length, literal, min, max, null, "slider_$from")
    }

    private val code = "n(1)\ns(\"bd\").lpf(slider(0.5, 0, 1))\n"
    private val literal = "0.5"
    private val at = code.indexOf(literal)

    private fun placed(text: String = code, current: String = text) = SliderTracking.place(text, current, listOf(spec(text, literal)))

    @Test
    fun aSliderWithANumberIsPlacedWhereItIsWritten() {
        val sliders = placed()
        assertEquals(1, sliders.size)
        assertEquals(SourceRange(at, at + 3), sliders[0].range)
    }

    @Test
    fun aSliderWithoutANumberHasNoPlace() {
        val noValue = SliderConfig(at, at + 3, null, 0.0, 1.0, null, "slider_$at")
        assertTrue(SliderTracking.place(code, code, listOf(noValue)).isEmpty())
    }

    @Test
    fun textEditedWhileTheProgramEvaluatedMovesThePlace() {
        val current = "// x\n$code"
        assertEquals(SourceRange(at + 5, at + 8), placed(code, current).single().range)
    }

    @Test
    fun aNumberTheProgramEvaluatedIsPlacedWithItsLengthNow() {
        // a digit typed after the number while the program evaluated: the number is the longer one
        assertEquals(4, placed(code, code.replace("0.5", "0.55")).single().length)
    }

    @Test
    fun aNumberThatWasEditedAwayWhileTheProgramEvaluatedHasNoPlace() {
        assertTrue(placed(code, code.replace("slider(0.5", "slider(x")).isEmpty())
    }

    @Test
    fun anEditBeforeTheNumberMovesIt() {
        val slider = placed().single()
        val after = "// hi\n$code"
        val moved = SliderTracking.afterEdit(listOf(slider), code, after).single()
        assertEquals(at + 6, moved.start)
        assertEquals(3, moved.length)
        assertEquals(slider.spec, moved.spec)
    }

    @Test
    fun anEditAfterTheNumberChangesNothing() {
        val list = placed()
        assertSame(list, SliderTracking.afterEdit(list, code, "$code// end\n"))
    }

    @Test
    fun anEditInAnEarlierLineMovesTheNumberByItsLength() {
        val after = code.replace("n(1)", "n(1).fast(2)")
        assertEquals(at + 8, SliderTracking.afterEdit(placed(), code, after).single().start)
    }

    @Test
    fun deletingBeforeTheNumberMovesItBack() {
        val slider = placed().single()
        val after = code.replaceFirst("n(1)\n", "")
        assertEquals(at - 5, SliderTracking.afterEdit(listOf(slider), code, after).single().start)
    }

    @Test
    fun typingADigitAfterTheNumberMakesItLonger() {
        val slider = placed().single()
        val after = code.replace("0.5", "0.55")
        val grown = SliderTracking.afterEdit(listOf(slider), code, after).single()
        assertEquals(at, grown.start)
        assertEquals(4, grown.length)
    }

    @Test
    fun typingOverTheNumberReplacesItsLength() {
        val slider = placed().single()
        val after = code.replace("0.5", "7")
        val replaced = SliderTracking.afterEdit(listOf(slider), code, after).single()
        assertEquals(at, replaced.start)
        assertEquals(1, replaced.length)
    }

    @Test
    fun editingTheNumberAwayRemovesTheSlider() {
        val slider = placed().single()
        assertTrue(SliderTracking.afterEdit(listOf(slider), code, code.replace("0.5", "x")).isEmpty())
        assertTrue(SliderTracking.afterEdit(listOf(slider), code, code.replace("slider(0.5, 0, 1)", "0.5")).isEmpty())
    }

    @Test
    fun anEditThatBeginsBeforeTheNumberAndReachesIntoItRemovesTheSlider() {
        val slider = placed().single()
        val after = code.replace("slider(0.5", "sl9") // the open paren and the start of the number go
        assertTrue(SliderTracking.afterEdit(listOf(slider), code, after).isEmpty())
    }

    @Test
    fun insertingJustBeforeTheNumberMovesIt() {
        val slider = placed().single()
        val after = code.substring(0, at) + "x + " + code.substring(at)
        val moved = SliderTracking.afterEdit(listOf(slider), code, after).single()
        assertEquals(at + 4, moved.start)
        assertEquals(3, moved.length)
    }

    @Test
    fun aDigitTypedJustBeforeTheNumberIsTypedIntoIt() {
        // the first character of the number and of the text typed are the same, so the edit begins after it: the number begins where it did and is a character longer
        val slider = placed().single()
        val after = code.substring(0, at) + "0" + code.substring(at)
        val grown = SliderTracking.afterEdit(listOf(slider), code, after).single()
        assertEquals(at, grown.start)
        assertEquals(4, grown.length)
    }

    @Test
    fun everySliderMovesByItsOwnShift() {
        val text = "a(slider(0.5))\nb(slider(0.25))\n"
        val first = SliderConfig(text.indexOf("0.5"), text.indexOf("0.5") + 3, "0.5", 0.0, 1.0, null, "s1")
        val second = SliderConfig(text.indexOf("0.25"), text.indexOf("0.25") + 4, "0.25", 0.0, 1.0, null, "s2")
        val sliders = SliderTracking.place(text, text, listOf(first, second))
        val after = text.replace("0.5", "0.75")
        val moved = SliderTracking.afterEdit(sliders, text, after)
        assertEquals(listOf(first.from, second.from + 1), moved.map { it.start })
        assertEquals(listOf(4, 4), moved.map { it.length })
    }

    @Test
    fun noEditChangesNothing() {
        val sliders = placed()
        assertTrue(SliderTracking.afterEdit(sliders, code, code) === sliders)
        assertTrue(SliderTracking.afterEdit(emptyList(), code, "x").isEmpty())
    }

    @Test
    fun aSignedNumberIsOneLiteral() {
        val text = "x(slider(-0.5, -1, 1))"
        val from = text.indexOf("-0.5")
        val spec = SliderConfig(from, from + 4, "-0.5", -1.0, 1.0, null, "slider_$from")
        val slider = SliderTracking.place(text, text, listOf(spec)).single()
        assertEquals(4, slider.length)
        val moved = SliderTracking.afterEdit(listOf(slider), text, "// y\n$text").single()
        assertEquals(from + 5, moved.start)
        assertEquals(4, moved.length)
    }

    @Test
    fun linesAreCountedFromOne() {
        assertEquals(1, SliderTracking.lineOf("abc", 0))
        assertEquals(1, SliderTracking.lineOf("abc\ndef", 3))
        assertEquals(2, SliderTracking.lineOf("abc\ndef", 4))
        assertEquals(3, SliderTracking.lineOf("a\nb\nc", 4))
        assertEquals(3, SliderTracking.lineOf("a\nb\nc", 99))
    }
}
