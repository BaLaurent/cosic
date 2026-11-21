package com.octopusman.cosic.editor

import cosic.HighlightEvent
import cosic.SourceRange

/**
 * The steps that sound at engine frame [playhead], as the editor marks them (`highlightMiniLocations` of Strudel's highlight.mjs): the events that
 * began at or before it and end at or after it (both ends count), and for every place of the code (a [SourceRange]) only the event that began last, whose
 * [HighlightEvent.color] and [HighlightEvent.markcss] the mark takes. A place that two events share (`"bd*2"`'s step, a sample of two patterns) is marked once.
 * The order is the order the places first appear in [events].
 *
 * The ranges of an event are places in the text its program was evaluated from; [place] says where such a place is in the text being edited now (null: it is gone, and so is
 * its mark). Places are compared after that, so that events of two programs, from two texts, share a place only when they are in the same place of the text now.
 */
internal fun activeHighlights(
    events: List<HighlightEvent>,
    playhead: Long,
    place: (HighlightEvent, SourceRange) -> SourceRange? = { _, range -> range },
): List<ActiveHighlight> {
    var found: LinkedHashMap<SourceRange, HighlightEvent>? = null
    for (event in events) {
        if (event.startFrame > playhead || playhead > event.endFrame) continue
        for (range in event.ranges) {
            val here = place(event, range) ?: continue
            val map = found ?: LinkedHashMap<SourceRange, HighlightEvent>().also { found = it }
            val kept = map[here]
            if (kept == null || event.startFrame > kept.startFrame) map[here] = event
        }
    }
    val map = found ?: return emptyList()
    return map.map { (range, event) -> ActiveHighlight(range, event.color, event.markcss) }
}
