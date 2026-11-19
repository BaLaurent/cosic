package com.octopusman.cosic.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/** The closing symbol that goes with each opening symbol of the symbol bar. */
private val CLOSING = mapOf("\"" to "\"", "(" to ")", "[" to "]", "{" to "}", "<" to ">")

/**
 * This value after the symbol bar inserted [symbol]. An opening symbol also adds its closing
 * partner: the cursor goes between the two, or the two wrap the selection and the cursor lands after
 * them. Any other symbol replaces the selection.
 */
fun TextFieldValue.withSymbol(symbol: String): TextFieldValue {
    val start = selection.min
    val end = selection.max
    val closing = CLOSING[symbol]
    val inserted = if (closing == null) symbol else symbol + text.substring(start, end) + closing
    val cursor = if (closing == null || start == end) start + symbol.length else start + inserted.length
    return TextFieldValue(text.replaceRange(start, end, inserted), TextRange(cursor))
}

/**
 * This value after a reference example [snippet] was inserted at the cursor, replacing the selection.
 * Code is line based, so the snippet gets a line of its own: a line break is added before it when
 * the cursor follows code on its line, and after it when code follows the cursor.
 */
fun TextFieldValue.withSnippet(snippet: String): TextFieldValue {
    val start = selection.min
    val end = selection.max
    val lineStart = text.lastIndexOf('\n', start - 1) + 1
    val lineEnd = text.indexOf('\n', end).let { if (it < 0) text.length else it }
    val before = if (text.substring(lineStart, start).isBlank()) "" else "\n"
    val after = if (text.substring(end, lineEnd).isBlank()) "" else "\n"
    val cursor = start + before.length + snippet.length
    return TextFieldValue(text.replaceRange(start, end, before + snippet + after), TextRange(cursor))
}
