package com.octopusman.cosic.visual

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import cosic.visual.VisualSession

/**
 * A screen that only the tests have: every widget of [session] as a card under a background visual, driven by a [VisualHost] whose playhead is the clock
 * ([cyclesPerSecond] cycles per second). The test lays it over the app's own activity.
 */
@Composable
internal fun VisualsTestScreen(session: VisualSession, cyclesPerSecond: Double = 0.5) {
    val started = remember { SystemClock.elapsedRealtime() }
    val host = rememberVisualHost(session = session, cycleAt = { (SystemClock.elapsedRealtime() - started) / 1000.0 * cyclesPerSecond }, isPlaying = { true })
    Box(Modifier.fillMaxSize().background(Color(0xFF15181E))) {
        VisualBackground(session, { host.revision })
        Column(Modifier.padding(8.dp)) {
            for (surface in session.surfaces) {
                val widget = surface.widget ?: continue
                Box(Modifier.padding(vertical = 4.dp)) { VisualSurfaceImage(session, surface, { host.revision }, widget.type.method) }
            }
        }
    }
}
