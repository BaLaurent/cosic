package com.octopusman.cosic.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import cosic.script.TokenKind
import cosic.script.TokenSpan
import cosic.script.tokenize

/**
 * This value with the selection grown to the smallest of these ranges that strictly contains it: the word at the cursor; the
 * contents of the string around it, then the string with its quotes; for each pair of brackets around it, from the inside out,
 * the argument (between commas, not for braces), the contents, then the call (its name and the brackets, without a receiver or a
 * chain); the line; the whole text. [this] when nothing is larger.
 */
fun TextFieldValue.expandSelection(): TextFieldValue {
    val from = selection
    val next = levels(text, from)
        .filter { it.min <= from.min && from.max <= it.max && it.length > from.length }
        .minByOrNull { it.length }
        ?: return this
    return TextFieldValue(text, next)
}

/**
 * Remembers what [expand] grew from, so [shrink] can walk back down. The memory only holds while the selection is the one the
 * ladder made last; any other selection or text starts over.
 */
class SelectionLadder {
    private val below = ArrayDeque<TextRange>()
    private var last: TextFieldValue? = null

    fun expand(value: TextFieldValue): TextFieldValue {
        val next = value.expandSelection()
        if (next.selection == value.selection) return value
        if (!owns(value)) below.clear()
        below.addLast(value.selection)
        last = next
        return next
    }

    fun shrink(value: TextFieldValue): TextFieldValue {
        if (!owns(value)) return value
        val back = below.removeLastOrNull() ?: return value
        return TextFieldValue(value.text, back).also { last = it }
    }

    private fun owns(value: TextFieldValue) = last?.let { it.text == value.text && it.selection == value.selection } == true
}

private class Group(val open: Int, val close: Int)

private fun levels(text: String, from: TextRange): List<TextRange> {
    val tokens = tokenize(text)
    val literal = BooleanArray(text.length)
    for (token in tokens) {
        if (token.kind == TokenKind.COMMENT || token.kind == TokenKind.MINI || token.kind == TokenKind.STRING) {
            for (i in token.start until token.end) literal[i] = true
        }
    }
    val found = mutableListOf<TextRange>()
    wordAt(text, from.min)?.let(found::add)
    for (token in tokens) {
        val inner = token.stringInterior(text) ?: continue
        if (token.start <= from.min && from.max <= token.end) {
            found += inner
            found += TextRange(token.start, token.end)
        }
    }
    for (group in groups(text, literal)) {
        if (group.open + 1 <= from.min && from.max <= group.close) {
            found += TextRange(group.open + 1, group.close)
            if (text[group.open] != '{') found += argumentAt(text, literal, group, from.min)
            found += TextRange(callStart(tokens, group.open) ?: group.open, group.close + 1)
        }
    }
    val lineStart = text.lastIndexOf('\n', from.min - 1) + 1
    val lineEnd = text.indexOf('\n', from.max).let { if (it < 0) text.length else it }
    found += TextRange(lineStart, lineEnd)
    found += TextRange(0, text.length)
    return found
}

private fun wordAt(text: String, at: Int): TextRange? {
    val anchor = when {
        at < text.length && isWordChar(text[at]) -> at
        at > 0 && isWordChar(text[at - 1]) -> at - 1
        else -> return null
    }
    var start = anchor
    var end = anchor + 1
    while (start > 0 && isWordChar(text[start - 1])) start--
    while (end < text.length && isWordChar(text[end])) end++
    return TextRange(start, end)
}

/** The matched brackets outside strings and comments. */
private fun groups(text: String, literal: BooleanArray): List<Group> {
    val open = ArrayDeque<Int>()
    val found = mutableListOf<Group>()
    for (i in text.indices) {
        if (literal[i]) continue
        when (text[i]) {
            '(', '[', '{' -> open.addLast(i)
            ')', ']', '}' -> {
                val start = open.removeLastOrNull() ?: continue
                if (closerOf(text[start]) == text[i]) found += Group(start, i)
            }
        }
    }
    return found
}

private fun closerOf(opener: Char) = when (opener) {
    '(' -> ')'
    '[' -> ']'
    else -> '}'
}

/** Where the call whose name ends right before the bracket at [open] starts, or null when no name is there. */
private fun callStart(tokens: List<TokenSpan>, open: Int): Int? =
    tokens.firstOrNull { it.kind == TokenKind.CALL && it.end == open }?.start

/** The text between the top-level commas of [group] that holds [at], without the blanks around it. */
private fun argumentAt(text: String, literal: BooleanArray, group: Group, at: Int): TextRange {
    var start = group.open + 1
    var depth = 0
    var mine: TextRange? = null
    fun close(end: Int) {
        if (mine == null && start <= at && at <= end) mine = TextRange(start, end)
    }
    for (i in group.open + 1 until group.close) {
        if (literal[i]) continue
        when (text[i]) {
            '(', '[', '{' -> depth++
            ')', ']', '}' -> depth--
            ',' -> if (depth == 0) {
                close(i)
                start = i + 1
            }
        }
    }
    close(group.close)
    val span = mine ?: TextRange(group.open + 1, group.close)
    var from = span.start
    var to = span.end
    while (from < to && text[from].isWhitespace()) from++
    while (to > from && text[to - 1].isWhitespace()) to--
    return TextRange(from, to)
}
