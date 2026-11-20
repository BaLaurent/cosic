package cosic.script.catalogue

import cosic.pattern.Pattern
import cosic.pattern.Registry
import cosic.pattern.pickF
import cosic.pattern.pickGlobal
import cosic.pattern.pickPatterns
import cosic.pattern.pickmodF
import cosic.pattern.reg2
import cosic.pattern.reg3
import cosic.pattern.squeeze

/**
 * The functions of pick.mjs (see [pickPatterns]): `pick` and `pickmod` (inner join), `pickOut` and `pickmodOut` (outer join),
 * `pickRestart` and `pickmodRestart`, `pickReset` and `pickmodReset`, `inhabit` (`pickSqueeze`) and `inhabitmod`
 * (`pickmodSqueeze`), `pickF` and `pickmodF`, which pick functions, and `squeeze`.
 *
 * The global `pick` is not the registered function: it still takes its arguments in the old order when the second one is
 * a list, and a global function is not curried. The global `squeeze(pat, xs)` is a plain function too; as a method
 * of patterns, `squeeze` is the alignment of the operators.
 */
internal fun registerPick() {
    fun picker(names: List<String>, modulo: Boolean, join: (Pattern) -> Pattern) {
        Registry.reg2(*names.toTypedArray()) { lookup, pat -> join(pickPatterns(lookup, pat, modulo)) }
    }
    picker(listOf("pick"), false) { it.innerJoin() }
    picker(listOf("pickmod"), true) { it.innerJoin() }
    picker(listOf("pickOut"), false) { it.outerJoin() }
    picker(listOf("pickmodOut"), true) { it.outerJoin() }
    picker(listOf("pickRestart"), false) { it.restartJoin() }
    picker(listOf("pickmodRestart"), true) { it.restartJoin() }
    picker(listOf("pickReset"), false) { it.resetJoin() }
    picker(listOf("pickmodReset"), true) { it.resetJoin() }
    picker(listOf("inhabit", "pickSqueeze"), false) { it.squeezeJoin() }
    picker(listOf("inhabitmod", "pickmodSqueeze"), true) { it.squeezeJoin() }

    Registry.reg3("pickF") { pickPattern, lookup, pat -> pat.pickF(pickPattern, lookup) }
    Registry.reg3("pickmodF") { pickPattern, lookup, pat -> pat.pickmodF(pickPattern, lookup) }

    catalogue("Pick") {
        function(listOf("pick"), 2) { ctx -> pickGlobal(ctx.kernelArg(0), ctx.kernelArg(1)) }
        function(listOf("squeeze"), 2) { ctx -> squeeze(ctx.kernelArg(0), ctx.kernelArg(1)) }
    }
}
