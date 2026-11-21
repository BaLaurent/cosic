package com.octopusman.cosic.editor

import cosic.visual.WidgetType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private fun block(id: String, end: Int) = BlockSeed(id, WidgetType.PIANOROLL, end, 500f, 60f)

private fun slider(id: String, start: Int) = SliderSeed(id, start, 0.0, 1.0, null, 0.5)

class EditorAnchorsTest {
    private fun blockAt(p: Int, e: TextEdit): Int? = EditorAnchors.of(listOf(block("b", p)), emptyList()).after(e).block("b")?.offset

    private fun sliderAt(q: Int, e: TextEdit): Int? = EditorAnchors.of(emptyList(), listOf(slider("s", q))).after(e).slider("s")?.offset

    @Test
    fun aBlockAnchorFollowsCodeMirrorsMapping() {
        assertEquals(13, blockAt(10, TextEdit(5, 5, 8)))
        assertEquals(10, blockAt(10, TextEdit(10, 10, 13))) // text typed at the anchor goes below the widget
        assertNull(blockAt(10, TextEdit(8, 10, 8))) // the character in front of the anchor was removed
        assertEquals(10, blockAt(10, TextEdit(10, 12, 10)))
        assertNull(blockAt(10, TextEdit(6, 12, 8)))
        assertEquals(10, blockAt(10, TextEdit(11, 11, 12)))
    }

    @Test
    fun aSliderAnchorIsDroppedOnlyWhenAnEditReachesOverIt() {
        assertEquals(10, sliderAt(10, TextEdit(10, 10, 12)))
        assertEquals(10, sliderAt(10, TextEdit(10, 13, 11)))
        assertEquals(8, sliderAt(10, TextEdit(8, 10, 8)))
        assertNull(sliderAt(10, TextEdit(8, 12, 8)))
        assertEquals(7, sliderAt(10, TextEdit(0, 3, 0)))
        assertEquals(10, sliderAt(10, TextEdit(11, 12, 11)))
    }

    @Test
    fun anchorsAreSortedByPlaceAndKeepTheirSeeds() {
        val anchors = EditorAnchors.of(listOf(block("late", 30), block("early", 5)), listOf(slider("b", 20), slider("a", 3)))
        assertEquals(listOf("early", "late"), anchors.blocks.map { it.id })
        assertEquals(listOf("a", "b"), anchors.sliders.map { it.id })
        assertEquals(30, anchors.block("late")?.offset)
        assertEquals(0.5, anchors.slider("a")?.seed?.initial)
        assertNull(anchors.slider("none"))
    }

    @Test
    fun noEditChangesNothingThatIsNotInTheWay() {
        val anchors = EditorAnchors.of(listOf(block("b", 4)), listOf(slider("s", 9)))
        val after = anchors.after(TextEdit(20, 20, 25))
        assertEquals(anchors.blocks, after.blocks)
        assertEquals(anchors.sliders, after.sliders)
    }
}

class AnchorTrackerTest {
    private fun tracker(vararg seeds: BlockSeed) = AnchorTracker().also {
        it.evaluationStarted()
        it.evaluationFinished(seeds.toList(), emptyList())
    }

    private fun AnchorTracker.blockOffset(id: String = "b") = anchors.block(id)?.offset

    @Test
    fun aFinishedEvaluationGivesTheAnchorsOfItsProgram() {
        assertEquals(10, tracker(block("b", 10)).blockOffset())
    }

    @Test
    fun editsBetweenEvaluationsMoveTheAnchors() {
        val tracker = tracker(block("b", 10))
        tracker.onEdit(TextEdit(0, 0, 3))
        assertEquals(13, tracker.blockOffset())
        tracker.onEdit(null)
        assertEquals(13, tracker.blockOffset())
    }

    @Test
    fun editsMadeWhileTheProgramWasEvaluatedAreReplayedOnItsAnchors() {
        val tracker = AnchorTracker()
        tracker.evaluationStarted()
        tracker.onEdit(TextEdit(0, 0, 3))
        tracker.onEdit(TextEdit(5, 5, 6))
        tracker.evaluationFinished(listOf(block("b", 10)), emptyList())
        assertEquals(14, tracker.blockOffset())
    }

    @Test
    fun anAnchorThatAnEditDuringTheEvaluationTouchedIsDropped() {
        val tracker = AnchorTracker()
        tracker.evaluationStarted()
        tracker.onEdit(TextEdit(8, 10, 8))
        tracker.evaluationFinished(listOf(block("b", 10)), emptyList())
        assertNull(tracker.blockOffset())
    }

    @Test
    fun aCancelledEvaluationKeepsTheAnchorsMovedByTheEditsSince() {
        val tracker = tracker(block("b", 10))
        tracker.evaluationStarted()
        tracker.onEdit(TextEdit(0, 0, 3))
        tracker.evaluationCancelled()
        assertEquals(13, tracker.blockOffset())
        // and nothing is logged any more: a later finish replays no old edit
        tracker.onEdit(TextEdit(0, 0, 1))
        tracker.evaluationStarted()
        tracker.evaluationFinished(listOf(block("b", 10)), emptyList())
        assertEquals(10, tracker.blockOffset())
    }

    @Test
    fun aNewEvaluationReplacesEveryAnchorIncludingTheOnesNotInTheProgramAnyMore() {
        val tracker = tracker(block("old", 10))
        tracker.evaluationStarted()
        tracker.evaluationFinished(listOf(block("new", 4)), listOf(slider("s", 2)))
        assertNull(tracker.blockOffset("old"))
        assertEquals(4, tracker.blockOffset("new"))
        assertEquals(2, tracker.anchors.slider("s")?.offset)
    }
}
