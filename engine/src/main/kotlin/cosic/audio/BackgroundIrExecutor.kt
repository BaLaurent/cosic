package cosic.audio

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.locks.LockSupport

/**
 * Runs the engine's background jobs (building reverb impulse responses: noise generation, filtering and the transforms of a [Convolver]'s partitions, tens to
 * hundreds of milliseconds of work) one at a time on one low-priority daemon thread, so the audio thread never does it.
 *
 * [submit] is what the audio thread calls: it appends the job to a lock-free queue and wakes the worker (`LockSupport.unpark`), never waits, and allocates
 * only the queue's node. The worker sleeps (parked, no cost) while there is nothing to do. One instance, [shared], serves every engine of the process.
 */
internal class BackgroundIrExecutor private constructor() : IrExecutor {
    private val queue = ConcurrentLinkedQueue<Runnable>()
    private val worker = Thread(::work, "cosic-reverb").apply {
        isDaemon = true
        priority = Thread.MIN_PRIORITY
        start()
    }

    override fun submit(job: Runnable) {
        queue.offer(job)
        LockSupport.unpark(worker)
    }

    private fun work() {
        while (true) {
            val job = queue.poll()
            if (job == null) {
                LockSupport.park(this)
                continue
            }
            try {
                job.run()
            } catch (_: Throwable) {
                // a job reports its own failures; the worker must outlive them
            }
        }
    }

    companion object {
        /** The executor of the process, started on first use (not on the audio thread: [cosic.CosicEngine] asks for it when it is made). */
        val shared: BackgroundIrExecutor by lazy { BackgroundIrExecutor() }
    }
}
