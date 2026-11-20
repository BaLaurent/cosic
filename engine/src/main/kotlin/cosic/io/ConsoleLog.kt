package cosic.io

/** The console shows at most this many lines (`log.slice(-40)` of useLogger.jsx). */
private const val MAX_ENTRIES = 40

/** An identical message that follows the previous one within this many milliseconds is not logged at all (`debounce` of logger.mjs). */
private const val DEBOUNCE_MILLIS = 1000L

/**
 * One line of the console. [type] is `"error"` (shown inverted), `"warning"`, `"debug"`, `"highlight"` (underlined) or null; [color]
 * tints the line (the `color` of the hap a `log()` was written for); [count] is how many times the message was written in a row, 1
 * for a line written once (the console shows `(count)` after a repeated one). [id] is new for every change of a line, so a list
 * can tell lines apart.
 */
data class LogEntry(val id: Long, val message: String, val type: String?, val count: Int, val color: String?)

/**
 * What the program, the engine and the pattern being played write to the console, the way the web REPL keeps it: the last 40
 * lines (`useLogger.jsx`), where a message that repeats the previous line only raises its count, and one that repeats it within
 * a second is dropped before that (`logger` of core/logger.mjs, so a failing pattern does not write sixteen lines per cycle).
 *
 * [log] may be called from any thread, but not from the audio thread, which posts what it wants logged through the
 * [OutputBus] instead. [entries] is a snapshot that never changes; read it from any thread.
 */
class ConsoleLog(private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L }) {
    private val lock = Any()
    private var lastMessage: String? = null
    private var lastTime = 0L
    private var nextId = 1L

    /** The lines, oldest first, at most 40. The same list comes back until a line is written. */
    @Volatile
    var entries: List<LogEntry> = emptyList()
        private set

    fun log(message: String, type: String? = null, color: String? = null) {
        synchronized(lock) {
            val now = nowMillis()
            if (message == lastMessage && now - lastTime < DEBOUNCE_MILLIS) return
            lastMessage = message
            lastTime = now
            val lines = entries
            val last = lines.lastOrNull()
            val next = ArrayList<LogEntry>(minOf(lines.size + 1, MAX_ENTRIES))
            if (last != null && last.message == message) {
                next.addAll(lines.subList(0, lines.size - 1))
                next.add(LogEntry(nextId++, message, type, last.count + 1, color))
            } else {
                next.addAll(lines)
                next.add(LogEntry(nextId++, message, type, 1, color))
            }
            while (next.size > MAX_ENTRIES) next.removeAt(0)
            entries = next
        }
    }

    /** `errorLogger(e, origin)`: `[origin] error: message`, as an error line. */
    fun error(origin: String, e: Throwable) = error(origin, e.message ?: e.toString())

    fun error(origin: String, message: String) = log("[$origin] error: $message", "error")
}
