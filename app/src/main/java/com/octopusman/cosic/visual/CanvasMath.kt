package com.octopusman.cosic.visual

import cosic.visual.CssColor
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/*
 * The rules of the HTML canvas 2D context that [BitmapPaintCanvas] follows, as plain Kotlin (no android.graphics), so the JVM tests can pin them down: which style a canvas
 * keeps when it is given nonsense, how a font string is read, how an arc is swept, how wide text is squeezed, where a text baseline sits, how big a bitmap is.
 */

/** Where text sits relative to the `y` of `fillText`, as the font reports it: [ascent] is the distance above the alphabetic baseline and [descent] below it, both positive. */
internal fun baselineShift(baseline: String, ascent: Float, descent: Float): Float = when (baseline) {
    "top", "hanging" -> ascent // the baseline lies one ascent below y
    "middle" -> (ascent - descent) / 2f
    "bottom", "ideographic" -> -descent
    else -> 0f // alphabetic
}

/** The baselines a canvas knows; any other string is ignored by the setter. */
internal val TEXT_BASELINES = setOf("top", "hanging", "middle", "alphabetic", "ideographic", "bottom")

/** The line caps a canvas knows. */
internal val LINE_CAPS = setOf("butt", "round", "square")

/** `globalAlpha = value`: a number from 0 to 1 is taken, anything else (NaN, out of range) is ignored. */
internal fun acceptAlpha(value: Double, previous: Double): Double = if (value.isNaN() || value < 0.0 || value > 1.0) previous else value

/** `lineWidth = value`: a finite number above 0 is taken, anything else is ignored. */
internal fun acceptLineWidth(value: Double, previous: Double): Double = if (value.isNaN() || value.isInfinite() || value <= 0.0) previous else value

/** `fillStyle = style`: the new (text, ARGB) when [style] is a colour, else the previous pair (a canvas keeps its old style when it is given nonsense). */
internal fun acceptStyle(style: String, previousText: String, previousArgb: Int): Pair<String, Int> =
    CssColor.parse(style)?.let { style to it } ?: (previousText to previousArgb)

/** [argb] with its alpha multiplied by [globalAlpha] (0..1), the way a canvas composes the two. */
internal fun withGlobalAlpha(argb: Int, globalAlpha: Double): Int {
    val alpha = (argb ushr 24) and 0xFF
    val effective = (alpha * globalAlpha.coerceIn(0.0, 1.0)).roundToInt().coerceIn(0, 255)
    return (effective shl 24) or (argb and 0x00FFFFFF)
}

/** The same colour as a CSS string, for the style getters: `#rrggbb` when opaque, else `rgba(...)`. */
internal fun cssOf(argb: Int): String {
    val a = (argb ushr 24) and 0xFF
    val rgb = argb and 0x00FFFFFF
    return if (a == 255) "#%06x".format(rgb) else "rgba(${(rgb shr 16) and 0xFF}, ${(rgb shr 8) and 0xFF}, ${rgb and 0xFF}, ${"%.3f".format(java.util.Locale.ROOT, a / 255.0).trimEnd('0').trimEnd('.')})"
}

// ---- fonts ------------------------------------------------------------------------------------------------------------------------------

/** The generic families a font string can name; [OTHER] is a named family the system looks up. */
internal enum class FontFamilyKind { MONOSPACE, SANS_SERIF, SERIF, OTHER }

/** A CSS font as the canvas needs it: size in logical px, the family (as written, unquoted, first of the list) and its kind, weight and slant. */
internal data class FontSpec(
    val sizePx: Float,
    val family: String,
    val kind: FontFamilyKind,
    val bold: Boolean = false,
    val italic: Boolean = false,
) {
    companion object {
        /** The default of a canvas: `10px sans-serif`. */
        val DEFAULT = FontSpec(10f, "sans-serif", FontFamilyKind.SANS_SERIF)

        private val SIZE = Regex("""^(\d+(?:\.\d+)?|\.\d+)px(?:/\S+)?$""")

        /**
         * Reads a font shorthand (`12px monospace`, `bold 14px "Fira Code", monospace`, `italic 600 10px/1.2 serif`). Returns null when it does not name a size in px and a family,
         * which is what a canvas ignores. Only the first family of a list counts.
         */
        fun parse(text: String): FontSpec? {
            val tokens = tokenize(text.trim())
            val at = tokens.indexOfFirst { SIZE.matches(it) }
            if (at < 0 || at == tokens.lastIndex) return null
            val size = SIZE.matchEntire(tokens[at])!!.groupValues[1].toFloatOrNull()?.takeIf { it.isFinite() && it >= 0f } ?: return null
            var bold = false
            var italic = false
            for (word in tokens.subList(0, at)) {
                when (word.lowercase()) {
                    "bold", "bolder", "600", "700", "800", "900" -> bold = true
                    "italic", "oblique" -> italic = true
                    "normal", "small-caps", "100", "200", "300", "400", "500", "lighter" -> Unit
                    else -> return null
                }
            }
            val family = tokens.subList(at + 1, tokens.size).joinToString(" ").substringBefore(',').trim().trim('"', '\'').trim()
            if (family.isEmpty()) return null
            val kind = when (family.lowercase()) {
                "monospace" -> FontFamilyKind.MONOSPACE
                "sans-serif" -> FontFamilyKind.SANS_SERIF
                "serif" -> FontFamilyKind.SERIF
                else -> FontFamilyKind.OTHER
            }
            return FontSpec(size, family, kind, bold, italic)
        }

        /** Splits on whitespace, keeping a quoted family name whole. */
        private fun tokenize(text: String): List<String> {
            val out = ArrayList<String>()
            val cur = StringBuilder()
            var quote = 0.toChar()
            for (c in text) {
                when {
                    quote != 0.toChar() -> { cur.append(c); if (c == quote) quote = 0.toChar() }
                    c == '"' || c == '\'' -> { cur.append(c); quote = c }
                    c.isWhitespace() -> if (cur.isNotEmpty()) { out.add(cur.toString()); cur.clear() }
                    else -> cur.append(c)
                }
            }
            if (cur.isNotEmpty()) out.add(cur.toString())
            return out
        }
    }
}

/**
 * How `fillText(text, x, y, maxWidth)` treats [maxWidth]: null for "draw nothing" (zero or less, NaN), 1 for "as is" (no limit, or the text fits), else the horizontal
 * factor that squeezes the text to [maxWidth] ([measured] is its natural width).
 */
internal fun textSqueeze(measured: Double, maxWidth: Double?): Double? {
    if (maxWidth == null) return 1.0
    if (maxWidth.isNaN() || maxWidth <= 0.0) return null
    if (measured <= maxWidth || measured <= 0.0) return 1.0
    return maxWidth / measured
}

// ---- paths ------------------------------------------------------------------------------------------------------------------------------

/** What a path call asks the platform path to do; see [PathTracker]. */
internal sealed interface PathStep {
    data class MoveTo(val x: Double, val y: Double) : PathStep
    data class LineTo(val x: Double, val y: Double) : PathStep

    /** A clockwise arc of the circle at ([cx], [cy]); [forceMove] starts a new subpath at its first point (when there is no current point), else the platform joins it with a line. */
    data class Arc(val cx: Double, val cy: Double, val r: Double, val startDegrees: Double, val sweepDegrees: Double, val forceMove: Boolean) : PathStep
    data object Close : PathStep
}

/**
 * Tracks the current point of a canvas path, which android.graphics.Path does not do the way a canvas does: a `lineTo` with no current point starts a subpath at that point
 * (Android's would draw from the origin), an `arc` with no current point starts at the arc's first point, and after `closePath` the current point is the start of the
 * subpath that was closed. Every call answers the steps for the platform path, or none when the canvas ignores the call.
 */
internal class PathTracker {
    private var hasPoint = false
    private var startX = 0.0
    private var startY = 0.0

    fun begin() {
        hasPoint = false
    }

    fun moveTo(x: Double, y: Double): List<PathStep> {
        if (!x.isFinite() || !y.isFinite()) return emptyList()
        hasPoint = true
        startX = x
        startY = y
        return listOf(PathStep.MoveTo(x, y))
    }

    fun lineTo(x: Double, y: Double): List<PathStep> {
        if (!x.isFinite() || !y.isFinite()) return emptyList()
        if (!hasPoint) return moveTo(x, y)
        return listOf(PathStep.LineTo(x, y))
    }

    /** `arc(x, y, r, start, end)`, clockwise. A negative radius or a non-finite number is an error in a canvas; the call draws nothing. A radius of 0 is a point. */
    fun arc(x: Double, y: Double, r: Double, start: Double, end: Double): List<PathStep> {
        if (!x.isFinite() || !y.isFinite() || !r.isFinite() || !start.isFinite() || !end.isFinite() || r < 0.0) return emptyList()
        val sweep = arcSweepRadians(start, end)
        val sx = x + r * Math.cos(start)
        val sy = y + r * Math.sin(start)
        if (r == 0.0) return lineTo(sx, sy)
        val force = !hasPoint
        // after the arc the current point is its end; the subpath starts where it began
        if (force) {
            startX = sx
            startY = sy
        }
        hasPoint = true
        val startDegrees = Math.toDegrees(start)
        // Skia's arcTo draws nothing for a sweep of a whole turn (its end is its start): a full circle is two half turns
        if (sweep >= 2 * PI) {
            return listOf(
                PathStep.Arc(x, y, r, startDegrees, 180.0, force),
                PathStep.Arc(x, y, r, startDegrees + 180.0, 180.0, false),
            )
        }
        return listOf(PathStep.Arc(x, y, r, startDegrees, Math.toDegrees(sweep), force))
    }

    fun close(): List<PathStep> {
        if (!hasPoint) return emptyList()
        return listOf(PathStep.Close)
    }
}

/** The clockwise sweep of `arc(.., start, end)` in radians: a full turn or more is exactly one turn; an end before the start wraps around into 0 until a turn. */
internal fun arcSweepRadians(start: Double, end: Double): Double {
    val full = 2 * PI
    val d = end - start
    return when {
        d >= full -> full
        d >= 0.0 -> d
        else -> d + full * ceil(-d / full)
    }
}

// ---- bitmaps ----------------------------------------------------------------------------------------------------------------------------

/** The bitmap behind a canvas of [width] x [height] logical px: its size in pixels and the factors from logical to pixel coordinates. */
internal data class BitmapPlan(val pixelWidth: Int, val pixelHeight: Int, val scaleX: Float, val scaleY: Float) {
    companion object {
        /** No side of a bitmap is longer than this many pixels (a very large canvas is drawn smaller and shown larger, never refused). */
        const val MAX_SIDE = 4096

        fun of(width: Double, height: Double, density: Float): BitmapPlan {
            val d = if (density.isNaN() || density <= 0f) 1f else density
            val w = if (width.isNaN() || width < 1.0) 1.0 else width
            val h = if (height.isNaN() || height < 1.0) 1.0 else height
            val pw = min(max(1.0, ceil(w * d)), MAX_SIDE.toDouble()).toInt()
            val ph = min(max(1.0, ceil(h * d)), MAX_SIDE.toDouble()).toInt()
            return BitmapPlan(pw, ph, (pw / w).toFloat(), (ph / h).toFloat())
        }
    }
}

/** How many pixels `scrollLeft(px)` moves a bitmap of [plan]: whole pixels, never beyond its width. */
internal fun scrollPixels(px: Double, plan: BitmapPlan): Int =
    if (px.isNaN() || px <= 0.0) 0 else min(plan.pixelWidth.toDouble(), abs(px) * plan.scaleX).roundToInt()
