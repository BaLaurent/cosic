package cosic.script.next

import cosic.pattern.CosicError
import cosic.pattern.Undefined
import cosic.script.next.js.JsFunction
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The budget of a run (steps, time, depth) and what happens when a realm is used from several threads. */
class RealmLimitsTest {
    @Test
    fun theDefaultBudgetIsFiftyMillionStepsTenSecondsAndFifteenHundredNestedCalls() {
        val limits = Limits()
        assertEquals(50_000_000L, limits.steps)
        assertEquals(10_000L, limits.millis)
        assertEquals(1500, limits.maxDepth)
        val realm = testRealm()
        assertEquals(50_000_000L, realm.limits.steps)
        assertEquals(10_000L, realm.limits.millis)
    }

    @Test
    fun anInfiniteLoopIsStoppedByTheStepBudget() {
        val realm = testRealm()
        realm.limits = Limits(steps = 10_000, millis = 60_000)
        val code = "let n = 0;\nwhile (true) { n++; }"
        val e = assertFailsWith<CosicError> { realm.runRaw(code) }
        assertEquals("Script took too long", e.message)
        assertNotNull(e.range)
        assertTrue(e.range.start >= code.indexOf("while"), "the range is inside the loop: ${e.range}")
    }

    @Test
    fun theWallClockStopsALoopThatTheStepBudgetWouldAllow() {
        val realm = testRealm()
        realm.limits = Limits(steps = Long.MAX_VALUE, millis = 100)
        val t0 = System.nanoTime()
        val e = assertFailsWith<CosicError> { realm.runRaw("for (;;) {}") }
        assertEquals("Script took too long", e.message)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue(ms in 90..5_000, "stopped after $ms ms")
    }

    @Test
    fun aRunThatIsStoppedCannotCatchItOrRunItsFinallyBlocks() {
        val realm = testRealm()
        realm.limits = Limits(steps = 5_000, millis = 60_000)
        var marked = 0
        realm.defineTestFunction("mark") { marked++; Undefined }
        val e = assertFailsWith<CosicError> { realm.runRaw("try { while (true) {} } catch (e) { mark(); } finally { mark(); }") }
        assertEquals("Script took too long", e.message)
        assertEquals(0, marked)
    }

    @Test
    fun callsThatNestTooDeepAreARangeErrorTheProgramCanCatch() {
        val realm = testRealm()
        realm.limits = Limits(maxDepth = 20)
        assertEquals(19.0, realm.runRaw("const f = (n) => n === 0 ? 0 : 1 + f(n - 1); return f(19);"))
        assertEquals(
            "RangeError: Maximum call stack size exceeded",
            realm.runRaw("const f = (n) => n === 0 ? 0 : 1 + f(n - 1); try { f(25); } catch (e) { return e.name + ': ' + e.message; }"),
        )
        // The depth is back to nothing after the error: the same calls work again.
        assertEquals(19.0, realm.runRaw("const f = (n) => n === 0 ? 0 : 1 + f(n - 1); return f(19);"))
    }

    @Test
    fun aJvmStackOverflowIsARangeErrorToo() {
        var caught: Any? = null
        var uncaught: Throwable? = null
        val t = Thread(null, {
            val realm = testRealm()
            realm.limits = Limits(maxDepth = Int.MAX_VALUE)
            caught = realm.runRaw("function f(n) { return f(n + 1) + 1; } try { f(0); } catch (e) { return e.name + ': ' + e.message; }")
            uncaught = try {
                realm.runRaw("function f(n) { return f(n + 1) + 1; } f(0);")
                null
            } catch (e: Throwable) {
                e
            }
        }, "small-stack", 256L * 1024)
        t.start()
        t.join()
        assertEquals("RangeError: Maximum call stack size exceeded", caught)
        assertTrue(uncaught is CosicError, "got $uncaught")
        assertEquals("Maximum call stack size exceeded", (uncaught as CosicError).message)
    }

    @Test
    fun eachRunFromOutsideGetsAFreshBudgetButCallsFromNativeCodeShareTheirs() {
        val realm = testRealm()
        realm.limits = Limits(steps = 5_000, millis = 60_000)
        val f = realm.runRaw("return () => { let s = 0; for (let i = 0; i < 20; i++) s += i; return s; }") as JsFunction
        // About 70 steps a call: a hundred calls from outside are far more than the budget, but each one has its own.
        repeat(100) { assertEquals(190.0, f.call(emptyList())) }

        realm.defineGlobal("f", f)
        realm.defineTestFunction("repeat", 2) { args ->
            repeat((args[1] as Double).toInt()) { realm.callFunction(args[0], Undefined, emptyList()) }
            Undefined
        }
        assertEquals(Undefined, realm.runRaw("repeat(f, 10)"))
        // A native that calls back into the program is part of the run that called it: the budget is shared.
        val e = assertFailsWith<CosicError> { realm.runRaw("repeat(f, 1000)") }
        assertEquals("Script took too long", e.message)
    }

    @Test
    fun theBudgetIsMeasuredPerRealmAndRunsOfOtherRealmsDoNotCountAgainstIt() {
        val a = testRealm()
        val b = testRealm()
        a.limits = Limits(steps = 100, millis = 60_000)
        assertFailsWith<CosicError> { a.runRaw("for (let i = 0; i < 1000; i++) {}") }
        assertEquals(1000.0, b.runRaw("let n = 0; for (let i = 0; i < 1000; i++) n++; return n;"))
    }

    @Test
    fun aCallFromOutsideCanHaveASmallerBudgetThanAnEvaluation() {
        val realm = testRealm()
        realm.callbackLimits = Limits(steps = 1_000, millis = 60_000)
        val f = realm.runRaw("return () => { for (let i = 0; i < 100000; i++) {} }") as JsFunction
        val e = assertFailsWith<CosicError> { f.call(emptyList()) }
        assertEquals("Script took too long", e.message)
        // An evaluation still has the full budget, and so does a call from inside it.
        assertEquals(100_000.0, realm.runRaw("let n = 0; for (let i = 0; i < 100000; i++) n++; return n;"))
        realm.defineGlobal("f", f)
        assertEquals(Undefined, realm.runRaw("return f()"))
    }

    @Test
    fun functionsOfTheProgramCanBeCalledFromManyThreadsAtOnce() {
        val realm = testRealm()
        val f = realm.runRaw("return (x) => { let s = 0; for (let i = 0; i <= x; i++) s += i; return s; }") as JsFunction
        val errors = ConcurrentLinkedQueue<Throwable>()
        val threads = (1..4).map {
            thread {
                try {
                    repeat(2_000) { n ->
                        val x = (n % 50).toDouble()
                        val r = f.call(listOf(x))
                        if (r != x * (x + 1) / 2) errors.add(IllegalStateException("f($x) = $r"))
                    }
                } catch (e: Throwable) {
                    errors.add(e)
                }
            }
        }
        threads.forEach { it.join() }
        assertTrue(errors.isEmpty(), "errors: ${errors.toList()}")
    }

    @Test
    fun aProgramCanBeEvaluatedWhileACallbackRunsOnAnotherThread() {
        val realm = testRealm()
        val f = realm.runRaw("return (x) => x * 2") as JsFunction
        val stop = AtomicBoolean(false)
        val errors = ConcurrentLinkedQueue<Throwable>()
        var calls = 0
        val callbacks = thread {
            try {
                while (!stop.get()) {
                    if (f.call(listOf(21.0)) != 42.0) errors.add(IllegalStateException("wrong answer"))
                    calls++
                }
            } catch (e: Throwable) {
                errors.add(e)
            }
        }
        repeat(300) { i ->
            realm.defineGlobal("g$i", i.toDouble())
            realm.patternPrototype.defineOwn("p$i", i.toDouble())
            assertEquals(i.toDouble() + 1, realm.runRaw("return g$i + 1"))
        }
        stop.set(true)
        callbacks.join()
        assertTrue(errors.isEmpty(), "errors: ${errors.toList()}")
        assertTrue(calls > 0)
    }

    @Test
    fun aSharedObjectCanBeReadWhileItIsExtendedAndKeepsItsInsertionOrder() {
        val realm = testRealm()
        val proto = realm.patternPrototype
        val stop = AtomicBoolean(false)
        val errors = ConcurrentLinkedQueue<Throwable>()
        val reader = thread {
            try {
                while (!stop.get()) {
                    proto.ownKeys()
                    proto.get("m5")
                    proto.has("m100")
                    realm.global.findProp("m7")
                }
            } catch (e: Throwable) {
                errors.add(e)
            }
        }
        for (i in 0 until 3_000) {
            proto.defineOwn("m$i", i.toDouble())
            if (i % 3 == 0) realm.global.defineOwn("m$i", i.toDouble())
        }
        stop.set(true)
        reader.join()
        assertTrue(errors.isEmpty(), "errors: ${errors.toList()}")
        assertEquals((0 until 3_000).map { "m$it" }, proto.ownKeys())
        assertEquals(2999.0, proto.get("m2999"))
        assertFalse(proto.hasOwn("m3000"))
    }

    @Test
    fun aStringThatWouldFillTheMemoryIsARangeError() {
        val realm = testRealm()
        assertEquals(
            "RangeError: Invalid string length",
            realm.runRaw("let s = 'xxxxxxxxxx'; try { for (let i = 0; i < 40; i++) s += s; } catch (e) { return e.name + ': ' + e.message; }"),
        )
    }

    @Test
    fun anArrayThatWouldFillTheMemoryIsARangeError() {
        val realm = testRealm()
        assertEquals("RangeError: Invalid array length", realm.runRaw("const a = []; try { a[100000000] = 1; } catch (e) { return e.name + ': ' + e.message; }"))
        assertEquals("RangeError: Invalid array length", realm.runRaw("const a = []; try { a.length = 2000000000; } catch (e) { return e.name + ': ' + e.message; }"))
    }
}
