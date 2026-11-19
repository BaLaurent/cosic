package cosic.player

import cosic.HighlightEvent

private const val CAPACITY = 512

/**
 * The events the editor may still highlight, oldest first: those scheduled recently and not yet over for long.
 * Holds at most 512; adding more drops the oldest. Audio thread only; [snapshot] is what gets published.
 */
internal class HighlightLog {
    private val events = ArrayList<HighlightEvent>()
    private var published: List<HighlightEvent> = emptyList()
    private var changed = false

    fun add(event: HighlightEvent) {
        if (events.size == CAPACITY) events.removeAt(0)
        events.add(event)
        changed = true
    }

    /** Drops every event that ended at or before [frame]. */
    fun expire(frame: Long) {
        var kept = 0
        for (event in events) if (event.endFrame > frame) events[kept++] = event
        if (kept == events.size) return
        while (events.size > kept) events.removeAt(events.size - 1)
        changed = true
    }

    fun clear() {
        if (events.isEmpty()) return
        events.clear()
        changed = true
    }

    /** An immutable copy of the events. The same list comes back until the log changes, so publishing it often is cheap. */
    fun snapshot(): List<HighlightEvent> {
        if (changed) {
            published = ArrayList(events)
            changed = false
        }
        return published
    }
}
