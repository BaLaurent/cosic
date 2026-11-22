package com.octopusman.cosic.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.octopusman.cosic.reference.InsertText

/** The closing symbol that goes with each opening symbol of the symbol bar. */
private val CLOSING = mapOf("\"" to "\"", "(" to ")", "[" to "]", "{" to "}", "<" to ">")

/**
 * This value after the symbol bar inserted [symbol]. Without a selection the symbol is inserted alone (the closing one is typed
 * when it is wanted). With a selection an opening symbol wraps it with its closing partner and the cursor lands after them;
 * any other symbol replaces the selection.
 */
fun TextFieldValue.withSymbol(symbol: String): TextFieldValue {
    val start = selection.min
    val end = selection.max
    val closing = if (start == end) null else CLOSING[symbol]
    val inserted = if (closing == null) symbol else symbol + text.substring(start, end) + closing
    val cursor = if (closing == null) start + symbol.length else start + inserted.length
    return TextFieldValue(text.replaceRange(start, end, inserted), TextRange(cursor))
}

/**
 * This value after a reference example [snippet] was inserted, as a line of its own. A selection is
 * replaced by it. Otherwise it takes the cursor's line when that line is blank, and goes on a new
 * line right after it when the line holds code, so the code being edited is never split.
 */
fun TextFieldValue.withSnippet(snippet: String): TextFieldValue {
    if (!selection.collapsed) {
        val cursor = selection.min + snippet.length
        return TextFieldValue(text.replaceRange(selection.min, selection.max, snippet), TextRange(cursor))
    }
    val lineStart = text.lastIndexOf('\n', selection.start - 1) + 1
    val lineEnd = text.indexOf('\n', selection.start).let { if (it < 0) text.length else it }
    if (text.substring(lineStart, lineEnd).isBlank()) {
        return TextFieldValue(text.replaceRange(lineStart, lineEnd, snippet), TextRange(lineStart + snippet.length))
    }
    val inserted = "\n" + snippet
    return TextFieldValue(text.replaceRange(lineEnd, lineEnd, inserted), TextRange(lineEnd + inserted.length))
}

/**
 * This value after a reference entry was put at the cursor, replacing the selection, with the cursor
 * where [entry] says (between the parentheses of a call). A method typed right after a dot keeps one dot.
 */
fun TextFieldValue.withEntry(entry: InsertText): TextFieldValue {
    val start = selection.min
    val dropDot = entry.text.startsWith(".") && start > 0 && text[start - 1] == '.'
    val inserted = if (dropDot) entry.text.drop(1) else entry.text
    val cursor = start + entry.cursor - (if (dropDot) 1 else 0)
    return TextFieldValue(text.replaceRange(start, selection.max, inserted), TextRange(cursor))
}
