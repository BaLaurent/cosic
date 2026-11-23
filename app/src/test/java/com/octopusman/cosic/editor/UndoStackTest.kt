package com.octopusman.cosic.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UndoStackTest {
    private var clock = 10_000L
    private val stack = UndoStack(now = { clock })

    private fun value(text: String) = TextFieldValue(text, TextRange(text.length))

    /** Reports an edit that happened [atMs] on the test clock. */
    private fun edit(before: String, after: String, atMs: Long) {
        clock = atMs
        stack.recordEdit(value(before), value(after))
    }

    @Test
    fun nothingToUndoOrRedoInitially() {
        assertFalse(stack.canUndo)
        assertFalse(stack.canRedo)
        assertNull(stack.undo(value("a")))
        assertNull(stack.redo(value("a")))
    }

    @Test
    fun firstEditPushesTheTextBeforeIt() {
        edit("", "a", atMs = 0)

        assertTrue(stack.canUndo)
        assertEquals(value(""), stack.undo(value("a")))
        assertFalse(stack.canUndo)
    }

    @Test
    fun editsInsideTheCoalescingWindowShareOneSnapshot() {
        edit("", "a", atMs = 0)
        edit("a", "ab", atMs = 100)
        edit("ab", "abc", atMs = 399)

        assertEquals("", stack.undo(value("abc"))?.text)
        assertFalse(stack.canUndo)
    }

    @Test
    fun anEditExactlyAtTheWindowEdgeStartsANewSnapshot() {
        edit("", "a", atMs = 0)
        edit("a", "ab", atMs = 400)

        assertEquals("a", stack.undo(value("ab"))?.text)
        assertEquals("", stack.undo(value("a"))?.text)
        assertFalse(stack.canUndo)
    }

    @Test
    fun theWindowRunsFromTheLastPushNotFromTheLastEdit() {
        edit("", "a", atMs = 0)
        edit("a", "ab", atMs = 300)
        // 300 ms after the previous edit, but 600 ms after the last push.
        edit("ab", "abc", atMs = 600)

        assertEquals("ab", stack.undo(value("abc"))?.text)
    }

    @Test
    fun switchingBetweenInsertionAndDeletionStartsANewSnapshot() {
        edit("", "ab", atMs = 0)
        edit("ab", "a", atMs = 50)
        edit("a", "ax", atMs = 100)

        assertEquals("a", stack.undo(value("ax"))?.text)
        assertEquals("ab", stack.undo(value("a"))?.text)
        assertEquals("", stack.undo(value("ab"))?.text)
        assertFalse(stack.canUndo)
    }

    @Test
    fun consecutiveDeletionsInsideTheWindowShareOneSnapshot() {
        edit("abc", "ab", atMs = 0)
        edit("ab", "a", atMs = 50)
        edit("a", "", atMs = 100)

        assertEquals("abc", stack.undo(value(""))?.text)
        assertFalse(stack.canUndo)
    }

    @Test
    fun keepsAtMostTwoHundredSnapshots() {
        var text = ""
        repeat(250) { i ->
            val next = text + "x"
            edit(text, next, atMs = i * 400L)
            text = next
        }

        var current = value(text)
        var undone = 0
        while (true) {
            current = stack.undo(current) ?: break
            undone++
        }

        assertEquals(200, undone)
        // The 50 oldest snapshots were dropped.
        assertEquals(50, current.text.length)
    }

    @Test
    fun undoThenRedoRestoresTheLaterText() {
        edit("", "a", atMs = 0)

        val undone = stack.undo(value("a"))
        assertEquals("", undone?.text)
        assertTrue(stack.canRedo)
        assertFalse(stack.canUndo)

        val redone = stack.redo(undone!!)
        assertEquals("a", redone?.text)
        assertFalse(stack.canRedo)
        assertTrue(stack.canUndo)
    }

    @Test
    fun anEditAfterUndoDropsTheRedoHistory() {
        edit("", "a", atMs = 0)
        stack.undo(value("a"))
        assertTrue(stack.canRedo)

        edit("", "b", atMs = 10)

        assertFalse(stack.canRedo)
    }

    @Test
    fun anEditAfterUndoOrRedoAlwaysStartsANewSnapshot() {
        edit("", "a", atMs = 0)
        stack.undo(value("a"))

        // Well inside the window of the earlier push, yet the undone state must stay reachable.
        edit("", "b", atMs = 10)
        assertEquals("", stack.undo(value("b"))?.text)

        edit("", "c", atMs = 20)
        val undone = stack.undo(value("c"))
        val redone = stack.redo(undone!!)
        edit(redone!!.text, redone.text + "d", atMs = 30)
        assertEquals("c", stack.undo(value("cd"))?.text)
    }

    @Test
    fun snapshotsKeepTheSelectionAndDropTheComposition() {
        val before = TextFieldValue("abc", TextRange(1, 2), composition = TextRange(0, 3))
        stack.recordEdit(before, TextFieldValue("abcd", TextRange(4)))

        val restored = stack.undo(TextFieldValue("abcd", TextRange(4)))

        assertEquals("abc", restored?.text)
        assertEquals(TextRange(1, 2), restored?.selection)
        assertNull(restored?.composition)
    }

    @Test
    fun aRunOfManyEditsIsOneStepHoweverLongItTakes() {
        clock = 0
        stack.beginRun(value("a"))
        // ten edits, 500 ms apart, alternating between inserting and deleting: every one of them would be a step of its own outside a run
        val states = listOf("a", "ab", "a", "ab", "a", "ab", "a", "ab", "a", "ab", "abc")
        for (i in 1..10) edit(states[i - 1], states[i], atMs = i * 500L)
        stack.endRun(value("abc"))

        assertTrue(stack.canUndo)
        assertEquals("a", stack.undo(value("abc"))?.text)
        assertFalse(stack.canUndo)
    }

    @Test
    fun aRunThatLeftTheTextAsItWasLeavesNoStep() {
        stack.beginRun(value("abc"))
        edit("abc", "abcd", atMs = 10)
        edit("abcd", "abc", atMs = 20)
        stack.endRun(value("abc"))

        assertFalse(stack.canUndo)
    }

    @Test
    fun anEditRightAfterARunStartsAStepOfItsOwn() {
        clock = 100
        stack.beginRun(value("a"))
        edit("a", "ab", atMs = 110)
        stack.endRun(value("ab"))

        // inside the window of the push the run made, and still not part of it
        edit("ab", "abc", atMs = 150)

        assertEquals("ab", stack.undo(value("abc"))?.text)
        assertEquals("a", stack.undo(value("ab"))?.text)
        assertFalse(stack.canUndo)
    }

    @Test
    fun twoRunsWithinTheCoalescingWindowAreTwoSteps() {
        // two quick "move line" taps: same text length, 20 ms apart, each one run of the lines row
        clock = 0
        stack.beginRun(value("a\nb\nc"))
        stack.endRun(value("b\na\nc"))
        clock = 20
        stack.beginRun(value("b\na\nc"))
        stack.endRun(value("b\nc\na"))

        assertEquals("b\na\nc", stack.undo(value("b\nc\na"))?.text)
        assertEquals("a\nb\nc", stack.undo(value("b\na\nc"))?.text)
        assertFalse(stack.canUndo)
    }

    @Test
    fun undoDuringARunEndsIt() {
        stack.beginRun(value("a"))
        edit("a", "ab", atMs = 10)

        assertEquals("a", stack.undo(value("ab"))?.text)
        // the run is over: this edit is recorded
        edit("a", "ac", atMs = 20)
        assertTrue(stack.canUndo)
        assertEquals("a", stack.undo(value("ac"))?.text)
    }

    @Test
    fun beginningARunClearsTheRedoHistory() {
        edit("", "a", atMs = 0)
        stack.undo(value("a"))
        assertTrue(stack.canRedo)

        stack.beginRun(value(""))

        assertFalse(stack.canRedo)
    }

    @Test
    fun endingARunThatNeverBeganChangesNothing() {
        edit("", "a", atMs = 0)
        stack.endRun(value("a"))

        assertEquals("", stack.undo(value("a"))?.text)
    }

    @Test
    fun theRunsStepCanBeRedone() {
        stack.beginRun(value("0.5"))
        edit("0.5", "0.6", atMs = 10)
        edit("0.6", "0.7", atMs = 20)
        stack.endRun(value("0.7"))

        val undone = stack.undo(value("0.7"))
        assertEquals("0.5", undone?.text)
        assertEquals("0.7", stack.redo(undone!!)?.text)
    }
}
