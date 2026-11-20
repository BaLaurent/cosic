package com.octopusman.cosic.visual

import cosic.visual.VisualSession

/**
 * What one frame of the visuals does, apart from Compose: the session on show is ticked with the cycle that is being heard; when the program stops, the session is reset
 * (the background is cleared at its next paint); when a program replaces another, the new session takes over what the old one had in view ([VisualSession.continueFrom]) before
 * its first tick. All calls come from one thread, the one that draws.
 *
 * [cycleAt] is `snapshot.cycleAt(playheadFrame)` of the engine (null while there is no snapshot) and [isPlaying] whether the engine plays.
 */
class VisualFrameDriver(private val cycleAt: () -> Double?, private val isPlaying: () -> Boolean) {
    /** The session on show, or null when the program has no visuals. */
    var session: VisualSession? = null
        private set

    private var wasPlaying = false

    /** True when the last [frame] changed nothing and the program does not play: nothing will change until the playing state or the session does, so the caller may sleep. */
    var settled: Boolean = true
        private set

    /** Shows [next] from the next [frame] on, as the replacement of the session on show (null: the program has none). The same session twice does nothing. */
    fun show(next: VisualSession?) {
        if (next === session) return
        if (next != null) next.continueFrom(session)
        session = next
        settled = false
    }

    /** One frame. Returns whether any surface changed (a surface needs painting when its [VisualSession.versionOf] differs from the one it was last painted at). */
    fun frame(): Boolean {
        val s = session
        if (s == null) {
            settled = true
            return false
        }
        val playing = isPlaying()
        var changed = false
        if (wasPlaying && !playing) {
            s.reset()
            changed = true
        }
        wasPlaying = playing
        val cycle = cycleAt()
        if (cycle != null && s.tick(cycle, playing)) changed = true
        settled = !playing && !changed
        return changed
    }

    /** Forgets that the program played, without resetting the session (the host left the screen). */
    fun detach() {
        wasPlaying = false
    }
}
