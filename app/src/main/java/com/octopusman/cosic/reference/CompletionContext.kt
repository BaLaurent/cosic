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
    data class Code(val afterDot: Boolean, val call: String? = null, val argIndex: Int = 0) : CursorContext

    /** Inside a string: the argument number [argIndex] of the call [call] (null when the string is not the argument of a call). */
    data class Text(val call: String?, val argIndex: Int) : CursorContext

    /** In a comment: nothing is suggested. */
    data object Comment : CursorContext
}

private fun Char.isIdentifierChar() = isLetterOrDigit() || this == '_' || this == '$'

/** One open bracket of the program being read: [call] is the function it calls when it is a `(` after a name, [argIndex] the argument being written. */
private class Frame(val call: String?, var argIndex: Int)

/**
 * The context of the cursor at [caret] in [text]: found by reading the program from its start, so that quotes, escapes and comments are
 * told from code the way JavaScript tells them (a string may have the other quotes, or a `//`, inside), with the calls the cursor is inside of.
 */
internal fun contextAt(text: String, caret: Int): CursorContext {
    val end = caret.coerceIn(0, text.length)
    var i = 0
    var quote = 0.toChar()
    val frames = ArrayList<Frame>()
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
        } else if (c == '(') {
            var nameEnd = i
            while (nameEnd > 0 && text[nameEnd - 1].isWhitespace()) nameEnd--
            var nameStart = nameEnd
            while (nameStart > 0 && text[nameStart - 1].isIdentifierChar()) nameStart--
            frames.add(Frame(text.substring(nameStart, nameEnd).ifEmpty { null }, 0))
        } else if (c == '[' || c == '{') {
            frames.add(Frame(null, 0))
        } else if (c == ')' || c == ']' || c == '}') {
            if (frames.isNotEmpty()) frames.removeAt(frames.size - 1)
        } else if (c == ',') {
            frames.lastOrNull()?.let { it.argIndex++ }
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
    val frame = frames.lastOrNull()
    if (quote != 0.toChar()) return CursorContext.Text(frame?.call, frame?.argIndex ?: 0)
    var start = end
    while (start > 0 && text[start - 1].isIdentifierChar()) start--
    return CursorContext.Code(afterDot = start > 0 && text[start - 1] == '.', call = frame?.call, argIndex = frame?.argIndex ?: 0)
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

/** What a place in a call's arguments asks for, by the types the documentation gives the parameter: a function to apply, or a number (a signal can stand for it). */
internal enum class Expectation { FUNCTION, NUMBER }

/** What the argument [argIndex] of [card] expects, from the types of its parameter; null when it takes a pattern or anything else. */
internal fun expectationOf(card: DocEntry?, argIndex: Int): Expectation? {
    val types = card?.params?.getOrNull(argIndex)?.types?.map { it.lowercase() }?.takeIf { it.isNotEmpty() } ?: return null
    return when {
        "function" in types && types.all { it == "function" || it == "array.<function()>" } -> Expectation.FUNCTION
        types.all { it == "number" || it == "integer" || it == "fraction" } -> Expectation.NUMBER
        else -> null
    }
}

/** The names of the signals a number can be given instead (`.lpf(sine.range(200, 2000))`). */
internal val SIGNAL_NAMES: Set<String> = setOf("sine", "cosine", "saw", "isaw", "tri", "square", "rand", "perlin", "irand", "brand", "sine2", "cosine2", "saw2", "tri2", "square2", "rand2", "berlin", "mousex", "mousey")
