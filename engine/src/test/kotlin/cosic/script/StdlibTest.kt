package cosic.script

import cosic.SoundLibrary
import cosic.render.emptyLibrary
import cosic.pattern.CosicError
import cosic.script.js.JsObject
import cosic.script.js.stdlib.JsInspectable
import cosic.script.js.stdlib.newPromise
import cosic.script.js.stdlib.std
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A host whose `runLater` only remembers the task, so a test decides when the host's thread runs what the event loop asked for. */
private class LoopHost : ScriptHost {
    override var cps: Double = 0.5
    override val library: SoundLibrary = emptyLibrary()
    val logs = CopyOnWriteArrayList<Pair<LogLevel, String>>()
    val scheduled = ConcurrentLinkedQueue<() -> Unit>()
    @Volatile var clock = 0.0

    override fun log(message: String, level: LogLevel) {
        logs.add(level to message)
    }

    override fun nowSeconds(): Double = clock

    override fun runLater(delayMillis: Long, task: () -> Unit): () -> Unit {
        scheduled.add(task)
        return {}
    }

    override fun sliderGet(id: String): Double? = null

    override fun sliderSet(id: String, value: Double) {}

    /** What the host's thread does: runs the tasks the loop asked it to. */
    fun runScheduled() {
        while (true) (scheduled.poll() ?: break)()
    }
}

/** The parts of the standard library the oracle cannot compare with V8: the event loop and what it shares with the host. */
class StdlibTest {
    private fun run(realm: Realm, code: String): Any? = realm.runRaw(code)

    @Test
    fun aPromiseReactionRunsWhenTheHostDrainsTheLoopNotBefore() {
        val host = LoopHost()
        val realm = Realm(host)
        run(realm, "globalThis.seen = []; Promise.resolve(1).then(x => seen.push(x)); seen.push(0);")
        assertEquals("0", run(realm, "return seen.join()"))
        host.runScheduled()
        assertEquals("0,1", run(realm, "return seen.join()"))
    }

    @Test
    fun awaitRunsTheTimersAndJobsOnTheWaitingThread() {
        val realm = Realm(LoopHost())
        val t0 = System.nanoTime()
        val v = run(realm, "const r = await new Promise(res => setTimeout(() => res('done'), 30)); return r;")
        assertEquals("done", v)
        assertTrue((System.nanoTime() - t0) / 1_000_000 >= 25, "waited for the timer")
    }

    @Test
    fun awaitWakesUpWhenAnotherThreadSettlesAPromiseOfTheHost() {
        val realm = Realm(LoopHost())
        val p = realm.newPromise()
        realm.defineGlobal("hostPromise", p)
        val t = thread { Thread.sleep(40); p.resolve(42.0) }
        assertEquals(42.0, run(realm, "return await hostPromise"))
        t.join()
        val q = realm.newPromise()
        realm.defineGlobal("failing", q)
        thread { Thread.sleep(20); q.reject("nope") }
        assertEquals("nope", run(realm, "try { await failing; } catch (e) { return e; }"))
    }

    @Test
    fun anAwaitedRejectionIsThrownAndAnAsyncFunctionRejectsInsteadOfThrowing() {
        val realm = Realm(LoopHost())
        assertEquals("boom", run(realm, "try { await Promise.reject(new Error('boom')); } catch (e) { return e.message; }"))
        assertEquals("x", run(realm, "async function g() { throw new Error('x'); } const p = g(); try { await p; } catch (e) { return e.message; }"))
        assertEquals(3.0, run(realm, "async function f() { return 3; } return await f();"))
        assertEquals("pending", run(realm, "return (async () => { await null; return 1; })() instanceof Promise ? 'pending' : 'no';"))
    }

    @Test
    fun awaitGivesUpOnAPromiseNothingSettles() {
        val realm = Realm(LoopHost())
        realm.std.loop.awaitTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(60)
        val e = assertFailsWith<CosicError> { run(realm, "await new Promise(() => {});") }
        assertEquals("await timed out", e.message)
    }

    @Test
    fun theRunsBudgetBoundsHowLongAnAwaitWaits() {
        val realm = Realm(LoopHost())
        realm.limits = Limits(steps = 1_000_000, millis = 80)
        val e = assertFailsWith<CosicError> { run(realm, "await new Promise(() => {});") }
        assertEquals("Script took too long", e.message)
    }

    @Test
    fun anUnhandledRejectionIsReportedOnceTheLoopHasRun() {
        val host = LoopHost()
        val realm = Realm(host)
        run(realm, "Promise.reject(new Error('lost'));")
        assertTrue(host.logs.isEmpty())
        host.runScheduled()
        assertEquals(listOf(LogLevel.ERROR to "Uncaught (in promise) Error: lost"), host.logs.toList())
        host.logs.clear()
        run(realm, "const p = Promise.reject(new Error('seen')); p.catch(() => {});")
        host.runScheduled()
        assertTrue(host.logs.isEmpty())
    }

    @Test
    fun theCombinatorsOfPromise() {
        val realm = Realm(LoopHost())
        assertEquals("1,2,3", run(realm, "return (await Promise.all([1, Promise.resolve(2), new Promise(r => setTimeout(() => r(3), 5))])).join();"))
        assertEquals("fast", run(realm, "return await Promise.race([new Promise(r => setTimeout(() => r('slow'), 60)), new Promise(r => setTimeout(() => r('fast'), 5))]);"))
        assertEquals("fulfilled,rejected", run(realm, "return (await Promise.allSettled([1, Promise.reject(2)])).map(s => s.status).join();"))
        assertEquals(2.0, run(realm, "return await Promise.any([Promise.reject(1), Promise.resolve(2)]);"))
        assertEquals("AggregateError:All promises were rejected:2", run(realm, "try { await Promise.any([Promise.reject(1), Promise.reject(2)]); } catch (e) { return e.name + ':' + e.message + ':' + e.errors.length; }"))
        assertEquals("a,b", run(realm, "return (await Promise.all(['a', 'b'].map(async x => x))).join();"))
        assertEquals("chained2", run(realm, "return await Promise.resolve(1).then(x => x + 1).then(x => 'chained' + x).finally(() => 'ignored');"))
        assertEquals("caught", run(realm, "return await Promise.reject(1).catch(() => 'caught');"))
    }

    @Test
    fun timersRepeatAndCanBeCleared() {
        val realm = Realm(LoopHost())
        assertEquals(3.0, run(realm, "let n = 0; const id = setInterval(() => { n++; if (n === 3) clearInterval(id); }, 2); await new Promise(r => setTimeout(r, 60)); return n;"))
        assertEquals(false, run(realm, "let fired = false; const id = setTimeout(() => { fired = true; }, 5); clearTimeout(id); await new Promise(r => setTimeout(r, 30)); return fired;"))
        assertEquals("a,b", run(realm, "const out = []; setTimeout(() => out.push('b'), 15); setTimeout(() => out.push('a'), 5); await new Promise(r => setTimeout(r, 40)); return out.join();"))
        assertEquals("1,2", run(realm, "const out = []; setTimeout((x, y) => out.push(x, y), 1, 1, 2); await new Promise(r => setTimeout(r, 20)); return out.join();"))
        val e = assertFailsWith<CosicError> { run(realm, "setTimeout(5)") }
        assertEquals("The \"callback\" argument must be of type function. Received type number (5)", e.message)
    }

    @Test
    fun aTimerFiresOnTheHostsThreadWhenNobodyAwaits() {
        val host = LoopHost()
        val realm = Realm(host)
        run(realm, "globalThis.hit = 0; setTimeout(() => { hit++; }, 1);")
        Thread.sleep(10)
        host.runScheduled()
        assertEquals(1.0, run(realm, "return hit"))
    }

    @Test
    fun theHostsDrainTakesTheExecutionLockWhileAnEvaluationHoldsIt() {
        val host = LoopHost()
        val realm = Realm(host)
        val lock = ReentrantLock()
        realm.std.loop.executionLock = lock
        run(realm, "globalThis.ran = 0; queueMicrotask(() => { ran++; });")
        val done = AtomicInteger()
        lock.lock()
        val t = thread { host.runScheduled(); done.incrementAndGet() }
        Thread.sleep(60)
        assertEquals(0, done.get(), "the host's drain waits for the evaluation")
        assertEquals(0.0, run(realm, "return ran"))
        lock.unlock()
        t.join(2000)
        assertEquals(1, done.get())
        assertEquals(1.0, run(realm, "return ran"))
        assertFalse(lock.isLocked)
    }

    @Test
    fun consoleWritesThroughTheHostWithItsLevelsAndGroups() {
        val host = LoopHost()
        val realm = Realm(host)
        run(realm, "console.log('a', 1); console.info('i'); console.warn('w'); console.error('e'); console.debug('d'); console.group('G'); console.log({x: [1, 2]}); console.groupEnd(); console.log('after');")
        assertEquals(
            listOf(
                LogLevel.INFO to "a 1", LogLevel.INFO to "i", LogLevel.WARNING to "w", LogLevel.ERROR to "e", LogLevel.DEBUG to "d",
                LogLevel.INFO to "G", LogLevel.INFO to "  { x: [ 1, 2 ] }", LogLevel.INFO to "after",
            ),
            host.logs.toList(),
        )
    }

    @Test
    fun anObjectOfTheHostChoosesWhatConsoleLogPrintsForIt() {
        val host = LoopHost()
        val realm = Realm(host)
        val shown = object : JsObject(realm.objectPrototype), JsInspectable {
            override fun inspectText(): String? = "Pattern"
        }
        val plain = object : JsObject(realm.objectPrototype), JsInspectable {
            override fun inspectText(): String? = null
        }
        realm.defineGlobal("shown", shown)
        realm.defineGlobal("plain", plain)
        run(realm, "console.log(shown, [shown], {p: shown}, plain);")
        assertEquals(listOf(LogLevel.INFO to "Pattern [ Pattern ] { p: Pattern } {}"), host.logs.toList())
    }

    @Test
    fun dateNowReadsTheClockOfTheHost() {
        val host = LoopHost()
        val realm = Realm(host)
        host.clock = 1.5
        assertEquals(1500.0, run(realm, "return Date.now();"))
        assertEquals("1970-01-01T00:00:01.500Z", run(realm, "return new Date().toISOString();"))
    }

    @Test
    fun whatTheLibraryDoesNotHaveIsRefusedWithAMessage() {
        val realm = Realm(LoopHost())
        assertEquals("Code generation from strings disallowed for this context", assertFailsWith<CosicError> { run(realm, "new Function('return 1');") }.message)
        assertEquals("Regular expressions are not supported", assertFailsWith<CosicError> { run(realm, "'abc'.match('b');") }.message)
        assertEquals("Array.prototype.push called on a non-array", assertFailsWith<CosicError> { run(realm, "Array.prototype.push.call({}, 1);") }.message)
        assertEquals("undefined", run(realm, "return typeof Symbol;"))
    }

    @Test
    fun aFrozenArrayRefusesTheMethodsThatChangeIt() {
        val realm = Realm(LoopHost())
        assertEquals(
            "Cannot add property 2, object is not extensible|Cannot delete property '1' of [object Array]|Cannot assign to read only property '0' of object '[object Array]'",
            run(
                realm,
                "const a = Object.freeze([1, 2]); const out = []; for (const f of [() => a.push(3), () => a.pop(), () => a.sort()]) { try { f(); } catch (e) { out.push(e.message); } } return out.join('|');",
            ),
        )
    }

    @Test
    fun aSortThatTheProgramInterruptsLeavesTheArrayAlone() {
        val realm = Realm(LoopHost())
        assertEquals("3,1,2", run(realm, "const a = [3, 1, 2]; try { a.sort(() => { throw new Error('stop'); }); } catch (e) {} return a.join();"))
    }

    @Test
    fun aRealmThatNobodyUsesAnymoreIsCollected() {
        fun make(): WeakReference<Realm> {
            val realm = Realm(LoopHost())
            realm.runRaw(
                "const a = [1, 2]; a.push(a); a.join(); [3, 1, 2].sort(); Object.freeze([1]); new Map([[1, 2]]); new Set([1]); JSON.stringify({a: [1]}); new Date(0).toString();" +
                    "console.log(a); Promise.resolve(1).then(() => 1); queueMicrotask(() => {}); setTimeout(() => {}, 1000000);",
            )
            return WeakReference(realm)
        }
        val ref = make()
        var attempts = 0
        while (ref.get() != null && attempts++ < 100) {
            System.gc()
            Thread.sleep(20)
        }
        assertNull(ref.get(), "the realm is still reachable: something static (a registry, a thread-local) holds on to it")
    }
}
