package cosic.audio

import java.util.concurrent.atomic.AtomicReferenceArray

/** Buses numbered `0 until MAX_BUSES` exist; a send to another one is dropped. */
internal const val MAX_BUSES = 64

/** Analysers with ids `1 until MAX_ANALYSERS` exist (id 0 is "no analyser"). */
internal const val MAX_ANALYSERS = 64

/**
 * The buses voices can send to (`bus`, `busgain`: superdough's `getBus(n)`, a stereo gain node) and the analysers they can feed (`analyze`, `fft`: its
 * `getAnalyserById`): `s("bus")` and `.bmod()` read the former, the scope and spectrum visuals the latter.
 *
 * Buses: a voice's sends are added into the bus for the block being rendered ([send]). A voice that reads a bus (a `bus` source, a `bmod` modulator; the [Mixer] calls
 * these readers) is rendered after all the others, so [tap] holds everything sent by non-readers in the same block: the graph is acyclic and Web Audio would
 * give the same. What a reader sends itself ([beginReaders] switches to this) arrives one block later: an exact answer would need the readers sorted by what they
 * read and send. A bus of the mono voices' sum is up-mixed (L = R) like every explicit-stereo node of superdough.
 *
 * Analysers ([analyse], [analyser]) are created, on the audio thread, by the first voice that asks for an id (and `fft` can resize them later); they are read
 * from any thread. Nothing allocates after a bus or analyser exists.
 */
internal class AudioBuses(private val sampleRate: Int) {
    private val current = arrayOfNulls<StereoBuffer>(MAX_BUSES)
    private val next = arrayOfNulls<StereoBuffer>(MAX_BUSES)
    private val used = IntArray(MAX_BUSES)
    private var usedCount = 0
    private var readersPhase = false
    private val taps = AtomicReferenceArray<AnalyserTap?>(MAX_ANALYSERS)
    private val tapIds = IntArray(MAX_ANALYSERS)
    private var tapCount = 0

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

    /**
     * Adds the block to analyser [id] (`post -> AnalyserNode`), creating it of size [fftSize] on first use and resizing it when the voice asks for another size.
     * Ids outside `1 until MAX_ANALYSERS` are ignored.
     */
    fun analyse(id: Int, fftSize: Int, b: AudioBlock, ctx: BlockContext) {
        if (id <= 0 || id >= MAX_ANALYSERS) return
        var tap = taps.get(id)
        if (tap == null) {
            tap = AnalyserTap(id, fftSize)
            tapIds[tapCount++] = id
            taps.set(id, tap)
        } else if (tap.fftSize != fftSize) {
            tap.resize(fftSize)
        }
        tap.add(b, ctx.n)
    }

    /** Ends a block of [n] frames: every analyser takes in the block's signal (silence for those nobody sent to). */
    fun endBlock(n: Int) {
        for (i in 0 until tapCount) taps.get(tapIds[i])!!.commit(n)
    }

    /** Analyser [id] (any thread), or null before the first voice that feeds it. */
    fun analyser(id: Int): AnalyserTap? = if (id > 0 && id < MAX_ANALYSERS) taps.get(id) else null

    /** Empties the buses (the analysers keep what they heard: they are for looking at). */
    fun clear() {
        for (u in 0 until usedCount) {
            current[used[u]]!!.clear(BLOCK_FRAMES)
            next[used[u]]!!.clear(BLOCK_FRAMES)
        }
    }
}
