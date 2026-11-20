package cosic.script.catalogue

import cosic.pattern.CosicError
import cosic.pattern.Pattern
import cosic.pattern.Registry
import cosic.pattern.Undefined
import cosic.pattern._brandBy
import cosic.pattern._degradeBy
import cosic.pattern._degradeByWith
import cosic.pattern._irand
import cosic.pattern._scramble
import cosic.pattern._shuffle
import cosic.pattern._someCyclesBy
import cosic.pattern._sometimesBy
import cosic.pattern._undegradeBy
import cosic.pattern.berlin
import cosic.pattern.brand
import cosic.pattern.brandBy
import cosic.pattern.callFn
import cosic.pattern.choose
import cosic.pattern.chooseCycles
import cosic.pattern.chooseIn
import cosic.pattern.chooseInWith
import cosic.pattern.chooseWith
import cosic.pattern.fromBipolarValues
import cosic.pattern.irand
import cosic.pattern.jsGetRandsAtTime
import cosic.pattern.patternOf
import cosic.pattern.perlin
import cosic.pattern.rand
import cosic.pattern.rand2
import cosic.pattern.randL
import cosic.pattern.randrun
import cosic.pattern.rawChooseWith
import cosic.pattern.reg1
import cosic.pattern.reg2
import cosic.pattern.reg3
import cosic.pattern.useRNG
import cosic.pattern.wchoose
import cosic.pattern.wchooseCycles
import cosic.pattern.withSeed

/** `pat.range(...)` of the argument that picks, which fails like JavaScript does when it is not a pattern. */
private fun pickerArg(v: Any?): Pattern = v as? Pattern ?: throw CosicError("pat.range is not a function")

/** The array of things to choose from (`xs.map(reify)`). */
private fun choicesArg(v: Any?): List<Any?> = (v as? List<*>)?.toList() ?: throw CosicError("xs.map is not a function")

/**
 * The random functions of `signal.mjs`: the random signals, choosing from lists (`choose`, `wchoose`, ...), `seed`, `shuffle`
 * and `scramble`, and everything that drops or applies things at random, `degradeBy` to `almostAlways`.
 */
internal fun registerRandom() {
    // random signals
    Registry.constant(listOf("rand"), rand)
    Registry.constant(listOf("rand2"), rand2)
    Registry.constant(listOf("brand"), brand)
    Registry.constant(listOf("perlin"), perlin)
    Registry.constant(listOf("berlin"), berlin)
    Registry.function(listOf("_brandBy")) { a -> _brandBy(a.getOrElse(0) { Undefined }) }
    Registry.function(listOf("brandBy")) { a -> brandBy(a.getOrElse(0) { Undefined }) }
    Registry.function(listOf("_irand")) { a -> _irand(a.getOrElse(0) { Undefined }) }
    Registry.function(listOf("irand")) { a -> irand(a.getOrElse(0) { Undefined }) }
    Registry.function(listOf("randL")) { a -> randL(a.getOrElse(0) { Undefined }) }
    Registry.function(listOf("randrun")) { a -> randrun(a.getOrElse(0) { Undefined }) }

    // the generator and the seed
    Registry.function(listOf("useRNG")) { a -> useRNG(a.getOrElse(0) { Undefined }) }
    Registry.function(listOf("getRandsAtTime")) { a ->
        jsGetRandsAtTime(a.getOrElse(0) { Undefined }, a.getOrElse(1) { Undefined }, a.getOrElse(2) { Undefined })
    }
    Registry.function(listOf("withSeed")) { a ->
        val func = a.getOrElse(0) { Undefined }
        withSeed({ seed -> callFn(func, seed) }, patternOf(a.getOrElse(1) { Undefined }))
    }
    Registry.reg2("seed") { n, pat -> withSeed({ n }, pat) }

    // choosing
    Registry.function(listOf("__chooseWith")) { a -> rawChooseWith(pickerArg(a.getOrElse(0) { Undefined }), choicesArg(a.getOrElse(1) { Undefined })) }
    Registry.function(listOf("chooseWith")) { a -> chooseWith(pickerArg(a.getOrElse(0) { Undefined }), choicesArg(a.getOrElse(1) { Undefined })) }
    Registry.function(listOf("chooseInWith")) { a -> chooseInWith(pickerArg(a.getOrElse(0) { Undefined }), choicesArg(a.getOrElse(1) { Undefined })) }
    Registry.dual(listOf("choose"), global = { a -> choose(a) }, method = { pat, a -> chooseWith(pat, a) })
    Registry.function(listOf("chooseIn")) { a -> chooseIn(a) }
    Registry.function(listOf("chooseCycles", "randcat")) { a -> chooseCycles(a) }
    Registry.function(listOf("wchoose")) { a -> wchoose(a) }
    Registry.function(listOf("wchooseCycles", "wrandcat")) { a -> wchooseCycles(a) }
    catalogue("Random") {
        // pat.choose2(...xs): like choose, for a pattern in -1..1
        method(listOf("choose2"), 0) { ctx ->
            chooseWith(ctx.bridge.receiverOf(ctx.self, "choose2").fromBipolarValues(), ctx.args.indices.map { ctx.kernelArg(it) })
        }
    }

    // shuffling
    Registry.reg2("shuffle") { n, pat -> pat._shuffle(n) }
    Registry.reg2("scramble") { n, pat -> pat._scramble(n) }

    // dropping events and applying functions at random
    Registry.reg3("degradeByWith") { withPat, x, pat -> pat._degradeByWith(withPat, x) }
    Registry.reg2("degradeBy", keep = true) { x, pat -> pat._degradeBy(x) }
    Registry.reg1("degrade", keep = true) { pat -> pat._degradeBy(0.5) }
    Registry.reg2("undegradeBy", keep = true) { x, pat -> pat._undegradeBy(x) }
    Registry.reg1("undegrade", keep = true) { pat -> pat._undegradeBy(0.5) }
    Registry.reg3("sometimesBy") { patx, func, pat -> pat._sometimesBy(patx, func) }
    Registry.reg2("sometimes") { func, pat -> pat._sometimesBy(0.5, func) }
    Registry.reg3("someCyclesBy") { patx, func, pat -> pat._someCyclesBy(patx, func) }
    Registry.reg2("someCycles") { func, pat -> pat._someCyclesBy(0.5, func) }
    Registry.reg2("often") { func, pat -> pat._sometimesBy(0.75, func) }
    Registry.reg2("rarely") { func, pat -> pat._sometimesBy(0.25, func) }
    Registry.reg2("almostNever") { func, pat -> pat._sometimesBy(0.1, func) }
    Registry.reg2("almostAlways") { func, pat -> pat._sometimesBy(0.9, func) }
    Registry.reg2("never") { _, pat -> pat }
    Registry.reg2("always") { func, pat -> callFn(func, pat) }
}
