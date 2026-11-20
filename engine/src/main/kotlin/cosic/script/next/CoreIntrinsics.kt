package cosic.script.next

import cosic.pattern.JsFn
import cosic.pattern.Undefined
import cosic.pattern.jsToString
import cosic.script.next.js.BoundFunction
import cosic.script.next.js.ErrorKind
import cosic.script.next.js.JsArray
import cosic.script.next.js.JsErrorObject
import cosic.script.next.js.JsFunction
import cosic.script.next.js.JsObject
import cosic.script.next.js.NativeFunction
import cosic.script.next.js.PropFlags
import cosic.script.next.js.arrayIndexOf
import cosic.script.next.js.isNullish
import cosic.script.next.js.toNumber
import cosic.script.next.js.toPropertyKey
import cosic.script.next.js.toStr

/*
 * What the language itself needs from its library, installed by every realm before the standard library: the global
 * constants, the methods that the object protocol calls (`Object.prototype.toString`/`valueOf`, which `ToPrimitive`
 * reaches), `Function.prototype.call/apply/bind/toString`, `Array.prototype.join/toString` (what an array is
 * as a string), and the error classes. The rest of the library (`Object.*`, `Array.prototype.map`, `Math`, ...) is the
 * standard library's: it adds to these objects, it does not replace them.
 */

private fun List<Any?>.arg(i: Int): Any? = if (i < size) this[i] else Undefined

internal fun installCoreIntrinsics(realm: Realm) {
    installGlobalValues(realm)
    installObjectPrototype(realm)
    installFunctionPrototype(realm)
    installArrayBasics(realm)
    installErrors(realm)
}

private fun installGlobalValues(realm: Realm) {
    realm.global.defineOwn("globalThis", realm.global, PropFlags.HIDDEN)
    realm.global.defineOwn("undefined", Undefined, 0)
    realm.global.defineOwn("NaN", Double.NaN, 0)
    realm.global.defineOwn("Infinity", Double.POSITIVE_INFINITY, 0)
}

// ---- Object.prototype ---------------------------------------------------------------------------------------------

private fun installObjectPrototype(realm: Realm) {
    val proto = realm.objectPrototype
    realm.defineMethod(proto, "toString", 0) { self, _ ->
        when (self) {
            null -> "[object Null]"
            Undefined -> "[object Undefined]"
            is JsObject -> "[object ${self.className}]"
            is String -> "[object String]"
            is Number -> "[object Number]"
            is Boolean -> "[object Boolean]"
            else -> "[object Object]"
        }
    }
    realm.defineMethod(proto, "valueOf", 0) { self, _ ->
        if (isNullish(self)) throw realm.typeError("Cannot convert undefined or null to object")
        self
    }
    realm.defineMethod(proto, "hasOwnProperty", 1) { self, args ->
        val key = realm.toPropertyKey(args.arg(0))
        when (self) {
            is JsObject -> self.hasOwn(key)
            is String -> key == "length" || arrayIndexOf(key).let { it >= 0 && it < self.length }
            null, Undefined -> throw realm.typeError("Cannot convert undefined or null to object")
            else -> false
        }
    }
    realm.defineMethod(proto, "isPrototypeOf", 1) { self, args ->
        val v = args.arg(0)
        if (self !is JsObject || v !is JsObject) {
            false
        } else {
            var o = v.prototype
            while (o != null && o !== self) o = o.prototype
            o != null
        }
    }
    realm.defineMethod(proto, "propertyIsEnumerable", 1) { self, args ->
        val key = realm.toPropertyKey(args.arg(0))
        self is JsObject && self.getOwnProp(key)?.enumerable == true
    }
}

// ---- Function.prototype -------------------------------------------------------------------------------------------

private fun installFunctionPrototype(realm: Realm) {
    val proto = realm.functionPrototype
    realm.defineMethod(proto, "call", 1) { self, args ->
        requireCallable(realm, self, "call")
        realm.callFunction(self, args.arg(0), if (args.size > 1) args.subList(1, args.size) else emptyList())
    }
    realm.defineMethod(proto, "apply", 2) { self, args ->
        requireCallable(realm, self, "apply")
        realm.callFunction(self, args.arg(0), listFromArrayLike(realm, args.arg(1)))
    }
    realm.defineMethod(proto, "bind", 1) { self, args ->
        if (self !is JsFunction) throw realm.typeError("Bind must be called on a function")
        BoundFunction(realm, self, args.arg(0), if (args.size > 1) ArrayList(args.subList(1, args.size)) else emptyList())
    }
    realm.defineMethod(proto, "toString", 0) { self, _ ->
        if (self is JsFunction) self.sourceText()
        else if (self is JsFn) "function () { [native code] }"
        else throw realm.typeError("Function.prototype.toString requires that 'this' be a Function")
    }
}

private fun requireCallable(realm: Realm, self: Any?, name: String) {
    if (self !is JsFunction && self !is JsFn) throw realm.typeError("Function.prototype.$name called on non-function")
}

/** The arguments `apply` takes: nothing for `null`/`undefined`, the elements of an array or of an object with a `length`. */
private fun listFromArrayLike(realm: Realm, v: Any?): List<Any?> {
    if (isNullish(v)) return emptyList()
    if (v is JsArray) return ArrayList(v.items)
    if (v !is JsObject) throw realm.typeError("CreateListFromArrayLike called on non-object")
    val n = realm.toNumber(v.get("length"))
    val count = if (n.isNaN() || n < 0) 0 else minOf(n, 1_000_000.0).toInt()
    return (0 until count).map { v.get(it.toString()) }
}

// ---- Array.prototype ----------------------------------------------------------------------------------------------

private fun installArrayBasics(realm: Realm) {
    val proto = realm.arrayPrototype
    realm.defineMethod(proto, "join", 1) { self, args ->
        val sep = args.arg(0).let { if (it === Undefined) "," else realm.toStr(it) }
        joinArray(realm, self, sep)
    }
    realm.defineMethod(proto, "toString", 0) { self, _ -> joinArray(realm, self, ",") }
}

private fun joinArray(realm: Realm, self: Any?, sep: String): String {
    val items: List<Any?> = when {
        self is JsArray -> self.items
        self is JsObject -> {
            val n = realm.toNumber(self.get("length"))
            (0 until (if (n.isNaN() || n < 0) 0 else minOf(n, 1_000_000.0).toInt())).map { self.get(it.toString()) }
        }
        isNullish(self) -> throw realm.typeError("Array.prototype.join called on null or undefined")
        else -> emptyList()
    }
    val sb = StringBuilder()
    for (i in items.indices) {
        if (i > 0) sb.append(sep)
        val item = items[i]
        if (!isNullish(item)) sb.append(realm.toStr(item))
    }
    return sb.toString()
}

// ---- the errors ---------------------------------------------------------------------------------------------------

private fun installErrors(realm: Realm) {
    for (kind in ErrorKind.entries) {
        val proto = realm.errorPrototypes.getValue(kind)
        val make: (List<Any?>) -> Any? = { args ->
            val e = JsErrorObject(proto)
            val message = args.arg(0)
            if (message !== Undefined) e.defineOwn("message", realm.toStr(message), PropFlags.HIDDEN)
            val options = args.arg(1)
            if (options is JsObject && options.has("cause")) e.defineOwn("cause", options.get("cause"), PropFlags.HIDDEN)
            e.defineOwn("stack", errorToString(realm, e), PropFlags.HIDDEN)
            e
        }
        val ctor = NativeFunction(realm, kind.jsName, 1, { _, args, _ -> make(args) }, make, shared = true)
        ctor.defineOwn("prototype", proto, 0)
        proto.defineOwn("constructor", ctor, PropFlags.HIDDEN)
        proto.defineOwn("name", kind.jsName, PropFlags.HIDDEN)
        proto.defineOwn("message", "", PropFlags.HIDDEN)
        realm.defineGlobal(kind.jsName, ctor)
    }
    realm.defineMethod(realm.errorPrototype, "toString", 0) { self, _ ->
        if (self !is JsObject) throw realm.typeError("Error.prototype.toString requires that 'this' be an Object")
        errorToString(realm, self)
    }
}

/** `Error.prototype.toString`: `name: message`, or just one of them when the other is empty. */
private fun errorToString(realm: Realm, e: JsObject): String {
    val name = e.get("name").let { if (it === Undefined) "Error" else realm.toStr(it) }
    val message = e.get("message").let { if (it === Undefined) "" else realm.toStr(it) }
    return when {
        name.isEmpty() -> message
        message.isEmpty() -> name
        else -> "$name: $message"
    }
}

/** The text `String(v)` gives for the value a program threw, used by tests and the host to show it. */
internal fun errorText(realm: Realm, v: Any?): String = if (v is JsObject) errorToString(realm, v) else jsToString(v)
