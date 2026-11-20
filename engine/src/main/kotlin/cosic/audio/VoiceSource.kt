package cosic.audio

import cosic.BankInfo
import cosic.SampleData
import kotlin.math.abs
import kotlin.math.floor

/** The MIDI note a voice plays when the pattern sets none. */
internal const val DEFAULT_NOTE = 48.0

/** True when a control was given and is a real number: absent and NaN controls both select the default. */
internal fun Double?.isSet(): Boolean = this != null && !isNaN()

/** What a voice plays. */
internal sealed interface VoiceSource {
    /** A built-in oscillator or noise source; `note` sets its pitch and `speed`, `begin` and `end` do not apply. */
    class Synth(val waveform: Waveform) : VoiceSource

    /**
     * A recorded sample: a bank entry or a drum-kit sound. [rootNote] is the MIDI note the recording sounds at, for
     * entries of pitched banks. For unpitched sounds it is null and `note` only transposes when it is set,
     * relative to note 48.
     */
    class Sample(val data: SampleData, val rootNote: Int?) : VoiceSource
}

/**
 * The entry of [bank] that a hit with raw controls [n] and [note] plays. Pitched banks (those with root notes) pick
 * the entry whose root is nearest to [note] (default 48; ties go to the lower root) and ignore [n]. Unpitched
 * banks pick `floor(n)` wrapped by the entry count. Pass the entry's root to [VoiceSource.Sample] for pitched banks.
 */
internal fun bankEntry(bank: BankInfo, n: Double, note: Double?): Int {
    val roots = bank.rootNotes ?: return Math.floorMod(floor(n).toInt(), maxOf(bank.count, 1))
    val target = if (note.isSet()) note!! else DEFAULT_NOTE
    var best = 0
    var bestDistance = Double.MAX_VALUE
    for (i in roots.indices) {
        val distance = abs(roots[i] - target)
        if (distance < bestDistance) {
            best = i
            bestDistance = distance
        }
    }
    return best
}
