package cosic.audio

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * A pool of instances that are too big for every voice to own and that must not run out: the effect chains of `FX(...)` elements, the phase
 * vocoders of `stretch`. Voices [take] an instance when they start and [give] it back when they end, both on the audio thread, neither allocating.
 *
 * The pool grows ahead of the demand instead of failing under it: [ensureFree] may be called from any thread, creates the instances that a number of
 * queued events are going to take and hands them to the audio thread through a lock-free queue, so the audio thread only ever moves references. The
 * scheduler calls it for the events it has queued (see [ChainDemand]). A voice that nobody prepared the pool for still gets its instance, made on the
 * spot by [take], because an effect that is silently left out changes what the pattern sounds like; that path is the exception, and [madeOnTheSpot]
 * counts it. The pool only grows, and never beyond [limit] instances.
 */
internal class GrowablePool<T : Any>(private val limit: Int, initial: Int, private val factory: () -> T) {
    /** The free instances, a stack. Audio thread only. */
    private val stack = arrayOfNulls<Any>(limit)
    private var stackSize = 0

    /** Instances made by [ensureFree] that the audio thread has not picked up yet. */
    private val incoming = ConcurrentLinkedQueue<T>()
    private val created = AtomicInteger(0)
    private val taken = AtomicInteger(0)
    private val onTheSpot = AtomicInteger(0)

    init {
        require(initial in 0..limit) { "initial $initial is outside 0..$limit" }
        for (i in 0 until initial) stack[stackSize++] = factory()
        created.set(initial)
    }

    /** How many instances exist, free or not. */
    val capacity: Int get() = created.get()

    /** How many instances voices hold now. */
    val inUse: Int get() = taken.get()

    /** How many times [take] had to make an instance itself, on the audio thread. */
    val madeOnTheSpot: Int get() = onTheSpot.get()

    /** The instances a voice could take right now. Only meaningful on the audio thread or while it is idle. */
    val available: Int get() = stackSize + incoming.size

    /** An instance, or null when [limit] instances exist and all are taken. Audio thread only. */
    fun take(): T? {
        if (stackSize == 0) drain()
        if (stackSize == 0) {
            if (created.get() >= limit) return null
            created.incrementAndGet()
            onTheSpot.incrementAndGet()
            taken.incrementAndGet()
            return factory()
        }
        val item = stack[--stackSize]
        stack[stackSize] = null
        taken.incrementAndGet()
        @Suppress("UNCHECKED_CAST")
        return item as T
    }

    /** Takes an instance back. Audio thread only. */
    fun give(item: T) {
        stack[stackSize++] = item
        taken.decrementAndGet()
    }

    /**
     * Makes sure [wanted] more instances can be taken on top of those in use now, creating what is missing (on the calling thread, which should
     * not be the audio thread). Any thread.
     */
    fun ensureFree(wanted: Int) {
        if (wanted <= 0) return
        val target = Math.min(limit, taken.get() + wanted)
        while (true) {
            val have = created.get()
            if (have >= target) return
            if (created.compareAndSet(have, have + 1)) incoming.offer(factory())
        }
    }

    private fun drain() {
        while (true) {
            val item = incoming.poll() ?: return
            stack[stackSize++] = item
        }
    }
}

/**
 * What a number of queued voices will take from the shared pools of a [Mixer]: summed over the events the scheduler has queued but not started, and
 * handed to [Mixer.prepare], so that the pools are big enough by the time those events start.
 */
internal class ChainDemand {
    /** Effect chains of `FX(...)` elements. */
    var fxChains = 0
        private set

    /** Phase vocoders of `stretch`, in the main chain and in `FX` elements. */
    var vocoders = 0
        private set

    fun clear() {
        fxChains = 0
        vocoders = 0
    }

    /** Adds what [voice] is going to take. */
    fun add(voice: VoiceParams) {
        val chains = Math.min(voice.fx.size, MAX_FX)
        fxChains += chains
        if (voice.main.has(Param.stretch)) vocoders++
        for (i in 0 until chains) if (voice.fx[i].has(Param.stretch)) vocoders++
    }
}
