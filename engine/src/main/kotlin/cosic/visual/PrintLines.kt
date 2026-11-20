package cosic.visual

import cosic.pattern.Hap
import cosic.pattern.jsMathRound
import cosic.pattern.jsNumberToString
import cosic.pattern.stringifyValues
import java.util.Collections
import java.util.IdentityHashMap

/*
 * What `print()` writes (packages/draw/print.mjs): a line for every hap, the time its whole begins at and its value: `0.25    : s:bd`. A widget (`._print()`) keeps the
 * last lines in a list; `.print()` writes them to the console as the haps are scheduled.
 */

/** `s.padEnd(length)`: [s] followed by spaces up to [length] characters; a length that is not a whole number counts rounded down, and one below the text's own adds nothing. */
internal fun padEnd(s: String, length: Double): String {
    val target = if (length.isNaN() || length < 1.0) 0 else Math.min(Math.floor(length), 1_000_000.0).toInt()
    return if (s.length >= target) s else s + " ".repeat(target - s.length)
}

/**
 * `formatTime(hap, round, padding)` of print.mjs:35-43: the time the whole of [hap] begins at (its part's, when it has no whole), [round] digits after the point
 * (0 shows the cycle only), padded on the right so the colons line up. [roundIsZero] says whether the program wrote exactly the number `0` (`round === 0`).
 */
internal fun formatTime(hap: Hap, round: Double, padding: Double, roundIsZero: Boolean = round == 0.0): String {
    val begin = hap.wholeOrPart().begin
    if (roundIsZero) return padEnd(jsNumberToString(begin.sam().toDouble()), padding)
    val factor = Math.pow(10.0, round)
    val time = jsMathRound(begin.toDouble() * factor) / factor
    return padEnd(jsNumberToString(time), padding + round + 1)
}

/** The line a hap is printed as: `time: values`. */
internal fun printLine(hap: Hap, round: Double, padding: Double, roundIsZero: Boolean = round == 0.0): String =
    "${formatTime(hap, round, padding, roundIsZero)}: ${stringifyValues(hap.value, true)}"

/**
 * The lines of one `_print` widget: at most [maxLines], the oldest dropped. A hap is written once, however many frames it stays in the memory of its loop (Strudel
 * remembers them in a `WeakSet` by identity, so two haps that look alike are two lines).
 */
internal class PrintLines(private val maxLines: Int, private val round: Double, private val padding: Double, private val roundIsZero: Boolean) {
    private val lines = ArrayList<String>()
    private val printed: MutableSet<Hap> = Collections.newSetFromMap(IdentityHashMap())

    /** The lines now, oldest first. Replaced, never changed, so it is safe to read from any thread. */
    @Volatile
    var snapshot: List<String> = emptyList()
        private set

    /** Writes a line for each of [haps] that has not been written yet. */
    fun append(haps: List<Hap>) {
        var changed = false
        for (hap in haps) {
            if (!printed.add(hap)) continue
            lines.add(printLine(hap, round, padding, roundIsZero))
            while (lines.size > maxLines) lines.removeAt(0)
            changed = true
        }
        if (changed) snapshot = lines.toList()
        // what has left the memory of the loop can never come back, so the set need not remember it
        if (printed.size > PRINTED_LIMIT) {
            val keep: MutableSet<Hap> = Collections.newSetFromMap(IdentityHashMap())
            keep.addAll(haps)
            printed.retainAll(keep)
        }
    }

    private companion object {
        const val PRINTED_LIMIT = 10_000
    }
}
