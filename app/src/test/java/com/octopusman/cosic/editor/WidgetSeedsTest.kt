package com.octopusman.cosic.editor

import cosic.CosicEngine
import cosic.EvalReport
import cosic.NoSampleStore
import cosic.sound.RegistryLibrary
import cosic.visual.WidgetType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WidgetSeedsTest {
    private fun evaluate(code: String): EvalReport.Ok {
        val engine = CosicEngine(48_000, RegistryLibrary(NoSampleStore, emptySet()))
        return engine.evaluate(code) as EvalReport.Ok
    }

    @Test
    fun aProgramWithNothingToShowHasNoSeeds() {
        val seeds = evaluate("""s("bd")""").program.toSeeds()
        assertTrue(seeds.blocks.isEmpty())
        assertTrue(seeds.sliders.isEmpty())
    }

    @Test
    fun aWidgetIsPlacedAtTheEndOfItsCallAndHasTheSizeItsCallAsked() {
        val code = "\$: note(\"c e g\")._pianoroll()\n\$: s(\"bd*4\")._scope({ width: 300, height: 40 })\n\$: s(\"hh\")._spiral()"
        val seeds = evaluate(code).program.toSeeds()
        assertEquals(listOf("_widget__pianoroll_0", "_widget__scope_0", "_widget__spiral_0"), seeds.blocks.map { it.id })
        val pianoroll = seeds.blocks[0]
        assertEquals(WidgetType.PIANOROLL, pianoroll.type)
        assertEquals(code.indexOf("._pianoroll()") + "._pianoroll()".length, pianoroll.callEnd)
        assertEquals(500f, pianoroll.widthDp)
        assertEquals(60f, pianoroll.heightDp)
        val scope = seeds.blocks[1]
        assertEquals(WidgetType.SCOPE, scope.type)
        assertEquals(300f, scope.widthDp)
        assertEquals(40f, scope.heightDp)
        assertEquals(275f, seeds.blocks[2].widthDp)
    }

    @Test
    fun theBackgroundVisualIsNoWidget() {
        val seeds = evaluate("""note("c3 e3").pianoroll()""").program.toSeeds()
        assertTrue(seeds.blocks.isEmpty())
    }

    @Test
    fun aSliderWithANumberIsASeedWithItsBoundsAndNumber() {
        val code = "s(\"bd\").gain(slider(0.5, 0, 1))\n\$: s(\"hh\").lpf(slider(-300.5, -1000, 1000, 0.5))"
        val seeds = evaluate(code).program.toSeeds()
        assertEquals(2, seeds.sliders.size)
        val first = seeds.sliders[0]
        assertEquals(code.indexOf("0.5"), first.argStart)
        assertEquals("slider_${first.argStart}", first.id)
        assertEquals(0.5, first.initial)
        assertEquals(0.0, first.min)
        assertEquals(1.0, first.max)
        assertNull(first.step)
        val second = seeds.sliders[1]
        // the sign belongs to the number
        assertEquals(code.indexOf("-300.5"), second.argStart)
        assertEquals(-300.5, second.initial)
        assertEquals(-1000.0, second.min)
        assertEquals(1000.0, second.max)
        assertEquals(0.5, second.step)
    }

    @Test
    fun aSliderThatIsGivenNoNumberHasNoSeed() {
        val seeds = evaluate("const x = 0.4\ns(\"bd\").gain(slider(x, 0, 1))").program.toSeeds()
        assertTrue(seeds.sliders.isEmpty())
    }

    @Test
    fun theSeedsMakeAnchorsThatFollowTheCode() {
        val code = "\$: note(\"c e g\")._pianoroll()\n\$: s(\"bd\").gain(slider(0.5, 0, 1))"
        val seeds = evaluate(code).program.toSeeds()
        val anchors = EditorAnchors.of(seeds.blocks, seeds.sliders)
        val edited = anchors.after(TextEdit(0, 0, 3))
        assertEquals(code.indexOf("._pianoroll()") + "._pianoroll()".length + 3, edited.blocks.single().offset)
        assertEquals(code.indexOf("0.5") + 3, edited.sliders.single().offset)
    }
}
