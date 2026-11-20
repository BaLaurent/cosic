package cosic.sound

import cosic.BankGroup
import cosic.pattern.CosicError
import cosic.pattern.getSoundIndex
import cosic.pattern.noteToMidi
import cosic.pattern.valueToMidi
import kotlin.math.abs

/** The pitch an unpitched sample is recorded at, and the one `valueToMidi` answers when an event sets neither `note` nor `freq`: C2. */
internal const val UNPITCHED_ROOT_MIDI = 36.0

/**
 * Which file an event plays and how far to repitch it: file [index] of group [group] of the bank, for an event that asks for MIDI
 * note [midi]; the file is played [transposeSemitones] higher than recorded.
 */
internal class SampleChoice(val group: Int, val index: Int, val midi: Double, val transposeSemitones: Double)

/**
 * `getCommonSampleInfo` (util.mjs:87-111) for the control map [value] of an event and the [groups] of a bank.
 *
 * The pitch asked for is `freq` (as MIDI), else `note` (a name or a number), else C2 (36). A list of files (one group without a note
 * name) is played at `pitch - 36` semitones. A pitched bank plays the group whose key is nearest to the pitch, the first one when
 * two are equally near (the order of the map, which is not the order of pitch), and `pitch - key` semitones. `n` picks the file
 * within the group: rounded, wrapped around, from the end for a negative number (see `getSoundIndex`); anything that is not a number
 * counts as 0, with a warning.
 *
 * Throws [CosicError] for a key that is not a note name (`not a note: "x"`) and for a bank without files.
 */
internal fun commonSampleInfo(groups: List<BankGroup>, value: Map<String, Any?>): SampleChoice {
    val midi = valueToMidi(value, UNPITCHED_ROOT_MIDI)
    val n = value["n"]
    val first = groups.firstOrNull() ?: throw CosicError("this sound has no samples")
    if (first.noteName == null) {
        if (first.urls.isEmpty()) throw CosicError("this sound has no samples")
        return SampleChoice(0, getSoundIndex(n, first.urls.size), midi, midi - UNPITCHED_ROOT_MIDI)
    }
    var best = -1
    var bestMidi = 0.0
    var bestDistance = 0.0
    for ((i, group) in groups.withIndex()) {
        val key = group.midi?.takeUnless { it.isNaN() } ?: noteToMidi(group.noteName)
        val distance = abs(key - midi)
        if (best < 0 || distance < bestDistance) {
            best = i
            bestMidi = key
            bestDistance = distance
        }
    }
    val files = groups[best].urls
    if (files.isEmpty()) throw CosicError("this sound has no samples")
    return SampleChoice(best, getSoundIndex(n, files.size), midi, midi - bestMidi)
}

/** [commonSampleInfo] of an event that sets only [n], [note] and [freq] (a preview of a sound, a test). */
internal fun commonSampleInfo(groups: List<BankGroup>, n: Any?, note: Any?, freq: Any?): SampleChoice {
    val value = LinkedHashMap<String, Any?>(3)
    if (n != null) value["n"] = n
    if (note != null) value["note"] = note
    if (freq != null) value["freq"] = freq
    return commonSampleInfo(groups, value)
}
