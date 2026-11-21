package com.octopusman.cosic.editor

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.octopusman.cosic.visual.VisualCard
import com.octopusman.cosic.widgets.SliderCard
import cosic.SliderConfig
import cosic.io.SliderBank
import cosic.visual.VisualSession
import cosic.visual.VisualSurface

/** One thing the program drew or made to be moved: a slider, or the picture of a widget. */
internal sealed interface WidgetItem {
    /** Where it is in the code (the end of the call for a widget), which orders the items. */
    val offset: Int

    class Slider(val live: LiveSlider) : WidgetItem {
        override val offset: Int get() = live.start
    }

    class Visual(val surface: VisualSurface) : WidgetItem {
        override val offset: Int get() = surface.widget?.callEnd?.takeIf { it >= 0 } ?: Int.MAX_VALUE
    }
}

/** The sliders and the inline widgets of the program in the order they are written. The background visual is no widget: it is drawn behind the code. */
internal fun widgetItems(sliders: List<LiveSlider>, session: VisualSession?): List<WidgetItem> {
    val visuals = session?.surfaces.orEmpty().filter { it.widget != null }.map { WidgetItem.Visual(it) }
    return (sliders.map { WidgetItem.Slider(it) } + visuals).sortedBy { it.offset }
}

/**
 * INTERIM: where the sliders and the inline widgets of a program are shown until the editor places them under the lines that make them (Task 4.15 replaces this
 * composable by those hosts, and the view model state it reads stays: [EditorViewModel.sliders], [EditorViewModel.visuals], [EditorViewModel.sliderBank]).
 * A plain column under the editor, at most [maxHeight] tall and scrolling when it is taller; nothing at all when the program has none.
 */
@Composable
internal fun WidgetHost(
    items: List<WidgetItem>,
    text: String,
    session: VisualSession?,
    revision: () -> Long,
    sliderBank: SliderBank,
    onSliderRewrite: (SliderConfig, String) -> Unit,
    onSliderDragStart: () -> Unit,
    onSliderDragEnd: () -> Unit,
    maxHeight: Dp,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    Column(modifier.heightIn(max = maxHeight).verticalScroll(rememberScrollState())) {
        for (item in items) {
            when (item) {
                is WidgetItem.Slider -> key(item.live.spec.id) {
                    SliderCard(
                        spec = item.live.spec,
                        line = SliderTracking.lineOf(text, item.live.start),
                        bank = sliderBank,
                        onRewrite = onSliderRewrite,
                        onGestureStart = onSliderDragStart,
                        onGestureEnd = onSliderDragEnd,
                    )
                }
                is WidgetItem.Visual -> if (session != null) key(item.surface.key) {
                    VisualCard(session, item.surface, revision, Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                }
            }
        }
    }
}
