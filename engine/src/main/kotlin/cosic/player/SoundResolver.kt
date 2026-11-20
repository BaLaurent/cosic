package cosic.player

import cosic.SoundLibrary
import cosic.audio.DrumKit
import cosic.audio.VoiceSource
import cosic.audio.Waveform
import cosic.audio.bankEntry
import cosic.pattern.ControlMap
import cosic.pattern.jsStringToNumber
import cosic.pattern.noteToMidi
import kotlin.math.floor

/** The outcome of looking up the sound an event asks for. */
internal sealed interface SoundLookup {
    class Ready(val source: VoiceSource) : SoundLookup

    /** A bank that exists but whose sample is not decoded yet. The library has been asked to load it. */
    data object Loading : SoundLookup

    class Unknown(val key: String) : SoundLookup
}

/** What an event plays when it names no sound: `note("c e g")` on its own is a triangle melody. */
private val DEFAULT_SOUND = SoundLookup.Ready(VoiceSource.Synth(Waveform.TRIANGLE))

/**
 * Finds what to play for the sound an event names: a synth waveform first, then a bank of the [library], then the
 * built-in drum sounds, which are the kit's (`bd`) and the drum machines' (`cosic808_bd`, the sound that
 * `s("bd").bank("cosic808")` asks for). A user bank may thus replace a drum or a machine's sound, but never a
 * synth. Lookups ask the library for the sample and render drum sounds on first use (unless [drums] has been warmed
 * up), so doing one off the audio thread (see [dryRun]) warms up what it needs.
 */
internal class SoundResolver(sampleRate: Int, private val library: SoundLibrary, private val drums: DrumKit = DrumKit(sampleRate)) {
    /**
     * The sound of an event's controls: `s`, prefixed with `bank_` when `bank` is set, with `n` and `note` choosing the sample.
     * `note` is kept as the pattern wrote it (`c3`) and becomes its MIDI number here; a name that is not a note is an error.
     */
    fun resolve(controls: ControlMap): SoundLookup {
        val name = controls["s"] as? String ?: return DEFAULT_SOUND
        val bank = controls["bank"] as? String
        return resolve(if (bank == null) name else "${bank}_$name", sampleNumber(controls["n"]), noteNumber(controls["note"]))
    }

    /** The sound called [key]; [n] counts samples (or drum variants) and [note] picks the entry of a pitched bank. */
    fun resolve(key: String, n: Double, note: Double?): SoundLookup {
        Waveform.byName(key)?.let { return SoundLookup.Ready(VoiceSource.Synth(it)) }
        library.bank(key)?.let { bank ->
            val index = bankEntry(bank, n, note)
            val data = library.sample(key, index) ?: return SoundLookup.Loading
            return SoundLookup.Ready(VoiceSource.Sample(data, bank.rootNotes?.get(index)))
        }
        drums.sample(key, floor(n).toInt())?.let { return SoundLookup.Ready(VoiceSource.Sample(it, null)) }
        return SoundLookup.Unknown(key)
    }
}

/** The sample number of an `n`: a number, or text that reads as one (anything else is sample 0). */
private fun sampleNumber(n: Any?): Double = when (n) {
    is Double -> n
    is String -> jsStringToNumber(n).let { if (it.isNaN()) 0.0 else it }
    else -> 0.0
}

/** The MIDI number of a `note`: a number as it is, a note name converted; null when none is set. */
private fun noteNumber(note: Any?): Double? = when (note) {
    is Double -> note
    is String -> noteToMidi(note)
    else -> null
}
