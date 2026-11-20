package cosic.io

import cosic.FrameClock
import cosic.MidiSink
import cosic.OscSink
import cosic.SpeechSink
import cosic.io.serial.SerialSink
import cosic.io.osc.OscHook
import cosic.pattern.ScheduleHook
import cosic.pattern.ScheduledHap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReferenceArray
import java.util.concurrent.locks.LockSupport

/** How many posts the bus holds before it drops new ones. */
internal const val OUTPUT_BUS_CAPACITY = 4096

/** The dispatcher thread ends when nothing was posted for this long; the next post starts it again. */
private const val IDLE_EXIT_NANOS = 3_000_000_000L

/** A parked dispatcher wakes up on its own after this long, whether or not it was told. */
private const val PARK_NANOS = 500_000_000L

/** What a hook that threw is reported as (`getSchedule` of core/repl.mjs). */
private const val ERROR_ORIGIN = "getSchedule"

/**
 * Where haps go that do more than sound: the hooks a pattern attached to them (`onSchedule`, `log`, `.midi()`, `.osc()`...) and the
 * lines the audio thread wants in the console. The scheduler, which runs on the audio thread, [post]s and never waits: a post is a few
 * atomic operations (a bounded queue without locks, many producers and one consumer), and one that finds the queue full is dropped and
 * counted in [dropped]. A dispatcher thread, started by the first post and gone again after a few idle seconds, takes the posts in
 * order and runs their hooks: anything that blocks (MIDI, sockets, speech, a user's callback) runs there or on the threads of
 * the sinks, never on the audio thread.
 *
 * The hooks of one hap run in the order they were attached; one that throws ends that hap's chain and is reported to the [console] as
 * `[getSchedule] error: message`, which keeps the other haps going (`getSchedule` of Strudel's REPL does the same).
 *
 * The devices that hooks reach ([midi], [osc], [speech]) are set by the app, which implements them; a hook that needs one that is not
 * there says so on the console. [clock] translates the frames of the engine into the system's time for them.
 *
 * A bus made with `autoStart = false` runs nothing by itself: [start] starts the thread and [drain] runs what is queued, which is what
 * tests do to see hooks run in order.
 */
class OutputBus(
    val console: ConsoleLog = ConsoleLog(),
    capacity: Int = OUTPUT_BUS_CAPACITY,
    private val autoStart: Boolean = true,
) {
    /** The engine's frames in the time of the system clock, `null` before the audio device has told. Set by whoever owns the device. */
    @Volatile
    var clock: FrameClock? = null

    @Volatile
    var midi: MidiSink? = null

    @Volatile
    var osc: OscSink? = null

    @Volatile
    var speech: SpeechSink? = null

    @Volatile
    var serial: SerialSink? = null
    /** The MQTT connections `.mqtt()` sends through (Task 4.10); set by the app, or by a test. */
    @Volatile
    var mqtt: cosic.io.mqtt.MqttOutput? = null
    /** Where `theme()`, `fontFamily()` and `fontSize()` of a program send the setting they ask for (see [SettingsSink]); the app implements it. */
    @Volatile
    var settings: SettingsSink? = null

    /**
     * Sends every hap that is not made to sound by a dominant hook to OSC instead of playing it (the audio engine target `osc`: "all events
     * routed to OSC, audio is silenced"). The hook that does it is [oscOutput]; without one the haps are silenced and nothing is sent.
     */
    @Volatile
    var oscTarget: Boolean = false

    /** What the default output becomes while [oscTarget] is on (`superdirtOutput`): what `.osc()` does, to the same device. */
    @Volatile
    internal var oscOutput: ScheduleHook? = OscHook(this)

    private class Post(val hooks: List<ScheduleHook>, val hap: ScheduledHap)

    private class LogPost(val message: String, val type: String?)

    private val slots = AtomicReferenceArray<Any?>(capacity)
    private val tail = AtomicLong(0)

    @Volatile
    private var head = 0L
    private val droppedPosts = AtomicLong(0)
    private val consumerLock = Any()
    private val running = AtomicBoolean(false)

    @Volatile
    private var thread: Thread? = null

    @Volatile
    private var parked = false

    @Volatile
    private var closed = false

    /** How many posts were dropped because the queue was full or the bus was shut down. */
    val dropped: Long get() = droppedPosts.get()

    /**
     * Hands [hap] to the dispatcher, which runs [hooks] with it. Audio thread; does not wait. [hooks] is the list of the hap's
     * context, which never changes after it is made.
     */
    internal fun post(hooks: List<ScheduleHook>, hap: ScheduledHap) {
        enqueue(Post(hooks, hap))
    }

    /** Hands a line to the dispatcher, which writes it to the [console]. Audio thread; does not wait. */
    internal fun postLog(message: String, type: String? = null) {
        enqueue(LogPost(message, type))
    }

    /** `[origin] error: message` as an error line of the console, from the audio thread. */
    internal fun postError(origin: String, message: String) = postLog("[$origin] error: $message", "error")

    private fun enqueue(item: Any) {
        if (closed) {
            droppedPosts.incrementAndGet()
            return
        }
        while (true) {
            val t = tail.get()
            if (t - head >= slots.length()) {
                droppedPosts.incrementAndGet()
                return
            }
            if (tail.compareAndSet(t, t + 1)) {
                slots.set((t % slots.length()).toInt(), item)
                break
            }
        }
        if (autoStart) ensureRunning()
        if (parked) LockSupport.unpark(thread)
    }

    /** Starts the dispatcher thread if it is not running (a bus made with `autoStart = false` runs only when this is called). */
    fun start() {
        closed = false
        ensureRunning()
    }

    private fun ensureRunning() {
        if (running.get() || !running.compareAndSet(false, true)) return
        val t = Thread(::dispatch, "cosic-output")
        t.isDaemon = true
        thread = t
        t.start()
    }

    /**
     * Runs what is queued, then stops the dispatcher: posts made afterwards are dropped until [start]. Waits for the thread for at most a
     * second. Queued hooks that never finish (a blocked sink) are left behind.
     */
    fun shutdown() {
        closed = true
        val t = thread
        LockSupport.unpark(t)
        if (t != null && t !== Thread.currentThread()) t.join(SHUTDOWN_JOIN_MILLIS)
        if (thread?.isAlive != true) drain()
    }

    /** Runs every post that is queued, on the calling thread, and returns how many ran. Tests use it with `autoStart = false`. */
    internal fun drain(): Int = synchronized(consumerLock) {
        var n = 0
        while (true) {
            val item = poll() ?: break
            run(item)
            n++
        }
        n
    }

    private fun hasPending(): Boolean = head < tail.get()

    /** The oldest post, or null when there is none (or one is on its way: a producer has taken its place but not stored it yet). */
    private fun poll(): Any? {
        val h = head
        if (h >= tail.get()) return null
        val index = (h % slots.length()).toInt()
        val item = slots.get(index) ?: return null
        slots.set(index, null)
        head = h + 1
        return item
    }

    private fun run(item: Any) {
        try {
            when (item) {
                is Post -> for (hook in item.hooks) hook.run(item.hap)
                is LogPost -> console.log(item.message, item.type)
            }
        } catch (e: Throwable) {
            // one hap's failing hook must not stop the others; the console is the last resort
            try {
                console.error(ERROR_ORIGIN, e)
            } catch (_: Throwable) {
            }
        }
    }

    private fun dispatch() {
        var idleSince = System.nanoTime()
        while (true) {
            val ran = synchronized(consumerLock) {
                var n = 0
                while (n < BATCH) {
                    val item = poll() ?: break
                    run(item)
                    n++
                }
                n
            }
            if (ran > 0) {
                idleSince = System.nanoTime()
                continue
            }
            if (closed && !hasPending()) {
                running.set(false)
                return
            }
            parked = true
            if (hasPending()) {
                parked = false
                // a producer has taken its place in the queue and is about to fill it
                Thread.yield()
                continue
            }
            LockSupport.parkNanos(this, PARK_NANOS)
            parked = false
            if (System.nanoTime() - idleSince >= IDLE_EXIT_NANOS) {
                // announce the exit, then look again: a producer that posted meanwhile either saw the thread running (and the queue
                // is not empty now) or saw it gone and starts another one
                running.set(false)
                if (hasPending() && running.compareAndSet(false, true)) continue
                return
            }
        }
    }

    private companion object {
        const val BATCH = 64
        const val SHUTDOWN_JOIN_MILLIS = 1000L
    }
}
