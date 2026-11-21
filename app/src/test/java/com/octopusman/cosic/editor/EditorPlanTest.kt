package com.octopusman.cosic.editor

import cosic.script.tokenize
import cosic.visual.WidgetType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditorPlanTest {
    @Test
    fun aWidgetTakesTheRowsItsHeightNeeds() {
        assertEquals(3, blockRows(60f, 1f, 20))
        assertEquals(3, blockRows(60f, 2.625f, 53))
        assertEquals(14, blockRows(275f, 2.625f, 53))
        assertEquals(10, blockRows(200f, 1f, 20))
        assertEquals(5, blockRows(100f, 1f, 20))
        assertEquals(1, blockRows(10f, 1f, 20))
        assertEquals(1, blockRows(0f, 1f, 20))
        assertEquals(120, blockRows(100_000f, 1f, 20))
    }

    private fun seed(type: WidgetType, w: Float, h: Float) = BlockSeed("w", type, 0, w, h)

    @Test
    fun aWideWidgetIsAsWideAsTheRoomAndKeepsItsHeight() {
        val pianoroll = seed(WidgetType.PIANOROLL, 500f, 60f)
        assertEquals(BlockSize(336f, 60f), fitBlock(pianoroll, 336f))
        assertEquals(BlockSize(500f, 60f), fitBlock(pianoroll, 600f))
        assertEquals(BlockSize(500f, 60f), fitBlock(pianoroll, 0f))
        assertEquals(BlockSize(336f, 100f), fitBlock(seed(WidgetType.PRINT, 600f, 100f), 336f))
    }

    @Test
    fun aRoundWidgetKeepsItsShape() {
        assertEquals(BlockSize(200f, 200f), fitBlock(seed(WidgetType.SPIRAL, 275f, 275f), 200f))
        assertEquals(BlockSize(100f, 100f), fitBlock(seed(WidgetType.SPECTRUM, 200f, 200f), 100f))
        assertEquals(BlockSize(100f, 100f), fitBlock(seed(WidgetType.PITCHWHEEL, 200f, 200f), 100f))
    }

    @Test
    fun lineNumbersCountLineBreaksInFront() {
        assertEquals(listOf(1, 2, 2, 3), listOf(0, 2, 3, 4).map { lineNumberAt("a\nb\nc", it) })
        assertEquals(3, lineNumberAt("a\nb\nc", 99))
        assertEquals(1, lineNumberAt("a\nb\nc", -4))
    }

    private val text = "\$: note(\"c e g\")._pianoroll()\n\$: s(\"bd*2\").gain(slider(0.5, 0, 1))"

    private fun plan(
        blocks: List<BlockSeed> = listOf(BlockSeed("_widget__pianoroll_0", WidgetType.PIANOROLL, 29, 500f, 60f)),
        sliders: List<SliderSeed> = listOf(SliderSeed("slider_55", 55, 0.0, 1.0, null, 0.5)),
        text: String = this.text,
    ) = planEditor(text, tokenize(text), EditorAnchors.of(blocks, sliders), contentWidthDp = 336f, density = 1f, rowPx = 20)

    @Test
    fun theWorkedExample() {
        assertEquals(66, text.length)
        val plan = plan()
        val block = assertNotNull(plan.block("_widget__pianoroll_0"))
        assertEquals(3, block.rows)
        assertEquals(336f, block.widthDp)
        assertEquals(60f, block.heightDp)
        assertEquals(1, block.line)
        assertEquals(2, assertNotNull(plan.slider("slider_55")).line)
        assertEquals(58, assertNotNull(plan.slider("slider_55")).literalEnd)
        val map = plan.map
        assertEquals(79, map.transformedLength)
        assertEquals(listOf(29, 34, 68, 69), listOf(29, 30, 55, 56).map(map::originalToTransformed))
        val shown = map.apply(text)
        assertEquals(text.substring(0, 29) + "\n\n\n\n" + text.substring(29, 55) + EditorMetrics.SLIDER_GAP + text.substring(55), shown)
    }

    @Test
    fun aSliderWhoseNumberIsGoneHasNoGap() {
        val edited = text.replace("0.5", "x")
        val plan = plan(text = edited, sliders = listOf(SliderSeed("slider_55", 55, 0.0, 1.0, null, 0.5)))
        assertNull(plan.slider("slider_55"))
        assertEquals(1, plan.map.size)
    }

    @Test
    fun anAnchorPastTheEndIsClampedToIt() {
        val plan = plan(blocks = listOf(BlockSeed("b", WidgetType.SCOPE, 999, 500f, 60f)), sliders = emptyList(), text = "s(\"bd\")")
        assertEquals(7 + 4, plan.map.transformedLength)
        assertEquals(1, plan.block("b")?.line)
    }

    @Test
    fun aNarrowRoomMakesAShorterWidget() {
        val plan = planEditor(text, tokenize(text), EditorAnchors.of(listOf(BlockSeed("s", WidgetType.SPIRAL, 29, 275f, 275f)), emptyList()), 137.5f, 1f, 20)
        val block = assertNotNull(plan.block("s"))
        assertEquals(137.5f, block.widthDp)
        assertEquals(137.5f, block.heightDp)
        assertEquals(7, block.rows)
        assertTrue(plan.map.transformedLength == text.length + 8)
    }
}
