package com.octopusman.cosic.editor

import cosic.SourceRange

/**
 * Carries source ranges from the text the engine evaluated over to the text being edited now.
 *
 * The edit is taken to be the one region between the longest common prefix and the longest common
 * suffix of the two texts. A range before it stays, a range after it moves by the change in length,
 * and a range touching it is dropped. Main thread only.
 */
class HighlightMapper(private val evaluatedText: String) {
    // The common prefix and suffix for [cachedText], so a frame that maps again costs no text scan.
    private var cachedText: String? = null
    private var prefix = 0
    private var suffix = 0

    fun map(ranges: List<SourceRange>, currentText: String): List<SourceRange> {
        if (ranges.isEmpty()) return ranges
        if (currentText !== cachedText) {
            measureCommonEnds(currentText)
            cachedText = currentText
        }
        val delta = currentText.length - evaluatedText.length
        val changedEnd = evaluatedText.length - suffix
        return ranges.mapNotNull { range ->
            when {
                range.end <= prefix -> range
                range.start >= changedEnd -> SourceRange(range.start + delta, range.end + delta)
                else -> null
            }
        }
    }

    private fun measureCommonEnds(currentText: String) {
        val limit = minOf(evaluatedText.length, currentText.length)
        var p = 0
        while (p < limit && evaluatedText[p] == currentText[p]) p++
        // The suffix may not reach into the prefix, so the two never claim the same character.
        var s = 0
        while (s < limit - p && evaluatedText[evaluatedText.length - 1 - s] == currentText[currentText.length - 1 - s]) s++
        prefix = p
        suffix = s
    }
}
