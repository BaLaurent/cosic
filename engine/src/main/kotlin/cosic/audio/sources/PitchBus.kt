package cosic.audio.sources

import java.util.Arrays

/**
 * The pitch inputs of the oscillators of one voice for one block, sample by sample: what superdough connects to the `detune` (cents) and
 * `frequency` (Hz) AudioParams of its `OscillatorNode`s. Vibrato and the pitch envelope add into [detuneCents] ([PitchModulator]); FM adds into
 * [frequencyAddHz] ([FrequencyModulator]).
 *
 * Per block the owner calls [clear], every modulator adds its signal for the frames `[0, n)` and sets its flag, then the oscillators render with the
 * bus. A flag that is false means the array is all zero and nobody has to read it.
 */
internal class PitchBus(val capacity: Int) {
    /** Detune in cents (1200 = an octave up) per frame; valid for the first `n` frames of the block when [detuneActive]. */
    val detuneCents = DoubleArray(capacity)

    /** Hz added to the frequency of the oscillator per frame; valid for the first `n` frames of the block when [frequencyActive]. */
    val frequencyAddHz = DoubleArray(capacity)

    /** True once a [PitchModulator] has added into [detuneCents] in this block. */
    var detuneActive = false

    /** True once a [FrequencyModulator] has added into [frequencyAddHz] in this block. */
    var frequencyActive = false

    /** How many frames the previous block asked for: the most that can be dirty. */
    private var written = 0

    /**
     * Starts a block of [n] frames: zeroes what the modulators wrote and resets both flags. Blocks may differ in length; what the previous block wrote
     * beyond [n] is zeroed too, so an array that is not active is always entirely zero.
     */
    fun clear(n: Int) {
        require(n in 0..capacity) { "block of $n frames does not fit a bus of $capacity" }
        val dirty = if (written > n) written else n
        if (detuneActive) {
            Arrays.fill(detuneCents, 0, dirty, 0.0)
            detuneActive = false
        }
        if (frequencyActive) {
            Arrays.fill(frequencyAddHz, 0, dirty, 0.0)
            frequencyActive = false
        }
        written = n
    }
}

/** Adds a pitch offset in cents into [PitchBus.detuneCents] for frames `[0, n)` and sets [PitchBus.detuneActive] (vibrato, pitch envelope). */
internal interface PitchModulator {
    fun add(bus: PitchBus, n: Int)
}

/** Adds a frequency offset in Hz into [PitchBus.frequencyAddHz] for frames `[0, n)` and sets [PitchBus.frequencyActive] (FM). */
internal interface FrequencyModulator {
    fun add(bus: PitchBus, n: Int)
}
