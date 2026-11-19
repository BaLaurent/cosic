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
}
