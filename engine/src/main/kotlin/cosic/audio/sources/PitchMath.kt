package cosic.audio.sources

import cosic.pattern.jsToNumber
import cosic.pattern.jsTruthy
import cosic.pattern.midiToFreq
import cosic.pattern.noteToMidi

/** The MIDI note a synth plays when the pattern sets none (`getFrequencyFromValue(value, defaultNote = 36)`): 65.41 Hz. */
internal const val DEFAULT_SYNTH_NOTE = 36.0

/**
 * The frequency in Hz a source plays for [c] (`getFrequencyFromValue`, helpers.mjs:605-617): `note` as a number or a name (default
 * [defaultNote]; a `note` that is 0 or empty counts as not set, so `note 0` plays the default), unless `freq` is set (and not 0), which
 * wins; then times `2^octave`. Throws `not a note` for a `note` that is a string but no note.
 *
 * The note utilities are those of `cosic.pattern` (`noteToMidi`, `midiToFreq`); this only combines them.
 */
internal fun frequencyFromValue(c: SourceControls, defaultNote: Double = DEFAULT_SYNTH_NOTE): Double {
    val rawNote = c["note"]
    val note = when {
        !jsTruthy(rawNote) -> defaultNote
        rawNote is String -> noteToMidi(rawNote)
        else -> jsToNumber(rawNote)
    }
    val rawFreq = c["freq"]
    val freq = if (jsTruthy(rawFreq)) jsToNumber(rawFreq) else midiToFreq(note)
    val octave = c["octave"].let { if (it == null || it === cosic.pattern.Undefined) 0.0 else jsToNumber(it) }
    return freq * Math.pow(2.0, octave)
}
