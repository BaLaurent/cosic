package com.octopusman.cosic.editor

/**
 * The jump to the next or the previous `$:` label of Strudel's editor (`jumpToCharacter` of packages/codemirror/labelJump.mjs, on Alt-W and
 * Alt-Q): a program of several patterns is a column of labels, and this moves the cursor from one to the next.
 */
object LabelJump {
    /** The character the jump looks for: the `$` of an anonymous label. */
    const val LABEL = '$'

    /**
     * Where the cursor goes from [caret] in [text]: just before the next [label] when [direction] is positive (the default), else before the previous one; null when the text has none.
     * A position counts as the one after the character (what a search cursor reports as its end): forwards the first position beyond the caret and
     * one more, else the first label, backwards the last position that is not after the caret and one more, else the last. So the cursor left right after a label
     * goes back to it, and one in front of it goes to the next.
     */
    fun jump(text: String, caret: Int, direction: Int = 1, label: Char = LABEL): Int? {
        val positions = ArrayList<Int>()
        var at = text.indexOf(label)
        while (at >= 0) {
            positions.add(at + 1)
            at = text.indexOf(label, at + 1)
        }
        if (positions.isEmpty()) return null
        val position = if (direction > 0) {
            positions.firstOrNull { it > caret + 1 } ?: positions.first() // loop back around for convenience
        } else {
            positions.lastOrNull { it < caret + 1 } ?: positions.last()
        }
        return position - 1
    }
}
