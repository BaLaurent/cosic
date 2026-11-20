package cosic.audio

import cosic.SampleData
import cosic.audio.sources.SoundSource
import cosic.soundfont.sf2.PresetZoneVoice

/** What a voice plays. */
internal sealed interface VoiceSource {
    /**
     * A sound the engine makes itself: an oscillator, a noise, a supersaw, a pulse, a bytebeat, `sbd` or a wavetable. The [source] was made for this event by the sound lookup from the
     * event's controls (`SynthRegistry`), and brings everything of its own: its pitch (`note`, `freq`, vibrato, FM), its level and its amplitude envelope, so the voice does not apply one.
     * `speed`, `begin` and `end` do not apply to it. [name] is the name of the sound: the canonical one for a synth (`sawtooth` for `saw`), the bank's for a wavetable.
     * A source plays once, so a [Synth] belongs to the event it was made for.
     */
    class Synth(val name: String, val source: SoundSource) : VoiceSource

    /** A recorded sample (a bank file, a drum-kit sound, a soundfont zone): the sampler of superdough, see [SampleSourceParams]. */
    class Sample(val params: SampleSourceParams) : VoiceSource

    /** A note of a soundfont preset loaded by the program (`.soundfont(sf)`): the zones that sound, played and summed as sfumato does, without the effects of the event. */
    class Preset(val zones: List<PresetZoneVoice>) : VoiceSource
}

/**
 * Everything the sampler needs that the controls of an event do not say: the [data] to play and how far to repitch it, which the sound
 * lookup decided from `note`, `freq` and the bank it picked the file from (`getCommonSampleInfo`).
 *
 * - [transposeSemitones]: the file plays at `2^(transpose/12)` times its speed. For a bank that is `pitch - root` (36 when the bank has no
 *   notes), for a soundfont zone `(100 pitch - baseDetune) / 100`.
 * - [peakGain]: the level of the amplitude envelope's peak (1 for samples, 0.3 for soundfonts); the source is multiplied by it.
 * - [loopStartFrame], [loopEndFrame]: a loop that belongs to the file itself, in frames of [data] (a soundfont's); NaN for none. The
 *   `loop`, `loopBegin` and `loopEnd` controls set the loop of an ordinary sample.
 * - [honoursSampleControls]: false for soundfonts, which ignore `begin`, `end`, `speed`, `unit`, `nudge`, `cut` and the sampler's gate
 *   rule (their gate is the hap's duration, always).
 * - [nudgeSeconds]: the `nudge` control, how long after the event's start the source starts (the envelope does not wait); a negative
 *   nudge counts as none.
 */
internal class SampleSourceParams(
    val data: SampleData,
    val transposeSemitones: Double = 0.0,
    val peakGain: Double = 1.0,
    val loopStartFrame: Double = Double.NaN,
    val loopEndFrame: Double = Double.NaN,
    val honoursSampleControls: Boolean = true,
    val nudgeSeconds: Double = 0.0,
)
