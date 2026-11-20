package cosic.script.catalogue

import cosic.pattern.CosicError
import cosic.pattern.Pattern
import cosic.pattern.Registry
import cosic.pattern.Undefined
import cosic.pattern.curry
import cosic.pattern.jsMerge
import cosic.pattern.pure
import cosic.pattern.reify
import cosic.script.kabel.Kabelsalat

/**
 * Kabelsalat (`K(...)`) and its `worklet` function. `K` is not a function but a transpiler special form
 * ([Kabelsalat.install]); what it leaves in the program is a call of [worklet]. The audio side (Task 3.18) runs the graph
 * that a hap carries as `workletSrc`.
 */
internal fun registerKabelsalat() {
    Kabelsalat.install()
    // `worklet = (...args) => pure({}).worklet(...args)` and `Pattern.prototype.worklet` (pattern.mjs:4487-4498):
    // neither is made with `register`, so the arguments are not patternified.
    Registry.dual(
        listOf("worklet"),
        global = { args -> worklet(pure(LinkedHashMap<String, Any?>()), args) },
        method = { self, args -> worklet(self, args) },
    )
}

/** pattern.mjs:4417-4422 `_asArrayPattern`: a pattern of the list of the values of [pats] at the same time, in the structure of the first. */
private fun asArrayPattern(pats: List<Pattern>): Pattern {
    // `curry(pack, null, pats.length)`: a function of that many arguments; with none, the function itself is the value
    var acc = pure(curry(pats.size) { it })
    for (p in pats) acc = acc.appLeft(p)
    return acc
}

/** `{ ...v }` of a hap value: the entries of a control map, the characters of a string, the items of a list; nothing else has own properties. */
private fun spreadOf(v: Any?): Map<String, Any?> = when (v) {
    is Map<*, *> -> LinkedHashMap<String, Any?>().also { out -> for ((k, value) in v) out[k.toString()] = value }
    is String -> LinkedHashMap<String, Any?>().also { out -> v.forEachIndexed { i, c -> out[i.toString()] = c.toString() } }
    is List<*> -> LinkedHashMap<String, Any?>().also { out -> v.forEachIndexed { i, item -> out[i.toString()] = item } }
    else -> emptyMap()
}

/** `Pattern.prototype.worklet = function (src, ...inputs)` (pattern.mjs:4487-4495). */
private fun worklet(self: Pattern, args: List<Any?>): Pattern {
    val src = args.getOrElse(0) { Undefined }
    val inputs = args.drop(1).map { reify(it) }
    return self.outerBind { v ->
        asArrayPattern(inputs).withValue { vInput ->
            // `const currInputs = v.workletInputs ?: []`, then `currInputs.concat(vInput)`
            val current = (v as? Map<*, *>)?.get("workletInputs")
            val currInputs: List<Any?> = when (current) {
                null, Undefined -> emptyList()
                is List<*> -> current
                else -> throw CosicError("${cosic.pattern.stringifyValues(current, true)}.concat is not a function")
            }
            val joined = if (vInput is List<*>) currInputs + vInput else currInputs + listOf(vInput)
            jsMerge(spreadOf(v), mapOf("workletSrc" to src, "workletInputs" to joined))
        }
    }
}
