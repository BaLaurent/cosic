package com.octopusman.cosic.reference

private fun Char.isWordChar() = isLetterOrDigit() || this == '_' || this == '$'

/** A known name at the cursor; [insideCall] when the cursor is in the parentheses of that call, not on a word. */
internal data class CursorHit(val name: String, val insideCall: Boolean)

/**
 * What the reference should open on for a cursor at [cursor] in [text]: the word the cursor touches
 * if [known] knows it (a function, a sound name inside a mini-notation string, a scale mode), else
 * the innermost call the cursor is inside of that [known] knows, for example `fast` in `.fast(|2)`.
 * Null when there is nothing known around the cursor.
 */
internal fun functionAtCursor(text: String, cursor: Int, known: (String) -> Boolean): CursorHit? {
    val position = cursor.coerceIn(0, text.length)

    var start = position
    while (start > 0 && text[start - 1].isWordChar()) start--
    var end = position
    while (end < text.length && text[end].isWordChar()) end++
    text.substring(start, end).takeIf { it.isNotEmpty() && known(it) }?.let { return CursorHit(it, insideCall = false) }

    var closed = 0
    for (i in position - 1 downTo 0) {
        when (text[i]) {
            ')' -> closed++
            '(' -> if (closed > 0) {
                closed--
            } else {
                var nameStart = i
                while (nameStart > 0 && text[nameStart - 1].isWordChar()) nameStart--
                text.substring(nameStart, i).takeIf { it.isNotEmpty() && known(it) }?.let { return CursorHit(it, insideCall = true) }
            }
        }
    }
    return null
}
