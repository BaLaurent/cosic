package cosic.script.next.js.stdlib

import cosic.pattern.Undefined
import cosic.pattern.jsToFixed
import cosic.pattern.jsTruthy
import cosic.script.next.LogLevel
import cosic.script.next.Realm
import cosic.script.next.js.JsObject
import cosic.script.next.js.toStr

/*
 * `console`. Every method writes through `ScriptHost.log`, formatted like Node's: `console.log('%s is %d', 'x', 5)`,
 * `console.log({ a: 1 })` prints `{ a: 1 }`. `group` indents what follows by two spaces. `console.table` and the like
 * print their argument like `log`.
 */

internal class ConsoleState {
    var groupIndent = ""
    val counts = HashMap<String, Int>()
    val timers = HashMap<String, Double>()
}

internal fun installConsole(realm: Realm, std: StdRealm) {
    val console = JsNamespace(realm, "console")
    realm.defineGlobal("console", console)
    val state = std.console

    fun write(level: LogLevel, text: String) {
        val indent = state.groupIndent
        realm.host.log(if (indent.isEmpty()) text else indent + text.replace("\n", "\n$indent"), level)
    }

    fun printer(name: String, level: LogLevel) {
        realm.defineMethod(console, name, 0) { _, args ->
            write(level, realm.formatLog(args))
            Undefined
        }
    }
    printer("log", LogLevel.INFO)
    printer("info", LogLevel.INFO)
    printer("debug", LogLevel.DEBUG)
    printer("warn", LogLevel.WARNING)
    printer("error", LogLevel.ERROR)
    printer("table", LogLevel.INFO)
    printer("dirxml", LogLevel.INFO)

    realm.defineMethod(console, "dir", 0) { _, args ->
        val options = args.arg(1)
        var depth: Int? = 2
        if (options is JsObject && options.has("depth")) {
            val d = options.get("depth")
            depth = if (d == null) null else realm.toIntegerOrInfinity(d).let { if (it.isInfinite()) null else it.toInt() }
        }
        write(LogLevel.INFO, realm.inspect(args.arg(0), depth))
        Undefined
    }
    realm.defineMethod(console, "trace", 0) { _, args ->
        val text = realm.formatLog(args)
        write(LogLevel.ERROR, if (text.isEmpty()) "Trace" else "Trace: $text")
        Undefined
    }
    realm.defineMethod(console, "assert", 0) { _, args ->
        if (!jsTruthy(args.arg(0))) {
            val rest = args.drop(1)
            val message = if (rest.isNotEmpty() && rest[0] is String) {
                listOf("Assertion failed: ${rest[0]}") + rest.drop(1)
            } else {
                listOf<Any?>("Assertion failed") + rest
            }
            write(LogLevel.WARNING, realm.formatLog(message))
        }
        Undefined
    }
    realm.defineMethod(console, "clear", 0) { _, _ -> Undefined }

    fun label(v: Any?): String = if (v === Undefined) "default" else realm.toStr(v)

    realm.defineMethod(console, "count", 0) { _, args ->
        val l = label(args.arg(0))
        val n = (state.counts[l] ?: 0) + 1
        state.counts[l] = n
        write(LogLevel.INFO, "$l: $n")
        Undefined
    }
    realm.defineMethod(console, "countReset", 0) { _, args ->
        val l = label(args.arg(0))
        if (state.counts.remove(l) == null) write(LogLevel.WARNING, "Count for '$l' does not exist")
        Undefined
    }

    fun group(args: List<Any?>) {
        if (args.isNotEmpty()) write(LogLevel.INFO, realm.formatLog(args))
        state.groupIndent += "  "
    }
    realm.defineMethod(console, "group", 0) { _, args -> group(args); Undefined }
    realm.defineMethod(console, "groupCollapsed", 0) { _, args -> group(args); Undefined }
    realm.defineMethod(console, "groupEnd", 0) { _, _ ->
        state.groupIndent = state.groupIndent.dropLast(2)
        Undefined
    }

    fun nowMillis(): Double = realm.host.nowSeconds() * 1000.0
    realm.defineMethod(console, "time", 0) { _, args ->
        val l = label(args.arg(0))
        if (state.timers.containsKey(l)) write(LogLevel.WARNING, "Timer '$l' already exists") else state.timers[l] = nowMillis()
        Undefined
    }
    fun elapsed(name: String, args: List<Any?>, end: Boolean) {
        val l = label(args.arg(0))
        val start = if (end) state.timers.remove(l) else state.timers[l]
        if (start == null) {
            write(LogLevel.WARNING, "No such label '$l' for console.$name()")
            return
        }
        val extra = if (end || args.size < 2) "" else " " + realm.formatLog(args.drop(1))
        write(LogLevel.INFO, "$l: ${formatTime(nowMillis() - start)}$extra")
    }
    realm.defineMethod(console, "timeEnd", 0) { _, args -> elapsed("timeEnd", args, end = true); Undefined }
    realm.defineMethod(console, "timeLog", 0) { _, args -> elapsed("timeLog", args, end = false); Undefined }
}

/** Node's `formatTime`: `1.234s`, `12.5ms`, or `m:ss.mmm (m:ss.mmm)` from a minute up. */
private fun formatTime(millis: Double): String {
    var ms = millis
    var hours = 0.0
    var minutes = 0.0
    var seconds = 0.0
    if (ms >= 1000) {
        if (ms >= 60_000) {
            if (ms >= 3_600_000) {
                hours = Math.floor(ms / 3_600_000)
                ms %= 3_600_000
            }
            minutes = Math.floor(ms / 60_000)
            ms %= 60_000
        }
        seconds = ms / 1000
    }
    if (hours != 0.0 || minutes != 0.0) {
        val parts = jsToFixed(seconds, 3).split('.')
        val head = if (hours != 0.0) "${hours.toInt()}:${minutes.toInt().toString().padStart(2, '0')}" else minutes.toInt().toString()
        return "$head:${parts[0].padStart(2, '0')}.${parts[1]} (${if (hours != 0.0) "h:m" else ""}m:ss.mmm)"
    }
    if (seconds != 0.0) return jsToFixed(seconds, 3) + "s"
    return cosic.pattern.jsNumberToString(jsToFixed(ms, 3).toDouble()) + "ms"
}
