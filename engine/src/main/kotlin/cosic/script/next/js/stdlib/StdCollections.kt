package cosic.script.next.js.stdlib

import cosic.pattern.Undefined
import cosic.script.next.Realm
import cosic.script.next.js.JsArray
import cosic.script.next.js.JsObject
import cosic.script.next.js.PropFlags
import cosic.script.next.js.describe
import cosic.script.next.js.isNullish
import cosic.script.next.js.iteratorOf
import cosic.script.next.js.toStr

/*
 * `Map` and `Set`: keys are compared like `SameValueZero` (`NaN` is a key, `0` and `-0` are one), iteration is in insertion
 * order and live (entries added while iterating are visited, deleted ones are not).
 */

/**
 * The entries of a `Map` or `Set` in insertion order. The entries are a doubly linked list, so that deleting one is
 * constant time and leaves no trace; every entry has a sequence number, so that an iterator standing on an entry that was
 * deleted since finds where it was (the first live entry with a higher number) instead of ending or repeating.
 */
internal class OrderedTable {
    class Entry(val key: Any?, var value: Any?, val seq: Long) {
        var next: Entry? = null
        var prev: Entry? = null
        var deleted = false
    }

    private val index = HashMap<Any?, Entry>()
    private val head = Entry(null, null, -1L)
    private var tail = head
    private var seq = 0L

    val size: Int get() = index.size

    private fun normalized(key: Any?): Any? = if (key is Double && key == 0.0) 0.0 else key

    fun find(key: Any?): Entry? = index[normalized(key)]

    fun put(key: Any?, value: Any?) {
        val k = normalized(key)
        val e = index[k]
        if (e != null) {
            e.value = value
            return
        }
        val n = Entry(k, value, seq++)
        n.prev = tail
        tail.next = n
        tail = n
        index[k] = n
    }

    fun remove(key: Any?): Boolean {
        val e = index.remove(normalized(key)) ?: return false
        val prev = e.prev!!
        prev.next = e.next
        val next = e.next
        if (next != null) next.prev = prev else tail = prev
        e.deleted = true
        return true
    }

    fun clear() {
        var e = head.next
        while (e != null) {
            e.deleted = true
            e = e.next
        }
        head.next = null
        tail = head
        index.clear()
    }

    /** An iterator over the live table; [select] says what each entry yields. */
    fun iterator(select: (Entry) -> Any?): Iterator<Any?> = object : Iterator<Any?> {
        private var last: Entry = head
        private var ready: Entry? = null

        private fun advance(): Entry? {
            if (!last.deleted) return last.next
            var e = head.next
            while (e != null && e.seq <= last.seq) e = e.next
            return e
        }

        override fun hasNext(): Boolean {
            if (ready == null) ready = advance()
            return ready != null
        }

        override fun next(): Any? {
            val e = ready ?: advance() ?: throw NoSuchElementException()
            ready = null
            last = e
            return select(e)
        }
    }
}

internal class JsMapObject(prototype: JsObject, private val realm: Realm) : JsObject(prototype) {
    val table = OrderedTable()

    override val className: String get() = "Map"

    override fun jsIterator(): Iterator<Any?> = table.iterator { e -> JsArray(realm, arrayListOf(e.key, e.value)) }
}

internal class JsSetObject(prototype: JsObject) : JsObject(prototype) {
    val table = OrderedTable()

    override val className: String get() = "Set"

    override fun jsIterator(): Iterator<Any?> = table.iterator { e -> e.key }
}

internal fun installCollections(realm: Realm, std: StdRealm) {
    installMap(realm, std)
    installSet(realm, std)
}

private fun installMap(realm: Realm, std: StdRealm) {
    val proto = realm.newSharedObject()
    std.mapPrototype = proto
    val construct: (List<Any?>) -> Any? = { args ->
        val map = JsMapObject(proto, realm)
        val source = args.arg(0)
        if (!isNullish(source)) {
            val it = iteratorOf(source) ?: throw realm.notIterable(source)
            while (it.hasNext()) {
                val entry = it.next()
                if (entry !is JsObject) throw realm.typeError("Iterator value ${realm.toStr(entry)} is not an entry object")
                map.table.put(entry.get("0"), entry.get("1"))
            }
        }
        map
    }
    val ctor = realm.constructor("Map", 0, proto, { _, _ -> throw realm.typeError("Constructor Map requires 'new'") }, construct)
    realm.defineGlobal("Map", ctor)

    realm.defineMethod(ctor, "groupBy", 2) { _, args ->
        val items = args.arg(0)
        if (isNullish(items)) throw realm.notIterable(items)
        val callback = realm.callable(args.arg(1))
        val it = iteratorOf(items) ?: throw realm.notIterable(items)
        val result = JsMapObject(proto, realm)
        var index = 0
        while (it.hasNext()) {
            val v = it.next()
            val key = realm.callFunction(callback, Undefined, listOf(v, (index++).toDouble()))
            val group = result.table.find(key)?.value as? JsArray ?: realm.newArray(emptyList()).also { result.table.put(key, it) }
            group.items.add(v)
        }
        result
    }

    fun thisMap(self: Any?, method: String): JsMapObject =
        self as? JsMapObject ?: throw realm.typeError("Method Map.prototype.$method called on incompatible receiver ${realm.describe(self)}")

    realm.defineMethod(proto, "get", 1) { self, args ->
        val e = thisMap(self, "get").table.find(args.arg(0))
        if (e == null) Undefined else e.value
    }
    realm.defineMethod(proto, "set", 2) { self, args ->
        thisMap(self, "set").table.put(args.arg(0), args.arg(1))
        self
    }
    realm.defineMethod(proto, "has", 1) { self, args -> thisMap(self, "has").table.find(args.arg(0)) != null }
    realm.defineMethod(proto, "delete", 1) { self, args -> thisMap(self, "delete").table.remove(args.arg(0)) }
    realm.defineMethod(proto, "clear", 0) { self, _ ->
        thisMap(self, "clear").table.clear()
        Undefined
    }
    realm.defineMethod(proto, "forEach", 1) { self, args ->
        val map = thisMap(self, "forEach")
        val fn = realm.callable(args.arg(0))
        val it = map.table.iterator { e -> e }
        while (it.hasNext()) {
            val e = it.next() as OrderedTable.Entry
            realm.callFunction(fn, args.arg(1), listOf(e.value, e.key, map))
        }
        Undefined
    }
    realm.defineMethod(proto, "keys", 0) { self, _ -> JsIterator(std.iteratorPrototype, "Map Iterator", thisMap(self, "keys").table.iterator { e -> e.key }) }
    realm.defineMethod(proto, "values", 0) { self, _ -> JsIterator(std.iteratorPrototype, "Map Iterator", thisMap(self, "values").table.iterator { e -> e.value }) }
    realm.defineMethod(proto, "entries", 0) { self, _ ->
        JsIterator(std.iteratorPrototype, "Map Iterator", thisMap(self, "entries").table.iterator { e -> JsArray(realm, arrayListOf(e.key, e.value)) })
    }
    proto.defineAccessor("size", { self -> thisMap(self, "size").table.size.toDouble() }, null, PropFlags.CONFIGURABLE)
}

private fun installSet(realm: Realm, std: StdRealm) {
    val proto = realm.newSharedObject()
    std.setPrototype = proto
    val construct: (List<Any?>) -> Any? = { args ->
        val set = JsSetObject(proto)
        val source = args.arg(0)
        if (!isNullish(source)) {
            val it = iteratorOf(source) ?: throw realm.notIterable(source)
            while (it.hasNext()) set.table.put(it.next(), null)
        }
        set
    }
    val ctor = realm.constructor("Set", 0, proto, { _, _ -> throw realm.typeError("Constructor Set requires 'new'") }, construct)
    realm.defineGlobal("Set", ctor)

    fun thisSet(self: Any?, method: String): JsSetObject =
        self as? JsSetObject ?: throw realm.typeError("Method Set.prototype.$method called on incompatible receiver ${realm.describe(self)}")

    realm.defineMethod(proto, "add", 1) { self, args ->
        thisSet(self, "add").table.put(args.arg(0), null)
        self
    }
    realm.defineMethod(proto, "has", 1) { self, args -> thisSet(self, "has").table.find(args.arg(0)) != null }
    realm.defineMethod(proto, "delete", 1) { self, args -> thisSet(self, "delete").table.remove(args.arg(0)) }
    realm.defineMethod(proto, "clear", 0) { self, _ ->
        thisSet(self, "clear").table.clear()
        Undefined
    }
    realm.defineMethod(proto, "forEach", 1) { self, args ->
        val set = thisSet(self, "forEach")
        val fn = realm.callable(args.arg(0))
        val it = set.table.iterator { e -> e.key }
        while (it.hasNext()) {
            val v = it.next()
            realm.callFunction(fn, args.arg(1), listOf(v, v, set))
        }
        Undefined
    }
    realm.defineMethod(proto, "values", 0) { self, _ -> JsIterator(std.iteratorPrototype, "Set Iterator", thisSet(self, "values").table.iterator { e -> e.key }) }
    realm.alias(proto, "keys", "values")
    realm.defineMethod(proto, "entries", 0) { self, _ ->
        JsIterator(std.iteratorPrototype, "Set Iterator", thisSet(self, "entries").table.iterator { e -> JsArray(realm, arrayListOf(e.key, e.key)) })
    }
    proto.defineAccessor("size", { self -> thisSet(self, "size").table.size.toDouble() }, null, PropFlags.CONFIGURABLE)
}
