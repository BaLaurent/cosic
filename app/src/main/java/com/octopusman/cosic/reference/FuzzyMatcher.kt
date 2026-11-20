package com.octopusman.cosic.reference

import kotlin.math.max
import kotlin.math.min

/** How well a word matches the pattern: the closer to zero the better, and the ranges of the word that matched, as `from, to` pairs. */
internal class FuzzyMatch(val score: Int, val matched: IntArray)

/**
 * The matcher of CodeMirror's autocomplete (`FuzzyMatcher` of @codemirror/autocomplete 6.18.4, src/filter.ts), which Strudel's editor ranks its
 * suggestions with: a word matches when the characters of the pattern are in it in order, and the score says how well. A match at the start counts
 * most, a match of whole words (`fG` for `fastGap`) next, then characters apart; a different case and a start that is not the beginning cost
 * something. Penalties: gap -1100, not at the start -700, case folded -200, by word -100, not the whole word -100.
 *
 * One matcher goes over all the words, in order, as in CodeMirror, and it keeps a buffer between them, with a quirk that is ported as well: the
 * array of positions by word never gets shorter, so once a word has had a match by word starts, the test "is there a position so far" is true for
 * every word after it. The pattern of one character only matches at the start of a word.
 */
internal class FuzzyMatcher(private val pattern: String) {
    private val chars: IntArray
    private val folded: IntArray
    private val astral: Boolean

    // Buffers reused by the calls of `match`, like those of CodeMirror's matcher.
    private val any: IntArray
    private val byWord: IntArray

    /** The `length` the JavaScript array `byWord` has: it only grows. */
    private var byWordLength = 0

    init {
        val points = ArrayList<Int>(pattern.length)
        val foldedPoints = ArrayList<Int>(pattern.length)
        var p = 0
        while (p < pattern.length) {
            val char = pattern.codePointAt(p)
            val size = sizeOf(char)
            points.add(char)
            val part = pattern.substring(p, p + size)
            val upper = part.uppercase()
            foldedPoints.add((if (upper == part) part.lowercase() else upper).codePointAt(0))
            p += size
        }
        chars = points.toIntArray()
        folded = foldedPoints.toIntArray()
        astral = pattern.length != chars.size
        any = IntArray(chars.size)
        byWord = IntArray(chars.size)
    }

    /** Matches [word] against the pattern, null when it does not match. */
    fun match(word: String): FuzzyMatch? {
        if (pattern.isEmpty()) return FuzzyMatch(NOT_FULL, IntArray(0))
        if (word.length < pattern.length) return null
        val len = chars.size
        // For single-character queries, only match when they occur right at the start
        if (len == 1) {
            val first = word.codePointAt(0)
            val firstSize = sizeOf(first)
            var score = if (firstSize == word.length) 0 else NOT_FULL
            if (first == chars[0]) {
                // exact
            } else if (first == folded[0]) {
                score += CASE_FOLD
            } else {
                return null
            }
            return FuzzyMatch(score, intArrayOf(0, firstSize))
        }
        val direct = word.indexOf(pattern)
        if (direct == 0) return FuzzyMatch(if (word.length == pattern.length) 0 else NOT_FULL, intArrayOf(0, pattern.length))
        var anyTo = 0
        if (direct < 0) {
            var i = 0
            val e = min(word.length, MAX_WORD)
            while (i < e && anyTo < len) {
                val next = word.codePointAt(i)
                if (next == chars[anyTo] || next == folded[anyTo]) any[anyTo++] = i
                i += sizeOf(next)
            }
            // No match, exit immediately
            if (anyTo < len) return null
        }
        // Tracks whether there is a match that hits only characters that appear to be starting words. `byWordFolded` is set to true when a case
        // folded character is encountered in such a match
        var byWordTo = 0
        var byWordFolded = false
        // If we've found a partial adjacent match, these track its state
        var adjacentTo = 0
        var adjacentStart = -1
        var adjacentEnd = -1
        val hasLower = word.any { it in 'a'..'z' }
        var wordAdjacent = true
        // Go over the option's text, scanning for the various kinds of matches
        var i = 0
        val e = min(word.length, MAX_WORD)
        var prevType = NON_WORD
        while (i < e && byWordTo < len) {
            val next = word.codePointAt(i)
            if (direct < 0 && adjacentTo < len) {
                if (next == chars[adjacentTo] || next == folded[adjacentTo]) {
                    if (adjacentTo == 0) adjacentStart = i
                    adjacentEnd = i + 1
                    adjacentTo++
                } else {
                    adjacentTo = 0
                }
            }
            val type = typeOf(next)
            if (i == 0 || type == UPPER && hasLower || prevType == NON_WORD && type != NON_WORD) {
                if (chars[byWordTo] == next) {
                    record(byWordTo++, i)
                } else if (folded[byWordTo] == next) {
                    byWordFolded = true
                    record(byWordTo++, i)
                } else if (byWordLength > 0) {
                    wordAdjacent = false
                }
            }
            prevType = type
            i += sizeOf(next)
        }
        if (byWordTo == len && byWord[0] == 0 && wordAdjacent) return result(BY_WORD + (if (byWordFolded) CASE_FOLD else 0), byWord, word)
        if (adjacentTo == len && adjacentStart == 0) {
            return FuzzyMatch(CASE_FOLD - word.length + (if (adjacentEnd == word.length) 0 else NOT_FULL), intArrayOf(0, adjacentEnd))
        }
        if (direct > -1) return FuzzyMatch(NOT_START - word.length, intArrayOf(direct, direct + pattern.length))
        if (adjacentTo == len) return FuzzyMatch(CASE_FOLD + NOT_START - word.length, intArrayOf(adjacentStart, adjacentEnd))
        if (byWordTo == len) {
            return result(BY_WORD + (if (byWordFolded) CASE_FOLD else 0) + NOT_START + (if (wordAdjacent) 0 else GAP), byWord, word)
        }
        return if (len == 2) null else result((if (any[0] != 0) NOT_START else 0) + CASE_FOLD + GAP, any, word)
    }

    /** `byWord[index] = at`: the array is as long as its highest index so far. */
    private fun record(index: Int, at: Int) {
        byWord[index] = at
        byWordLength = max(byWordLength, index + 1)
    }

    /** The match at [positions] (one per character of the pattern): neighbours are joined into one range. */
    private fun result(score: Int, positions: IntArray, word: String): FuzzyMatch {
        val ranges = ArrayList<Int>(positions.size * 2)
        for (k in positions.indices) {
            val pos = positions[k]
            val to = pos + (if (astral) sizeOf(word.codePointAt(pos)) else 1)
            if (ranges.isNotEmpty() && ranges[ranges.size - 1] == pos) {
                ranges[ranges.size - 1] = to
            } else {
                ranges.add(pos)
                ranges.add(to)
            }
        }
        return FuzzyMatch(score - word.length, ranges.toIntArray())
    }

    private companion object {
        const val GAP = -1100
        const val NOT_START = -700
        const val CASE_FOLD = -200
        const val BY_WORD = -100
        const val NOT_FULL = -100

        /** CodeMirror looks at most this far into a word. */
        const val MAX_WORD = 200

        const val NON_WORD = 0
        const val UPPER = 1
        const val LOWER = 2

        fun sizeOf(codePoint: Int): Int = if (codePoint < 0x10000) 1 else 2

        /** The kind of a character, which tells where a word starts: a lower case letter or digit, an upper case letter, or neither. */
        fun typeOf(next: Int): Int {
            if (next < 0xFF) {
                return when (next) {
                    in 48..57, in 97..122 -> LOWER
                    in 65..90 -> UPPER
                    else -> NON_WORD
                }
            }
            val ch = String(Character.toChars(next))
            return if (ch != ch.lowercase()) UPPER else if (ch != ch.uppercase()) LOWER else NON_WORD
        }
    }
}
