package cosic.player

import cosic.audio.VoiceParams
import cosic.audio.VoiceSource
import cosic.pattern.ControlMap
import cosic.pattern.noteToMidi

/** A voice to start at engine frame [startFrame]. */
internal class SoundEvent(val startFrame: Long, val voice: VoiceParams)

/**
 * The raw controls of one event as voice parameters, with its [source] and the length of its whole in seconds.
 * `s`, `n` and `bank` only choose the source and are not parameters. Controls that were not set stay null, so the
 * voice applies its own defaults, and a value that is not a number counts as not set. The keys are Strudel's
 * (`lpf` sets `cutoff`, `size` sets `roomsize`, ...); a `note` that is a name becomes its MIDI number here, as it does
 * in Strudel's output stage, so that patterns keep the names they were written with.
 */
internal fun voiceParams(controls: ControlMap, source: VoiceSource, durationSec: Double): VoiceParams = VoiceParams(
    source, durationSec,
    note = controls.midiNote(), gain = controls.number("gain"), velocity = controls.number("velocity"),
    postgain = controls.number("postgain"), pan = controls.number("pan"), speed = controls.number("speed"),
    begin = controls.number("begin"), end = controls.number("end"), cut = controls.number("cut"),
    clip = controls.number("clip"), attack = controls.number("attack"), decay = controls.number("decay"),
    sustain = controls.number("sustain"), release = controls.number("release"), lpf = controls.number("cutoff"),
    lpq = controls.number("resonance"), hpf = controls.number("hcutoff"), hpq = controls.number("hresonance"),
    bpf = controls.number("bandf"), bpq = controls.number("bandq"), shape = controls.number("shape"),
    crush = controls.number("crush"), coarse = controls.number("coarse"), delay = controls.number("delay"),
    delaytime = controls.number("delaytime"), delayfeedback = controls.number("delayfeedback"),
    room = controls.number("room"), size = controls.number("roomsize"),
)

private fun ControlMap.number(key: String): Double? = this[key] as? Double

/** The MIDI number of the `note` control: a number as it is, a note name converted (`c3` is 48); anything else counts as not set. */
private fun ControlMap.midiNote(): Double? = when (val note = this["note"]) {
    is Double -> note
    is String -> noteToMidi(note)
    else -> null
}
