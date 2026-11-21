package com.octopusman.cosic.editor

import cosic.CosicEngine
import cosic.EvalReport
import cosic.NoSampleStore
import cosic.SliderConfig
import cosic.sound.RegistryLibrary
import cosic.visual.VisualSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WidgetItemsTest {
    private fun evaluate(code: String): EvalReport.Ok {
        val engine = CosicEngine(48_000, RegistryLibrary(NoSampleStore, emptySet()))
        return engine.evaluate(code) as EvalReport.Ok
    }

    private fun slider(text: String, literal: String): LiveSlider {
        val from = text.indexOf(literal)
        return LiveSlider(SliderConfig(from, from + literal.length, literal, 0.0, 1.0, null, "slider_$from"), from, literal.length)
    }

    @Test
    fun aProgramWithNothingToShowHasNoItems() {
        assertTrue(widgetItems(emptyList(), null).isEmpty())
    }

    @Test
    fun slidersComeInTheOrderTheyAreWritten() {
        val text = "a(slider(0.5))\nb(slider(0.25))\n"
        val second = slider(text, "0.25")
        val first = slider(text, "0.5")
        val items = widgetItems(listOf(second, first), null)
        assertEquals(listOf(first, second), items.map { (it as WidgetItem.Slider).live })
    }

    @Test
    fun theBackgroundVisualIsNoWidget() {
        val ok = evaluate("""note("c3 e3").pianoroll()""")
        val session = assertNotNull(ok.program.visuals as? VisualSession)
        assertTrue(widgetItems(emptyList(), session).isEmpty())
    }

    @Test
    fun widgetsAndSlidersAreMixedInTheOrderOfTheirPlaceInTheCode() {
        val code = "s(\"bd\")._scope()\n\$: note(\"c3\").lpf(slider(0.5, 0, 1))._pianoroll()\n"
        val ok = evaluate(code)
        val session = assertNotNull(ok.program.visuals as? VisualSession)
        val live = slider(code, "0.5")
        val items = widgetItems(listOf(live), session)
        assertEquals(3, items.size)
        val kinds = items.map { if (it is WidgetItem.Slider) "slider" else (it as WidgetItem.Visual).surface.widget!!.type.method }
        assertEquals(listOf("_scope", "slider", "_pianoroll"), kinds)
    }
}
