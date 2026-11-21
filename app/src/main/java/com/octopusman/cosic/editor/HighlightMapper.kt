package com.octopusman.cosic.editor

import cosic.SourceRange

/**
 * Carries source ranges from the text the engine evaluated over to the text being edited now: it is given every [TextEdit] made to the text since the evaluation began and
 * moves a range through them one after the other, as CodeMirror moves its marks. A range before an edit stays, a range after it moves by the change in length, and a range an
 * edit reaches into is dropped (text typed right at the end of a range leaves it, text typed right at its start moves it). Two edits far apart in the code cost nothing in
 * between them, which is what a slider that is dragged needs: every move rewrites its number. Main thread only.
 */
internal class HighlightMapper {
    private val edits = ArrayList<TextEdit>()

    /** Whether the text has been edited since the evaluation this follows: when it has not, the places of its program are the places in the text. */
    val hasEdits: Boolean get() = edits.isNotEmpty()

    /** The text was edited ([edit] is null when it was not). */
    internal fun onEdit(edit: TextEdit?) {
        if (edit != null) edits += edit
    }

    /** [range] as it is in the text now, or null when an edit reached into it. */
    fun map(range: SourceRange): SourceRange? {
        var start = range.start
        var end = range.end
        for (e in edits) {
            when {
                end <= e.start -> Unit
                start >= e.oldEnd -> {
                    start += e.delta
                    end += e.delta
                }
                else -> return null
            }
        }
        return if (start == range.start && end == range.end) range else SourceRange(start, end)
    }

    /** [ranges] as they are in the text now, without those an edit reached into. */
    fun map(ranges: List<SourceRange>): List<SourceRange> {
        if (ranges.isEmpty() || edits.isEmpty()) return ranges
        return ranges.mapNotNull(::map)
    }
}
