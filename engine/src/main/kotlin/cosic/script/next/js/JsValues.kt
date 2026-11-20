package cosic.script.next.js

import cosic.SourceRange
import cosic.pattern.Undefined

/*
 * The values of the script interpreter (K3 of the parity plan): there is no value class, a JavaScript value is a Kotlin
 * `Any?`.
 *
 * - `undefined` is [Undefined], the kernel's one sentinel ([JsUndefined] is its name here); JavaScript `null` is Kotlin `null`;
 * - a boolean is a `Boolean`, a number a `Double` (the interpreter never makes any other kind of number), a string a `String`;
 * - an object is a [JsObject]: arrays are [JsArray], functions [JsFunction], errors are plain objects over an error prototype;
 * - values from the pattern kernel that reach a program unconverted (`Rational`, `List`, `Map`, `Pattern`, a bare `JsFn`)
 *   are opaque: they have no properties, and a [cosic.pattern.JsFn] can be called. The host bridge wraps them as objects.
 */

/** Any JavaScript value. */
internal typealias JsValue = Any?

/** JavaScript's `undefined`: the kernel's single sentinel, `cosic.pattern.Undefined`. */
internal typealias JsUndefined = Undefined

/** The classes of error the language itself throws, with the name `error.name` has. */
internal enum class ErrorKind(val jsName: String) {
    ERROR("Error"),
    TYPE("TypeError"),
    RANGE("RangeError"),
    REFERENCE("ReferenceError"),
    SYNTAX("SyntaxError"),
    EVAL("EvalError"),
    URI("URIError"),
}

/** The attributes of a property, as the bits of [Prop.flags]. */
internal object PropFlags {
    const val WRITABLE = 1
    const val ENUMERABLE = 2
    const val CONFIGURABLE = 4

    /** What an assignment or an object literal makes. */
    const val ALL = WRITABLE or ENUMERABLE or CONFIGURABLE

    /** What the methods of a built-in have: they can be replaced, but are not listed by `for in` or `Object.keys`. */
    const val HIDDEN = WRITABLE or CONFIGURABLE

    /** What `length` and `name` of a function have. */
    const val READ_ONLY = CONFIGURABLE
}

/**
 * A JavaScript `throw`: [value] is whatever was thrown (an error object for everything the interpreter and the library
 * throw, but a program may throw any value) and [range] where in the program it was thrown, when known. An error that is
 * thrown by a native without a range gets the range of the innermost call in the program that was running (see
 * [withRange]); the interpreter fills it in as the exception passes the call.
 *
 * It has no stack trace: it is control flow, and creating one must be cheap.
 */
internal class JsThrow(@JvmField val value: Any?, @JvmField val range: SourceRange? = null) :
    RuntimeException(null, null, false, false) {

    /** This throw, or a copy that carries [at] when it has no range yet. */
    fun withRange(at: SourceRange?): JsThrow = if (range != null || at == null) this else JsThrow(value, at)

    override val message: String get() = describeThrown(value)
}

/** How a thrown value reads when it escapes the program: `TypeError: x is not a function` for errors, `String(value)` otherwise. */
internal fun describeThrown(value: Any?): String {
    if (value is JsObject && value.className == "Error") {
        val name = value.get("name")
        val message = value.get("message")
        val n = if (name is String && name.isNotEmpty()) name else "Error"
        return if (message is String && message.isNotEmpty()) "$n: $message" else n
    }
    return cosic.pattern.jsToString(value)
}

/**
 * The program used up its step budget or its time (`Script took too long`) or an instance of something else that must
 * not be caught by the program's own `try`/`catch`. It passes through `catch` and `finally` blocks and ends the
 * evaluation; [range] is the statement that was running.
 */
internal class ScriptLimitExceeded(val reason: String, val range: SourceRange?) :
    RuntimeException(reason, null, false, false)
