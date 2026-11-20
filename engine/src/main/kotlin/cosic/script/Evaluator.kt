package cosic.script

import cosic.SourceRange
import cosic.mini.parseMini
import cosic.pattern.ArithOp
import cosic.pattern.ControlMap
import cosic.pattern.CosicError
import cosic.pattern.Pattern
import cosic.pattern.arith
import cosic.pattern.asControlMap
import cosic.pattern.reportingExhaustion
import cosic.pattern.silence
import cosic.pattern.stack

/** A program's result: the pattern to play, as sound parameters, and its tempo in cycles per second. */
internal class ProgramResult(val pattern: Pattern<ControlMap>, val cps: Double)

/**
 * Parses and evaluates a program. This is the entry point of the language: the result's pattern is ready to
 * be queried, with every value turned into a [ControlMap] (a sound name becomes `{s: name}`, a number
 * `{note: number}`).
 *
 * The pattern is the stack of all labelled statements that are not muted; without labels it is the last
 * expression statement that is a pattern. Throws [CosicError] (with a source range) for syntax errors,
 * unknown names and bad arguments evaluated right away. Errors that depend on pattern values, such as a
 * word where a number is needed, are thrown later by the pattern when it is queried, also as [CosicError]
 * with the range of the call that caused them.
 */
internal fun evaluateProgram(code: String): ProgramResult = reportingExhaustion {
    Evaluator().run(parseProgram(code))
}

/**
 * Evaluates [code] the way `queryCode` in Strudel's test/runtime.mjs does, for the parity harness: the value of the
 * last statement is the result, as a raw pattern (no conversion to control maps). In that runtime labels are plain
 * expressions (`p` is mocked to the identity), `setcps` and `setcpm` return their argument, and the visual and output
 * methods it mocks (`osc`, `pianoroll`, `midi`, ...) return the pattern they are called on. A declaration as the last
 * statement gives silence, like the `silence` the transpiler appends there.
 */
internal fun evaluateForQuery(code: String): Pattern<Any> = reportingExhaustion {
    Evaluator(queryMode = true).runForQuery(parseProgram(code))
}

/** The methods test/runtime.mjs mocks as `function () { return this }`. */
private val IDENTITY_METHODS = setOf(
    "osc", "csound", "tone", "webdirt", "pianoroll", "speak", "wave", "filter", "adsr", "webaudio", "soundfont", "tune",
    "midi", "_scope", "_spiral", "_pitchwheel", "_pianoroll", "_spectrum", "_print", "markcss", "p", "dough",
)

/** The globals test/runtime.mjs mocks as `(x) => x`. */
private val IDENTITY_FUNCTIONS = setOf("setcps", "setcpm")

private class Scope(private val parent: Scope?) {
    private val values = HashMap<String, Value>()

    operator fun get(name: String): Value? = values[name] ?: parent?.get(name)

    operator fun set(name: String, value: Value) {
        values[name] = value
    }
}

/** Plays [pattern] but gives errors thrown while querying it without a range the range of [range]. */
private fun attributing(pattern: Pattern<Any>, range: SourceRange): Pattern<Any> = Pattern { span ->
    try {
        pattern.query(span)
    } catch (e: CosicError) {
        throw e.withRange(range)
    }
}

private fun scalarOf(value: Value): Any? = when (value) {
    is Value.Num -> value.value
    is Value.Str -> value.value
    is Value.Bool -> value.value
    else -> null
}

/**
 * The pattern combining [left] and [right] with [op]. The structure comes from the left; a plain value on
 * the left has none, so then it comes from the right.
 */
internal fun arithmetic(op: ArithOp, left: Value, right: Value): Pattern<Any> {
    val rightScalar = scalarOf(right)
    val leftScalar = scalarOf(left)
    return when {
        rightScalar != null -> toPattern(left).fmap { arith(op, it, rightScalar) }
        leftScalar != null -> toPattern(right).fmap { arith(op, leftScalar, it) }
        else -> toPattern(left).appLeft(toPattern(right)) { a, b -> arith(op, a, b) }
    }
}

private class Evaluator(private val queryMode: Boolean = false) {
    private val session = Session()
    private val globals = Scope(null)

    fun run(statements: List<Stmt>): ProgramResult {
        val labelled = ArrayList<Pattern<Any>>()
        var hasLabels = false
        var last: Pattern<Any>? = null
        for (statement in statements) {
            when (statement) {
                is Stmt.Let -> globals[statement.name] = eval(statement.value, globals)
                is Stmt.Labeled -> {
                    hasLabels = true
                    val pattern = patternOf(statement.value)
                    if (!statement.muted) labelled.add(pattern)
                }
                is Stmt.Evaluate -> {
                    val value = eval(statement.expr, globals)
                    if (value is Value.Pat) last = value.pattern
                }
            }
        }
        val program: Pattern<Any> = when {
            session.hushed -> silence
            hasLabels -> stack(labelled)
            else -> last ?: silence
        }
        return ProgramResult(program.fmap { asControlMap(it) }, session.cps)
    }

    /** The value of the last statement as a pattern, or silence when there is none; see [evaluateForQuery]. */
    fun runForQuery(statements: List<Stmt>): Pattern<Any> {
        var last: Value? = null
        for (statement in statements) {
            last = when (statement) {
                is Stmt.Let -> {
                    globals[statement.name] = eval(statement.value, globals)
                    null
                }
                is Stmt.Labeled -> eval(statement.value, globals)
                is Stmt.Evaluate -> eval(statement.expr, globals)
            }
        }
        return toPattern(last ?: return silence)
    }

    private fun patternOf(expr: Expr): Pattern<Any> = try {
        toPattern(eval(expr, globals))
    } catch (e: CosicError) {
        throw e.withRange(expr.range)
    }

    /** Evaluates [expr]; errors without a range of their own get the range of the innermost expression. */
    private fun eval(expr: Expr, scope: Scope): Value = try {
        evalNode(expr, scope)
    } catch (e: CosicError) {
        throw e.withRange(expr.range)
    }

    private fun evalNode(expr: Expr, scope: Scope): Value = when (expr) {
        is Expr.Number -> Value.Num(expr.value)
        is Expr.Text -> Value.Str(expr.value)
        is Expr.Bool -> Value.Bool(expr.value)
        is Expr.Mini -> Value.Pat(attributing(parseMini(expr.content, expr.contentStart), expr.range))
        is Expr.Ident -> identifier(expr, scope)
        is Expr.ArrayLit -> Value.Arr(expr.items.map { eval(it, scope) })
        is Expr.Negate -> negate(expr, scope)
        is Expr.Binary -> binary(expr, scope)
        is Expr.Lambda -> lambda(expr, scope)
        is Expr.Call -> call(expr, scope)
        is Expr.MethodCall -> methodCall(expr, scope)
    }

    private fun negate(expr: Expr.Negate, scope: Scope): Value {
        val operand = eval(expr.operand, scope)
        if (operand is Value.Num) return Value.Num(-operand.value)
        return Value.Pat(attributing(arithmetic(ArithOp.MUL, operand, Value.Num(-1.0)), expr.range))
    }

    private fun binary(expr: Expr.Binary, scope: Scope): Value {
        val left = eval(expr.left, scope)
        val right = eval(expr.right, scope)
        if (left is Value.Num && right is Value.Num) return Value.Num(expr.op.apply(left.value, right.value))
        return Value.Pat(attributing(arithmetic(expr.op, left, right), expr.range))
    }

    private fun lambda(expr: Expr.Lambda, scope: Scope): Value = Value.Fn { args ->
        if (args.size != expr.params.size) throw CosicError("Function expects ${expr.params.size} arguments, got ${args.size}")
        val inner = Scope(scope)
        for ((i, name) in expr.params.withIndex()) inner[name] = args[i]
        eval(expr.body, inner)
    }

    // ---- names and calls ----------------------------------------------------------------

    private fun identifier(expr: Expr.Ident, scope: Scope): Value {
        scope[expr.name]?.let { return it }
        val builtin = Builtins.lookup(expr.name) ?: throw CosicError("Unknown variable '${expr.name}'", expr.range)
        val takesNothing = builtin.params.isEmpty() && builtin.variadic == null
        return when {
            builtin.kind == BuiltinKind.SIGNAL || (builtin.kind == BuiltinKind.CONSTRUCTOR && takesNothing) ->
                invoke(builtin, expr.name, null, emptyList(), expr.range)
            builtin.kind == BuiltinKind.METHOD && takesNothing -> methodFunction(builtin, expr.name, emptyList(), expr.range)
            else -> throw CosicError("'${expr.name}' must be called, like ${expr.name}(...)", expr.range)
        }
    }

    private fun call(expr: Expr.Call, scope: Scope): Value {
        val callee = expr.callee
        if (callee is Expr.Ident) {
            val local = scope[callee.name]
            if (local == null) {
                if (queryMode && callee.name in IDENTITY_FUNCTIONS) {
                    return expr.args.map { eval(it, scope) }.firstOrNull() ?: Value.None
                }
                val builtin = Builtins.lookup(callee.name) ?: throw CosicError("Unknown function '${callee.name}'", expr.range)
                return callBuiltin(builtin, callee.name, expr.args.map { eval(it, scope) }, expr.range)
            }
            return applyValue(local, expr.args.map { eval(it, scope) }, callee.name)
        }
        return applyValue(eval(callee, scope), expr.args.map { eval(it, scope) }, null)
    }

    private fun applyValue(function: Value, args: List<Value>, name: String?): Value {
        if (function !is Value.Fn) throw CosicError(if (name != null) "'$name' is not a function" else "Not a function")
        return function.body(args)
    }

    private fun methodCall(expr: Expr.MethodCall, scope: Scope): Value {
        val receiver = eval(expr.receiver, scope)
        if (queryMode && expr.name in IDENTITY_METHODS) {
            expr.args.forEach { eval(it, scope) }
            return receiver
        }
        val builtin = Builtins.lookup(expr.name) ?: throw CosicError("Unknown function '${expr.name}'", expr.range)
        if (builtin.kind != BuiltinKind.METHOD && builtin.kind != BuiltinKind.CONTROL) {
            throw CosicError("'${expr.name}' can't be used as a method", expr.range)
        }
        val args = expr.args.map { eval(it, scope) }
        requireArity(builtin, expr.name, args.size, expected = builtin.params.size)
        return invoke(builtin, expr.name, toPattern(receiver), checked(builtin, expr.name, args), expr.range)
    }

    /** `name(args)` at top level: a constructor call, or a method whose pattern is the last argument. */
    private fun callBuiltin(builtin: Builtin, name: String, args: List<Value>, range: SourceRange): Value {
        val declared = builtin.params.size
        return when (builtin.kind) {
            BuiltinKind.CONSTRUCTOR, BuiltinKind.SIGNAL, BuiltinKind.SETTING -> {
                requireArity(builtin, name, args.size, expected = declared)
                invoke(builtin, name, null, checked(builtin, name, args), range)
            }
            BuiltinKind.CONTROL -> when (args.size) {
                declared -> invoke(builtin, name, null, checked(builtin, name, args), range)
                declared + 1 -> invoke(builtin, name, toPattern(args.last()), checked(builtin, name, args.dropLast(1)), range)
                else -> throw arityError(name, declared, args.size)
            }
            BuiltinKind.METHOD -> when {
                builtin.variadic != null -> {
                    requireArity(builtin, name, args.size, expected = declared)
                    methodFunction(builtin, name, checked(builtin, name, args), range)
                }
                args.size == declared + 1 ->
                    invoke(builtin, name, toPattern(args.last()), checked(builtin, name, args.dropLast(1)), range)
                args.size == declared -> methodFunction(builtin, name, checked(builtin, name, args), range)
                else -> throw arityError(name, declared, args.size)
            }
        }
    }

    /** A function waiting for the pattern a method works on. */
    private fun methodFunction(builtin: Builtin, name: String, args: List<Value>, range: SourceRange): Value =
        Value.Fn { rest ->
            if (rest.size != 1) throw CosicError("'$name' expects a pattern")
            invoke(builtin, name, toPattern(rest[0]), args, range)
        }

    private fun invoke(builtin: Builtin, name: String, self: Pattern<Any>?, args: List<Value>, range: SourceRange): Value {
        val result = try {
            builtin.impl(Call(name, range, self, args, session))
        } catch (e: CosicError) {
            throw e.withRange(range)
        } catch (e: ArithmeticException) {
            throw CosicError("'$name': number out of range", range)
        } catch (e: IllegalArgumentException) {
            throw CosicError("'$name': ${e.message}", range)
        }
        return if (result is Value.Pat) Value.Pat(attributing(result.pattern, range)) else result
    }

    private fun requireArity(builtin: Builtin, name: String, got: Int, expected: Int) {
        val fine = got == expected || (builtin.variadic != null && got >= expected)
        if (!fine) throw arityError(name, expected, got)
    }

    private fun arityError(name: String, expected: Int, got: Int) =
        CosicError("'$name' expects $expected arguments, got $got")

    /**
     * Checks every argument against the kind of its parameter. A setting is the exception: it takes plain values and
     * words its own complaint about a bad one, like `Tempo out of range` for `setcps('x')`.
     */
    private fun checked(builtin: Builtin, name: String, args: List<Value>): List<Value> {
        if (builtin.kind == BuiltinKind.SETTING) return args
        return args.mapIndexed { i, value ->
            when (builtin.params.getOrNull(i) ?: builtin.variadic) {
                ParamKind.NUM_PAT ->
                    if (value is Value.Num || value is Value.Pat || value is Value.Arr) value else throw wrongKind(name, "a number", value)
                ParamKind.STR_PAT ->
                    if (value is Value.Str || value is Value.Num || value is Value.Pat || value is Value.Arr) value
                    else throw wrongKind(name, "a string", value)
                ParamKind.PAT -> if (value is Value.Pat) value else Value.Pat(toPattern(value))
                ParamKind.FN -> if (value is Value.Fn) value else throw wrongKind(name, "a function", value)
                ParamKind.ANY, null -> value
            }
        }
    }

    private fun wrongKind(name: String, wanted: String, value: Value) =
        CosicError("'$name' expects $wanted, got ${describe(value)}")
}
