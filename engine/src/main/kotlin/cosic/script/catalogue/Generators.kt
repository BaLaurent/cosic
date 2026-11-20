package cosic.script.catalogue

import cosic.pattern.GeneratorFunctions
import cosic.pattern.Registry
import cosic.pattern.Undefined
import cosic.pattern.collect
import cosic.pattern.reg3
import cosic.pattern.reg4
import cosic.pattern.reg5

/**
 * The generators and small combinators that make patterns out of numbers and lists: `run`, `binary`, `binaryN`, `binaryL`,
 * `binaryNL`, `base`, `xfade`, `beat`, `morph`, `parray`, `partials`, `phases`, `FX`, `collect`, `hsla` and `hsl`
 * (signal.mjs:343-410, pattern.mjs:1042, 3267-3282, 4099-4552). `ref` is defined with the language, `soft` to `chebyshev` with the
 * controls, `arp` and `arpWith` with the tonal functions and `worklet` with the kabelsalat ones.
 */
internal fun registerGenerators() {
    Registry.function(listOf("run")) { a -> GeneratorFunctions.run(a.arg(0)) }
    Registry.function(listOf("binary")) { a -> GeneratorFunctions.binary(a.arg(0)) }
    Registry.function(listOf("binaryN")) { a -> GeneratorFunctions.binaryN(a.arg(0), a.arg(1)) }
    Registry.function(listOf("binaryL")) { a -> GeneratorFunctions.binaryL(a.arg(0)) }
    Registry.function(listOf("binaryNL")) { a -> GeneratorFunctions.binaryNL(a.arg(0), a.arg(1)) }
    Registry.function(listOf("base")) { a -> GeneratorFunctions.base(a.arg(0), a.arg(1), a.arg(2)) }

    // `xfade(a, pos, b)`; the method is the same with the pattern first, so that the left and right of the fade read left to right
    Registry.dual(
        listOf("xfade"),
        global = { a -> GeneratorFunctions.xfade(a.arg(0), a.arg(1), a.arg(2)) },
        method = { pat, a -> GeneratorFunctions.xfade(pat, a.arg(0), a.arg(1)) },
    )
    Registry.reg3("beat") { t, div, pat -> GeneratorFunctions.beat(t, div, pat) }

    Registry.function(listOf("_morph")) { a -> GeneratorFunctions._morph(a.arg(0), a.arg(1), a.arg(2)) }
    Registry.function(listOf("morph")) { a -> GeneratorFunctions.morph(a.arg(0), a.arg(1), a.arg(2)) }

    Registry.function(listOf("parray")) { a -> GeneratorFunctions.parray(a.arg(0)) }
    // `pat.partials(list)` sets the control; `partials(list)` makes a pattern of it, by naming the list (`as`) as Strudel does
    Registry.dual(
        listOf("partials"),
        global = { a -> GeneratorFunctions.listControl("partials", a.arg(0)) },
        method = { pat, a -> GeneratorFunctions.withList(pat, "partials", a.arg(0)) },
    )
    Registry.dual(
        listOf("phases"),
        global = { a -> GeneratorFunctions.listControl("phases", a.arg(0)) },
        method = { pat, a -> GeneratorFunctions.withList(pat, "phases", a.arg(0)) },
    )

    Registry.reg5("hsla") { h, s, l, a, pat -> GeneratorFunctions.hsla(h, s, l, a, pat) }
    Registry.reg4("hsl") { h, s, l, pat -> GeneratorFunctions.hsl(h, s, l, pat) }

    // methods that are no global functions
    catalogue("Generators") {
        method(listOf("FX"), 0) { ctx ->
            GeneratorFunctions.fx(ctx.bridge.receiverOf(ctx.self, "FX"), ctx.args.indices.map { ctx.kernelArg(it) })
        }
        method(listOf("collect"), 0) { ctx -> ctx.bridge.receiverOf(ctx.self, "collect").collect() }
    }
}

private fun List<Any?>.arg(i: Int): Any? = getOrElse(i) { Undefined }
