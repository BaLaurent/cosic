package cosic.script.catalogue

import cosic.pattern.CosicError
import cosic.pattern.Undefined
import cosic.script.EmbeddedLanguages
import cosic.script.PatternValue
import cosic.script.js.JsArray
import cosic.script.js.describe
import cosic.script.js.toNumber
import cosic.script.js.toStr
import cosic.script.mondo.MondoLanguage
import cosic.script.mondo.MondoParser
import cosic.script.mondo.mondoPattern

/*
 * Mondo, a notation of its own that is a small lisp with the sugar of mini-notation (`@strudel/mondo`, see
 * [cosic.script.mondo.MondoParser]). A program uses it as a template, ``mondo`s [bd hh] # fast 2` ``, or calls the
 * functions the package exports: `mondo(code, offset)`, `mondolang(code)`, `mondi(code, offset)` (the code in square
 * brackets) and `getLocations(code, offset)` (where the atoms of the code are).
 */

/**
 * Makes ``mondo`...` `` and ``mondolang`...` `` programs in mondo. The tags are the transpiler's, which works before there
 * is a realm, and they are shared by all of them; every realm that is made says them again, so that they are the real ones
 * whatever has been put in their place meanwhile.
 */
private fun registerMondoLanguages() {
    EmbeddedLanguages.register("mondo", MondoLanguage(withOffset = true))
    EmbeddedLanguages.register("mondolang", MondoLanguage(withOffset = false))
}

internal fun registerMondo() {
    registerMondoLanguages()
    catalogue("Mondo") {
        mondoFunction("mondo", 2) { ctx -> mondoPattern(ctx.bridge, codeOf(ctx, "mondo", ctx.arg(0)), offsetOf(ctx, ctx.arg(1))) }
        mondoFunction("mondolang", 1) { ctx -> mondoPattern(ctx.bridge, codeOf(ctx, "mondolang", ctx.arg(0)), 0) }
        mondoFunction("mondi", 2) { ctx -> mondoPattern(ctx.bridge, "[" + ctx.realm.toStr(ctx.arg(0)) + "]", offsetOf(ctx, ctx.arg(1))) }
        mondoFunction("getLocations", 2) { ctx ->
            val code = ctx.arg(0) as? String ?: ctx.fail("getLocations(): expected a string, got ${ctx.realm.describe(ctx.arg(0))}")
            val locations = withoutRange { MondoParser().getLocations(code, offsetOf(ctx, ctx.arg(1))) }
            ctx.realm.newArray(locations.map { ctx.realm.newArray(listOf(it[0].toDouble(), it[1].toDouble())) })
        }
    }
}

/**
 * A global function of the category. It is defined as a global value so that making a realm brings the languages back
 * (see [registerMondoLanguages]).
 */
private fun CatalogueBuilder.mondoFunction(name: String, length: Int, impl: (CallContext) -> Any?) =
    global(name) { bridge ->
        registerMondoLanguages()
        bridge.realm.functionAt(name, length) { self, args, site ->
            bridge.result(withoutRange { impl(CallContext(bridge, self, args, site?.range)) }, site)
        }
    }

/**
 * The positions an error of the notation carries are those of the code counted from the offset the call was given, which
 * need not be where the code is in the program: the error belongs to the call.
 */
private fun <T> withoutRange(block: () -> T): T = try {
    block()
} catch (e: CosicError) {
    throw CosicError(e.message)
}

/** The code of a call: a string, or an array of strings that is joined (the pieces of a template). */
private fun codeOf(ctx: CallContext, function: String, code: Any?): String = when (code) {
    is String -> code
    is JsArray -> code.items.joinToString("") { if (it == null || it === Undefined) "" else ctx.realm.toStr(it) }
    is PatternValue -> ctx.fail("$function(): pass a single-quoted string or use a $function`\u2026` template")
    else -> ctx.fail("$function(): expected a string, got ${ctx.realm.describe(code)}")
}

/** The offset argument: `undefined` is 0, as the default parameter of the original makes it. */
private fun offsetOf(ctx: CallContext, offset: Any?): Int {
    if (offset === Undefined) return 0
    val number = ctx.realm.toNumber(offset)
    return if (number.isNaN()) 0 else number.toInt()
}
