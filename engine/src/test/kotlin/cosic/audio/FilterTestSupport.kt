package cosic.audio

import cosic.player.paramSetOf

/**
 * Runs a [stage] over [input] (one array per channel) in blocks of [blockSize] frames, the first of which starts at engine frame [startFrame],
 * the way a voice feeds its chain; returns the output, one array per channel.
 */
internal fun runStage(stage: ChainStage, input: List<FloatArray>, blockSize: Int = 256, startFrame: Long = 0L, sampleRate: Int = SR): List<FloatArray> {
    require(blockSize in 1..BLOCK_FRAMES) { "a block holds at most $BLOCK_FRAMES frames" }
    val out = input.map { it.copyOf() }
    val block = AudioBlock(BLOCK_FRAMES)
    val ctx = BlockContext(sampleRate)
    val frames = input[0].size
    var done = 0
    while (done < frames) {
        val n = Math.min(blockSize, frames - done)
        block.clear(n)
        for (ch in input.indices) System.arraycopy(out[ch], done, if (ch == 0) block.l else block.r, 0, n)
        block.channels = input.size
        ctx.startFrame = startFrame + done
        ctx.n = n
        stage.process(block, ctx)
        for (ch in input.indices) System.arraycopy(if (ch == 0) block.l else block.r, 0, out[ch], done, n)
        done += n
    }
    return out
}

/** The controls of one chain as the audio engine reads them, from Strudel's names (`"cutoff" to 800.0`, `"ftype" to "ladder"`). */
internal fun controlsOf(vararg controls: Pair<String, Any?>): ParamSet = paramSetOf(mapOf(*controls))

/** The timing of a voice that starts at [t], whose gate lasts [gate] seconds (the release is irrelevant to the filters). */
internal fun timingOf(t: Double = 0.0, gate: Double = 1.0, cps: Double = 0.5, cycle: Double = 0.0): VoiceTiming =
    VoiceTiming().also { it.set(t, t + gate, t + gate + 0.01, cps, cycle) }

/** A white noise of [frames] frames from a fixed seed, in `[-amp, amp]`. */
internal fun noise(frames: Int, seed: Int = 1, amp: Float = 0.5f): FloatArray {
    var x = seed
    return FloatArray(frames) {
        x = x * 1664525 + 1013904223
        ((x ushr 8) / 8388608f - 1f) * amp
    }
}
