package cosic.audio

import cosic.player.paramSetOf
import kotlin.test.assertTrue

/** The timing of a voice that starts at 0 and has a long gate: what the stages that read the clock need. */
internal fun stageTiming(t: Double = 0.0, endWithRelease: Double = 100.0, cps: Double = 0.5, cycle: Double = 0.0) = VoiceTiming(t, endWithRelease - 0.01, endWithRelease, cps, cycle)

/** [this] after a voice start with the given controls (reset, then configure), failing when the stage does not take part in the chain. */
internal fun <S : ChainStage> S.startedWith(vararg controls: Pair<String, Any?>, timing: VoiceTiming = stageTiming(), isMain: Boolean = true): S {
    reset()
    assertTrue(configure(paramSetOf(mapOf(*controls)), timing, isMain), "the stage is not part of the chain for ${controls.toList()}")
    return this
}

/** Whether the stage takes part in the chain for the given controls (reset first, as at a voice start). */
internal fun ChainStage.isInChain(vararg controls: Pair<String, Any?>, timing: VoiceTiming = stageTiming()): Boolean {
    reset()
    return configure(paramSetOf(mapOf(*controls)), timing, true)
}

/**
 * Pushes [left] (and [right], for a stereo signal) through the stage in blocks of the given sizes (taken in turn), the first of which starts at engine
 * frame [startFrame]. Returns the channels of the output: two when the signal was or became stereo, else one.
 */
internal fun ChainStage.pushThrough(left: FloatArray, right: FloatArray? = null, blockSizes: IntArray = intArrayOf(BLOCK_FRAMES), startFrame: Long = 0L, sampleRate: Int = SR): Array<FloatArray> {
    val frames = left.size
    val block = AudioBlock(BLOCK_FRAMES)
    val ctx = BlockContext(sampleRate)
    val outL = FloatArray(frames)
    val outR = FloatArray(frames)
    var channels = if (right != null) 2 else 1
    var frame = 0
    var turn = 0
    while (frame < frames) {
        val n = minOf(blockSizes[turn++ % blockSizes.size], frames - frame)
        block.channels = if (right != null) 2 else 1
        System.arraycopy(left, frame, block.l, 0, n)
        if (right != null) System.arraycopy(right, frame, block.r, 0, n)
        ctx.startFrame = startFrame + frame
        ctx.n = n
        process(block, ctx)
        System.arraycopy(block.l, 0, outL, frame, n)
        if (block.channels == 2) System.arraycopy(block.r, 0, outR, frame, n)
        channels = block.channels
        frame += n
    }
    return if (channels == 2) arrayOf(outL, outR) else arrayOf(outL)
}

/** The mono signal [x] through the stage; the left channel of the result. */
internal fun ChainStage.pushMono(x: FloatArray, blockSizes: IntArray = intArrayOf(BLOCK_FRAMES), startFrame: Long = 0L): FloatArray = pushThrough(x, null, blockSizes, startFrame)[0]
