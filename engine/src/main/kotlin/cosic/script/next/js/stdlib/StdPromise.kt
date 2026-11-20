package cosic.script.next.js.stdlib

import cosic.pattern.JsFn
import cosic.pattern.Undefined
import cosic.script.next.AsyncSupport
import cosic.script.next.LogLevel
import cosic.script.next.Realm
import cosic.script.next.js.ErrorKind
import cosic.script.next.js.JsErrorObject
import cosic.script.next.js.JsFunction
import cosic.script.next.js.JsObject
import cosic.script.next.js.JsThrow
import cosic.script.next.js.PropFlags
import cosic.script.next.js.ScriptLimitExceeded
import cosic.script.next.js.describe
import cosic.script.next.js.describeThrown
import cosic.script.next.js.isNullish
import cosic.script.next.js.iteratorOf
import cosic.script.next.js.toStr
import java.util.PriorityQueue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock

/*
 * Promises, `async`/`await`, and the event loop they and the timers share.
 *
 * The interpreter has no way to suspend a function, so `await` blocks the thread that evaluates: it runs the pending
 * promise jobs and the due timers on that thread until the promise it waits for is settled (or the run's budget, or 30 s,
 * is used up). An async function runs to the end when it is called and returns an already settled promise. That is the
 * model of the host's promises too: a host function that has to wait for the outside world (a MIDI port opening) makes a
 * promise with [newPromise] and settles it from any thread; `await` in the program wakes up.
 *
 * The jobs of promises (`then` callbacks) and the timers are queued in the realm's [EventLoop]. Whoever is waiting in `await`
 * runs them; otherwise the host does, because every queued job asks it (`ScriptHost.runLater(0)`) to call [EventLoop.drain].
 */

/** One piece of work for after the current code: a promise reaction, a timer callback, `queueMicrotask`. */
internal typealias Job = () -> Unit

internal class EventLoop(private val realm: Realm) {
    private val monitor = Object()
    private val jobs = ConcurrentLinkedQueue<Job>()
    private val timers = PriorityQueue<Timer>(compareBy<Timer>({ it.due }, { it.seq }))
    private val timersById = HashMap<Double, Timer>()
    private val drainLock = ReentrantLock()
    private val drainRequested = AtomicBoolean(false)
    private val rejections = ConcurrentLinkedQueue<JsPromise>()
    private var timerSeq = 0L
    private var nextTimerId = 1.0

    /** The longest `await` waits for a promise that nothing settles, in nanoseconds. */
    @Volatile
    var awaitTimeoutNanos: Long = TimeUnit.SECONDS.toNanos(30)

    /**
     * The lock that evaluation holds, when the host has one. The drain the host is asked to make (`ScriptHost.runLater`) runs
     * program code, so it takes this lock first and waits for an evaluation in progress; the thread that is evaluating and
     * waits in `await` already holds it. Null: the host serializes its `runLater` tasks with evaluation itself.
     */
    @Volatile
    var executionLock: java.util.concurrent.locks.Lock? = null

    class Timer(val id: Double, val callback: Any?, val args: List<Any?>, val repeatMillis: Long, var due: Long, val seq: Long) {
        @Volatile
        var cancelled = false
        var cancelHost: (() -> Unit)? = null
    }

    // ---- queueing ---------------------------------------------------------------------------------------------------

    /** Queues [job]; it runs on the thread that is waiting in `await`, or on the host's, after the code that is running now. */
    fun post(job: Job) {
        jobs.add(job)
        signal()
        requestDrain()
    }

    /** Wakes the threads waiting in `await`: something they wait for may have happened. */
    fun signal() {
        synchronized(monitor) { monitor.notifyAll() }
    }

    private fun requestDrain() {
        if (!drainRequested.compareAndSet(false, true)) return
        realm.host.runLater(0) {
            drainRequested.set(false)
            drainForHost()
        }
    }

    /** [drain] as the host's thread calls it: under the [executionLock]. */
    private fun drainForHost() {
        val lock = executionLock
        if (lock == null) {
            drain()
            return
        }
        lock.lock()
        try {
            drain()
        } finally {
            lock.unlock()
        }
    }

    fun trackRejection(promise: JsPromise) {
        rejections.add(promise)
        requestDrain()
    }

    // ---- timers -----------------------------------------------------------------------------------------------------

    fun addTimer(callback: Any?, args: List<Any?>, delayMillis: Long, repeat: Boolean): Double {
        val timer: Timer
        synchronized(monitor) {
            val id = nextTimerId++
            timer = Timer(id, callback, args, if (repeat) delayMillis else -1L, System.nanoTime() + delayMillis * 1_000_000L, timerSeq++)
            timers.add(timer)
            timersById[id] = timer
        }
        arm(timer, delayMillis)
        signal()
        return timer.id
    }

    /** Asks the host to call [drain] when [timer] is due, for the time when nobody is waiting in `await`. */
    private fun arm(timer: Timer, delayMillis: Long) {
        timer.cancelHost = realm.host.runLater(delayMillis) { drainForHost() }
    }

    fun clearTimer(id: Double) {
        val timer: Timer?
        synchronized(monitor) {
            timer = timersById.remove(id)
            if (timer != null) {
                timer.cancelled = true
                timers.remove(timer)
            }
        }
        timer?.cancelHost?.invoke()
    }

    // ---- running ----------------------------------------------------------------------------------------------------

    /**
     * Runs what is ready now: the jobs in order, then the timers that are due (each followed by the jobs it queued),
     * until nothing is left. Only one thread runs jobs at a time; when another has the loop, this returns at once and
     * that thread does the work.
     */
    fun drain() {
        if (!drainLock.tryLock()) return
        try {
            while (true) {
                var ran = false
                while (true) {
                    val job = jobs.poll() ?: break
                    runJob(job)
                    ran = true
                }
                val timer = takeDueTimer()
                if (timer != null) {
                    runTimer(timer)
                    ran = true
                }
                if (!ran) break
            }
            reportRejections()
        } finally {
            drainLock.unlock()
        }
    }

    private fun takeDueTimer(): Timer? = synchronized(monitor) {
        val first = timers.peek()
        if (first != null && first.due <= System.nanoTime()) timers.poll() else null
    }

    private fun runTimer(timer: Timer) {
        if (timer.cancelled) return
        if (timer.repeatMillis >= 0) {
            synchronized(monitor) {
                timer.due = System.nanoTime() + timer.repeatMillis * 1_000_000L
                timers.add(timer)
            }
            arm(timer, timer.repeatMillis)
        } else {
            synchronized(monitor) { timersById.remove(timer.id) }
        }
        runJob { realm.callFunction(timer.callback, Undefined, timer.args) }
    }

    /** A job that fails is reported like an uncaught error of a callback, and does not stop the others. */
    private fun runJob(job: Job) {
        try {
            job()
        } catch (e: ScriptLimitExceeded) {
            realm.host.log(e.reason, LogLevel.ERROR)
        } catch (t: Throwable) {
            if (!realm.isCatchable(t)) throw t
            realm.host.log("Uncaught ${describeThrown(realm.caught(t))}", LogLevel.ERROR)
        }
    }

    private fun reportRejections() {
        while (true) {
            val p = rejections.poll() ?: return
            if (p.handled) continue
            realm.host.log("Uncaught (in promise) ${describeRejection(p.result)}", LogLevel.ERROR)
        }
    }

    private fun describeRejection(reason: Any?): String =
        if (reason is JsObject && reason.className == "Error") describeThrown(reason) else realm.inspect(reason)

    // ---- waiting ----------------------------------------------------------------------------------------------------

    /**
     * `await promise`: runs jobs and timers on this thread until [promise] is settled, then returns its value or throws
     * its reason. Gives up with `Script took too long` when the run's budget is used up, and with `await timed out`
     * after [awaitTimeoutNanos].
     */
    fun await(promise: JsPromise): Any? {
        promise.markHandled()
        realm.withExec { exec ->
            val timeoutAt = System.nanoTime() + awaitTimeoutNanos
            val giveUpAt = minOf(exec.deadline, timeoutAt)
            while (promise.state == JsPromise.State.PENDING) {
                drain()
                if (promise.state != JsPromise.State.PENDING) break
                val now = System.nanoTime()
                if (now >= giveUpAt) {
                    if (now >= exec.deadline) throw ScriptLimitExceeded("Script took too long", null)
                    throw JsThrow(realm.newError(ErrorKind.ERROR, "await timed out"))
                }
                waitUntil(minOf(giveUpAt, nextTimerDue()))
            }
        }
        if (promise.state == JsPromise.State.REJECTED) throw JsThrow(promise.result)
        return promise.result
    }

    private fun nextTimerDue(): Long = synchronized(monitor) { timers.peek()?.due ?: Long.MAX_VALUE }

    private fun waitUntil(deadlineNanos: Long) {
        synchronized(monitor) {
            if (jobs.isNotEmpty()) return
            val remaining = deadlineNanos - System.nanoTime()
            if (remaining <= 0) return
            monitor.wait(remaining / 1_000_000L, (remaining % 1_000_000L).toInt())
        }
    }
}

/**
 * A promise. It may be settled from any thread ([resolve], [reject]); its reactions run as jobs of the realm's event loop.
 * [state] and [result] are what `console.log` prints and `await` reads.
 */
internal class JsPromise(prototype: JsObject, private val realm: Realm, private val loop: EventLoop) : JsObject(prototype) {
    enum class State { PENDING, FULFILLED, REJECTED }

    private class Reaction(val onFulfilled: Any?, val onRejected: Any?, val derived: JsPromise?, val native: ((State, Any?) -> Unit)?)

    @Volatile
    var state = State.PENDING
        private set

    @Volatile
    var result: Any? = Undefined
        private set

    @Volatile
    var handled = false
        private set

    private var locked = false
    private var reactions: MutableList<Reaction>? = ArrayList()

    override val className: String get() = "Promise"

    /** Resolves with [value]; a thenable is followed. Only the first call of [resolve] and [reject] counts. */
    fun resolve(value: Any?) {
        synchronized(this) {
            if (locked) return
            locked = true
        }
        resolveValue(value)
    }

    fun reject(reason: Any?) {
        synchronized(this) {
            if (locked) return
            locked = true
        }
        settle(State.REJECTED, reason)
    }

    private fun resolveValue(value: Any?) {
        if (value === this) {
            settle(State.REJECTED, realm.newError(ErrorKind.TYPE, "Chaining cycle detected for promise #<Promise>"))
            return
        }
        if (value is JsObject) {
            val then = try {
                value.get("then")
            } catch (e: JsThrow) {
                settle(State.REJECTED, e.value)
                return
            }
            if (then is JsFunction || then is JsFn) {
                loop.post { followThenable(value, then) }
                return
            }
        }
        settle(State.FULFILLED, value)
    }

    /** `PromiseResolveThenableJob`: calls `then` of the thenable with functions that settle this promise. */
    private fun followThenable(thenable: JsObject, then: Any?) {
        var done = false
        val resolveFn = realm.function("", 1) { _, a ->
            if (!done) {
                done = true
                resolveValue(a.arg(0))
            }
            Undefined
        }
        val rejectFn = realm.function("", 1) { _, a ->
            if (!done) {
                done = true
                settle(State.REJECTED, a.arg(0))
            }
            Undefined
        }
        try {
            realm.callFunction(then, thenable, listOf(resolveFn, rejectFn))
        } catch (t: Throwable) {
            if (!realm.isCatchable(t)) throw t
            if (!done) {
                done = true
                settle(State.REJECTED, realm.caught(t))
            }
        }
    }

    private fun settle(newState: State, value: Any?) {
        val toRun: List<Reaction>?
        synchronized(this) {
            if (state != State.PENDING) return
            result = value
            state = newState
            toRun = reactions
            reactions = null
        }
        if (toRun != null) for (r in toRun) schedule(r)
        if (newState == State.REJECTED && !handled) loop.trackRejection(this)
        loop.signal()
    }

    private fun schedule(reaction: Reaction) {
        val s = state
        val v = result
        loop.post { run(reaction, s, v) }
    }

    private fun run(reaction: Reaction, s: State, v: Any?) {
        if (reaction.native != null) {
            reaction.native.invoke(s, v)
            return
        }
        val handler = if (s == State.FULFILLED) reaction.onFulfilled else reaction.onRejected
        val derived = reaction.derived ?: return
        if (handler !is JsFunction && handler !is JsFn) {
            if (s == State.FULFILLED) derived.resolve(v) else derived.reject(v)
            return
        }
        try {
            derived.resolve(realm.callFunction(handler, Undefined, listOf(v)))
        } catch (t: Throwable) {
            if (!realm.isCatchable(t)) throw t
            derived.reject(realm.caught(t))
        }
    }

    private fun add(reaction: Reaction) {
        var now = false
        synchronized(this) {
            handled = true
            val list = reactions
            if (list != null) list.add(reaction) else now = true
        }
        if (now) schedule(reaction)
    }

    /** `then(onFulfilled, onRejected)`: the promise of what the handlers return. */
    fun then(onFulfilled: Any?, onRejected: Any?): JsPromise {
        val derived = realm.newPromise()
        add(Reaction(onFulfilled, onRejected, derived, null))
        return derived
    }

    /** Calls [reaction] with the outcome once it is known, as a job; no promise is made. */
    fun whenSettled(reaction: (State, Any?) -> Unit) {
        add(Reaction(null, null, null, reaction))
    }

    /** Whoever awaits a promise handles its rejection. */
    fun markHandled() {
        handled = true
    }
}

/** A new pending promise of this realm, for host functions that finish later: settle it with [JsPromise.resolve] or [JsPromise.reject] from any thread. */
internal fun Realm.newPromise(): JsPromise = JsPromise(std.promisePrototype, this, std.loop)

/** A promise already fulfilled with [value] (or following it, when it is a thenable). */
internal fun Realm.resolvedPromise(value: Any?): JsPromise {
    if (value is JsPromise) return value
    return newPromise().also { it.resolve(value) }
}

// ---- the Promise global ----------------------------------------------------------------------------------------------------

internal fun installPromise(realm: Realm, std: StdRealm) {
    val proto = realm.newSharedObject()
    std.promisePrototype = proto
    realm.async = PromiseAsync(realm, std)

    val ctor = realm.constructor(
        "Promise", 1, proto,
        { _, _ -> throw realm.typeError("Promise constructor cannot be invoked without 'new'") },
        { args -> constructPromise(realm, args.arg(0)) },
    )
    realm.defineGlobal("Promise", ctor)

    fun thisPromise(self: Any?, method: String): JsPromise =
        self as? JsPromise ?: throw realm.typeError("Method Promise.prototype.$method called on incompatible receiver ${plain(realm, self)}")

    realm.defineMethod(proto, "then", 2) { self, args -> thisPromise(self, "then").then(args.arg(0), args.arg(1)) }
    realm.defineMethod(proto, "catch", 1) { self, args -> thisPromise(self, "catch").then(Undefined, args.arg(0)) }
    realm.defineMethod(proto, "finally", 1) { self, args ->
        val p = thisPromise(self, "finally")
        val onFinally = args.arg(0)
        if (onFinally !is JsFunction && onFinally !is JsFn) return@defineMethod p.then(onFinally, onFinally)
        val thenFinally = realm.function("", 1) { _, a ->
            val value = a.arg(0)
            realm.resolvedPromise(realm.callFunction(onFinally, Undefined, emptyList())).then(realm.function("", 0) { _, _ -> value }, Undefined)
        }
        val catchFinally = realm.function("", 1) { _, a ->
            val reason = a.arg(0)
            realm.resolvedPromise(realm.callFunction(onFinally, Undefined, emptyList())).then(realm.function("", 0) { _, _ -> throw JsThrow(reason) }, Undefined)
        }
        p.then(thenFinally, catchFinally)
    }

    realm.defineMethod(ctor, "resolve", 1) { _, args -> realm.resolvedPromise(args.arg(0)) }
    realm.defineMethod(ctor, "reject", 1) { _, args -> realm.newPromise().also { it.reject(args.arg(0)) } }
    realm.defineMethod(ctor, "withResolvers", 0) { _, _ ->
        val promise = realm.newPromise()
        val result = realm.newObject()
        result.defineOwn("promise", promise, PropFlags.ALL)
        result.defineOwn("resolve", realm.function("", 1) { _, a -> promise.resolve(a.arg(0)); Undefined }, PropFlags.ALL)
        result.defineOwn("reject", realm.function("", 1) { _, a -> promise.reject(a.arg(0)); Undefined }, PropFlags.ALL)
        result
    }
    realm.defineMethod(ctor, "all", 1) { _, args -> combine(realm, args.arg(0), Combinator.ALL) }
    realm.defineMethod(ctor, "allSettled", 1) { _, args -> combine(realm, args.arg(0), Combinator.ALL_SETTLED) }
    realm.defineMethod(ctor, "race", 1) { _, args -> combine(realm, args.arg(0), Combinator.RACE) }
    realm.defineMethod(ctor, "any", 1) { _, args -> combine(realm, args.arg(0), Combinator.ANY) }

    installAggregateError(realm, std)
}

private fun plain(realm: Realm, v: Any?): String = if (v is String) v else realm.describe(v)

private fun constructPromise(realm: Realm, executor: Any?): Any? {
    if (executor !is JsFunction && executor !is JsFn) throw realm.typeError("Promise resolver ${plain(realm, executor)} is not a function")
    val promise = realm.newPromise()
    val resolveFn = realm.function("", 1) { _, a ->
        promise.resolve(a.arg(0))
        Undefined
    }
    val rejectFn = realm.function("", 1) { _, a ->
        promise.reject(a.arg(0))
        Undefined
    }
    try {
        realm.callFunction(executor, Undefined, listOf(resolveFn, rejectFn))
    } catch (t: Throwable) {
        if (!realm.isCatchable(t)) throw t
        promise.reject(realm.caught(t))
    }
    return promise
}

private enum class Combinator { ALL, ALL_SETTLED, RACE, ANY }

private fun combine(realm: Realm, iterable: Any?, mode: Combinator): Any? {
    val result = realm.newPromise()
    try {
        val iterator = iteratorOf(iterable) ?: throw realm.notIterable(iterable)
        val values = ArrayList<Any?>()
        var remaining = 1
        fun finish() {
            if (--remaining != 0) return
            when (mode) {
                Combinator.ALL, Combinator.ALL_SETTLED -> result.resolve(realm.newArray(values))
                Combinator.ANY -> result.reject(aggregateError(realm, values))
                Combinator.RACE -> Unit
            }
        }
        var index = 0
        while (iterator.hasNext()) {
            val i = index++
            values.add(Undefined)
            remaining++
            realm.resolvedPromise(iterator.next()).whenSettled { state, v ->
                when (mode) {
                    Combinator.ALL -> if (state == JsPromise.State.FULFILLED) {
                        values[i] = v
                        finish()
                    } else {
                        result.reject(v)
                    }
                    Combinator.ALL_SETTLED -> {
                        val o = realm.newObject()
                        if (state == JsPromise.State.FULFILLED) {
                            o.defineOwn("status", "fulfilled", PropFlags.ALL)
                            o.defineOwn("value", v, PropFlags.ALL)
                        } else {
                            o.defineOwn("status", "rejected", PropFlags.ALL)
                            o.defineOwn("reason", v, PropFlags.ALL)
                        }
                        values[i] = o
                        finish()
                    }
                    Combinator.RACE -> if (state == JsPromise.State.FULFILLED) result.resolve(v) else result.reject(v)
                    Combinator.ANY -> if (state == JsPromise.State.FULFILLED) {
                        result.resolve(v)
                    } else {
                        values[i] = v
                        finish()
                    }
                }
            }
        }
        if (mode != Combinator.RACE) finish()
    } catch (t: Throwable) {
        if (!realm.isCatchable(t)) throw t
        result.reject(realm.caught(t))
    }
    return result
}

// ---- AggregateError ------------------------------------------------------------------------------------------------------

private fun installAggregateError(realm: Realm, std: StdRealm) {
    val proto = realm.newSharedObject(realm.errorPrototype)
    std.aggregateErrorPrototype = proto
    val make: (List<Any?>) -> Any? = { args ->
        val e = JsErrorObject(proto)
        val message = args.arg(1)
        if (message !== Undefined) e.defineOwn("message", realm.toStr(message), PropFlags.HIDDEN)
        val errors = args.arg(0).let { source -> iteratorOf(source)?.let { it.asSequence().toList() } ?: throw realm.notIterable(source) }
        e.defineOwn("errors", realm.newArray(errors), PropFlags.HIDDEN)
        e.defineOwn("stack", describeThrown(e), PropFlags.HIDDEN)
        e
    }
    val ctor = realm.constructor("AggregateError", 2, proto, { _, args -> make(args) }, make)
    proto.defineOwn("name", "AggregateError", PropFlags.HIDDEN)
    proto.defineOwn("message", "", PropFlags.HIDDEN)
    realm.defineGlobal("AggregateError", ctor)
}

private fun aggregateError(realm: Realm, errors: List<Any?>): JsObject {
    val e = JsErrorObject(realm.std.aggregateErrorPrototype)
    e.defineOwn("message", "All promises were rejected", PropFlags.HIDDEN)
    e.defineOwn("errors", realm.newArray(errors), PropFlags.HIDDEN)
    e.defineOwn("stack", "AggregateError: All promises were rejected", PropFlags.HIDDEN)
    return e
}

// ---- async / await -------------------------------------------------------------------------------------------------------

/** The realm's `async` and `await`: see the notes at the top of this file. */
internal class PromiseAsync(private val realm: Realm, private val std: StdRealm) : AsyncSupport {
    override fun runAsync(body: () -> Any?): Any? {
        val promise = realm.newPromise()
        try {
            promise.resolve(body())
        } catch (t: Throwable) {
            if (!realm.isCatchable(t)) throw t
            promise.reject(realm.caught(t))
        }
        return promise
    }

    override fun await(value: Any?): Any? {
        val promise = when {
            value is JsPromise -> value
            value is JsObject && !isNullish(value) && value.get("then").let { it is JsFunction || it is JsFn } -> realm.newPromise().also { it.resolve(value) }
            else -> return value
        }
        return std.loop.await(promise)
    }
}
