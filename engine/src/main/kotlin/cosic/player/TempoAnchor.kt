package cosic.player

import cosic.pattern.Rational

/**
 * The clock: cycle [cycle] sounds at engine frame [frame], and time runs at [cps] cycles per second from there.
 * A tempo change starts a new anchor at the scheduling horizon instead of rescaling this one, so events that are
 * already queued keep their frames and only what comes after is retimed.
 */
internal class TempoAnchor(val frame: Long, val cycle: Rational, val cps: Double, private val sampleRate: Int) {
    /** The frame at which cycle [at] sounds, rounded to the nearest. */
    fun frameAt(at: Rational): Long = frame + Math.round((at - cycle).toDouble() / cps * sampleRate)
}
