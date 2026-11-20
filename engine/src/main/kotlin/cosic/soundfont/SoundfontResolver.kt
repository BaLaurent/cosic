package cosic.soundfont

import cosic.SoundLibrary
import cosic.audio.SampleSourceParams
import cosic.audio.VoiceSource
import cosic.pattern.ControlMap
import cosic.pattern.CosicError
import cosic.pattern.Undefined
import cosic.pattern.freqToMidi
import cosic.pattern.getSoundIndex
import cosic.pattern.jsToNumber
import cosic.pattern.jsTruthy
import cosic.pattern.jsTypeof
import cosic.pattern.noteToMidi
import cosic.player.SoundLookup

/** The level a soundfont voice reaches: `getParamADSR(gain, ..., 0, 0.3, ...)` in fontloader.mjs:165. */
internal const val SOUNDFONT_PEAK_GAIN = 0.3

/**
 * What the `gm_*` sound [bank] plays for an event: the soundfont file `n` picks (`getSoundIndex(value.n, fonts.length)`, so it wraps around), at the pitch of `freq` or `note`
 * (`getFontBufferSource`, fontloader.mjs:37-71), as the sample of the zone that covers the pitch, tuned by the zone's numbers and looped by the zone's loop.
 *
 * The pitch is `freqToMidi(freq)` when `freq` is truthy, else `note`, which is `c3` when the event has none: a name is read by `noteToMidi` (a name that is no note throws
 * `not a note: "x"`), a number is the MIDI note; anything else is an error (`unexpected "note" type "boolean"`). Soundfonts ignore `begin`, `end`, `speed`, `unit`, `loop`, `cut`
 * and `nudge`: the voice plays the whole zone at the rate the pitch asks for until the event's duration is over, with the envelope the `attack`, `decay`, `sustain` and
 * `release` controls say, at a peak of 0.3 ([SOUNDFONT_PEAK_GAIN]).
 *
 * [SoundLookup.Loading] while the font file or the zone is not decoded yet; the library has been asked for it.
 */
internal fun resolveSoundfont(library: SoundLibrary, bank: SoundfontBank, controls: ControlMap): SoundLookup {
    // an infinite `n` wraps to NaN, and `fonts[NaN]` is no font: Strudel asks for `undefined.js` and nothing plays
    if (jsToNumber(controls["n"] ?: 0.0).isInfinite()) return SoundLookup.Loading
    val font = bank.fonts[getSoundIndex(controls["n"], bank.fonts.size)]
    val midi = soundfontMidi(controls)
    val zone = library.fontZone(font, midi) ?: return SoundLookup.Loading
    return SoundLookup.Ready(
        VoiceSource.Sample(
            SampleSourceParams(
                data = zone.data,
                transposeSemitones = zone.transposeSemitones(midi),
                peakGain = SOUNDFONT_PEAK_GAIN,
                loopStartFrame = zone.loopStartFrame,
                loopEndFrame = zone.loopEndFrame,
                honoursSampleControls = false,
            ),
        ),
    )
}

/** The MIDI note a soundfont event asks for: see [resolveSoundfont]. */
internal fun soundfontMidi(controls: ControlMap): Double {
    val freq = controls["freq"]
    if (jsTruthy(freq)) return freqToMidi(jsToNumber(freq))
    val note = controls["note"]
    return when {
        note === Undefined || (note == null && !controls.containsKey("note")) -> noteToMidi("c3")
        note is String -> noteToMidi(note)
        note is Number -> note.toDouble()
        else -> throw CosicError("unexpected \"note\" type \"${jsTypeof(note)}\"")
    }
}
