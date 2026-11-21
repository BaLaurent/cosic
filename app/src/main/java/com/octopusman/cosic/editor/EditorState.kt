package com.octopusman.cosic.editor

import cosic.SourceRange
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/** The outcome of the latest evaluation, as the status line reports it. */
sealed interface EvalStatus {
    data object Idle : EvalStatus

    data class Ok(val warnings: List<String>) : EvalStatus

    /** [line] and [column] are 1-based, or 0 when the engine could not say where the error is. */
    data class Error(val message: String, val range: SourceRange?, val line: Int, val column: Int) : EvalStatus
}

/** What the engine reports while it plays, refreshed every frame. */
data class PlaybackInfo(
    val cps: Double,
    val cycle: Int,
    val runtimeError: String?,
    val warnings: List<String>,
)

/**
 * A step of the code that sounds right now: where it is ([range], in the coordinates of the text being edited), the CSS colour of its event's `color` control
 * and the style of its `markcss` control. Strudel draws the mark with `markcss` when the event has one, else as an outline of 2 px in `color`, white when unset.
 */
data class ActiveHighlight(val range: SourceRange, val color: String?, val markcss: String?)

/** Tempo in cycles per minute, without a decimal when it is a whole number. */
fun formatCpm(cps: Double): String {
    val cpm = cps * 60.0
    val whole = cpm.roundToLong()
    return if (abs(cpm - whole) < 0.05) whole.toString() else String.format(Locale.ROOT, "%.1f", cpm)
}
