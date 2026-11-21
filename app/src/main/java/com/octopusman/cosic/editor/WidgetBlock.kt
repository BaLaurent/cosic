package com.octopusman.cosic.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import com.octopusman.cosic.R
import com.octopusman.cosic.visual.VisualCard
import cosic.visual.VisualSession
import cosic.visual.WidgetType

/** The string that names a widget of [type] for accessibility; `%1$d` is the line of its call. */
internal fun widgetLabelRes(type: WidgetType): Int = when (type) {
    WidgetType.PIANOROLL -> R.string.editor_widget_pianoroll
    WidgetType.PUNCHCARD -> R.string.editor_widget_punchcard
    WidgetType.SPIRAL -> R.string.editor_widget_spiral
    WidgetType.SCOPE -> R.string.editor_widget_scope
    WidgetType.PITCHWHEEL -> R.string.editor_widget_pitchwheel
    WidgetType.SPECTRUM -> R.string.editor_widget_spectrum
    WidgetType.PRINT -> R.string.editor_widget_print
}

/**
 * The picture of one inline widget, under the line of the call that made it: the card of the surface [session] has for [block], as big as the box the editor laid out for it (the
 * incoming constraints are fixed). A block that is not [visible] (far off the screen) holds its [revision] still, so that the frames of the visuals do not redraw it. Its one
 * accessibility node is named for the call and carries the widget's id as its tag.
 */
@Composable
internal fun WidgetBlock(block: PlannedBlock, session: VisualSession?, revision: () -> Long, visible: Boolean, modifier: Modifier = Modifier) {
    val surface = session?.surfaces?.firstOrNull { it.widget?.id == block.id } ?: return
    val type = surface.widget?.type ?: return
    val label = stringResource(widgetLabelRes(type), block.line)
    val frozen = remember(session) { { 0L } }
    Box(modifier.fillMaxSize().testTag(block.id).clearAndSetSemantics { contentDescription = label }) {
        VisualCard(session, surface, if (visible) revision else frozen, Modifier.fillMaxSize(), fill = true)
    }
}
