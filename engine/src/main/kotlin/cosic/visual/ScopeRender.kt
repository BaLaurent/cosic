package cosic.visual

import cosic.pattern.jsNumberToString
import cosic.pattern.jsToNumber
import cosic.pattern.jsTruthy

/*
 * The oscilloscopes, port of packages/webaudio/scope.mjs: `scope` (the waveform), `fscope` (the spectrum as bars). They draw what an [Analyser] hands them.
 */

/** `clamp(x, min, max)` of util.mjs: `Math.min(Math.max(x, min), max)`, so a NaN stays one. */
internal fun jsClamp(x: Double, min: Double, max: Double): Double = Math.min(Math.max(x, min), max)

/** `clearScreen(smear, smearRGB, ctx)` of scope.mjs:89-96: the surface is cleared, or faded when the program asks for a smear. */
internal fun clearScreen(ctx: PaintCanvas, smear: Any?, smearRGB: String = "0,0,0") {
    if (!jsTruthy(smear)) {
        ctx.clearRect(0.0, 0.0, ctx.width, ctx.height)
    } else {
        ctx.fillStyle = "rgba($smearRGB,${jsNumberToString(1 - jsToNumber(smear))})"
        ctx.fillRect(0.0, 0.0, ctx.width, ctx.height)
    }
}

/**
 * The waveform (`drawTimeScope`): [data] is what the analyser has heard, oldest first, or null while no sound with the analyser's id has played, which draws a
 * flat line. With `align` the line starts where the signal first falls through `-trigger`, so a steady note stands still.
 */
internal fun drawTimeScope(ctx: PaintCanvas, data: FloatArray?, o: VisualOptions) {
    val align = o.flag("align", true)
    val color = o.text("color", "white")
    val thickness = o.num("thickness", 3.0)
    val scale = o.num("scale", 0.25)
    val pos = o.num("pos", 0.75)
    val trigger = o.num("trigger", 0.0)
    ctx.lineWidth = thickness
    ctx.strokeStyle = color
    val width = ctx.width
    val height = ctx.height

    if (data == null) {
        // no analyser yet: a straight line
        ctx.beginPath()
        val y = pos * height
        ctx.moveTo(0.0, y)
        ctx.lineTo(width, y)
        ctx.stroke()
        return
    }

    ctx.beginPath()
    val bufferSize = data.size
    var triggerIndex = 0
    if (align) {
        triggerIndex = -1
        for (i in 1 until bufferSize) {
            if (data[i - 1] > -trigger && data[i] <= -trigger) {
                triggerIndex = i
                break
            }
        }
    }
    triggerIndex = Math.max(triggerIndex, 0) // no trigger found: from the start

    val sliceWidth = (width * 1.0) / bufferSize
    var x = 0.0
    for (i in triggerIndex until bufferSize) {
        val v = data[i].toDouble() + 1
        val y = (pos - scale * (v - 1)) * height
        // the first point starts the line (a canvas does the same with a first lineTo)
        if (i == triggerIndex) ctx.moveTo(x, y) else ctx.lineTo(x, y)
        x += sliceWidth
    }
    ctx.stroke()
}

/** The spectrum as bars (`drawFrequencyScope`): [db] is the level of each bin in decibels, from the lowest frequency up. */
internal fun drawFrequencyScope(ctx: PaintCanvas, db: FloatArray, o: VisualOptions) {
    val color = o.text("color", "white")
    val scale = o.num("scale", 0.25)
    val pos = o.num("pos", 0.75)
    val lean = o.num("lean", 0.5)
    val min = o.num("min", -150.0)
    val max = o.num("max", 0.0)
    ctx.fillStyle = color
    val bufferSize = db.size
    val sliceWidth = (ctx.width * 1.0) / bufferSize
    var x = 0.0
    for (i in 0 until bufferSize) {
        val normalized = jsClamp((db[i].toDouble() - min) / (max - min), 0.0, 1.0)
        val v = normalized * scale
        val h = v * ctx.height
        val y = (pos - v * lean) * ctx.height
        ctx.fillRect(x, y, Math.max(sliceWidth, 1.0), h)
        x += sliceWidth
    }
}
