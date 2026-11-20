package com.octopusman.cosic.visual

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import cosic.visual.VisualSession
import kotlinx.coroutines.delay

/**
 * The frame loop of the visuals: once per display frame it ticks the [session] (through [VisualFrameDriver]) and counts a [revision] when something changed, which is
 * what the cards and the background read in their draw phase to repaint. One host serves every card and the background of the editor.
 *
 * Make it with [rememberVisualHost], which also runs the loop while it is in the composition.
 */
@Stable
class VisualHost internal constructor(cycleAt: () -> Double?, isPlaying: () -> Boolean) {
    private val driver = VisualFrameDriver(cycleAt, isPlaying)
    private val revisionState = mutableLongStateOf(0L)
    private var sessionState by mutableStateOf<VisualSession?>(null)

    /** The session on show; a card or the background draws surfaces of this one. */
    val session: VisualSession? get() = sessionState

    /** Counts the frames that changed something; read it where the drawing is, so only the drawing is redone. */
    val revision: Long get() = revisionState.longValue

    /** Shows [next], the session of the program just evaluated (null when it has none). It continues what the session on show had in view. */
    internal fun show(next: VisualSession?) {
        if (next === sessionState) return
        driver.show(next)
        sessionState = next
    }

    internal suspend fun run() {
        try {
            while (true) {
                if (driver.settled) delay(IDLE_POLL_MILLIS) // nothing moves: look again in a moment instead of every frame
                withFrameNanos {
                    if (driver.frame()) revisionState.longValue++
                }
            }
        } finally {
            driver.detach()
        }
    }

    private companion object {
        const val IDLE_POLL_MILLIS = 50L
    }
}

/**
 * The host for [session] (the visuals of the program that is current, `ProgramInfo.visuals as? VisualSession`; null when it has none), running its frame loop while this is in
 * the composition. [cycleAt] is `snapshot.cycleAt(playheadFrame)` of the engine, null when it cannot say; [isPlaying] whether the engine plays. Both are read once per frame, on
 * the main thread.
 */
@Composable
fun rememberVisualHost(session: VisualSession?, cycleAt: () -> Double?, isPlaying: () -> Boolean): VisualHost {
    val cycle by rememberUpdatedState(cycleAt)
    val playing by rememberUpdatedState(isPlaying)
    val host = remember { VisualHost({ cycle() }, { playing() }) }
    SideEffect { host.show(session) }
    LaunchedEffect(host) { host.run() }
    return host
}
