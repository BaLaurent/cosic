package cosic.script.next

import cosic.pattern.errorLogger
import cosic.pattern.logger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Cycles per second a session starts at, and goes back to when a different project is opened. */
internal const val DEFAULT_CPS = 0.5

/** One line a program (or a pattern playing) wrote to the console. */
internal class LogLine(val level: LogLevel, val message: String)

/**
 * The host of one session ([ReplSession]): what the interpreter and the catalogue reach outside of the language. It holds
 * the tempo (setting it tells [onTempo], which is how the engine learns of a `setcps` while the program is still running,
 * as Strudel's scheduler does), the lines written to the console, the sliders and a timer.
 *
 * Everything but the timer and [lock] is safe to use from any thread: patterns call back into the program from the
 * audio thread.
 */
internal class SessionHost(private val onTempo: (Double) -> Unit = {}) : ScriptHost {
    @Volatile
    private var tempo = DEFAULT_CPS

    override var cps: Double
        get() = tempo
        set(value) {
            tempo = value
            onTempo(value)
        }

    /** Held while a program is evaluated, so that two evaluations never use the realm together. */
    val lock = Any()

    // ---- console ---------------------------------------------------------------------------------------------------

    private val recent = ArrayDeque<LogLine>()

    /** Told of every line written to the console while [muted] is false; the engine's console view subscribes here. */
    @Volatile
    var onLog: ((LogLine) -> Unit)? = null

    /** While true lines are dropped: the engine plays a program through once to check it, and its logging must not appear twice. */
    @Volatile
    var muted: Boolean = false

    override fun log(message: String, level: LogLevel) {
        if (muted) return
        val line = LogLine(level, message)
        synchronized(recent) {
            recent.addLast(line)
            while (recent.size > MAX_RECENT_LINES) recent.removeFirst()
        }
        onLog?.invoke(line)
    }

    /** The last lines written to the console, oldest first. */
    fun recentLines(): List<LogLine> = synchronized(recent) { recent.toList() }

    /** Runs [block] with the console muted (see [muted]). */
    fun <T> quietly(block: () -> T): T {
        val before = muted
        muted = true
        try {
            return block()
        } finally {
            muted = before
        }
    }

    // ---- clock and timers --------------------------------------------------------------------------------------------

    override fun nowSeconds(): Double = System.nanoTime() / 1e9

    private val timer: ScheduledThreadPoolExecutor by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ScheduledThreadPoolExecutor(1) { r -> Thread(r, "cosic-timer").apply { isDaemon = true } }.apply {
            removeOnCancelPolicy = true
        }
    }

    override fun runLater(delayMillis: Long, task: () -> Unit): () -> Unit {
        val future = timer.schedule(
            {
                try {
                    task()
                } catch (e: Exception) {
                    log(e.message ?: e.toString(), LogLevel.ERROR)
                }
            },
            maxOf(0L, delayMillis),
            TimeUnit.MILLISECONDS,
        )
        return { future.cancel(false) }
    }

    // ---- sliders -------------------------------------------------------------------------------------------------------

    private val sliders = ConcurrentHashMap<String, Double>()

    override fun sliderGet(id: String): Double? = sliders[id]

    override fun sliderSet(id: String, value: Double) {
        sliders[id] = value
    }

    private companion object {
        const val MAX_RECENT_LINES = 500
    }
}

// ---- the kernel's logging -----------------------------------------------------------------------------------------------

/** `logger()` of logger.mjs dedupes identical consecutive messages within this many milliseconds. */
private const val LOG_DEDUPE_MILLIS = 1000L

/**
 * Sends what the pattern kernel logs (`log()`, a failing branch of a `stack`) to [host]'s console. The kernel's hooks are
 * process-wide, so the session that evaluated last is the one that hears them; Strudel's logger drops a message
 * that repeats the previous one within a second (core/logger.mjs:5-35), which keeps a failing pattern from filling the
 * console sixteen times per cycle.
 */
internal fun routeKernelLogsTo(host: ScriptHost) {
    var last: String? = null
    var lastAt = 0L
    logger = { message, type, _ ->
        val now = System.nanoTime() / 1_000_000L
        val repeated = synchronized(host) {
            val same = message == last && now - lastAt < LOG_DEDUPE_MILLIS
            last = message
            lastAt = now
            same
        }
        if (!repeated) {
            val level = when (type) {
                "error" -> LogLevel.ERROR
                "warning" -> LogLevel.WARNING
                "debug" -> LogLevel.DEBUG
                else -> LogLevel.INFO
            }
            host.log(message, level)
        }
    }
    errorLogger = { e -> logger("[query] error: ${e.message ?: e}", "error", null) }
}

// ---- threads -----------------------------------------------------------------------------------------------------------

/**
 * The stack an evaluation gets: a program may nest calls 1500 deep and the interpreter takes many frames for each, more
 * than a pool or UI thread has. Only the pages a run touches are ever committed.
 */
private const val EVALUATION_STACK_BYTES = 64L * 1024 * 1024

/**
 * Runs [block] on a thread with a big stack and waits for it; what it throws is thrown here. An evaluation runs this
 * way (see [cosic.CosicEngine.evaluate]); a caller that already has a big stack does not need it.
 */
internal fun <T> onBigStack(block: () -> T): T {
    var result: T? = null
    var failure: Throwable? = null
    val thread = Thread(null, {
        try {
            result = block()
        } catch (t: Throwable) {
            failure = t
        }
    }, "cosic-eval", EVALUATION_STACK_BYTES)
    thread.isDaemon = true
    thread.start()
    thread.join()
    failure?.let { throw it }
    @Suppress("UNCHECKED_CAST")
    return result as T
}
