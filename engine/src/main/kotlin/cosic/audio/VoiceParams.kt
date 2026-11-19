package cosic.audio

import cosic.BankInfo
import cosic.SampleData
import kotlin.math.abs
import kotlin.math.floor

/** The MIDI note a voice plays when the pattern sets none. */
internal const val DEFAULT_NOTE = 48.0

/** True when a control was given and is a real number: absent and NaN controls both select the default. */
internal fun Double?.isSet(): Boolean = this != null && !isNaN()

/** What a voice plays. */
internal sealed interface VoiceSource {
    /** A built-in oscillator or noise source; `note` sets its pitch and `speed`, `begin` and `end` do not apply. */
    class Synth(val waveform: Waveform) : VoiceSource

    /**
     * A recorded sample: a bank entry or a drum-kit sound. [rootNote] is the MIDI note the recording sounds at, for
     * entries of pitched banks. For unpitched sounds it is null and `note` only transposes when it is set,
     * relative to note 48.
     */
    class Sample(val data: SampleData, val rootNote: Int?) : VoiceSource
}

/**
 * Raw controls for one voice, exactly as the pattern produced them. `null` (or NaN) means "not set"; every value is
 * clamped when [Mixer.startVoice] starts the voice, so callers never pre-process. Field names match the controls.
 *
 * - [source], [durationSec]: what plays, and the length of the event (a hap's whole divided by cps).
 * - [note]: MIDI note, default 48, clamped to -24..132. Pitches synths; transposes samples (see [VoiceSource.Sample]).
 * - [gain], [postgain]: linear, default 1, 0..4. [velocity]: default 1, 0..1. `postgain` scales the dry signal and
 *   the delay and reverb sends alike.
 * - [pan]: default 0.5 (centre), 0..1, equal-power.
 * - [speed]: sample playback rate multiplier, default 1, -8..8, negative plays backwards. Zero skips the event.
 * - [begin], [end]: fractions of the sample to play, defaults 0 and 1, each 0..1. `end <= begin` skips the event.
 * - [cut]: integer group; starting a voice fades every other voice in the same group over 5 ms. Absent = no group.
 * - [clip]: gate length as a multiple of [durationSec], 0..16. Default 1 for synths; unset for samples, which then
 *   play to their end.
 * - [attack], [decay], [release]: seconds, 0..10. [sustain]: level, 0..1. Defaults: synths 0.002, 0.1, 0.05, 0.8;
 *   samples 0.001, 0, 0.01, 1.
 * - [lpf], [hpf], [bpf]: filter cutoffs in Hz (20..0.45 x sample rate), applied in that order only when set.
 *   [lpq] and [hpq] default 0.707, [bpq] defaults 1, all 0.1..30.
 * - [shape]: waveshaper amount, 0..0.99. [crush]: bit depth, 1..16. [coarse]: sample-and-hold factor, 1..64.
 * - [delay], [room]: send levels into the shared delay and reverb, default 0, 0..1.
 * - [delaytime] (0.001..4 s), [delayfeedback] (0..0.95) and [size] (0..1) configure those buses for everyone: the
 *   most recently started voice that sets them wins, even if it sends nothing itself.
 */
internal data class VoiceParams(
    val source: VoiceSource,
    val durationSec: Double,
    val note: Double? = null,
    val gain: Double? = null,
    val velocity: Double? = null,
    val postgain: Double? = null,
    val pan: Double? = null,
    val speed: Double? = null,
    val begin: Double? = null,
    val end: Double? = null,
    val cut: Double? = null,
    val clip: Double? = null,
    val attack: Double? = null,
    val decay: Double? = null,
    val sustain: Double? = null,
    val release: Double? = null,
    val lpf: Double? = null,
    val lpq: Double? = null,
    val hpf: Double? = null,
    val hpq: Double? = null,
    val bpf: Double? = null,
    val bpq: Double? = null,
    val shape: Double? = null,
    val crush: Double? = null,
    val coarse: Double? = null,
    val delay: Double? = null,
    val delaytime: Double? = null,
    val delayfeedback: Double? = null,
    val room: Double? = null,
    val size: Double? = null,
)

/**
 * The entry of [bank] that a hit with raw controls [n] and [note] plays. Pitched banks (those with root notes) pick
 * the entry whose root is nearest to [note] (default 48; ties go to the lower root) and ignore [n]. Unpitched
 * banks pick `floor(n)` wrapped by the entry count. Pass the entry's root to [VoiceSource.Sample] for pitched banks.
 */
internal fun bankEntry(bank: BankInfo, n: Double, note: Double?): Int {
    val roots = bank.rootNotes ?: return Math.floorMod(floor(n).toInt(), maxOf(bank.count, 1))
    val target = if (note.isSet()) note!! else DEFAULT_NOTE
    var best = 0
    var bestDistance = Double.MAX_VALUE
    for (i in roots.indices) {
        val distance = abs(roots[i] - target)
        if (distance < bestDistance) {
            best = i
            bestDistance = distance
        }
    }
    return best
}
