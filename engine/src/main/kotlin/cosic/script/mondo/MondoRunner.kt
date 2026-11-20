package cosic.script.mondo

import cosic.pattern.CosicError
import cosic.pattern.JsFn
import cosic.pattern.Undefined
import cosic.pattern.chargeQuery
import cosic.pattern.jsFn
import cosic.pattern.jsStringToNumber
import cosic.pattern.jsTruthy
import cosic.pattern.reportingExhaustion

/*
 * The runner of mondo programs, a port of `MondoRunner` of `packages/mondo/mondo.mjs`. It knows the special forms of the
 * language (`fn`, `match`, `if`, `let`, `def`) and nothing about what the names mean: a leaf and a call are given to an
 * evaluator, which decides (for patterns, see [MondoStrudel]). Values are whatever the evaluator makes of them; `undefined`
 * is [Undefined].
 */

/** The names a program has defined (the object the original passes around); a function closes over a copy of it when it is called. */
internal typealias MondoScope = MutableMap<String, Any?>

/**
 * A leaf of the tree as the evaluator sees it: a number has its value as a `Double`, a quoted string is a `string` without
 * its quotes, everything else keeps the text it was written as.
 */
internal class MondoLeaf(val type: String, val value: Any?, val loc: IntArray?)

/** What a program means: the `evaluator` the original [MondoRunner] is made with. */
internal interface MondoEvaluator {
    /** The value of a leaf. */
    fun leaf(leaf: MondoLeaf, scope: MondoScope): Any?

    /** The value of a list whose children have been evaluated, the first being the function to call. */
    fun call(children: List<Any?>, scope: MondoScope): Any?
}

internal class MondoRunner(private val evaluator: MondoEvaluator) {
    val parser = MondoParser()

    /**
     * Parses and evaluates [code]; [offset] is where it is in the program, for the positions of its leaves (-1 for none).
     * A program that nests or calls itself so deeply that the stack runs out is a [CosicError].
     */
    fun run(code: String, scope: MondoScope = HashMap(), offset: Int = 0): Any? =
        reportingExhaustion { evaluate(parser.parse(code, offset), scope) }

    fun evaluate(ast: MondoNode, scope: MondoScope = HashMap()): Any? {
        if (ast.type != "list") return evaluateLeaf(ast, scope)
        return when (ast.children.firstOrNull()?.value) {
            "fn" -> evaluateLambda(ast, scope)
            "match" -> evaluateMatch(ast, scope)
            "if" -> evaluateIf(ast, scope)
            "let" -> evaluateLet(ast, scope)
            // a definition is a call as well, once the name is defined: its value is whatever the evaluator makes of `def`
            "def" -> evaluateList(evaluateDef(ast, scope), scope)
            else -> evaluateList(ast, scope)
        }
    }

    private fun undefinedRead(property: String): Nothing =
        throw CosicError("Cannot read properties of undefined (reading '$property')")

    /** `(let ((x 3) (y 4)) ...body)` is `((fn (x y) ...body) 3 4)`, the values being evaluated outside. */
    private fun evaluateLet(ast: MondoNode, scope: MondoScope): Any? {
        val definitions = ast.children.getOrNull(1) ?: undefinedRead("children")
        if (definitions.type != "list") undefinedRead("map")
        val pairs = definitions.children.map { pair ->
            if (pair.type != "list") undefinedRead("0")
            pair.children
        }
        val args = pairs.map { it.getOrNull(0) ?: undefinedRead("value") }
        val values = pairs.map { it.getOrNull(1) ?: undefinedRead("type") }
        val body = ast.children.drop(2)
        val lambda = MondoNode.list(arrayListOf(MondoNode.plain("fn"), MondoNode.list(ArrayList(args)), *body.toTypedArray()))
        return evaluate(MondoNode.list(ArrayList<MondoNode>(values.size + 1).also { it.add(lambda); it.addAll(values) }), scope)
    }

    /**
     * `(def name body)` defines `name`; `(def (name a b) body...)` is `(def name (fn (a b) body...))`. The tree the call
     * is made of afterwards is the one returned: the original changes the tree it was given, and an extra child of the
     * function form is thrown away.
     */
    private fun evaluateDef(ast: MondoNode, scope: MondoScope): MondoNode {
        val second = ast.children.getOrNull(1) ?: undefinedRead("type")
        val children: MutableList<MondoNode>
        if (second.type == "list") {
            val args = second.children.drop(1)
            val lambda = MondoNode.list(arrayListOf(MondoNode.plain("fn"), MondoNode.list(ArrayList(args))).also { it.addAll(ast.children.drop(2)) })
            children = arrayListOf(ast.children[0], second.children.getOrNull(0) ?: undefinedRead("value"), lambda)
        } else {
            children = ast.children
        }
        if (children.size != 3) throw CosicError("expected \"def\" to have 3 children, but got ${children.size}")
        val name = children[1].value ?: "undefined"
        scope[name] = evaluate(children[2], scope)
        return if (children === ast.children) ast else MondoNode.list(children, ast.loc)
    }

    /** `(match (p1 e1) (p2 e2) ... (else en))`: the first expression whose predicate is truthy; `undefined` when none is. */
    private fun evaluateMatch(ast: MondoNode, scope: MondoScope): Any? {
        for (i in 1 until ast.children.size) {
            val clause = ast.children[i]
            if (clause.type != "list") throw CosicError("undefined is not iterable (cannot read property Symbol(Symbol.iterator))")
            val predicate = clause.children.getOrNull(0) ?: undefinedRead("value")
            val expression = clause.children.getOrNull(1)
            if (predicate.value == "else") return evaluate(expression ?: undefinedRead("type"), scope)
            if (jsTruthy(evaluate(predicate, scope))) return evaluate(expression ?: undefinedRead("type"), scope)
        }
        return Undefined
    }

    /** `(if predicate consequent alternative)` is `(match (predicate consequent) (else alternative))`; with another number of children it has no value. */
    private fun evaluateIf(ast: MondoNode, scope: MondoScope): Any? {
        if (ast.children.size != 4) return Undefined
        val (_, predicate, consequent, alternative) = ast.children
        val matcher = MondoNode.list(
            arrayListOf(
                MondoNode.plain("match"),
                MondoNode.list(arrayListOf(predicate, consequent)),
                MondoNode.list(arrayListOf(MondoNode.plain("else"), alternative)),
            ),
        )
        return evaluateMatch(matcher, scope)
    }

    /**
     * `(fn (a b) ...body)`: a function whose parameters are `a` and `b`. The scope it runs in is a copy of the scope it was
     * made in, taken when it is called (so a name defined after it was made is visible), with the parameters over it;
     * the value is that of the last expression of the body. A call counts against the budget of the query it is made in
     * (a pattern calls the functions it was made of whenever it is queried), and a function that calls itself without end
     * is stopped by the stack running out.
     */
    private fun evaluateLambda(ast: MondoNode, scope: MondoScope): JsFn {
        val formalArgs = ast.children.getOrNull(1)
        val body = ast.children.drop(2)
        return jsFn(0) { args ->
            chargeQuery(1)
            reportingExhaustion {
                if (formalArgs == null) undefinedRead("children")
                if (formalArgs.type != "list") undefinedRead("map")
                val closure = HashMap(scope)
                formalArgs.children.forEachIndexed { i, arg -> closure[arg.value ?: "undefined"] = args.getOrElse(i) { Undefined } }
                var result: Any? = Undefined
                for (expression in body) result = evaluate(expression, closure)
                result
            }
        }
    }

    /** Evaluates every child but the comments, then lets the evaluator call the first with the rest. */
    private fun evaluateList(ast: MondoNode, scope: MondoScope): Any? {
        val values = ArrayList<Any?>(ast.children.size)
        for (child in ast.children) if (child.type != "comment") values.add(evaluate(child, scope))
        return evaluator.call(values, scope)
    }

    private fun evaluateLeaf(ast: MondoNode, scope: MondoScope): Any? {
        val leaf = when (ast.type) {
            "number" -> MondoLeaf("number", jsStringToNumber(ast.value!!), ast.loc)
            "quotes_double", "quotes_single" -> MondoLeaf("string", ast.value!!.substring(1, ast.value.length - 1), ast.loc)
            else -> MondoLeaf(ast.type, ast.value, ast.loc)
        }
        return evaluator.leaf(leaf, scope)
    }
}
