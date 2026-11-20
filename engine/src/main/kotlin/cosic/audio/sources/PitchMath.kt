package cosic.audio.sources

import cosic.audio.Param
import cosic.audio.ParamSet
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

/**
 * [frequencyFromValue] for the controls of a voice's [ParamSet] (a `note` name has been turned into its number by then). It runs on the
 * audio thread and must not allocate, which rules out `midiToFreq` of `cosic.pattern`: `StrictMath.pow` allocates in the JVM's early
 * compilation tiers. `Math.pow` is an intrinsic and gives the same frequency up to the last bit.
 */
internal fun frequencyFromParams(p: ParamSet, defaultNote: Double = DEFAULT_SYNTH_NOTE): Double {
    val rawNote = p[Param.note]
    val note = if (rawNote.isNaN() || rawNote == 0.0) defaultNote else rawNote
    val rawFreq = p[Param.freq]
    val freq = if (rawFreq.isNaN() || rawFreq == 0.0) Math.pow(2.0, (note - 69.0) / 12.0) * 440.0 else rawFreq
    return freq * Math.pow(2.0, p.or(Param.octave, 0.0))
}
