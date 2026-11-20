package cosic.script.next

import cosic.pattern.Undefined

/*
 * The resolver: one pass over a finished tree (after the transpiler) that decides where every name lives, so that the
 * interpreter never looks a name up by string for a local variable.
 *
 * Runtime scopes are [Frame]s: an array of slots and a link to the frame of the enclosing scope. A scope gets a frame
 * only when it declares something; a block without declarations costs nothing at run time. Every function call gets
 * one (parameters, `var`s, function declarations and the `let`/`const` of the function body share it, as the parser
 * models), and so does the program. Each `Identifier` that is a reference or a binding gets a [Ref] `(hops, slot)`:
 * how many frames to go up, and the slot there. A name that no scope declares is a [GlobalRef] (a property of the
 * global object), or a [ConstRef] for `undefined`, `NaN` and `Infinity`.
 *
 * What the resolver leaves on the tree:
 * - `Identifier.resolution`: a [Ref], [GlobalRef] or [ConstRef];
 * - `Program.scope`: the [ScopeInfo] of the program frame;
 * - `FunctionNode.scope`: a [FunctionInfo];
 * - `BlockStatement`, `ForStatement`, `ForInStatement`, `ForOfStatement`, `SwitchStatement`, `CatchClause`: a
 *   [ScopeInfo], or null when the scope declares nothing and so has no frame. The body block of a function and of a
 *   `catch` clause is part of the scope of the function or clause and has no scope of its own.
 *
 * `let` and `const` slots start as [Tdz]; reading one before its declaration has run is a `ReferenceError`. Function
 * declarations are instantiated when their scope is entered. Block-level functions are block scoped (strict mode).
 */

/** What a frame is made of: [template] is a new frame's slots (`undefined`, or [Tdz]), [functions] are made when the scope is entered. */
internal class ScopeInfo(
    @JvmField val template: Array<Any?>,
    @JvmField val functions: Array<HoistedFunction>,
    /** For a `for (let ...)` loop whose body, test or update makes a closure: each iteration gets a copy of the frame. */
    @JvmField val perIteration: Boolean,
)

/** A function declaration to instantiate into [slot] when its scope is entered. */
internal class HoistedFunction(@JvmField val slot: Int, @JvmField val node: FunctionDeclaration)

/**
 * What a function call needs besides its [scope]: whether the parameters are plain names in slots `0 until n` (then
 * binding them is a copy), the slots of `arguments` and of the function's own name (a named function expression sees
 * itself), or -1, and the program text that `String(fn)` reads.
 */
internal class FunctionInfo(
    @JvmField val scope: ScopeInfo,
    @JvmField val simpleParams: Boolean,
    @JvmField val argumentsSlot: Int,
    @JvmField val selfSlot: Int,
    @JvmField val source: String,
)

/** The marker that a `let`/`const` slot holds until its declaration has run. */
internal object Tdz

internal const val REF_TDZ = 1
internal const val REF_CONST = 2

/** A name that lives in a frame: [hops] frames up from the current one, in [slot]. [flags] are [REF_TDZ] (check for [Tdz]) and [REF_CONST] (not assignable). */
internal class Ref(@JvmField val hops: Int, @JvmField val slot: Int, @JvmField val flags: Int)

/** A name no scope declares: a property of the global object. */
internal object GlobalRef

/** `undefined`, `NaN` and `Infinity` when nothing shadows them: the value itself. */
internal class ConstRef(@JvmField val value: Any?)

private val UNDEFINED_REF = ConstRef(Undefined)
private val NAN_REF = ConstRef(Double.NaN)
private val INFINITY_REF = ConstRef(Double.POSITIVE_INFINITY)

internal class Resolver(private val source: String) {
    private class Binding(@JvmField val slot: Int, @JvmField val flags: Int)

    private class Scope(@JvmField val parent: Scope?, @JvmField val isFunction: Boolean, @JvmField val isArrow: Boolean = false) {
        val names = LinkedHashMap<String, Binding>()
        val tdz = ArrayList<Int>()
        val functions = ArrayList<HoistedFunction>()
        var argumentsSlot = -1
        var selfSlot = -1

        fun declare(name: String, flags: Int): Int {
            val existing = names[name]
            if (existing != null) return existing.slot
            val slot = names.size
            names[name] = Binding(slot, flags)
            if (flags and REF_TDZ != 0) tdz.add(slot)
            return slot
        }
    }

    private var scope: Scope? = null
    private var closures = 0

    /** Resolves a whole program. The program is run as the body of a function: it has a frame of its own, and a top-level `return` is allowed. */
    fun resolveProgram(program: Program) {
        val s = Scope(null, true)
        scope = s
        hoistDeclarations(program.body, s, functionLevel = true)
        for (statement in program.body) visit(statement)
        program.scope = finish(s, false)
        scope = null
    }

    // ---- declarations ------------------------------------------------------------------------------------------------

    private fun declarationFlags(kind: DeclKind): Int = when (kind) {
        DeclKind.VAR -> 0
        DeclKind.LET -> REF_TDZ
        DeclKind.CONST -> REF_TDZ or REF_CONST
    }

    private fun declarePatternNames(p: Node?, s: Scope, flags: Int) {
        when (p) {
            is Identifier -> s.declare(p.name, flags)
            is AssignmentPattern -> declarePatternNames(p.left, s, flags)
            is RestElement -> declarePatternNames(p.argument, s, flags)
            is ArrayPattern -> for (e in p.elements) declarePatternNames(e, s, flags)
            is ObjectPattern -> for (property in p.properties) {
                if (property is Property) declarePatternNames(property.value, s, flags) else declarePatternNames(property, s, flags)
            }
            else -> Unit
        }
    }

    private fun declareDeclarators(declaration: VariableDeclaration, s: Scope) {
        val flags = declarationFlags(declaration.kind)
        for (d in declaration.declarations) declarePatternNames(d.id, s, flags)
    }

    /**
     * Declares what the statements of one scope declare. At function level that is every `var` in them, however
     * deeply nested in blocks and loops, and the functions declared directly in them (which behave like `var`s: they
     * exist from the start). In a block it is the `let`/`const` and function declarations directly in it.
     */
    private fun hoistDeclarations(statements: List<Node>, s: Scope, functionLevel: Boolean) {
        if (functionLevel) for (statement in statements) collectVars(statement, s)
        for (statement in statements) {
            when (statement) {
                is VariableDeclaration -> if (statement.kind != DeclKind.VAR) declareDeclarators(statement, s)
                is FunctionDeclaration -> s.functions.add(HoistedFunction(s.declare(statement.id!!.name, 0), statement))
                else -> Unit
            }
        }
    }

    private fun collectVars(n: Node?, s: Scope) {
        when (n) {
            is VariableDeclaration -> if (n.kind == DeclKind.VAR) declareDeclarators(n, s)
            is BlockStatement -> for (statement in n.body) collectVars(statement, s)
            is IfStatement -> {
                collectVars(n.consequent, s)
                collectVars(n.alternate, s)
            }
            is ForStatement -> {
                collectVars(n.init, s)
                collectVars(n.body, s)
            }
            is ForInStatement -> {
                collectVars(n.left, s)
                collectVars(n.body, s)
            }
            is ForOfStatement -> {
                collectVars(n.left, s)
                collectVars(n.body, s)
            }
            is WhileStatement -> collectVars(n.body, s)
            is DoWhileStatement -> collectVars(n.body, s)
            is TryStatement -> {
                collectVars(n.block, s)
                collectVars(n.handler?.body, s)
                collectVars(n.finalizer, s)
            }
            is SwitchStatement -> for (c in n.cases) for (statement in c.consequent) collectVars(statement, s)
            is LabeledStatement -> collectVars(n.body, s)
            else -> Unit
        }
    }

    private fun finish(s: Scope, perIteration: Boolean): ScopeInfo {
        val template = arrayOfNulls<Any?>(s.names.size)
        java.util.Arrays.fill(template, Undefined)
        for (slot in s.tdz) template[slot] = Tdz
        return ScopeInfo(template, s.functions.toTypedArray(), perIteration)
    }

    // ---- references --------------------------------------------------------------------------------------------------

    private fun lookup(name: String): Ref? {
        var s = scope
        var hops = 0
        while (s != null) {
            val b = s.names[name]
            if (b != null) return Ref(hops, b.slot, b.flags)
            if (s.isFunction || s.names.isNotEmpty()) hops++
            s = s.parent
        }
        return null
    }

    private fun resolve(id: Identifier) {
        var ref = lookup(id.name)
        if (ref == null && id.name == "arguments") {
            // `arguments` is a binding of the nearest function that is not an arrow function.
            var s = scope
            while (s != null) {
                if (s.isFunction && !s.isArrow && s.parent != null) {
                    if (s.argumentsSlot < 0) s.argumentsSlot = s.declare("arguments", 0)
                    ref = lookup("arguments")
                    break
                }
                s = s.parent
            }
        }
        id.resolution = ref ?: when (id.name) {
            "undefined" -> UNDEFINED_REF
            "NaN" -> NAN_REF
            "Infinity" -> INFINITY_REF
            else -> GlobalRef
        }
    }

    // ---- the walk ----------------------------------------------------------------------------------------------------

    private fun visit(n: Node?) {
        when (n) {
            null -> Unit
            is Identifier -> resolve(n)
            is Literal, is TemplateElement, is ThisExpression, is EmptyStatement, is BreakStatement, is ContinueStatement -> Unit
            is FunctionNode -> visitFunction(n)
            is BlockStatement -> visitBlock(n)
            is ForStatement -> visitFor(n)
            is ForInStatement -> visitForInOf(n, n.left, n.right, n.body)
            is ForOfStatement -> visitForInOf(n, n.left, n.right, n.body)
            is SwitchStatement -> visitSwitch(n)
            is TryStatement -> {
                visit(n.block)
                n.handler?.let(::visitCatch)
                visit(n.finalizer)
            }
            is MemberExpression -> {
                visit(n.obj)
                if (n.computed) visit(n.property)
            }
            is Property -> {
                if (n.computed) visit(n.key)
                visit(n.value)
            }
            is LabeledStatement -> visit(n.body)
            else -> n.forEachChild { visit(it) }
        }
    }

    private fun visitFunction(n: FunctionNode) {
        closures++
        val s = Scope(scope, true, n is ArrowFunctionExpression)
        val simple = n.params.all { it is Identifier }
        for (p in n.params) declarePatternNames(p, s, 0)
        val body = n.body
        if (body is BlockStatement) hoistDeclarations(body.body, s, functionLevel = true)
        val own = (n as? FunctionExpression)?.id
        if (own != null && own.name !in s.names) s.selfSlot = s.declare(own.name, REF_CONST)
        val saved = scope
        scope = s
        for (p in n.params) visit(p)
        if (body is BlockStatement) for (statement in body.body) visit(statement) else visit(body)
        scope = saved
        n.scope = FunctionInfo(finish(s, false), simple, s.argumentsSlot, s.selfSlot, source)
    }

    private fun visitBlock(n: BlockStatement) {
        val s = Scope(scope, false)
        hoistDeclarations(n.body, s, functionLevel = false)
        val saved = scope
        scope = s
        for (statement in n.body) visit(statement)
        scope = saved
        n.scope = if (s.names.isEmpty()) null else finish(s, false)
    }

    private fun visitFor(n: ForStatement) {
        val s = Scope(scope, false)
        val init = n.init
        if (init is VariableDeclaration && init.kind != DeclKind.VAR) declareDeclarators(init, s)
        val saved = scope
        scope = s
        val before = closures
        visit(n.init)
        visit(n.test)
        visit(n.update)
        visit(n.body)
        scope = saved
        n.scope = if (s.names.isEmpty()) null else finish(s, closures > before)
    }

    private fun visitForInOf(owner: ScopeOwner, left: Node, right: Node, body: Node) {
        visit(right)
        val s = Scope(scope, false)
        if (left is VariableDeclaration && left.kind != DeclKind.VAR) declareDeclarators(left, s)
        val saved = scope
        scope = s
        visit(left)
        visit(body)
        scope = saved
        owner.scope = if (s.names.isEmpty()) null else finish(s, false)
    }

    private fun visitSwitch(n: SwitchStatement) {
        visit(n.discriminant)
        val s = Scope(scope, false)
        for (c in n.cases) hoistDeclarations(c.consequent, s, functionLevel = false)
        val saved = scope
        scope = s
        for (c in n.cases) {
            visit(c.test)
            for (statement in c.consequent) visit(statement)
        }
        scope = saved
        n.scope = if (s.names.isEmpty()) null else finish(s, false)
    }

    private fun visitCatch(n: CatchClause) {
        val s = Scope(scope, false)
        declarePatternNames(n.param, s, 0)
        hoistDeclarations(n.body.body, s, functionLevel = false)
        val saved = scope
        scope = s
        visit(n.param)
        for (statement in n.body.body) visit(statement)
        scope = saved
        n.scope = if (s.names.isEmpty()) null else finish(s, false)
    }
}
