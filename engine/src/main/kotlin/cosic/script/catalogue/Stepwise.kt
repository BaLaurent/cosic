package cosic.script.catalogue

import cosic.pattern.Registry
import cosic.pattern.Steps
import cosic.pattern.Undefined
import cosic.pattern._contract
import cosic.pattern._drop
import cosic.pattern._grow
import cosic.pattern._growlist
import cosic.pattern._pace
import cosic.pattern._polymeterListSteps
import cosic.pattern._replicate
import cosic.pattern._shrink
import cosic.pattern._shrinklist
import cosic.pattern._take
import cosic.pattern.arrange
import cosic.pattern.growlist
import cosic.pattern.jsTruthy
import cosic.pattern.polymeter
import cosic.pattern.reg2
import cosic.pattern.seqPLoop
import cosic.pattern.shrinklist
import cosic.pattern.stackBy
import cosic.pattern.stackCenter
import cosic.pattern.stackLeft
import cosic.pattern.stackRepeat
import cosic.pattern.stackRight
import cosic.pattern.stepReg2
import cosic.pattern.stepalt
import cosic.pattern.tour
import cosic.pattern.zip

/**
 * The stepwise functions of pattern.mjs (see cosic.pattern.Stepwise.kt): what works with the number of steps per cycle of a
 * pattern, and the combinators that line patterns up by them. `stepcat`, `timecat`, `expand` and `extend`, with their `s_`
 * aliases, are part of the kernel's catalogue (Kernel.kt).
 *
 * `take`, `drop`, `replicate`, `contract`, `shrink` and `grow` join their patterned arguments by steps (`stepJoin`) instead of
 * `innerJoin`. `polymeter`, `zip`, `stepalt`, `arrange`, `seqPLoop` and `stackBy` are plain global functions of the module:
 * there is no `Pattern.prototype.polymeter` or `zip`, so their `s_` aliases are global only too.
 */
internal fun registerStepwise() {
    val pace = Registry.reg2("pace") { target, pat -> pat._pace(target) }.single()
    // `Pattern.prototype.steps = Pattern.prototype.pace` and `export const steps = pace`: the same function. It has no `_steps`
    // of its own: that is the steps a pattern has.
    Registry.dual(listOf("steps"), global = { a -> pace.global(a) }, method = { pat, a -> pace.method(pat, a) })

    Registry.stepReg2("take", "s_add") { i, pat -> pat._take(i) }
    Registry.stepReg2("drop", "s_sub") { i, pat -> pat._drop(i) }
    Registry.stepReg2("replicate") { factor, pat -> pat._replicate(factor) }
    Registry.stepReg2("contract", "s_contract") { factor, pat -> pat._contract(factor) }
    Registry.reg2("shrink", "s_taper", join = { it.stepJoin() }) { amount, pat -> pat._shrink(amount) }
    Registry.reg2("grow", join = { it.stepJoin() }) { amount, pat -> pat._grow(amount) }

    Registry.function(listOf("polymeter", "pm", "s_polymeter")) { a -> polymeter(a) }
    Registry.function(listOf("_polymeterListSteps")) { a -> _polymeterListSteps(a.getOrElse(0) { Undefined }, a.drop(1)) }
    Registry.function(listOf("stepalt", "s_alt")) { a -> stepalt(a) }
    Registry.function(listOf("arrange")) { a -> arrange(a) }
    Registry.function(listOf("seqPLoop")) { a -> seqPLoop(a) }
    Registry.function(listOf("zip", "s_zip")) { a -> zip(a) }

    // `Pattern.prototype.tour` and the stack functions put the pattern they are called on in front of their arguments.
    Registry.variadic(listOf("tour", "s_tour")) { a -> tour(a) }
    Registry.variadic(listOf("stackLeft")) { a -> stackLeft(a) }
    Registry.variadic(listOf("stackRight")) { a -> stackRight(a) }
    Registry.variadic(listOf("stackCenter", "stackCentre")) { a -> stackCenter(a) }
    Registry.variadic(listOf("stackRepeat")) { a -> stackRepeat(a) }
    Registry.function(listOf("stackBy")) { a -> stackBy(a.getOrElse(0) { Undefined }, a.drop(1)) }

    // `calculateSteps(false)` stops patterns from keeping track of their steps (it persists across evaluations, as in Strudel).
    Registry.function(listOf("calculateSteps")) { a ->
        Steps.enabled = jsTruthy(a.getOrElse(0) { Undefined })
        Undefined
    }

    // `shrinklist` and `growlist` answer lists of patterns, which a registered function cannot: the global is a plain
    // two-argument function (not curried), the method takes the amount.
    catalogue("Stepwise") {
        function(listOf("shrinklist", "s_taperlist"), 2) { ctx -> shrinklist(ctx.kernelArg(0), ctx.kernelArg(1)) }
        function(listOf("growlist"), 2) { ctx -> growlist(ctx.kernelArg(0), ctx.kernelArg(1)) }
        method(listOf("shrinklist", "s_taperlist"), 1) { ctx -> ctx.bridge.receiverOf(ctx.self, "shrinklist")._shrinklist(ctx.kernelArg(0)) }
        method(listOf("growlist"), 1) { ctx -> ctx.bridge.receiverOf(ctx.self, "growlist")._growlist(ctx.kernelArg(0)) }
    }
}
