package cosic.visual

import cosic.pattern.Undefined
import cosic.pattern.jsToNumber
import cosic.pattern.jsToString
import cosic.pattern.jsTruthy

/**
 * The options object a program gave a visual (`._pianoroll({ cycles: 8, vertical: 1 })`), read the way the destructuring of Strudel's
 * functions reads it: a default applies only when the key is absent or `undefined`, and anything else is taken as the program wrote it
 * (`null`, `0` and `""` included) and coerced as JavaScript would when the value is used.
 */
internal class VisualOptions(val map: Map<String, Any?> = emptyMap()) {
    /** The value of [key], [Undefined] when there is none. */
    fun raw(key: String): Any? = if (map.containsKey(key)) map[key] else Undefined

    /** Whether the program set [key] to something other than `undefined`. */
    fun isSet(key: String): Boolean = raw(key) !== Undefined

    fun rawOr(key: String, default: Any?): Any? = raw(key).let { if (it === Undefined) default else it }

    fun num(key: String, default: Double): Double = raw(key).let { if (it === Undefined) default else jsToNumber(it) }

    /** The number [key] holds, or null when it is `undefined` or `null` (a `??` test). */
    fun numOrNull(key: String): Double? = raw(key).let { if (it === Undefined || it == null) null else jsToNumber(it) }

    /** The truthiness of [key], [default] when it is `undefined`: for options JavaScript tests with `if (x)` or `x ? a : b`. */
    fun flag(key: String, default: Boolean): Boolean = raw(key).let { if (it === Undefined) default else jsTruthy(it) }

    fun text(key: String, default: String): String = raw(key).let { if (it === Undefined) default else jsToString(it) }

    /** The text of [key], or null when it is falsy (`undefined`, `null`, `""`, `0`): for `x || default`. */
    fun textOrNull(key: String): String? = raw(key).let { if (jsTruthy(it)) jsToString(it) else null }

    /** `{...this, ...other}`: [other] wins. */
    fun plus(other: Map<String, Any?>): VisualOptions = VisualOptions(LinkedHashMap(map).also { it.putAll(other) })

    /** The same options without [key]. */
    fun without(key: String): VisualOptions = VisualOptions(LinkedHashMap(map).also { it.remove(key) })

    companion object {
        val EMPTY = VisualOptions()
    }
}

/** `value[key]` for a value that may be a control map: [Undefined] when it is not a map or has no such key. */
internal fun propOf(value: Any?, key: String): Any? {
    val m = value as? Map<*, *> ?: return Undefined
    return if (m.containsKey(key)) m[key] else Undefined
}
