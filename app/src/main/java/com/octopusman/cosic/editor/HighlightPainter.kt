package com.octopusman.cosic.editor

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import com.octopusman.cosic.visual.parseCssColor
import cosic.SourceRange

/** The rows and characters of a [TextLayoutResult], for [blockRect], [sliderRect] and [highlightRects]. */
internal class TextLayoutRows(private val layout: TextLayoutResult) : RowGeometry {
    override val textLength: Int get() = layout.layoutInput.text.length
    override val lineCount: Int get() = layout.lineCount

    override fun lineForOffset(offset: Int): Int = layout.getLineForOffset(offset)

    override fun lineTop(line: Int): Float = layout.getLineTop(line)

    override fun lineBottom(line: Int): Float = layout.getLineBottom(line)

    override fun lineLeft(line: Int): Float = layout.getLineLeft(line)

    override fun lineRight(line: Int): Float = layout.getLineRight(line)

    override fun charLeft(offset: Int): Float = layout.getBoundingBox(offset).left

    override fun charRight(offset: Int): Float = layout.getBoundingBox(offset).right
}

/** The styles `markcss` strings parse to, kept so that a string that comes with every event of a pattern is read once. */
internal class MarkStyles {
    private val cache = HashMap<String, MarkStyle>()

    fun of(css: String): MarkStyle {
        if (cache.size > MAX_CACHED) cache.clear()
        return cache.getOrPut(css) { parseMarkCss(css) }
    }

    private companion object {
        const val MAX_CACHED = 256
    }
}

/** The style a highlight is drawn in: its `markcss` when it has one (Strudel gives it as the whole style), else an outline of 2 px in its `color`, [foreground] when that is unset. */
internal fun styleOf(highlight: ActiveHighlight, foreground: Color, styles: MarkStyles): MarkStyle {
    val css = highlight.markcss?.takeIf { it.isNotEmpty() }
    if (css != null) return styles.of(css)
    val color = highlight.color?.let(::parseCssColor) ?: foreground
    return MarkStyle(null, defaultOutline(color))
}

/** A style of the text of the code at [range] that a `markcss` asks for. */
internal data class MarkSpan(val range: SourceRange, val style: SpanStyle)

/** The highlights whose `markcss` asks for a style of the text itself (colour, weight, and so on): the rest is drawn over the text and costs no layout. */
internal fun textStyledHighlights(highlights: List<ActiveHighlight>, styles: MarkStyles): List<MarkSpan> =
    highlights.mapNotNull { h ->
        val css = h.markcss?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        styles.of(css).span?.let { MarkSpan(h.range, it) }
    }

/**
 * Draws the outlines of [highlights] over the text (Strudel draws a mark of `outline: solid 2px <color>` around the code that sounds). [map] takes a highlight's range to the shown
 * text, [rows] says where that is, [padLeft] and [padTop] are what the field has around its text. An outline is drawn outside the marked text, as CSS draws it.
 */
internal fun DrawScope.drawHighlights(
    highlights: List<ActiveHighlight>,
    rows: RowGeometry,
    map: InsertionMap,
    padLeft: Float,
    padTop: Float,
    foreground: Color,
    styles: MarkStyles,
) {
    for (highlight in highlights) {
        val outline = styleOf(highlight, foreground, styles).outline ?: continue
        val from = map.spanStart(highlight.range.start)
        val to = map.spanEnd(highlight.range.end)
        for (rect in highlightRects(rows, from, to)) drawOutline(rect.translate(padLeft, padTop), outline, foreground)
    }
}

private fun DrawScope.drawOutline(box: Rect, outline: MarkOutline, foreground: Color) {
    val width = (outline.widthDp * density).coerceAtLeast(1f)
    val color = outline.color ?: foreground
    when (outline.style) {
        OutlineStyle.SOLID -> strokeBox(box, color, width, width / 2f)
        OutlineStyle.DASHED -> strokeBox(box, color, width, width / 2f, PathEffect.dashPathEffect(floatArrayOf(width * 3, width * 2)))
        OutlineStyle.DOTTED -> strokeBox(box, color, width, width / 2f, PathEffect.dashPathEffect(floatArrayOf(0.1f, width * 2)), StrokeCap.Round)
        OutlineStyle.DOUBLE -> {
            // two lines of a third of the width with a gap of a third between them
            val line = (width / 3f).coerceAtLeast(1f)
            strokeBox(box, color, line, line / 2f)
            strokeBox(box, color, line, width - line / 2f)
        }
    }
}

/** A stroke of [width] whose middle is [outset] outside the edge of [box]. */
private fun DrawScope.strokeBox(box: Rect, color: Color, width: Float, outset: Float, effect: PathEffect? = null, cap: StrokeCap = StrokeCap.Butt) {
    drawRect(
        color = color,
        topLeft = Offset(box.left - outset, box.top - outset),
        size = Size(box.width + 2 * outset, box.height + 2 * outset),
        style = Stroke(width = width, cap = cap, pathEffect = effect),
    )
}

/** The flash of an evaluation: [color] behind every piece of code that is text, but not behind the rows and gaps the editor made room for the widgets with. */
internal fun DrawScope.drawFlash(rows: RowGeometry, map: InsertionMap, padLeft: Float, padTop: Float, color: Color) {
    var from = 0
    for (i in 0..map.size) {
        val to = if (i == map.size) map.transformedLength else map.transformedStart(i)
        for (rect in highlightRects(rows, from, to)) {
            val r = rect.translate(padLeft, padTop)
            drawRect(color, Offset(r.left, r.top), Size(r.width, r.height))
        }
        if (i < map.size) from = map.transformedEnd(i)
    }
}
