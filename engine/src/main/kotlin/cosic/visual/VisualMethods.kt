package cosic.visual

import cosic.pattern.Pattern
import cosic.pattern.ScheduleHook
import cosic.pattern.Undefined
import cosic.pattern.callMethod
import cosic.pattern.jsToNumber
import cosic.pattern.jsToString
import cosic.pattern.jsTruthy
import cosic.pattern.logger
import cosic.pattern.tag

/*
 * The methods of patterns that show something (`Pattern.prototype.pianoroll`, `punchcard`, `spiral`, `scope`, ... and the inline widgets `_pianoroll`, ...), written as
 * functions of a [VisualSession] and a receiver pattern so that the catalogue only has to pass the arguments of the program. Each returns what the method of Strudel
 * returns (the receiver, tagged or analysed as the method does) and registers what it draws with the session.
 */

private const val BG = VisualSurface.BACKGROUND_KEY

/** `{...a, ...b}` for the option maps the widgets build. */
private fun merge(vararg parts: Map<String, Any?>): VisualOptions {
    val out = LinkedHashMap<String, Any?>()
    for (p in parts) out.putAll(p)
    return VisualOptions(out)
}

/** `.pianoroll(options)`: a pianoroll that draws the haps of the receiver around the playhead (`draw` loop with the id of the options, 1 by default). */
internal fun VisualSession.pianoroll(receiver: Pattern, options: VisualOptions, target: String = BG): Pattern {
    val id = options.rawOr("id", 1.0)
    addLoop(PianorollLoop(jsToString(id), target, receiver, options))
    return receiver
}

/** `.punchcard(options)`: every hap of the program as a bar of a pianoroll, painted by the drawer. */
internal fun VisualSession.punchcard(receiver: Pattern, options: VisualOptions, target: String = BG): Pattern {
    addPainter(target)
    return receiver.onPaint(Painter(PainterKind.PUNCHCARD, options, target))
}

/** `.wordfall(options)`: a punchcard that runs from the top down with the names of the sounds written on the bars. */
internal fun VisualSession.wordfall(receiver: Pattern, options: VisualOptions, target: String = BG): Pattern =
    punchcard(
        receiver,
        merge(mapOf("vertical" to 1.0, "labels" to 1.0, "stroke" to 0.0, "fillActive" to 1.0, "active" to "white"), options.map),
        target,
    )

/** `.spiral(options)`. */
internal fun VisualSession.spiral(receiver: Pattern, options: VisualOptions, target: String = BG): Pattern {
    addPainter(target)
    return receiver.onPaint(Painter(PainterKind.SPIRAL, options, target))
}

/** `.pitchwheel(options)`: the receiver is tagged with the id of the options (1 by default), which is how the wheel knows its haps. */
internal fun VisualSession.pitchwheel(receiver: Pattern, options: VisualOptions, target: String = BG): Pattern {
    val id = options.rawOr("id", 1.0)
    addPainter(target)
    return receiver.tag(id).onPaint(Painter(PainterKind.PITCHWHEEL, options.plus(mapOf("id" to id)), target))
}

/** The id of an analyser loop: `config.id ?? 1`. */
private fun analyserId(options: VisualOptions): Any? = options.raw("id").let { if (it === Undefined || it == null) 1.0 else it }

/** The feed of the analyser [id]: the sound the engine's voices make with `analyze: id`. */
private fun VisualSession.feedOf(id: Any?): AnalyserFeed = TapFeed(jsToString(id)) { scopeTaps() }

/** `.scope(config)` and `.tscope(config)`: the waveform of the sound that the receiver's haps make (the receiver gets the `analyze` control with the id). */
internal fun VisualSession.scope(receiver: Pattern, options: VisualOptions, target: String = BG, feed: AnalyserFeed? = null): Pattern {
    val id = analyserId(options)
    val analysed = receiver.callMethod("analyze", id)
    addLoop(ScopeLoop(jsToString(id), target, analysed, options, frequency = false, feed = feed ?: feedOf(id)))
    return analysed
}

/** `.fscope(config)`: the spectrum of that sound as bars. */
internal fun VisualSession.fscope(receiver: Pattern, options: VisualOptions, target: String = BG, feed: AnalyserFeed? = null): Pattern {
    val id = analyserId(options)
    val analysed = receiver.callMethod("analyze", id)
    addLoop(ScopeLoop(jsToString(id), target, analysed, options, frequency = true, feed = feed ?: feedOf(id)))
    return analysed
}

/** `.spectrum(config)`: the spectrum of that sound as a picture that scrolls. */
internal fun VisualSession.spectrum(receiver: Pattern, options: VisualOptions, target: String = BG, feed: AnalyserFeed? = null): Pattern {
    val id = analyserId(options)
    val analysed = receiver.callMethod("analyze", id)
    addLoop(SpectrumLoop(jsToString(id), target, analysed, options, feed ?: feedOf(id)))
    return analysed
}

/** `.print({ el, ... })`, the form with a list widget: the haps that start are written to the lines of the widget [target]. */
internal fun VisualSession.printWidget(receiver: Pattern, options: VisualOptions, target: String): Pattern {
    val id = jsToString(options.rawOr("id", 1.0))
    val maxLines = options.num("maxLines", 500.0)
    val limit = if (maxLines.isNaN()) MAX_PRINT_LINES else Math.min(Math.max(maxLines, 0.0), MAX_PRINT_LINES.toDouble()).toInt()
    val lines = PrintLines(
        limit,
        options.num("round", 4.0),
        options.num("padding", 3.0),
        options.raw("round").let { it is Double && it == 0.0 },
    )
    addPrintLines(id, lines)
    addLoop(PrintLoop(id, target, receiver, lines))
    return receiver
}

private const val MAX_PRINT_LINES = 100_000

/**
 * `.print(options)` without a widget: every hap writes a line to the console when it is scheduled, `[print(line:N)] 0.25    : s:bd`, where [label] says which call
 * of `print()` of the program it is. The haps still sound.
 */
internal fun printToConsole(receiver: Pattern, options: VisualOptions, label: String): Pattern {
    val round = options.num("round", 4.0)
    val padding = options.num("padding", 3.0)
    val roundIsZero = options.raw("round").let { it is Double && it == 0.0 }
    return receiver.onSchedule(ScheduleHook { sh -> logger("[$label] ${printLine(sh.hap, round, padding, roundIsZero)}", null, null) }, false)
}

/** The number a widget id ends in (`_widget__scope_2` is 2): how many widgets of its type the transpiler had met before it. */
private fun indexOfWidget(id: String): Int = id.substringAfterLast('_').toIntOrNull() ?: 0

/** `options.size || default` of JavaScript: the size a widget is made from. */
private fun sizeOption(options: VisualOptions, default: Double): Double = options.raw("size").let { if (jsTruthy(it)) jsToNumber(it) else default }

/**
 * The widget methods of `widget.mjs` (`_pianoroll(id, options)` and the like): [id] is the id the transpiler gave the call, [options] what the program passed, [callEnd]
 * where the call ends. The widget is registered with the surface size its options ask for, and the visual is made with the defaults of the widget and drawn on it.
 */
internal fun VisualSession.widget(type: WidgetType, id: String, options: VisualOptions, receiver: Pattern, callEnd: Int): Pattern {
    val spec = WidgetSpec(type, id, indexOfWidget(id), callEnd, options.map)
    when (type) {
        WidgetType.PIANOROLL, WidgetType.PUNCHCARD -> {
            registerWidget(spec, options.num("width", 500.0), options.num("height", 60.0))
            val o = merge(mapOf("fold" to 1.0), options.map, mapOf("id" to id))
            val tagged = receiver.tag(id)
            return if (type == WidgetType.PIANOROLL) pianoroll(tagged, o, id) else punchcard(tagged, o, id)
        }
        WidgetType.SPIRAL -> {
            val size = sizeOption(options, 275.0)
            val o = merge(mapOf("width" to size, "height" to size), options.map, mapOf("size" to size / 5))
            registerWidget(spec, o.num("width", size), o.num("height", size))
            return spiral(receiver.tag(id), o.plus(mapOf("id" to id)), id)
        }
        WidgetType.SCOPE -> {
            val o = merge(mapOf("width" to 500.0, "height" to 60.0, "pos" to 0.5, "scale" to 1.0), options.map)
            registerWidget(spec, o.num("width", 500.0), o.num("height", 60.0))
            return scope(receiver.tag(id), o.plus(mapOf("id" to id)), id)
        }
        WidgetType.PITCHWHEEL, WidgetType.SPECTRUM -> {
            val size = sizeOption(options, 200.0)
            val o = merge(mapOf("width" to size, "height" to size), options.map, mapOf("size" to size / 5))
            registerWidget(spec, o.num("width", size), o.num("height", size))
            val withId = o.plus(mapOf("id" to id))
            return if (type == WidgetType.PITCHWHEEL) pitchwheel(receiver, withId, id) else spectrum(receiver, withId, id)
        }
        WidgetType.PRINT -> {
            val o = merge(mapOf("width" to 600.0, "height" to 100.0, "maxLines" to 500.0), options.map)
            registerWidget(spec, o.num("width", 600.0), o.num("height", 100.0))
            return printWidget(receiver, o.plus(mapOf("id" to id)), id)
        }
    }
}
