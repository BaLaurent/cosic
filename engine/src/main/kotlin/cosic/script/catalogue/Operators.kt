package cosic.script.catalogue

import cosic.pattern.Alignment
import cosic.pattern.OPS
import cosic.pattern.Pattern
import cosic.pattern.Registry
import cosic.pattern.Undefined
import cosic.pattern._range
import cosic.pattern._range2
import cosic.pattern._rangex
import cosic.pattern.aligned
import cosic.pattern.alignHook
import cosic.pattern.callFn
import cosic.pattern.ceil
import cosic.pattern.curry
import cosic.pattern.floor
import cosic.pattern.fromBipolar
import cosic.pattern.log2
import cosic.pattern.operatorGlobal
import cosic.pattern.patternOf
import cosic.pattern.ratio
import cosic.pattern.reg1
import cosic.pattern.reg3
import cosic.pattern.reify
import cosic.pattern.round
import cosic.pattern.setDefaultJoin
import cosic.pattern.toBipolar

/*
 * The operators and numeric functions of pattern.mjs (CORE Task T2): `add`, `mul`, `set`, `lt`, ... with their alignments
 * (`pat.add.out(x)`), `struct`, `mask`, `reset`, `restart`, `range`, `round`, ... and `setDefaultJoin`. The pattern side is
 * `pattern/Operators.kt`.
 *
 * The operators are not `register`ed functions: `pat.add(x)` is `pat.add.in(x)` (or what `setDefaultJoin` chose), one `fmap` and
 * one `appLeft`, with no patternified arguments and no `innerJoin`. Each one is therefore a `Registry.dual` entry, flagged
 * `alignable` so that the bridge makes `pat.add` an accessor with the alignments as properties. The names that exist as methods
 * only (`in`, `structAll`, `_add`, ...) are defined with the catalogue builder, which also keeps them from replacing the
 * `Registry` entry of a function of another category that has the same name (the global `squeeze` of pick.mjs).
 */
internal fun registerOperators() {
    registerOperatorEntries()
    registerStructure()
    registerNumeric()
    registerCurriedGlobals()
    Registry.function(listOf("setDefaultJoin")) { a ->
        setDefaultJoin(a.getOrElse(0) { Undefined })
        Undefined
    }
    alignHook = { pat, op, how, args -> pat.aligned(op, how, args) }

    catalogue("Operators") {
        plainOperatorMethods()
        alignmentMethods()
        structureMethods()
    }
}

// ---- the operators ---------------------------------------------------------------------------------------------------

/** `set keep keepif add ... func`: a global (`add(1, pat)`) and a method with the alignments as properties (`pat.add.mix(1)`). */
private fun registerOperatorEntries() {
    val globals = OPS.keys.associateWith { operatorGlobal(it) }
    for (op in OPS.keys) {
        val global = globals.getValue(op)
        Registry.dual(
            listOf(op),
            alignable = true,
            global = { args -> global.call(args) },
            method = { pat, args -> pat.aligned(op, Alignment.default, args) },
        )
    }
}

/** The plain version of each operator, `pat._add(1)`: the operator of every value and the number as it is (pattern.mjs:1394). */
private fun CatalogueBuilder.plainOperatorMethods() {
    for ((op, f) in OPS) {
        patternMethod(listOf("_$op"), 1) { pat, args -> pat.fmap { x -> f(x, args.getOrElse(0) { Undefined }) } }
    }
}

/** `pat.in(x)` and the others: the alignment of `set` (pattern.mjs:1448-1452). The arguments are passed on as one array. */
private fun CatalogueBuilder.alignmentMethods() {
    for (how in listOf("in", "out", "mix", "squeeze", "squeezeout", "poly")) {
        patternMethod(listOf(how), 0) { pat, args -> pat.aligned("set", how, listOf(args)) }
    }
}

// ---- struct, mask, reset, restart -------------------------------------------------------------------------------------

/** `struct` and `mask`, which are also functions of a pattern of structure and the pattern (`mask("1 0", pat)`, pattern.mjs:1963). */
private fun registerStructure() {
    for ((name, how) in listOf("struct" to "out", "mask" to "in")) {
        val global = curry(2) { a -> reify(a[1]).aligned("keepif", how, listOf(a[0])) }
        Registry.dual(
            listOf(name),
            global = { args -> global.call(args) },
            method = { pat, args -> pat.aligned("keepif", how, args) },
        )
    }
}

/** The methods that are `keepif` or `keep` with an alignment (pattern.mjs:1463-1507). `struct` keeps the structure of its argument, `mask` that of the pattern. */
private fun CatalogueBuilder.structureMethods() {
    val methods = listOf(
        Triple("structAll", "keep", "out"),
        Triple("maskAll", "keep", "in"),
        Triple("reset", "keepif", "reset"),
        Triple("resetAll", "keep", "reset"),
        Triple("restart", "keepif", "restart"),
        Triple("restartAll", "keep", "restart"),
    )
    for ((name, op, how) in methods) {
        patternMethod(listOf(name), 0) { pat, args -> pat.aligned(op, how, args) }
    }
}

// ---- numeric functions ----------------------------------------------------------------------------------------------

private fun registerNumeric() {
    Registry.reg1("round") { it.round() }
    Registry.reg1("floor") { it.floor() }
    Registry.reg1("log2") { it.log2() }
    Registry.reg1("ceil") { it.ceil() }
    Registry.reg1("toBipolar") { it.toBipolar() }
    Registry.reg1("fromBipolar") { it.fromBipolar() }
    Registry.reg1("ratio") { it.ratio() }
    Registry.reg3("range") { min, max, pat -> pat._range(min, max) }
    Registry.reg3("rangex") { min, max, pat -> pat._rangex(min, max) }
    Registry.reg3("range2") { min, max, pat -> pat._range2(min, max) }
}

// ---- curried globals of methods -------------------------------------------------------------------------------------

/**
 * `withValue`, `bind` and the other binds as functions of a function and a pattern, curried (`export const bind = curry((a, b) =>
 * reify(b).bind(a))`, pattern.mjs:1966-1973). The methods are the bridge's own members of every pattern.
 */
private fun registerCurriedGlobals() {
    fun curried(name: String, f: (Any?, Pattern) -> Pattern) {
        val fn = curry(2) { a -> f(a[0], reify(a[1])) }
        Registry.function(listOf(name)) { args -> fn.call(args) }
    }
    curried("withValue") { f, pat -> pat.withValue { callFn(f, it) } }
    curried("bind") { f, pat -> pat.bind { patternOf(callFn(f, it)) } }
    curried("innerBind") { f, pat -> pat.innerBind { patternOf(callFn(f, it)) } }
    curried("outerBind") { f, pat -> pat.outerBind { patternOf(callFn(f, it)) } }
    curried("squeezeBind") { f, pat -> pat.squeezeBind { patternOf(callFn(f, it)) } }
    curried("stepBind") { f, pat -> pat.stepBind { patternOf(callFn(f, it)) } }
    curried("polyBind") { f, pat -> pat.polyBind { patternOf(callFn(f, it)) } }
}

// ---- helpers ----------------------------------------------------------------------------------------------------------

/** A method of `Pattern.prototype` that works on the pattern and the arguments as the kernel sees them. */
private fun CatalogueBuilder.patternMethod(names: List<String>, length: Int, impl: (Pattern, List<Any?>) -> Any?) {
    method(names, length) { ctx -> impl(ctx.bridge.receiverOf(ctx.self, names[0]), ctx.args.map { ctx.bridge.toKernel(it) }) }
}
