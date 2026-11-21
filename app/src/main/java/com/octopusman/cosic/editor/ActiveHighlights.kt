package com.octopusman.cosic.editor

import cosic.HighlightEvent
import cosic.SourceRange

/**
 * The steps that sound at engine frame [playhead], as the editor marks them (`highlightMiniLocations` of Strudel's highlight.mjs): the events that
 * began at or before it and end at or after it (both ends count), and for every place of the code (a [SourceRange]) only the event that began last, whose
 * [HighlightEvent.color] and [HighlightEvent.markcss] the mark takes. A place that two events share (`"bd*2"`'s step, a sample of two patterns) is marked once.
 * The order is the order the places first appear in [events].
 */
internal fun activeHighlights(events: List<HighlightEvent>, playhead: Long): List<ActiveHighlight> {
    var found: LinkedHashMap<SourceRange, HighlightEvent>? = null
    for (event in events) {
        if (event.startFrame > playhead || playhead > event.endFrame) continue
        val map = found ?: LinkedHashMap<SourceRange, HighlightEvent>().also { found = it }
        for (range in event.ranges) {
            val kept = map[range]
            if (kept == null || event.startFrame > kept.startFrame) map[range] = event
        }
    }
    val map = found ?: return emptyList()
    return map.map { (range, event) -> ActiveHighlight(range, event.color, event.markcss) }
}

/** [highlights] in the coordinates of the text being edited, from those of the text [HighlightMapper] was made for; a highlight that an edit touches is dropped. */
internal fun HighlightMapper.mapActive(highlights: List<ActiveHighlight>, currentText: String): List<ActiveHighlight> {
    if (highlights.isEmpty()) return highlights
    val mapped = ArrayList<ActiveHighlight>(highlights.size)
    for (highlight in highlights) {
        val range = map(listOf(highlight.range), currentText).firstOrNull() ?: continue
        mapped.add(if (range == highlight.range) highlight else highlight.copy(range = range))
    }
    return mapped
}
