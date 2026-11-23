package com.octopusman.cosic.editor

import androidx.compose.ui.text.TextRange
import cosic.script.TokenKind
import cosic.script.TokenSpan

/** A character of a word of the code: of a name, of a number, or of a word of a mini-notation string (`bd`, `c4`). */
internal fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '$'

/**
 * The text between the quotes of a quoted string token (a `MINI` or `STRING` span that opens with `"`, `'` or a backtick), or null for
 * any other token, a regular expression included. A string that is not closed runs to the end of its span.
 */
internal fun TokenSpan.stringInterior(text: String): TextRange? {
    if (kind != TokenKind.MINI && kind != TokenKind.STRING) return null
    if (end - start < 2 || text[start] !in "\"'`") return null
    val closed = text[end - 1] == text[start]
    return TextRange(start + 1, if (closed) end - 1 else end)
}
