package cosic.script.next.catalogue

import cosic.mini.m as miniPattern
import cosic.mini.mini as miniSequence
import cosic.pattern.Pattern
import cosic.pattern.Undefined
import cosic.pattern.pure
import cosic.pattern.reify
import cosic.script.next.LogLevel
import cosic.script.next.js.toNumber
import cosic.script.next.js.toStr

/**
 * The functions that belong to the language the transpiler's output is written in rather than to the library of patterns:
 * `m` and `mini` (the mini-notation of a double-quoted string), `register`, `ref` and the sliders. The REPL's own functions
 * (`all`, `each`, `hush`, `setcps`, ...) belong to the session, see [cosic.script.next.ReplSession].
 *
 * This category is registered last (K4), so what it defines replaces anything of the same name an earlier category made.
 */
internal fun registerLanguage() = catalogue("Language") {
    // `"c e g"` is transpiled to `m('c e g', <offset of the opening quote>)` (plugin-mini.mjs).
    function(listOf("m"), 2) { ctx ->
        miniPattern(ctx.realm.toStr(ctx.arg(0)), (ctx.arg(1) as? Double)?.toInt() ?: 0)
    }
    function(listOf("mini"), 1) { ctx -> miniSequence(*ctx.args.map { ctx.realm.toStr(it) }.toTypedArray()) }

    function(listOf("register"), 5) { ctx -> ctx.bridge.registerUser(ctx.args) }

    // `ref(accessor)`: a pattern that reads `accessor()` whenever it is queried (pattern.mjs:4094).
    function(listOf("ref"), 1) { ctx ->
        val accessor = ctx.arg(0)
        refPattern { ctx.bridge.toKernel(ctx.bridge.callJs(accessor)) }
    }

    // `slider(...)` is transpiled to `sliderWithID('slider_<offset>', value, min, max, step)`; the editor draws the control
    // and keeps the value the host holds under that id (widget.mjs).
    function(listOf("sliderWithID"), 5) { ctx ->
        val id = ctx.realm.toStr(ctx.arg(0))
        val host = ctx.host
        host.sliderSet(id, ctx.realm.toNumber(ctx.arg(1)))
        refPattern { host.sliderGet(id) ?: Undefined }
    }
    function(listOf("slider"), 4) { ctx ->
        ctx.host.log("slider will only work when the transpiler is used... passing value as is", LogLevel.WARNING)
        pure(ctx.kernelArg(0))
    }

    // Modules are not loaded in a program: everything is in the global scope already.
    function(listOf("evalScope"), 0) { ctx -> ctx.realm.newArray(emptyList()) }

    function(listOf("logger"), 3) { ctx ->
        val level = when (ctx.arg(1)) {
            "error" -> LogLevel.ERROR
            "warning" -> LogLevel.WARNING
            "debug" -> LogLevel.DEBUG
            else -> LogLevel.INFO
        }
        ctx.host.log(ctx.realm.toStr(ctx.arg(0)), level)
        Undefined
    }
}

/** `ref` of pattern.mjs: one event per cycle whose value is whatever [accessor] gives when the pattern is queried. */
internal fun refPattern(accessor: () -> Any?): Pattern = pure(1.0).fmap { reify(accessor()) }.innerJoin()
