package cosic.script.js

import cosic.pattern.JsFn
import cosic.pattern.Undefined
import cosic.pattern.jsLess
import cosic.pattern.jsLooseEq
import cosic.pattern.jsNumberToString
import cosic.pattern.jsToInt32
import cosic.pattern.jsToNumber
import cosic.pattern.jsToPrimitive
import cosic.pattern.jsToString
import cosic.script.Realm

/*
 * Conversions and operators that need the object protocol (ECMA-262 `ToPrimitive`, `valueOf`, `toString`), and so a
 * realm to throw errors in. Everything that is about primitives only is the kernel's: `jsTruthy`, `jsTypeof`,
 * `jsStrictEq`, `jsNumberToString`, `jsStringToNumber`, `jsToInt32`, `jsLess`, `jsLooseEq` in `pattern/JsValue.kt`;
 * these functions call them once the objects are out of the way.
 */

/** The longest string a program can make; more is a `RangeError` (`Invalid string length`) instead of an out-of-memory crash. */
internal const val MAX_STRING_LENGTH = 1 shl 25

/** The hint of `ToPrimitive`: which of `valueOf` and `toString` is tried first. */
internal enum class Hint { DEFAULT, NUMBER, STRING }

internal fun isNullish(v: Any?): Boolean = v == null || v === Undefined

/** Whether [v] is a primitive value (not an object of any kind). */
internal fun isPrimitive(v: Any?): Boolean = v == null || v === Undefined || v is String || v is Double || v is Boolean || v is Number

/** `ToPrimitive`: an object becomes the result of its `valueOf` or `toString`, whichever the [hint] asks for first and returns a primitive. */
internal fun Realm.toPrimitive(v: Any?, hint: Hint = Hint.DEFAULT): Any? {
    if (isPrimitive(v)) return v
    if (v !is JsObject) return jsToPrimitive(v)
    val order = if (hint == Hint.STRING) arrayOf("toString", "valueOf") else arrayOf("valueOf", "toString")
    for (name in order) {
        val method = v.get(name, v)
        val result = when (method) {
            is JsFunction -> method.call(v, emptyList())
            is JsFn -> method.call(emptyList())
            else -> continue
        }
        if (isPrimitive(result)) return result
    }
    throw typeError("Cannot convert object to primitive value")
}

/** `Number(v)`. */
internal fun Realm.toNumber(v: Any?): Double = when (v) {
    is Double -> v
    is JsObject -> jsToNumber(toPrimitive(v, Hint.NUMBER))
    else -> jsToNumber(v)
}

/** `String(v)`. */
internal fun Realm.toStr(v: Any?): String = when (v) {
    is String -> v
    is JsObject -> jsToString(toPrimitive(v, Hint.STRING))
    else -> jsToString(v)
}

/** The key `object[v]` looks up: numbers as `jsNumberToString` writes them, everything else as a string. */
internal fun Realm.toPropertyKey(v: Any?): String = when (v) {
    is String -> v
    is Double -> jsNumberToString(v)
    else -> toStr(v)
}

internal fun Realm.toInt32(v: Any?): Int = jsToInt32(toNumber(v))

/** `ToUint32`: [toInt32] read as an unsigned number. */
internal fun Realm.toUint32(v: Any?): Long = toInt32(v).toLong() and 0xFFFFFFFFL

/** `a + b`: string concatenation when either side is a string once it is a primitive, else addition. */
internal fun Realm.add(a: Any?, b: Any?): Any? {
    if (a is Double && b is Double) return a + b
    val x = toPrimitive(a)
    val y = toPrimitive(b)
    if (x is String || y is String) {
        val s = if (x is String) x else toStr(x)
        val t = if (y is String) y else toStr(y)
        if (s.length.toLong() + t.length > MAX_STRING_LENGTH) throw rangeError("Invalid string length")
        return s + t
    }
    return jsToNumber(x) + jsToNumber(y)
}

/** `a == b`: the kernel's rules for primitives, and objects compared with a primitive by their primitive value. */
internal fun Realm.looseEquals(a: Any?, b: Any?): Boolean {
    if (isPrimitive(a) && isPrimitive(b)) return jsLooseEq(a, b)
    if (isNullish(a) || isNullish(b)) return false
    if (a is Boolean) return looseEquals(if (a) 1.0 else 0.0, b)
    if (b is Boolean) return looseEquals(a, if (b) 1.0 else 0.0)
    val aObject = !isPrimitive(a)
    val bObject = !isPrimitive(b)
    if (aObject && bObject) return a === b
    return if (aObject) looseEquals(toPrimitive(a), b) else looseEquals(a, toPrimitive(b))
}

/** The abstract relational comparison `a < b`: true or false, or null when a NaN took part (then every comparison is false). */
internal fun Realm.lessThan(a: Any?, b: Any?): Boolean? {
    val x = toPrimitive(a, Hint.NUMBER)
    val y = toPrimitive(b, Hint.NUMBER)
    return jsLess(x, y)
}

/** `value instanceof constructor`. */
internal fun Realm.instanceOf(value: Any?, constructor: Any?): Boolean {
    if (constructor !is JsObject) throw typeError("Right-hand side of 'instanceof' is not an object")
    if (constructor !is JsFunction) throw typeError("Right-hand side of 'instanceof' is not callable")
    val target = if (constructor is BoundFunction) constructor.target else constructor
    if (value !is JsObject) return false
    val proto = target.get("prototype", target)
    if (proto !is JsObject) throw typeError("Function has non-object prototype '${toStr(proto)}' in instanceof check")
    var o = value.prototype
    while (o != null) {
        if (o === proto) return true
        o = o.prototype
    }
    return false
}

/** `key in object`. */
internal fun Realm.hasProperty(obj: Any?, key: Any?): Boolean {
    if (obj !is JsObject) throw typeError("Cannot use 'in' operator to search for '${toStr(key)}' in ${toStr(obj)}")
    return obj.has(toPropertyKey(key))
}

/** `x ** y`: `Math.pow` with the special cases of ECMAScript (`1 ** Infinity` is NaN, `NaN ** 0` is 1). */
internal fun jsPow(x: Double, y: Double): Double {
    if (y.isNaN()) return Double.NaN
    if (y == 0.0) return 1.0
    if ((x == 1.0 || x == -1.0) && y.isInfinite()) return Double.NaN
    return StrictMath.pow(x, y)
}

/** `a >>> b`. */
internal fun jsUnsignedShiftRight(a: Int, b: Int): Double = ((a.toLong() and 0xFFFFFFFFL) ushr (b and 31)).toDouble()

/** How a value reads in an error message: strings quoted, objects as `#<Object>`, the rest as `String(value)`. */
internal fun Realm.describe(v: Any?): String = when (v) {
    is String -> "\"$v\""
    is JsArray -> "[object Array]"
    is JsFunction -> if (v.name.isEmpty()) "function" else "function ${v.name}"
    is JsObject -> "#<${v.className}>"
    else -> jsToString(v)
}

/** The elements of [value] as `for of`, spread and array destructuring read them, or null when it is not iterable. A string reads by code points. */
internal fun iteratorOf(value: Any?): Iterator<Any?>? = when (value) {
    is JsObject -> value.jsIterator()
    is String -> object : Iterator<Any?> {
        private var i = 0
        override fun hasNext(): Boolean = i < value.length
        override fun next(): Any? {
            val cp = value.codePointAt(i)
            val n = Character.charCount(cp)
            val s = value.substring(i, i + n)
            i += n
            return s
        }
    }
    else -> null
}
