package cosic.audio

/**
 * The shapes of Web Audio's `BiquadFilterNode` that superdough uses: the low-pass, high-pass and band-pass filters of its filter stages and
 * the notch of the phaser.
 *
 * Stub of Task 3.1: Task 3.7 (filters) adds the `Biquad` class itself to this file.
 */
internal enum class BiquadType { LOWPASS, HIGHPASS, BANDPASS, NOTCH }
