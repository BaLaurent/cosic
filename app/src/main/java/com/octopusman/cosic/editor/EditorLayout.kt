package com.octopusman.cosic.editor

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import kotlin.math.ceil
import kotlin.math.roundToInt

/** The slot of a widget's box in the editor's layout. */
internal data class BlockSlot(val id: String)

/** The slot of a slider's control in the editor's layout. */
internal data class SliderSlot(val id: String)

private data object FieldSlot

/** The colour the flash of an evaluation puts behind the code (`rgba(255,255,255,.4)` of Strudel's flash). */
private val FLASH_COLOR = Color.White.copy(alpha = 0.4f)

/** What the editor's layout knows about the field it laid out. */
internal class EditorGeometry {
    /**
     * The layout of the field's text as the field last measured it. Not state: it is written by the measure of the field and read right after it, where the widgets are
     * placed, and when the highlights are drawn.
     */
    var layout: TextLayoutResult? = null

    /** The ids of the widgets that are on the screen or near it, null while that is not known (all of them are then). */
    val visible = mutableStateOf<Set<String>?>(null)
}

/**
 * The editor's layout: the text field, the widgets under their calls and the sliders in front of their numbers, all in one plain layout (no subcomposition, so that it can live in
 * movable content). It measures the field first; the field reports the layout of its text, which says where the rows made for the widgets and the gaps made for the sliders are
 * (the [plan] says what was made), and the layout puts the widget boxes and slider controls there, over the field. It also draws, in the draw phase: the flash behind the code and
 * the outlines of [highlights] over it, so that neither makes the text layout again.
 *
 * [content] holds the field (with the field's slot), a [BlockSlot] for every widget and a [SliderSlot] for every slider of the plan; one that cannot be placed (its rows are not in
 * the layout yet) is left out. [rowPx] is the height of a row of text and [onRowPx] is told when the layout says it is another. [scroll] and [viewportHeightPx] say which widgets are
 * visible.
 */
@Composable
internal fun EditorLayout(
    plan: EditorPlan,
    geometry: EditorGeometry,
    scroll: ScrollState,
    viewportHeightPx: Int,
    rowPx: Int,
    onRowPx: (Int) -> Unit,
    highlights: () -> List<ActiveHighlight>,
    flashing: () -> Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val padLeft = with(density) { EditorMetrics.PadX.toPx() }
    val padTop = with(density) { EditorMetrics.PadY.toPx() }
    val sliderHeight = with(density) { EditorMetrics.SliderHeight.toPx() }
    val sliderMargin = with(density) { EditorMetrics.SliderMargin.toPx() }
    val sliderMinWidth = with(density) { EditorMetrics.SliderMinWidth.toPx() }
    val foreground = MaterialTheme.colorScheme.onBackground
    val styles = remember { MarkStyles() }
    val currentOnRowPx by rememberUpdatedState(onRowPx)
    val dpToPx = density.density

    val policy = remember(plan, rowPx, dpToPx, padLeft, padTop, scroll, viewportHeightPx, geometry) {
        MeasurePolicy { measurables, constraints ->
            val field = measurables.first { it.layoutId == FieldSlot }.measure(constraints)
            val shown = geometry.layout
            // A middle line is a full row of the layout; the first and the last may be shorter when the platform trims them.
            if (shown != null && shown.lineCount >= 3) {
                val measured = ceil(shown.getLineBottom(1) - shown.getLineTop(1)).toInt()
                if (measured > 0 && measured != rowPx) currentOnRowPx(measured)
            }
            val rows = shown?.takeIf { it.layoutInput.text.length == plan.map.transformedLength }?.let(::TextLayoutRows)
            val placed = ArrayList<Pair<Placeable, IntOffset>>()
            val rects = HashMap<String, Rect>()
            if (rows != null) {
                for (m in measurables) {
                    when (val slot = m.layoutId) {
                        is BlockSlot -> {
                            val block = plan.block(slot.id) ?: continue
                            val size = Size(block.widthDp * dpToPx, ceil(block.heightDp * dpToPx))
                            val r = blockRect(plan.map, plan.map.indexOf(slot.id), rows, size, padLeft, padTop) ?: continue
                            placed += m.measure(Constraints.fixed(r.width.roundToInt().coerceAtLeast(0), r.height.roundToInt().coerceAtLeast(0))) to IntOffset(r.left.roundToInt(), r.top.roundToInt())
                            rects[slot.id] = r
                        }
                        is SliderSlot -> {
                            val r = sliderRect(plan.map, plan.map.indexOf(slot.id), rows, sliderHeight, sliderMargin, sliderMinWidth, padLeft, padTop) ?: continue
                            placed += m.measure(Constraints.fixed(r.width.roundToInt().coerceAtLeast(0), r.height.roundToInt().coerceAtLeast(0))) to IntOffset(r.left.roundToInt(), r.top.roundToInt())
                        }
                    }
                }
            }
            layout(field.width, field.height) {
                field.place(0, 0)
                for ((placeable, offset) in placed) placeable.place(offset)
                val ids = visibleIds(rects, scroll.value.toFloat(), viewportHeightPx.toFloat(), viewportHeightPx / 2f)
                if (geometry.visible.value != ids) geometry.visible.value = ids
            }
        }
    }

    Layout(
        content = content,
        modifier = modifier.drawWithContent {
            val rows = geometry.layout?.takeIf { it.layoutInput.text.length == plan.map.transformedLength }?.let(::TextLayoutRows)
            if (rows != null && flashing()) drawFlash(rows, plan.map, padLeft, padTop, FLASH_COLOR)
            drawContent()
            if (rows != null) drawHighlights(highlights(), rows, plan.map, padLeft, padTop, foreground, styles)
        },
        measurePolicy = policy,
    )
}

/** The marker of the field in the editor's layout. */
internal fun Modifier.fieldSlot(): Modifier = layoutId(FieldSlot)

/** The marker of the control of slider [id] in the editor's layout. */
internal fun Modifier.sliderSlot(id: String): Modifier = layoutId(SliderSlot(id))

/**
 * The box of the widget [block] in the editor: it takes the taps (the editor does not handle events inside a widget, as Strudel's does not) and shows [content], told whether the
 * widget is on the screen or near it.
 */
@Composable
internal fun BlockFrame(block: PlannedBlock, geometry: EditorGeometry, content: @Composable (PlannedBlock, Boolean) -> Unit) {
    key(block.id) {
        val visible = geometry.visible.value?.contains(block.id) ?: true
        Box(Modifier.layoutId(BlockSlot(block.id)).pointerInput(Unit) { detectTapGestures { } }) { content(block, visible) }
    }
}
