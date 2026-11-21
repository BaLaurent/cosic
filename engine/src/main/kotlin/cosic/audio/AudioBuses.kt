package cosic.audio

/** Buses numbered `0 until MAX_BUSES` exist; a send to another one is dropped. */
internal const val MAX_BUSES = 64

/**
 * The buses voices can send to (`bus`, `busgain`: superdough's `getBus(n)`, a stereo gain node): `s("bus")` and `.bmod()` read them. The analysers that voices
 * feed (`analyze`, superdough's `getAnalyserById`) are the [cosic.io.ScopeTaps] of the engine, not buses.
 *
 * Buses: a voice's sends are added into the bus for the block being rendered ([send]). A voice that reads a bus (a `bus` source, a `bmod` modulator; the [Mixer] calls
 * these readers) is rendered after all the others, so [tap] holds everything sent by non-readers in the same block: the graph is acyclic and Web Audio would
 * give the same. What a reader sends itself ([beginReaders] switches to this) arrives one block later: an exact answer would need the readers sorted by what they
 * read and send. A bus of the mono voices' sum is up-mixed (L = R) like every explicit-stereo node of superdough.
 * Nothing allocates after a bus exists.
 */
internal class AudioBuses(private val sampleRate: Int) {
    private val current = arrayOfNulls<StereoBuffer>(MAX_BUSES)
    private val next = arrayOfNulls<StereoBuffer>(MAX_BUSES)
    private val used = IntArray(MAX_BUSES)
    private var usedCount = 0
    private var readersPhase = false

    /**
     * The sum of the sends to bus [bus] in the block being rendered, frames `0 until ctx.n` valid (a mono send reaches both channels); null when the bus does not exist
     * or was never sent to. Valid until the end of the block. Not for a reader to keep.
     */
    fun tap(bus: Int): StereoBuffer? = if (bus in 0 until MAX_BUSES) current[bus] else null

    /** Adds the block, scaled by [gain], to bus [bus] (`post -> GainNode(busgain) -> buses[bus]`). */
    fun send(bus: Int, b: AudioBlock, gain: AParam, ctx: BlockContext) {
        if (bus !in 0 until MAX_BUSES) return
        var target = (if (readersPhase) next[bus] else current[bus])
        if (target == null) {
            current[bus] = StereoBuffer(BLOCK_FRAMES)
            next[bus] = StereoBuffer(BLOCK_FRAMES)
            used[usedCount++] = bus
            target = if (readersPhase) next[bus] else current[bus]
        }
        val n = ctx.n
        val right = if (b.channels == 2) b.r else b.l
        if (gain.isConstant) {
            val g = gain.constantValue.toFloat()
            for (i in 0 until n) {
                target!!.left[i] += b.l[i] * g
                target.right[i] += right[i] * g
            }
        } else {
            val gains = ctx.borrow()
            gain.fill(gains, ctx)
            for (i in 0 until n) {
                target!!.left[i] += b.l[i] * gains[i]
                target.right[i] += right[i] * gains[i]
            }
            ctx.release()
        }
    }

    /**
     * Starts a block: what the readers sent in the previous one becomes this block's start of every bus, and sends now go to it again.
     */
    fun beginBlock() {
        readersPhase = false
        for (u in 0 until usedCount) {
            val bus = used[u]
            val sent = next[bus]!!
            val old = current[bus]!!
            current[bus] = sent
            next[bus] = old
            old.clear(BLOCK_FRAMES)
        }
    }

    /** From here on until the next [beginBlock] the sends come from readers and are heard one block later. */
    fun beginReaders() {
        readersPhase = true
    }

    /** Empties the buses (the analysers keep what they heard: they are for looking at). */
    fun clear() {
        for (u in 0 until usedCount) {
            current[used[u]]!!.clear(BLOCK_FRAMES)
            next[used[u]]!!.clear(BLOCK_FRAMES)
        }
    }
}
