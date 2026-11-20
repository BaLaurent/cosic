package cosic.script.catalogue

import cosic.EvalEffects
import cosic.SourceRange
import cosic.pattern.CosicError
import cosic.pattern.Pattern
import cosic.pattern.Registry
import cosic.pattern.Undefined
import cosic.script.catalogue.registerKernel
import cosic.script.Bridge
import cosic.script.Node
import cosic.script.Realm
import cosic.script.ScriptHost
import cosic.script.bridge
import cosic.script.js.PropFlags

/*
 * The catalogue: everything a program can call that is not part of the language itself (K4 of the parity plan).
 *
 * Functions of patterns are registered in the pattern kernel's `Registry` by the category files
 * (`script/catalogue/<Category>.kt`, each with an `internal fun register<Category>()`); the bridge makes every entry a
 * global and a method of `Pattern.prototype`. What is not a function of patterns (`samples`, `slider`, `midin`, ...) is
 * defined with a [CatalogueBuilder] in the category's `register` function:
 *
 *     internal fun registerMidi() = catalogue("Midi") {
 *         function(listOf("midin"), 1) { ctx -> ... }
 *     }
 *
 * [Catalogue.installAll] runs the category functions once, in the fixed order of K4, and [Catalogue.install] gives a realm
 * its globals. A later definition of a name replaces an earlier one, as `register` does in Strudel. Within a realm the order
 * is: fallbacks, then the entries of the `Registry`, then the definitions of the builders (a builder's definition replaces
 * a `Registry` entry of the same name).
 *
 * What the reference of the app says about a function is not defined here: it is Strudel's own JSDoc text, see
 * [cosic.script.reference].
 */

/**
 * One call of a function defined with a [CatalogueBuilder]: [self] is `this` (the pattern for a method), [args] the
 * arguments as the program passed them (values of the program: use [kernelArg] for the kernel's view), [range] the call
 * in the program, which an error thrown by the function is reported at.
 */
internal class CallContext(val bridge: Bridge, val self: Any?, val args: List<Any?>, val range: SourceRange?) {
    val realm: Realm get() = bridge.realm

    val host: ScriptHost get() = bridge.realm.host

    /** What the engine shares with the outside world: inputs, outputs, console, the report of the evaluation (see [EvalEffects]). */
    val effects: EvalEffects get() = host.effects

    fun arg(i: Int): Any? = args.getOrElse(i) { Undefined }

    /** The argument [i] as the kernel wants it (see [Bridge.toKernel]). */
    fun kernelArg(i: Int): Any? = bridge.toKernel(arg(i))

    fun fail(message: String): Nothing = throw CosicError(message, range)
}

internal sealed interface Definition {
    val names: List<String>
}

/** A global function. A [fallback] is only used when nothing else defines the name. */
internal class FunctionDefinition(
    override val names: List<String>,
    val length: Int,
    val fallback: Boolean,
    val impl: (CallContext) -> Any?,
) : Definition

/** A method of `Pattern.prototype` that is not a registered function. */
internal class MethodDefinition(override val names: List<String>, val length: Int, val impl: (CallContext) -> Any?) : Definition

/** A global value, made when a realm is installed. */
internal class ValueDefinition(val name: String, val value: (Bridge) -> Any?) : Definition {
    override val names: List<String> get() = listOf(name)
}

/** Collects the definitions of one category; see the description of the file. */
internal class CatalogueBuilder(val category: String) {
    internal val definitions = ArrayList<Definition>()

    /** A global value, made for each realm by [value]. */
    fun global(name: String, value: (Bridge) -> Any?) {
        definitions.add(ValueDefinition(name, value))
    }

    /** A global function; [impl] returns a value of the kernel or of the program, which the bridge converts. */
    fun function(names: List<String>, length: Int, impl: (CallContext) -> Any?) {
        definitions.add(FunctionDefinition(names, length, false, impl))
    }

    /** A global function that is only there until a later category defines the name, the stand-in of a function a later task makes real. */
    fun fallback(names: List<String>, length: Int, impl: (CallContext) -> Any?) {
        definitions.add(FunctionDefinition(names, length, true, impl))
    }

    /** A method of `Pattern.prototype` that is not a registered function. */
    fun method(names: List<String>, length: Int, impl: (CallContext) -> Any?) {
        definitions.add(MethodDefinition(names, length, impl))
    }

    /** A continuous pattern as a global value, `sine`. */
    fun signal(name: String, pattern: Pattern) = global(name) { it.wrap(pattern) }
}

/** Defines the functions of the category [category] with [block], which is a function of its [CatalogueBuilder]. */
internal fun catalogue(category: String, block: CatalogueBuilder.() -> Unit) = Catalogue.define(category, block)

internal object Catalogue {
    private val lock = Any()
    private var installed = false
    private val definitions = ArrayList<Definition>()

    internal fun define(category: String, block: CatalogueBuilder.() -> Unit) {
        val builder = CatalogueBuilder(category).apply(block)
        synchronized(lock) { definitions.addAll(builder.definitions) }
    }

    /**
     * Runs the `register` function of every category, once. The order is K4's: a later category replaces what an earlier
     * one defined. Each task that adds a category adds one line here; a category that does not exist yet is not listed.
     */
    fun installAll() {
        synchronized(lock) {
            if (installed) return
            installed = true
            registerFallbacks()
            registerKernel()
            registerControls()
            registerTime()
            registerOperators()
            registerStepwise()
            registerSampleFunctions()
            registerGenerators()
            registerSignals()
            registerRandom()
            registerPick()
            registerEuclid()
            registerUtil()
            registerTonal()
            registerXen()
            registerSoundfonts()
            registerMondo()
            registerTidal()
            registerLibrary()
            registerHooks()
            registerOsc()
            registerSerial()
            registerMqtt()
            registerInput()
            registerMeta()
            registerMidi()
            registerKabelsalat() // before Language, after everything it could depend on: `K` is a transpiler hook and `worklet` needs only the kernel
            registerLanguage()
        }
    }

    /**
     * Gives [realm] its globals and the methods of its patterns (see the description of the file) and returns its bridge.
     * Calling it again for the same realm brings the realm up to date with what has been registered since.
     */
    fun install(realm: Realm): Bridge {
        installAll()
        val bridge = realm.bridge
        val defs = synchronized(lock) { definitions.toList() }
        for (d in defs) if (d is FunctionDefinition && d.fallback) bind(bridge, d)
        for (name in Registry.names()) Registry.lookup(name)?.let { bridge.installRegistered(it) }
        for ((name, value) in Registry.constants()) realm.defineGlobal(name, bridge.toJs(value))
        for (d in defs) if (!(d is FunctionDefinition && d.fallback)) bind(bridge, d)
        return bridge
    }

    private fun bind(bridge: Bridge, d: Definition) {
        val realm = bridge.realm
        when (d) {
            is ValueDefinition -> realm.defineGlobal(d.name, d.value(bridge))
            is FunctionDefinition -> for (name in d.names) {
                realm.defineGlobal(name, realm.functionAt(name, d.length) { self, args, site -> run(bridge, d.impl, self, args, site) })
            }
            is MethodDefinition -> for (name in d.names) {
                realm.patternPrototype.defineOwn(
                    name,
                    realm.functionAt(name, d.length) { self, args, site -> run(bridge, d.impl, self, args, site) },
                    PropFlags.HIDDEN,
                )
            }
        }
    }

    private fun run(bridge: Bridge, impl: (CallContext) -> Any?, self: Any?, args: List<Any?>, site: Node?): Any? =
        bridge.result(impl(CallContext(bridge, self, args, site?.range)), site)
}

/**
 * The stand-ins: the functions that programs call whose real version a later task provides. They are defined before
 * everything else, so the real version replaces them without anyone deleting the stand-in (the task that makes the real
 * one removes its line here). None is left at the moment.
 */
private fun registerFallbacks() = catalogue("Fallbacks") {}
