package cosic.script.kabel

import cosic.pattern.CosicError
import cosic.script.ArrowFunctionExpression
import cosic.script.BlockStatement
import cosic.script.CallExpression
import cosic.script.ChainExpression
import cosic.script.FunctionExpression
import cosic.script.Identifier
import cosic.script.Literal
import cosic.script.MemberExpression
import cosic.script.Node
import cosic.script.TranspileHooks
import java.util.IdentityHashMap

/**
 * The transpile side of `K(...)` (plugin-kabelsalat.mjs, the `transpilerPlugin` and `extractPatternPlaceholders`).
 *
 * `K(expr, ...rest)` and `pat.K(expr, ...rest)` hold a kabelsalat graph written as JavaScript. The graph is not run
 * here: the call becomes `worklet(template, ...patterns, ...rest)` (`pat.worklet(...)` for the member form), where
 * `template` is the source text of [expr] in which every piece that is a Strudel pattern, a double-quoted or backtick
 * string (already `m(...)` when the rewrite runs) or the argument of `S(...)`, is replaced by `pat[<index>]`, and
 * `patterns` are those pieces in the order they appear in the code. The `worklet` function stores the text and the
 * values of the patterns on each hap (`workletSrc`, `workletInputs`), and the voice substitutes the values and runs the
 * graph (superdough.mjs:637-641).
 */
internal object Kabelsalat {
    /** [template] is the text of the graph with `pat[i]` in place of [patternExprs]`[i]`. */
    class Result(val template: String, val patternExprs: List<Node>)

    private class Target(val node: Node, val patternExpr: Node)

    /** Makes `K` a transpiler special form. Called once, by the catalogue's category function. */
    fun install() {
        TranspileHooks.special("K") { call, _ -> rewrite(call) }
    }

    /** plugin-kabelsalat.mjs:145-200: the `leave` of a `K(...)` call. */
    fun rewrite(call: CallExpression): Node {
        var expr = call.arguments.firstOrNull() ?: throw CosicError("K(...) requires an expression", call.range)
        val rest = call.arguments.drop(1)
        if (shouldCallKabelExpression(expr)) expr = CallExpression(expr.start, expr.end, expr, emptyList(), optional = false)
        val extracted = extract(expr)
        val workletArgs = ArrayList<Node>(1 + extracted.patternExprs.size + rest.size)
        workletArgs.add(Literal(call.start, call.end, extracted.template, jsSingleQuoted(extracted.template)))
        workletArgs.addAll(extracted.patternExprs)
        workletArgs.addAll(rest)
        var callee = call.callee
        if (callee is ChainExpression) callee = callee.expression
        val worklet = Identifier(callee.start, callee.end, "worklet")
        val newCallee: Node = if (callee is MemberExpression) {
            MemberExpression(callee.start, callee.end, callee.obj, worklet, computed = false, optional = false)
        } else {
            worklet
        }
        return CallExpression(call.start, call.end, newCallee, workletArgs, optional = false)
    }

    /** `K(() => { ... })` calls the function: its block is the graph (plugin-kabelsalat.mjs:208-214). */
    private fun shouldCallKabelExpression(expr: Node): Boolean {
        if (expr is ArrowFunctionExpression) return expr.params.isEmpty() && expr.body is BlockStatement
        if (expr is FunctionExpression) return expr.params.isEmpty() && expr.body is BlockStatement
        return false
    }

    /** plugin-kabelsalat.mjs:108-141 `extractPatternPlaceholders`. */
    fun extract(expr: Node): Result {
        val targets = ArrayList<Target>()
        fun walk(node: Node) {
            val patternExpr = strudelPatternExpr(node)
            if (patternExpr != null) {
                targets.add(Target(node, patternExpr))
                return // the walk does not enter a target
            }
            node.forEachChild(::walk)
        }
        walk(expr)
        if (targets.isEmpty()) return Result(printJs(expr), emptyList())

        // Sorted by where the code is (stable), so `pat[0]` is the first one the user wrote whatever the nesting.
        val ordered = targets.sortedBy { it.node.start }
        val placeholders = IdentityHashMap<Node, Node>()
        for ((index, target) in ordered.withIndex()) placeholders[target.node] = placeholder(target.node, index)

        fun rebuild(node: Node): Node = placeholders[node] ?: node.mapChildren(::rebuild)
        return Result(printJs(rebuild(expr)), ordered.map { it.patternExpr })
    }

    /** `S(x)` (or `obj.S(x)`) gives `x`, a mini-notation call `m('...', offset)` gives itself; anything else is not a pattern placeholder. */
    private fun strudelPatternExpr(node: Node): Node? {
        if (node !is CallExpression) return null
        val callee = node.callee
        val isS = when (callee) {
            is Identifier -> callee.name == "S"
            is MemberExpression -> !callee.computed && (callee.property as? Identifier)?.name == "S"
            else -> false
        }
        if (isS) return node.arguments.firstOrNull() ?: throw CosicError("S(...) requires an argument", node.range)
        if (callee is Identifier && callee.name == "m" && (node.arguments.firstOrNull() as? Literal)?.value is String) return node
        return null
    }

    /** `pat[index]`, standing where [at] was. */
    private fun placeholder(at: Node, index: Int): Node = MemberExpression(
        at.start,
        at.end,
        Identifier(at.start, at.end, "pat"),
        Literal(at.start, at.end, index.toDouble(), index.toString()),
        computed = true,
        optional = false,
    )
}
