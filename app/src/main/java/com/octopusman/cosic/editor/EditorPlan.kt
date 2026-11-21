package com.octopusman.cosic.editor

import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cosic.script.TokenSpan
import kotlin.math.ceil

/** The sizes of the editor's text and of what is laid over it (INLINE §3.4). */
internal object EditorMetrics {
    /** The padding of the code inside its field. */
    val PadX = 12.dp
    val PadY = 8.dp

    /** The size of the code text, and the height of a line at that size, when no program asked for another. */
    val FontSize = 14.sp
    val LineHeight = 20.sp

    /** What a slider takes: its control is this high and ends this far in front of the number it moves. */
    val SliderHeight = 24.dp
    val SliderMargin = 4.dp

    /** The least width of a slider's control: a narrower gap hides it. */
    val SliderMinWidth = 24.dp

    /** The gap in the text for a slider's control: this many no-break spaces in front of the number. */
    const val SLIDER_GAP_CHARS = 9
    const val SLIDER_GAP = "\u00A0\u00A0\u00A0\u00A0\u00A0\u00A0\u00A0\u00A0\u00A0"

    /** A widget takes at most this many rows of text (the field's layout grows with every row). */
    const val MAX_BLOCK_ROWS = 120

    /** The line height per font size: 20 for 14. */
    const val LINE_HEIGHT_PER_FONT = 20f / 14f
}

/** The number of text rows the widget of [heightDp] takes at [density] when a row is [rowPx] tall: at least 1 and at most [EditorMetrics.MAX_BLOCK_ROWS]. */
internal fun blockRows(heightDp: Float, density: Float, rowPx: Int): Int {
    val heightPx = maxOf(1, ceil(heightDp * density).toInt())
    return ceil(heightPx.toDouble() / maxOf(1, rowPx)).toInt().coerceIn(1, EditorMetrics.MAX_BLOCK_ROWS)
}

/** A widget's size in dp. */
internal data class BlockSize(val widthDp: Float, val heightDp: Float)

/**
 * The size [seed] is shown at when [availableWidthDp] is the room there is: the size its call asked for when that fits, else (`k = available / width`) the shape-keeping ones
 * (spiral, pitch wheel, spectrum) scaled down by `k`, and every other one as wide as the room, with the height the call asked for. No room known (not above 0) is room enough.
 */
internal fun fitBlock(seed: BlockSeed, availableWidthDp: Float): BlockSize {
    if (availableWidthDp <= 0f || seed.widthDp <= availableWidthDp) return BlockSize(seed.widthDp, seed.heightDp)
    return if (seed.type.keepsAspect) {
        val k = availableWidthDp / seed.widthDp
        BlockSize(availableWidthDp, seed.heightDp * k)
    } else {
        BlockSize(availableWidthDp, seed.heightDp)
    }
}

/** The 1-based line of [text] that [offset] is on (1 + the line breaks in front of it). */
internal fun lineNumberAt(text: String, offset: Int): Int {
    var line = 1
    for (i in 0 until offset.coerceIn(0, text.length)) if (text[i] == '\n') line++
    return line
}

/** A widget as the editor lays it out: its [anchor], the size it is shown at, the [rows] of text it takes under its call, and the 1-based [line] of its call. */
internal class PlannedBlock(val anchor: BlockAnchor, val widthDp: Float, val heightDp: Float, val rows: Int, val line: Int) {
    val id: String get() = anchor.id
}

/** A slider as the editor lays it out: its [anchor], where its number literal ends ([literalEnd]) and the 1-based [line] it is on. */
internal class PlannedSlider(val anchor: SliderAnchor, val literalEnd: Int, val line: Int) {
    val id: String get() = anchor.id
}

/**
 * What the editor shows over the code and the text that makes room for it: the [map] to the shown text, and every widget and slider that has a place in it. Made by [planEditor]
 * for one text; it is a pure value, so the same text and anchors always make the same plan.
 */
internal class EditorPlan(val map: InsertionMap, val blocks: List<PlannedBlock>, val sliders: List<PlannedSlider>) {
    private val blockById = blocks.associateBy { it.id }
    private val sliderById = sliders.associateBy { it.id }

    fun block(id: String): PlannedBlock? = blockById[id]

    fun slider(id: String): PlannedSlider? = sliderById[id]
}

/**
 * The plan for [text] (its [tokens] being what the highlighter found in it) with the widgets and sliders at [anchors]. A widget is given the rows its size needs under the end of
 * its call: `rows + 1` line breaks, which cut the call's line there (what comes after the call is on the row below the widget, as in CodeMirror). A slider is given a gap of
 * [EditorMetrics.SLIDER_GAP_CHARS] no-break spaces in front of its number; one whose number is no longer one has none. [contentWidthDp] is the room for a widget, [density] the
 * pixels per dp and [rowPx] the height of a row of text.
 */
internal fun planEditor(
    text: String,
    tokens: List<TokenSpan>,
    anchors: EditorAnchors,
    contentWidthDp: Float,
    density: Float,
    rowPx: Int,
): EditorPlan {
    val insertions = ArrayList<Insertion>(anchors.blocks.size + anchors.sliders.size)
    val blocks = ArrayList<PlannedBlock>(anchors.blocks.size)
    for (anchor in anchors.blocks) {
        val size = fitBlock(anchor.seed, contentWidthDp)
        val rows = blockRows(size.heightDp, density, rowPx)
        val at = anchor.offset.coerceIn(0, text.length)
        insertions += Insertion(at, "\n".repeat(rows + 1), false, anchor.id)
        blocks += PlannedBlock(anchor, size.widthDp, size.heightDp, rows, lineNumberAt(text, at))
    }
    val sliders = ArrayList<PlannedSlider>(anchors.sliders.size)
    for (anchor in anchors.sliders) {
        val end = literalEnd(text, tokens, anchor.offset) ?: continue
        insertions += Insertion(anchor.offset, EditorMetrics.SLIDER_GAP, true, anchor.id)
        sliders += PlannedSlider(anchor, end, lineNumberAt(text, anchor.offset))
    }
    return EditorPlan(InsertionMap.of(text.length, insertions), blocks, sliders)
}
