package cosic.player

import cosic.BuiltinSound
import cosic.SampleBank
import cosic.SoundLibrary
import cosic.WaveTableBank
import cosic.audio.AudioSettings
import cosic.audio.DRUM_VARIANTS
import cosic.audio.DrumKit
import cosic.audio.SampleSourceParams
import cosic.audio.VoiceSource
import cosic.audio.sources.SourceSpec
import cosic.audio.sources.SynthRegistry
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
import cosic.soundfont.PRESET_CONTROL
import cosic.soundfont.PresetRef
import cosic.soundfont.SoundfontBank
import cosic.soundfont.resolvePreset
import cosic.soundfont.resolveSoundfont
import java.util.concurrent.atomic.AtomicLong

/** The outcome of looking up the sound an event asks for. */
internal sealed interface SoundLookup {
    class Ready(val source: VoiceSource) : SoundLookup

    /** A sound that exists but whose sample is not decoded yet. The library has been asked to load it. */
    data object Loading : SoundLookup

    /**
     * A sound that exists but whose file could not be loaded (the library asked, the load failed, and it will not ask again for a while): [sound] is `name:index` as
     * Strudel says it (`[sampler] could not load "bd:0"`), [reason] what went wrong. Unlike [Loading] it ends: the event is skipped, with a warning, and an export need not wait for it.
     */
    class Failed(val sound: String, val reason: String) : SoundLookup

    /** A rest: `s` was `-`, `~` or `_`. No event, no warning. */
    data object Silent : SoundLookup

    /** No sound of that name is registered (`sound <key> not found! Is it loaded?`). */
    class Unknown(val key: String) : SoundLookup
}

private val REST_NAMES = setOf("-", "~", "_")

/**
 * Finds what to play for the sound an event names, the way `superdough()` and `getSound` do: a rest (`-`, `~`, `_`) first; then `s`,
 * prefixed with `bank_` when `bank` is set (both by JavaScript truthiness); then, in this order, the registry of the [library]
 * (case-insensitive, the last registration wins, so a bank called `sine` replaces the synth), the engine's own synths ([SynthRegistry]), and its
 * synthesised drums (`bd`, and `cosic808_bd`, the sound that `s("bd").bank("cosic808")` asks for). An `s` that is no string plays a triangle
 * and logs a warning. For a bank, `n` and `note`/`freq` choose the file and its pitch ([commonSampleInfo]).
 *
 * A synth, a noise or a wavetable is a source made for the event: it reads the event's controls, which is why a lookup is given the length of the event in
 * seconds ([resolve]'s `durationSec`, `Hap.duration / cps`, `clip` included) and the tempo. The source is made here, off the audio thread's render path (the scheduler
 * runs a lookup, and so does the dry run of a program), and given a seed of its own that makes its noise differ from note to note and repeat from run to run.
 *
 * Lookups ask the library for the sample, which starts loading it, and render drum sounds on first use (unless [drums] has been warmed up),
 * so doing one off the audio thread (see [dryRun]) warms up what it needs; the first source of each wave builds its tables. A soundfont (`gm_*`) is played by
 * [resolveSoundfont]. A wavetable that is not decoded yet is [SoundLookup.Loading], as a sample is.
 */
internal class SoundResolver(
    private val sampleRate: Int,
    val library: SoundLibrary,
    private val drums: DrumKit = DrumKit(sampleRate),
    /** The settings programs change; an event with no `s` plays the sound they default it to (`getDefaultValue('s')`, a triangle at first). */
    val settings: AudioSettings = AudioSettings(),
) {
    private val sourceCount = AtomicLong()

    /** The programs of the events' `K(...)` and `worklet(...)` controls: made and compiled here, off the audio thread's render path, like the sources. */
    val kabel = cosic.kabel.KabelFactory(sampleRate)

    /**
     * The sound of an event's controls, which lasts [durationSec] seconds at the tempo [cps]. A `note` that is not a note name, and a bank whose keys are no notes,
     * throw [cosic.pattern.CosicError] (`not a note: "x"`).
     */
    fun resolve(controls: ControlMap, durationSec: Double, cps: Double): SoundLookup {
        (controls[PRESET_CONTROL] as? PresetRef)?.let { return resolvePreset(it, controls) } // `.soundfont(sf)`: the preset of a loaded soundfont, not a named sound
        val given = controls["s"]
        val s: Any? = if (given === Undefined || (given == null && !controls.containsKey("s"))) settings.current.default("s") else given
        if (s is String && s in REST_NAMES) return SoundLookup.Silent
        val bank = controls["bank"]
        val name: Any? = if (jsTruthy(bank) && jsTruthy(s)) "${jsToString(bank)}_${jsToString(s)}" else s
        if (name !is String) {
            logger("getSound: expected string got \"${jsToString(name)}\". fall back to triangle", "warning", null)
            return synth("triangle", controls, durationSec, cps)
        }
        return when (val entry = library.sound(name)) {
            is SampleBank -> {
                val choice = commonSampleInfo(entry.groups, controls)
                val data = library.sample(entry, choice.group, choice.index) ?: return notLoaded(name, choice.index, entry.groups.getOrNull(choice.group)?.urls?.getOrNull(choice.index))
                SoundLookup.Ready(VoiceSource.Sample(SampleSourceParams(data, choice.transposeSemitones, nudgeSeconds = nudgeOf(controls))))
            }
            is WaveTableBank -> {
                val choice = commonSampleInfo(entry.groups, controls)
                val frames = library.wavetable(entry, choice.group, choice.index) ?: return notLoaded(name, choice.index, entry.groups.getOrNull(choice.group)?.urls?.getOrNull(choice.index))
                SoundLookup.Ready(VoiceSource.Synth(entry.name, SynthRegistry.wavetable(frames, specOf(controls, durationSec, cps))))
            }
            is SoundfontBank -> resolveSoundfont(library, entry, controls)
            is BuiltinSound -> builtin(entry.name, controls, durationSec, cps) ?: SoundLookup.Unknown(name)
            null -> builtin(name, controls, durationSec, cps) ?: SoundLookup.Unknown(name)
            else -> SoundLookup.Unknown(name)
        }
    }

    /** A file the library has no data for: [SoundLookup.Failed] when its load failed lately, [SoundLookup.Loading] otherwise (it is being fetched, or nobody asked yet). */
    private fun notLoaded(name: String, index: Int, url: String?): SoundLookup {
        val reason = url?.let(library::fileFailure) ?: return SoundLookup.Loading
        return SoundLookup.Failed("$name:$index", reason)
    }

    /** The sound called [key] for a preview: sample [n] of it, at MIDI note [note] when that is given, lasting [durationSec] at the tempo [cps]. */
    fun resolve(key: String, n: Double, note: Double?, durationSec: Double, cps: Double): SoundLookup {
        val controls = HashMap<String, Any?>(3)
        controls["s"] = key
        controls["n"] = n
        if (note != null) controls["note"] = note
        return resolve(controls, durationSec, cps)
    }

    /** A synth of the engine, or one of its synthesised drums (variant `n`); null when [name] is neither. */
    private fun builtin(name: String, controls: ControlMap, durationSec: Double, cps: Double): SoundLookup? {
        if (SynthRegistry.canonical(name) != null) return synth(name, controls, durationSec, cps)
        val key = name.lowercase()
        val sample = drums.sample(key, getSoundIndex(controls["n"], DRUM_VARIANTS)) ?: return null
        val transpose = valueToMidi(controls, UNPITCHED_ROOT_MIDI) - UNPITCHED_ROOT_MIDI
        return SoundLookup.Ready(VoiceSource.Sample(SampleSourceParams(sample, transpose, nudgeSeconds = nudgeOf(controls))))
    }

    /** The source of the synth [name] (a name [SynthRegistry] knows) for one event. */
    private fun synth(name: String, controls: ControlMap, durationSec: Double, cps: Double): SoundLookup.Ready {
        val canonical = checkNotNull(SynthRegistry.canonical(name)) { "'$name' is no synth" }
        val source = checkNotNull(SynthRegistry.create(canonical, specOf(controls, durationSec, cps))) { "no source for synth '$canonical'" }
        return SoundLookup.Ready(VoiceSource.Synth(canonical, source))
    }

    /** What a source is made from: the event's controls and its length and tempo, with a seed of its own. */
    private fun specOf(controls: ControlMap, durationSec: Double, cps: Double) =
        SourceSpec(sampleRate, controls.asSourceControls(), durationSec, cps, sourceCount.incrementAndGet())

    /** The `nudge` control in seconds; anything but a number counts as none. */
    private fun nudgeOf(controls: ControlMap): Double = (controls["nudge"] as? Double) ?: 0.0
}
