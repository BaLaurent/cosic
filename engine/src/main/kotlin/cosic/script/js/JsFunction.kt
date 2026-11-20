package cosic.script.js

import cosic.pattern.JsFn
import cosic.pattern.Undefined
import cosic.script.Node
import cosic.script.Realm
import kotlin.math.max

/**
 * A function object. It is an object (it has properties, `name` and `length` among them, and a prototype) and a
 * [JsFn], so pattern code that receives a function from a program calls it directly with [call] (`call(args)`, `this`
 * is `undefined`).
 *
 * [length] is `func.length`: the parameters before the first default or rest parameter, and it is also the [arity] the
 * kernel sees. `name` and `length` are read-only own properties that are not listed by `Object.keys`.
 *
 * A call from outside a program (the scheduler running a callback, `Array.prototype.map` calling its argument) goes
 * through [call], which starts a new run with its own step budget when none is going on in this thread, and turns an
 * uncaught throw into a [cosic.pattern.CosicError] with the range it came from; a call from inside a program continues
 * the run the caller is in.
 */
internal abstract class JsFunction(
    @JvmField val realm: Realm,
    name: String,
    @JvmField val length: Int,
    prototype: JsObject? = realm.functionPrototype,
    shared: Boolean = false,
) : JsObject(prototype, shared), JsFn {
    @JvmField var name: String = name

    override val className: String get() = "Function"

    override val arity: Int get() = length

    /** `function.call(thisArg, ...args)`. [args] must not be changed by the callee. */
    abstract fun call(thisArg: Any?, args: List<Any?>): Any?

    /**
     * The same call made from the program at [site] (the call expression). A native that wants to report errors at
     * the place of the call overrides this instead of [call]; the default ignores the site.
     */
    open fun callAt(thisArg: Any?, args: List<Any?>, site: Node?): Any? = call(thisArg, args)

    final override fun call(args: List<Any?>): Any? = call(Undefined, args)

    /** Whether `new function(...)` works. */
    open val isConstructor: Boolean get() = false

    /** `new function(...args)`. */
    open fun construct(args: List<Any?>): Any? = throw realm.typeError("${displayName()} is not a constructor")

    /** `String(function)`: the source of a function written in the program, `function name() { [native code] }` for the others. */
    open fun sourceText(): String = "function $name() { [native code] }"

    /** The name as messages show it. */
    fun displayName(): String = name.ifEmpty { "anonymous" }

    override fun getOwnProp(key: String): Prop? {
        val own = super.getOwnProp(key)
        if (own != null) return own
        return when (key) {
            "length" -> Prop(length.toDouble(), PropFlags.READ_ONLY)
            "name" -> Prop(name, PropFlags.READ_ONLY)
            else -> null
        }
    }

    override fun ownKeys(): List<String> {
        val own = super.ownKeys()
        val keys = ArrayList<String>(own.size + 2)
        if ("length" !in own) keys.add("length")
        if ("name" !in own) keys.add("name")
        keys.addAll(own)
        return keys
    }
}

/**
 * A function written in Kotlin. [impl] gets `this`, the arguments and the call site (null when the function is
 * called from native code). A function with a [constructor] works with `new`; the installer then also gives it a
 * `prototype` property.
 */
internal class NativeFunction(
    realm: Realm,
    name: String,
    length: Int,
    private val impl: (thisArg: Any?, args: List<Any?>, site: Node?) -> Any?,
    private val constructor: ((args: List<Any?>) -> Any?)? = null,
    shared: Boolean = false,
) : JsFunction(realm, name, length, realm.functionPrototype, shared) {
    override val isConstructor: Boolean get() = constructor != null

    override fun call(thisArg: Any?, args: List<Any?>): Any? = impl(thisArg, args, null)

    override fun callAt(thisArg: Any?, args: List<Any?>, site: Node?): Any? = impl(thisArg, args, site)

    override fun construct(args: List<Any?>): Any? = constructor?.invoke(args) ?: super.construct(args)
}

/** `function.bind(thisArg, ...args)`. */
internal class BoundFunction(
    realm: Realm,
    val target: JsFunction,
    private val boundThis: Any?,
    private val boundArgs: List<Any?>,
) : JsFunction(realm, "bound " + target.name, max(0, target.length - boundArgs.size)) {
    override val isConstructor: Boolean get() = target.isConstructor

    override fun call(thisArg: Any?, args: List<Any?>): Any? = target.call(boundThis, boundArgs + args)

    override fun callAt(thisArg: Any?, args: List<Any?>, site: Node?): Any? = target.callAt(boundThis, boundArgs + args, site)

    override fun construct(args: List<Any?>): Any? = target.construct(boundArgs + args)

    override fun sourceText(): String = "function () { [native code] }"
}
