package cosic.player

import cosic.HighlightEvent
import cosic.pattern.ControlMap
import cosic.pattern.Hap
import cosic.pattern.Pattern
import cosic.pattern.Rational
import cosic.pattern.TOO_DENSE
import cosic.pattern.TimeSpan
import cosic.pattern.reportingExhaustion
import cosic.pattern.silence
import cosic.pattern.withQueryBudget
import cosic.script.DEFAULT_CPS

/** The pattern is queried in pieces of this many cycles. */
private val CHUNK = Rational(1, 16)

private const val MAX_HAPS_PER_CHUNK = 2000
private const val LOOKAHEAD_SECONDS = 0.1
private const val DRY_RUN_CYCLES = 2L

private fun unknownSoundWarning(key: String) = "Unknown sound '$key'"

/** What to tell the user about an exception thrown while a pattern was queried. */
internal fun errorMessage(error: Throwable): String = error.message ?: error.toString()

/** Warnings without repeats, in the order they came up. [list] is replaced, never changed, when one is added. */
private class Warnings(initial: List<String> = emptyList()) {
    private val seen = LinkedHashSet(initial)

    var list: List<String> = seen.toList()
        private set

    fun add(message: String) {
        if (seen.add(message)) list = seen.toList()
    }
}

/**
 * The haps of [pattern] that start inside the chunk beginning at [begin]: those with an onset whose whole begins in
 * it. Beyond [MAX_HAPS_PER_CHUNK] the rest is dropped and [warnings] told. Querying runs under a budget (see
 * [withQueryBudget]) and throws a [cosic.pattern.CosicError] when the chunk is too dense to build.
 */
private fun onsets(pattern: Pattern<ControlMap>, begin: Rational, warnings: Warnings): List<Hap<ControlMap>> {
    val end = begin + CHUNK
    val haps = withQueryBudget { pattern.query(TimeSpan(begin, end)) }
    val starting = ArrayList<Hap<ControlMap>>()
    for (hap in haps) {
        if (hap.hasOnset() && hap.part.begin >= begin && hap.part.begin < end) starting.add(hap)
    }
    if (starting.size <= MAX_HAPS_PER_CHUNK) return starting
    warnings.add(TOO_DENSE)
    return starting.subList(0, MAX_HAPS_PER_CHUNK)
}

/**
 * Looks ahead the way the [Scheduler] will, over two cycles from [fromCycle], without making a sound. Returns the
 * warnings playing those cycles would raise (unknown sounds, a pattern too dense), and throws a
 * [cosic.pattern.CosicError] or other exception for whatever would fail, so a program that would fail is rejected when
 * it is evaluated. Resolving each sound also makes the library start loading the samples and renders the drum sounds,
 * which keeps that work off the audio thread.
 */
internal fun dryRun(pattern: Pattern<ControlMap>, fromCycle: Long, sounds: SoundResolver): List<String> = reportingExhaustion {
    val warnings = Warnings()
    var begin = Rational(fromCycle)
    val end = begin + Rational(DRY_RUN_CYCLES)
    while (begin < end) {
        for (hap in onsets(pattern, begin, warnings)) {
            val lookup = sounds.resolve(hap.value)
            if (lookup is SoundLookup.Unknown) warnings.add(unknownSoundWarning(lookup.key))
        }
        begin += CHUNK
    }
    warnings.list
}

/**
 * Turns the program's pattern into [SoundEvent]s ahead of the audio. Time is kept in cycles: the [horizon] is the
 * start of the first chunk not yet queried, and the [anchor] says which frame a cycle sounds at. [schedule] queries
 * chunks of 1/16 cycle until the horizon is a tenth of a second past the block about to be rendered, and queues
 * what starts in them, ordered by frame. Audio thread only.
 */
internal class Scheduler(private val sampleRate: Int, private val sounds: SoundResolver) {
    private val lookaheadFrames = Math.round(LOOKAHEAD_SECONDS * sampleRate)
    private val pending = ArrayDeque<SoundEvent>()
    private var pattern: Pattern<ControlMap> = silence
    private var warningLog = Warnings()

    /** The tempo of the current program, which the next [play] starts at. */
    var cps = DEFAULT_CPS
        private set

    /** Which frame a cycle sounds at, from the last [play] or tempo change on. */
    var anchor = TempoAnchor(0, Rational.ZERO, DEFAULT_CPS, sampleRate)
        private set

    /** The start of the first chunk that has not been queried yet. */
    var horizon = Rational.ZERO
        private set

    var playing = false
        private set

    /** The last error thrown while querying the pattern, which the chunk that raised it was skipped for. */
    var runtimeError: String? = null
        private set

    val warnings: List<String> get() = warningLog.list

    /** What the editor can highlight: one entry per queued event that has source ranges. */
    val highlights = HighlightLog()

    /** The start frame of the earliest queued event, or [Long.MAX_VALUE] when there is none. */
    val nextEventFrame: Long get() = if (pending.isEmpty()) Long.MAX_VALUE else pending.first().startFrame

    /** Removes and returns the earliest queued event if it starts at or before [frame]. */
    fun takeDue(frame: Long): SoundEvent? {
        val next = pending.firstOrNull() ?: return null
        return if (next.startFrame <= frame) pending.removeFirst() else null
    }

    /**
     * Switches to a new program from the horizon on: events already queued stay as they are. A tempo change starts
     * a new anchor at the horizon, so those events keep their frames. [warnings] are the ones its dry run found.
     */
    fun setProgram(pattern: Pattern<ControlMap>, cps: Double, warnings: List<String>) {
        if (playing && cps != anchor.cps) anchor = TempoAnchor(anchor.frameAt(horizon), horizon, cps, sampleRate)
        this.pattern = pattern
        this.cps = cps
        warningLog = Warnings(warnings)
        runtimeError = null
    }

    /** Starts at cycle 0, which sounds at engine frame [startFrame]. */
    fun play(startFrame: Long) {
        anchor = TempoAnchor(startFrame, Rational.ZERO, cps, sampleRate)
        horizon = Rational.ZERO
        playing = true
        runtimeError = null
    }

    /** Drops everything queued and goes back to cycle 0. Voices that are already sounding are the caller's to fade out. */
    fun stop() {
        pending.clear()
        highlights.clear()
        horizon = Rational.ZERO
        playing = false
    }

    /** Queues the events that start before frame [blockEnd] plus the look-ahead. */
    fun schedule(blockEnd: Long) {
        if (!playing) return
        val until = blockEnd + lookaheadFrames
        while (anchor.frameAt(horizon) < until) scheduleChunk()
    }

    /** Queues one chunk. When that throws, the chunk is skipped and the error recorded; scheduling goes on. */
    private fun scheduleChunk() {
        val begin = horizon
        horizon = begin + CHUNK
        try {
            reportingExhaustion { queueChunk(begin) }
        } catch (e: Exception) {
            runtimeError = "Runtime error: ${errorMessage(e)}"
        }
    }

    private fun queueChunk(begin: Rational) {
        val events = ArrayList<SoundEvent>()
        val marks = ArrayList<HighlightEvent>()
        for (hap in onsets(pattern, begin, warningLog)) {
            when (val lookup = sounds.resolve(hap.value)) {
                is SoundLookup.Ready -> {
                    val whole = checkNotNull(hap.whole)
                    val start = anchor.frameAt(whole.begin)
                    val seconds = (whole.end - whole.begin).toDouble() / anchor.cps
                    events.add(SoundEvent(start, voiceParams(hap.value, lookup.source, seconds)))
                    if (hap.locations.isNotEmpty()) marks.add(HighlightEvent(start, anchor.frameAt(whole.end), hap.locations))
                }
                is SoundLookup.Unknown -> warningLog.add(unknownSoundWarning(lookup.key))
                SoundLookup.Loading -> Unit
            }
        }
        events.sortBy { it.startFrame }
        pending.addAll(events)
        for (mark in marks) highlights.add(mark)
    }
}
