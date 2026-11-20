package cosic.script.next

import cosic.pattern.Undefined
import cosic.script.next.js.JsArray
import cosic.script.next.js.JsObject
import cosic.script.next.js.toPropertyKey
import cosic.script.next.js.toStr

/** A host that records what it is told and has no clock, no timers and no sliders. */
internal class TestHost : ScriptHost {
    override var cps: Double = 0.5
    val logs = ArrayList<Pair<LogLevel, String>>()
    val sliders = HashMap<String, Double>()

    override fun log(message: String, level: LogLevel) {
        logs.add(level to message)
    }

    override fun nowSeconds(): Double = 0.0

    override fun runLater(delayMillis: Long, task: () -> Unit): () -> Unit = {}

    override fun sliderGet(id: String): Double? = sliders[id]

    override fun sliderSet(id: String, value: Double) {
        sliders[id] = value
    }
}

/**
 * A realm for the tests of the interpreter. The standard library is the business of its own tests; what the interpreter's
 * tests need from it is made here, in the smallest form: `isArray(v)`, `keys(o)` and `hasOwn(o, k)` (`Array.isArray`,
 * `Object.keys`, `Object.prototype.hasOwnProperty.call`) and `Array.prototype.push`.
 */
internal fun testRealm(host: ScriptHost = TestHost()): Realm {
    val realm = Realm(host)
    realm.defineGlobalFunction("isArray", 1) { _, args -> args.getOrNull(0) is JsArray }
    realm.defineGlobalFunction("keys", 1) { _, args ->
        when (val v = args.getOrNull(0)) {
            is JsObject -> realm.newArray(v.ownEnumerableKeys())
            is String -> realm.newArray(List(v.length) { it.toString() })
            else -> realm.newArray(emptyList())
        }
    }
    realm.defineGlobalFunction("hasOwn", 2) { _, args ->
        val o = args.getOrNull(0)
        val key = realm.toPropertyKey(args.getOrElse(1) { Undefined })
        o is JsObject && o.hasOwn(key)
    }
    realm.defineMethod(realm.arrayPrototype, "push", 1) { self, args ->
        val a = self as? JsArray ?: throw realm.typeError("push called on a non-array")
        a.items.addAll(args)
        a.items.size.toDouble()
    }
    return realm
}

/** Runs [code] as a function body in a fresh test realm; the value of its `return`. */
internal fun evalJs(code: String, realm: Realm = testRealm()): Any? = realm.runRaw(code)

/** `String(value)` for assertions, without the library. */
internal fun Realm.text(value: Any?): String = toStr(value)

/** A test global: `name` is a function that calls [impl]. */
internal fun Realm.defineTestFunction(name: String, length: Int = 1, impl: (List<Any?>) -> Any?) {
    defineGlobal(name, function(name, length) { _, args -> impl(args) })
}

/** The own enumerable properties of an object as a map, for assertions. */
internal fun JsObject.toMap(): Map<String, Any?> = ownEnumerableKeys().associateWith { get(it) }
