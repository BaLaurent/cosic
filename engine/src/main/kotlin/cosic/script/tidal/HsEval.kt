package cosic.script.tidal

import cosic.pattern.Undefined

/*
 * The evaluator of the Haskell syntax tree: a port of hs2js's `run` (packages/hs2js/src/hs2js.mjs), which Strudel's `tidal`
 * calls. It works on the tree as it is, by node type and by the position of the children, so it does with a tree the
 * parser made what hs2js does with one tree-sitter made, errors included: a node it has no rule for fails with
 * `unhandled leaf type X` (no children) or `unhandled branch type X` (more than one), a node with exactly one child is
 * its child.
 *
 * What a program can reach is the [HsHost]'s: the global scope that names are looked up in (and that bindings write to),
 * how numbers, strings and lists are made, how a function is called, and the table of operators.
 */

internal interface HsHost {
    /** `scope[name]` of the global scope; `undefined` for a name nobody defined. */
    fun lookup(name: String): Any?

    /** `scope[name] = value` on the global scope, which is what a top-level `x = ...` and `f x = ...` do. */
    fun assign(name: String, value: Any?)

    /** `Number(text)` for the text of an `integer` or a `float`. */
    fun number(text: String): Any?

    /** The `string` hook of the operator table: the value of a string literal (with its quotes). */
    fun string(node: HsNode): Any?

    /** A function of one argument, which the program can call and pass around. */
    fun function(body: (Any?) -> Any?): Any?

    /** `typeof value === 'function'`. */
    fun isFunction(value: Any?): Boolean

    /** `fn(arg)`, for the application at [at]. */
    fun call(fn: Any?, arg: Any?, at: HsNode?): Any?

    /** An array holding [items]. */
    fun list(items: List<Any?>): Any?

    /** The `ops[symbol]` of the operator table, or null for a symbol without one. */
    fun operator(symbol: String): HsOperator?

    /** The value `ops.constructor(node)` makes of a constructor: `Object(node)` is the node itself, an object that is no function. */
    fun nodeValue(node: HsNode): Any?

    /** An `Error` of the program that stopped at [at]. */
    fun error(message: String, at: HsNode?): RuntimeException

    /** A `TypeError` of the program (what the JavaScript engine reports for a misuse of a value) that stopped at [at]. */
    fun typeError(message: String, at: HsNode?): RuntimeException
}

/** An entry of the operator table: `(left, right) => ...`, for the infix expression [at]. */
internal fun interface HsOperator {
    fun apply(left: Any?, right: Any?, at: HsNode?): Any?
}

internal class HsEval(private val host: HsHost) {
    /** One level of the scope chain: the variable a function bound, and the scope it was made in. The root is the host's global scope. */
    private class Scope(val parent: Scope?, val name: String?, val value: Any?) {
        val own: HashMap<String, Any?> = HashMap(0)
    }

    private val root = Scope(null, null, null)

    fun evaluate(tree: HsNode): Any? = run(tree, root)

    private fun lookup(scope: Scope, name: String): Any? {
        var s: Scope? = scope
        while (s != null) {
            if (s.own.containsKey(name)) return s.own[name]
            if (s.parent != null && s.name == name) return s.value
            s = s.parent
        }
        return host.lookup(name)
    }

    private fun assign(scope: Scope, name: String, value: Any?) {
        if (scope === root) host.assign(name, value) else scope.own[name] = value
    }

    private fun child(node: HsNode, index: Int): HsNode? = node.children.getOrNull(index)

    private fun missing(property: String, at: HsNode?): RuntimeException =
        host.typeError("Cannot read properties of undefined (reading '$property')", at)

    private fun run(node: HsNode?, scope: Scope): Any? {
        if (node == null) throw missing("type", null)
        // `ops[node.type]` comes first: the operator table also answers `string` (the hook of tidal) and, through
        // Object.prototype, `constructor`, whose value is the node itself
        when (node.type) {
            "string" -> return host.string(node)
            "constructor" -> return host.nodeValue(node)
        }
        host.operator(node.type)?.let { return it.apply(host.nodeValue(node), Undefined, node) }
        val c = node.children
        return when (node.type) {
            "ERROR" -> throw host.error("invalid syntax: \"${node.text}\"", node)
            "declarations" -> {
                var result: Any? = Undefined
                for (declaration in c) result = run(declaration, scope)
                result
            }
            "integer", "float" -> host.number(node.text)
            "lambda" -> {
                val patterns = child(node, 1) ?: throw missing("children", node)
                curry(patterns.children, child(node, 3), scope)
            }
            "function" -> {
                val variable = child(node, 0) ?: throw missing("text", node)
                val patterns = child(node, 1) ?: throw missing("children", node)
                val fn = curry(patterns.children, child(node, 2), scope)
                assign(scope, variable.text, fn)
                fn
            }
            "list" -> host.list(c.filterIndexed { i, _ -> i % 2 == 1 }.map { run(it, scope) })
            "match" -> {
                if (child(node, 0)?.text != "=" || c.size != 2) throw host.error("match node so far only support simple assignments", node)
                run(c[1], scope)
            }
            "bind" -> {
                if (c.size != 2) throw host.error("expected 2 children for node type bind", node)
                if (c[0].type != "variable") throw host.error("expected variable as first child of bind node", node)
                if (c[1].type != "match") throw host.error("expected match as first child of bind node", node)
                val value = run(c[1], scope)
                assign(scope, c[0].text, value)
                value
            }
            "variable" -> lookup(scope, node.text)
            "infix" -> {
                val op = child(node, 1) ?: throw missing("text", node)
                val left = run(child(node, 0), scope)
                val right = run(child(node, 2), scope)
                infix(left, op.text, right, node)
            }
            "apply" -> {
                if (c.size != 2) throw host.error("expected 2 children for node type apply, got ${c.size}", node)
                val fn = run(c[0], scope)
                val arg = run(c[1], scope)
                if (!host.isFunction(fn)) throw host.error("${c[0].text} is not a function", node)
                host.call(fn, arg, node)
            }
            "left_section" -> {
                val operand = run(child(node, 1), scope)
                host.function { left ->
                    val op = child(node, 2) ?: throw missing("text", node)
                    infix(left, op.text, operand, node)
                }
            }
            "right_section" -> {
                val operand = run(child(node, 2), scope)
                host.function { left ->
                    val op = child(node, 1) ?: throw missing("text", node)
                    infix(left, op.text, operand, node)
                }
            }
            "parens" -> {
                if (c.size != 3) throw host.error("expected 3 children for node type parens", node)
                run(c[1], scope)
            }
            else -> when {
                c.isEmpty() -> throw host.error("unhandled leaf type ${node.type}", node)
                c.size > 1 -> throw host.error("unhandled branch type ${node.type}", node)
                else -> run(c[0], scope)
            }
        }
    }

    /**
     * `runInfix`: an operator of the table, or one of the three the language itself has: `$` applies its left side, `&` its
     * right side (without the logging the original does), `.` composes. The arithmetic operators of the original are
     * always in the table of `tidal`, so they are not here.
     */
    private fun infix(left: Any?, symbol: String, right: Any?, at: HsNode): Any? {
        host.operator(symbol)?.let { return it.apply(left, right, at) }
        return when (symbol) {
            "$" -> {
                if (!host.isFunction(left)) throw host.typeError("left is not a function", at)
                host.call(left, right, at)
            }
            "&" -> {
                if (!host.isFunction(right)) throw host.typeError("right is not a function", at)
                host.call(right, left, at)
            }
            "." -> host.function { x ->
                if (!host.isFunction(right)) throw host.typeError("right is not a function", at)
                val inner = host.call(right, x, at)
                if (!host.isFunction(left)) throw host.typeError("left is not a function", at)
                host.call(left, inner, at)
            }
            else -> throw host.error("unexpected infix operator $symbol", at)
        }
    }

    /** `curry`: a function that takes one argument per pattern and then evaluates the body with them bound. */
    private fun curry(patterns: List<HsNode>, body: HsNode?, scope: Scope): Any? {
        val variable = patterns.firstOrNull()
        return host.function { arg ->
            val bound = variable ?: throw missing("text", null)
            val local = Scope(scope, bound.text, arg)
            if (patterns.size == 1) run(body, local) else curry(patterns.drop(1), body, local)
        }
    }
}
