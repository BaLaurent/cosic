package cosic.script.js.stdlib

import cosic.pattern.JsFn
import cosic.pattern.Undefined
import cosic.script.Realm
import cosic.script.js.JsFunction
import cosic.script.js.JsObject
import cosic.script.js.toNumber

/*
 * `setTimeout`, `setInterval`, `clearTimeout`, `clearInterval`, `queueMicrotask`. The timers live in the realm's event loop
 * (StdPromise.kt), which runs a due timer on the thread that waits in `await` or, when nobody does, on the host's, which
 * `ScriptHost.runLater` wakes. A delay is at least 1 ms and at most 2^31 - 1 ms, like in Node. The timer is a number.
 */

private const val MAX_DELAY = 2_147_483_647.0

internal fun installTimers(realm: Realm, std: StdRealm) {
    fun callbackOf(v: Any?): Any {
        if (v is JsFunction || v is JsFn) return v
        throw realm.typeError("The \"callback\" argument must be of type function. Received ${received(realm, v)}")
    }

    fun delayOf(v: Any?): Long {
        val d = realm.toNumber(v)
        return if (d >= 1 && d <= MAX_DELAY) d.toLong() else 1L
    }

    realm.defineGlobalFunction("setTimeout", 2) { _, args ->
        std.loop.addTimer(callbackOf(args.arg(0)), args.drop(2), delayOf(args.arg(1)), repeat = false)
    }
    realm.defineGlobalFunction("setInterval", 2) { _, args ->
        std.loop.addTimer(callbackOf(args.arg(0)), args.drop(2), delayOf(args.arg(1)), repeat = true)
    }
    for (name in listOf("clearTimeout", "clearInterval")) {
        realm.defineGlobalFunction(name, 1) { _, args ->
            val id = args.arg(0)
            if (id is Double) std.loop.clearTimer(id)
            Undefined
        }
    }
    realm.defineGlobalFunction("queueMicrotask", 1) { _, args ->
        val callback = callbackOf(args.arg(0))
        std.loop.post { realm.callFunction(callback, Undefined, emptyList()) }
        Undefined
    }
}

/** Node's `determineSpecificType`: how an argument of the wrong type is described (`undefined`, `type number (5)`, `an instance of Object`). */
private fun received(realm: Realm, v: Any?): String = when {
    v == null -> "null"
    v === Undefined -> "undefined"
    v is JsFunction && v.name.isNotEmpty() -> "function ${v.name}"
    v is JsObject -> {
        var p: JsObject? = v
        var name: String? = null
        while (p != null && name == null) {
            val c = p.getOwnProp("constructor")?.value
            if (c is JsFunction && c.name.isNotEmpty()) name = c.name
            p = p.prototype
        }
        if (name != null) "an instance of $name" else realm.inspect(v, 0)
    }
    else -> {
        var shown = realm.inspect(v)
        if (shown.length > 28) shown = shown.take(25) + "..."
        "type ${cosic.pattern.jsTypeof(v)} ($shown)"
    }
}
