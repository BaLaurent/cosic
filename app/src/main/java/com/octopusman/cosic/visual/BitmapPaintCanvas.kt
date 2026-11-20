package com.octopusman.cosic.visual

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import cosic.visual.PaintCanvas

/**
 * [PaintCanvas] on an android [Bitmap]: the HTML canvas 2D subset the visuals draw with (rules in [CanvasMath]). Coordinates are logical px, the units of
 * [width] and [height] (dp in the app): the bitmap has [density] times as many pixels and the canvas is scaled by that, so sizes (line widths, fonts) are written
 * as for a canvas at a pixel ratio of 1 and come out sharp.
 *
 * All calls come from one thread (the one that draws); the object is not thread safe. The bitmap is transparent at first; [bitmap] is what the host shows.
 */
class BitmapPaintCanvas(override val width: Double, override val height: Double, density: Float) : PaintCanvas {
    private val plan = BitmapPlan.of(width, height, density)

    /** What was drawn so far, `plan.pixelWidth` x `plan.pixelHeight` ARGB_8888 pixels. */
    val bitmap: Bitmap = Bitmap.createBitmap(plan.pixelWidth, plan.pixelHeight, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap).also { it.scale(plan.scaleX, plan.scaleY) }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeMiter = 10f // the canvas default; android's is 4
        strokeJoin = Paint.Join.MITER
    }
    private val clearPaint = Paint().apply { style = Paint.Style.FILL; xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
    private val rect = RectF()
    private val path = Path()
    private val tracker = PathTracker()
    private var scratch: Bitmap? = null

    private var fillArgb = 0xFF000000.toInt()
    private var strokeArgb = 0xFF000000.toInt()
    private var fillText = "#000000"
    private var strokeText = "#000000"
    private var alpha = 1.0
    private var widthPx = 1.0
    private var capName = "butt"
    private var fontSpec = FontSpec.DEFAULT
    private var fontText = "10px sans-serif"
    private var baseline = "alphabetic"

    init {
        applyFont()
    }

    override var fillStyle: String
        get() = fillText
        set(value) {
            val accepted = acceptStyle(value, fillText, fillArgb)
            fillText = accepted.first
            fillArgb = accepted.second
        }

    override var strokeStyle: String
        get() = strokeText
        set(value) {
            val accepted = acceptStyle(value, strokeText, strokeArgb)
            strokeText = accepted.first
            strokeArgb = accepted.second
        }

    override var globalAlpha: Double
        get() = alpha
        set(value) {
            alpha = acceptAlpha(value, alpha)
        }

    override var lineWidth: Double
        get() = widthPx
        set(value) {
            widthPx = acceptLineWidth(value, widthPx)
            strokePaint.strokeWidth = widthPx.toFloat()
        }

    override var lineCap: String
        get() = capName
        set(value) {
            if (value in LINE_CAPS) {
                capName = value
                strokePaint.strokeCap = when (value) {
                    "round" -> Paint.Cap.ROUND
                    "square" -> Paint.Cap.SQUARE
                    else -> Paint.Cap.BUTT
                }
            }
        }

    override var font: String
        get() = fontText
        set(value) {
            val spec = FontSpec.parse(value) ?: return
            fontSpec = spec
            fontText = value
            applyFont()
        }

    override var textBaseline: String
        get() = baseline
        set(value) {
            if (value in TEXT_BASELINES) baseline = value
        }

    private fun applyFont() {
        fillPaint.textSize = fontSpec.sizePx
        val style = when {
            fontSpec.bold && fontSpec.italic -> Typeface.BOLD_ITALIC
            fontSpec.bold -> Typeface.BOLD
            fontSpec.italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        fillPaint.typeface = when (fontSpec.kind) {
            FontFamilyKind.MONOSPACE -> Typeface.create(Typeface.MONOSPACE, style)
            FontFamilyKind.SANS_SERIF -> Typeface.create(Typeface.SANS_SERIF, style)
            FontFamilyKind.SERIF -> Typeface.create(Typeface.SERIF, style)
            FontFamilyKind.OTHER -> Typeface.create(fontSpec.family, style)
        }
    }

    private fun useFill() {
        fillPaint.color = withGlobalAlpha(fillArgb, alpha)
    }

    private fun useStroke() {
        strokePaint.color = withGlobalAlpha(strokeArgb, alpha)
        strokePaint.strokeWidth = widthPx.toFloat()
    }

    private fun sane(vararg v: Double): Boolean = v.all { it.isFinite() }

    private fun set(x: Double, y: Double, w: Double, h: Double): RectF {
        // a canvas rectangle with a negative size extends the other way
        val x2 = x + w
        val y2 = y + h
        rect.set(Math.min(x, x2).toFloat(), Math.min(y, y2).toFloat(), Math.max(x, x2).toFloat(), Math.max(y, y2).toFloat())
        return rect
    }

    override fun clearRect(x: Double, y: Double, w: Double, h: Double) {
        if (!sane(x, y, w, h)) return
        canvas.drawRect(set(x, y, w, h), clearPaint)
    }

    override fun fillRect(x: Double, y: Double, w: Double, h: Double) {
        if (!sane(x, y, w, h) || w == 0.0 || h == 0.0) return
        useFill()
        fillPaint.style = Paint.Style.FILL
        canvas.drawRect(set(x, y, w, h), fillPaint)
    }

    override fun strokeRect(x: Double, y: Double, w: Double, h: Double) {
        if (!sane(x, y, w, h) || (w == 0.0 && h == 0.0)) return
        useStroke()
        canvas.drawRect(set(x, y, w, h), strokePaint)
    }

    override fun fillText(text: String, x: Double, y: Double, maxWidth: Double?) {
        if (!sane(x, y) || text.isEmpty()) return
        val squeeze = textSqueeze(fillPaint.measureText(text).toDouble(), maxWidth) ?: return
        useFill()
        fillPaint.style = Paint.Style.FILL
        val metrics = fillPaint.fontMetrics
        val baselineY = y + baselineShift(baseline, -metrics.ascent, metrics.descent)
        if (squeeze == 1.0) {
            canvas.drawText(text, x.toFloat(), baselineY.toFloat(), fillPaint)
        } else {
            canvas.save()
            canvas.translate(x.toFloat(), baselineY.toFloat())
            canvas.scale(squeeze.toFloat(), 1f)
            canvas.drawText(text, 0f, 0f, fillPaint)
            canvas.restore()
        }
    }

    override fun measureText(text: String): Double = fillPaint.measureText(text).toDouble()

    override fun beginPath() {
        path.reset()
        tracker.begin()
    }

    override fun moveTo(x: Double, y: Double) = apply(tracker.moveTo(x, y))

    override fun lineTo(x: Double, y: Double) = apply(tracker.lineTo(x, y))

    override fun arc(x: Double, y: Double, r: Double, start: Double, end: Double) = apply(tracker.arc(x, y, r, start, end))

    override fun closePath() = apply(tracker.close())

    private fun apply(steps: List<PathStep>) {
        for (step in steps) when (step) {
            is PathStep.MoveTo -> path.moveTo(step.x.toFloat(), step.y.toFloat())
            is PathStep.LineTo -> path.lineTo(step.x.toFloat(), step.y.toFloat())
            is PathStep.Arc -> {
                rect.set((step.cx - step.r).toFloat(), (step.cy - step.r).toFloat(), (step.cx + step.r).toFloat(), (step.cy + step.r).toFloat())
                path.arcTo(rect, step.startDegrees.toFloat(), step.sweepDegrees.toFloat(), step.forceMove)
            }
            PathStep.Close -> path.close()
        }
    }

    override fun stroke() {
        useStroke()
        canvas.drawPath(path, strokePaint)
    }

    override fun fill() {
        useFill()
        fillPaint.style = Paint.Style.FILL
        canvas.drawPath(path, fillPaint)
    }

    override fun scrollLeft(px: Double) {
        val shift = scrollPixels(px, plan)
        if (shift <= 0) return
        val copy = scratch?.takeIf { it.width == bitmap.width && it.height == bitmap.height } ?: Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888).also { scratch = it }
        val raw = Canvas(copy)
        raw.drawColor(0, PorterDuff.Mode.CLEAR)
        raw.drawBitmap(bitmap, 0f, 0f, null)
        val rawTarget = Canvas(bitmap)
        rawTarget.drawColor(0, PorterDuff.Mode.CLEAR)
        rawTarget.drawBitmap(copy, -shift.toFloat(), 0f, null)
    }

    /** Makes the whole bitmap transparent again (a new life for the same surface). */
    fun clear() {
        bitmap.eraseColor(0)
    }

    /** Frees the bitmaps; the canvas is unusable afterwards. */
    fun recycle() {
        scratch?.recycle()
        scratch = null
        bitmap.recycle()
    }
}
