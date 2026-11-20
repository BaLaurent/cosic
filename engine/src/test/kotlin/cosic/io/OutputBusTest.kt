package cosic.io

import cosic.pattern.Hap
import cosic.pattern.Rational
import cosic.pattern.ScheduleHook
import cosic.pattern.ScheduledHap
import cosic.pattern.TimeSpan
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun scheduled(n: Int): ScheduledHap {
    val whole = TimeSpan(Rational.ZERO, Rational.ONE)
    return ScheduledHap(Hap(whole, whole, mapOf<String, Any?>("n" to n.toDouble())), n.toLong(), n / 1000.0, 0.5, 0.0, 1.0)
}

/** The number a [scheduled] hap carries. */
private val ScheduledHap.n: Int get() = frame.toInt()

private fun waitFor(what: String, millis: Long = 5000, condition: () -> Boolean) {
    val deadline = System.nanoTime() + millis * 1_000_000
    while (!condition()) {
        check(System.nanoTime() < deadline) { "timed out waiting for $what" }
        Thread.sleep(2)
    }
}

/** What the audio thread hands to the dispatcher: haps with hooks and lines for the console. */
class OutputBusTest {
    private fun manual(capacity: Int = 16) = OutputBus(ConsoleLog { 0L }, capacity, autoStart = false)

    @Test
    fun hooksRunInTheOrderOfPosting() {
        val bus = manual()
        val seen = ArrayList<Int>()
        val hook = ScheduleHook { seen.add(it.n) }
        for (n in 1..5) bus.post(listOf(hook), scheduled(n))
        assertEquals(emptyList(), seen, "nothing runs by itself on a bus that is not started")
        assertEquals(5, bus.drain())
        assertEquals(listOf(1, 2, 3, 4, 5), seen)
        assertEquals(0, bus.drain())
    }

    @Test
    fun theHooksOfOneHapRunInTheirOrder() {
        val bus = manual()
        val seen = ArrayList<String>()
        bus.post(listOf(ScheduleHook { seen.add("first") }, ScheduleHook { seen.add("second") }), scheduled(1))
        bus.drain()
        assertEquals(listOf("first", "second"), seen)
    }

    @Test
    fun aHookThatThrowsIsReportedAndEndsThatHapsChainOnly() {
        val bus = manual()
        val seen = ArrayList<String>()
        val failing = ScheduleHook { throw IllegalStateException("boom") }
        val after = ScheduleHook { seen.add("after ${it.n}") }
        bus.post(listOf(failing, after), scheduled(1))
        bus.post(listOf(after), scheduled(2))
        bus.drain()
        assertEquals(listOf("after 2"), seen, "the rest of the first hap's chain did not run, the second hap's did")
        val line = bus.console.entries.single()
        assertEquals("[getSchedule] error: boom", line.message)
        assertEquals("error", line.type)
    }

    @Test
    fun aFullQueueDropsNewPostsAndCountsThem() {
        val bus = manual(capacity = 4)
        val seen = ArrayList<Int>()
        val hook = ScheduleHook { seen.add(it.n) }
        for (n in 1..6) bus.post(listOf(hook), scheduled(n))
        assertEquals(2L, bus.dropped)
        bus.drain()
        assertEquals(listOf(1, 2, 3, 4), seen, "the oldest are kept")
        bus.post(listOf(hook), scheduled(7))
        bus.drain()
        assertEquals(listOf(1, 2, 3, 4, 7), seen, "room is made again as hooks run")
        assertEquals(2L, bus.dropped)
    }

    @Test
    fun theQueueWrapsAroundManyTimes() {
        val bus = manual(capacity = 4)
        val seen = ArrayList<Int>()
        val hook = ScheduleHook { seen.add(it.n) }
        var next = 0
        repeat(25) {
            repeat(3) { bus.post(listOf(hook), scheduled(next++)) }
            bus.drain()
        }
        assertEquals((0 until 75).toList(), seen)
        assertEquals(0L, bus.dropped)
    }

    @Test
    fun linesPostedFromTheAudioThreadReachTheConsole() {
        val bus = manual()
        bus.postLog("hello")
        bus.postError("cyclist", "bad query")
        bus.drain()
        assertEquals(listOf("hello", "[cyclist] error: bad query"), bus.console.entries.map { it.message })
        assertEquals(listOf(null, "error"), bus.console.entries.map { it.type })
    }

    @Test
    fun theDispatcherRunsHooksOnAThreadOfItsOwn() {
        val bus = OutputBus(ConsoleLog { 0L })
        val threads = CopyOnWriteArrayList<Thread>()
        val latch = CountDownLatch(3)
        val hook = ScheduleHook {
            threads.add(Thread.currentThread())
            latch.countDown()
        }
        for (n in 1..3) bus.post(listOf(hook), scheduled(n))
        assertTrue(latch.await(5, TimeUnit.SECONDS), "the hooks ran")
        assertTrue(threads.all { it !== Thread.currentThread() })
        assertEquals(1, threads.toSet().size)
        assertEquals("cosic-output", threads.first().name)
        bus.shutdown()
    }

    @Test
    fun postingDoesNotWaitForAHookThatBlocks() {
        val bus = OutputBus(ConsoleLog { 0L })
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val ran = AtomicInteger()
        bus.post(
            listOf(
                ScheduleHook {
                    entered.countDown()
                    release.await()
                },
            ),
            scheduled(0),
        )
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        val hook = ScheduleHook { ran.incrementAndGet() }
        val started = System.nanoTime()
        for (n in 1..1000) bus.post(listOf(hook), scheduled(n))
        val tookMillis = (System.nanoTime() - started) / 1_000_000
        assertTrue(tookMillis < 500, "1000 posts took $tookMillis ms while the dispatcher was blocked")
        assertEquals(0, ran.get())
        release.countDown()
        waitFor("the queued hooks") { ran.get() == 1000 }
        bus.shutdown()
    }

    @Test
    fun manyProducersLoseNothingAndKeepTheirOrder() {
        val bus = OutputBus(ConsoleLog { 0L }, capacity = 8192)
        val seen = CopyOnWriteArrayList<Int>()
        val hook = ScheduleHook { seen.add(it.n) }
        val producers = (0 until 4).map { p ->
            thread {
                for (i in 0 until 1000) bus.post(listOf(hook), scheduled(p * 1_000_000 + i))
            }
        }
        producers.forEach { it.join() }
        waitFor("4000 hooks") { seen.size == 4000 }
        assertEquals(0L, bus.dropped)
        for (p in 0 until 4) {
            val mine = seen.filter { it / 1_000_000 == p }.map { it % 1_000_000 }
            assertEquals((0 until 1000).toList(), mine, "producer $p")
        }
        bus.shutdown()
    }

    @Test
    fun shutdownRunsWhatIsQueuedAndDropsLaterPosts() {
        val bus = OutputBus(ConsoleLog { 0L })
        val seen = CopyOnWriteArrayList<Int>()
        val hook = ScheduleHook { seen.add(it.n) }
        for (n in 1..10) bus.post(listOf(hook), scheduled(n))
        bus.shutdown()
        assertEquals((1..10).toList(), seen)
        bus.post(listOf(hook), scheduled(11))
        Thread.sleep(50)
        assertEquals(10, seen.size)
        assertEquals(1L, bus.dropped)
    }

    @Test
    fun startRunsTheQueueOfABusThatDoesNotStartItself() {
        val bus = OutputBus(ConsoleLog { 0L }, autoStart = false)
        val seen = CopyOnWriteArrayList<Int>()
        bus.post(listOf(ScheduleHook { seen.add(it.n) }), scheduled(1))
        Thread.sleep(30)
        assertTrue(seen.isEmpty())
        bus.start()
        waitFor("the hook") { seen.isNotEmpty() }
        bus.shutdown()
    }

    @Test
    fun theDevicesAndTheClockAreThereToBeSet() {
        val bus = manual()
        assertNull(bus.clock)
        assertNull(bus.midi)
        assertNull(bus.osc)
        assertNull(bus.speech)
        assertFalse(bus.oscTarget)
    }
}
