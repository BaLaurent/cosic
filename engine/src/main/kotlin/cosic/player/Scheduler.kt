package cosic.player

import cosic.HighlightEvent
import cosic.io.OutputBus
import cosic.pattern.ControlMap
import cosic.pattern.CosicError
import cosic.pattern.Hap
import cosic.pattern.Pattern
import cosic.pattern.QueryContext
import cosic.pattern.Rational
import cosic.pattern.ScheduledHap
import cosic.pattern.TOO_DENSE
import cosic.pattern.Undefined
import cosic.pattern.jsToString
import cosic.pattern.reportingExhaustion
import cosic.pattern.silence
import cosic.pattern.withQueryBudget
import cosic.script.DEFAULT_CPS

/** The pattern is queried in pieces of this many cycles. */
private val CHUNK = Rational(1, 16)

private const val MAX_HAPS_PER_CHUNK = 2000

/** How far ahead of the audio the scheduler queues events: the latency of Strudel's cyclist (`latency = 0.1`). */
internal const val LOOKAHEAD_SECONDS = 0.1
private const val DRY_RUN_CYCLES = 2L

/** The window `_triggerKeyboard` of midi.mjs queries around the instant of a key: `eps = 1e-6` either side. */
private const val TRIGGER_EPSILON = 1e-6

/** The fastest tempo a program may set (see `setcps`); a `cps` control outside of `(0, MAX_CPS]` is not a tempo. */
private const val MAX_CPS = 10.0

/** The origin Strudel gives the errors of routing a hap (`getSchedule` of core/repl.mjs), and of querying the pattern (`cyclist`). */
private const val ROUTING_ERROR_ORIGIN = "getSchedule"
private const val QUERY_ERROR_ORIGIN = "cyclist"

/** What Strudel says when a sound is not registered (superdough.mjs:594). */
private fun unknownSoundWarning(key: String) = "sound $key not found! Is it loaded?"

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

/** The controls Strudel's cyclist passes with every query: the tempo (`_cps`, read by `loopAt`, `splice` and `fit`) and who is asking. */
private fun schedulerControls(cps: Double): Map<String, Any?> = mapOf("_cps" to cps, "cyclist" to "cyclist")

/**
 * The haps of [pattern] that start inside the chunk beginning at [begin]: those with an onset whose whole begins in
 * it. Beyond [MAX_HAPS_PER_CHUNK] the rest is dropped and [warnings] told. Querying runs under a budget (see
 * [withQueryBudget]) and throws a [cosic.pattern.CosicError] when the chunk is too dense to build.
 */
private fun onsets(pattern: Pattern, begin: Rational, cps: Double, warnings: Warnings): List<Hap> {
    val end = begin + CHUNK
    val haps = withQueryBudget { QueryContext.asScheduler { pattern.queryArc(begin, end, schedulerControls(cps)) } }
    val starting = ArrayList<Hap>()
    for (hap in haps) {
        if (hap.hasOnset() && hap.part.begin >= begin && hap.part.begin < end) starting.add(hap)
    }
    if (starting.size <= MAX_HAPS_PER_CHUNK) return starting
    warnings.add(TOO_DENSE)
    return starting.subList(0, MAX_HAPS_PER_CHUNK)
}

/** Whether a hap's value is a control map with a `cps` (`hap.value.cps !== undefined`). */
private fun hasCps(value: Any?): Boolean = value is Map<*, *> && value["cps"].let { it != null && it !== Undefined }

/**
 * The haps in the order the cyclist hands them out (cyclist.mjs:80-96): by the start of their whole, and where that is the same those that
 * set the tempo first, so the others of that instant are timed by the new tempo. The sort is stable.
 */
private fun inStartOrder(haps: List<Hap>): List<Hap> {
    if (haps.size < 2) return haps
    return haps.sortedWith { a, b ->
        val byStart = checkNotNull(a.whole).begin.compareTo(checkNotNull(b.whole).begin)
        if (byStart != 0) byStart else (if (hasCps(a.value)) 0 else 1) - (if (hasCps(b.value)) 0 else 1)
    }
}

/**
 * The controls an event plays with. A hap whose value is not an object (a bare `"c e"` or `60`) is an error in Strudel
 * too, with the hint to append `.note()` or `.s()`.
 */
private fun controlsOf(hap: Hap): ControlMap {
    hap.ensureObjectValue()
    @Suppress("UNCHECKED_CAST")
    return hap.value as? ControlMap ?: throw CosicError("expected hap.value to be an object, but got a list. Hint: append .note() or .s() to the end")
}

/**
 * `Hap.duration` (hap.mjs:36-47) in cycles: the length of the hap's whole, replaced by the `duration` control when that is a number
 * (in cycles, whatever its documentation says), times the `clip` control when that is a number. [whole] is the length of the whole.
 */
internal fun hapCycles(controls: ControlMap, whole: Rational): Double {
    var cycles = (controls["duration"] as? Double) ?: whole.toDouble()
    (controls["clip"] as? Double)?.let { cycles *= it }
    return cycles
}

/**
 * `Hap.duration / cps`: what `superdough()` is given as `hapDuration`, `clip` included, so the voice does not apply `clip` again.
 */
internal fun hapSeconds(controls: ControlMap, whole: Rational, cps: Double): Double = hapCycles(controls, whole) / cps

/** The tempo a hap sets with its `cps` control, or null when it sets none (or one that is no tempo). */
private fun tempoOf(value: Any?): Double? {
    val cps = (value as? Map<*, *>)?.get("cps") as? Double ?: return null
    return if (cps.isFinite() && cps > 0.0 && cps <= MAX_CPS) cps else null
}

/**
 * Looks ahead the way the [Scheduler] will, over two cycles from [fromCycle], without making a sound. Returns the
 * warnings playing those cycles would raise (unknown sounds, a pattern too dense), and throws a
 * [cosic.pattern.CosicError] or other exception for whatever would fail, so a program that would fail is rejected when
 * it is evaluated. Resolving each sound also makes the library start loading the samples and renders the drum sounds,
 * which keeps that work off the audio thread. Haps that are not played (those a dominant hook takes over, and every hap while the
 * [outputs] route everything to OSC) are not looked at.
 */
internal fun dryRun(
    pattern: Pattern,
    fromCycle: Long,
    sounds: SoundResolver,
    cps: Double = DEFAULT_CPS,
    outputs: OutputBus? = null,
): List<String> = reportingExhaustion {
    val warnings = Warnings()
    var begin = Rational(fromCycle)
    val end = begin + Rational(DRY_RUN_CYCLES)
    while (begin < end) {
        for (hap in onsets(pattern, begin, cps, warnings)) {
            if (outputs?.oscTarget == true || takenOver(hap)) continue
            val lookup = sounds.resolve(controlsOf(hap))
            if (lookup is SoundLookup.Unknown) warnings.add(unknownSoundWarning(lookup.key))
        }
        begin += CHUNK
    }
    warnings.list
}

/** Whether a dominant hook takes the hap's place at the audio output (the rule of `getSchedule`: no default output when `dominantSchedule`). */
private fun takenOver(hap: Hap): Boolean = hap.context.dominant && !hap.context.hooks.isNullOrEmpty()

/**
 * Turns the program's pattern into [SoundEvent]s ahead of the audio. Time is kept in cycles: the [horizon] is the
 * start of the first chunk not yet queried, and the [anchor] says which frame a cycle sounds at. [schedule] queries
 * chunks of 1/16 cycle until the horizon is a tenth of a second past the block about to be rendered, and queues
 * what starts in them, ordered by frame. Audio thread only.
 *
 * Every hap that starts in a chunk is routed the way `getSchedule` of Strudel's REPL does it: the hooks a pattern attached to it
 * (`onSchedule`, `log`, `.midi()`...) are handed to the [outputs], whose dispatcher runs them off the audio thread, and the default
 * output, the sound, is made unless a dominant hook took the hap over. A hap whose `cps` control is a tempo sets that tempo from then on
 * (cyclist.mjs:104-118): later haps are timed by it, and the hap's own length is measured in it. The editor is told to highlight every hap
 * that has source ranges, whether or not it makes a sound.
 */
internal class Scheduler(
    private val sampleRate: Int,
    private val sounds: SoundResolver,
    private val outputs: OutputBus = OutputBus(),
) {
    private val lookaheadFrames = Math.round(LOOKAHEAD_SECONDS * sampleRate)
    private val pending = ArrayDeque<SoundEvent>()
    private var pattern: Pattern = silence
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
    fun setProgram(pattern: Pattern, cps: Double, warnings: List<String>) {
        setCps(cps)
        this.pattern = pattern
        warningLog = Warnings(warnings)
        runtimeError = null
    }

    /** Changes the tempo from the horizon on, as `setcps` does while a program runs; events already queued keep their frames. */
    fun setCps(cps: Double) {
        if (playing && cps != anchor.cps) anchor = TempoAnchor(anchor.frameAt(horizon), horizon, cps, sampleRate)
        this.cps = cps
    }

    /** Starts at cycle [startCycle] (the first by default), which sounds at engine frame [startFrame]. */
    fun play(startFrame: Long, startCycle: Rational = Rational.ZERO) {
        anchor = TempoAnchor(startFrame, startCycle, cps, sampleRate)
        horizon = startCycle
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

    /**
     * Plays what a live input just made (`_triggerKeyboard` of midi.mjs): [match] picks, among the haps the program has at the instant
     * [nowCycle] plus [latencyFrames] (at the tempo [cps]), those that come from the input (the notes the keyboard just played), and each of
     * them is routed like any scheduled hap, sounding at engine frame [nowFrame] plus its distance from [nowCycle]. [nowFrame] is the engine
     * frame at which [nowCycle] sounds. True when any hap was routed, which is the keyboard's cue that its notes have been played and can
     * be forgotten. Does nothing while stopped. Audio thread only; an exception that querying throws is recorded like those of the chunks.
     */
    fun trigger(nowFrame: Long, latencyFrames: Long, nowCycle: Double, cps: Double, match: (Hap) -> Boolean): Boolean {
        if (!playing) return false
        val t = nowCycle + latencyFrames.toDouble() / sampleRate * cps
        val events = ArrayList<SoundEvent>()
        val marks = ArrayList<HighlightEvent>()
        var routed = false
        try {
            reportingExhaustion {
                val begin = Rational.of(t - TRIGGER_EPSILON)
                val end = Rational.of(t + TRIGGER_EPSILON)
                // not a query of the cyclist: the program's patterns that remember what they were asked (the keyboard's) keep it
                val haps = withQueryBudget { pattern.queryArc(begin, end, mapOf("_cps" to cps)) }
                for (hap in haps) {
                    if (!hap.hasOnset() || !match(hap)) continue
                    routed = true
                    route(hap, events, marks, cps, tempoMayChange = false) { cycle ->
                        nowFrame + Math.round((cycle.toDouble() - nowCycle) / cps * sampleRate)
                    }
                }
            }
        } catch (e: Exception) {
            reportRuntimeError(e, QUERY_ERROR_ORIGIN)
        }
        // what is queued is ordered by start, and these start earlier than much of it
        for (event in events) {
            var at = pending.size
            while (at > 0 && pending[at - 1].startFrame > event.startFrame) at--
            pending.add(at, event)
        }
        for (mark in marks) highlights.add(mark)
        return routed
    }

    /** Queues one chunk. When that throws, the chunk is skipped and the error recorded; scheduling goes on. */
    private fun scheduleChunk() {
        val begin = horizon
        horizon = begin + CHUNK
        try {
            reportingExhaustion { queueChunk(begin) }
        } catch (e: Exception) {
            reportRuntimeError(e, QUERY_ERROR_ORIGIN)
        }
    }

    private fun reportRuntimeError(e: Exception, origin: String) {
        runtimeError = "Runtime error: ${errorMessage(e)}"
        outputs.postError(origin, errorMessage(e))
    }

    private fun queueChunk(begin: Rational) {
        val events = ArrayList<SoundEvent>()
        val marks = ArrayList<HighlightEvent>()
        for (hap in inStartOrder(onsets(pattern, begin, anchor.cps, warningLog))) {
            route(hap, events, marks, anchor.cps, tempoMayChange = true) { cycle -> anchor.frameAt(cycle) }
        }
        events.sortBy { it.startFrame }
        pending.addAll(events)
        for (mark in marks) highlights.add(mark)
    }

    /**
     * What `getSchedule` does with a hap that starts now: the sound unless a dominant hook takes the hap's place (or everything goes to
     * OSC), then the hooks, which the [outputs] run. [frameOf] says at which frame a cycle sounds. A failure is the hap's own: it is
     * reported and the other haps go on.
     */
    private inline fun route(
        hap: Hap,
        events: MutableList<SoundEvent>,
        marks: MutableList<HighlightEvent>,
        tempo: Double,
        tempoMayChange: Boolean,
        frameOf: (Rational) -> Long,
    ) {
        val whole = checkNotNull(hap.whole)
        val start = frameOf(whole.begin)
        val setsTempo = if (tempoMayChange) tempoOf(hap.value) else null
        val hapCps = setsTempo ?: tempo
        val hooks = hap.context.hooks
        val hooked = !hooks.isNullOrEmpty()
        try {
            val scheduled = if (hooked || outputs.oscTarget) {
                ScheduledHap(hap, start, start.toDouble() / sampleRate, hapCps, whole.begin.toDouble(), cyclesOf(hap))
            } else {
                null
            }
            if (!hooked || !hap.context.dominant) {
                if (outputs.oscTarget) {
                    outputs.oscOutput?.let { outputs.post(listOf(it), checkNotNull(scheduled)) }
                } else {
                    play(hap, whole.end - whole.begin, start, hapCps, events)
                }
            }
            if (hooked) outputs.post(checkNotNull(hooks), checkNotNull(scheduled))
        } catch (e: Exception) {
            reportRuntimeError(e, ROUTING_ERROR_ORIGIN)
        }
        if (setsTempo != null && setsTempo != anchor.cps) {
            anchor = TempoAnchor(start, whole.begin, setsTempo, sampleRate)
            cps = setsTempo
        }
        if (hap.context.locations.isNotEmpty()) {
            val value = hap.value as? Map<*, *>
            marks.add(HighlightEvent(start, endOf(hap, whole.end, frameOf), hap.context.locations, colorOf(value), markcssOf(value)))
        }
    }

    /** The default output of a hap: a voice that starts at frame [start], unless the sound is not there (yet). */
    private fun play(hap: Hap, wholeLength: Rational, start: Long, tempo: Double, events: MutableList<SoundEvent>) {
        // a value that is no object is the hap's own error (`expected hap.value to be an object, but got "c3". Hint: append .note() or .s() to the end`)
        val controls = controlsOf(hap)
        when (val lookup = sounds.resolve(controls)) {
            is SoundLookup.Ready -> {
                val cycle = checkNotNull(hap.whole).begin.toDouble()
                events.add(SoundEvent(start, voiceParams(controls, lookup.source, hapSeconds(controls, wholeLength, tempo), tempo, cycle)))
            }
            is SoundLookup.Unknown -> warningLog.add(unknownSoundWarning(lookup.key))
            SoundLookup.Loading, SoundLookup.Silent -> Unit
        }
    }

    /** `hap.duration` in cycles, for the hooks. */
    private fun cyclesOf(hap: Hap): Double = try {
        hap.duration.toDouble()
    } catch (e: CosicError) {
        checkNotNull(hap.whole).let { it.end - it.begin }.toDouble()
    }

    /** The frame the highlight of [hap] ends at: its onset plus `hap.duration` (`endClipped`, which the editor compares the playhead to). */
    private inline fun endOf(hap: Hap, wholeEnd: Rational, frameOf: (Rational) -> Long): Long = try {
        frameOf(checkNotNull(hap.whole).begin + hap.duration)
    } catch (e: CosicError) {
        frameOf(wholeEnd)
    }

    /** The `color` of a hap's value, as the text the editor reads it from (`hap.value?.color`). */
    private fun colorOf(value: Map<*, *>?): String? =
        value?.get("color").let { if (it == null || it === Undefined) null else if (it is String) it else jsToString(it) }

    /** The `markcss` control, which replaces the style of the mark when it is not empty (`hap.value?.markcss || default`). */
    private fun markcssOf(value: Map<*, *>?): String? = (value?.get("markcss") as? String)?.takeIf { it.isNotEmpty() }
}
