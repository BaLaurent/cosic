package com.octopusman.cosic.editor

import com.octopusman.cosic.widgets.SliderEdits
import cosic.SliderConfig
import cosic.SourceRange

/** A slider of the program, with where the number literal of its first argument is in the text being edited now. */
class LiveSlider(val spec: SliderConfig, val start: Int, val length: Int) {
    val range: SourceRange get() = SourceRange(start, start + length)

    override fun equals(other: Any?): Boolean = other is LiveSlider && other.spec == spec && other.start == start && other.length == length

    override fun hashCode(): Int = (spec.hashCode() * 31 + start) * 31 + length
}

/**
 * Where the sliders' literals are as the text is edited: the program says where they were when it was evaluated, and every edit after that moves them (or
 * takes one away, when its literal is gone). The same job the highlight mapper does for the highlights, for the one thing that is written back into the code.
 * Pure functions; a slider whose first argument is no literal has no place and no control, so it is not tracked.
 */
object SliderTracking {
    /**
     * The sliders of [specs], which were found in [evaluated], as they sit in [current] (the text may have been edited while the program was evaluated). A slider
     * whose literal an edit touched, or that is no longer a number, is left out.
     */
    fun place(evaluated: String, current: String, specs: List<SliderConfig>): List<LiveSlider> {
        val mapper = HighlightMapper(evaluated)
        val placed = ArrayList<LiveSlider>(specs.size)
        for (spec in specs) {
            if (spec.value == null) continue
            val range = mapper.map(listOf(SourceRange(spec.from, spec.to)), current).firstOrNull() ?: continue
            val length = SliderEdits.literalLength(current, range.start) ?: continue
            placed.add(LiveSlider(spec, range.start, length))
        }
        return placed
    }

    /**
     * [sliders] after the text changed from [before] to [after], taken as one replaced region (what is between the longest common beginning and the longest common end):
     * a literal after the region moves by the change of length; one that the region begins in, or after, keeps its start, and its length is read again (typing a digit
     * after it, or over it, makes it a longer or shorter number); one that the region begins before and reaches into is gone, and so is one that is no number any more.
     * A text typed right in front of a number whose first characters are the number's own ("0" in front of "0.5") cannot be told from a character typed into it: the
     * region begins after them, and the number is read as the longer one. Edits are keystrokes, so this is what it is when a digit is typed at the start of a number.
     */
    fun afterEdit(sliders: List<LiveSlider>, before: String, after: String): List<LiveSlider> {
        if (sliders.isEmpty() || before == after) return sliders
        val limit = minOf(before.length, after.length)
        var prefix = 0
        while (prefix < limit && before[prefix] == after[prefix]) prefix++
        var suffix = 0
        while (suffix < limit - prefix && before[before.length - 1 - suffix] == after[after.length - 1 - suffix]) suffix++
        val replacedEnd = before.length - suffix
        val delta = after.length - before.length
        val kept = ArrayList<LiveSlider>(sliders.size)
        for (slider in sliders) {
            val start = when {
                slider.start >= replacedEnd -> slider.start + delta
                slider.start <= prefix -> slider.start
                else -> continue
            }
            val length = SliderEdits.literalLength(after, start) ?: continue
            kept.add(if (start == slider.start && length == slider.length) slider else LiveSlider(slider.spec, start, length))
        }
        return if (kept == sliders) sliders else kept
    }

    /** The 1-based line of [text] that [offset] is on. */
    fun lineOf(text: String, offset: Int): Int {
        var line = 1
        val end = offset.coerceIn(0, text.length)
        for (i in 0 until end) if (text[i] == '\n') line++
        return line
    }
}
