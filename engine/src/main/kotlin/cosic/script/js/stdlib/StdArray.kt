package cosic.script.js.stdlib

import cosic.pattern.JsFn
import cosic.pattern.Undefined
import cosic.pattern.jsNumberToString
import cosic.pattern.jsSliceIndex
import cosic.pattern.jsStrictEq
import cosic.pattern.jsTruthy
import cosic.script.Realm
import cosic.script.js.JsArray
import cosic.script.js.JsFunction
import cosic.script.js.JsObject
import cosic.script.js.MAX_ARRAY_LENGTH
import cosic.script.js.PropFlags
import cosic.script.js.describe
import cosic.script.js.MAX_STRING_LENGTH
import cosic.script.js.isNullish
import cosic.script.js.iteratorOf
import cosic.script.js.toNumber
import cosic.script.js.toStr

/*
 * `Array` and the iterator objects (`array.keys()`, `map.entries()`...). The methods are generic over anything with a
 * `length` when they only read; the ones that change the array need an array. An array has no holes, so `Array(3).map(f)`
 * calls `f` three times (V8 skips the holes).
 */

/** What `keys()`, `values()` and `entries()` return: an object with `next()` that `for of` and spread read directly. */
internal class JsIterator(
    prototype: JsObject,
    private val tag: String,
    private val source: Iterator<Any?>,
    /** Whether it yields `[key, value]` entries (`map.entries()`), which `console.log` writes as `[Map Entries]`. */
    val entries: Boolean = false,
) : JsObject(prototype) {
    override val className: String get() = tag

    /** The iterator of a `Map` or `Set` this wraps, which can say what it has left; null for the others. */
    val table: OrderedTable.TableIterator? get() = source as? OrderedTable.TableIterator

    override fun jsIterator(): Iterator<Any?> = source
}

internal fun installArray(realm: Realm, std: StdRealm) {
    installIteratorPrototype(realm, std)
    val proto = realm.arrayPrototype
    val make: (List<Any?>) -> Any? = { args -> newArrayFromArguments(realm, args) }
    val ctor = realm.constructor("Array", 1, proto, { _, args -> make(args) }, make)
    realm.defineGlobal("Array", ctor)

    realm.defineMethod(ctor, "isArray", 1) { _, args -> args.arg(0) is JsArray }
    realm.defineMethod(ctor, "of", 0) { _, args -> realm.newArray(args) }
    realm.defineMethod(ctor, "from", 1) { _, args -> arrayFrom(realm, args) }

    proto.defineOwn("length", 0.0, PropFlags.WRITABLE)
    installReading(realm, std, proto)
    installIteration(realm, proto)
    installMutation(realm, std, proto)
}

private fun newArrayFromArguments(realm: Realm, args: List<Any?>): JsArray {
    val first = args.arg(0)
    if (args.size == 1 && first is Double) {
        val n = first.toInt()
        if (n.toDouble() != first || n < 0 || n > MAX_ARRAY_LENGTH) throw realm.rangeError("Invalid array length")
        return JsArray(realm, ArrayList<Any?>(n).also { list -> repeat(n) { list.add(Undefined) } })
    }
    return realm.newArray(args)
}

private fun arrayFrom(realm: Realm, args: List<Any?>): Any? {
    val items = args.arg(0)
    val mapper = args.arg(1)
    val thisArg = args.arg(2)
    if (mapper !== Undefined) realm.callable(mapper)
    if (isNullish(items)) throw realm.notIterable(items)
    val out = ArrayList<Any?>()
    val iterator = iteratorOf(items)
    fun add(v: Any?) {
        if (out.size >= MAX_ARRAY_LENGTH) throw realm.rangeError("Invalid array length")
        out.add(if (mapper === Undefined) v else realm.callFunction(mapper, thisArg, listOf(v, out.size.toDouble())))
    }
    if (iterator != null) {
        while (iterator.hasNext()) add(iterator.next())
    } else {
        for (v in realm.arrayLikeItems(items)) add(v)
    }
    return JsArray(realm, out)
}

// ---- the iterator objects -----------------------------------------------------------------------------------------------

private fun installIteratorPrototype(realm: Realm, std: StdRealm) {
    val proto = realm.newSharedObject()
    std.iteratorPrototype = proto
    realm.defineMethod(proto, "next", 0) { self, _ ->
        if (self !is JsIterator) throw realm.typeError("next method called on incompatible receiver ${realm.toStr(self)}")
        val it = self.jsIterator()
        val result = realm.newObject()
        if (it.hasNext()) {
            result.defineOwn("value", it.next(), PropFlags.ALL)
            result.defineOwn("done", false, PropFlags.ALL)
        } else {
            result.defineOwn("value", Undefined, PropFlags.ALL)
            result.defineOwn("done", true, PropFlags.ALL)
        }
        result
    }
}

/** An iterator over the live elements of [array]: elements added during the walk are visited, a shortened array ends it. */
private fun liveIterator(array: JsArray, entry: (Int, Any?) -> Any?): Iterator<Any?> = object : Iterator<Any?> {
    private var i = 0

    override fun hasNext(): Boolean = i < array.items.size

    override fun next(): Any? {
        val index = i++
        return entry(index, array.items[index])
    }
}

// ---- receivers --------------------------------------------------------------------------------------------------------

/** The methods whose message for a `null` or `undefined` receiver is `Array.prototype.X called on null or undefined` in V8; the others say `Cannot convert undefined or null to object`. */
private val CALLED_ON_NULL = setOf("concat", "find", "findIndex", "findLast", "findLastIndex", "indexOf", "forEach", "filter", "map", "every", "some", "reduce", "reduceRight")

private fun Realm.rejectNullish(self: Any?, method: String) {
    if (!isNullish(self)) return
    throw typeError(if (method in CALLED_ON_NULL) "Array.prototype.$method called on null or undefined" else "Cannot convert undefined or null to object")
}

/** What a read-only method works on: the live list of an array, a snapshot of any other object that has a length. */
private fun Realm.elements(self: Any?, method: String): List<Any?> {
    if (self is JsArray) return self.items
    rejectNullish(self, method)
    return arrayLikeItems(self)
}

private fun Realm.arrayReceiver(self: Any?, method: String): JsArray {
    if (self is JsArray) return self
    rejectNullish(self, method)
    throw typeError("Array.prototype.$method called on a non-array")
}

/** `array.join(separator)` for an array, an array-like or a string; an array that contains itself leaves its inner occurrence empty. */
private fun joinArray(realm: Realm, std: StdRealm, self: Any?, separator: Any?): String {
    val items = realm.elements(self, "join")
    val sep = if (separator === Undefined) "," else realm.toStr(separator)
    val joining = std.joining.get()
    val key = self as? JsObject
    if (key != null) {
        if (joining.containsKey(key)) return ""
        joining[key] = true
    }
    try {
        val sb = StringBuilder()
        for (i in items.indices) {
            if (i > 0) sb.append(sep)
            val item = items[i]
            if (!isNullish(item)) sb.append(realm.toStr(item))
            if (sb.length > MAX_STRING_LENGTH) throw realm.rangeError("Invalid string length")
        }
        return sb.toString()
    } finally {
        if (key != null) joining.remove(key)
    }
}

private fun Realm.checkGrowable(array: JsArray, std: StdRealm, adding: Int) {
    if (adding <= 0) return
    if (!array.extensible || array in std.frozenArrays) throw typeError("Cannot add property ${array.items.size}, object is not extensible")
    if (array.items.size.toLong() + adding > MAX_ARRAY_LENGTH) throw rangeError("Invalid array length")
}

private fun Realm.checkWritable(array: JsArray, std: StdRealm, index: Int = 0) {
    if (array in std.frozenArrays) throw typeError("Cannot assign to read only property '$index' of object '[object Array]'")
}

private fun Realm.checkShrinkable(array: JsArray, std: StdRealm) {
    if (array in std.frozenArrays) throw typeError("Cannot delete property '${array.items.size - 1}' of [object Array]")
}

private fun callback(realm: Realm, fn: Any?, thisArg: Any?, v: Any?, i: Int, self: Any?): Any? =
    realm.callFunction(fn, thisArg, listOf(v, i.toDouble(), self))

// ---- reading ----------------------------------------------------------------------------------------------------------

private fun installReading(realm: Realm, std: StdRealm, proto: JsObject) {
    realm.defineMethod(proto, "at", 1) { self, args ->
        val items = realm.elements(self, "at")
        val rel = realm.toIntegerOrInfinity(args.arg(0))
        val k = if (rel >= 0) rel else items.size + rel
        if (k < 0 || k >= items.size) Undefined else items[k.toInt()]
    }
    realm.defineMethod(proto, "concat", 1) { self, args ->
        realm.requireCoercible(self, "Array.prototype.concat")
        val out = ArrayList<Any?>()
        for (part in listOf(realm.toObject(self)) + args) {
            if (part is JsArray) out.addAll(part.items) else out.add(part)
            if (out.size > MAX_ARRAY_LENGTH) throw realm.rangeError("Invalid array length")
        }
        JsArray(realm, out)
    }
    realm.defineMethod(proto, "join", 1) { self, args -> joinArray(realm, std, self, args.arg(0)) }
    realm.defineMethod(proto, "toString", 0) { self, _ ->
        val obj = realm.toObject(self)
        val join = obj.get("join")
        if (join is JsFunction || join is JsFn) realm.callFunction(join, obj, emptyList()) else "[object ${obj.className}]"
    }
    realm.defineMethod(proto, "slice", 2) { self, args ->
        val items = realm.elements(self, "slice")
        val start = jsSliceIndex(realm.toNumber(args.arg(0)), items.size)
        val end = if (args.arg(1) === Undefined) items.size else jsSliceIndex(realm.toNumber(args.arg(1)), items.size)
        JsArray(realm, if (start < end) ArrayList(items.subList(start, end)) else ArrayList())
    }
    realm.defineMethod(proto, "indexOf", 1) { self, args ->
        val items = realm.elements(self, "indexOf")
        val target = args.arg(0)
        var i = if (args.size > 1) searchStart(realm.toIntegerOrInfinity(args[1]), items.size) else 0
        var result = -1.0
        while (i < items.size) {
            if (jsStrictEq(items[i], target)) {
                result = i.toDouble()
                break
            }
            i++
        }
        result
    }
    realm.defineMethod(proto, "lastIndexOf", 1) { self, args ->
        val items = realm.elements(self, "lastIndexOf")
        val target = args.arg(0)
        val n = if (args.size > 1) realm.toIntegerOrInfinity(args[1]) else items.size - 1.0
        var i = if (n >= 0) minOf(n, items.size - 1.0) else items.size + n
        var result = -1.0
        while (i >= 0) {
            if (jsStrictEq(items[i.toInt()], target)) {
                result = i
                break
            }
            i--
        }
        result
    }
    realm.defineMethod(proto, "includes", 1) { self, args ->
        val items = realm.elements(self, "includes")
        val target = args.arg(0)
        var i = if (args.size > 1) searchStart(realm.toIntegerOrInfinity(args[1]), items.size) else 0
        var found = false
        while (i < items.size) {
            if (sameValueZero(items[i], target)) {
                found = true
                break
            }
            i++
        }
        found
    }
    realm.defineMethod(proto, "keys", 0) { self, _ ->
        val a = realm.arrayReceiver(self, "keys")
        JsIterator(std.iteratorPrototype, "Array Iterator", liveIterator(a) { i, _ -> i.toDouble() })
    }
    realm.defineMethod(proto, "values", 0) { self, _ ->
        val a = realm.arrayReceiver(self, "values")
        JsIterator(std.iteratorPrototype, "Array Iterator", liveIterator(a) { _, v -> v })
    }
    realm.defineMethod(proto, "entries", 0) { self, _ ->
        val a = realm.arrayReceiver(self, "entries")
        JsIterator(std.iteratorPrototype, "Array Iterator", liveIterator(a) { i, v -> realm.newArray(listOf(i.toDouble(), v)) })
    }
    realm.defineMethod(proto, "toLocaleString", 0) { self, _ ->
        realm.elements(self, "toLocaleString").joinToString(",") { v ->
            if (isNullish(v)) "" else realm.toStr(realm.callFunction(realm.toObject(v).get("toLocaleString"), v, emptyList()))
        }
    }
    realm.defineMethod(proto, "flat", 0) { self, args ->
        val items = realm.elements(self, "flat")
        val depth = if (args.arg(0) === Undefined) 1.0 else realm.toIntegerOrInfinity(args[0])
        val out = ArrayList<Any?>()
        flatten(realm, items, depth, out)
        JsArray(realm, out)
    }
    realm.defineMethod(proto, "toReversed", 0) { self, _ -> JsArray(realm, ArrayList(realm.elements(self, "toReversed")).also { it.reverse() }) }
    realm.defineMethod(proto, "toSorted", 1) { self, args ->
        val compare = validatedComparator(realm, args.arg(0))
        val copy = ArrayList(realm.elements(self, "toSorted"))
        sortValues(realm, copy, compare)
        JsArray(realm, copy)
    }
    realm.defineMethod(proto, "toSpliced", 2) { self, args ->
        val copy = ArrayList(realm.elements(self, "toSpliced"))
        splice(realm, copy, args)
        JsArray(realm, copy)
    }
    realm.defineMethod(proto, "with", 2) { self, args ->
        val copy = ArrayList(realm.elements(self, "with"))
        val rel = realm.toIntegerOrInfinity(args.arg(0))
        val k = if (rel >= 0) rel else copy.size + rel
        if (k < 0 || k >= copy.size) throw realm.rangeError("Invalid index : ${jsNumberToString(rel)}")
        copy[k.toInt()] = args.arg(1)
        JsArray(realm, copy)
    }
}

/** Where a search (`indexOf`, `includes`) starts: a negative position counts from the end, and the result is within `0..length`. */
private fun searchStart(n: Double, len: Int): Int = if (n < 0) maxOf(len + n, 0.0).toInt() else minOf(n, len.toDouble()).toInt()

private fun flatten(realm: Realm, items: List<Any?>, depth: Double, out: MutableList<Any?>) {
    for (v in items.toList()) {
        if (v is JsArray && depth >= 1) flatten(realm, v.items, depth - 1, out) else out.add(v)
        if (out.size > MAX_ARRAY_LENGTH) throw realm.rangeError("Invalid array length")
    }
}

// ---- walking with a callback --------------------------------------------------------------------------------------------

private fun installIteration(realm: Realm, proto: JsObject) {
    realm.defineMethod(proto, "forEach", 1) { self, args ->
        val items = realm.elements(self, "forEach")
        val fn = realm.callable(args.arg(0))
        val thisArg = args.arg(1)
        val n = items.size
        for (i in 0 until n) {
            if (i >= items.size) break
            callback(realm, fn, thisArg, items[i], i, self)
        }
        Undefined
    }
    realm.defineMethod(proto, "map", 1) { self, args ->
        val items = realm.elements(self, "map")
        val fn = realm.callable(args.arg(0))
        val thisArg = args.arg(1)
        val n = items.size
        val out = ArrayList<Any?>(n)
        for (i in 0 until n) out.add(if (i < items.size) callback(realm, fn, thisArg, items[i], i, self) else Undefined)
        JsArray(realm, out)
    }
    realm.defineMethod(proto, "filter", 1) { self, args ->
        val items = realm.elements(self, "filter")
        val fn = realm.callable(args.arg(0))
        val thisArg = args.arg(1)
        val out = ArrayList<Any?>()
        val n = items.size
        for (i in 0 until n) {
            if (i >= items.size) break
            val v = items[i]
            if (jsTruthy(callback(realm, fn, thisArg, v, i, self))) out.add(v)
        }
        JsArray(realm, out)
    }
    realm.defineMethod(proto, "flatMap", 1) { self, args ->
        val items = realm.elements(self, "flatMap")
        val fn = realm.callable(args.arg(0))
        val thisArg = args.arg(1)
        val out = ArrayList<Any?>()
        val n = items.size
        for (i in 0 until n) {
            if (i >= items.size) break
            val r = callback(realm, fn, thisArg, items[i], i, self)
            if (r is JsArray) out.addAll(r.items) else out.add(r)
            if (out.size > MAX_ARRAY_LENGTH) throw realm.rangeError("Invalid array length")
        }
        JsArray(realm, out)
    }
    realm.defineMethod(proto, "some", 1) { self, args ->
        val items = realm.elements(self, "some")
        val fn = realm.callable(args.arg(0))
        val thisArg = args.arg(1)
        var result = false
        val n = items.size
        for (i in 0 until n) {
            if (i >= items.size) break
            if (jsTruthy(callback(realm, fn, thisArg, items[i], i, self))) {
                result = true
                break
            }
        }
        result
    }
    realm.defineMethod(proto, "every", 1) { self, args ->
        val items = realm.elements(self, "every")
        val fn = realm.callable(args.arg(0))
        val thisArg = args.arg(1)
        var result = true
        val n = items.size
        for (i in 0 until n) {
            if (i >= items.size) break
            if (!jsTruthy(callback(realm, fn, thisArg, items[i], i, self))) {
                result = false
                break
            }
        }
        result
    }
    fun finder(name: String, fromEnd: Boolean, wantIndex: Boolean) {
        realm.defineMethod(proto, name, 1) { self, args ->
            val items = realm.elements(self, name)
            val fn = realm.callable(args.arg(0))
            val thisArg = args.arg(1)
            val n = items.size
            var found: Any? = if (wantIndex) -1.0 else Undefined
            for (k in 0 until n) {
                val i = if (fromEnd) n - 1 - k else k
                val v = if (i < items.size) items[i] else Undefined
                if (jsTruthy(callback(realm, fn, thisArg, v, i, self))) {
                    found = if (wantIndex) i.toDouble() else v
                    break
                }
            }
            found
        }
    }
    finder("find", fromEnd = false, wantIndex = false)
    finder("findIndex", fromEnd = false, wantIndex = true)
    finder("findLast", fromEnd = true, wantIndex = false)
    finder("findLastIndex", fromEnd = true, wantIndex = true)

    fun reducer(name: String, fromEnd: Boolean) {
        realm.defineMethod(proto, name, 1) { self, args ->
            val items = realm.elements(self, name)
            val fn = realm.callable(args.arg(0))
            val n = items.size
            var k = 0
            var acc: Any?
            if (args.size > 1) {
                acc = args[1]
            } else {
                if (n == 0) throw realm.typeError("Reduce of empty array with no initial value")
                acc = items[if (fromEnd) n - 1 else 0]
                k = 1
            }
            while (k < n) {
                val i = if (fromEnd) n - 1 - k else k
                val v = if (i < items.size) items[i] else Undefined
                acc = realm.callFunction(fn, Undefined, listOf(acc, v, i.toDouble(), self))
                k++
            }
            acc
        }
    }
    reducer("reduce", fromEnd = false)
    reducer("reduceRight", fromEnd = true)
}

// ---- changing an array --------------------------------------------------------------------------------------------------

private fun installMutation(realm: Realm, std: StdRealm, proto: JsObject) {
    realm.defineMethod(proto, "push", 1) { self, args ->
        val a = realm.arrayReceiver(self, "push")
        realm.checkGrowable(a, std, args.size)
        a.items.addAll(args)
        a.items.size.toDouble()
    }
    realm.defineMethod(proto, "pop", 0) { self, _ ->
        val a = realm.arrayReceiver(self, "pop")
        if (a.items.isEmpty()) {
            Undefined
        } else {
            realm.checkShrinkable(a, std)
            a.items.removeAt(a.items.size - 1)
        }
    }
    realm.defineMethod(proto, "shift", 0) { self, _ ->
        val a = realm.arrayReceiver(self, "shift")
        if (a.items.isEmpty()) {
            Undefined
        } else {
            realm.checkWritable(a, std)
            a.items.removeAt(0)
        }
    }
    realm.defineMethod(proto, "unshift", 1) { self, args ->
        val a = realm.arrayReceiver(self, "unshift")
        realm.checkGrowable(a, std, args.size)
        a.items.addAll(0, args)
        a.items.size.toDouble()
    }
    realm.defineMethod(proto, "splice", 2) { self, args ->
        val a = realm.arrayReceiver(self, "splice")
        val after = ArrayList(a.items)
        val taken = splice(realm, after, args)
        val growth = after.size - a.items.size
        realm.checkGrowable(a, std, growth)
        if (taken.isNotEmpty() || growth != 0) realm.checkWritable(a, std)
        a.items.clear()
        a.items.addAll(after)
        JsArray(realm, taken)
    }
    realm.defineMethod(proto, "reverse", 0) { self, _ ->
        val a = realm.arrayReceiver(self, "reverse")
        if (a.items.size > 1) realm.checkWritable(a, std)
        a.items.reverse()
        a
    }
    realm.defineMethod(proto, "fill", 1) { self, args ->
        val a = realm.arrayReceiver(self, "fill")
        val len = a.items.size
        val start = jsSliceIndex(realm.toNumber(args.arg(1)), len)
        val end = if (args.arg(2) === Undefined) len else jsSliceIndex(realm.toNumber(args.arg(2)), len)
        if (start < end) realm.checkWritable(a, std, start)
        for (i in start until end) a.items[i] = args.arg(0)
        a
    }
    realm.defineMethod(proto, "copyWithin", 2) { self, args ->
        val a = realm.arrayReceiver(self, "copyWithin")
        val len = a.items.size
        val to = jsSliceIndex(realm.toNumber(args.arg(0)), len)
        val from = jsSliceIndex(realm.toNumber(args.arg(1)), len)
        val end = if (args.arg(2) === Undefined) len else jsSliceIndex(realm.toNumber(args.arg(2)), len)
        val count = minOf(end - from, len - to)
        if (count > 0) {
            realm.checkWritable(a, std, to)
            val chunk = ArrayList(a.items.subList(from, from + count))
            for (i in 0 until count) a.items[to + i] = chunk[i]
        }
        a
    }
    realm.defineMethod(proto, "sort", 1) { self, args ->
        val compare = validatedComparator(realm, args.arg(0))
        val a = realm.arrayReceiver(self, "sort")
        if (a.items.size > 1) realm.checkWritable(a, std)
        val copy = ArrayList(a.items)
        sortValues(realm, copy, compare)
        for (i in copy.indices) if (i < a.items.size) a.items[i] = copy[i]
        a
    }
}

/**
 * `Array.prototype.splice` on [list]: changes it and returns the elements taken out. [args] is `(start, deleteCount, ...items)`
 * as the program gave them.
 */
private fun splice(realm: Realm, list: MutableList<Any?>, args: List<Any?>): ArrayList<Any?> {
    val len = list.size
    val start = if (args.isEmpty()) 0 else jsSliceIndex(realm.toNumber(args[0]), len)
    val deleteCount = when {
        args.isEmpty() -> 0
        args.size == 1 -> len - start
        else -> realm.toIntegerOrInfinity(args[1]).let { if (it < 0) 0 else minOf(it, (len - start).toDouble()).toInt() }
    }
    val inserted = if (args.size > 2) args.subList(2, args.size) else emptyList()
    if (len.toLong() - deleteCount + inserted.size > MAX_ARRAY_LENGTH) throw realm.rangeError("Invalid array length")
    val taken = ArrayList(list.subList(start, start + deleteCount))
    list.subList(start, start + deleteCount).clear()
    list.addAll(start, inserted)
    return taken
}

// ---- sorting ------------------------------------------------------------------------------------------------------------

/** The argument of `sort`: a function, or `undefined` for the default order; anything else is the `TypeError` V8 throws. */
private fun validatedComparator(realm: Realm, compare: Any?): Any? {
    if (compare === Undefined || compare is JsFunction || compare is JsFn) return compare
    throw realm.typeError("The comparison function must be either a function or undefined: ${describePlain(realm, compare)}")
}

private class Keyed(val key: String, val value: Any?)

/**
 * Sorts [list] in place the way V8 does (see [TimSort]). `undefined` goes last without being compared; [compare] is the
 * function of the program (already validated), or `undefined` for the default order, which compares the elements as strings
 * by UTF-16 code units.
 */
internal fun sortValues(realm: Realm, list: MutableList<Any?>, compare: Any?) {
    val defined = ArrayList<Any?>(list.size)
    var undefinedCount = 0
    for (v in list) if (v === Undefined) undefinedCount++ else defined.add(v)
    val sorted: Array<Any?>
    if (compare === Undefined) {
        val keyed = Array<Any?>(defined.size) { Keyed(realm.toStr(defined[it]), defined[it]) }
        TimSort(keyed) { x, y -> (x as Keyed).key.compareTo((y as Keyed).key) }.sort()
        sorted = Array(keyed.size) { (keyed[it] as Keyed).value }
    } else {
        sorted = defined.toTypedArray()
        TimSort(sorted) { x, y ->
            val v = realm.toNumber(realm.callFunction(compare, Undefined, listOf(x, y)))
            if (v < 0) -1 else if (v > 0) 1 else 0
        }.sort()
    }
    list.clear()
    list.addAll(sorted)
    repeat(undefinedCount) { list.add(Undefined) }
}
