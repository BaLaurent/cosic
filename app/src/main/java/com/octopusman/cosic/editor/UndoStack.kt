package com.octopusman.cosic.editor

import androidx.compose.ui.text.input.TextFieldValue

private const val MAX_SNAPSHOTS = 200
private const val COALESCE_WINDOW_MS = 400L

private enum class EditKind { INSERTION, DELETION }

/**
 * Undo and redo history for the code editor.
 *
 * Every edit is reported with [recordEdit]; the text from before the edit becomes an undo snapshot
 * only when it starts a new run: at least [COALESCE_WINDOW_MS] after the last snapshot, or when the
 * edit switches between inserting and deleting. Other edits join the run in progress, so typing a
 * word is undone in one step. At most [MAX_SNAPSHOTS] snapshots are kept; the oldest are dropped.
 *
 * A gesture that edits for as long as it lasts (dragging a slider, which rewrites its number on every move) brackets its edits with [beginRun] and [endRun]: whatever
 * the edits in between are, and however long they take, they are one step.
 *
 * Not thread-safe: use it from the main thread, like the text it tracks.
 */
class UndoStack(private val now: () -> Long) {
    private val undoSnapshots = ArrayDeque<TextFieldValue>()
    private val redoSnapshots = ArrayDeque<TextFieldValue>()
    private var lastSnapshotAt = 0L
    private var lastKind: EditKind? = null
    private var run = false

    val canUndo: Boolean get() = undoSnapshots.isNotEmpty()
    val canRedo: Boolean get() = redoSnapshots.isNotEmpty()

    /** Reports that the text changed from [before] to [after]. */
    fun recordEdit(before: TextFieldValue, after: TextFieldValue) {
        if (run) return // the run's own snapshot is the one that counts
        val kind = if (after.text.length < before.text.length) EditKind.DELETION else EditKind.INSERTION
        val time = now()
        if (lastKind == null || kind != lastKind || time - lastSnapshotAt >= COALESCE_WINDOW_MS) {
            push(undoSnapshots, before)
            lastSnapshotAt = time
        }
        lastKind = kind
        redoSnapshots.clear()
    }

    /** The edits that follow, until [endRun], are one step of the history: [before] is what undoing it goes back to. Nothing can be redone any more. */
    fun beginRun(before: TextFieldValue) {
        push(undoSnapshots, before)
        lastSnapshotAt = now()
        lastKind = null
        redoSnapshots.clear()
        run = true
    }

    /** Ends the run [beginRun] began; when the text is what it was, the run leaves no step. The next edit starts a step of its own. */
    fun endRun(current: TextFieldValue) {
        if (!run) return
        run = false
        lastKind = null
        if (undoSnapshots.lastOrNull()?.text == current.text) undoSnapshots.removeLast()
    }

    /** The text to go back to, or null when there is none. [current] becomes redoable. */
    fun undo(current: TextFieldValue): TextFieldValue? = step(from = undoSnapshots, to = redoSnapshots, current)

    /** The text that was undone last, or null when there is none. [current] becomes undoable again. */
    fun redo(current: TextFieldValue): TextFieldValue? = step(from = redoSnapshots, to = undoSnapshots, current)

    private fun step(from: ArrayDeque<TextFieldValue>, to: ArrayDeque<TextFieldValue>, current: TextFieldValue): TextFieldValue? {
        run = false // going back ends a run that is still going
        val target = from.removeLastOrNull() ?: return null
        push(to, current)
        // The next edit starts from this state, so it must not join an older run.
        lastKind = null
        return target
    }

    private fun push(snapshots: ArrayDeque<TextFieldValue>, value: TextFieldValue) {
        // A snapshot is restored later, when whatever the keyboard was composing is long gone.
        snapshots.addLast(TextFieldValue(value.text, value.selection))
        if (snapshots.size > MAX_SNAPSHOTS) snapshots.removeFirst()
    }
}
