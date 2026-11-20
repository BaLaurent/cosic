package cosic.script

import cosic.SourceRange

/*
 * The syntax tree of a program: ESTree as acorn 8 produces it for ecmaVersion 2022, reduced to what Cosic runs.
 *
 * - Every node knows where it came from: [Node.start] and [Node.end] are UTF-16 offsets into the source, `end`
 *   exclusive, exactly acorn's `start` and `end`. Line and column are derived from them, see [lineColumn].
 * - Nodes are immutable. A pass that rewrites the tree (the transpiler) builds new nodes; [Node.mapChildren] rebuilds a
 *   node over new children and returns the node itself when nothing changed. Nodes built by a pass should carry the
 *   range of the code they stand for, because errors at run time are reported at the range of the node that failed.
 * - Fields keep acorn's names (`object` is `obj` here and `arguments` stays `arguments`), and so do node types.
 *   Where ESTree has a union, the field is typed [Node] and the comment says which node types occur.
 * - The only mutable state are the annotation slots ([Identifier.resolution], [ScopeOwner.scope]) that the resolver
 *   fills in after the tree is final.
 * - Generators, classes, regular expressions, BigInt, `with`, `debugger`, private names, `super`, `new.target`,
 *   `import`/`export` and `for await` have no node: the parser rejects them with a [cosic.pattern.CosicError].
 */
internal sealed class Node(
    /** Offset of the first character of the node. */
    @JvmField val start: Int,
    /** Offset after the last character of the node. */
    @JvmField val end: Int,
) {
    val range: SourceRange get() = SourceRange(start, end)

    /** The ESTree `type` of the node, e.g. `"CallExpression"`. */
    abstract val type: String

    /** Calls [action] for each child node, in source order. Holes of an array do not count. */
    abstract fun forEachChild(action: (Node) -> Unit)

    /**
     * This node over the children [transform] returns, or this node itself when [transform] returns every child
     * unchanged. [transform] must return a node that is allowed in the position of the child it is given: an
     * expression for an expression, a statement for a statement. It is called once per child, in source order.
     */
    abstract fun mapChildren(transform: (Node) -> Node): Node

    override fun toString(): String = "$type[$start,$end]"
}

/** A node that opens a scope; the resolver stores what it computed for the scope in [scope]. */
internal sealed class ScopeOwner(start: Int, end: Int) : Node(start, end) {
    @JvmField var scope: Any? = null
}

// ---- operators -------------------------------------------------------------------------------------------------

internal enum class DeclKind(val keyword: String) { VAR("var"), LET("let"), CONST("const") }

internal enum class PropertyKind { INIT, GET, SET }

internal enum class UnaryOp(val symbol: String) {
    MINUS("-"), PLUS("+"), NOT("!"), BIT_NOT("~"), TYPEOF("typeof"), VOID("void"), DELETE("delete");

    companion object {
        private val bySymbol = entries.associateBy { it.symbol }
        fun of(symbol: String): UnaryOp = bySymbol.getValue(symbol)
    }
}

internal enum class UpdateOp(val symbol: String) {
    INCREMENT("++"), DECREMENT("--");

    companion object {
        fun of(symbol: String): UpdateOp = if (symbol == "++") INCREMENT else DECREMENT
    }
}

internal enum class BinaryOp(val symbol: String) {
    ADD("+"), SUB("-"), MUL("*"), DIV("/"), MOD("%"), EXP("**"),
    EQ("=="), NE("!="), STRICT_EQ("==="), STRICT_NE("!=="),
    LT("<"), GT(">"), LE("<="), GE(">="),
    SHL("<<"), SHR(">>"), USHR(">>>"),
    BIT_AND("&"), BIT_OR("|"), BIT_XOR("^"),
    IN("in"), INSTANCEOF("instanceof");

    companion object {
        private val bySymbol = entries.associateBy { it.symbol }
        fun of(symbol: String): BinaryOp = bySymbol.getValue(symbol)
    }
}

internal enum class LogicalOp(val symbol: String) {
    AND("&&"), OR("||"), NULLISH("??");

    companion object {
        fun of(symbol: String): LogicalOp = when (symbol) {
            "&&" -> AND
            "||" -> OR
            else -> NULLISH
        }
    }
}

/** An assignment operator. [binary] is the arithmetic operator of `+=` and the like, [logical] that of `&&=`, `||=`, `??=`. */
internal enum class AssignOp(val symbol: String, val binary: BinaryOp? = null, val logical: LogicalOp? = null) {
    ASSIGN("="),
    ADD_ASSIGN("+=", binary = BinaryOp.ADD),
    SUB_ASSIGN("-=", binary = BinaryOp.SUB),
    MUL_ASSIGN("*=", binary = BinaryOp.MUL),
    DIV_ASSIGN("/=", binary = BinaryOp.DIV),
    MOD_ASSIGN("%=", binary = BinaryOp.MOD),
    EXP_ASSIGN("**=", binary = BinaryOp.EXP),
    SHL_ASSIGN("<<=", binary = BinaryOp.SHL),
    SHR_ASSIGN(">>=", binary = BinaryOp.SHR),
    USHR_ASSIGN(">>>=", binary = BinaryOp.USHR),
    BIT_AND_ASSIGN("&=", binary = BinaryOp.BIT_AND),
    BIT_OR_ASSIGN("|=", binary = BinaryOp.BIT_OR),
    BIT_XOR_ASSIGN("^=", binary = BinaryOp.BIT_XOR),
    AND_ASSIGN("&&=", logical = LogicalOp.AND),
    OR_ASSIGN("||=", logical = LogicalOp.OR),
    NULLISH_ASSIGN("??=", logical = LogicalOp.NULLISH);

    companion object {
        private val bySymbol = entries.associateBy { it.symbol }
        fun of(symbol: String): AssignOp = bySymbol.getValue(symbol)
    }
}

// ---- program and statements ----------------------------------------------------------------------------------------

/** The whole code. Always starts at 0 and ends at the length of the source. [body] holds statements. */
internal class Program(start: Int, end: Int, @JvmField val body: List<Node>) : ScopeOwner(start, end) {
    override val type get() = "Program"
    override fun forEachChild(action: (Node) -> Unit) = body.forEach(action)
    override fun mapChildren(transform: (Node) -> Node): Node {
        val b = body.mapNodes(transform)
        return if (b === body) this else Program(start, end, b)
    }
}

/** `expression;` */
internal class ExpressionStatement(start: Int, end: Int, @JvmField val expression: Node) : Node(start, end) {
    override val type get() = "ExpressionStatement"
    override fun forEachChild(action: (Node) -> Unit) = action(expression)
    override fun mapChildren(transform: (Node) -> Node): Node {
        val e = transform(expression)
        return if (e === expression) this else ExpressionStatement(start, end, e)
    }
}

/** `{ ... }`; [body] holds statements. */
internal class BlockStatement(start: Int, end: Int, @JvmField val body: List<Node>) : ScopeOwner(start, end) {
    override val type get() = "BlockStatement"
    override fun forEachChild(action: (Node) -> Unit) = body.forEach(action)
    override fun mapChildren(transform: (Node) -> Node): Node {
        val b = body.mapNodes(transform)
        return if (b === body) this else BlockStatement(start, end, b)
    }
}

/** `;` */
internal class EmptyStatement(start: Int, end: Int) : Node(start, end) {
    override val type get() = "EmptyStatement"
    override fun forEachChild(action: (Node) -> Unit) = Unit
    override fun mapChildren(transform: (Node) -> Node): Node = this
}

/** `var a = 1, b`; also the `init` of a `for` and the `left` of `for-in`/`for-of`. */
internal class VariableDeclaration(
    start: Int,
    end: Int,
    @JvmField val kind: DeclKind,
    @JvmField val declarations: List<VariableDeclarator>,
) : Node(start, end) {
    override val type get() = "VariableDeclaration"
    override fun forEachChild(action: (Node) -> Unit) = declarations.forEach(action)
    override fun mapChildren(transform: (Node) -> Node): Node {
        val d = declarations.mapNodes(transform)
        return if (d === declarations) this else VariableDeclaration(start, end, kind, d)
    }
}

/** `id = init`. [id] is an Identifier, ObjectPattern or ArrayPattern; [init] is null for `let a;`. */
internal class VariableDeclarator(start: Int, end: Int, @JvmField val id: Node, @JvmField val init: Node?) : Node(start, end) {
    override val type get() = "VariableDeclarator"
    override fun forEachChild(action: (Node) -> Unit) {
        action(id)
        init?.let(action)
    }
    override fun mapChildren(transform: (Node) -> Node): Node {
        val i = transform(id)
        val n = init?.let(transform)
        return if (i === id && n === init) this else VariableDeclarator(start, end, i, n)
    }
}

/**
 * The common part of the three kinds of function. [params] holds patterns: an Identifier, ObjectPattern,
 * ArrayPattern, AssignmentPattern (a default) or RestElement (only last). [body] is a BlockStatement, or for an arrow
 * function with an expression body the expression ([expression] tells which).
 */
internal sealed class FunctionNode(
    start: Int,
    end: Int,
    @JvmField val id: Identifier?,
    @JvmField val params: List<Node>,
    @JvmField val body: Node,
    @JvmField val async: Boolean,
) : ScopeOwner(start, end) {
    /** True for an arrow function whose body is an expression. */
    val expression: Boolean get() = this is ArrowFunctionExpression && body !is BlockStatement

    /** The `length` of the function in JavaScript: the parameters before the first default or rest parameter. */
    val length: Int
        get() {
            var n = 0
            for (p in params) {
                if (p is AssignmentPattern || p is RestElement) break
                n++
            }
            return n
        }

    final override fun forEachChild(action: (Node) -> Unit) {
        id?.let(action)
        params.forEach(action)
        action(body)
    }
}

/** `function f(params) { body }`; [id] is never null, [body] is a BlockStatement. */
internal class FunctionDeclaration(
    start: Int,
    end: Int,
    id: Identifier,
    params: List<Node>,
    body: BlockStatement,
    async: Boolean,
) : FunctionNode(start, end, id, params, body, async) {
    override val type get() = "FunctionDeclaration"
    override fun mapChildren(transform: (Node) -> Node): Node {
        val i = transform(id!!) as Identifier
        val p = params.mapNodes(transform)
        val b = transform(body) as BlockStatement
        return if (i === id && p === params && b === body) this else FunctionDeclaration(start, end, i, p, b, async)
    }
}

/**
 * `function (params) { body }` and the methods of an object literal (`{ f() {} }`, whose range starts at the `(`).
 * [id] is null for an anonymous function; [body] is a BlockStatement.
 */
internal class FunctionExpression(
    start: Int,
    end: Int,
    id: Identifier?,
    params: List<Node>,
    body: BlockStatement,
    async: Boolean,
) : FunctionNode(start, end, id, params, body, async) {
    override val type get() = "FunctionExpression"
    override fun mapChildren(transform: (Node) -> Node): Node {
        val i = id?.let { transform(it) as Identifier }
        val p = params.mapNodes(transform)
        val b = transform(body) as BlockStatement
        return if (i === id && p === params && b === body) this else FunctionExpression(start, end, i, p, b, async)
    }
}

/** `(params) => body` and `x => body`; [id] is always null. */
internal class ArrowFunctionExpression(
    start: Int,
    end: Int,
    params: List<Node>,
    body: Node,
    async: Boolean,
) : FunctionNode(start, end, null, params, body, async) {
    override val type get() = "ArrowFunctionExpression"
    override fun mapChildren(transform: (Node) -> Node): Node {
        val p = params.mapNodes(transform)
        val b = transform(body)
        return if (p === params && b === body) this else ArrowFunctionExpression(start, end, p, b, async)
    }
}

internal class ReturnStatement(start: Int, end: Int, @JvmField val argument: Node?) : Node(start, end) {
    override val type get() = "ReturnStatement"
    override fun forEachChild(action: (Node) -> Unit) {
        argument?.let(action)
    }
    override fun mapChildren(transform: (Node) -> Node): Node {
        val a = argument?.let(transform)
        return if (a === argument) this else ReturnStatement(start, end, a)
    }
}

internal class IfStatement(
    start: Int,
    end: Int,
    @JvmField val test: Node,
    @JvmField val consequent: Node,
    @JvmField val alternate: Node?,
) : Node(start, end) {
    override val type get() = "IfStatement"
    override fun forEachChild(action: (Node) -> Unit) {
        action(test)
        action(consequent)
        alternate?.let(action)
    }
    override fun mapChildren(transform: (Node) -> Node): Node {
        val t = transform(test)
        val c = transform(consequent)
        val a = alternate?.let(transform)
        return if (t === test && c === consequent && a === alternate) this else IfStatement(start, end, t, c, a)
    }
}

/** `for (init; test; update) body`; [init] is a VariableDeclaration or an expression. */
internal class ForStatement(
    start: Int,
    end: Int,
    @JvmField val init: Node?,
    @JvmField val test: Node?,
    @JvmField val update: Node?,
    @JvmField val body: Node,
) : ScopeOwner(start, end) {
    override val type get() = "ForStatement"
    override fun forEachChild(action: (Node) -> Unit) {
        init?.let(action)
        test?.let(action)
        update?.let(action)
        action(body)
    }
    override fun mapChildren(transform: (Node) -> Node): Node {
        val i = init?.let(transform)
        val t = test?.let(transform)
        val u = update?.let(transform)
        val b = transform(body)
        return if (i === init && t === test && u === update && b === body) this else ForStatement(start, end, i, t, u, b)
    }
}

/** `for (left in right) body`; [left] is a VariableDeclaration with one declarator without init, or a pattern/member target. */
internal class ForInStatement(
    start: Int,
    end: Int,
    @JvmField val left: Node,
    @JvmField val right: Node,
    @JvmField val body: Node,
) : ScopeOwner(start, end) {
    override val type get() = "ForInStatement"
    override fun forEachChild(action: (Node) -> Unit) {
        action(left)
        action(right)
        action(body)
    }
    override fun mapChildren(transform: (Node) -> Node): Node {
        val l = transform(left)
        val r = transform(right)
        val b = transform(body)
        return if (l === left && r === right && b === body) this else ForInStatement(start, end, l, r, b)
    }
}

/** `for (left of right) body`, shaped like [ForInStatement]. */
internal class ForOfStatement(
    start: Int,
    end: Int,
    @JvmField val left: Node,
    @JvmField val right: Node,
    @JvmField val body: Node,
) : ScopeOwner(start, end) {
    override val type get() = "ForOfStatement"
    override fun forEachChild(action: (Node) -> Unit) {
        action(left)
        action(right)
        action(body)
    }
    override fun mapChildren(transform: (Node) -> Node): Node {
        val l = transform(left)
        val r = transform(right)
        val b = transform(body)
        return if (l === left && r === right && b === body) this else ForOfStatement(start, end, l, r, b)
    }
}

internal class WhileStatement(start: Int, end: Int, @JvmField val test: Node, @JvmField val body: Node) : Node(start, end) {
    override val type get() = "WhileStatement"
    override fun forEachChild(action: (Node) -> Unit) {
        action(test)
        action(body)
    }
    override fun mapChildren(transform: (Node) -> Node): Node {
        val t = transform(test)
        val b = transform(body)
        return if (t === test && b === body) this else WhileStatement(start, end, t, b)
    }
}

internal class DoWhileStatement(start: Int, end: Int, @JvmField val body: Node, @JvmField val test: Node) : Node(start, end) {
    override val type get() = "DoWhileStatement"
    override fun forEachChild(action: (Node) -> Unit) {
        action(body)
        action(test)
    }
    override fun mapChildren(transform: (Node) -> Node): Node {
        val b = transform(body)
        val t = transform(test)
        return if (b === body && t === test) this else DoWhileStatement(start, end, b, t)
    }
}

/** `break` or `break label`. */
internal class BreakStatement(start: Int, end: Int, @JvmField val label: Identifier?) : Node(start, end) {
    override val type get() = "BreakStatement"
    override fun forEachChild(action: (Node) -> Unit) {
        label?.let(action)
    }
    override fun mapChildren(transform: (Node) -> Node): Node {
        val l = label?.let { transform(it) as Identifier }
        return if (l === label) this else BreakStatement(start, end, l)
    }
}

/** `continue` or `continue label`. */
internal class ContinueStatement(start: Int, end: Int, @JvmField val label: Identifier?) : Node(start, end) {
    override val type get() = "ContinueStatement"
    override fun forEachChild(action: (Node) -> Unit) {
        label?.let(action)
    }
    override fun mapChildren(transform: (Node) -> Node): Node {
        val l = label?.let { transform(it) as Identifier }
        return if (l === label) this else ContinueStatement(start, end, l)
    }
}

internal class ThrowStatement(start: Int, end: Int, @JvmField val argument: Node) : Node(start, end) {
    override val type get() = "ThrowStatement"
    override fun forEachChild(action: (Node) -> Unit) = action(argument)
    override fun mapChildren(transform: (Node) -> Node): Node {
        val a = transform(argument)
        return if (a === argument) this else ThrowStatement(start, end, a)
    }
}

internal class TryStatement(
    start: Int,
    end: Int,
    @JvmField val block: BlockStatement,
    @JvmField val handler: CatchClause?,
    @JvmField val finalizer: BlockStatement?,
) : Node(start, end) {
    override val type get() = "TryStatement"
    override fun forEachChild(action: (Node) -> Unit) {
        action(block)
        handler?.let(action)
        finalizer?.let(action)
    }
    override fun mapChildren(transform: (Node) -> Node): Node {
        val b = transform(block) as BlockStatement
        val h = handler?.let { transform(it) as CatchClause }
        val f = finalizer?.let { transform(it) as BlockStatement }
        return if (b === block && h === handler && f === finalizer) this else TryStatement(start, end, b, h, f)
    }
}

/** `catch (param) body`; [param] is null for `catch { }`, else an Identifier or a destructuring pattern. */
internal class CatchClause(start: Int, end: Int, @JvmField val param: Node?, @JvmField val body: BlockStatement) : ScopeOwner(start, end) {
    override val type get() = "CatchClause"
    override fun forEachChild(action: (Node) -> Unit) {
        param?.let(action)
        action(body)
    }
    override fun mapChildren(transform: (Node) -> Node): Node {
        val p = param?.let(transform)
        val b = transform(body) as BlockStatement
        return if (p === param && b === body) this else CatchClause(start, end, p, b)
    }
}

internal class SwitchStatement(
    start: Int,
    end: Int,
    @JvmField val discriminant: Node,
    @JvmField val cases: List<SwitchCase>,
) : ScopeOwner(start, end) {
    override val type get() = "SwitchStatement"
    override fun forEachChild(action: (Node) -> Unit) {
        action(discriminant)
        cases.forEach(action)
    }
    override fun mapChildren(transform: (Node) -> Node): Node {
        val d = transform(discriminant)
        val c = cases.mapNodes(transform)
        return if (d === discriminant && c === cases) this else SwitchStatement(start, end, d, c)
    }
}

/** `case test: consequent...`; [test] is null for `default:`. [consequent] holds statements. */
internal class SwitchCase(start: Int, end: Int, @JvmField val test: Node?, @JvmField val consequent: List<Node>) : Node(start, end) {
    override val type get() = "SwitchCase"
    override fun forEachChild(action: (Node) -> Unit) {
        test?.let(action)
        consequent.forEach(action)
    }
    override fun mapChildren(transform: (Node) -> Node): Node {
        val t = test?.let(transform)
        val c = consequent.mapNodes(transform)
        return if (t === test && c === consequent) this else SwitchCase(start, end, t, c)
    }
}

/**
 * `label: body`. A Strudel program line `$: s("bd")` is a LabeledStatement whose [label] is the identifier `$`: the
 * parser does nothing special for labels, the transpiler turns them into `.p('<label>')`.
 */
internal class LabeledStatement(start: Int, end: Int, @JvmField val label: Identifier, @JvmField val body: Node) : Node(start, end) {
    override val type get() = "LabeledStatement"
    override fun forEachChild(action: (Node) -> Unit) {
        action(label)
        action(body)
    }
    override fun mapChildren(transform: (Node) -> Node): Node {
        val l = transform(label) as Identifier
        val b = transform(body)
        return if (l === label && b === body) this else LabeledStatement(start, end, l, b)
    }
}

// ---- expressions -----------------------------------------------------------------------------------------------

/** A name. The resolver stores what it resolved the name to in [resolution]. */
internal class Identifier(start: Int, end: Int, @JvmField val name: String) : Node(start, end) {
    @JvmField var resolution: Any? = null
    override val type get() = "Identifier"
    override fun forEachChild(action: (Node) -> Unit) = Unit
    override fun mapChildren(transform: (Node) -> Node): Node = this
}

/**
 * A number, string, `true`, `false` or `null`. [value] is a Double, a String (cooked), a Boolean or null (JavaScript
 * `null`). [raw] is the source text, so a double-quoted string is recognised by `raw[0] == '"'`.
 */
internal class Literal(start: Int, end: Int, @JvmField val value: Any?, @JvmField val raw: String) : Node(start, end) {
    override val type get() = "Literal"
    override fun forEachChild(action: (Node) -> Unit) = Unit
    override fun mapChildren(transform: (Node) -> Node): Node = this
}

/** `` `a${b}c` ``: always one more [quasis] than [expressions]. */
internal class TemplateLiteral(
    start: Int,
    end: Int,
    @JvmField val quasis: List<TemplateElement>,
    @JvmField val expressions: List<Node>,
) : Node(start, end) {
    override val type get() = "TemplateLiteral"

    /** The children in source order: quasi, expression, quasi, ... */
    override fun forEachChild(action: (Node) -> Unit) {
        for (i in quasis.indices) {
            action(quasis[i])
            if (i < expressions.size) action(expressions[i])
        }
    }
    override fun mapChildren(transform: (Node) -> Node): Node {
        var changed = false
        val q = ArrayList<TemplateElement>(quasis.size)
        val e = ArrayList<Node>(expressions.size)
        for (i in quasis.indices) {
            val quasi = transform(quasis[i]) as TemplateElement
            changed = changed || quasi !== quasis[i]
            q.add(quasi)
            if (i < expressions.size) {
                val expression = transform(expressions[i])
                changed = changed || expression !== expressions[i]
                e.add(expression)
            }
        }
        return if (changed) TemplateLiteral(start, end, q, e) else this
    }
}

/**
 * One literal piece of a template. The range covers the piece without the backtick or `${` before it and the
 * `${` or backtick after it. [cooked] is null for a piece with an invalid escape (only allowed in a tagged template);
 * [raw] has its line breaks normalised to `\n`. [tail] marks the last piece.
 */
internal class TemplateElement(
    start: Int,
    end: Int,
    @JvmField val cooked: String?,
    @JvmField val raw: String,
    @JvmField val tail: Boolean,
) : Node(start, end) {
    override val type get() = "TemplateElement"
    override fun forEachChild(action: (Node) -> Unit) = Unit
    override fun mapChildren(transform: (Node) -> Node): Node = this
}

internal class TaggedTemplateExpression(start: Int, end: Int, @JvmField val tag: Node, @JvmField val quasi: TemplateLiteral) : Node(start, end) {
    override val type get() = "TaggedTemplateExpression"
    override fun forEachChild(action: (Node) -> Unit) {
        action(tag)
        action(quasi)
    }
    override fun mapChildren(transform: (Node) -> Node): Node {
        val t = transform(tag)
        val q = transform(quasi) as TemplateLiteral
        return if (t === tag && q === quasi) this else TaggedTemplateExpression(start, end, t, q)
    }
}

/** `[a, , ...b]`; [elements] holds expressions and SpreadElements, and null for a hole. */
internal class ArrayExpression(start: Int, end: Int, @JvmField val elements: List<Node?>) : Node(start, end) {
    override val type get() = "ArrayExpression"
    override fun forEachChild(action: (Node) -> Unit) {
        for (e in elements) if (e != null) action(e)
    }
    override fun mapChildren(transform: (Node) -> Node): Node {
        val e = elements.mapNullableNodes(transform)
        return if (e === elements) this else ArrayExpression(start, end, e)
    }
}

/** `{ a: 1, b, ...c }`; [properties] holds Property and SpreadElement nodes. */
internal class ObjectExpression(start: Int, end: Int, @JvmField val properties: List<Node>) : Node(start, end) {
    override val type get() = "ObjectExpression"
    override fun forEachChild(action: (Node) -> Unit) = properties.forEach(action)
    override fun mapChildren(transform: (Node) -> Node): Node {
        val p = properties.mapNodes(transform)
        return if (p === properties) this else ObjectExpression(start, end, p)
    }
}

/**
 * A member of an object literal or object pattern. [key] is an Identifier (also for a keyword such as `if`), a
 * Literal, or any expression when [computed]. [value] is the value expression, or the pattern in an object pattern.
 * A [shorthand] property has the same range for key and value (`{ a }`, or `{ a = 1 }` in a pattern, where the value
 * is an AssignmentPattern); a [method] and a getter or setter have a FunctionExpression value whose range starts at
 * its `(`.
 */
internal class Property(
    start: Int,
    end: Int,
    @JvmField val key: Node,
    @JvmField val value: Node,
    @JvmField val kind: PropertyKind,
    @JvmField val computed: Boolean,
    @JvmField val shorthand: Boolean,
    @JvmField val method: Boolean,
) : Node(start, end) {
    override val type get() = "Property"
    override fun forEachChild(action: (Node) -> Unit) {
        action(key)
        action(value)
    }
    override fun mapChildren(transform: (Node) -> Node): Node {
        val k = transform(key)
        val v = transform(value)
        return if (k === key && v === value) this else Property(start, end, k, v, kind, computed, shorthand, method)
    }
}

internal class UnaryExpression(start: Int, end: Int, @JvmField val operator: UnaryOp, @JvmField val argument: Node) : Node(start, end) {
    override val type get() = "UnaryExpression"
    override fun forEachChild(action: (Node) -> Unit) = action(argument)
    override fun mapChildren(transform: (Node) -> Node): Node {
        val a = transform(argument)
        return if (a === argument) this else UnaryExpression(start, end, operator, a)
    }
}

/** `++a`, `a--`. [argument] is an Identifier or a MemberExpression. */
internal class UpdateExpression(
    start: Int,
    end: Int,
    @JvmField val operator: UpdateOp,
    @JvmField val prefix: Boolean,
    @JvmField val argument: Node,
) : Node(start, end) {
    override val type get() = "UpdateExpression"
    override fun forEachChild(action: (Node) -> Unit) = action(argument)
    override fun mapChildren(transform: (Node) -> Node): Node {
        val a = transform(argument)
        return if (a === argument) this else UpdateExpression(start, end, operator, prefix, a)
    }
}

internal class BinaryExpression(
    start: Int,
    end: Int,
    @JvmField val operator: BinaryOp,
    @JvmField val left: Node,
    @JvmField val right: Node,
) : Node(start, end) {
    override val type get() = "BinaryExpression"
    override fun forEachChild(action: (Node) -> Unit) {
        action(left)
        action(right)
    }
    override fun mapChildren(transform: (Node) -> Node): Node {
        val l = transform(left)
        val r = transform(right)
        return if (l === left && r === right) this else BinaryExpression(start, end, operator, l, r)
    }
}

/** `a && b`, `a || b`, `a ?? b`. */
internal class LogicalExpression(
    start: Int,
    end: Int,
    @JvmField val operator: LogicalOp,
    @JvmField val left: Node,
    @JvmField val right: Node,
) : Node(start, end) {
    override val type get() = "LogicalExpression"
    override fun forEachChild(action: (Node) -> Unit) {
        action(left)
        action(right)
    }
    override fun mapChildren(transform: (Node) -> Node): Node {
        val l = transform(left)
        val r = transform(right)
        return if (l === left && r === right) this else LogicalExpression(start, end, operator, l, r)
    }
}

/** `left = right` and the compound forms. [left] is an Identifier or MemberExpression, or a pattern for `=` only. */
internal class AssignmentExpression(
    start: Int,
    end: Int,
    @JvmField val operator: AssignOp,
    @JvmField val left: Node,
    @JvmField val right: Node,
) : Node(start, end) {
    override val type get() = "AssignmentExpression"
    override fun forEachChild(action: (Node) -> Unit) {
        action(left)
        action(right)
    }
    override fun mapChildren(transform: (Node) -> Node): Node {
        val l = transform(left)
        val r = transform(right)
        return if (l === left && r === right) this else AssignmentExpression(start, end, operator, l, r)
    }
}

internal class ConditionalExpression(
    start: Int,
    end: Int,
    @JvmField val test: Node,
    @JvmField val consequent: Node,
    @JvmField val alternate: Node,
) : Node(start, end) {
    override val type get() = "ConditionalExpression"
    override fun forEachChild(action: (Node) -> Unit) {
        action(test)
        action(consequent)
        action(alternate)
    }
    override fun mapChildren(transform: (Node) -> Node): Node {
        val t = transform(test)
        val c = transform(consequent)
        val a = transform(alternate)
        return if (t === test && c === consequent && a === alternate) this else ConditionalExpression(start, end, t, c, a)
    }
}

/** `callee(arguments)`, or `callee?.(arguments)` when [optional]. [arguments] holds expressions and SpreadElements. */
internal class CallExpression(
    start: Int,
    end: Int,
    @JvmField val callee: Node,
    @JvmField val arguments: List<Node>,
    @JvmField val optional: Boolean,
) : Node(start, end) {
    override val type get() = "CallExpression"
    override fun forEachChild(action: (Node) -> Unit) {
        action(callee)
        arguments.forEach(action)
    }
    override fun mapChildren(transform: (Node) -> Node): Node {
        val c = transform(callee)
        val a = arguments.mapNodes(transform)
        return if (c === callee && a === arguments) this else CallExpression(start, end, c, a, optional)
    }
}

/** `new callee(arguments)`; without parentheses [arguments] is empty. */
internal class NewExpression(start: Int, end: Int, @JvmField val callee: Node, @JvmField val arguments: List<Node>) : Node(start, end) {
    override val type get() = "NewExpression"
    override fun forEachChild(action: (Node) -> Unit) {
        action(callee)
        arguments.forEach(action)
    }
    override fun mapChildren(transform: (Node) -> Node): Node {
        val c = transform(callee)
        val a = arguments.mapNodes(transform)
        return if (c === callee && a === arguments) this else NewExpression(start, end, c, a)
    }
}

/**
 * `obj.property`, or `obj[property]` when [computed], or `obj?.property` when [optional]. For a dot access the
 * [property] is an Identifier (a keyword such as `if` is allowed).
 */
internal class MemberExpression(
    start: Int,
    end: Int,
    @JvmField val obj: Node,
    @JvmField val property: Node,
    @JvmField val computed: Boolean,
    @JvmField val optional: Boolean,
) : Node(start, end) {
    override val type get() = "MemberExpression"
    override fun forEachChild(action: (Node) -> Unit) {
        action(obj)
        action(property)
    }
    override fun mapChildren(transform: (Node) -> Node): Node {
        val o = transform(obj)
        val p = transform(property)
        return if (o === obj && p === property) this else MemberExpression(start, end, o, p, computed, optional)
    }
}

/**
 * The whole of an optional chain: `a?.b.c(d)` is a ChainExpression over the call; the `?.` links inside have
 * `optional = true`. When a link finds null or undefined, the whole ChainExpression is undefined.
 */
internal class ChainExpression(start: Int, end: Int, @JvmField val expression: Node) : Node(start, end) {
    override val type get() = "ChainExpression"
    override fun forEachChild(action: (Node) -> Unit) = action(expression)
    override fun mapChildren(transform: (Node) -> Node): Node {
        val e = transform(expression)
        return if (e === expression) this else ChainExpression(start, end, e)
    }
}

/** `a, b, c`: at least two expressions. */
internal class SequenceExpression(start: Int, end: Int, @JvmField val expressions: List<Node>) : Node(start, end) {
    override val type get() = "SequenceExpression"
    override fun forEachChild(action: (Node) -> Unit) = expressions.forEach(action)
    override fun mapChildren(transform: (Node) -> Node): Node {
        val e = expressions.mapNodes(transform)
        return if (e === expressions) this else SequenceExpression(start, end, e)
    }
}

/** `...argument` in an array literal, a call, `new`, or an object literal. */
internal class SpreadElement(start: Int, end: Int, @JvmField val argument: Node) : Node(start, end) {
    override val type get() = "SpreadElement"
    override fun forEachChild(action: (Node) -> Unit) = action(argument)
    override fun mapChildren(transform: (Node) -> Node): Node {
        val a = transform(argument)
        return if (a === argument) this else SpreadElement(start, end, a)
    }
}

internal class AwaitExpression(start: Int, end: Int, @JvmField val argument: Node) : Node(start, end) {
    override val type get() = "AwaitExpression"
    override fun forEachChild(action: (Node) -> Unit) = action(argument)
    override fun mapChildren(transform: (Node) -> Node): Node {
        val a = transform(argument)
        return if (a === argument) this else AwaitExpression(start, end, a)
    }
}

internal class ThisExpression(start: Int, end: Int) : Node(start, end) {
    override val type get() = "ThisExpression"
    override fun forEachChild(action: (Node) -> Unit) = Unit
    override fun mapChildren(transform: (Node) -> Node): Node = this
}

// ---- patterns --------------------------------------------------------------------------------------------------

/** `{ a, b: c, ...d }` as a binding or assignment target; [properties] holds Property and RestElement nodes. */
internal class ObjectPattern(start: Int, end: Int, @JvmField val properties: List<Node>) : Node(start, end) {
    override val type get() = "ObjectPattern"
    override fun forEachChild(action: (Node) -> Unit) = properties.forEach(action)
    override fun mapChildren(transform: (Node) -> Node): Node {
        val p = properties.mapNodes(transform)
        return if (p === properties) this else ObjectPattern(start, end, p)
    }
}

/** `[a, , b, ...c]` as a binding or assignment target; [elements] holds patterns and null for a hole. */
internal class ArrayPattern(start: Int, end: Int, @JvmField val elements: List<Node?>) : Node(start, end) {
    override val type get() = "ArrayPattern"
    override fun forEachChild(action: (Node) -> Unit) {
        for (e in elements) if (e != null) action(e)
    }
    override fun mapChildren(transform: (Node) -> Node): Node {
        val e = elements.mapNullableNodes(transform)
        return if (e === elements) this else ArrayPattern(start, end, e)
    }
}

/** `left = right`: a pattern with a default value, in a parameter list or inside another pattern. */
internal class AssignmentPattern(start: Int, end: Int, @JvmField val left: Node, @JvmField val right: Node) : Node(start, end) {
    override val type get() = "AssignmentPattern"
    override fun forEachChild(action: (Node) -> Unit) {
        action(left)
        action(right)
    }
    override fun mapChildren(transform: (Node) -> Node): Node {
        val l = transform(left)
        val r = transform(right)
        return if (l === left && r === right) this else AssignmentPattern(start, end, l, r)
    }
}

/** `...argument` as the last element of a parameter list, array pattern or object pattern. */
internal class RestElement(start: Int, end: Int, @JvmField val argument: Node) : Node(start, end) {
    override val type get() = "RestElement"
    override fun forEachChild(action: (Node) -> Unit) = action(argument)
    override fun mapChildren(transform: (Node) -> Node): Node {
        val a = transform(argument)
        return if (a === argument) this else RestElement(start, end, a)
    }
}

// ---- helpers -----------------------------------------------------------------------------------------------------

private fun <T : Node> List<T>.mapNodes(transform: (Node) -> Node): List<T> {
    var result: ArrayList<T>? = null
    for (i in indices) {
        val old = this[i]
        @Suppress("UNCHECKED_CAST")
        val new = transform(old) as T
        if (result == null && new !== old) {
            result = ArrayList(size)
            for (j in 0 until i) result.add(this[j])
        }
        result?.add(new)
    }
    return result ?: this
}

private fun List<Node?>.mapNullableNodes(transform: (Node) -> Node): List<Node?> {
    var result: ArrayList<Node?>? = null
    for (i in indices) {
        val old = this[i]
        val new = if (old == null) null else transform(old)
        if (result == null && new !== old) {
            result = ArrayList(size)
            for (j in 0 until i) result.add(this[j])
        }
        result?.add(new)
    }
    return result ?: this
}

/** A position the way acorn reports it: [line] counts from 1, [column] from 0. */
internal class LineColumn(val line: Int, val column: Int)

/** The line and column of [offset] in [source]; lines end at `\n`, `\r`, `\r\n`, U+2028 and U+2029. */
internal fun lineColumn(source: String, offset: Int): LineColumn {
    val end = offset.coerceIn(0, source.length)
    var line = 1
    var lineStart = 0
    var i = 0
    while (i < end) {
        val c = source[i]
        if (c == '\n' || c == '\u2028' || c == '\u2029' || c == '\r') {
            if (c == '\r' && i + 1 < end && source[i + 1] == '\n') i++
            line++
            lineStart = i + 1
        }
        i++
    }
    return LineColumn(line, end - lineStart)
}
