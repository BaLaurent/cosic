package cosic.visual

import cosic.pattern.CosicError
import cosic.pattern.Hap
import cosic.pattern.Undefined
import cosic.pattern.freqToMidi
import cosic.pattern.jsNumberToString
import cosic.pattern.jsStrictEq
import cosic.pattern.jsToNumber
import cosic.pattern.jsToString
import cosic.pattern.jsTruthy
import cosic.pattern.jsTypeof
import cosic.pattern.noteToMidi

/*
 * The pianoroll, port of `__pianoroll` of packages/draw/pianoroll.mjs, which also draws the punchcard and the wordfall (a pianoroll
 * of the whole program, painted by the drawer). Written literally: the colour of one hap leaks into the next, the rows are the distinct
 * values in the order of an inconsistent comparator, and the numbers are JavaScript's.
 */

/** `scale(n, min, max)` of pianoroll.mjs:10. */
private fun scale(normalized: Double, min: Double, max: Double): Double = normalized * (max - min) + min

/**
 * `getValue(e)` of pianoroll.mjs:11-37: what decides the row of a hap. A frequency is its MIDI note, a note name too (0 when it is not one), a number is
 * itself, `s` is `"_" + s`, and anything else is the value, which is a row of its own.
 */
internal fun pianorollValue(e: Hap): Any? {
    var value = e.value
    if (jsTypeof(value) != "object") value = mapOf("value" to value)
    if (value == null) throw CosicError("Cannot destructure property 'note' of 'value' as it is null.")
    val freq = propOf(value, "freq")
    val note0 = propOf(value, "note")
    val n = propOf(value, "n")
    val s = propOf(value, "s")
    if (jsTruthy(freq)) return freqToMidi(jsToNumber(freq))
    val note = if (note0 === Undefined || note0 == null) n else note0
    if (note is String) {
        return try {
            noteToMidi(note)
        } catch (_: CosicError) {
            0.0
        }
    }
    if (note is Double) return note
    if (jsTruthy(s)) return "_" + jsToString(s)
    return value
}

/** `includes` (SameValueZero). */
private fun sameValueZero(a: Any?, b: Any?): Boolean {
    if (a is Double && b is Double) return a == b || (a.isNaN() && b.isNaN())
    return jsStrictEq(a, b)
}

/** The comparator of the rows (pianoroll.mjs:187-193): numbers by value, a number after anything else, the others by `localeCompare` of their text. */
private fun compareRows(a: Any?, b: Any?): Double = when {
    a is Double && b is Double -> a - b
    a is Double -> 1.0
    else -> localeCompare(jsToString(a), jsToString(b)).toDouble()
}

/**
 * Draws [haps] on [ctx] as a pianoroll (`__pianoroll`): [time] is the cycle at the playhead, [o] the options of the program (`cycles`, `playhead`,
 * `vertical`, `fold`, `labels`, `smear`, `active`, `inactive`, ... see the defaults below). When `id` is among them only the haps with that tag are drawn.
 */
internal fun drawPianoroll(ctx: PaintCanvas, time: Double, haps: List<Hap>, o: VisualOptions) {
    val cycles = o.num("cycles", 4.0)
    val playhead = o.num("playhead", 0.5)
    val flipTime = o.flag("flipTime", false)
    val flipValues = o.flag("flipValues", false)
    var inactive: Any? = o.rawOr("inactive", VisualTheme.foreground)
    var active: Any? = o.rawOr("active", VisualTheme.foreground)
    val background = o.text("background", "transparent")
    val smear = o.flag("smear", false)
    val playheadColor = o.text("playheadColor", VisualTheme.foreground)
    var minMidi = o.num("minMidi", 10.0)
    var maxMidi = o.num("maxMidi", 90.0)
    val autorange = o.flag("autorange", false)
    val timeframeProp = o.raw("timeframe")
    val fold = o.flag("fold", true)
    val vertical = o.flag("vertical", false)
    val labels = o.flag("labels", false)
    val fill = o.flag("fill", true)
    val fillActive = o.flag("fillActive", false)
    val strokeActive = o.flag("strokeActive", true)
    val stroke = o.raw("stroke")
    val hideInactive = o.flag("hideInactive", false)
    val colorizeInactive = o.flag("colorizeInactive", true)
    val fontFamily = o.raw("fontFamily")
    val id = o.raw("id")

    val w = ctx.width
    val h = ctx.height
    var from = -cycles * playhead
    var to = cycles * (1 - playhead)

    var visible = haps
    if (jsTruthy(id)) visible = visible.filter { it.hasTag(jsToString(id)) }

    if (jsTruthy(timeframeProp)) {
        from = 0.0
        to = jsToNumber(timeframeProp)
    }
    val timeAxis = if (vertical) h else w
    val valueAxis = if (vertical) w else h
    val timeRange = if (vertical) doubleArrayOf(timeAxis, 0.0) else doubleArrayOf(0.0, timeAxis)
    val timeExtent = to - from
    val valueRange = if (vertical) doubleArrayOf(0.0, valueAxis) else doubleArrayOf(valueAxis, 0.0)
    var valueExtent = maxMidi - minMidi + 1
    if (flipTime) timeRange.reverse()
    if (flipValues) valueRange.reverse()

    // the rows: the distinct values of the haps
    var min = Double.POSITIVE_INFINITY
    var max = Double.NEGATIVE_INFINITY
    val values = ArrayList<Any?>()
    for (e in visible) {
        val v = pianorollValue(e)
        if (v is Double) {
            if (v < min) min = v
            if (v > max) max = v
        }
        if (values.none { sameValueZero(it, v) }) values.add(v)
    }
    if (autorange) {
        minMidi = min
        maxMidi = max
        valueExtent = maxMidi - minMidi + 1
    }
    jsSort(values, ::compareRows)
    val foldValues: List<Any?> = values
    val barThickness = if (fold) valueAxis / foldValues.size else valueAxis / valueExtent

    ctx.fillStyle = background
    ctx.globalAlpha = 1.0
    if (!smear) {
        ctx.clearRect(0.0, 0.0, w, h)
        ctx.fillRect(0.0, 0.0, w, h)
    }
    for (event in visible) {
        val whole = event.whole ?: throw CosicError("Cannot read properties of undefined (reading 'begin')")
        val wholeBegin = whole.begin.toDouble()
        val isActive = wholeBegin <= time && event.endClipped.toDouble() > time
        val strokeCurrent = if (stroke === Undefined || stroke == null) strokeActive && isActive else jsTruthy(stroke)
        val fillCurrent = (!isActive && fill) || (isActive && fillActive)
        if (hideInactive && !isActive) continue
        val hapColor = propOf(event.value, "color")
        active = if (jsTruthy(hapColor)) hapColor else active
        inactive = if (colorizeInactive) (if (jsTruthy(hapColor)) hapColor else inactive) else inactive
        val color = jsToString(if (isActive) active else inactive)
        ctx.fillStyle = if (fillCurrent) color else "transparent"
        ctx.strokeStyle = color
        val source = if (jsTruthy(event.value)) event.value else null
        val velocity = propOf(source, "velocity").let { if (it === Undefined) 1.0 else jsToNumber(it) }
        val gain = propOf(source, "gain").let { if (it === Undefined) 1.0 else jsToNumber(it) }
        ctx.globalAlpha = velocity * gain
        val timeProgress = (wholeBegin - (if (flipTime) to else from)) / timeExtent
        val timePx = scale(timeProgress, timeRange[0], timeRange[1])
        val durationPx = scale(event.duration.toDouble() / timeExtent, 0.0, timeAxis)
        val value = pianorollValue(event)
        val valueProgress = if (fold) {
            foldValues.indexOfFirst { jsStrictEq(it, value) }.toDouble() / foldValues.size
        } else {
            (jsToNumber(value) - minMidi) / valueExtent
        }
        val valuePx = scale(valueProgress, valueRange[0], valueRange[1])
        val margin = 0.0
        val offset = scale(time / timeExtent, timeRange[0], timeRange[1])
        val x: Double
        val y: Double
        val cw: Double
        val ch: Double
        if (vertical) {
            x = valuePx + 1 - (if (flipValues) barThickness else 0.0)
            y = timeAxis - offset + timePx + margin + 1 - (if (flipTime) 0.0 else durationPx)
            cw = barThickness - 2
            ch = durationPx - 2
        } else {
            x = timePx - offset + margin + 1 - (if (flipTime) durationPx else 0.0)
            y = valuePx + 1 - (if (flipValues) 0.0 else barThickness)
            cw = durationPx - 2
            ch = barThickness - 2
        }
        if (strokeCurrent) ctx.strokeRect(x, y, cw, ch)
        if (fillCurrent) ctx.fillRect(x, y, cw, ch)
        if (labels) {
            val v = event.value ?: throw CosicError("Cannot read properties of null (reading 'note')")
            val note = propOf(v, "note")
            val n = propOf(v, "n")
            val defaultLabel: Any? =
                if (note !== Undefined && note != null) note else jsToString(propOf(v, "s")) + (if (jsTruthy(n)) ":" + jsToString(n) else "")
            val inactiveLabel = propOf(v, "label")
            val activeLabel = propOf(v, "activeLabel")
            val customLabel = if (isActive) (if (jsTruthy(activeLabel)) activeLabel else inactiveLabel) else inactiveLabel
            val label = if (customLabel === Undefined || customLabel == null) defaultLabel else customLabel
            val measure = if (vertical) durationPx else barThickness * 0.75
            ctx.font = "${jsNumberToString(measure)}px ${if (jsTruthy(fontFamily)) jsToString(fontFamily) else "monospace"}"
            ctx.fillStyle = if (!fillCurrent) color else "black"
            ctx.textBaseline = "top"
            ctx.fillText(jsToString(label), x, y, cw)
        }
    }
    ctx.globalAlpha = 1.0
    val playheadPosition = scale(-from / timeExtent, timeRange[0], timeRange[1])
    ctx.strokeStyle = playheadColor
    ctx.beginPath()
    if (vertical) {
        ctx.moveTo(0.0, playheadPosition)
        ctx.lineTo(valueAxis, playheadPosition)
    } else {
        ctx.moveTo(playheadPosition, 0.0)
        ctx.lineTo(playheadPosition, valueAxis)
    }
    ctx.stroke()
}

/**
 * `getDrawOptions(drawTime, options)` of pianoroll.mjs:281-287: the options of a painted pianoroll (the punchcard): the program's, with `fold` on by
 * default and `cycles` and `playhead` from the time the drawer looks back and ahead, which override the program's.
 */
internal fun drawOptionsOf(lookbehind: Double, lookahead: Double, options: VisualOptions): VisualOptions {
    val behind = Math.abs(lookbehind)
    val cycles = lookahead + behind
    val playhead = if (cycles != 0.0) behind / cycles else 0.0
    val merged = LinkedHashMap<String, Any?>()
    merged["fold"] = 1.0
    merged.putAll(options.map)
    merged["cycles"] = cycles
    merged["playhead"] = playhead
    return VisualOptions(merged)
}
