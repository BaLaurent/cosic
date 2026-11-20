package cosic.visual

import cosic.pattern.Hap
import cosic.pattern.CosicError
import cosic.pattern.jsToString
import cosic.pattern.jsTruthy

/*
 * The spiral, port of packages/draw/spiral.mjs: every hap is a segment of a spiral that turns once per cycle, the playhead a short bright segment at its
 * start. Drawn in many small steps, so a segment has as many line pieces as sixtieths of a turn it is long.
 */

/** The most line pieces of one segment: a hap that lasts thousands of cycles would hold the thread that draws it for as long as it lasts. */
private const val MAX_PIECES = 200_000

private fun fromPolar(angle: Double, radius: Double, cx: Double, cy: Double): DoubleArray {
    val radians = ((angle - 90) * Math.PI) / 180
    return doubleArrayOf(cx + StrictMath.cos(radians) * radius, cy + StrictMath.sin(radians) * radius)
}

private fun xyOnSpiral(angle: Double, margin: Double, cx: Double, cy: Double, rotate: Double): DoubleArray =
    fromPolar((angle + rotate) * 360, margin * angle, cx, cy)

/** `spiralSegment` of spiral.mjs:13-51: the piece of the spiral from [from] to [to] (in turns), opaque to [toOpacity] along it. */
private fun spiralSegment(
    ctx: PaintCanvas,
    from0: Double,
    to0: Double,
    margin: Double,
    cx: Double,
    cy: Double,
    rotate0: Double,
    thickness: Double,
    color: String,
    cap: String,
    stretch: Double,
    fromOpacity: Double,
    toOpacity: Double,
) {
    val from = from0 * stretch
    val to = to0 * stretch
    val rotate = rotate0 * stretch
    ctx.lineWidth = thickness
    ctx.lineCap = cap
    ctx.strokeStyle = color
    ctx.globalAlpha = fromOpacity

    ctx.beginPath()
    val start = xyOnSpiral(from, margin, cx, cy, rotate)
    ctx.moveTo(start[0], start[1])

    val increment = 1.0 / 60
    var angle = from
    var pieces = 0
    while (angle <= to && pieces < MAX_PIECES) {
        val p = xyOnSpiral(angle, margin, cx, cy, rotate)
        ctx.globalAlpha = ((angle - from) / (to - from)) * toOpacity
        ctx.lineTo(p[0], p[1])
        angle += increment
        pieces++
    }
    ctx.stroke()
}

/**
 * Draws [haps] as a spiral (`drawSpiral`): [time] is the cycle at the playhead, [lookbehind] the first number of the drawer's time (`-2`: what fades out
 * towards the past), [o] the options of the program.
 */
internal fun drawSpiral(ctx: PaintCanvas, time: Double, haps: List<Hap>, lookbehind: Double, o: VisualOptions) {
    val stretch = o.num("stretch", 1.0)
    val size = o.num("size", 80.0)
    val thickness = o.num("thickness", size / 2)
    val cap = o.text("cap", "butt")
    val inset = o.num("inset", 3.0)
    val playheadColor = o.text("playheadColor", "#ffffff")
    val playheadLength = o.num("playheadLength", 0.02)
    val playheadThickness = o.num("playheadThickness", thickness)
    val padding = o.num("padding", 0.0)
    val steady = o.num("steady", 1.0)
    val activeColor = o.rawOr("activeColor", VisualTheme.foreground)
    val inactiveColor = o.rawOr("inactiveColor", VisualTheme.gutterForeground)
    val colorizeInactive = o.flag("colorizeInactive", false)
    val fade = o.flag("fade", true)
    val id = o.raw("id")

    var visible = haps
    if (jsTruthy(id)) visible = visible.filter { it.hasTag(jsToString(id)) }

    val w = ctx.width
    val h = ctx.height
    ctx.clearRect(0.0, 0.0, w * 2, h * 2)
    val cx = w / 2
    val cy = h / 2
    val margin = size / stretch

    val rotate = steady * time
    for (hap in visible) {
        val whole = hap.whole ?: throw CosicError("Cannot read properties of undefined (reading 'begin')")
        val wholeBegin = whole.begin.toDouble()
        val endClipped = hap.endClipped.toDouble()
        val isActive = wholeBegin <= time && endClipped > time
        val from = wholeBegin - time + inset
        val to = endClipped - time + inset - padding
        val own = propOf(hap.value, "color")
        val hapColor = if (jsTruthy(own)) own else activeColor
        val color = jsToString(if (colorizeInactive || isActive) hapColor else inactiveColor)
        val opacity = if (fade) 1 - Math.abs((wholeBegin - time) / lookbehind) else 1.0
        spiralSegment(ctx, from, to, margin, cx, cy, rotate, thickness, color, cap, stretch, opacity, opacity)
    }
    spiralSegment(ctx, inset - playheadLength, inset, margin, cx, cy, rotate, playheadThickness, playheadColor, cap, stretch, 1.0, 1.0)
}
