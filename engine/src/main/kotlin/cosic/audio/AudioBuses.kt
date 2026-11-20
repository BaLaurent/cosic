package cosic.audio

/**
 * The buses voices can send to (`bus`, `busgain`) and the analysers they can feed (`analyze`, `fft`): `s("bus")` reads the former, the
 * visuals the latter.
 *
 * Stub of Task 3.1: nothing is routed. Task 3.9 (orbit buses) implements it.
 */
@Suppress("UNUSED_PARAMETER")
internal class AudioBuses(sampleRate: Int) {
    /** Adds the block, scaled by [gain], to bus [bus] (`post -> GainNode(busgain) -> buses[bus]`). */
    fun send(bus: Int, b: AudioBlock, gain: AParam, ctx: BlockContext) {}

    /** Feeds the block to analyser [id] of size `2^(fft + 5)`, [fftSize]. */
    fun analyse(id: Int, fftSize: Int, b: AudioBlock, ctx: BlockContext) {}

    /** Empties the buses. */
    fun clear() {}
}
