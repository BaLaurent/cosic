package cosic.kabel

import cosic.pattern.Undefined
import cosic.pattern.jsToNumber
import cosic.pattern.jsToString
import cosic.script.LogLevel
import cosic.script.js.ErrorKind
import cosic.script.js.JsArray
import cosic.script.js.JsFunction
import cosic.script.js.JsObject
import cosic.script.js.NativeFunction
import cosic.script.js.PropFlags
import kotlin.math.cos
import kotlin.math.sin

private fun arg(args: List<Any?>, i: Int): Any? = if (i < args.size) args[i] else Undefined

/** An argument that is `undefined` (or missing) takes [default], as a default parameter does. */
private fun argOr(args: List<Any?>, i: Int, default: Any?): Any? = arg(args, i).let { if (it === Undefined) default else it }

/**
 * Everything a kabel program can name: the node functions and modules of lib.js, `n`, `register`, `module`, `out`, the methods of `Node.prototype` and the
 * values `PI`, `mouseX`, `mouseY`. The parts of kabelsalat that need a browser or custom ugens (`registerNode`, `addUgen`, `compile`, `spawn`, ...) throw
 * `[kabelsalat] <name> is not supported`.
 */
internal fun installKabelGlobals(kr: KabelRealm) {
    val realm = kr.realm
    val proto = kr.nodePrototype

    fun unsupported(name: String): Nothing = throw realm.error(ErrorKind.ERROR, "[kabelsalat] $name is not supported")

    fun method(name: String, length: Int = 0, impl: (KNode, List<Any?>) -> Any?) {
        realm.defineMethod(proto, name, length) { self, args ->
            impl(self as? KNode ?: throw realm.typeError("Node.prototype.$name called on an incompatible receiver"), args)
        }
    }

    // ---- the properties of a node ---------------------------------------------------------------------------------
    proto.defineAccessor(
        "type",
        { self -> (self as? KNode)?.type ?: Undefined },
        { self, v -> (self as? KNode)?.let { it.type = jsToString(v) } },
    )
    proto.defineAccessor(
        "value",
        { self -> if (self is KNode) self.value else Undefined },
        { self, v -> if (self is KNode) self.value = v },
    )
    proto.defineAccessor(
        "ins",
        { self -> (self as? KNode)?.ins ?: Undefined },
        { self, v -> if (self is KNode && v is JsArray) self.ins = v },
    )

    // ---- register: a function that is a global and a method ------------------------------------------------------
    fun registerFn(name: String, fn: (List<Any?>) -> Any?): NativeFunction {
        val f = realm.function(name, 0) { _, args -> fn(args) }
        method(name) { self, args ->
            val all = ArrayList<Any?>(args.size + 1)
            all.add(self)
            all.addAll(args)
            fn(all)
        }
        method("_$name") { self, _ -> self } // bypasses the shortcut of the module
        return f
    }

    fun global(name: String, value: Any?) {
        kr.scopeNames.add(name)
        realm.defineGlobal(name, value)
    }

    /** `registerNode(type)`: the node function of that type, as global [globalName] and as method [type]. */
    fun registerNode(type: String, globalName: String = type): NativeFunction {
        val f = registerFn(type) { args -> kr.getNode(type, args) }
        global(globalName, f)
        return f
    }

    /** `module(name, fn)` (graph.js:175-188): the arguments are made nodes before [fn] runs, and what it returns is made the output of the module. */
    fun module(name: String, fn: (List<Any?>) -> Any?): NativeFunction = registerFn(name) { args ->
        kr.moduleId++
        val parsed = ArrayList<Any?>(args.size)
        for (input in args) {
            val p = kr.parseInput(input, null)
            if (p == null) throw realm.typeError("Cannot read properties of null (reading 'asModuleInput')")
            parsed.add(if (p is KNode) p else Undefined) // an array has no asModuleInput: the argument becomes undefined
        }
        val result = fn(parsed)
        if (result == null) throw realm.typeError("Cannot read properties of null (reading 'asModuleOutput')")
        if (result === Undefined) throw realm.typeError("Cannot read properties of undefined (reading 'asModuleOutput')")
        if (result is KNode) result else Undefined
    }

    fun rejectSchema(schema: Any?) {
        if (schema is JsObject && (schema.has("compile") || schema.has("ugen"))) unsupported("a schema with compile or ugen")
    }

    // ---- node functions -------------------------------------------------------------------------------------------
    for (type in listOf(
        "adsr", "clock", "clockdiv", "distort", "noise", "pink", "brown", "dust", "impulse", "saw", "zaw", "sine", "tri", "pulse", "slide", "lag", "slew",
        "filter", "fold", "seq", "delay", "hold", "midifreq", "midigate", "midivel", "midicc", "cc", "audioin", "log", "exp", "pow", "sin", "cos", "tan",
        "acos", "asin", "atan", "mul", "add", "div", "sub", "mod", "abs", "round", "clamp", "floor", "ceil", "sign", "min", "max", "argmin", "argmax",
        "greater", "lower", "xor", "and", "or", "not", "bool", "ifelse", "range", "remap", "thru", "midinote", "src", "output", "poly", "bpf", "pick",
        "clip", "trig", "qf", "exit",
    )) registerNode(type)
    registerNode("lcgnoise", "rng")
    global("gt", realm.global.get("greater"))
    global("lt", realm.global.get("lower"))

    fun fn(name: String): (List<Any?>) -> Any? {
        val f = realm.global.get(name) as JsFunction
        return { args -> f.call(Undefined, args) }
    }
    val log = fn("log")
    val exp = fn("exp")
    val cosFn = fn("cos")
    val sinFn = fn("sin")
    val polyFn = fn("poly")
    val add = fn("add")
    val noise = fn("noise")
    val impulse = fn("impulse")
    val ccFn = fn("cc")

    fun nodeFn(type: String, vararg args: Any?): Any? = kr.getNode(type, args.toList())

    val pi: Any? = kr.n(Math.PI)
    global("PI", pi)

    // ---- modules (graph.js `module`): the arguments are nodes --------------------------------------------------
    fun adsrOf(gate: Any?, a: Any?, d: Any?, s: Any?, r: Any?): Any? {
        if (gate !is KNode) throw realm.typeError("gate.adsr is not a function")
        return kr.invoke(gate, "adsr", listOf(a, d, s, r))
    }
    global("ar", module("ar") { a -> adsrOf(argOr(a, 0, 0.0), argOr(a, 1, 0.02), 0.0, 1.0, argOr(a, 2, 0.1)) })
    global("ad", module("ad") { a -> adsrOf(argOr(a, 0, 0.0), argOr(a, 1, 0.02), argOr(a, 2, 0.1), 0.0, argOr(a, 2, 0.1)) })
    global(
        "perc",
        module("perc") { a ->
            if (arg(a, 0) === Undefined) throw realm.typeError("Cannot read properties of undefined (reading 'adsr')")
            adsrOf(arg(a, 0), 0.0, 0.0, 1.0, arg(a, 1))
        },
    )
    val filter = realm.global.get("filter") as JsFunction
    global("lpf", module("lpf") { a -> filter.call(Undefined, a) })
    global(
        "hpf",
        module("hpf") { a ->
            val input = arg(a, 0)
            // `input.sub(input.lpf(...))`: the method is looked up before its argument is made
            if (input == null || input === Undefined) throw realm.typeError("Cannot read properties of ${if (input == null) "null" else "undefined"} (reading 'sub')")
            val lpf = kr.invoke(input, "lpf", listOf(arg(a, 1), argOr(a, 2, 0.0)))
            kr.invoke(input, "sub", listOf(lpf))
        },
    )
    global("lfnoise", module("lfnoise") { a -> kr.invoke(noise(emptyList()), "hold", listOf(impulse(listOf(arg(a, 0))))) })
    global("bipolar", module("bipolar") { a -> kr.invoke(kr.invoke(kr.n(arg(a, 0)), "mul", listOf(2.0)), "sub", listOf(1.0)) })
    global("unipolar", module("unipolar") { a -> kr.invoke(kr.invoke(kr.n(arg(a, 0)), "add", listOf(1.0)), "div", listOf(2.0)) })
    global(
        "rangex",
        module("rangex") { a ->
            val logmin = log(listOf(arg(a, 1)))
            val range = kr.invoke(log(listOf(arg(a, 2))), "sub", listOf(logmin))
            val uni = kr.invoke(arg(a, 0), "unipolar", emptyList())
            val l = kr.invoke(kr.invoke(uni, "mul", listOf(range)), "add", listOf(logmin))
            exp(listOf(l))
        },
    )
    global(
        "pan",
        module("pan") { a ->
            // (pos + 1) / 2 * PI / 2 = (pos + 1) * PI * 0.25
            val pos = kr.invoke(kr.invoke(kr.n(arg(a, 1)), "add", listOf(1.0)), "mul", listOf(pi, 0.25))
            kr.invoke(arg(a, 0), "mul", listOf(kr.realm.newArray(listOf(cosFn(listOf(pos)), sinFn(listOf(pos))))))
        },
    )
    val mouseX = module("mouseX") { ccFn(listOf("mouseX")) }
    val mouseY = module("mouseY") { ccFn(listOf("mouseY")) }
    global("mouseX", mouseX.call(Undefined, emptyList()))
    global("mouseY", mouseY.call(Undefined, emptyList()))

    // ---- registered functions that are not modules ------------------------------------------------------------------
    global("time", registerFn("time") { a -> kr.newNode("time", arg(a, 0)) })
    for (name in listOf("raw", "bytebeat", "floatbeat")) {
        // the graph is made; compiling it fails, as the code these nodes emit does not run in the worklet (see KabelCompiler)
        val type = if (name == "raw") "raw" else "bytebeat"
        global(name, registerFn(name) { a -> kr.newNode(type, arg(a, 1)).withIns(listOf(kr.n(arg(a, 0)))) })
    }
    global(
        "fork",
        registerFn("fork") { a ->
            val input = arg(a, 0)
            if (input !is KNode) throw realm.typeError("input.clone is not a function")
            val times = jsToNumber(argOr(a, 1, 1.0)).let { if (it.isNaN() || it < 0) 0 else minOf(it, 1000.0).toInt() }
            polyFn(List(times) { kr.invoke(input, "clone", emptyList()) })
        },
    )
    for ((name, kind) in listOf("qlpf" to 0.0, "qhpf" to 1.0, "qbpf" to 2.0, "qnf" to 3.0, "qapf" to 3.0)) { // qapf is a notch in kabelsalat too
        global(name, registerFn(name) { a -> kr.invoke(arg(a, 0), "qf", listOf(kind, arg(a, 1), argOr(a, 2, 10.0))) })
    }
    global(
        "split",
        registerFn("split") { a ->
            val input = arg(a, 0)
            val f = arg(a, 1)
            if (f !is JsFunction) throw realm.typeError("fn is not a function")
            val type = (input as? KNode)?.type ?: if (input == null || input === Undefined) throw realm.typeError("Cannot read properties of ${if (input == null) "null" else "undefined"} (reading 'type')") else Undefined
            if (type != POLY) f.call(Undefined, listOf(realm.newArray(listOf(input)))) else f.call(Undefined, listOf((input as KNode).ins))
        },
    )
    global(
        "mix",
        registerFn("mix") { a ->
            val input = arg(a, 0)
            var channels = argOr(a, 1, 1.0)
            if (channels != 1.0 && channels != 2.0) {
                channels = 2.0
                kr.host.log("mix only supports 1 or 2 channels atm.. falling back to 2", LogLevel.WARNING)
            }
            if (input == null || input === Undefined) throw realm.typeError("Cannot read properties of ${if (input == null) "null" else "undefined"} (reading 'type')")
            if (!isPoly(input)) {
                input
            } else if (channels == 2.0) {
                val ins = polyIns(input)
                val panned = ArrayList<Any?>(ins.size)
                for ((i, inlet) in ins.withIndex()) {
                    // channels are fixed: this is worked out here, not in the graph
                    val pos = (i.toDouble() / (ins.size - 1)) * 2 - 1
                    val deg = ((pos + 1) * Math.PI) / 4
                    panned.add(kr.invoke(inlet, "mul", listOf(realm.newArray(listOf(cos(deg), sin(deg))))))
                }
                add(panned)
            } else {
                val ins = polyIns(input)
                for (inlet in ins) if (inlet !is KNode) throw realm.typeError("inlet.inherit is not a function")
                kr.newNode("mix").withIns(ins)
            }
        },
    )
    // `scope` calls a free `getNode` that does not exist in a local scope (lib.js:1261-1267)
    global("scope", registerFn("scope") { throw realm.referenceError("getNode is not defined") })

    // ---- the plain functions ---------------------------------------------------------------------------------------
    val n = realm.function("n", 1) { _, a -> kr.n(arg(a, 0)) }
    global("n", n)
    global("B", n)
    global("_", n)
    global("node", realm.function("node", 2) { _, a -> kr.newNode(jsToString(arg(a, 0)), arg(a, 1)) })
    global("getNode", realm.function("getNode", 1) { _, a -> kr.getNode(jsToString(arg(a, 0)), a.drop(1)) })
    global("polyType", POLY)
    global("outputType", EXIT)
    global("feedback", realm.function("feedback", 1) { _, a -> add(listOf(arg(a, 0))) })
    method("feedback", 1) { self, a -> kr.invoke(self, "add", listOf(arg(a, 0))) }
    global(
        "register",
        realm.function("register", 3) { _, a ->
            val name = jsToString(arg(a, 0))
            val f = arg(a, 1)
            if (f !is JsFunction) throw realm.typeError("fn is not a function")
            rejectSchema(arg(a, 2))
            realm.defineMethod(proto, name, 0) { self, args ->
                val all = ArrayList<Any?>(args.size + 1)
                all.add(self)
                all.addAll(args)
                f.call(Undefined, all)
            }
            realm.defineMethod(proto, "_$name", 0) { self, _ -> self }
            f
        },
    )
    global(
        "module",
        realm.function("module", 3) { _, a ->
            val name = jsToString(arg(a, 0))
            val f = arg(a, 1)
            if (f !is JsFunction) throw realm.typeError("fn is not a function")
            rejectSchema(arg(a, 2))
            module(name) { parsed -> f.call(Undefined, parsed) }
        },
    )
    global(
        "out",
        realm.function("out", 1) { _, a ->
            val input = arg(a, 0)
            val channels = argOr(a, 1, realm.newArray(listOf(0.0, 1.0)))
            kr.collector?.add(kr.invoke(input, "output", listOf(channels)))
            input
        },
    )
    method("out", 1) { self, a ->
        kr.collector?.add(kr.invoke(self, "output", listOf(argOr(a, 0, realm.newArray(listOf(0.0, 1.0))))))
        self
    }
    global("audio", realm.newObject())
    global("repl", realm.newObject())

    // the Node constructor
    global(
        "Node",
        realm.constructor(
            "Node", 2, proto,
            { _, a -> kr.newNode(jsToString(arg(a, 0)), arg(a, 1)) },
            { a -> kr.newNode(jsToString(arg(a, 0)), arg(a, 1)) },
        ),
    )

    // ---- the methods of Node.prototype (graph.js) ------------------------------------------------------------------
    method("inherit", 1) { self, _ -> self } // carries metadata for the graph view
    method("withIns", 1) { self, a -> self.withIns(a) }
    method("apply", 1) { self, a -> (arg(a, 0) as? JsFunction ?: throw realm.typeError("fn is not a function")).call(Undefined, listOf(self)) }
    method("apply2", 1) { self, a -> (arg(a, 0) as? JsFunction ?: throw realm.typeError("fn is not a function")).call(Undefined, listOf(self, self)) }
    method("over", 1) { self, a ->
        val f = arg(a, 0) as? JsFunction ?: throw realm.typeError("fn is not a function")
        f.call(Undefined, listOf(self))
        throw realm.referenceError("add is not defined") // graph.js calls an `add` that only the library module has
    }
    method("clone") { self, _ -> kr.newNode(self.type, self.value).withIns(self.inputs) }
    method("map", 1) { self, a ->
        val f = arg(a, 0) as? JsFunction ?: throw realm.typeError("fn is not a function")
        if (self.type != POLY) {
            f.call(Undefined, listOf(self))
        } else {
            for ((i, item) in self.inputs.withIndex()) f.call(Undefined, listOf(item, i.toDouble(), self.ins))
            throw realm.referenceError("poly is not defined") // likewise
        }
    }
    method("channel", 1) { self, a ->
        if (self.type != POLY) {
            self
        } else {
            val items = self.inputs
            val index = jsToNumber(arg(a, 0)) % items.size
            if (index == Math.floor(index) && index >= 0 && index < items.size) items[index.toInt()] else Undefined
        }
    }
    method("select", 1) { self, a ->
        val type = jsToString(arg(a, 0))
        fun search(node: Any?, depth: Int): Any? {
            if (depth > MAX_SELECT_DEPTH) throw realm.rangeError("Maximum call stack size exceeded")
            val items = (node as? KNode)?.inputs ?: return Undefined
            for (input in items) {
                if (input is KNode && input.type == type) return input
                if (input !is KNode) throw realm.typeError("input.select is not a function")
                val upper = search(input, depth + 1)
                if (upper !== Undefined) return upper
            }
            return Undefined
        }
        search(self, 0)
    }
    method("debug", 1) { self, a ->
        val f = arg(a, 0)
        val shown = if (f is JsFunction) f.call(Undefined, listOf(self)) else self
        kr.host.log(if (shown is KNode) "Node { type: '${shown.type}' }" else jsToString(shown), LogLevel.INFO)
        self
    }
    method("asModuleInput", 3) { self, _ -> self }
    method("asModuleOutput", 2) { self, _ -> self }

    // ---- what kabelsalat has and this port does not ---------------------------------------------------------------------
    for (name in listOf("registerNode", "registerUgen", "addUgen", "evaluate", "compile", "exportModule", "getInletName")) {
        global(name, realm.function(name, 0) { _, _ -> unsupported(name) })
    }
    realm.global.defineAccessor("nodeRegistry", { unsupported("nodeRegistry") }, null, PropFlags.CONFIGURABLE)
    kr.scopeNames.add("nodeRegistry")
    for (name in listOf("flatten", "toObject", "stringify", "spawn", "compile")) method(name) { _, _ -> unsupported(name) }

    // `dfs(node, fn, visited)` of graph.js: replaces every node of the graph by what `fn(node, visited)` returns, inputs first-seen first
    fun dfs(node0: Any?, f: JsFunction, visited: JsArray): Any? {
        val node = f.call(Undefined, listOf(node0, visited))
        visited.items.add(node)
        if (node !is KNode) throw realm.typeError("Cannot read properties of ${if (node == null) "null" else "undefined"} (reading 'ins')")
        val mapped = node.inputs.map { input -> if (visited.items.any { it === input }) input else dfs(input, f, visited) }
        node.ins = realm.newArray(mapped)
        return node
    }
    method("dfs", 2) { self, a ->
        val f = arg(a, 0) as? JsFunction ?: throw realm.typeError("fn is not a function")
        dfs(self, f, arg(a, 1) as? JsArray ?: realm.newArray(emptyList()))
    }
}

private const val MAX_SELECT_DEPTH = 1000
