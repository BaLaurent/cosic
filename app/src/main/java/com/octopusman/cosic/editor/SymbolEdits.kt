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
