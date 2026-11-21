package com.octopusman.cosic.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import cosic.script.TokenKind
import cosic.script.TokenSpan

/**
 * Where the number literal that [start] begins ends, or null when none begins there: an optional sign and the spaces after it are skipped, and a number token (of [tokens], the
 * tokens of [text]) must start at what is left. Strudel keeps the length the literal had when the slider was made and so replaces too little or too much once the number was
 * edited; this reads the code as it is now.
 */
internal fun literalEnd(text: String, tokens: List<TokenSpan>, start: Int): Int? {
    if (start !in text.indices) return null
    var i = start
    if (text[i] == '-' || text[i] == '+') {
        i++
        while (i < text.length && text[i].isWhitespace()) i++
    }
    val idx = tokens.binarySearchBy(i) { it.start }
    return if (idx >= 0 && tokens[idx].kind == TokenKind.NUMBER) tokens[idx].end else null
}

/** The text after a slider moved, with the caret moved by the edit, and the edit. */
internal class SliderRewrite(val value: TextFieldValue, val edit: TextEdit)

/** [value] with the [oldLength] characters of the literal at [start] replaced by [newText]; the selection follows the text it was in. */
internal fun replaceLiteral(value: TextFieldValue, start: Int, oldLength: Int, newText: String): SliderRewrite {
    val edit = TextEdit(start, start + oldLength, start + newText.length)
    return SliderRewrite(
        TextFieldValue(
            value.text.replaceRange(edit.start, edit.oldEnd, newText),
            TextRange(edit.mapOffset(value.selection.start), edit.mapOffset(value.selection.end)),
        ),
        edit,
    )
}
