package com.octopusman.cosic.widgets

import cosic.SliderConfig
import cosic.SourceRange
import cosic.script.TokenKind
import cosic.script.tokenize

/**
 * What moving a slider does to the code: the number the first argument of `slider(...)` is written as is replaced by the text of
 * the slider's new value (slider.mjs:43-54). The caller keeps the live start of the argument as the text around it changes.
 */
object SliderEdits {
    /** No number literal of a slider is longer than this; the highlighter reads no more than this of the code after it. */
    private const val MAX_LITERAL = 128

    /**
     * [text] with the [currentLen] characters of the literal at [SliderConfig.from] replaced by [newText], and the range [newText] now takes.
     * [currentLen] is the length of what the last edit left there, see [literalLength].
     */
    fun apply(text: String, spec: SliderConfig, currentLen: Int, newText: String): Pair<String, SourceRange> =
        applyAt(text, spec.from, currentLen, newText)

    /** [apply] for an argument that has moved to [start]. */
    fun applyAt(text: String, start: Int, currentLen: Int, newText: String): Pair<String, SourceRange> {
        require(start >= 0 && currentLen >= 0 && start + currentLen <= text.length) {
            "the literal [$start, ${start + currentLen}) is not in a text of ${text.length} characters"
        }
        return text.replaceRange(start, start + currentLen, newText) to SourceRange(start, start + newText.length)
    }

    /**
     * The length of the number literal that begins at [start], an optional sign and spaces after it included: `-0.5` is 4, `- 1` is 3.
     * Null when no number literal is there (the code was edited under the slider).
     */
    fun literalLength(text: String, start: Int): Int? {
        if (start < 0 || start >= text.length) return null
        var number = start
        if (text[number] == '-' || text[number] == '+') {
            number++
            while (number < text.length && text[number].isWhitespace()) number++
        }
        if (number >= text.length) return null
        // the highlighter splits a signed number into an operator and a number, and never reads a sign as part of a number
        val token = tokenize(text.substring(number, minOf(text.length, number + MAX_LITERAL))).firstOrNull() ?: return null
        if (token.start != 0 || token.kind != TokenKind.NUMBER) return null
        return number + token.end - start
    }
}
