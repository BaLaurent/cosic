package com.octopusman.cosic.editor

import kotlin.test.Test
import kotlin.test.assertEquals

class ReferenceSplitTest {
    @Test
    fun draggingUpGrowsThePanelByTheShareOfTheHeightMoved() {
        assertEquals(0.55f, ReferenceSplit.dragged(0.45f, dragPx = -100f, totalPx = 1000f), 0.0001f)
    }

    @Test
    fun draggingDownShrinksIt() {
        assertEquals(0.35f, ReferenceSplit.dragged(0.45f, dragPx = 100f, totalPx = 1000f), 0.0001f)
    }

    @Test
    fun thePanelNeverCoversTheWholeEditorNorVanishes() {
        assertEquals(ReferenceSplit.MAX, ReferenceSplit.dragged(0.8f, dragPx = -5000f, totalPx = 1000f))
        assertEquals(ReferenceSplit.MIN, ReferenceSplit.dragged(0.3f, dragPx = 5000f, totalPx = 1000f))
    }

    @Test
    fun anUnmeasuredLayoutChangesNothing() {
        assertEquals(0.45f, ReferenceSplit.dragged(0.45f, dragPx = -100f, totalPx = 0f))
    }

    @Test
    fun tappingTheHandleOpensTheTallPanelAndBringsTheSmallOneBack() {
        assertEquals(ReferenceSplit.MAX, ReferenceSplit.toggled(ReferenceSplit.DEFAULT))
        assertEquals(ReferenceSplit.DEFAULT, ReferenceSplit.toggled(ReferenceSplit.MAX))
        assertEquals(ReferenceSplit.DEFAULT, ReferenceSplit.toggled(0.8f))
        assertEquals(ReferenceSplit.MAX, ReferenceSplit.toggled(0.25f))
    }

    @Test
    fun onAShortScreenThePanelOpensTallEnoughToShowItsList() {
        // 600 px high, the panel needs 400 px: two thirds.
        assertEquals(400f / 600f, ReferenceSplit.onOpen(ReferenceSplit.DEFAULT, minPanelPx = 400f, totalPx = 600f), 0.0001f)
    }

    @Test
    fun onATallScreenTheSizeTheUserLeftItAtIsKept() {
        assertEquals(0.45f, ReferenceSplit.onOpen(0.45f, minPanelPx = 400f, totalPx = 2000f), 0.0001f)
    }

    @Test
    fun theOpeningSizeNeverExceedsTheLargestAndUnmeasuredChangesNothing() {
        assertEquals(ReferenceSplit.MAX, ReferenceSplit.onOpen(0.45f, minPanelPx = 900f, totalPx = 600f))
        assertEquals(0.45f, ReferenceSplit.onOpen(0.45f, minPanelPx = 400f, totalPx = 0f))
    }
}
