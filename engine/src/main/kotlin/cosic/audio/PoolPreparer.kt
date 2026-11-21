package cosic.audio

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

/**
 * Makes the shared pools of a [Mixer] (the effect chains of `FX(...)` elements, the phase vocoders of `stretch`) big enough for the events the scheduler has queued,
 * without the audio thread doing it: a chain is hundreds of kilobytes, and the garbage collector that follows such an allocation is what drops frames.
 *
 * The audio thread calls [request] once per block with what the queued events will take ([ChainDemand]). It never allocates, takes no lock and never waits: when the
 * pools already cover the demand it returns at once, otherwise it records the demand and wakes the one low-priority worker thread of the process, which grows the pools
 * ([Mixer.prepare]) and hands the new instances to the audio thread through the pools' own lock-free queues. A voice that starts before the worker is done still
 * gets its chain, made on the audio thread by [GrowablePool.take]: that is the last resort, and the engine reports it on the console.
 *
 * With [background] false (offline rendering, which has no deadline and must give the same sound every time) the pools are grown on the calling thread, at once.
 */
internal class PoolPreparer(private val mixer: Mixer, private val background: Boolean) {
    private val queued = AtomicBoolean(false)

    @Volatile
    private var fxChains = 0

    @Volatile
    private var vocoders = 0

    /** The link of the stack of preparers that wait for the worker; owned by that stack while [queued]. */
    private var next: PoolPreparer? = null

    init {
        if (background) Worker.start()
    }

    /** What the queued events need on top of what voices hold now. Audio thread (or whichever thread schedules); never allocates. */
    fun request(demand: ChainDemand) {
        val fx = demand.fxChains
        val v = demand.vocoders
        if (mixer.isPrepared(fx, v)) return
        if (!background) {
            mixer.prepare(fx, v)
            return
        }
        fxChains = fx
        vocoders = v
        if (queued.compareAndSet(false, true)) Worker.push(this)
    }

    /** Waits until the worker has nothing left to do for this preparer; for tests. */
    internal fun awaitIdle(timeoutMillis: Long): Boolean {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (queued.get() || running) {
            if (System.nanoTime() > deadline) return false
            Thread.sleep(1)
        }
        return true
    }

    @Volatile
    private var running = false

    private fun run() {
        running = true
        // cleared before the demand is read: a request that arrives after this queues the preparer again, one that came before is seen here
        queued.set(false)
        try {
            mixer.prepare(fxChains, vocoders)
        } finally {
            running = false
        }
    }

    /** The one thread that grows pools, for every engine of the process; its work is a stack of the preparers that asked. */
    private object Worker {
        private val head = AtomicReference<PoolPreparer?>(null)
        private val thread = Thread(::loop, "cosic-prepare").apply {
            isDaemon = true
            priority = Thread.NORM_PRIORITY - 1
        }

        /** Starts the thread on first use; a [PoolPreparer] does so when it is made, not on the audio thread. */
        fun start() {
            synchronized(this) { if (!thread.isAlive && thread.state == Thread.State.NEW) thread.start() }
        }

        fun push(preparer: PoolPreparer) {
            do {
                val top = head.get()
                preparer.next = top
            } while (!head.compareAndSet(top, preparer))
            LockSupport.unpark(thread)
        }

        private fun loop() {
            while (true) {
                var preparer = head.getAndSet(null)
                if (preparer == null) {
                    LockSupport.park(this)
                    continue
                }
                while (preparer != null) {
                    val following = preparer.next
                    preparer.next = null
                    try {
                        preparer.run()
                    } catch (_: Throwable) {
                        // growing a pool is best effort: the voices that find nothing make their chain themselves
                    }
                    preparer = following
                }
            }
        }
    }
}
