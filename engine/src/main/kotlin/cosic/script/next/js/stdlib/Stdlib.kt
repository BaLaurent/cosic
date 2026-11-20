package cosic.script.next.js.stdlib

import cosic.pattern.JsFn
import cosic.pattern.Undefined
import cosic.pattern.jsNumberToString
import cosic.script.next.Realm
import cosic.script.next.js.AccessorProp
import cosic.script.next.js.JsArray
import cosic.script.next.js.JsFunction
import cosic.script.next.js.JsObject
import cosic.script.next.js.JsThrow
import cosic.script.next.js.PropFlags
import cosic.script.next.js.isNullish
import cosic.script.next.js.toNumber
import java.util.WeakHashMap
import kotlin.math.truncate

/*
 * The standard library of the script language: the built-ins a Strudel program can use besides the language itself
 * (`Object`, `Array`, `String`, `Number`, `Boolean`, `Math`, `JSON`, `Map`, `Set`, `Date`, `Promise`, the timers, `console`,
 * `parseInt` and the like), installed by every `Realm` after the core intrinsics.
 *
 * Messages follow V8's (Node is the oracle, see `StdlibOracleTest`), the values are the interpreter's (K3 of the parity
 * plan: `Undefined`, `null`, `Boolean`, `Double`, `String`, `JsObject`). There are no symbols, no regular expressions and
 * no typed arrays, so nothing here refers to them; an array has no holes.
 */

/** What the library keeps for one realm: the prototypes it makes, the random generator, the event loop. */
internal class StdRealm(val realm: Realm) {
    val random = java.util.Random()
    lateinit var iteratorPrototype: JsObject
    lateinit var mapPrototype: JsObject
    lateinit var setPrototype: JsObject
    lateinit var datePrototype: JsObject
    lateinit var promisePrototype: JsObject
    lateinit var aggregateErrorPrototype: JsObject
    val loop: EventLoop = EventLoop(realm)

    /** Arrays that `Object.freeze` froze: an array's elements are not properties, so the flag lives here. */
    val frozenArrays: MutableSet<JsArray> = java.util.Collections.newSetFromMap(java.util.Collections.synchronizedMap(WeakHashMap()))

    /** Per-realm state of `console`: the group indentation and the counters. */
    val console = ConsoleState()
}

private val registry = WeakHashMap<Realm, StdRealm>()

/** The library's state of this realm. */
internal val Realm.std: StdRealm
    get() = synchronized(registry) { registry[this] } ?: throw IllegalStateException("the standard library is not installed in this realm")

/** Installs the whole library. Called by the realm after its core intrinsics. */
internal fun installStdlib(realm: Realm) {
    val std = StdRealm(realm)
    synchronized(registry) { registry[realm] = std }
    installObject(realm, std)
    installArray(realm, std)
    installString(realm, std)
    installNumber(realm, std)
    installMath(realm, std)
    installJson(realm, std)
    installCollections(realm, std)
    installDate(realm, std)
    installPromise(realm, std)
    installTimers(realm, std)
    installConsole(realm, std)
}

// ---- arguments --------------------------------------------------------------------------------------------------------

internal fun List<Any?>.arg(i: Int): Any? = if (i < size) this[i] else Undefined

/** `ToIntegerOrInfinity`: NaN is 0, fractions are cut, infinities stay. */
internal fun Realm.toIntegerOrInfinity(v: Any?): Double {
    val d = toNumber(v)
    if (d.isNaN()) return 0.0
    if (d.isInfinite()) return d
    val t = truncate(d)
    return if (t == 0.0) 0.0 else t
}

/** `ToLength`: an integer from 0 to 2^53 - 1. */
internal fun Realm.toLength(v: Any?): Double {
    val n = toIntegerOrInfinity(v)
    return if (n <= 0.0) 0.0 else minOf(n, 9007199254740991.0)
}

/** How V8 words a value that is not what a built-in asked for: `undefined`, `object null`, `number 5`, `string "x"`, `boolean true`, `object`. */
internal fun describeForCall(v: Any?): String = when (v) {
    Undefined -> "undefined"
    null -> "object null"
    is Boolean -> "boolean $v"
    is Double -> "number " + jsNumberToString(v)
    is Number -> "number " + jsNumberToString(v.toDouble())
    is String -> "string \"$v\""
    else -> "object"
}

/** [v] when it is a function (of the program or a kernel one), else the `TypeError` V8 throws for a callback that is none. */
internal fun Realm.callable(v: Any?): Any {
    if (v is JsFunction || v is JsFn) return v
    throw typeError("${describeForCall(v)} is not a function")
}

internal fun Realm.notIterable(v: Any?): JsThrow =
    typeError("${describeForCall(v)} is not iterable (cannot read property Symbol(Symbol.iterator))")

/** The `TypeError` of a method called on `null` or `undefined`: `Array.prototype.map called on null or undefined`. */
internal fun Realm.requireCoercible(self: Any?, what: String) {
    if (isNullish(self)) throw typeError("$what called on null or undefined")
}

/** `obj[key] = value` for a built-in that writes (`Object.assign`, `push`): a refused write is a `TypeError`, with V8's three wordings. */
internal fun Realm.setOrThrow(obj: JsObject, key: String, value: Any?) {
    if (obj.set(key, value, obj)) return
    val p = obj.findProp(key)
    val where = if (obj is JsArray) "[object Array]" else "#<${obj.className}>"
    throw typeError(
        when {
            p is AccessorProp -> "Cannot set property $key of $where which has only a getter"
            p != null -> "Cannot assign to read only property '$key' of object '$where'"
            else -> "Cannot add property $key, object is not extensible"
        },
    )
}

/** `ToObject` as far as the library needs it: primitives become boxes, `null` and `undefined` are refused. */
internal fun Realm.toObject(v: Any?): JsObject = when (v) {
    is JsObject -> v
    is String -> JsBox(stringPrototype, "String", v)
    is Double -> JsBox(numberPrototype, "Number", v)
    is Number -> JsBox(numberPrototype, "Number", v.toDouble())
    is Boolean -> JsBox(booleanPrototype, "Boolean", v)
    null, Undefined -> throw typeError("Cannot convert undefined or null to object")
    else -> newObject()
}

/** The name of a function property of a prototype: the same function under several names (`trimLeft`/`trimStart`). */
internal fun Realm.alias(target: JsObject, name: String, of: String) {
    target.defineOwn(name, target.get(of), PropFlags.HIDDEN)
}

/** [value] as the key list of an array-like (`length` and the elements), for the built-ins that accept any object. */
internal fun Realm.arrayLikeItems(value: Any?): List<Any?> = when (value) {
    is JsArray -> value.items
    is String -> List(value.length) { value[it].toString() }
    is JsObject -> {
        val n = toLength(value.get("length"))
        val count = minOf(n, 16_777_216.0).toInt()
        List(count) { value.get(it.toString()) }
    }
    else -> emptyList()
}
