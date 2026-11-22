package com.octopusman.cosic.reference

/** What an argument of a function is made of, when the editor knows a list of words for it: what `s("...")`, `.bank("...")`, `.scale("...")` can be given. */
internal enum class ArgKind { SOUND, BANK, SCALE, NOTE, VOWEL }

/** The words the editor can suggest for an [ArgKind]; the app fills them from its sound library and the engine. */
internal interface Vocabulary {
    fun words(kind: ArgKind): List<String>
}

/** Where the cursor is in a program, as far as the autocomplete needs it. */
internal sealed interface CursorContext {
    /** In code: [afterDot] when the word is a method (`.fa|`), which only a method of patterns can be. */
    data class Code(val afterDot: Boolean) : CursorContext

    /** Inside a string: the argument number [argIndex] of the call [call] (null when the string is not the argument of a call). */
    data class Text(val call: String?, val argIndex: Int) : CursorContext

    /** In a comment: nothing is suggested. */
    data object Comment : CursorContext
}

private fun Char.isIdentifierChar() = isLetterOrDigit() || this == '_' || this == '$'

/**
 * The context of the cursor at [caret] in [text]: found by reading the program from its start, so that quotes, escapes and comments are
 * told from code the way JavaScript tells them (a string may have the other quotes, or a `//`, inside).
 */
internal fun contextAt(text: String, caret: Int): CursorContext {
    val end = caret.coerceIn(0, text.length)
    var i = 0
    var quote = 0.toChar()
    var stringStart = -1
    while (i < end) {
        val c = text[i]
        if (quote != 0.toChar()) {
            when {
                c == '\\' -> i++
                c == quote -> quote = 0.toChar()
                c == '\n' && quote != '`' -> quote = 0.toChar()
            }
        } else if (c == '"' || c == '\'' || c == '`') {
            quote = c
            stringStart = i
        } else if (c == '/' && i + 1 < text.length && text[i + 1] == '/') {
            val lineEnd = text.indexOf('\n', i).let { if (it < 0) text.length else it }
            if (lineEnd >= end) return CursorContext.Comment
            i = lineEnd
        } else if (c == '/' && i + 1 < text.length && text[i + 1] == '*') {
            val close = text.indexOf("*/", i + 2)
            if (close < 0 || close + 2 > end) return CursorContext.Comment
            i = close + 1
        }
        i++
    }
    if (quote != 0.toChar()) return callOfString(text, stringStart)
    var start = end
    while (start > 0 && text[start - 1].isIdentifierChar()) start--
    return CursorContext.Code(afterDot = start > 0 && text[start - 1] == '.')
}

/** The call whose argument is the string that opens at [stringStart], and which argument it is. */
private fun callOfString(text: String, stringStart: Int): CursorContext.Text {
    var j = stringStart - 1
    while (j >= 0 && text[j].isWhitespace()) j--
    if (j < 0) return CursorContext.Text(null, 0)
    var argIndex = 0
    if (text[j] == ',') {
        // count the commas of this call's arguments back to its opening parenthesis
        var depth = 0
        while (j >= 0) {
            when (text[j]) {
                ')', ']', '}' -> depth++
                '(', '[', '{' -> if (depth == 0) break else depth--
                ',' -> if (depth == 0) argIndex++
            }
            j--
        }
        if (j < 0 || text[j] != '(') return CursorContext.Text(null, argIndex)
    } else if (text[j] != '(') {
        return CursorContext.Text(null, 0)
    }
    var nameEnd = j
    while (nameEnd > 0 && text[nameEnd - 1].isWhitespace()) nameEnd--
    var nameStart = nameEnd
    while (nameStart > 0 && text[nameStart - 1].isIdentifierChar()) nameStart--
    return CursorContext.Text(text.substring(nameStart, nameEnd).ifEmpty { null }, argIndex)
}

/**
 * What the argument [argIndex] of the function [card] is made of, from what its documentation calls the parameter (`sound`, `bank`, `scale`, `vowel`).
 * `note` has no documented parameter: its argument is a note name.
 */
internal fun argKindOf(card: DocEntry?, call: String, argIndex: Int): ArgKind? {
    if (argIndex != 0) return null
    if (call == "note") return ArgKind.NOTE
    return when (card?.params?.getOrNull(argIndex)?.name?.lowercase()) {
        "sound", "sounds" -> ArgKind.SOUND
        "bank" -> ArgKind.BANK
        "scale" -> ArgKind.SCALE
        "vowel" -> ArgKind.VOWEL
        else -> null
    }
}
