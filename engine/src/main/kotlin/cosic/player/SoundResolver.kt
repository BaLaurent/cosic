package cosic.player

import cosic.BuiltinSound
import cosic.SampleBank
import cosic.SoundLibrary
import cosic.WaveTableBank
import cosic.audio.DRUM_VARIANTS
import cosic.audio.DrumKit
import cosic.audio.SampleSourceParams
import cosic.audio.VoiceSource
import cosic.audio.Waveform
import cosic.audio.sources.asSourceControls
import cosic.pattern.ControlMap
import cosic.pattern.Undefined
import cosic.pattern.getSoundIndex
import cosic.pattern.jsToString
import cosic.pattern.jsTruthy
import cosic.pattern.logger
import cosic.pattern.valueToMidi
import cosic.sound.UNPITCHED_ROOT_MIDI
import cosic.sound.commonSampleInfo

/** The outcome of looking up the sound an event asks for. */
internal sealed interface SoundLookup {
    class Ready(val source: VoiceSource) : SoundLookup

    /** A sound that exists but whose sample is not decoded yet. The library has been asked to load it. */
    data object Loading : SoundLookup

    /** A rest: `s` was `-`, `~` or `_`. No event, no warning. */
    data object Silent : SoundLookup

    /** No sound of that name is registered (`sound <key> not found! Is it loaded?`). */
    class Unknown(val key: String) : SoundLookup
}

/** What an event plays when it names no sound, and when its sound name is not a string: `note("c e g")` on its own is a triangle melody. */
private val TRIANGLE = SoundLookup.Ready(VoiceSource.Synth(Waveform.TRIANGLE))

private val REST_NAMES = setOf("-", "~", "_")

/**
 * Finds what to play for the sound an event names, the way `superdough()` and `getSound` do: a rest (`-`, `~`, `_`) first; then `s`,
 * prefixed with `bank_` when `bank` is set (both by JavaScript truthiness); then, in this order, the registry of the [library]
 * (case-insensitive, the last registration wins, so a bank called `sine` replaces the synth), the engine's own synths, and its
 * synthesised drums (`bd`, and `cosic808_bd`, the sound that `s("bd").bank("cosic808")` asks for). An `s` that is no string plays a triangle
 * and logs a warning. For a bank, `n` and `note`/`freq` choose the file and its pitch ([commonSampleInfo]).
 *
 * Lookups ask the library for the sample, which starts loading it, and render drum sounds on first use (unless [drums] has been warmed up),
 * so doing one off the audio thread (see [dryRun]) warms up what it needs. Soundfonts have no source yet and look unknown. A wavetable that is not decoded yet is [SoundLookup.Loading], as a sample is.
 */
internal class SoundResolver(sampleRate: Int, val library: SoundLibrary, private val drums: DrumKit = DrumKit(sampleRate)) {
    /**
     * The sound of an event's controls. A `note` that is not a note name, and a bank whose keys are no notes, throw [cosic.pattern.CosicError]
     * (`not a note: "x"`).
     */
    fun resolve(controls: ControlMap): SoundLookup {
        val given = controls["s"]
        val s: Any? = if (given === Undefined || (given == null && !controls.containsKey("s"))) "triangle" else given
        if (s is String && s in REST_NAMES) return SoundLookup.Silent
        val bank = controls["bank"]
        val name: Any? = if (jsTruthy(bank) && jsTruthy(s)) "${jsToString(bank)}_${jsToString(s)}" else s
        if (name !is String) {
            logger("getSound: expected string got \"${jsToString(name)}\". fall back to triangle", "warning", null)
            return TRIANGLE
        }
        return when (val entry = library.sound(name)) {
            is SampleBank -> {
                val choice = commonSampleInfo(entry.groups, controls)
                val data = library.sample(entry, choice.group, choice.index) ?: return SoundLookup.Loading
                SoundLookup.Ready(VoiceSource.Sample(SampleSourceParams(data, choice.transposeSemitones, nudgeSeconds = nudgeOf(controls))))
            }
            is WaveTableBank -> {
                val choice = commonSampleInfo(entry.groups, controls)
                val frames = library.wavetable(entry, choice.group, choice.index) ?: return SoundLookup.Loading
                SoundLookup.Ready(VoiceSource.Wavetable(frames, controls.asSourceControls()))
            }
            is BuiltinSound -> builtin(entry.name, controls) ?: SoundLookup.Unknown(name)
            null -> builtin(name, controls) ?: SoundLookup.Unknown(name)
            else -> SoundLookup.Unknown(name)
        }
    }

    /** The sound called [key] for a preview: sample [n] of it, at MIDI note [note] when that is given. */
    fun resolve(key: String, n: Double, note: Double?): SoundLookup {
        val controls = HashMap<String, Any?>(3)
        controls["s"] = key
        controls["n"] = n
        if (note != null) controls["note"] = note
        return resolve(controls)
    }

    /** A synth of the engine, or one of its synthesised drums (variant `n`); null when [name] is neither. */
    private fun builtin(name: String, controls: ControlMap): SoundLookup? {
        val key = name.lowercase()
        Waveform.byName(key)?.let { return SoundLookup.Ready(VoiceSource.Synth(it)) }
        val sample = drums.sample(key, getSoundIndex(controls["n"], DRUM_VARIANTS)) ?: return null
        val transpose = valueToMidi(controls, UNPITCHED_ROOT_MIDI) - UNPITCHED_ROOT_MIDI
        return SoundLookup.Ready(VoiceSource.Sample(SampleSourceParams(sample, transpose, nudgeSeconds = nudgeOf(controls))))
    }

    /** The `nudge` control in seconds; anything but a number counts as none. */
    private fun nudgeOf(controls: ControlMap): Double = (controls["nudge"] as? Double) ?: 0.0
}
