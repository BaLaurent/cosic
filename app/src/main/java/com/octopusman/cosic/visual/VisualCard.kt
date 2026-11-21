package com.octopusman.cosic.visual

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.octopusman.cosic.R
import com.octopusman.cosic.ui.tagged
import cosic.visual.VisualSession
import cosic.visual.VisualSurface
import cosic.visual.WidgetType

/** The name of a widget type for accessibility (`strings_4_3.xml`). */
internal fun widgetLabelRes(type: WidgetType): Int = when (type) {
    WidgetType.PIANOROLL -> R.string.visual_pianoroll
    WidgetType.PUNCHCARD -> R.string.visual_punchcard
    WidgetType.SPIRAL -> R.string.visual_spiral
    WidgetType.SCOPE -> R.string.visual_scope
    WidgetType.PITCHWHEEL -> R.string.visual_pitchwheel
    WidgetType.SPECTRUM -> R.string.visual_spectrum
    WidgetType.PRINT -> R.string.visual_print
}

/**
 * The picture of one widget of the program (a `._pianoroll()`, `._scope()`, `._spiral()`, `._pitchwheel()`, `._spectrum()`, `._punchcard()`, or the lines of a `._print()`),
 * placed by the editor under the line that made it. It is [surface] of [session], drawn at the size the call asked for ([VisualSurface.width] x [VisualSurface.height] logical px,
 * the default sizes of the widgets) or, on a narrower screen, as wide as the room and as tall as the same shape asks. [revision] is read while drawing: when it changes
 * (the host counts a frame that changed something) the card repaints, without recomposing. With [fill] the card is as big as the box it is given (the editor lays the box out, at
 * the size of the call or smaller on a narrow screen) and ignores the shape of the surface.
 */
@Composable
fun VisualCard(session: VisualSession, surface: VisualSurface, revision: () -> Long, modifier: Modifier = Modifier, fill: Boolean = false) {
    val type = surface.widget?.type ?: return // the background is not a card: see VisualBackground
    val description = stringResource(widgetLabelRes(type))
    if (type == WidgetType.PRINT) {
        PrintCard(
            lines = { session.printLines(surface.key) },
            revision = revision,
            width = surface.width.dp,
            height = surface.height.dp,
            modifier = modifier.tagged("visual_${surface.key}", description),
            fill = fill,
        )
    } else {
        VisualSurfaceImage(session, surface, revision, description, modifier, fill)
    }
}

/**
 * The widget's canvas as an image; the content description is given, so this can be shown without the app's string resources. The image is as wide as the room (at most the
 * surface) with the surface's shape, or, with [fill], as big as the box it is given.
 */
@Composable
internal fun VisualSurfaceImage(
    session: VisualSession,
    surface: VisualSurface,
    revision: () -> Long,
    description: String,
    modifier: Modifier = Modifier,
    fill: Boolean = false,
) {
    val density = LocalDensity.current.density
    val painter = remember(session, surface, density) { SurfacePainter(session, surface, surface.width, surface.height, density) }
    val w = surface.width.coerceAtLeast(1.0)
    val h = surface.height.coerceAtLeast(1.0)
    val shape = if (fill) Modifier.fillMaxSize() else Modifier.widthIn(max = w.dp).fillMaxWidth().aspectRatio((w / h).toFloat())
    Canvas(modifier = modifier.then(shape).tagged("visual_${surface.key}", description)) {
        revision() // reading it here makes a changed frame redo this drawing and nothing else
        painter.refresh()
        drawImage(painter.image, dstSize = IntSize(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1)), filterQuality = FilterQuality.Medium)
    }
}

/**
 * The visuals that draw behind the code (`.pianoroll()`, `.punchcard()`, `.spiral()`... without the underscore, and `.scope()`): one picture as big as the place this is given.
 * Draws nothing when the program has no background visual. Put it behind the text of the editor; it takes no input.
 */
@Composable
fun VisualBackground(session: VisualSession?, revision: () -> Long, modifier: Modifier = Modifier) {
    if (session == null || !session.hasBackground) return
    val density = LocalDensity.current.density
    val surface = session.surfaces.first()
    val painter = remember(session, density) { SurfacePainter(session, surface, 1.0, 1.0, density) }
    Canvas(modifier.fillMaxSize()) {
        revision()
        painter.resize((size.width / density).toDouble().coerceAtLeast(1.0), (size.height / density).toDouble().coerceAtLeast(1.0))
        painter.refresh()
        drawImage(painter.image, dstSize = IntSize(size.width.toInt().coerceAtLeast(1), size.height.toInt().coerceAtLeast(1)), filterQuality = FilterQuality.Medium)
    }
}
