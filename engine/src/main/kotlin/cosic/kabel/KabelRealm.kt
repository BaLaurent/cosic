package cosic.kabel

import cosic.pattern.Undefined
import cosic.pattern.jsTypeof
import cosic.script.Limits
import cosic.script.LogLevel
import cosic.script.Realm
import cosic.script.ScriptHost
import cosic.script.js.ErrorKind
import cosic.script.js.JsArray
import cosic.script.js.JsFunction
import cosic.script.js.JsObject
import kotlin.math.max
import cosic.SourceRange
import cosic.pattern.CosicError
import cosic.script.ArrayPattern
import cosic.script.AssignmentPattern
import cosic.script.DeclKind
import cosic.script.Identifier
import cosic.script.Node
import cosic.script.ObjectPattern
import cosic.script.Program
import cosic.script.Property
import cosic.script.RestElement
import cosic.script.VariableDeclaration
import cosic.script.parseFunctionBody

/** The budget of one evaluation of a kabel program: its graph is a few hundred nodes, so a program that needs more than this is a loop that does not end. */
internal const val KABEL_STEPS = 2_000_000L
internal const val KABEL_MILLIS = 500L
internal const val KABEL_DEPTH = 400

/**
 * The world a kabel program runs in: a [Realm] with the standard library and the graph language of kabelsalat (`saw(220).mul(0.5).out()`) in place
 * of Strudel's catalogue, which is `core.evaluate(code, {...core, ...lib, ...compiler, audio, addUgen, repl})` of `SalatRepl` with `localScope`.
 * Programs are run as plain function bodies ([Realm.runRaw]); the nodes they make are [KNode]s. One realm lives as long as the factory that owns it
 * and is used by one thread at a time, so what a program registers (`register('kick', ...)`) stays for the next one, as in Strudel.
 */
internal class KabelRealm(host: ScriptHost, limits: Limits = Limits(KABEL_STEPS, KABEL_MILLIS, KABEL_DEPTH)) {
    val realm: Realm = Realm(host).also { it.limits = limits }
    val host: ScriptHost get() = realm.host

    /** `Node.prototype`: the methods of every node function. */
    val nodePrototype: JsObject = JsObject(realm.objectPrototype)

    /** The counter `module` gives its calls (graph.js:174). */
    var moduleId = 0

    /** The outputs `.out()` collects while a program runs (the `nodes` of `core.evaluate`); null when none is running. */
    var collector: ArrayList<Any?>? = null

    private val logged = HashSet<String>()
    /** The names the program sees without declaring them (`Object.keys(scope)` of `core.evaluate`): a `let` or `const` of one of them at the top is a syntax error. */
    val scopeNames = HashSet<String>()

    init {
        installKabelGlobals(this)
    }

    fun newNode(type: String, value: Any? = Undefined): KNode = KNode(this, type, value)

    /** `n(value)` of graph.js:195-203: an array becomes a poly node, an object is itself, anything else a constant. */
    fun n(value: Any?): Any? = when {
        value is JsArray -> throw realm.referenceError("poly is not defined") // graph.js calls a `poly` that only the library module has
        value == null || (value is JsObject && value !is JsFunction) -> value
        else -> newNode(CONST, value)
    }

    /** Writes a line to the console of the host, once per distinct [message] (what a worklet that warns on every compile would otherwise flood it with). */
    fun logOnce(message: String, level: LogLevel = LogLevel.WARNING) {
        if (logged.add(message)) host.log(message, level)
    }

    /** `core.evaluate(code, scope)`: runs [code] and returns the `exit` node over everything the program sent `.out()`. */
    fun evaluate(code: String): KNode {
        val nodes = ArrayList<Any?>()
        collector = nodes
        try {
            val parsed = parseFunctionBody(code)
            checkRedeclarations(parsed.program)
            realm.runProgram(parsed.program, code)
        } finally {
            collector = null
        }
        return getNode(EXIT, nodes) as KNode
    }

    /**
     * The program is the body of a function whose parameters are the names of the scope, so `let mod = 1` at its top level is `Identifier 'mod' has already been
     * declared` (a `var`, or a declaration inside a function, is fine).
     */
    private fun checkRedeclarations(program: Program) {
        fun check(pattern: Node?) {
            when (pattern) {
                is Identifier -> if (pattern.name in scopeNames) throw CosicError("Identifier '${pattern.name}' has already been declared", SourceRange(pattern.start, pattern.end))
                is ArrayPattern -> pattern.elements.forEach(::check)
                is ObjectPattern -> pattern.properties.forEach { check(if (it is Property) it.value else it) }
                is AssignmentPattern -> check(pattern.left)
                is RestElement -> check(pattern.argument)
                else -> Unit
            }
        }
        for (statement in program.body) {
            if (statement is VariableDeclaration && statement.kind != DeclKind.VAR) statement.declarations.forEach { check(it.id) }
        }
    }

    // ---- graph.js -------------------------------------------------------------------------------------------------

    /** `x.type` of a value that may be null or undefined, which throws the way property access does. */
    private fun typeOfNode(x: Any?): Any? {
        if (x == null) throw realm.typeError("Cannot read properties of null (reading 'type')")
        if (x === Undefined) throw realm.typeError("Cannot read properties of undefined (reading 'type')")
        return if (x is KNode) x.type else Undefined
    }

    /** `Node.parseInput(input, node)` (graph.js:7-33). */
    fun parseInput(input: Any?, node: KNode?): Any? = when {
        input is JsFunction -> {
            if (node == null) throw realm.error(ErrorKind.ERROR, "tried to parse function input without without passing node..")
            input.call(Undefined, listOf(node))
        }
        input is JsObject -> input
        input is Double && !input.isNaN() -> newNode(CONST, input)
        input is String -> newNode(CONST, input)
        else -> {
            if (node == null) throw realm.typeError("Cannot read properties of undefined (reading 'type')")
            host.log("invalid input type \"${jsTypeof(input)}\" for node of type \"${node.type}\", falling back to 0. The input was: ${describeInput(input)}", LogLevel.INFO)
            InvalidInput
        }
    }

    private fun describeInput(input: Any?): String = when (input) {
        null -> "null"
        Undefined -> "undefined"
        else -> input.toString()
    }

    /**
     * `getNode(type, ...args)` (graph.js:55-117): a node of [type] over [rawArgs], with the multichannel expansion of arrays and poly nodes
     * (`node([a, b, c], [x, y])` is `poly(node(a, x), node(b, y), node(c, x))`) and feedback functions (an argument that is a function gets the node
     * being made and returns what to connect, so the node can read its own output).
     */
    fun getNode(type: String, rawArgs: List<Any?>): Any? {
        var maxExpansions = 1
        val args = ArrayList<Any?>(rawArgs.size)
        for (a in rawArgs) {
            var arg = a
            if (arg is JsArray) {
                if (arg.items.size == 1) {
                    args.add(arg.items[0]) // not a poly for one
                    continue
                }
                arg = newNode(POLY).withIns(arg.items)
            }
            if (arg is JsFunction) {
                // a feedback function may itself expand, so ask it once what it returns
                val peek = arg.call(Undefined, listOf(newNode("peek")))
                if (typeOfNode(peek) == POLY) maxExpansions = max((peek as KNode).inputs.size, maxExpansions)
            }
            if (typeOfNode(arg) == POLY) maxExpansions = max((arg as KNode).inputs.size, maxExpansions)
            args.add(arg)
        }

        if (maxExpansions == 1) {
            val next = newNode(type)
            val inputs = ArrayList<Any?>(args.size)
            for (arg in args) inputs.add(parseInput(arg, next))
            return next.withIns(inputs)
        }

        // the exit node is not expanded: every channel is an input of it
        if (type == EXIT) {
            val inputs = ArrayList<Any?>()
            for (arg in args) {
                if (isPoly(arg)) {
                    for (item in polyIns(arg)) inputs.add(requireNode(parseInput(item, null), "Node.parseInput(...).inherit is not a function"))
                } else {
                    inputs.add(arg)
                }
            }
            return newNode(EXIT).withIns(inputs)
        }

        val expanded = ArrayList<Any?>(maxExpansions)
        for (i in 0 until maxExpansions) {
            val cloned = newNode(type)
            val inputs = ArrayList<Any?>(args.size)
            for (arg in args) {
                if (isPoly(arg)) {
                    val ins = polyIns(arg)
                    if (ins.isEmpty()) throw realm.typeError("input.inherit is not a function")
                    inputs.add(requireNode(parseInput(ins[i % ins.size], cloned), "input.inherit is not a function"))
                } else {
                    var parsed = parseInput(arg, cloned) // wired to the clone, for feedback
                    if (isPoly(parsed)) parsed = polyIns(parsed).getOrElse(i) { InvalidInput } // a function that expanded
                    inputs.add(parsed)
                }
            }
            expanded.add(cloned.withIns(inputs))
        }
        return newNode(POLY).withIns(expanded)
    }

    /** `x.inherit(...)` is called on what a poly input parses to: it fails for an invalid input, which is not a node. */
    private fun requireNode(x: Any?, message: String): Any? {
        if (x === InvalidInput) throw realm.typeError(message)
        return x
    }

    /** `target[name](...args)`: how the library's own functions call methods, so that a method a program re-registers is the one they use. */
    fun invoke(target: Any?, name: String, args: List<Any?>): Any? {
        if (target == null) throw realm.typeError("Cannot read properties of null (reading '$name')")
        if (target === Undefined) throw realm.typeError("Cannot read properties of undefined (reading '$name')")
        val f = (target as? JsObject)?.get(name)
        if (f !is JsFunction) throw realm.typeError("${describeTarget(target)}.$name is not a function")
        return f.call(target, args)
    }

    private fun describeTarget(target: Any?): String = when (target) {
        is KNode -> "node"
        is Double -> "(${cosic.pattern.jsToString(target)})"
        is String -> "\"$target\""
        else -> "value"
    }
}
