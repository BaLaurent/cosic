package com.octopusman.cosic.editor

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.math.ceil

/** The rows and the characters of the text as the field laid it out: what [blockRect] and [sliderRect] need of a `TextLayoutResult` (and a fake in the tests). */
internal interface RowGeometry {
    val textLength: Int
    val lineCount: Int

    fun lineForOffset(offset: Int): Int

    fun lineTop(line: Int): Float

    fun lineBottom(line: Int): Float

    fun lineLeft(line: Int): Float

    fun lineRight(line: Int): Float

    fun charLeft(offset: Int): Float

    fun charRight(offset: Int): Float
}

/**
 * Where the widget made for insertion [i] of [map] goes, in the pixels of the field, or null when its rows are not in the layout (the layout is of other text, or has not got
 * them). The insertion is the `rows + 1` line breaks under the end of a call: the first one ends the call's line, the rows after it are the widget's room and the last line
 * is what came after the call. The box is [size] wide and high, but no higher than the room the rows are really given, and starts under the call's line. [padLeft] and [padTop]
 * are what the field has around its text.
 */
internal fun blockRect(map: InsertionMap, i: Int, rows: RowGeometry, size: Size, padLeft: Float, padTop: Float): Rect? {
    if (i < 0 || i >= map.size) return null
    val start = map.transformedStart(i)
    val newlines = map.insertion(i).text.length
    if (start + 1 >= rows.textLength) return null
    val first = rows.lineForOffset(start + 1)
    val last = first + newlines - 2
    if (last >= rows.lineCount || last < first) return null
    val top = rows.lineTop(first)
    val region = rows.lineBottom(last) - top
    val height = minOf(ceil(size.height), region)
    return Rect(padLeft, top + padTop, padLeft + size.width, top + padTop + height)
}

/**
 * Where the control of the slider made for insertion [i] of [map] goes, in the pixels of the field: in the gap of the text in front of the number, [marginPx] short of the number,
 * [heightPx] high and centred on the row. A gap that a line break cuts in two (a large font on a narrow screen) holds the control on the row of the number, in the part of the gap
 * that is on it. Null when there is less than [minWidthPx] of it.
 */
internal fun sliderRect(map: InsertionMap, i: Int, rows: RowGeometry, heightPx: Float, marginPx: Float, minWidthPx: Float, padLeft: Float, padTop: Float): Rect? {
    if (i < 0 || i >= map.size) return null
    val start = map.transformedStart(i)
    val end = map.transformedEnd(i)
    if (end <= start || end > rows.textLength) return null
    val line = rows.lineForOffset(end - 1)
    // the row of the number starts inside the gap when the gap was cut, and then every character of it up to the number is a space of the gap
    val left = if (rows.lineForOffset(start) == line) rows.charLeft(start) else rows.lineLeft(line)
    val width = rows.charRight(end - 1) - left - marginPx
    if (width < minWidthPx) return null
    val centre = (rows.lineTop(line) + rows.lineBottom(line)) / 2f
    val top = centre - heightPx / 2f + padTop
    return Rect(padLeft + left, top, padLeft + left + width, top + heightPx)
}

/** The ids of the [rects] that are inside the viewport of [viewportPx] at [scrollPx], or within [marginPx] of it: the widgets that are worth painting. */
internal fun visibleIds(rects: Map<String, Rect>, scrollPx: Float, viewportPx: Float, marginPx: Float): Set<String> {
    val from = scrollPx - marginPx
    val to = scrollPx + viewportPx + marginPx
    var ids: HashSet<String>? = null
    for ((id, rect) in rects) {
        if (rect.bottom >= from && rect.top <= to) (ids ?: HashSet<String>().also { ids = it }).add(id)
    }
    return ids ?: emptySet()
}

/**
 * The boxes a mark around the shown text `[from, to)` is drawn on: one per row the text is on, from the left of its first character to the right of its last on the first and the
 * last row, and across the whole row in between. Empty for an empty range.
 */
internal fun highlightRects(rows: RowGeometry, from: Int, to: Int): List<Rect> {
    val a = from.coerceIn(0, rows.textLength)
    val b = to.coerceIn(0, rows.textLength)
    if (a >= b || rows.lineCount == 0) return emptyList()
    val first = rows.lineForOffset(a)
    val last = rows.lineForOffset(b - 1)
    val rects = ArrayList<Rect>(last - first + 1)
    for (line in first..last) {
        val left = if (line == first) rows.charLeft(a) else rows.lineLeft(line)
        val right = if (line == last) rows.charRight(b - 1) else rows.lineRight(line)
        if (right > left) rects += Rect(left, rows.lineTop(line), right, rows.lineBottom(line))
    }
    return rects
}
