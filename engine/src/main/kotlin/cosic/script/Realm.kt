package cosic.script

import cosic.EvalEffects
import cosic.SoundLibrary
import cosic.detachedEffects
import cosic.pattern.CosicError
import cosic.pattern.JsFn
import cosic.pattern.jsToString
import cosic.script.js.ErrorKind
import cosic.script.js.JsArray
import cosic.script.js.JsErrorObject
import cosic.script.js.JsFunction
import cosic.script.js.JsObject
import cosic.script.js.JsThrow
import cosic.script.js.NativeFunction
import cosic.script.js.PropFlags
import cosic.script.js.ScriptLimitExceeded
import cosic.script.js.describe
import cosic.script.js.describeThrown
import cosic.script.js.toStr
import cosic.script.js.stdlib.installStdlib

/** How serious a line the program wrote to the console is. */
internal enum class LogLevel { DEBUG, INFO, WARNING, ERROR }

/**
 * What a program can reach outside of the language: the tempo, the console, the clock, a timer, the sliders of the
 * editor. The host bridge ([Realm.host]) is what the standard library and the catalogue talk to.
 */
internal interface ScriptHost {
    /** Cycles per second. */
    var cps: Double

    fun log(message: String, level: LogLevel = LogLevel.INFO)

    /** Seconds on the clock the program sees (`Date.now()` is derived from it; patterns use cycles). */
    fun nowSeconds(): Double

    /** Runs [task] after [delayMillis] milliseconds. The returned function cancels it. */
    fun runLater(delayMillis: Long, task: () -> Unit): () -> Unit

    fun sliderGet(id: String): Double?

    fun sliderSet(id: String, value: Double)

    /** The sounds the program can reach: what `samples`, `soundAlias`, `getDuration` and the like read and register. */
    val library: SoundLibrary

    /**
     * What the engine shares with the outside world: the inputs, the outputs, the console and the report of the evaluation in progress.
     * A host that has none gets inert ones shared by all such hosts.
     */
    val effects: EvalEffects get() = detachedEffects
}

/**
 * What one run of the interpreter may use before it is stopped with `Script took too long`: [steps] statements, calls
 * and loop iterations, [millis] of wall-clock time, [maxDepth] nested calls (`RangeError: Maximum call stack size
 * exceeded`). Each entry into the interpreter from outside gets a fresh budget; calls made from inside share it.
 */
internal class Limits(
    val steps: Long = DEFAULT_STEPS,
    val millis: Long = DEFAULT_MILLIS,
    val maxDepth: Int = DEFAULT_DEPTH,
) {
    companion object {
        const val DEFAULT_STEPS = 50_000_000L
        const val DEFAULT_MILLIS = 10_000L
        const val DEFAULT_DEPTH = 1500
    }
}

/**
 * `async` and `await`. The interpreter has no promises of its own; a library that has them replaces
 * [Realm.async]. The default makes the language usable without one: an async function runs to the end when it is called
 * and returns what it returns, `await` returns its operand. (Strudel code awaits `samples(...)`, `midin(...)` and
 * the like, whose values the host has already made available.)
 */
internal interface AsyncSupport {
    /** Runs the body of an async function that was just called; returns the value of the call. */
    fun runAsync(body: () -> Any?): Any?

    /** `await value`. */
    fun await(value: Any?): Any?
}

internal object SynchronousAsync : AsyncSupport {
    override fun runAsync(body: () -> Any?): Any? = body()

    override fun await(value: Any?): Any? = value
}

/**
 * The installers that fill a new realm with the standard library (`Math`, `Array`, `Object`, `Number`, `String`,
 * `JSON`, `console`...), in order. The core of the language (Object.prototype, Function.prototype, the errors) is
 * installed first by [installCoreIntrinsics]. Whoever adds a library file appends its `install` function here.
 */
private val BASE_INSTALLERS: List<(Realm) -> Unit> = listOf(::installStdlib)

/**
 * One world of JavaScript: a global object, the prototypes of the built-ins, and the interpreter that runs programs in
 * it. A session (an editor, an engine) has one realm for its lifetime, evaluates one program after the other in it, and
 * calls the functions those programs made from any thread (the audio thread runs callbacks of patterns).
 *
 * Threading: a program is evaluated by one thread at a time (the caller holds a lock), but the functions it made may be
 * called from other threads meanwhile. Nothing is static: every run has its own [Exec] (step budget, depth), kept per
 * thread; the objects that are shared by programs (the global object, the prototypes) have concurrent tables.
 *
 * `Realm(host)` is the bare language plus the standard library; the pattern catalogue and the REPL's functions are
 * installed on top by the host bridge.
 */
internal class Realm(val host: ScriptHost) {
    val objectPrototype = JsObject(null, shared = true)
    val functionPrototype = JsObject(objectPrototype, shared = true)
    val arrayPrototype = JsObject(objectPrototype, shared = true)
    val stringPrototype = JsObject(objectPrototype, shared = true)
    val numberPrototype = JsObject(objectPrototype, shared = true)
    val booleanPrototype = JsObject(objectPrototype, shared = true)

    /** `Error.prototype` is `errorPrototypes[ErrorKind.ERROR]`; the other kinds inherit from it. */
    val errorPrototypes: Map<ErrorKind, JsObject>
    val errorPrototype: JsObject

    /** The prototype of pattern objects (`Pattern.prototype`); the host bridge fills it. */
    val patternPrototype = JsObject(objectPrototype, shared = true)

    /** The global object: where names that no scope of the program declares are looked up. */
    val global = JsObject(objectPrototype, shared = true)

    /** The budget of a run that starts now (an evaluation of a program). Change it before the run. */
    @Volatile
    var limits: Limits = Limits()

    /**
     * The budget of a call into the program from outside (the scheduler running the callback of a pattern, native code
     * calling a function it was given) when no run is going on in the thread; [limits] when null. A callback that runs
     * on the audio thread has far less time than an evaluation, so a host sets this.
     */
    @Volatile
    var callbackLimits: Limits? = null

    @Volatile
    var async: AsyncSupport = SynchronousAsync

    internal val interpreter = Interpreter(this)

    /** The run this thread is in the middle of, or null. */
    private val currentExec = ThreadLocal<Exec?>()

    init {
        val base = JsObject(objectPrototype, shared = true)
        errorPrototype = base
        errorPrototypes = ErrorKind.entries.associateWith { if (it == ErrorKind.ERROR) base else JsObject(base, shared = true) }
        installCoreIntrinsics(this)
        for (install in BASE_INSTALLERS) install(this)
    }

    // ---- making values -----------------------------------------------------------------------------------------------

    fun newObject(): JsObject = JsObject(objectPrototype)

    /**
     * An object that programs share and may extend while other threads read it: a namespace such as `Math`, a prototype.
     * Its table is concurrent (see [JsObject]).
     */
    fun newSharedObject(prototype: JsObject? = objectPrototype): JsObject = JsObject(prototype, shared = true)

    /** An array holding a copy of [items]. */
    fun newArray(items: List<Any?>): JsArray = JsArray(this, ArrayList(items))

    /** An error object of class [kind] with [message]. */
    fun newError(kind: ErrorKind, message: String): JsObject {
        val e = JsErrorObject(errorPrototypes.getValue(kind))
        e.defineOwn("message", message, PropFlags.HIDDEN)
        e.defineOwn("stack", if (message.isEmpty()) kind.jsName else "${kind.jsName}: $message", PropFlags.HIDDEN)
        return e
    }

    /** The throw of an error of class [kind]; `throw realm.typeError("x is not a function")` in native code. */
    fun error(kind: ErrorKind, message: String): JsThrow = JsThrow(newError(kind, message))

    fun typeError(message: String): JsThrow = error(ErrorKind.TYPE, message)

    fun rangeError(message: String): JsThrow = error(ErrorKind.RANGE, message)

    fun referenceError(message: String): JsThrow = error(ErrorKind.REFERENCE, message)

    fun syntaxError(message: String): JsThrow = error(ErrorKind.SYNTAX, message)

    /** A native function of [length] parameters: `impl(thisArg, args)`. */
    fun function(name: String, length: Int, impl: (thisArg: Any?, args: List<Any?>) -> Any?): NativeFunction =
        NativeFunction(this, name, length, { t, a, _ -> impl(t, a) })

    /** A native function that also gets the call site, to report errors where the program called it. */
    fun functionAt(name: String, length: Int, impl: (thisArg: Any?, args: List<Any?>, site: Node?) -> Any?): NativeFunction =
        NativeFunction(this, name, length, impl)

    /** Defines [name] on [target] as a method: a native function, replaceable and not enumerable like the methods of a built-in. */
    fun defineMethod(target: JsObject, name: String, length: Int, impl: (thisArg: Any?, args: List<Any?>) -> Any?): NativeFunction {
        val f = function(name, length, impl)
        target.defineOwn(name, f, PropFlags.HIDDEN)
        return f
    }

    /** A global variable (not enumerable, like the built-ins). */
    fun defineGlobal(name: String, value: Any?) {
        global.defineOwn(name, value, PropFlags.HIDDEN)
    }

    fun defineGlobalFunction(name: String, length: Int, impl: (thisArg: Any?, args: List<Any?>) -> Any?): NativeFunction {
        val f = function(name, length, impl)
        defineGlobal(name, f)
        return f
    }

    /**
     * A constructor: [call] runs for `Name(...)`, [construct] for `new Name(...)`. Its `prototype` is [prototype], whose
     * `constructor` it becomes. The function is not installed anywhere.
     */
    fun constructor(
        name: String,
        length: Int,
        prototype: JsObject,
        call: (thisArg: Any?, args: List<Any?>) -> Any?,
        construct: (args: List<Any?>) -> Any?,
    ): NativeFunction {
        val f = NativeFunction(this, name, length, { t, a, _ -> call(t, a) }, construct, shared = true)
        f.defineOwn("prototype", prototype, 0)
        prototype.defineOwn("constructor", f, PropFlags.HIDDEN)
        return f
    }

    // ---- calling -----------------------------------------------------------------------------------------------------

    /** `f.call(thisArg, ...args)` for native code; [f] may be a function of the program or a bare kernel function. */
    fun callFunction(f: Any?, thisArg: Any?, args: List<Any?>): Any? = when (f) {
        is JsFunction -> f.call(thisArg, args)
        is JsFn -> f.call(args)
        else -> throw typeError("${describe(f)} is not a function")
    }

    /** `new f(...args)` for native code. */
    fun construct(f: Any?, args: List<Any?>): Any? {
        if (f !is JsFunction || !f.isConstructor) throw typeError("${describe(f)} is not a constructor")
        return f.construct(args)
    }

    // ---- running programs --------------------------------------------------------------------------------------------

    /**
     * Runs [code] as the body of a function (no transpiler passes): a top-level `return` is allowed and gives the value
     * of the run, `let`/`const`/`var`/`function` at the top level are local to the run, `this` is `undefined`. Parse
     * errors, and uncaught errors of the program, are thrown as [CosicError] with the range they came from.
     */
    fun runRaw(code: String): Any? = runProgram(parseFunctionBody(code).program, code)

    /**
     * Runs a parsed (and transpiled) program, which is resolved here. The program is the body of a function: a
     * top-level `return` (the last expression, once the transpiler has made it one) gives the value of the run.
     */
    fun runProgram(program: Program, source: String): Any? {
        Resolver(source).resolveProgram(program)
        return withExec { exec -> interpreter.runProgramBody(program, exec) }
    }

    /**
     * Runs [block] in the run this thread is in, or in a new one when it is in none: with a fresh budget ([limits], or
     * [callbackLimits] when [callback] is set), and with an uncaught throw turned into a [CosicError] (the boundary of
     * the interpreter). The interpreter and the functions of the program enter through here.
     */
    internal fun <T> withExec(callback: Boolean = false, block: (Exec) -> T): T {
        val current = currentExec.get()
        if (current != null) return block(current)
        val lim = (if (callback) callbackLimits else null) ?: limits
        val now = System.nanoTime()
        val budget = if (lim.millis >= Long.MAX_VALUE / 1_000_000L) Long.MAX_VALUE else lim.millis * 1_000_000L
        val deadline = if (budget > Long.MAX_VALUE - now) Long.MAX_VALUE else now + budget
        val exec = Exec(lim.steps, deadline, lim.maxDepth)
        currentExec.set(exec)
        try {
            return block(exec)
        } catch (e: JsThrow) {
            throw toCosicError(e)
        } catch (e: ScriptLimitExceeded) {
            throw CosicError(e.reason, e.range)
        } catch (e: StackOverflowError) {
            throw CosicError("Maximum call stack size exceeded")
        } finally {
            currentExec.remove()
        }
    }

    /** The [CosicError] an uncaught throw of the program becomes: the `message` of an error object, else the thrown value as text; with the range of the throw. */
    internal fun toCosicError(e: JsThrow): CosicError {
        val v = e.value
        val text = if (v is JsObject && v.className == "Error") {
            val message = v.get("message")
            val m = if (message is String) message else jsToString(message)
            m.ifEmpty { describeThrown(v) }
        } else if (v is JsObject) {
            try {
                toStr(v)
            } catch (t: JsThrow) {
                "[object Object]"
            }
        } else {
            jsToString(v)
        }
        return CosicError(text, e.range)
    }

    /** The error object a program sees when it catches [e], a [CosicError] thrown by native (kernel) code. */
    internal fun errorFromCosic(e: CosicError): JsObject = newError(ErrorKind.ERROR, e.message)

    /** What `catch (e)` binds when the Kotlin exception [t] is caught, or null when it is not a thing a program can catch. */
    internal fun caught(t: Throwable): Any? = when (t) {
        is JsThrow -> t.value
        is CosicError -> errorFromCosic(t)
        is StackOverflowError -> newError(ErrorKind.RANGE, "Maximum call stack size exceeded")
        else -> null
    }

    internal fun isCatchable(t: Throwable): Boolean = t is JsThrow || t is CosicError || t is StackOverflowError
}
