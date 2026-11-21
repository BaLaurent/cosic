package cosic.audio

import cosic.SampleData

/**
 * Everything a voice needs to start, as the scheduler hands it to the [Mixer]: what plays ([source]), for how long, at which tempo
 * and cycle, and the controls of the event, split into the effect chains Strudel's `superdough()` builds from them.
 *
 * - [durationSec]: the length of the event in seconds, `Hap.duration / cps`: the whole of the hap, or its `duration` control (in
 *   cycles) when set, times its `clip` when set. The voice does not apply `clip` again.
 * - [cps], [cycle]: the tempo and the hap's `whole.begin`, which LFOs that are locked to the cycle (`tremolo`, filter LFOs)
 *   start their phase from (`cycle / cps`).
 * - [main]: the controls of the event itself; [fx] the controls of its `FX(...)` elements, which run through the chain first
 *   (at most [MAX_FX]); [mods]: the `.lfo`, `.env` and `.bmod` modulators of all of them, in the order `superdough` connects them.
 * - [irSample]: the sample named by the `ir` control, which the reverb convolves with instead of generating a room, when the
 *   scheduler resolved one (null: none asked for or not loaded yet).
 * - [defaultRelease]: `getDefaultValue('release')`, the release the chain of a voice lives for when the event sets none (0.01). Only how long the chain stays up
 *   counts it: the envelopes of the sources have a release default of their own.
 */
internal class VoiceParams(
    val source: VoiceSource,
    val durationSec: Double,
    val cps: Double,
    val cycle: Double,
    val main: ParamSet,
    val fx: List<ParamSet> = emptyList(),
    val mods: List<ModSpec> = emptyList(),
    val irSample: SampleData? = null,
    val defaultRelease: Double = DEFAULT_VOICE_RELEASE,
)
