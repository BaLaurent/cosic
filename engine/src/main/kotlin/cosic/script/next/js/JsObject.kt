package cosic.script.next.js

import cosic.pattern.Undefined
import cosic.pattern.jsKeyOrder
import cosic.script.next.Realm
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** The longest array a program can make: a bigger length is a `RangeError` (`Invalid array length`) instead of an out-of-memory crash. */
internal const val MAX_ARRAY_LENGTH = 1 shl 24

/** A property: a [value] with [flags] (see [PropFlags]), or an [AccessorProp]. [order] is its position among the keys of a shared object. */
internal open class Prop(@JvmField var value: Any?, @JvmField var flags: Int, @JvmField val order: Long = 0L) {
    val writable: Boolean get() = flags and PropFlags.WRITABLE != 0
    val enumerable: Boolean get() = flags and PropFlags.ENUMERABLE != 0
    val configurable: Boolean get() = flags and PropFlags.CONFIGURABLE != 0
    open val isAccessor: Boolean get() = false
}

/** A property made of a getter and a setter; `self` is the object the property is read from or written to. */
internal class AccessorProp(
    @JvmField val getter: ((self: Any?) -> Any?)?,
    @JvmField val setter: ((self: Any?, value: Any?) -> Unit)?,
    flags: Int,
    order: Long = 0L,
) : Prop(Undefined, flags and PropFlags.WRITABLE.inv(), order) {
    override val isAccessor: Boolean get() = true
}

/**
 * A JavaScript object: properties by string key, and a [prototype] to look up what the object does not have itself.
 * (There are no symbols.) Own keys come in JavaScript order: integer-like keys ascending, then the others in the order
 * they were added.
 *
 * Objects are plain: one thread at a time. The objects that every program shares and a later evaluation may extend
 * (the global object, the prototypes and constructors of the built-ins) are made `shared`: their table is a
 * [ConcurrentHashMap] and a property is replaced instead of changed, so that a callback that runs on the audio thread
 * while the next program is being evaluated reads them safely.
 *
 * Subclasses make exotic objects (arrays, functions) by overriding [getOwnProp], [get], [set], [delete], [ownKeys].
 */
internal open class JsObject(@JvmField var prototype: JsObject? = null, shared: Boolean = false) {
    private var table: MutableMap<String, Prop>? = if (shared) ConcurrentHashMap() else null
    private val orders: AtomicLong? = if (shared) AtomicLong() else null

    /** False after `Object.preventExtensions` / `freeze` / `seal`: no property can be added. */
    @JvmField var extensible: Boolean = true

    /** The `[[Class]]`: what `Object.prototype.toString` prints and error messages use (`#<Object>`). */
    open val className: String get() = "Object"

    val isShared: Boolean get() = orders != null

    private fun nextOrder(): Long = orders?.incrementAndGet() ?: 0L

    // ---- own properties --------------------------------------------------------------------------------------------

    /** The own property [key] (the property itself, do not keep it: a shared object replaces it when the value changes), or null. */
    open fun getOwnProp(key: String): Prop? = table?.get(key)

    private fun putProp(key: String, prop: Prop) {
        var t = table
        if (t == null) {
            t = LinkedHashMap()
            table = t
        }
        t[key] = prop
    }

    /** Makes [key] an own data property with [value] and [flags] (JavaScript's `CreateDataProperty` / `defineProperty`). */
    open fun defineOwn(key: String, value: Any?, flags: Int = PropFlags.ALL) {
        val old = table?.get(key)
        if (old != null && !old.isAccessor && orders == null) {
            old.value = value
            old.flags = flags
            return
        }
        putProp(key, Prop(value, flags, old?.order ?: nextOrder()))
    }

    /** Makes [key] an own accessor property. The functions get the object the property is read from or written to. */
    fun defineAccessor(
        key: String,
        getter: ((self: Any?) -> Any?)?,
        setter: ((self: Any?, value: Any?) -> Unit)? = null,
        flags: Int = PropFlags.ENUMERABLE or PropFlags.CONFIGURABLE,
    ) {
        val old = table?.get(key)
        putProp(key, AccessorProp(getter, setter, flags, old?.order ?: nextOrder()))
    }

    private fun writeValue(key: String, prop: Prop, value: Any?) {
        if (orders != null) putProp(key, Prop(value, prop.flags, prop.order)) else prop.value = value
    }

    // ---- the object protocol ---------------------------------------------------------------------------------------

    /** `object[key]`: the value of the first property named [key] along the prototype chain; a getter is called with [receiver] as `this`. */
    open fun get(key: String, receiver: Any? = this): Any? {
        var o: JsObject? = this
        while (o != null) {
            val p = o.getOwnProp(key)
            if (p != null) {
                if (p is AccessorProp) {
                    val getter = p.getter ?: return Undefined
                    return getter(receiver)
                }
                return p.value
            }
            o = o.prototype
        }
        return Undefined
    }

    /**
     * `object[key] = value`, with [receiver] as the object that gets the property (this one, unless a prototype's
     * setter is being run). Returns false when the assignment is refused: a read-only property or one with a getter
     * and no setter, or a new property on an object that is not [extensible]. The interpreter makes a `TypeError` of it.
     */
    open fun set(key: String, value: Any?, receiver: Any? = this): Boolean {
        val own = getOwnProp(key)
        if (own != null) {
            if (own is AccessorProp) {
                val setter = own.setter ?: return false
                setter(receiver, value)
                return true
            }
            if (!own.writable) return false
            if (receiver === this) {
                writeValue(key, own, value)
                return true
            }
            return setOnReceiver(receiver, key, value)
        }
        var p = prototype
        while (p != null) {
            val inherited = p.getOwnProp(key)
            if (inherited != null) {
                if (inherited is AccessorProp) {
                    val setter = inherited.setter ?: return false
                    setter(receiver, value)
                    return true
                }
                if (!inherited.writable) return false
                break
            }
            p = p.prototype
        }
        return setOnReceiver(receiver, key, value)
    }

    private fun setOnReceiver(receiver: Any?, key: String, value: Any?): Boolean {
        if (receiver !is JsObject) return false
        val existing = receiver.getOwnProp(key)
        if (existing != null) {
            if (existing is AccessorProp || !existing.writable) return false
            receiver.defineOwn(key, value, existing.flags)
            return true
        }
        if (!receiver.extensible) return false
        receiver.defineOwn(key, value, PropFlags.ALL)
        return true
    }

    /** `key in object`: whether the object or one of its prototypes has the property. */
    open fun has(key: String): Boolean {
        var o: JsObject? = this
        while (o != null) {
            if (o.getOwnProp(key) != null) return true
            o = o.prototype
        }
        return false
    }

    /** Whether the object itself has the property. */
    fun hasOwn(key: String): Boolean = getOwnProp(key) != null

    /** `delete object[key]`: false when the property is there and cannot be deleted. */
    open fun delete(key: String): Boolean {
        val t = table ?: return true
        val p = t[key] ?: return true
        if (!p.configurable) return false
        t.remove(key)
        return true
    }

    /** All own keys, including the ones that are not enumerable, in JavaScript order. */
    open fun ownKeys(): List<String> {
        val t = table ?: return emptyList()
        val keys: List<String> = if (orders != null) t.entries.sortedBy { it.value.order }.map { it.key } else t.keys.toList()
        return jsKeyOrder(keys)
    }

    /** The own keys that `Object.keys`, `for in`, spread and `JSON.stringify` see. */
    fun ownEnumerableKeys(): List<String> = ownKeys().filter { getOwnProp(it)?.enumerable == true }

    /** The first property named [key] along the prototype chain, or null. */
    fun findProp(key: String): Prop? {
        var o: JsObject? = this
        while (o != null) {
            val p = o.getOwnProp(key)
            if (p != null) return p
            o = o.prototype
        }
        return null
    }

    /** What `for of`, spread and destructuring read: null when the object is not iterable. A collection overrides it. */
    open fun jsIterator(): Iterator<Any?>? = null

    override fun toString(): String = "[$className]"
}

/** An error object: what `new Error(...)`, a `TypeError` thrown by the interpreter and the like are. */
internal class JsErrorObject(prototype: JsObject?) : JsObject(prototype) {
    override val className: String get() = "Error"
}

/**
 * The index of [key] when it names an array element (a canonical non-negative integer: `"0"`, `"12"`, not `"01"` or
 * `"-1"`), else -1. Only indices that fit an `Int` count; larger ones are ordinary properties.
 */
internal fun arrayIndexOf(key: String): Int {
    val n = key.length
    if (n == 0 || n > 10) return -1
    val first = key[0]
    if (first < '0' || first > '9') return -1
    if (first == '0') return if (n == 1) 0 else -1
    var v = 0L
    for (i in 0 until n) {
        val c = key[i]
        if (c < '0' || c > '9') return -1
        v = v * 10 + (c - '0')
    }
    return if (v < Int.MAX_VALUE) v.toInt() else -1
}

/**
 * An array. The elements are [items]; a hole reads as `undefined` like any element that is not there. `length` is
 * writable. Every other property is an ordinary one.
 */
internal class JsArray(@JvmField val realm: Realm, @JvmField val items: ArrayList<Any?>) : JsObject(realm.arrayPrototype) {
    override val className: String get() = "Array"

    override fun getOwnProp(key: String): Prop? {
        if (key == "length") return Prop(items.size.toDouble(), PropFlags.WRITABLE)
        val i = arrayIndexOf(key)
        if (i >= 0) return if (i < items.size) Prop(items[i], PropFlags.ALL) else null
        return super.getOwnProp(key)
    }

    override fun get(key: String, receiver: Any?): Any? {
        if (key == "length") return items.size.toDouble()
        val i = arrayIndexOf(key)
        if (i >= 0 && i < items.size) return items[i]
        return super.get(key, receiver)
    }

    override fun set(key: String, value: Any?, receiver: Any?): Boolean {
        if (receiver === this) {
            if (key == "length") {
                setLength(value)
                return true
            }
            val i = arrayIndexOf(key)
            if (i >= 0) {
                setIndex(i, value)
                return true
            }
        }
        return super.set(key, value, receiver)
    }

    override fun defineOwn(key: String, value: Any?, flags: Int) {
        if (key == "length") {
            setLength(value)
            return
        }
        val i = arrayIndexOf(key)
        if (i >= 0) {
            setIndex(i, value)
            return
        }
        super.defineOwn(key, value, flags)
    }

    override fun delete(key: String): Boolean {
        if (key == "length") return false
        val i = arrayIndexOf(key)
        if (i >= 0) {
            if (i < items.size) items[i] = Undefined
            return true
        }
        return super.delete(key)
    }

    override fun ownKeys(): List<String> {
        val keys = ArrayList<String>(items.size + 1)
        for (i in items.indices) keys.add(i.toString())
        keys.add("length")
        keys.addAll(super.ownKeys())
        return keys
    }

    override fun jsIterator(): Iterator<Any?> = object : Iterator<Any?> {
        private var i = 0
        override fun hasNext(): Boolean = i < items.size
        override fun next(): Any? = items[i++]
    }

    /** `array[i] = value`: grows the array with `undefined` when [i] is past the end. */
    fun setIndex(i: Int, value: Any?) {
        if (i < items.size) {
            items[i] = value
            return
        }
        if (i >= MAX_ARRAY_LENGTH) throw realm.rangeError("Invalid array length")
        items.ensureCapacity(i + 1)
        while (items.size < i) items.add(Undefined)
        items.add(value)
    }

    /** `array.length = value`: cuts the array or grows it with `undefined`. A length that is not a non-negative integer is a `RangeError`. */
    fun setLength(value: Any?) {
        val d = realm.toNumber(value)
        val n = d.toInt()
        if (n.toDouble() != d || n < 0 || n > MAX_ARRAY_LENGTH) throw realm.rangeError("Invalid array length")
        if (n < items.size) {
            items.subList(n, items.size).clear()
        } else {
            items.ensureCapacity(n)
            while (items.size < n) items.add(Undefined)
        }
    }
}
