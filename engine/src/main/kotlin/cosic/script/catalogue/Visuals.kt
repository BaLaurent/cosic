package cosic.script.catalogue

import cosic.pattern.ControlSpec
import cosic.pattern.Controls
import cosic.pattern.CosicError
import cosic.pattern.Pattern
import cosic.pattern.Rational
import cosic.pattern.Registry
import cosic.pattern.Undefined
import cosic.pattern.callMethod
import cosic.pattern.pure
import cosic.pattern.reg2
import cosic.pattern.reg3
import cosic.pattern.silence
import cosic.pattern.withQueryBudget
import cosic.script.LogLevel
import cosic.script.PatternValue
import cosic.visual.Painter
import cosic.visual.VisualOptions
import cosic.visual.VisualSession
import cosic.visual.WidgetType
import cosic.visual.fscope
import cosic.visual.onPaint
import cosic.visual.pianoroll
import cosic.visual.pitchwheel
import cosic.visual.printToConsole
import cosic.visual.punchcard
import cosic.visual.scope
import cosic.visual.spectrum
import cosic.visual.spiral
import cosic.visual.widget
import cosic.visual.wordfall

/*
 * The visuals a program can ask for (packages/draw and packages/webaudio: `pianoroll`, `punchcard`, `wordfall`, `spiral`, `pitchwheel`, `scope`, `tscope`, `fscope`,
 * `spectrum`, `print`; packages/codemirror/widget.mjs: the inline widgets `_pianoroll` ... `_print`). A call registers a visual with the [VisualSession] of the evaluation
 * (made by the first call, which puts it in `EvalEffects.visuals`, from where it ends up in `ProgramInfo.visuals`); the app draws it. The widget methods are
 * given their id by the transpiler as the first argument (`pat._scope(options)` is `pat._scope('_widget__scope_0', options)`).
 *
 * What cannot work on a phone is accepted and does nothing, with one line on the console (ENV D-4 of the parity plan): the canvas functions `draw`, `onPaint`, `animate`,
 * `getDrawContext` and `backgroundImage`, whose callbacks and contexts belong to a browser. `animate` returns silence, as it does in Strudel, and `getPainters` lists the
 * painters of a pattern without a way to call them.
 *
 * The `x`, `y`, `w`, `h`, `angle`, `r` and `fill` controls and `rescale`, `moveXY` and `zoomIn` are those of draw/animate.mjs, which `animate` reads.
 */

/** The session of the evaluation in progress: the first visual of the program makes it. */
private fun sessionOf(ctx: CallContext): VisualSession {
    val effects = ctx.effects
    (effects.visuals as? VisualSession)?.let { return it }
    val session = VisualSession { message -> effects.console.log(message, "error") }
    effects.visuals = session
    return session
}

/** The options argument [i]: the object the program passed, or none when it passed nothing (a value that is not an object has no options either). */
private fun optionsOf(ctx: CallContext, i: Int): VisualOptions {
    @Suppress("UNCHECKED_CAST")
    val map = ctx.kernelArg(i) as? Map<String, Any?> ?: return VisualOptions.EMPTY
    return VisualOptions(map)
}

private fun notSupported(ctx: CallContext, name: String) = ctx.host.log("[draw] $name is not supported in Cosic", LogLevel.WARNING)

internal fun registerVisuals() {
    registerAnimateControls()
    catalogue("Visuals") {
        // ---- the visuals of a pattern ------------------------------------------------------------------------------------------------

        // `pianoroll(arg)` of pianoroll.mjs:104-113: a pattern is given a pianoroll, anything else is the options of one and gives the function that does it,
        // which makes `all(pianoroll)` and `all(pianoroll({ ... }))` work.
        function(listOf("pianoroll"), 1) { ctx ->
            val arg = ctx.arg(0)
            if (arg is PatternValue) {
                sessionOf(ctx).pianoroll(arg.pattern, VisualOptions.EMPTY)
            } else {
                val options = optionsOf(ctx, 0)
                ctx.realm.function("", 1) { _, args ->
                    val pat = args.getOrElse(0) { Undefined } as? PatternValue ?: throw ctx.realm.typeError("pat.pianoroll is not a function")
                    ctx.bridge.wrap(sessionOf(ctx).pianoroll(pat.pattern, options))
                }
            }
        }
        method(listOf("pianoroll"), 1) { ctx -> sessionOf(ctx).pianoroll(receiver(ctx, "pianoroll"), optionsOf(ctx, 0)) }
        method(listOf("punchcard"), 1) { ctx -> sessionOf(ctx).punchcard(receiver(ctx, "punchcard"), optionsOf(ctx, 0)) }
        method(listOf("wordfall"), 1) { ctx -> sessionOf(ctx).wordfall(receiver(ctx, "wordfall"), optionsOf(ctx, 0)) }
        method(listOf("spiral"), 1) { ctx -> sessionOf(ctx).spiral(receiver(ctx, "spiral"), optionsOf(ctx, 0)) }
        method(listOf("pitchwheel"), 1) { ctx -> sessionOf(ctx).pitchwheel(receiver(ctx, "pitchwheel"), optionsOf(ctx, 0)) }
        method(listOf("scope", "tscope"), 1) { ctx -> sessionOf(ctx).scope(receiver(ctx, "scope"), optionsOf(ctx, 0)) }
        method(listOf("fscope"), 1) { ctx -> sessionOf(ctx).fscope(receiver(ctx, "fscope"), optionsOf(ctx, 0)) }
        method(listOf("spectrum"), 1) { ctx -> sessionOf(ctx).spectrum(receiver(ctx, "spectrum"), optionsOf(ctx, 0)) }

        // `.print(options)` of print.mjs: a line on the console for every hap, as it is scheduled, labelled with the number of the call in the program.
        method(listOf("print"), 1) { ctx ->
            val label = "print(line:${ctx.effects.nextPrintIndex()})"
            printToConsole(receiver(ctx, "print"), optionsOf(ctx, 0), label)
        }

        // ---- the inline widgets: the transpiler gives the call its id as the first argument ------------------------------------------------

        for (type in WidgetType.entries) {
            method(listOf(type.method), 2) { ctx ->
                val first = ctx.arg(0)
                val withId = first is String
                val id = if (first is String) first else "_widget_${type.method}_x${ctx.range?.start ?: 0}"
                val options = optionsOf(ctx, if (withId) 1 else 0)
                sessionOf(ctx).widget(type, id, options, receiver(ctx, type.method), ctx.range?.end ?: -1)
            }
        }

        // ---- what a phone cannot do ----------------------------------------------------------------------------------------------------------

        method(listOf("draw"), 2) { ctx ->
            notSupported(ctx, "draw")
            receiver(ctx, "draw")
        }
        method(listOf("onPaint"), 1) { ctx ->
            notSupported(ctx, "onPaint")
            receiver(ctx, "onPaint")
        }
        method(listOf("animate"), 1) { ctx ->
            notSupported(ctx, "animate")
            silence
        }
        // the painters a pattern registers when it is queried (`getPainters` of draw.mjs:99-103): they are listed, but there is no canvas to call them with
        method(listOf("getPainters"), 0) { ctx ->
            val found = ArrayList<Any?>()
            val pat = receiver(ctx, "getPainters")
            try {
                withQueryBudget { pat.queryArc(Rational.ZERO, Rational.ZERO, mapOf("painters" to found)) }
            } catch (_: CosicError) {
                // like queryArc of Strudel, which logs a failing query and answers nothing
            }
            ctx.realm.newArray(
                found.filterIsInstance<Painter>().map { painter ->
                    ctx.realm.function(painter.kind.name.lowercase(), 4) { _, _ -> Undefined }
                },
            )
        }
        function(listOf("getDrawContext"), 2) { ctx ->
            notSupported(ctx, "getDrawContext")
            val canvas = ctx.realm.newObject().also {
                it.defineOwn("width", 0.0)
                it.defineOwn("height", 0.0)
            }
            ctx.realm.newObject().also { context ->
                context.defineOwn("canvas", canvas)
                for (name in CANVAS_METHODS) context.defineOwn(name, ctx.realm.function(name, 0) { _, _ -> Undefined })
            }
        }
        function(listOf("backgroundImage"), 2) { ctx ->
            notSupported(ctx, "backgroundImage")
            Undefined
        }
    }
}

private fun receiver(ctx: CallContext, name: String): Pattern = ctx.bridge.receiverOf(ctx.self, name)

/** The drawing methods of a canvas context: a program that asks for the context of the screen gets an object that has them and does nothing with them. */
private val CANVAS_METHODS = listOf(
    "clearRect", "fillRect", "strokeRect", "fillText", "strokeText", "beginPath", "closePath", "moveTo", "lineTo", "arc", "arcTo", "ellipse", "rect",
    "stroke", "fill", "save", "restore", "translate", "rotate", "scale", "setTransform", "resetTransform", "drawImage", "clip",
    "quadraticCurveTo", "bezierCurveTo", "setLineDash", "putImageData",
)

/**
 * The controls of draw/animate.mjs (`createParams('x', 'y', 'w', 'h', 'angle', 'r', 'fill', 'smear')`; `smear` is a control of the core as well) and the functions
 * that use them: `rescale(f)` scales what `animate` draws, `moveXY(dx, dy)` moves it, and `zoomIn(f)`, which calls `move`, a method that does not exist
 * in Strudel either, so it always fails there with the same message.
 */
private fun registerAnimateControls() {
    val x = Controls.registerCustom(listOf("x"), false, emptyList())
    val y = Controls.registerCustom(listOf("y"), false, emptyList())
    val w = Controls.registerCustom(listOf("w"), false, emptyList())
    val h = Controls.registerCustom(listOf("h"), false, emptyList())
    for (spec in listOf(x, y, w, h) + listOf("angle", "r", "fill").map { Controls.registerCustom(listOf(it), false, emptyList()) }) {
        Registry.dual(
            listOf(spec.name),
            global = { a -> Controls.applyControl(spec, a.getOrElse(0) { Undefined }, a.getOrNull(1)) },
            method = { pat, a -> Controls.applyControl(spec, a.getOrElse(0) { Undefined }, pat) },
        )
    }
    fun control(spec: ControlSpec, value: Any?, pat: Pattern? = null): Pattern = Controls.applyControl(spec, value, pat)

    // rescale(f) = pat.mul(x(f).w(f).y(f).h(f))
    Registry.reg2("rescale") { f, pat -> pat.callMethod("mul", control(h, f, control(y, f, control(w, f, control(x, f))))) }
    // moveXY(dx, dy) = pat.add(x(dx).y(dy))
    Registry.reg3("moveXY") { dx, dy, pat -> pat.callMethod("add", control(y, dy, control(x, dx))) }
    Registry.reg2("zoomIn") { f, pat ->
        pure(1.0).callMethod("sub", f).callMethod("div", 2.0) // d, which would be the argument of the `move` that is not there
        pat.callMethod("rescale", f)
        throw CosicError("pat.rescale(...).move is not a function")
    }
}
