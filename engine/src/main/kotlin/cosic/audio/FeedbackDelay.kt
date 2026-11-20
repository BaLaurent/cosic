package cosic.audio

private const val MAX_PARAM = 3.4e38



/**
 * superdough's `FeedbackDelayNode` (feedbackdelay.mjs) as Chromium runs it: a `DelayNode` of at most [maxSeconds] whose output feeds back into
 * its input through a gain (`feedback`) and is also the wet output (`delayGain`, 1). Two independent lines, left and right (a mono input is fed to both).
 *
 * What the browser does, which the Web Audio text does not say and `tools/strudel-ref/orbit-oracle.mjs` measures:
 *  - The line is read at `delayTime` frames behind the newest one, `delayTime` sampled per frame, with linear interpolation. Blink's read position is a float32
 *    (`write_index + buffer_length - delay_frames`, over a ring of `maxDelay * sampleRate + 128` frames), so the interpolation weight is that position's fraction
 *    in float32: rounded to 1/256 for the positions around 48000 and to 1/1024 around 8192 (it depends on where the write index is in the ring).
 *    A change of the delay time at an event is abrupt: no crossfade, no smoothing.
 *  - The feedback runs through a cycle of the audio graph, which costs one render quantum: the gain reads the output of the PREVIOUS quantum. So the way
 *    round is `delayTime + 128` frames and the echoes of an impulse come at `T`, `2T + 128`, `3T + 256` frames. (The Web Audio text only says the delay of a
 *    cycle is at least a quantum; the first echo, which is not in the loop, is on time.) The loop is one uniform lag of 128 frames, so it does not depend on
 *    how blocks are cut.
 *  - `delayTime` is clamped to `[0, maxSeconds]`; there is no minimum inside a block (a delay of 0 reads the frame just written).
 *
 * `delayTime` and `feedback` are [AParam]s so that events (`setValueAtTime` from the voices that share the orbit) and modulators can move them. Never
 * allocates after construction (two lines of `maxSeconds * sampleRate` frames).
 */
internal class FeedbackDelay(private val sampleRate: Int, maxSeconds: Double = 1.0) {
    /** The `delayTime` AudioParam, in seconds. */
    val delayTime = AParam(0.0, 0.0, maxSeconds, 4)

    /** The gain of the feedback path (`feedbackGain.gain`). */
    val feedback = AParam(0.0, -MAX_PARAM, MAX_PARAM, 4)

    private val length = Math.floor(maxSeconds * sampleRate).toInt() + QUANTUM
    private val lineL = FloatArray(length)
    private val lineR = FloatArray(length)
    private var writeIndex = 0

    // y(n - 128): the output of the previous render quantum, by frame number modulo 128
    private val loopL = FloatArray(QUANTUM)
    private val loopR = FloatArray(QUANTUM)
    private var loopIndex = 0

    /**
     * Makes the delay as `createFeedbackDelay(1, time, feedback)` does: the delay time is [timeSec], the feedback gain `min(|feedback|, 0.995)`, no events, an
     * empty line.
     */
    fun start(timeSec: Double, feedbackGain: Double) {
        delayTime.reset(timeSec)
        feedback.reset(Math.min(Math.abs(feedbackGain), 0.995))
        clear()
    }

    /** Empties the line and the loop; the parameters stay. */
    fun clear() {
        lineL.fill(0f)
        lineR.fill(0f)
        loopL.fill(0f)
        loopR.fill(0f)
        writeIndex = 0
        loopIndex = 0
    }

    /**
     * Delays the first `ctx.n` frames of [inL], [inR] (the same array for a mono input) into [outL], [outR], which must not be the input arrays. Allocation-free.
     */
    fun process(inL: FloatArray, inR: FloatArray, outL: FloatArray, outR: FloatArray, n: Int, ctx: BlockContext) {
        val timeMoves = !delayTime.isConstant
        val feedbackMoves = !feedback.isConstant
        var times: FloatArray? = null
        var gains: FloatArray? = null
        if (timeMoves) {
            times = ctx.borrow()
            delayTime.fill(times, ctx)
        }
        if (feedbackMoves) {
            gains = ctx.borrow()
            feedback.fill(gains, ctx)
        }
        val constantFrames = delayTime.constantValue.toFloat().toDouble() * sampleRate
        val constantGain = feedback.constantValue.toFloat()
        var w = writeIndex
        var slot = loopIndex
        for (i in 0 until n) {
            val g = if (gains == null) constantGain else gains[i]
            // the input of the delay: the signal plus the feedback of the previous quantum
            val vl = flushToZero(inL[i] + g * loopL[slot])
            val vr = flushToZero(inR[i] + g * loopR[slot])
            lineL[w] = vl
            lineR[w] = vr

            val frames = if (times == null) constantFrames else times[i].toDouble() * sampleRate
            // Blink: `read_position = write_index + buffer_length - delay_frames` in float32, wrapped, then linear interpolation between the frame it is in and the next
            var position = (w + length - frames).toFloat()
            if (position >= length) position -= length
            val r1 = position.toInt()
            val fraction = position - r1
            var r2 = r1 + 1
            if (r2 >= length) r2 -= length
            val yl = (1f - fraction) * lineL[r1] + fraction * lineL[r2]
            val yr = (1f - fraction) * lineR[r1] + fraction * lineR[r2]

            loopL[slot] = yl
            loopR[slot] = yr
            outL[i] = yl
            outR[i] = yr
            if (++w == length) w = 0
            slot = (slot + 1) and (QUANTUM - 1)
        }
        writeIndex = w
        loopIndex = slot
        if (feedbackMoves) ctx.release()
        if (timeMoves) ctx.release()
    }
}
