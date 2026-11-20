package cosic.script.tidal

import cosic.pattern.Undefined
import cosic.pattern.reify
import cosic.script.Bridge
import cosic.script.Realm
import cosic.pattern.JsFn
import cosic.script.js.JsFunction
import cosic.script.js.JsObject

/*
 * The operators of Tidal, as packages/tidal/tidal.mjs defines them (getInfixOperators). Each is a method of the pattern on
 * its left, with the pattern on its right as the argument, and the four spellings of an operator are four alignments:
 *
 *     >  #  +  -  *  /      the method set, add, sub, mul, div: `reify(l)[name](reify(r))`
 *     |o                    `.in`: the structure comes from the left
 *     o|                    `.out`: from the right
 *     |o|                   `.mix`: from both
 *
 * and the rest: `~>` is `late` and `<~` is `early` (with the pattern on the left, not on the right: `0.25 <~ p` shifts the
 * number 0.25 by `p`, which fails when `p` is queried, where `(0.25 <~) p` is `p.early(0.25)`, because hs2js applies a left
 * section the other way round), and `<$>` is `fmap` with a join. `$`, `.` and `&` belong to the evaluator.
 *
 * The methods are looked up on the pattern, as `pat.add.out(x)` is in a program, so whichever function the catalogue
 * registered under the name is the one that runs.
 */

internal enum class TidalOpKind { METHOD, LATE, EARLY, FMAP }

internal class TidalOp(val kind: TidalOpKind, val method: String = "", val alignment: String? = null)

internal object TidalOps {
    /** The 24 operators made of the six base operators and the three alignments, and `~>`, `<~` and `<$>`. */
    val table: Map<String, TidalOp> by lazy {
        val operators = listOf(">" to "set", "#" to "set", "+" to "add", "-" to "sub", "*" to "mul", "/" to "div")
        val ops = LinkedHashMap<String, TidalOp>()
        for ((symbol, name) in operators) {
            ops[symbol] = TidalOp(TidalOpKind.METHOD, name)
            ops["|$symbol"] = TidalOp(TidalOpKind.METHOD, name, "in")
            ops["$symbol|"] = TidalOp(TidalOpKind.METHOD, name, "out")
            ops["|$symbol|"] = TidalOp(TidalOpKind.METHOD, name, "mix")
        }
        ops["~>"] = TidalOp(TidalOpKind.LATE)
        ops["<~"] = TidalOp(TidalOpKind.EARLY)
        ops["<\$>"] = TidalOp(TidalOpKind.FMAP)
        ops
    }

    /** `reify(value)` as a value of the program. */
    private fun reified(bridge: Bridge, value: Any?): Any? = bridge.wrap(reify(bridge.toKernel(value)))

    /** `base[name]`, with the error JavaScript gives for a base that has no properties. */
    private fun member(realm: Realm, base: Any?, name: String): Any? = when (base) {
        is JsObject -> base.get(name, base)
        is String -> realm.stringPrototype.get(name, base)
        is Double -> realm.numberPrototype.get(name, base)
        is Boolean -> realm.booleanPrototype.get(name, base)
        null -> throw realm.typeError("Cannot read properties of null (reading '$name')")
        Undefined -> throw realm.typeError("Cannot read properties of undefined (reading '$name')")
        else -> Undefined
    }

    private fun callable(value: Any?): Boolean = value is JsFunction || value is JsFn

    /** `op(left, right)`. */
    fun apply(bridge: Bridge, op: TidalOp, left: Any?, right: Any?): Any? {
        val realm = bridge.realm
        return when (op.kind) {
            TidalOpKind.METHOD -> {
                val pattern = reified(bridge, left)
                var fn = member(realm, pattern, op.method)
                var self: Any? = pattern
                if (op.alignment != null) {
                    self = fn
                    fn = member(realm, fn, op.alignment)
                }
                if (!callable(fn)) throw realm.typeError("reify(...)[name]${if (op.alignment != null) "[a]" else ""} is not a function")
                realm.callFunction(fn, self, listOf(reified(bridge, right)))
            }
            TidalOpKind.LATE, TidalOpKind.EARLY -> {
                val name = if (op.kind == TidalOpKind.LATE) "late" else "early"
                val pattern = reified(bridge, left)
                val fn = member(realm, pattern, name)
                if (!callable(fn)) throw realm.typeError("reify(...).$name is not a function")
                realm.callFunction(fn, pattern, listOf(reified(bridge, right)))
            }
            TidalOpKind.FMAP -> {
                val pattern = reified(bridge, right)
                val fmap = member(realm, pattern, "fmap")
                if (!callable(fmap)) throw realm.typeError("reify(...).fmap is not a function")
                val mapped = realm.callFunction(fmap, pattern, listOf(left))
                val join = member(realm, mapped, "outerJoin")
                if (!callable(join)) throw realm.typeError("reify(...).fmap(...).outerJoin is not a function")
                realm.callFunction(join, mapped, emptyList())
            }
        }
    }
}
