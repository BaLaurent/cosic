package cosic.player

import cosic.SoundLibrary
import cosic.audio.DrumKit
import cosic.audio.VoiceSource
import cosic.audio.Waveform
import cosic.audio.bankEntry
import cosic.pattern.ControlMap
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
 * built-in drum kit. A user bank may thus replace a drum, but never a synth. Lookups ask the library for the sample
 * and render drum sounds on first use, so doing one off the audio thread (see [dryRun]) warms everything up.
 */
internal class SoundResolver(sampleRate: Int, private val library: SoundLibrary) {
    private val drums = DrumKit(sampleRate)

    /** The sound of an event's controls: `s`, prefixed with `bank_` when `bank` is set, with `n` and `note` choosing the sample. */
    fun resolve(controls: ControlMap): SoundLookup {
        val name = controls["s"] as? String ?: return DEFAULT_SOUND
        val bank = controls["bank"] as? String
        return resolve(if (bank == null) name else "${bank}_$name", controls["n"] as? Double ?: 0.0, controls["note"] as? Double)
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
