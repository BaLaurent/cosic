package cosic.script.next

import cosic.pattern.CosicError
import cosic.pattern.JsFn
import cosic.pattern.Undefined
import cosic.pattern.jsNumberToString
import cosic.pattern.jsStrictEq
import cosic.pattern.jsToInt32
import cosic.pattern.jsToString
import cosic.pattern.jsTruthy
import cosic.pattern.jsTypeof
import cosic.script.next.js.AccessorProp
import cosic.script.next.js.ErrorKind
import cosic.script.next.js.JsArray
import cosic.script.next.js.JsFunction
import cosic.script.next.js.JsObject
import cosic.script.next.js.JsThrow
import cosic.script.next.js.Prop
import cosic.script.next.js.PropFlags
import cosic.script.next.js.ScriptLimitExceeded
import cosic.script.next.js.add
import cosic.script.next.js.arrayIndexOf
import cosic.script.next.js.describe
import cosic.script.next.js.hasProperty
import cosic.script.next.js.instanceOf
import cosic.script.next.js.isNullish
import cosic.script.next.js.iteratorOf
import cosic.script.next.js.jsPow
import cosic.script.next.js.jsUnsignedShiftRight
import cosic.script.next.js.lessThan
import cosic.script.next.js.looseEquals
import cosic.script.next.js.toInt32
import cosic.script.next.js.toNumber
import cosic.script.next.js.toPropertyKey
import cosic.script.next.js.toStr
import cosic.script.next.js.toUint32

/*
 * The interpreter: a tree walk over the nodes of the parser, with the names already resolved to frame slots by the
 * [Resolver]. Strict mode throughout: assigning to a name that nobody declared is a ReferenceError, `this` is
 * `undefined` in a plain call, a refused assignment is a TypeError.
 *
 * Control flow: a statement returns null when it completes normally, or a completion (break, continue, return) that the
 * enclosing statements pass up until the one it concerns takes it. `throw` is a Kotlin exception ([JsThrow]); a native
 * [CosicError] and a [StackOverflowError] are catchable by the program too, a [ScriptLimitExceeded] is not.
 *
 * Errors carry the range of the innermost call, statement or arrow body that was running, so that the message points at
 * code the user wrote.
 */

/** The state of one run, one per thread (see [Realm.withExec]): the budget and the depth of nested calls. */
internal class Exec(
    @JvmField val maxSteps: Long,
    @JvmField val deadline: Long,
    @JvmField val maxDepth: Int,
) {
    @JvmField var steps = 0L
    @JvmField var depth = 0
}

/** The variables of one scope while it runs; see [Resolver]. [exec] is the run this frame belongs to. */
internal class Frame(
    @JvmField val slots: Array<Any?>,
    @JvmField val parent: Frame?,
    @JvmField val thisValue: Any?,
    @JvmField val exec: Exec,
)

internal class BreakCompletion(@JvmField val label: String?)

internal class ContinueCompletion(@JvmField val label: String?)

internal class ReturnCompletion(@JvmField val value: Any?)

private val BREAK = BreakCompletion(null)
private val CONTINUE = ContinueCompletion(null)

/** What an optional chain evaluates to once a `?.` has found `null` or `undefined`: the whole chain is `undefined`. */
private object ChainBreak

/** A function written in the program: a function declaration, function expression, method or arrow function. */
internal class JsClosure(
    realm: Realm,
    @JvmField val node: FunctionNode,
    @JvmField val info: FunctionInfo,
    /** The frame the function was made in; calls make their frame inside it. */
    @JvmField val env: Frame,
    name: String,
    /** Whether `new` works: functions made by `function` that are not async, not methods, not arrows. */
    private val constructable: Boolean,
    private val sourceStart: Int = node.start,
) : JsFunction(realm, name, node.length) {
    private var prototypeMade = false

    override val isConstructor: Boolean get() = constructable

    override fun call(thisArg: Any?, args: List<Any?>): Any? = realm.interpreter.callExternal(this, thisArg, args)

    override fun construct(args: List<Any?>): Any? = realm.interpreter.constructExternal(this, args)

    override fun sourceText(): String = info.source.substring(sourceStart, node.end)

    override fun getOwnProp(key: String): Prop? {
        if (constructable && !prototypeMade && key == "prototype") makePrototype()
        return super.getOwnProp(key)
    }

    override fun ownKeys(): List<String> {
        if (constructable && !prototypeMade) makePrototype()
        return super.ownKeys()
    }

    /** The `prototype` of a constructor is made when it is first looked at: most functions are never used with `new`. */
    private fun makePrototype() {
        prototypeMade = true
        val proto = JsObject(realm.objectPrototype)
        proto.defineOwn("constructor", this, PropFlags.HIDDEN)
        defineOwn("prototype", proto, PropFlags.WRITABLE)
    }
}

internal class Interpreter(private val realm: Realm) {

    // ---- entry points ------------------------------------------------------------------------------------------------

    /** Runs the program, already resolved, as the body of a function; the value of a top-level `return`, else `undefined`. */
    fun runProgramBody(program: Program, exec: Exec): Any? {
        val info = program.scope as ScopeInfo
        val frame = Frame(info.template.copyOf(), null, Undefined, exec)
        instantiate(info, frame)
        val c = execList(program.body, frame)
        return if (c is ReturnCompletion) c.value else Undefined
    }

    fun callExternal(fn: JsClosure, thisValue: Any?, args: List<Any?>): Any? =
        realm.withExec(callback = true) { callClosure(fn, thisValue, args, it) }

    fun constructExternal(fn: JsClosure, args: List<Any?>): Any? =
        realm.withExec(callback = true) { constructClosure(fn, args, it) }

    // ---- errors --------------------------------------------------------------------------------------------------------

    private fun error(kind: ErrorKind, message: String, node: Node): JsThrow = JsThrow(realm.newError(kind, message), node.range)

    private fun typeErr(message: String, node: Node) = error(ErrorKind.TYPE, message, node)

    private fun refErr(message: String, node: Node) = error(ErrorKind.REFERENCE, message, node)

    private fun limit(node: Node) = ScriptLimitExceeded("Script took too long", node.range)

    private fun nullishText(v: Any?): String = if (v == null) "null" else "undefined"

    // ---- statements --------------------------------------------------------------------------------------------------

    private fun execList(list: List<Node>, f: Frame): Any? {
        for (i in list.indices) {
            val c = exec(list[i], f)
            if (c != null) return c
        }
        return null
    }

    private fun exec(node: Node, f: Frame): Any? {
        val e = f.exec
        val steps = ++e.steps
        if (steps > e.maxSteps || (steps and 0x3FFL == 0L && System.nanoTime() > e.deadline)) throw limit(node)
        try {
            return when (node) {
                is ExpressionStatement -> {
                    eval(node.expression, f)
                    null
                }
                is VariableDeclaration -> {
                    execVariables(node, f)
                    null
                }
                is ReturnStatement -> ReturnCompletion(if (node.argument == null) Undefined else eval(node.argument, f))
                is IfStatement -> if (jsTruthy(eval(node.test, f))) exec(node.consequent, f) else node.alternate?.let { exec(it, f) }
                is BlockStatement -> execBlock(node, f)
                is ForStatement -> execFor(node, f, null)
                is ForOfStatement -> execForOf(node, f, null)
                is ForInStatement -> execForIn(node, f, null)
                is WhileStatement -> execWhile(node, f, null)
                is DoWhileStatement -> execDoWhile(node, f, null)
                is BreakStatement -> if (node.label == null) BREAK else BreakCompletion(node.label.name)
                is ContinueStatement -> if (node.label == null) CONTINUE else ContinueCompletion(node.label.name)
                is ThrowStatement -> throw JsThrow(eval(node.argument, f), node.range)
                is TryStatement -> execTry(node, f)
                is SwitchStatement -> execSwitch(node, f)
                is LabeledStatement -> execLabeled(node, f)
                is FunctionDeclaration, is EmptyStatement -> null
                else -> throw IllegalStateException("not a statement: $node")
            }
        } catch (t: JsThrow) {
            throw t.withRange(node.range)
        } catch (t: CosicError) {
            throw t.withRange(node.range)
        }
    }

    private fun execBlock(node: BlockStatement, f: Frame): Any? {
        val info = node.scope as ScopeInfo? ?: return execList(node.body, f)
        return execList(node.body, newFrame(info, f))
    }

    private fun execVariables(node: VariableDeclaration, f: Frame) {
        val isVar = node.kind == DeclKind.VAR
        for (d in node.declarations) {
            val id = d.id
            val init = d.init
            if (init == null) {
                // `let x;` leaves the temporal dead zone with `undefined`; `var x;` does nothing.
                if (!isVar) initIdentifier(id as Identifier, Undefined, f)
                continue
            }
            if (id is Identifier) initIdentifier(id, evalNamed(init, f, id.name), f) else bindPattern(id, eval(init, f), f, true, init)
        }
    }

    /** What a loop does about the completion [c] of its body: 0 go on, 1 leave the loop normally, 2 leave with [c]. */
    private fun loopAction(c: Any?, labels: List<String>?): Int {
        if (c == null || c === CONTINUE) return 0
        if (c === BREAK) return 1
        if (c is ContinueCompletion && labels != null && c.label in labels) return 0
        return 2
    }

    private fun execWhile(node: WhileStatement, f: Frame, labels: List<String>?): Any? {
        while (jsTruthy(eval(node.test, f))) {
            val c = exec(node.body, f)
            when (loopAction(c, labels)) {
                1 -> return null
                2 -> return c
            }
        }
        return null
    }

    private fun execDoWhile(node: DoWhileStatement, f: Frame, labels: List<String>?): Any? {
        do {
            val c = exec(node.body, f)
            when (loopAction(c, labels)) {
                1 -> return null
                2 -> return c
            }
        } while (jsTruthy(eval(node.test, f)))
        return null
    }

    private fun execFor(node: ForStatement, outer: Frame, labels: List<String>?): Any? {
        val info = node.scope as ScopeInfo?
        var f = if (info != null) newFrame(info, outer) else outer
        val init = node.init
        if (init is VariableDeclaration) execVariables(init, f) else if (init != null) eval(init, f)
        val perIteration = info != null && info.perIteration
        if (perIteration) f = Frame(f.slots.copyOf(), f.parent, f.thisValue, f.exec)
        val test = node.test
        val update = node.update
        while (test == null || jsTruthy(eval(test, f))) {
            val c = exec(node.body, f)
            when (loopAction(c, labels)) {
                1 -> return null
                2 -> return c
            }
            // Each iteration has its own copy of the `let` variables, which a closure made in it keeps.
            if (perIteration) f = Frame(f.slots.copyOf(), f.parent, f.thisValue, f.exec)
            if (update != null) eval(update, f)
        }
        return null
    }

    private fun execForOf(node: ForOfStatement, f: Frame, labels: List<String>?): Any? {
        val iterable = eval(node.right, f)
        val iterator = iteratorOf(iterable) ?: throw notIterable(node.right, iterable, node.right)
        val info = node.scope as ScopeInfo?
        while (iterator.hasNext()) {
            val value = iterator.next()
            val frame = if (info != null) newFrame(info, f) else f
            assignLoopTarget(node.left, value, frame)
            val c = exec(node.body, frame)
            when (loopAction(c, labels)) {
                1 -> return null
                2 -> return c
            }
        }
        return null
    }

    private fun execForIn(node: ForInStatement, f: Frame, labels: List<String>?): Any? {
        val subject = eval(node.right, f)
        if (isNullish(subject)) return null
        val info = node.scope as ScopeInfo?
        for (key in forInKeys(subject)) {
            if (subject is JsObject && !subject.has(key)) continue
            val frame = if (info != null) newFrame(info, f) else f
            assignLoopTarget(node.left, key, frame)
            val c = exec(node.body, frame)
            when (loopAction(c, labels)) {
                1 -> return null
                2 -> return c
            }
        }
        return null
    }

    private fun assignLoopTarget(left: Node, value: Any?, f: Frame) {
        if (left is VariableDeclaration) bindPattern(left.declarations[0].id, value, f, true, null) else bindPattern(left, value, f, false, null)
    }

    /** The keys `for in` visits: enumerable ones, own first and then along the prototypes, each name once. */
    private fun forInKeys(v: Any?): List<String> {
        if (v is String) return List(v.length) { it.toString() }
        if (v !is JsObject) return emptyList()
        val seen = HashSet<String>()
        val keys = ArrayList<String>()
        var o: JsObject? = v
        while (o != null) {
            for (k in o.ownKeys()) {
                if (seen.add(k) && o.getOwnProp(k)?.enumerable == true) keys.add(k)
            }
            o = o.prototype
        }
        return keys
    }

    private fun execTry(node: TryStatement, f: Frame): Any? {
        var completion: Any? = null
        var pending: Throwable? = null
        try {
            try {
                completion = execBlock(node.block, f)
            } catch (t: Throwable) {
                val handler = node.handler
                if (handler == null || !realm.isCatchable(t)) throw t
                completion = execCatch(handler, realm.caught(t), f)
            }
        } catch (t: Throwable) {
            pending = t
        }
        val finalizer = node.finalizer
        if (finalizer != null) {
            if (pending is ScriptLimitExceeded) throw pending
            val c = execBlock(finalizer, f)
            // An abrupt completion of the `finally` block (return, break, throw) replaces whatever was going on.
            if (c != null) return c
        }
        if (pending != null) throw pending
        return completion
    }

    private fun execCatch(handler: CatchClause, value: Any?, f: Frame): Any? {
        val info = handler.scope as ScopeInfo?
        val frame = if (info != null) newFrame(info, f) else f
        handler.param?.let { bindPattern(it, value, frame, true, null) }
        return execList(handler.body.body, frame)
    }

    private fun execSwitch(node: SwitchStatement, f: Frame): Any? {
        val discriminant = eval(node.discriminant, f)
        val info = node.scope as ScopeInfo?
        val frame = if (info != null) newFrame(info, f) else f
        val cases = node.cases
        var start = -1
        for (i in cases.indices) {
            val test = cases[i].test ?: continue
            if (jsStrictEq(discriminant, eval(test, frame))) {
                start = i
                break
            }
        }
        if (start < 0) start = cases.indexOfFirst { it.test == null }
        if (start < 0) return null
        for (i in start until cases.size) {
            val c = execList(cases[i].consequent, frame)
            if (c != null) return if (c === BREAK) null else c
        }
        return null
    }

    private fun execLabeled(node: LabeledStatement, f: Frame): Any? {
        val labels = ArrayList<String>(1)
        var body: Node = node
        while (body is LabeledStatement) {
            labels.add(body.label.name)
            body = body.body
        }
        val c = when (body) {
            is ForStatement -> execFor(body, f, labels)
            is ForOfStatement -> execForOf(body, f, labels)
            is ForInStatement -> execForIn(body, f, labels)
            is WhileStatement -> execWhile(body, f, labels)
            is DoWhileStatement -> execDoWhile(body, f, labels)
            else -> exec(body, f)
        }
        if (c is BreakCompletion && c.label != null && c.label in labels) return null
        return c
    }

    // ---- frames and functions ----------------------------------------------------------------------------------------

    private fun newFrame(info: ScopeInfo, parent: Frame): Frame {
        val frame = Frame(info.template.copyOf(), parent, parent.thisValue, parent.exec)
        if (info.functions.isNotEmpty()) instantiate(info, frame)
        return frame
    }

    /** Makes the function declarations of a scope that is being entered. */
    private fun instantiate(info: ScopeInfo, frame: Frame) {
        for (h in info.functions) frame.slots[h.slot] = makeClosure(h.node, frame, h.node.id!!.name)
    }

    private fun makeClosure(node: FunctionNode, f: Frame, name: String, method: Boolean = false, sourceStart: Int = node.start): JsClosure =
        JsClosure(realm, node, node.scope as FunctionInfo, f, name, !method && !node.async && node !is ArrowFunctionExpression, sourceStart)

    /** [node] as a value; an anonymous function (or arrow) takes [name], as in `const f = () => 1`. */
    private fun evalNamed(node: Node, f: Frame, name: String?): Any? =
        if (name != null && node is FunctionNode && node.id == null) makeClosure(node, f, name) else eval(node, f)

    fun callClosure(fn: JsClosure, thisValue: Any?, args: List<Any?>, exec: Exec): Any? {
        val node = fn.node
        if (exec.depth >= exec.maxDepth) throw JsThrow(realm.newError(ErrorKind.RANGE, "Maximum call stack size exceeded"))
        exec.depth++
        try {
            val steps = ++exec.steps
            if (steps > exec.maxSteps || (steps and 0x3FFL == 0L && System.nanoTime() > exec.deadline)) throw limit(node)
            val info = fn.info
            val frame = Frame(info.scope.template.copyOf(), fn.env, if (node is ArrowFunctionExpression) fn.env.thisValue else thisValue, exec)
            if (info.selfSlot >= 0) frame.slots[info.selfSlot] = fn
            if (info.argumentsSlot >= 0) frame.slots[info.argumentsSlot] = realm.newArray(args)
            return if (node.async) realm.async.runAsync { runBody(fn, frame, args) } else runBody(fn, frame, args)
        } finally {
            exec.depth--
        }
    }

    private fun runBody(fn: JsClosure, frame: Frame, args: List<Any?>): Any? {
        val node = fn.node
        bindParams(node, fn.info, frame, args)
        instantiate(fn.info.scope, frame)
        val body = node.body
        if (body is BlockStatement) {
            val c = execList(body.body, frame)
            return if (c is ReturnCompletion) c.value else Undefined
        }
        try {
            return eval(body, frame)
        } catch (t: JsThrow) {
            throw t.withRange(body.range)
        } catch (t: CosicError) {
            throw t.withRange(body.range)
        }
    }

    private fun bindParams(node: FunctionNode, info: FunctionInfo, frame: Frame, args: List<Any?>) {
        val params = node.params
        if (info.simpleParams) {
            val n = minOf(params.size, args.size)
            for (i in 0 until n) frame.slots[i] = args[i]
            return
        }
        for (i in params.indices) {
            val p = params[i]
            if (p is RestElement) {
                val rest = if (i < args.size) args.subList(i, args.size) else emptyList()
                bindPattern(p.argument, realm.newArray(rest), frame, true, null)
                break
            }
            bindPattern(p, if (i < args.size) args[i] else Undefined, frame, true, null)
        }
    }

    fun constructClosure(fn: JsClosure, args: List<Any?>, exec: Exec): Any? {
        val prototype = fn.get("prototype", fn) as? JsObject ?: realm.objectPrototype
        val obj = JsObject(prototype)
        val result = callClosure(fn, obj, args, exec)
        return if (result is JsObject) result else obj
    }

    // ---- names -------------------------------------------------------------------------------------------------------

    private fun frameOf(r: Ref, f: Frame): Frame {
        var frame = f
        var h = r.hops
        while (h > 0) {
            frame = frame.parent!!
            h--
        }
        return frame
    }

    private fun lookup(id: Identifier, f: Frame): Any? {
        val r = id.resolution
        if (r is Ref) {
            val v = frameOf(r, f).slots[r.slot]
            if (v === Tdz) throw refErr("Cannot access '${id.name}' before initialization", id)
            return v
        }
        if (r is ConstRef) return r.value
        val p = realm.global.findProp(id.name) ?: throw refErr("${id.name} is not defined", id)
        return readProp(p, realm.global)
    }

    private fun readProp(p: Prop, receiver: Any?): Any? {
        if (p is AccessorProp) {
            val getter = p.getter ?: return Undefined
            return getter(receiver)
        }
        return p.value
    }

    /** The binding of a declaration: sets the slot, without the checks an assignment makes (a `const` is set once, here). */
    private fun initIdentifier(id: Identifier, value: Any?, f: Frame) {
        val r = id.resolution as? Ref ?: throw IllegalStateException("unresolved binding ${id.name}")
        frameOf(r, f).slots[r.slot] = value
    }

    private fun assignIdentifier(id: Identifier, value: Any?, f: Frame) {
        val r = id.resolution
        if (r is Ref) {
            val frame = frameOf(r, f)
            if (r.flags != 0) {
                if (r.flags and REF_TDZ != 0 && frame.slots[r.slot] === Tdz) throw refErr("Cannot access '${id.name}' before initialization", id)
                if (r.flags and REF_CONST != 0) throw typeErr("Assignment to constant variable.", id)
            }
            frame.slots[r.slot] = value
            return
        }
        if (r is ConstRef) throw typeErr("Cannot assign to read only property '${id.name}' of object '#<Object>'", id)
        if (!realm.global.has(id.name)) throw refErr("${id.name} is not defined", id)
        if (!realm.global.set(id.name, value, realm.global)) throw typeErr("Cannot assign to read only property '${id.name}' of object '#<Object>'", id)
    }

    // ---- expressions -------------------------------------------------------------------------------------------------

    private fun eval(node: Node, f: Frame): Any? = when (node) {
        is Identifier -> lookup(node, f)
        is Literal -> node.value
        is MemberExpression -> evalMember(node, f)
        is CallExpression -> evalCall(node, f)
        is BinaryExpression -> evalBinary(node, f)
        is AssignmentExpression -> evalAssign(node, f)
        is LogicalExpression -> evalLogical(node, f)
        is ConditionalExpression -> if (jsTruthy(eval(node.test, f))) eval(node.consequent, f) else eval(node.alternate, f)
        is UnaryExpression -> evalUnary(node, f)
        is UpdateExpression -> evalUpdate(node, f)
        is ArrowFunctionExpression -> makeClosure(node, f, "")
        is FunctionExpression -> makeClosure(node, f, node.id?.name ?: "")
        is ObjectExpression -> evalObject(node, f)
        is ArrayExpression -> evalArray(node, f)
        is TemplateLiteral -> evalTemplate(node, f)
        is ThisExpression -> f.thisValue
        is NewExpression -> evalNew(node, f)
        is ChainExpression -> eval(node.expression, f).let { if (it === ChainBreak) Undefined else it }
        is SequenceExpression -> {
            var last: Any? = Undefined
            for (e in node.expressions) last = eval(e, f)
            last
        }
        is TaggedTemplateExpression -> evalTagged(node, f)
        is AwaitExpression -> realm.async.await(eval(node.argument, f))
        else -> throw IllegalStateException("not an expression: $node")
    }

    private fun evalLogical(node: LogicalExpression, f: Frame): Any? {
        val l = eval(node.left, f)
        return when (node.operator) {
            LogicalOp.AND -> if (jsTruthy(l)) eval(node.right, f) else l
            LogicalOp.OR -> if (jsTruthy(l)) l else eval(node.right, f)
            LogicalOp.NULLISH -> if (isNullish(l)) eval(node.right, f) else l
        }
    }

    private fun logicalSkips(op: LogicalOp, old: Any?): Boolean = when (op) {
        LogicalOp.AND -> !jsTruthy(old)
        LogicalOp.OR -> jsTruthy(old)
        LogicalOp.NULLISH -> !isNullish(old)
    }

    private fun evalBinary(node: BinaryExpression, f: Frame): Any? {
        val l = eval(node.left, f)
        val r = eval(node.right, f)
        if (l is Double && r is Double) {
            when (node.operator) {
                BinaryOp.ADD -> return l + r
                BinaryOp.SUB -> return l - r
                BinaryOp.MUL -> return l * r
                BinaryOp.DIV -> return l / r
                BinaryOp.MOD -> return l % r
                BinaryOp.LT -> return l < r
                BinaryOp.GT -> return l > r
                BinaryOp.LE -> return l <= r
                BinaryOp.GE -> return l >= r
                BinaryOp.STRICT_EQ, BinaryOp.EQ -> return l == r
                BinaryOp.STRICT_NE, BinaryOp.NE -> return l != r
                else -> Unit
            }
        }
        try {
            return binary(node.operator, l, r)
        } catch (t: JsThrow) {
            throw t.withRange(node.range)
        }
    }

    /** `l op r`, the operators that take two values (not `&&`, `||`, `??`, which do not always evaluate their right side). */
    private fun binary(op: BinaryOp, l: Any?, r: Any?): Any? = when (op) {
        BinaryOp.ADD -> realm.add(l, r)
        BinaryOp.SUB -> realm.toNumber(l) - realm.toNumber(r)
        BinaryOp.MUL -> realm.toNumber(l) * realm.toNumber(r)
        BinaryOp.DIV -> realm.toNumber(l) / realm.toNumber(r)
        BinaryOp.MOD -> realm.toNumber(l) % realm.toNumber(r)
        BinaryOp.EXP -> jsPow(realm.toNumber(l), realm.toNumber(r))
        BinaryOp.EQ -> realm.looseEquals(l, r)
        BinaryOp.NE -> !realm.looseEquals(l, r)
        BinaryOp.STRICT_EQ -> jsStrictEq(l, r)
        BinaryOp.STRICT_NE -> !jsStrictEq(l, r)
        BinaryOp.LT -> realm.lessThan(l, r) == true
        BinaryOp.GT -> realm.lessThan(r, l) == true
        BinaryOp.LE -> realm.lessThan(r, l) == false
        BinaryOp.GE -> realm.lessThan(l, r) == false
        BinaryOp.SHL -> (realm.toInt32(l) shl (realm.toInt32(r) and 31)).toDouble()
        BinaryOp.SHR -> (realm.toInt32(l) shr (realm.toInt32(r) and 31)).toDouble()
        BinaryOp.USHR -> jsUnsignedShiftRight(realm.toInt32(l), realm.toInt32(r))
        BinaryOp.BIT_AND -> (realm.toInt32(l) and realm.toInt32(r)).toDouble()
        BinaryOp.BIT_OR -> (realm.toInt32(l) or realm.toInt32(r)).toDouble()
        BinaryOp.BIT_XOR -> (realm.toInt32(l) xor realm.toInt32(r)).toDouble()
        BinaryOp.IN -> realm.hasProperty(r, l)
        BinaryOp.INSTANCEOF -> realm.instanceOf(l, r)
    }

    private fun evalUnary(node: UnaryExpression, f: Frame): Any? {
        val arg = node.argument
        try {
            when (node.operator) {
                UnaryOp.TYPEOF -> {
                    if (arg is Identifier && arg.resolution === GlobalRef) {
                        val p = realm.global.findProp(arg.name) ?: return "undefined"
                        return jsTypeof(readProp(p, realm.global))
                    }
                    return jsTypeof(eval(arg, f))
                }
                UnaryOp.DELETE -> return evalDelete(arg, f, node)
                UnaryOp.VOID -> {
                    eval(arg, f)
                    return Undefined
                }
                UnaryOp.NOT -> return !jsTruthy(eval(arg, f))
                UnaryOp.MINUS -> {
                    val v = eval(arg, f)
                    return if (v is Double) -v else -realm.toNumber(v)
                }
                UnaryOp.PLUS -> {
                    val v = eval(arg, f)
                    return if (v is Double) v else realm.toNumber(v)
                }
                UnaryOp.BIT_NOT -> return realm.toInt32(eval(arg, f)).inv().toDouble()
            }
        } catch (t: JsThrow) {
            throw t.withRange(node.range)
        }
    }

    private fun evalDelete(arg: Node, f: Frame, node: Node): Any? {
        if (arg is ChainExpression) {
            val m = arg.expression as? MemberExpression ?: return true
            val base = eval(m.obj, f)
            if (base === ChainBreak || (m.optional && isNullish(base))) return true
            return deleteMember(base, m, f, node)
        }
        if (arg is MemberExpression) return deleteMember(eval(arg.obj, f), arg, f, node)
        eval(arg, f)
        return true
    }

    private fun deleteMember(base: Any?, m: MemberExpression, f: Frame, node: Node): Any? {
        val key = if (m.computed) realm.toPropertyKey(eval(m.property, f)) else (m.property as Identifier).name
        if (isNullish(base)) throw typeErr("Cannot convert undefined or null to object", node)
        if (base !is JsObject) return true
        if (!base.delete(key)) throw typeErr("Cannot delete property '$key' of ${objectDescription(base)}", node)
        return true
    }

    private fun evalUpdate(node: UpdateExpression, f: Frame): Any? {
        val target = node.argument
        val delta = if (node.operator == UpdateOp.INCREMENT) 1.0 else -1.0
        try {
            if (target is Identifier) {
                val v = lookup(target, f)
                val old = if (v is Double) v else realm.toNumber(v)
                val new = old + delta
                assignIdentifier(target, new, f)
                return if (node.prefix) new else old
            }
            target as MemberExpression
            val base = eval(target.obj, f)
            val keyV: Any? = if (target.computed) eval(target.property, f) else null
            val old = realm.toNumber(if (target.computed) getComputed(base, keyV, target) else getNamed(base, (target.property as Identifier).name, target))
            val new = old + delta
            if (target.computed) setComputed(base, keyV, new, target) else setNamed(base, (target.property as Identifier).name, new, target)
            return if (node.prefix) new else old
        } catch (t: JsThrow) {
            throw t.withRange(node.range)
        }
    }

    private fun evalAssign(node: AssignmentExpression, f: Frame): Any? {
        val left = node.left
        val op = node.operator
        try {
            if (op == AssignOp.ASSIGN) {
                if (left is Identifier) {
                    val v = evalNamed(node.right, f, left.name)
                    assignIdentifier(left, v, f)
                    return v
                }
                if (left is MemberExpression) {
                    val base = eval(left.obj, f)
                    val keyV: Any? = if (left.computed) eval(left.property, f) else null
                    val v = eval(node.right, f)
                    if (left.computed) setComputed(base, keyV, v, left) else setNamed(base, (left.property as Identifier).name, v, left)
                    return v
                }
                val v = eval(node.right, f)
                bindPattern(left, v, f, false, node.right)
                return v
            }
            val logical = op.logical
            if (left is Identifier) {
                val old = lookup(left, f)
                if (logical != null) {
                    if (logicalSkips(logical, old)) return old
                    val v = evalNamed(node.right, f, left.name)
                    assignIdentifier(left, v, f)
                    return v
                }
                val v = binaryOp(op.binary!!, old, eval(node.right, f))
                assignIdentifier(left, v, f)
                return v
            }
            left as MemberExpression
            val base = eval(left.obj, f)
            val keyV: Any? = if (left.computed) eval(left.property, f) else null
            val name = if (left.computed) "" else (left.property as Identifier).name
            val old = if (left.computed) getComputed(base, keyV, left) else getNamed(base, name, left)
            val v: Any?
            if (logical != null) {
                if (logicalSkips(logical, old)) return old
                v = eval(node.right, f)
            } else {
                v = binaryOp(op.binary!!, old, eval(node.right, f))
            }
            if (left.computed) setComputed(base, keyV, v, left) else setNamed(base, name, v, left)
            return v
        } catch (t: JsThrow) {
            throw t.withRange(node.range)
        }
    }

    private fun binaryOp(op: BinaryOp, l: Any?, r: Any?): Any? =
        if (l is Double && r is Double) {
            when (op) {
                BinaryOp.ADD -> l + r
                BinaryOp.SUB -> l - r
                BinaryOp.MUL -> l * r
                BinaryOp.DIV -> l / r
                else -> binary(op, l, r)
            }
        } else {
            binary(op, l, r)
        }

    // ---- properties --------------------------------------------------------------------------------------------------

    private fun readError(base: Any?, key: String, node: Node): JsThrow =
        typeErr("Cannot read properties of ${nullishText(base)} (reading '$key')", node)

    private fun getNamed(base: Any?, name: String, node: Node): Any? = when (base) {
        is JsObject -> base.get(name, base)
        is String -> getStringProperty(base, name)
        is Double -> realm.numberPrototype.get(name, base)
        is Boolean -> realm.booleanPrototype.get(name, base)
        null, Undefined -> throw readError(base, name, node)
        is Number -> realm.numberPrototype.get(name, base)
        is JsFn -> if (name == "length") base.arity.toDouble() else realm.functionPrototype.get(name, base)
        else -> Undefined
    }

    private fun getStringProperty(s: String, name: String): Any? {
        if (name == "length") return s.length.toDouble()
        val i = arrayIndexOf(name)
        if (i >= 0 && i < s.length) return s[i].toString()
        return realm.stringPrototype.get(name, s)
    }

    private fun getComputed(base: Any?, key: Any?, node: Node): Any? {
        if (key is Double) {
            val i = key.toInt()
            if (i.toDouble() == key && i >= 0) {
                if (base is JsArray) {
                    if (i < base.items.size) return base.items[i]
                } else if (base is String) {
                    if (i < base.length) return base[i].toString()
                }
            }
        }
        if (isNullish(base)) throw readError(base, realm.toPropertyKey(key), node)
        return getNamed(base, realm.toPropertyKey(key), node)
    }

    private fun evalMember(node: MemberExpression, f: Frame): Any? {
        val base = eval(node.obj, f)
        if (base === ChainBreak) return ChainBreak
        if (node.optional && isNullish(base)) return ChainBreak
        return if (node.computed) getComputed(base, eval(node.property, f), node) else getNamed(base, (node.property as Identifier).name, node)
    }

    private fun setNamed(base: Any?, name: String, value: Any?, node: Node) {
        when (base) {
            is JsObject -> if (!base.set(name, value, base)) throw setFailure(base, name, node)
            null, Undefined -> throw typeErr("Cannot set properties of ${nullishText(base)} (setting '$name')", node)
            else -> throw typeErr("Cannot create property '$name' on ${jsTypeof(base)} '${jsToString(base)}'", node)
        }
    }

    private fun setComputed(base: Any?, key: Any?, value: Any?, node: Node) {
        if (base is JsArray && key is Double) {
            val i = key.toInt()
            if (i.toDouble() == key && i >= 0) {
                base.setIndex(i, value)
                return
            }
        }
        if (isNullish(base)) throw typeErr("Cannot set properties of ${nullishText(base)} (setting '${realm.toPropertyKey(key)}')", node)
        setNamed(base, realm.toPropertyKey(key), value, node)
    }

    /** The error for an assignment that [JsObject.set] refused, with V8's wording for the three ways it can happen. */
    private fun setFailure(obj: JsObject, key: String, node: Node): JsThrow {
        val p = obj.findProp(key)
        val where = objectDescription(obj)
        return typeErr(
            when {
                p is AccessorProp -> "Cannot set property $key of $where which has only a getter"
                p != null && obj is JsFunction -> "Cannot assign to read only property '$key' of function '${obj.sourceText()}'"
                p != null -> "Cannot assign to read only property '$key' of object '$where'"
                else -> "Cannot add property $key, object is not extensible"
            },
            node,
        )
    }

    private fun objectDescription(o: JsObject): String = if (o is JsArray) "[object Array]" else "#<${o.className}>"

    // ---- calls -------------------------------------------------------------------------------------------------------

    private fun evalArgs(args: List<Node>, f: Frame): List<Any?> {
        if (args.isEmpty()) return emptyList()
        val out = ArrayList<Any?>(args.size)
        for (a in args) {
            if (a is SpreadElement) spreadInto(out, eval(a.argument, f), a.argument, true) else out.add(eval(a, f))
        }
        return out
    }

    private fun spreadInto(out: MutableList<Any?>, v: Any?, node: Node, inCall: Boolean) {
        if (v is JsArray) {
            out.addAll(v.items)
            return
        }
        val it = iteratorOf(v) ?: throw spreadError(node, v, inCall)
        while (it.hasNext()) out.add(it.next())
    }

    private fun spreadError(node: Node, v: Any?, inCall: Boolean): JsThrow {
        if (inCall) {
            return typeErr(
                if (isNullish(v)) "${nullishText(v)} is not iterable (cannot read property ${nullishText(v)})" else "Spread syntax requires ...iterable[Symbol.iterator] to be a function",
                node,
            )
        }
        return notIterable(node, v, node)
    }

    private fun notIterable(source: Node, v: Any?, at: Node): JsThrow {
        val text = when {
            source is CallExpression -> return typeErr("${renderCallee(source.callee)} is not a function or its return value is not iterable", at)
            source is Identifier || source is MemberExpression || source is Literal -> renderCallee(source)
            source is ObjectExpression -> if (source.properties.isEmpty()) "{}" else "{(intermediate value)}"
            else -> realm.describe(v)
        }
        return typeErr("$text is not iterable", at)
    }

    private fun evalCall(node: CallExpression, f: Frame): Any? {
        val callee = node.callee
        val thisValue: Any?
        val fn: Any?
        if (callee is MemberExpression) {
            val base = eval(callee.obj, f)
            if (base === ChainBreak) return ChainBreak
            if (callee.optional && isNullish(base)) return ChainBreak
            fn = if (callee.computed) getComputed(base, eval(callee.property, f), callee) else getNamed(base, (callee.property as Identifier).name, callee)
            thisValue = base
        } else if (callee is ChainExpression && callee.expression is MemberExpression) {
            // `(a?.b)()` keeps `this`: the parentheses only end the chain.
            val m = callee.expression
            val base = eval(m.obj, f)
            if (base === ChainBreak || (m.optional && isNullish(base))) {
                fn = Undefined
                thisValue = Undefined
            } else {
                fn = if (m.computed) getComputed(base, eval(m.property, f), m) else getNamed(base, (m.property as Identifier).name, m)
                thisValue = base
            }
        } else {
            fn = eval(callee, f)
            if (fn === ChainBreak) return ChainBreak
            thisValue = Undefined
        }
        if (node.optional && isNullish(fn)) return ChainBreak
        return invoke(fn, thisValue, evalArgs(node.arguments, f), callee, node, f)
    }

    /** Calls [fn] for the call at [site]; [callee] is what the program wrote for it, for the message when it is not a function. */
    private fun invoke(fn: Any?, thisValue: Any?, args: List<Any?>, callee: Node, site: Node, f: Frame): Any? {
        try {
            if (fn is JsClosure) return callClosure(fn, thisValue, args, f.exec)
            if (fn is JsFunction) return fn.callAt(thisValue, args, site)
            if (fn is JsFn) return fn.call(args)
        } catch (t: JsThrow) {
            throw t.withRange(site.range)
        } catch (t: CosicError) {
            throw t.withRange(site.range)
        }
        throw typeErr("${renderCallee(callee)} is not a function", site)
    }

    private fun evalNew(node: NewExpression, f: Frame): Any? {
        val fn = eval(node.callee, f)
        val args = evalArgs(node.arguments, f)
        if (fn is JsFunction && fn.isConstructor) {
            try {
                return if (fn is JsClosure) constructClosure(fn, args, f.exec) else fn.construct(args)
            } catch (t: JsThrow) {
                throw t.withRange(node.range)
            } catch (t: CosicError) {
                throw t.withRange(node.range)
            }
        }
        throw typeErr("${renderCallee(node.callee)} is not a constructor", node)
    }

    private fun evalTagged(node: TaggedTemplateExpression, f: Frame): Any? {
        val tag = node.tag
        if (tag is Identifier) {
            val language = EmbeddedLanguages.lookup(tag.name)
            if (language != null) return language.evaluate(node.quasi.quasis[0].raw, node.quasi.start + 1, realm, node.range)
        }
        val thisValue: Any?
        val fn: Any?
        if (tag is MemberExpression) {
            val base = eval(tag.obj, f)
            fn = if (tag.computed) getComputed(base, eval(tag.property, f), tag) else getNamed(base, (tag.property as Identifier).name, tag)
            thisValue = base
        } else {
            fn = eval(tag, f)
            thisValue = Undefined
        }
        val quasis = node.quasi.quasis
        val strings = realm.newArray(quasis.map { it.cooked ?: Undefined })
        strings.defineOwn("raw", realm.newArray(quasis.map { it.raw }), 0)
        val args = ArrayList<Any?>(quasis.size)
        args.add(strings)
        for (e in node.quasi.expressions) args.add(eval(e, f))
        return invoke(fn, thisValue, args, tag, node, f)
    }

    /** `String(...)` of the values of a template, glued to its literal parts. */
    private fun evalTemplate(node: TemplateLiteral, f: Frame): Any? {
        val quasis = node.quasis
        if (node.expressions.isEmpty()) return quasis[0].cooked
        val sb = StringBuilder(quasis[0].cooked!!)
        try {
            for (i in node.expressions.indices) {
                sb.append(realm.toStr(eval(node.expressions[i], f)))
                sb.append(quasis[i + 1].cooked)
            }
        } catch (t: JsThrow) {
            throw t.withRange(node.range)
        }
        return sb.toString()
    }

    // ---- literals ----------------------------------------------------------------------------------------------------

    private fun evalArray(node: ArrayExpression, f: Frame): Any? {
        val items = ArrayList<Any?>(node.elements.size)
        for (e in node.elements) {
            when (e) {
                null -> items.add(Undefined)
                is SpreadElement -> spreadInto(items, eval(e.argument, f), e.argument, false)
                else -> items.add(eval(e, f))
            }
        }
        return JsArray(realm, items)
    }

    private fun propertyKeyName(key: Node): String = when (key) {
        is Identifier -> key.name
        is Literal -> if (key.value is String) key.value else if (key.value is Double) jsNumberToString(key.value) else jsToString(key.value)
        else -> throw IllegalStateException("unexpected property key $key")
    }

    private fun evalObject(node: ObjectExpression, f: Frame): Any? {
        val obj = realm.newObject()
        for (p in node.properties) {
            if (p is SpreadElement) {
                copyDataProperties(obj, eval(p.argument, f), null)
                continue
            }
            p as Property
            val key = if (p.computed) realm.toPropertyKey(eval(p.key, f)) else propertyKeyName(p.key)
            val valueNode = p.value
            when (p.kind) {
                PropertyKind.INIT -> {
                    val v = if (valueNode is FunctionNode && valueNode.id == null) {
                        makeClosure(valueNode, f, key, method = p.method, sourceStart = if (p.method) p.start else valueNode.start)
                    } else {
                        eval(valueNode, f)
                    }
                    obj.defineOwn(key, v, PropFlags.ALL)
                }
                PropertyKind.GET -> {
                    val c = makeClosure(valueNode as FunctionNode, f, key, method = true, sourceStart = p.start)
                    val existing = obj.getOwnProp(key) as? AccessorProp
                    obj.defineAccessor(key, { self -> c.call(self, emptyList()) }, existing?.setter)
                }
                PropertyKind.SET -> {
                    val c = makeClosure(valueNode as FunctionNode, f, key, method = true, sourceStart = p.start)
                    val existing = obj.getOwnProp(key) as? AccessorProp
                    obj.defineAccessor(key, existing?.getter, { self, v -> c.call(self, listOf(v)) })
                }
            }
        }
        return obj
    }

    /** `{...source}`: the own enumerable properties of [source] (a string gives its characters) are copied to [target], except the [excluded] keys. */
    private fun copyDataProperties(target: JsObject, source: Any?, excluded: Collection<String>?) {
        when (source) {
            is String -> for (i in source.indices) target.defineOwn(i.toString(), source[i].toString(), PropFlags.ALL)
            is JsObject -> for (key in source.ownKeys()) {
                if (excluded != null && key in excluded) continue
                val prop = source.getOwnProp(key) ?: continue
                if (!prop.enumerable) continue
                target.defineOwn(key, source.get(key, source), PropFlags.ALL)
            }
            else -> Unit
        }
    }

    // ---- destructuring -----------------------------------------------------------------------------------------------

    /**
     * Binds the pattern [target] to [value]. [init] is true for a declaration or parameter (a plain write to the slot) and
     * false for an assignment (`[a, b] = [b, a]`: constants and the dead zone are checked, members are set). [source] is
     * the expression the value came from, for the message when it cannot be destructured; [nested] is true for a pattern
     * inside another one, whose messages are worded as a failed property read.
     */
    private fun bindPattern(target: Node, value: Any?, f: Frame, init: Boolean, source: Node?, nested: Boolean = false) {
        when (target) {
            is Identifier -> if (init) initIdentifier(target, value, f) else assignIdentifier(target, value, f)
            is MemberExpression -> {
                val base = eval(target.obj, f)
                if (target.computed) setComputed(base, eval(target.property, f), value, target) else setNamed(base, (target.property as Identifier).name, value, target)
            }
            is AssignmentPattern -> {
                var v = value
                if (v === Undefined) v = evalNamed(target.right, f, (target.left as? Identifier)?.name)
                bindPattern(target.left, v, f, init, null, nested)
            }
            is ObjectPattern -> bindObjectPattern(target, value, f, init, source, nested)
            is ArrayPattern -> bindArrayPattern(target, value, f, init, source)
            else -> throw IllegalStateException("not a pattern: $target")
        }
    }

    private fun bindObjectPattern(pattern: ObjectPattern, value: Any?, f: Frame, init: Boolean, source: Node?, nested: Boolean) {
        if (isNullish(value)) throw destructureError(pattern, value, source, nested)
        val used = ArrayList<String>()
        for (p in pattern.properties) {
            if (p is RestElement) {
                val rest = realm.newObject()
                copyDataProperties(rest, value, used)
                bindPattern(p.argument, rest, f, init, null, true)
                continue
            }
            p as Property
            val key = if (p.computed) realm.toPropertyKey(eval(p.key, f)) else propertyKeyName(p.key)
            used.add(key)
            bindPattern(p.value, getNamed(value, key, p), f, init, null, true)
        }
    }

    private fun bindArrayPattern(pattern: ArrayPattern, value: Any?, f: Frame, init: Boolean, source: Node?) {
        val iterator = iteratorOf(value) ?: throw notIterableValue(value, source, pattern)
        for (e in pattern.elements) {
            if (e == null) {
                if (iterator.hasNext()) iterator.next()
                continue
            }
            if (e is RestElement) {
                val rest = ArrayList<Any?>()
                while (iterator.hasNext()) rest.add(iterator.next())
                bindPattern(e.argument, JsArray(realm, rest), f, init, null, true)
                break
            }
            bindPattern(e, if (iterator.hasNext()) iterator.next() else Undefined, f, init, null, true)
        }
    }

    private fun notIterableValue(value: Any?, source: Node?, at: Node): JsThrow =
        if (source != null) notIterable(source, value, at) else typeErr("${realm.describe(value)} is not iterable", at)

    /** V8's messages for `const { a } = undefined` and its relatives. */
    private fun destructureError(pattern: ObjectPattern, value: Any?, source: Node?, nested: Boolean): JsThrow {
        val first = pattern.properties.firstOrNull()
        val kind = nullishText(value)
        if (first is Property && !first.computed) {
            if (nested) return typeErr("Cannot read properties of $kind (reading '${propertyKeyName(first.key)}')", pattern)
            val what = if (source != null) renderCallee(source) else if (value == null) "object null" else "undefined"
            return typeErr("Cannot destructure property '${propertyKeyName(first.key)}' of '$what' as it is $kind.", pattern)
        }
        val what = if (source != null) renderCallee(source) else if (value == null) "object null" else "undefined"
        return typeErr("Cannot destructure '$what' as it is $kind.", pattern)
    }
}

/**
 * How the program wrote a callee, for `x is not a function`, as V8 prints it: a name, `a.b`, `a[b]`, `f(...)`, `this`, a
 * literal, and `(intermediate value)` for anything else.
 */
internal fun renderCallee(n: Node): String = when (n) {
    is Identifier -> n.name
    is ThisExpression -> "this"
    is MemberExpression -> {
        val obj = renderCallee(n.obj)
        val optional = if (n.optional) "?." else ""
        val p = n.property
        when {
            !n.computed -> "$obj${if (n.optional) "?." else "."}${(p as Identifier).name}"
            p is Literal && p.value is String -> "$obj${if (n.optional) "?." else "."}${p.value}"
            else -> "$obj$optional[${renderKey(p)}]"
        }
    }
    is CallExpression -> renderCallee(n.callee) + "(...)"
    is Literal -> if (n.value is String) "\"${n.value}\"" else n.raw
    is TemplateLiteral -> if (n.expressions.isEmpty()) "\"${n.quasis[0].cooked}\"" else "(intermediate value)"
    is ChainExpression -> renderCallee(n.expression)
    else -> "(intermediate value)"
}

private fun renderKey(n: Node): String = when (n) {
    is Identifier -> n.name
    is Literal -> n.raw
    is UnaryExpression -> if (n.operator == UnaryOp.MINUS && n.argument is Literal) "-" + renderKey(n.argument) else "(intermediate value)"
    is MemberExpression, is CallExpression -> renderCallee(n)
    is BinaryExpression -> "(${renderKey(n.left)} ${n.operator.symbol} ${renderKey(n.right)})"
    else -> "(intermediate value)"
}
