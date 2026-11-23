package com.octopusman.cosic.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import cosic.script.tokenize

/**
 * This value with the cursor [delta] characters further (−1 or +1), never past the ends of the text. A selection is not moved from: it
 * collapses at its left edge for a negative [delta], at its right edge for a positive one.
 */
fun TextFieldValue.moveCursor(delta: Int): TextFieldValue {
    val target = when {
        !selection.collapsed -> if (delta < 0) selection.min else selection.max
        else -> (selection.start + delta).coerceIn(0, text.length)
    }
    return TextFieldValue(text, TextRange(target))
}

/**
 * This value with the cursor on the previous ([direction] < 0) or next stop of [boundaries], counted from the left or right edge of
 * the selection. At an end of the text the cursor stays.
 */
fun TextFieldValue.jumpToken(direction: Int): TextFieldValue {
    val from = if (direction < 0) selection.min else selection.max
    val marks = boundaries(text)
    val target = (if (direction < 0) marks.lastOrNull { it < from } else marks.firstOrNull { it > from }) ?: from
    return TextFieldValue(text, TextRange(target))
}

/** Where [jumpToken] stops: both ends of the text and of every token, and inside a quoted string its inner edges and the edges of its words. */
private fun boundaries(text: String): List<Int> {
    val marks = sortedSetOf(0, text.length)
    for (token in tokenize(text)) {
        marks += token.start
        marks += token.end
        val inner = token.stringInterior(text) ?: continue
        marks += inner.start
        marks += inner.end
        for (i in inner.start + 1 until inner.end) {
            if (isWordChar(text[i]) != isWordChar(text[i - 1])) marks += i
        }
    }
    return marks.toList()
}
