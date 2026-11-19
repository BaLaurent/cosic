package cosic.player

import cosic.audio.VoiceParams
import cosic.audio.VoiceSource
import cosic.pattern.ControlMap

/** A voice to start at engine frame [startFrame]. */
internal class SoundEvent(val startFrame: Long, val voice: VoiceParams)

/**
 * The raw controls of one event as voice parameters, with its [source] and the length of its whole in seconds.
 * `s`, `n` and `bank` only choose the source and are not parameters. Controls that were not set stay null, so the
 * voice applies its own defaults, and a value that is not a number counts as not set.
 */
internal fun voiceParams(controls: ControlMap, source: VoiceSource, durationSec: Double): VoiceParams = VoiceParams(
    source, durationSec,
    note = controls.number("note"), gain = controls.number("gain"), velocity = controls.number("velocity"),
    postgain = controls.number("postgain"), pan = controls.number("pan"), speed = controls.number("speed"),
    begin = controls.number("begin"), end = controls.number("end"), cut = controls.number("cut"),
    clip = controls.number("clip"), attack = controls.number("attack"), decay = controls.number("decay"),
    sustain = controls.number("sustain"), release = controls.number("release"), lpf = controls.number("lpf"),
    lpq = controls.number("lpq"), hpf = controls.number("hpf"), hpq = controls.number("hpq"),
    bpf = controls.number("bpf"), bpq = controls.number("bpq"), shape = controls.number("shape"),
    crush = controls.number("crush"), coarse = controls.number("coarse"), delay = controls.number("delay"),
    delaytime = controls.number("delaytime"), delayfeedback = controls.number("delayfeedback"),
    room = controls.number("room"), size = controls.number("size"),
)

private fun ControlMap.number(key: String): Double? = this[key] as? Double
