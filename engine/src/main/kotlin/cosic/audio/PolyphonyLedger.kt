package cosic.audio

/** What to do with a voice that is stolen at [tNew]; a functional interface so that calling it never boxes the time. */
internal fun interface StealAction {
    fun steal(voice: Voice, tNew: Double)
}

/**
 * The voices that count toward `maxPolyphony`: superdough's `activeSoundSources`, whose entries live until the chain of the sound is
 * released, that is until `max(end of the source, endWithRelease)`, even when the sound is long silent. When a new voice would
 * be the one beyond the maximum, the oldest entries are stolen ([admit]). Entries are kept in start order; nothing allocates.
 */
internal class PolyphonyLedger(capacity: Int) {
    private val voices = arrayOfNulls<Voice>(capacity)
    private val until = DoubleArray(capacity)
    private var count = 0

    /** Voices counted now. */
    val size: Int get() = count

    /** Drops the entries whose voice is over at time [now]. */
    fun expire(now: Double) {
        var kept = 0
        for (i in 0 until count) {
            if (until[i] > now) {
                voices[kept] = voices[i]
                until[kept] = until[i]
                kept++
            }
        }
        for (i in kept until count) voices[i] = null
        count = kept
    }

    /** Forgets every entry. */
    fun clear() {
        for (i in 0 until count) voices[i] = null
        count = 0
    }

    /** Drops [voice] from the ledger, if it is in it. */
    fun remove(voice: Voice) {
        var at = -1
        for (i in 0 until count) if (voices[i] === voice) at = i
        if (at < 0) return
        for (i in at until count - 1) {
            voices[i] = voices[i + 1]
            until[i] = until[i + 1]
        }
        voices[--count] = null
    }

    /**
     * Counts [voice] (which starts at [startTime] and is active until [activeUntil]) after stealing the oldest entries while there are
     * [max] or more of them: `for (i = 0; i <= size - maxPolyphony; i++)` of the original, where the size shrinks as entries go. A
     * stolen voice is handed to [steal] with [startTime] and leaves the ledger.
     */
    fun admit(voice: Voice, startTime: Double, activeUntil: Double, max: Int, steal: StealAction) {
        var i = 0
        while (i <= count - max && count > 0) {
            val oldest = voices[0]!!
            for (k in 0 until count - 1) {
                voices[k] = voices[k + 1]
                until[k] = until[k + 1]
            }
            voices[--count] = null
            steal.steal(oldest, startTime)
            i++
        }
        if (count == voices.size) return
        voices[count] = voice
        until[count] = activeUntil
        count++
    }
}

/**
 * The zero-based output channels of a voice (`mapChannelNumbers`, superdough.mjs:410-412, 527-531): its `channels` control (one-based) when
 * set, otherwise `[1, 2]`, or `[2 orbit - 1, 2 orbit]` when [multi] (multi-channel orbits) is on and [orbit] is above 0; each minus 1.
 */
internal fun mapOutputChannels(channels: IntArray?, orbit: Double, multi: Boolean): IntArray {
    val oneBased = when {
        channels != null -> channels
        multi && orbit > 0 -> intArrayOf((orbit * 2 - 1).toInt(), (orbit * 2).toInt())
        else -> intArrayOf(1, 2)
    }
    return IntArray(oneBased.size) { oneBased[it] - 1 }
}
