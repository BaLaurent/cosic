package cosic.pattern

import java.util.concurrent.ConcurrentHashMap

/**
 * A JavaScript function value: what a hap can hold (`pickF`, the partially applied functions of `register`) and what
 * `register` and friends receive. [arity] is `func.length`, the number of parameters before the first default or rest
 * parameter. It is an interface so that the interpreter's function objects, which are objects as well, can implement
 * it: pattern code then calls user lambdas directly.
 */
internal interface JsFn {
    val arity: Int

    fun call(args: List<Any?>): Any?

    operator fun invoke(vararg a: Any?): Any? = call(a.asList())
}

/** A native function of [arity] parameters. */
internal fun jsFn(arity: Int, body: (List<Any?>) -> Any?): JsFn = NativeJsFn(arity, body)

private class NativeJsFn(override val arity: Int, private val body: (List<Any?>) -> Any?) : JsFn {
    override fun call(args: List<Any?>): Any? = body(args)
}

/** Calls [f] with [args]; a value that is not a function is a `TypeError`. */
internal fun callFn(f: Any?, vararg args: Any?): Any? {
    if (f !is JsFn) throw CosicError("${stringifyValues(f, true)} is not a function")
    return f.call(args.asList())
}

/**
 * `curry` of util.mjs: a function that collects arguments over any number of calls and runs [f] once it has [arity] of
 * them (or more). The function returned for a partial call reports the number of arguments still missing as its arity.
 */
internal fun curry(arity: Int, f: (List<Any?>) -> Any?): JsFn = curried(arity, emptyList(), f)

private fun curried(arity: Int, have: List<Any?>, f: (List<Any?>) -> Any?): JsFn = NativeJsFn(arity - have.size) { args ->
    val all = if (have.isEmpty()) args else have + args
    if (all.size >= arity) f(all) else curried(arity, all, f)
}

/** How a [Registered] function is called. */
internal enum class RegisteredKind {
    /** Made by `register`: a method with the pattern as the receiver and a curried global with the pattern last. */
    REGISTER,

    /** A plain global function that takes its arguments as they are (`pure`, `gap`, `stepcat`). It has no method form. */
    FUNCTION,

    /** A global that takes any number of arguments (`stack`, `cat`, `seq`), and perhaps a method that puts the receiver first. */
    VARIADIC,
}

/**
 * One function of the catalogue (K4): what `register(name, func, patternify, preserveSteps, join)` of Strudel makes of
 * a function. [impl] is `func`: for a registered function it gets the plain arguments with the pattern last, as
 * `func(...args, pat)` does. [alignable] marks an operator (`add`, `mul`, ...) whose accessor also carries the
 * alignment variants `.in`, `.out`, `.mix`, ... (see [alignHook]).
 */
internal class Registered(
    val names: List<String>,
    val arity: Int,
    val patternify: Boolean,
    val preserveSteps: Boolean,
    val join: (Pattern) -> Pattern,
    val impl: (List<Any?>) -> Any?,
    val alignable: Boolean = false,
    val kind: RegisteredKind = RegisteredKind.REGISTER,
    /** Whether `pat.name(...)` exists. Always true for a registered function. */
    val hasMethod: Boolean = kind != RegisteredKind.FUNCTION,
    /** When set, `pat.name(...)` calls this with the receiver and the arguments as they are, instead of the registered-function rules (see [Registry.dual]). */
    val methodImpl: ((Pattern, List<Any?>) -> Any?)? = null,
) {
    val name: String get() = names[0]

    /** Whether the plain method `pat._name(...)` exists: a registered function with more than the pattern as parameter. */
    val hasPlain: Boolean get() = kind == RegisteredKind.REGISTER && arity > 1

    private val curriedGlobal: JsFn by lazy(LazyThreadSafetyMode.PUBLICATION) { curry(arity) { pfunc(it) } }

    /**
     * The patternified function (`pfunc`): every argument becomes a pattern; when all the arguments before the pattern
     * are constant, [impl] is called with their plain values, otherwise it is called for each value of the patterns
     * and the resulting patterns are joined with [join].
     */
    private fun pfunc(args0: List<Any?>): Any? {
        val args = args0.map(::reify)
        val pat = args.last()
        if (!patternify) {
            val result = impl(args)
            return if (preserveSteps && result is Pattern) result.setSteps(pat.steps) else result
        }
        var result: Any?
        if (arity == 1) {
            result = impl(listOf(pat))
        } else {
            val firstArgs = args.dropLast(1)
            if (firstArgs.all { it.pureValue != null }) {
                val pureArgs = firstArgs.map { it.pureValue }
                val pureLocs = firstArgs.mapNotNull { it.pureLoc }
                result = patternOf(impl(pureArgs + listOf(pat))).withContext { ctx ->
                    if (pureLocs.isEmpty()) ctx else ctx.copy(locations = ctx.locations + pureLocs)
                }
            } else {
                val mapFn = curry(arity - 1) { a -> impl(a + listOf(pat)) }
                var acc = firstArgs[0].fmap { v -> mapFn.call(listOf(v)) }
                for (p in firstArgs.drop(1)) acc = acc.appLeft(p)
                result = join(acc)
            }
        }
        if (preserveSteps && result is Pattern) result = result.setSteps(pat.steps)
        return result
    }

    /**
     * `Pattern.prototype[name](...args)`: a function of one patterned argument (arity 2) takes any number of
     * arguments and sequences them (`.fast()` is `.fast(silence)`, `.fast(2, 3)` is `.fast(seq(2, 3))`); any other
     * function must get exactly its arguments. A [RegisteredKind.VARIADIC] entry puts the receiver first.
     */
    fun method(receiver: Pattern, args: List<Any?>): Pattern {
        if (!hasMethod) throw CosicError("'$name' can't be used as a method")
        methodImpl?.let { return resultPattern(it(receiver, args)) }
        if (kind == RegisteredKind.VARIADIC) return resultPattern(impl(listOf<Any?>(receiver) + args))
        val given = when {
            arity == 2 && args.size != 1 -> listOf<Any?>(sequence(args))
            arity != args.size + 1 -> throw CosicError(".$name() expects ${arity - 1} inputs but got ${args.size}.")
            else -> args
        }
        return resultPattern(pfunc(given.map(::reify) + listOf(receiver)))
    }

    /** `Pattern.prototype['_' + name](...args)`: the function on the receiver with the arguments as they are: no patterns, no joins. */
    fun plain(receiver: Pattern, args: List<Any?>): Any? {
        val result = impl(args + listOf(receiver))
        return if (preserveSteps && result is Pattern) result.setSteps(receiver.steps) else result
    }

    /** The global function: a registered function is curried (the pattern comes last), the others are called with what they get. */
    fun global(args: List<Any?>): Any? = if (kind == RegisteredKind.REGISTER) curriedGlobal.call(args) else impl(args)

    private fun resultPattern(result: Any?): Pattern =
        result as? Pattern ?: throw CosicError("'$name' did not return a pattern, but ${stringifyValues(result, true)}")
}

/**
 * Where `pat.<op>.<how>(...args)` (`add.out`, `mul.squeeze`, ...) is carried out for a [Registered.alignable] operator:
 * [op] is the operator, [how] one of `in out mix squeeze squeezein squeezeout reset restart poly`. Set by the operator
 * catalogue; until then the alignments are not available.
 */
@Volatile
internal var alignHook: ((pat: Pattern, op: String, how: String, args: List<Any?>) -> Pattern)? = null

/** The one catalogue of functions programs can call. A later registration of a name replaces an earlier one, as `register` does in Strudel. */
internal object Registry {
    private val entries = ConcurrentHashMap<String, Registered>()
    private val constants = ConcurrentHashMap<String, Any>()

    /**
     * `register(names, func, patternify, preserveSteps, join)`: every name gets its own [Registered]. [arity] counts
     * the pattern, which [impl] gets last.
     */
    fun register(
        names: List<String>,
        arity: Int,
        patternify: Boolean = true,
        preserveSteps: Boolean = false,
        join: (Pattern) -> Pattern = { it.innerJoin() },
        alignable: Boolean = false,
        impl: (List<Any?>) -> Any?,
    ): List<Registered> = names.map { name ->
        Registered(listOf(name), arity, patternify, preserveSteps, join, impl, alignable).also { entries[name] = it }
    }

    /** A plain global function that is called with the arguments it gets (`pure`, `gap`, `stepcat`). */
    fun function(names: List<String>, impl: (List<Any?>) -> Any?): List<Registered> = names.map { name ->
        Registered(listOf(name), 0, true, false, { it.innerJoin() }, impl, kind = RegisteredKind.FUNCTION).also { entries[name] = it }
    }

    /** A global that takes any number of arguments; as a method ([method] true) it puts the receiver first: `a.stack(b)` is `stack(a, b)`. */
    fun variadic(names: List<String>, method: Boolean = true, impl: (List<Any?>) -> Any?): List<Registered> = names.map { name ->
        Registered(listOf(name), 0, true, false, { it.innerJoin() }, impl, kind = RegisteredKind.VARIADIC, hasMethod = method)
            .also { entries[name] = it }
    }

    /** A global with its own function for the method form: `name(args)` is [global] and `pat.name(args)` is [method]. Neither is patternified. */
    fun dual(names: List<String>, global: (List<Any?>) -> Any?, method: (Pattern, List<Any?>) -> Any?): List<Registered> = names.map { name ->
        Registered(listOf(name), 0, true, false, { it.innerJoin() }, global, kind = RegisteredKind.FUNCTION, hasMethod = true, methodImpl = method)
            .also { entries[name] = it }
    }

    /** A global that is a value, not a function (`silence`). */
    fun constant(names: List<String>, value: Any) {
        for (name in names) constants[name] = value
    }

    fun lookup(name: String): Registered? = entries[name]

    /** The names of all registered functions. */
    fun names(): Set<String> = entries.keys.toSet()

    /** The globals that are plain values. */
    fun constants(): Map<String, Any> = constants.toMap()

    /** Calls the global function [name] with [args]. */
    fun call(name: String, args: List<Any?>): Any? =
        (entries[name] ?: throw CosicError("unknown function '$name'")).global(args)
}

/** `pat[name](...args)`: calls a registered function as a method of this pattern. */
internal fun Pattern.callMethod(name: String, vararg args: Any?): Pattern {
    val registered = Registry.lookup(name) ?: throw CosicError("unknown method '$name'")
    return registered.method(this, args.asList())
}

// ---- shorthands for registering functions of 1 to 5 parameters, the pattern being the last --------------------------

internal fun Registry.reg1(vararg names: String, keep: Boolean = false, alignable: Boolean = false, f: (Pattern) -> Any?): List<Registered> =
    register(names.asList(), 1, true, keep, alignable = alignable) { a -> f(a[0] as Pattern) }

internal fun Registry.reg2(
    vararg names: String,
    patternify: Boolean = true,
    keep: Boolean = false,
    join: (Pattern) -> Pattern = { it.innerJoin() },
    alignable: Boolean = false,
    f: (Any?, Pattern) -> Any?,
): List<Registered> = register(names.asList(), 2, patternify, keep, join, alignable) { a -> f(a[0], a[1] as Pattern) }

internal fun Registry.reg3(
    vararg names: String,
    patternify: Boolean = true,
    keep: Boolean = false,
    join: (Pattern) -> Pattern = { it.innerJoin() },
    alignable: Boolean = false,
    f: (Any?, Any?, Pattern) -> Any?,
): List<Registered> = register(names.asList(), 3, patternify, keep, join, alignable) { a -> f(a[0], a[1], a[2] as Pattern) }

internal fun Registry.reg4(
    vararg names: String,
    patternify: Boolean = true,
    keep: Boolean = false,
    join: (Pattern) -> Pattern = { it.innerJoin() },
    alignable: Boolean = false,
    f: (Any?, Any?, Any?, Pattern) -> Any?,
): List<Registered> = register(names.asList(), 4, patternify, keep, join, alignable) { a -> f(a[0], a[1], a[2], a[3] as Pattern) }

internal fun Registry.reg5(
    vararg names: String,
    patternify: Boolean = true,
    keep: Boolean = false,
    join: (Pattern) -> Pattern = { it.innerJoin() },
    alignable: Boolean = false,
    f: (Any?, Any?, Any?, Any?, Pattern) -> Any?,
): List<Registered> = register(names.asList(), 5, patternify, keep, join, alignable) { a -> f(a[0], a[1], a[2], a[3], a[4] as Pattern) }

/** `stepRegister`: like `reg2`, but the patterned arguments are joined by steps (`stepJoin`) instead of `innerJoin`. */
internal fun Registry.stepReg2(
    vararg names: String,
    patternify: Boolean = true,
    keep: Boolean = false,
    f: (Any?, Pattern) -> Any?,
): List<Registered> = reg2(*names, patternify = patternify, keep = keep, join = { it.stepJoin() }, f = f)
