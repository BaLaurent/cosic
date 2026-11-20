package cosic.script.catalogue

import cosic.SourceRange
import cosic.pattern.Undefined
import cosic.pattern.jsToNumber
import cosic.script.EmbeddedLanguages
import cosic.script.js.JsArray
import cosic.script.js.toStr
import cosic.script.tidal.TidalLanguage
import cosic.script.tidal.definePatternLabels
import cosic.script.tidal.evaluateTidal

/**
 * `tidal` and `initTidal` (packages/tidal/tidal.mjs): patterns written in Tidal's Haskell. ``tidal`d1 $ s "bd*2"` `` is
 * the tagged template of the [TidalLanguage] and needs no function; `tidal(code, offset)` is the call the transpiler makes of
 * it, for a program that calls it itself, and `initTidal()` defines `d1` to `d9`, which a program that does not call it has
 * anyway inside the Haskell (see [TidalLanguage]).
 */
internal fun registerTidal() {
    EmbeddedLanguages.register("tidal", TidalLanguage)
    catalogue("Tidal") {
        function(listOf("tidal"), 2) { ctx ->
            val code = ctx.arg(0)
            val text = when {
                code is JsArray -> code.items.joinToString("") { ctx.realm.toStr(it) }
                code is String -> code
                else -> throw ctx.realm.typeError("code.replaceAll is not a function")
            }
            val offsetArg = ctx.arg(1)
            val offset = if (offsetArg === Undefined) 0 else jsToNumber(offsetArg).let { if (it.isNaN()) 0 else it.toInt() }
            evaluateTidal(ctx.realm, text, offset, ctx.range ?: SourceRange(0, 0), nodeRanges = false)
        }
        function(listOf("initTidal"), 0) { ctx ->
            definePatternLabels(ctx.realm)
            Undefined
        }
    }
}
