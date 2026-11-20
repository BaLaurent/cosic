package cosic.visual

import cosic.pattern.CosicError
import cosic.pattern.Hap
import cosic.pattern.Undefined
import cosic.pattern.getFrequency
import cosic.pattern.jsStrictEq
import cosic.pattern.jsToNumber
import cosic.pattern.jsToString
import cosic.pattern.jsTruthy
import cosic.pattern.midiToFreq

/*
 * The pitch wheel, port of `pitchwheel` of packages/draw/pitchwheel.mjs: the octave as a circle, with a dot at every step of the scale (`edo` of them) and a
 * mark for every hap that is sounding, at the angle of its pitch class.
 */

/** `Math.log2`, exact for the powers of two (which `ln(x) / ln(2)` is not). */
private fun jsLog2(x: Double): Double {
    if (x.isNaN() || x < 0.0) return Double.NaN
    if (x == 0.0) return Double.NEGATIVE_INFINITY
    if (x == Double.POSITIVE_INFINITY) return x
    val e = Math.getExponent(x)
    if (e < java.lang.Double.MIN_EXPONENT) return StrictMath.log(x) / LN2
    return e + StrictMath.log(Math.scalb(x, -e)) / LN2
}

private val LN2 = StrictMath.log(2.0)
private const val TWO_PI = 2.0 * Math.PI

private fun circlePos(cx: Double, cy: Double, radius: Double, angle0: Double): DoubleArray {
    val angle = angle0 * Math.PI * 2
    return doubleArrayOf(StrictMath.sin(angle) * radius + cx, StrictMath.cos(angle) * radius + cy)
}

private fun freq2angle(freq: Double, root: Double): Double = 0.5 - (jsLog2(freq / root) % 1)

/** `Array.from({length: n})` takes the length as `ToLength`: a whole number from 0 up, and a program that asks for more steps than can be drawn gets the most that can. */
private fun lengthOf(n: Any?): Int {
    val d = jsToNumber(n)
    if (d.isNaN() || d <= 0.0) return 0
    return Math.min(Math.floor(d), MAX_EDO.toDouble()).toInt()
}

private const val MAX_EDO = 10_000

/** `haps[0].value && haps[0].value.<key> ? haps[0].value.<key> : null`. */
private fun firstValue(haps: List<Hap>, key: String): Any? {
    if (haps.isEmpty()) return null
    val value = haps[0].value
    if (!jsTruthy(value)) return null
    val v = propOf(value, key)
    return if (jsTruthy(v)) v else null
}

/** Draws the haps that sound as a pitch wheel on [ctx] (`pitchwheel`): [haps] are the haps the wheel shows, the options are those of [o]. */
internal fun drawPitchwheel(ctx: PaintCanvas, haps: List<Hap>, o: VisualOptions) {
    val hapcircles = o.flag("hapcircles", true)
    val circle = o.flag("circle", false)
    var edo: Any? = o.rawOr("edo", 12.0)
    var root: Any? = o.rawOr("root", midiToFreq(36.0))
    val thickness = o.num("thickness", 3.0)
    val hapRadius = o.num("hapRadius", 6.0)
    val mode = o.text("mode", "flake")
    val margin = o.num("margin", 10.0)
    val id = o.raw("id")

    val connectdots = mode == "polygon"
    val centerlines = mode == "flake"
    val w = ctx.width
    val h = ctx.height
    ctx.clearRect(0.0, 0.0, w, h)
    val color = VisualTheme.foreground

    val size = Math.min(w, h)
    val radius = size / 2 - thickness / 2 - hapRadius - margin
    val centerX = w / 2
    val centerY = h / 2

    var visible = haps
    if (jsTruthy(id)) visible = visible.filter { it.hasTag(jsToString(id)) }
    ctx.strokeStyle = color
    ctx.fillStyle = color
    ctx.globalAlpha = 1.0
    ctx.lineWidth = thickness

    if (circle) {
        ctx.beginPath()
        ctx.arc(centerX, centerY, radius, 0.0, TWO_PI)
        ctx.stroke()
    }

    if (jsTruthy(edo)) {
        edo = firstValue(visible, "edo") ?: edo
        root = firstValue(visible, "root") ?: root
        val degreeIndexes = firstValue(visible, "degreeIndexes")
        val intLabels = firstValue(visible, "intLabels")
        val rootHz = jsToNumber(root)
        ctx.font = "20px sans-serif"
        val label = "${jsToString(edo)} EDO"
        // an EDO label at the lower right
        ctx.fillText(label, centerX + radius - ctx.measureText(label) + 15, centerY + radius)
        val steps = lengthOf(edo)
        val edoNumber = jsToNumber(edo)
        val indexes = degreeIndexes as? List<*>
        val labels = intLabels as? List<*>
        for (i in 0 until steps) {
            val angle = freq2angle(rootHz * StrictMath.pow(2.0, i / edoNumber), rootHz)
            val p = circlePos(centerX, centerY, radius, angle)
            val x = p[0]
            val y = p[1]
            ctx.beginPath()
            // the interval label of degree i when there is one
            if (degreeIndexes == null || (indexes != null && indexes.any { it is Double && it == i.toDouble() })) {
                ctx.globalAlpha = 1.0
                ctx.arc(x, y, hapRadius, 0.0, TWO_PI)
                if (intLabels != null) {
                    if (indexes == null) throw CosicError("Cannot read properties of null (reading 'indexOf')")
                    val degree = indexes.indexOfFirst { jsStrictEq(it, i.toDouble()) }
                    val text = if (labels != null && degree >= 0 && degree < labels.size) labels[degree] else Undefined
                    if (jsTruthy(text)) {
                        if (angle < 0.32 && angle > 0.125) {
                            ctx.fillText(jsToString(text), x - 34, y)
                        } else if (angle < 0.1 && angle > -1.125) {
                            ctx.fillText(jsToString(text), x - 7, y - 12)
                        } else {
                            ctx.fillText(jsToString(text), x + 9, y)
                        }
                    }
                }
            } else {
                ctx.globalAlpha = 0.15
                ctx.arc(x, y, hapRadius, 0.0, TWO_PI)
            }
            ctx.fill()
        }
        ctx.stroke()
    }

    class Mark(val x: Double, val y: Double, val angle: Double, val color: String, val alpha: Double)
    val shape = ArrayList<Mark>()
    ctx.lineWidth = hapRadius
    val rootHz = jsToNumber(root)
    for (hap in visible) {
        val freq = try {
            getFrequency(hap)
        } catch (_: CosicError) {
            continue
        }
        val angle = freq2angle(freq, rootHz)
        val p = circlePos(centerX, centerY, radius, angle)
        val own = propOf(hap.value, "color")
        val hapColor = if (jsTruthy(own)) jsToString(own) else color
        ctx.strokeStyle = hapColor
        ctx.fillStyle = hapColor
        val source = if (jsTruthy(hap.value)) hap.value else null
        val velocity = propOf(source, "velocity").let { if (it === Undefined) 1.0 else jsToNumber(it) }
        val gain = propOf(source, "gain").let { if (it === Undefined) 1.0 else jsToNumber(it) }
        val alpha = velocity * gain
        ctx.globalAlpha = alpha
        shape.add(Mark(p[0], p[1], angle, hapColor, alpha))
        ctx.beginPath()
        if (hapcircles) {
            ctx.moveTo(p[0] + hapRadius, p[1])
            ctx.arc(p[0], p[1], hapRadius, 0.0, TWO_PI)
            ctx.fill()
        }
        if (centerlines) {
            ctx.moveTo(centerX, centerY)
            ctx.lineTo(p[0], p[1])
        }
        ctx.stroke()
    }

    ctx.strokeStyle = color
    ctx.globalAlpha = 1.0
    if (connectdots && shape.isNotEmpty()) {
        jsSort(shape) { a, b -> a.angle - b.angle }
        ctx.beginPath()
        ctx.moveTo(shape[0].x, shape[0].y)
        for (mark in shape) {
            ctx.strokeStyle = mark.color
            ctx.globalAlpha = mark.alpha
            ctx.lineTo(mark.x, mark.y)
        }
        ctx.lineTo(shape[0].x, shape[0].y)
        ctx.stroke()
    }
}
