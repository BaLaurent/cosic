package com.octopusman.cosic.editor

/**
 * One change of the text: `[start, oldEnd)` of the text before was replaced by `[start, newEnd)` of the text after. Everything the editor remembers by place (the widgets' anchors, the
 * highlights, the error) is carried through the text's edits one by one, as CodeMirror carries its decorations.
 */
internal data class TextEdit(val start: Int, val oldEnd: Int, val newEnd: Int) {
    /** How much longer the text is after the edit. */
    val delta: Int get() = newEnd - oldEnd

    /** Where the place [o] of the text before is in the text after; a place inside the replaced part is at the end of what replaced it. */
    fun mapOffset(o: Int): Int = when {
        o <= start -> o
        o >= oldEnd -> o + delta
        else -> newEnd
    }

    companion object {
        /**
         * The edit that turns [old] into [new], or null when they are the same text. An edit is one replaced region, found from the longest common beginning and end of the
         * two texts; where a repeated character makes that ambiguous, the caret ([cursor], the end of the selection after the edit) says where the typing or the deleting was.
         * `foo()` -> `foo())` with the caret at 5 is an insertion at 4, not 5; `foo())` -> `foo()` with the caret at 4 deletes the character at 4, so that an anchor at 5 is kept.
         */
        fun between(old: CharSequence, new: CharSequence, cursor: Int): TextEdit? {
            val nO = old.length
            val nN = new.length
            val limit = minOf(nO, nN)
            var p = 0
            while (p < limit && old[p] == new[p]) p++
            var s = 0
            while (s < limit && old[nO - 1 - s] == new[nN - 1 - s]) s++
            if (p == nO && p == nN) return null
            return when {
                nN > nO && p + s >= nO -> {
                    val m = nN - nO
                    val at = (cursor - m).coerceIn(nO - s, p)
                    TextEdit(at, at, at + m)
                }
                nN < nO && p + s >= nN -> {
                    val m = nO - nN
                    val at = cursor.coerceIn(nN - s, p)
                    TextEdit(at, at + m, at)
                }
                else -> {
                    val tail = minOf(s, limit - p)
                    TextEdit(p, nO - tail, nN - tail)
                }
            }
        }
    }
}
