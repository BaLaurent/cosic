package cosic.script.js.stdlib

import cosic.pattern.JsFn
import cosic.pattern.Undefined
import cosic.pattern.jsTruthy
import cosic.script.Realm
import cosic.script.js.AccessorProp
import cosic.script.js.ErrorKind
import cosic.script.js.JsArray
import cosic.script.js.JsFunction
import cosic.script.js.JsObject
import cosic.script.js.Prop
import cosic.script.js.PropFlags
import cosic.script.js.arrayIndexOf
import cosic.script.js.isNullish
import cosic.script.js.iteratorOf
import cosic.script.js.toPropertyKey
import cosic.script.js.toStr

/*
 * `Object`, `Function` and `Boolean`, and the boxes (`new Number(1)`, `new String("a")`, `new Boolean(true)`).
 */

/** `new Number(1)`, `new String("a")`, `new Boolean(true)`: an object that holds a primitive. */
internal class JsBox(prototype: JsObject, val kind: String, val primitive: Any?) : JsObject(prototype) {
    override val className: String get() = kind

    override fun getOwnProp(key: String): Prop? {
        if (primitive is String) {
            if (key == "length") return Prop(primitive.length.toDouble(), 0)
            val i = arrayIndexOf(key)
            if (i >= 0 && i < primitive.length) return Prop(primitive[i].toString(), PropFlags.ENUMERABLE)
        }
        return super.getOwnProp(key)
    }

    override fun ownKeys(): List<String> {
        if (primitive !is String) return super.ownKeys()
        val keys = ArrayList<String>(primitive.length + 1)
        for (i in primitive.indices) keys.add(i.toString())
        keys.add("length")
        keys.addAll(super.ownKeys())
        return keys
    }
}

internal fun installObject(realm: Realm, std: StdRealm) {
    installObjectConstructor(realm, std)
    installObjectPrototypeExtras(realm)
    installFunction(realm)
    installBoolean(realm)
}

// ---- Object -----------------------------------------------------------------------------------------------------------

private fun installObjectConstructor(realm: Realm, std: StdRealm) {
    val make: (List<Any?>) -> Any? = { args ->
        val v = args.arg(0)
        if (isNullish(v)) realm.newObject() else realm.toObject(v)
    }
    val ctor = realm.constructor("Object", 1, realm.objectPrototype, { _, args -> make(args) }, make)
    realm.defineGlobal("Object", ctor)

    realm.defineMethod(ctor, "keys", 1) { _, args -> realm.newArray(ownEnumerableEntries(realm, realm.toObject(args.arg(0))).map { it.first }) }
    realm.defineMethod(ctor, "values", 1) { _, args -> realm.newArray(ownEnumerableEntries(realm, realm.toObject(args.arg(0))).map { it.second }) }
    realm.defineMethod(ctor, "entries", 1) { _, args ->
        realm.newArray(ownEnumerableEntries(realm, realm.toObject(args.arg(0))).map { realm.newArray(listOf(it.first, it.second)) })
    }
    realm.defineMethod(ctor, "assign", 2) { _, args ->
        val target = realm.toObject(args.arg(0))
        for (i in 1 until args.size) {
            val source = args[i]
            if (isNullish(source)) continue
            for ((key, value) in ownEnumerableEntries(realm, realm.toObject(source))) realm.setOrThrow(target, key, value)
        }
        target
    }
    realm.defineMethod(ctor, "fromEntries", 1) { _, args ->
        val iterable = args.arg(0)
        val it = iteratorOf(iterable) ?: throw if (isNullish(iterable)) realm.typeError("undefined is not iterable") else realm.notIterable(iterable)
        val result = realm.newObject()
        while (it.hasNext()) {
            val entry = it.next()
            if (entry !is JsObject) throw realm.typeError("Iterator value ${realm.toStr(entry)} is not an entry object")
            result.defineOwn(realm.toPropertyKey(entry.get("0")), entry.get("1"), PropFlags.ALL)
        }
        result
    }
    realm.defineMethod(ctor, "groupBy", 2) { _, args ->
        val items = args.arg(0)
        if (isNullish(items)) throw realm.typeError("${describeForCall(items)} is not iterable (cannot read property Symbol(Symbol.iterator))")
        val callback = realm.callable(args.arg(1))
        val it = iteratorOf(items) ?: throw realm.notIterable(items)
        val groups = JsObject(null)
        var index = 0
        while (it.hasNext()) {
            val v = it.next()
            val key = realm.toPropertyKey(realm.callFunction(callback, Undefined, listOf(v, (index++).toDouble())))
            val group = groups.getOwnProp(key)?.value as? JsArray ?: realm.newArray(emptyList()).also { groups.defineOwn(key, it, PropFlags.ALL) }
            group.items.add(v)
        }
        groups
    }

    realm.defineMethod(ctor, "freeze", 1) { _, args -> args.arg(0).also { if (it is JsObject) lockDown(std, it, freeze = true) } }
    realm.defineMethod(ctor, "seal", 1) { _, args -> args.arg(0).also { if (it is JsObject) lockDown(std, it, freeze = false) } }
    realm.defineMethod(ctor, "preventExtensions", 1) { _, args -> args.arg(0).also { if (it is JsObject) it.extensible = false } }
    realm.defineMethod(ctor, "isFrozen", 1) { _, args -> args.arg(0).let { it !is JsObject || isLockedDown(std, it, frozen = true) } }
    realm.defineMethod(ctor, "isSealed", 1) { _, args -> args.arg(0).let { it !is JsObject || isLockedDown(std, it, frozen = false) } }
    realm.defineMethod(ctor, "isExtensible", 1) { _, args -> args.arg(0).let { it is JsObject && it.extensible } }

    realm.defineMethod(ctor, "getPrototypeOf", 1) { _, args ->
        when (val v = args.arg(0)) {
            is JsObject -> v.prototype
            is String -> realm.stringPrototype
            is Double, is Number -> realm.numberPrototype
            is Boolean -> realm.booleanPrototype
            null, Undefined -> throw realm.typeError("Cannot convert undefined or null to object")
            else -> realm.objectPrototype
        }
    }
    realm.defineMethod(ctor, "setPrototypeOf", 2) { _, args ->
        val o = args.arg(0)
        if (isNullish(o)) throw realm.typeError("Object.setPrototypeOf called on null or undefined")
        val proto = args.arg(1)
        if (proto != null && proto !is JsObject) throw realm.typeError("Object prototype may only be an Object or null: ${realm.toStr(proto)}")
        if (o is JsObject) setPrototype(realm, o, proto as JsObject?)
        o
    }
    realm.defineMethod(ctor, "create", 2) { _, args ->
        val proto = args.arg(0)
        if (proto != null && proto !is JsObject) throw realm.typeError("Object prototype may only be an Object or null: ${realm.toStr(proto)}")
        val o = JsObject(proto as JsObject?)
        val props = args.arg(1)
        if (props !== Undefined) defineProperties(realm, o, props)
        o
    }

    realm.defineMethod(ctor, "defineProperty", 3) { _, args ->
        val o = args.arg(0) as? JsObject ?: throw realm.typeError("Object.defineProperty called on non-object")
        val key = realm.toPropertyKey(args.arg(1))
        defineProperty(realm, o, key, toDescriptor(realm, args.arg(2)))
        o
    }
    realm.defineMethod(ctor, "defineProperties", 2) { _, args ->
        val o = args.arg(0) as? JsObject ?: throw realm.typeError("Object.defineProperties called on non-object")
        defineProperties(realm, o, args.arg(1))
        o
    }
    realm.defineMethod(ctor, "getOwnPropertyNames", 1) { _, args -> realm.newArray(realm.toObject(args.arg(0)).ownKeys()) }
    realm.defineMethod(ctor, "getOwnPropertySymbols", 1) { _, args ->
        realm.toObject(args.arg(0))
        realm.newArray(emptyList())
    }
    realm.defineMethod(ctor, "getOwnPropertyDescriptor", 2) { _, args ->
        val o = realm.toObject(args.arg(0))
        fromProp(realm, o.getOwnProp(realm.toPropertyKey(args.arg(1))))
    }
    realm.defineMethod(ctor, "getOwnPropertyDescriptors", 1) { _, args ->
        val o = realm.toObject(args.arg(0))
        val result = realm.newObject()
        for (key in o.ownKeys()) result.defineOwn(key, fromProp(realm, o.getOwnProp(key)), PropFlags.ALL)
        result
    }
    realm.defineMethod(ctor, "is", 2) { _, args -> sameValue(args.arg(0), args.arg(1)) }
    realm.defineMethod(ctor, "hasOwn", 2) { _, args ->
        val o = realm.toObject(args.arg(0))
        o.hasOwn(realm.toPropertyKey(args.arg(1)))
    }
}

/** The own enumerable string-keyed properties of [o] as (key, value), in property order. A string gives its characters. */
internal fun ownEnumerableEntries(realm: Realm, o: JsObject): List<Pair<String, Any?>> {
    val out = ArrayList<Pair<String, Any?>>()
    for (key in o.ownKeys()) {
        val p = o.getOwnProp(key) ?: continue
        if (!p.enumerable) continue
        out.add(key to if (p is AccessorProp) o.get(key, o) else p.value)
    }
    return out
}

/** `SameValue`: `Object.is`. */
internal fun sameValue(a: Any?, b: Any?): Boolean = when {
    a is Double && b is Double -> if (a.isNaN() && b.isNaN()) true else a == b && (a != 0.0 || 1.0 / a == 1.0 / b)
    a is Number && b is Number -> sameValue(a.toDouble(), b.toDouble())
    a is String && b is String -> a == b
    a is Boolean && b is Boolean -> a == b
    a == null || b == null -> a == null && b == null
    a === Undefined || b === Undefined -> a === b
    else -> a === b
}

/** `SameValueZero`: what `includes`, `Map` and `Set` use; NaN equals NaN, and 0 equals -0. */
internal fun sameValueZero(a: Any?, b: Any?): Boolean = when {
    a is Double && b is Double -> a == b || (a.isNaN() && b.isNaN())
    else -> sameValue(a, b)
}

private fun setPrototype(realm: Realm, o: JsObject, proto: JsObject?) {
    if (o.prototype === proto) return
    if (!o.extensible) throw realm.typeError("#<${o.className}> is not extensible")
    var p = proto
    while (p != null) {
        if (p === o) throw realm.typeError("Cyclic __proto__ value")
        p = p.prototype
    }
    o.prototype = proto
}

private fun lockDown(std: StdRealm, o: JsObject, freeze: Boolean) {
    o.extensible = false
    if (o is JsArray && freeze) std.frozenArrays.add(o)
    for (key in o.ownKeys()) {
        if (o is JsArray && (key == "length" || arrayIndexOf(key) >= 0)) continue
        val p = o.getOwnProp(key) ?: continue
        if (p is AccessorProp) {
            o.defineAccessor(key, p.getter, p.setter, p.flags and PropFlags.CONFIGURABLE.inv())
        } else {
            val removed = if (freeze) PropFlags.WRITABLE or PropFlags.CONFIGURABLE else PropFlags.CONFIGURABLE
            o.defineOwn(key, p.value, p.flags and removed.inv())
        }
    }
}

private fun isLockedDown(std: StdRealm, o: JsObject, frozen: Boolean): Boolean {
    if (o.extensible) return false
    if (o is JsArray && o.items.isNotEmpty() && o !in std.frozenArrays) return false
    for (key in o.ownKeys()) {
        if (o is JsArray && (key == "length" || arrayIndexOf(key) >= 0)) continue
        val p = o.getOwnProp(key) ?: continue
        if (p.configurable) return false
        if (frozen && !p.isAccessor && p.writable) return false
    }
    return true
}

// ---- property descriptors -----------------------------------------------------------------------------------------------

/** A property descriptor as `Object.defineProperty` reads it: each attribute is null when the object did not say. */
private class Descriptor(
    val value: Any?,
    val hasValue: Boolean,
    val writable: Boolean?,
    val getter: Any?,
    val hasGetter: Boolean,
    val setter: Any?,
    val hasSetter: Boolean,
    val enumerable: Boolean?,
    val configurable: Boolean?,
) {
    val isAccessor: Boolean get() = hasGetter || hasSetter
    val isData: Boolean get() = hasValue || writable != null
}

private fun toDescriptor(realm: Realm, v: Any?): Descriptor {
    if (v !is JsObject) throw realm.typeError("Property description must be an object: ${realm.toStr(v)}")
    fun flag(name: String): Boolean? = if (v.has(name)) jsTruthy(v.get(name)) else null
    val hasGet = v.has("get")
    val hasSet = v.has("set")
    val getter = if (hasGet) v.get("get") else Undefined
    val setter = if (hasSet) v.get("set") else Undefined
    if (hasGet && getter !== Undefined && getter !is JsFunction && getter !is JsFn) throw realm.typeError("Getter must be a function: ${realm.toStr(getter)}")
    if (hasSet && setter !== Undefined && setter !is JsFunction && setter !is JsFn) throw realm.typeError("Setter must be a function: ${realm.toStr(setter)}")
    val d = Descriptor(v.get("value"), v.has("value"), flag("writable"), getter, hasGet, setter, hasSet, flag("enumerable"), flag("configurable"))
    if (d.isAccessor && d.isData) {
        throw realm.typeError("Invalid property descriptor. Cannot both specify accessors and a value or writable attribute, #<${constructorNameOf(v)}>")
    }
    return d
}

private fun defineProperties(realm: Realm, o: JsObject, props: Any?) {
    val source = realm.toObject(props)
    val descriptors = ownEnumerableEntries(realm, source).map { (key, value) -> key to toDescriptor(realm, value) }
    for ((key, d) in descriptors) defineProperty(realm, o, key, d)
}

private fun getterOf(realm: Realm, fn: Any?): ((Any?) -> Any?)? = if (fn === Undefined) null else { self -> realm.callFunction(fn, self, emptyList()) }

private fun setterOf(realm: Realm, fn: Any?): ((Any?, Any?) -> Unit)? = if (fn === Undefined) null else { self, v -> realm.callFunction(fn, self, listOf(v)) }

private fun flagsOf(writable: Boolean, enumerable: Boolean, configurable: Boolean): Int =
    (if (writable) PropFlags.WRITABLE else 0) or (if (enumerable) PropFlags.ENUMERABLE else 0) or (if (configurable) PropFlags.CONFIGURABLE else 0)

/** `ValidateAndApplyPropertyDescriptor`: creates the property, or changes it as far as its attributes allow. */
private fun defineProperty(realm: Realm, o: JsObject, key: String, d: Descriptor) {
    val current = o.getOwnProp(key)
    if (current == null) {
        if (!o.extensible) throw realm.typeError("Cannot define property $key, object is not extensible")
        if (d.isAccessor) {
            o.defineAccessor(key, getterOf(realm, d.getter), setterOf(realm, d.setter), flagsOf(false, d.enumerable == true, d.configurable == true))
        } else {
            o.defineOwn(key, if (d.hasValue) d.value else Undefined, flagsOf(d.writable == true, d.enumerable == true, d.configurable == true))
        }
        return
    }
    val redefine = { realm.typeError("Cannot redefine property: $key") }
    if (!current.configurable) {
        if (d.configurable == true) throw redefine()
        if (d.enumerable != null && d.enumerable != current.enumerable) throw redefine()
        if (d.isAccessor != current.isAccessor && (d.isAccessor || d.isData)) throw redefine()
        if (!current.isAccessor && !current.writable) {
            if (d.writable == true) throw redefine()
            if (d.hasValue && !sameValue(d.value, current.value)) throw redefine()
        }
        if (current is AccessorProp && d.isAccessor) throw redefine()
    }
    val enumerable = d.enumerable ?: current.enumerable
    val configurable = d.configurable ?: current.configurable
    when {
        d.isAccessor -> {
            val old = current as? AccessorProp
            o.defineAccessor(
                key,
                if (d.hasGetter) getterOf(realm, d.getter) else old?.getter,
                if (d.hasSetter) setterOf(realm, d.setter) else old?.setter,
                flagsOf(false, enumerable, configurable),
            )
        }
        current is AccessorProp && !d.isData -> o.defineAccessor(key, current.getter, current.setter, flagsOf(false, enumerable, configurable))
        else -> {
            val wasData = !current.isAccessor
            val writable = d.writable ?: (wasData && current.writable)
            o.defineOwn(key, if (d.hasValue) d.value else if (wasData) current.value else Undefined, flagsOf(writable, enumerable, configurable))
        }
    }
}

/** The descriptor object of an own property, or `undefined`. */
private fun fromProp(realm: Realm, p: Prop?): Any? {
    if (p == null) return Undefined
    val d = realm.newObject()
    if (p is AccessorProp) {
        d.defineOwn("get", p.getter?.let { g -> realm.function("get", 0) { self, _ -> g(self) } } ?: Undefined, PropFlags.ALL)
        d.defineOwn("set", p.setter?.let { s -> realm.function("set", 1) { self, a -> s(self, a.arg(0)); Undefined } } ?: Undefined, PropFlags.ALL)
    } else {
        d.defineOwn("value", p.value, PropFlags.ALL)
        d.defineOwn("writable", p.writable, PropFlags.ALL)
    }
    d.defineOwn("enumerable", p.enumerable, PropFlags.ALL)
    d.defineOwn("configurable", p.configurable, PropFlags.ALL)
    return d
}

private fun installObjectPrototypeExtras(realm: Realm) {
    val proto = realm.objectPrototype
    realm.defineMethod(proto, "valueOf", 0) { self, _ -> realm.toObject(self) }
    realm.defineMethod(proto, "toLocaleString", 0) { self, _ ->
        if (isNullish(self)) throw realm.typeError("Object.prototype.toLocaleString called on null or undefined")
        realm.callFunction(realm.toObject(self).get("toString"), self, emptyList())
    }
    proto.defineAccessor(
        "__proto__",
        { self ->
            when (self) {
                is JsObject -> self.prototype
                is String -> realm.stringPrototype
                is Double, is Number -> realm.numberPrototype
                is Boolean -> realm.booleanPrototype
                null, Undefined -> throw realm.typeError("Cannot convert undefined or null to object")
                else -> realm.objectPrototype
            }
        },
        { self, v ->
            if (isNullish(self)) throw realm.typeError("Object.prototype.__proto__ called on null or undefined")
            if (self is JsObject && (v == null || v is JsObject)) setPrototype(realm, self, v as JsObject?)
        },
        PropFlags.CONFIGURABLE,
    )
}

// ---- Function ----------------------------------------------------------------------------------------------------------

private fun installFunction(realm: Realm) {
    val refuse: (Any?) -> Nothing = { throw realm.error(ErrorKind.EVAL, "Code generation from strings disallowed for this context") }
    val ctor = realm.constructor("Function", 1, realm.functionPrototype, { _, a -> refuse(a) }, { a -> refuse(a) })
    realm.defineGlobal("Function", ctor)
}

// ---- Boolean -----------------------------------------------------------------------------------------------------------

private fun installBoolean(realm: Realm) {
    val proto = realm.booleanPrototype
    val ctor = realm.constructor(
        "Boolean", 1, proto,
        { _, args -> jsTruthy(args.arg(0)) },
        { args -> JsBox(proto, "Boolean", jsTruthy(args.arg(0))) },
    )
    realm.defineGlobal("Boolean", ctor)
    fun thisBoolean(self: Any?, method: String): Boolean = when {
        self is Boolean -> self
        self is JsBox && self.kind == "Boolean" -> self.primitive as Boolean
        else -> throw realm.typeError("Boolean.prototype.$method requires that 'this' be a Boolean")
    }
    realm.defineMethod(proto, "toString", 0) { self, _ -> thisBoolean(self, "toString").toString() }
    realm.defineMethod(proto, "valueOf", 0) { self, _ -> thisBoolean(self, "valueOf") }
}
