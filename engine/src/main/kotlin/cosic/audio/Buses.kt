package cosic.audio

private const val DEFAULT_DELAY_SECONDS = 0.25
private const val MIN_DELAY_SECONDS = 0.001
private const val MAX_DELAY_SECONDS = 4.0
private const val DEFAULT_DELAY_FEEDBACK = 0.5
private const val MAX_DELAY_FEEDBACK = 0.95

/** A change of delay time is crossfaded over this long, so moving it never clicks or glides in pitch. */
private const val DELAY_CROSSFADE_SECONDS = 0.02

// Freeverb: eight damped feedback combs in parallel, then four allpasses in series, per channel. Tunings are in
// samples at 44.1 kHz and scaled to the engine rate.
private val COMB_TUNINGS = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
private val ALLPASS_TUNINGS = intArrayOf(556, 441, 341, 225)
private const val STEREO_SPREAD = 23
private const val REVERB_INPUT_GAIN = 0.015f

/** Chosen so a send of 1 (`room(1)`) returns a wet signal at roughly the level of the dry one. */
private const val REVERB_RETURN_GAIN = 2f
private const val REVERB_DAMPING = 0.5f * 0.4f
private const val ALLPASS_FEEDBACK = 0.5f
private const val DEFAULT_ROOM_SIZE = 0.5

/** A stereo block of audio; [Voice]s add into these and the buses read them. */
internal class StereoBuffer(frames: Int) {
    val left = FloatArray(frames)
    val right = FloatArray(frames)

    fun clear(n: Int) {
        left.fill(0f, 0, n)
        right.fill(0f, 0, n)
    }

    /** Adds `mono[0 until n]` panned with the given per-channel gains. */
    fun addMono(mono: FloatArray, n: Int, leftGain: Float, rightGain: Float) {
        for (i in 0 until n) {
            left[i] += mono[i] * leftGain
            right[i] += mono[i] * rightGain
        }
    }
}

/**
 * Keeps feedback loops healthy: denormals and anything that is not a sane finite value become exactly zero, so one
 * bad sample can neither slow the loop down nor circulate forever.
 */
internal fun flushToZero(x: Float): Float =
    if ((x > 1e-20f && x < 1e6f) || (x < -1e-20f && x > -1e6f)) x else 0f

/**
 * Stereo feedback delay shared by all voices, up to 4 s. Each channel feeds back on itself. The delay time is
 * linearly interpolated and changes are crossfaded between two read taps.
 */
internal class DelayBus(private val sampleRate: Int) {
    private val size = (MAX_DELAY_SECONDS * sampleRate).toInt() + 2
    private val bufferLeft = FloatArray(size)
    private val bufferRight = FloatArray(size)
    private var writeIndex = 0
    private var feedback = DEFAULT_DELAY_FEEDBACK.toFloat()

    private var currentFrames = DEFAULT_DELAY_SECONDS * sampleRate
    private var incomingFrames = currentFrames
    private var targetFrames = currentFrames
    private var crossfading = false
    private var crossfade = 0.0
    private val crossfadeStep = 1.0 / (DELAY_CROSSFADE_SECONDS * sampleRate)

    /** Seconds, clamped to 0.001..4. */
    fun setTime(seconds: Double) {
        targetFrames = seconds.coerceIn(MIN_DELAY_SECONDS, MAX_DELAY_SECONDS) * sampleRate
    }

    /** Clamped to 0..0.95. */
    fun setFeedback(amount: Double) {
        feedback = amount.coerceIn(0.0, MAX_DELAY_FEEDBACK).toFloat()
    }

    /** Feeds `send[0 until n]` in and adds the echoes to `out`. */
    fun process(send: StereoBuffer, n: Int, out: StereoBuffer) {
        for (i in 0 until n) {
            if (!crossfading && targetFrames != currentFrames) {
                incomingFrames = targetFrames
                crossfade = 0.0
                crossfading = true
            }
            val wetLeft: Float
            val wetRight: Float
            if (crossfading) {
                val mix = crossfade.toFloat()
                wetLeft = read(bufferLeft, currentFrames) * (1f - mix) + read(bufferLeft, incomingFrames) * mix
                wetRight = read(bufferRight, currentFrames) * (1f - mix) + read(bufferRight, incomingFrames) * mix
                crossfade += crossfadeStep
                if (crossfade >= 1.0) {
                    currentFrames = incomingFrames
                    crossfading = false
                }
            } else {
                wetLeft = read(bufferLeft, currentFrames)
                wetRight = read(bufferRight, currentFrames)
            }
            bufferLeft[writeIndex] = flushToZero(send.left[i] + wetLeft * feedback)
            bufferRight[writeIndex] = flushToZero(send.right[i] + wetRight * feedback)
            out.left[i] += wetLeft
            out.right[i] += wetRight
            if (++writeIndex == size) writeIndex = 0
        }
    }

    fun clear() {
        bufferLeft.fill(0f)
        bufferRight.fill(0f)
    }

    private fun read(buffer: FloatArray, delayFrames: Double): Float {
        var position = writeIndex - delayFrames
        if (position < 0.0) position += size
        val index = position.toInt()
        val next = if (index + 1 == size) 0 else index + 1
        return buffer[index] + (buffer[next] - buffer[index]) * (position - index).toFloat()
    }
}

/** Freeverb-style stereo reverb shared by all voices (algorithm by Jezar at Dreampoint, public domain). */
internal class Reverb(sampleRate: Int) {
    private class Comb(size: Int) {
        private val buffer = FloatArray(size)
        private var index = 0
        private var store = 0f
        var feedback = 0f

        fun process(input: Float): Float {
            val output = buffer[index]
            store = flushToZero(output * (1f - REVERB_DAMPING) + store * REVERB_DAMPING)
            buffer[index] = input + store * feedback
            if (++index == buffer.size) index = 0
            return output
        }

        fun clear() {
            buffer.fill(0f)
            store = 0f
        }
    }

    private class Allpass(size: Int) {
        private val buffer = FloatArray(size)
        private var index = 0

        fun process(input: Float): Float {
            val buffered = buffer[index]
            buffer[index] = input + buffered * ALLPASS_FEEDBACK
            if (++index == buffer.size) index = 0
            return buffered - input
        }

        fun clear() = buffer.fill(0f)
    }

    private fun scaled(tuning: Int, sampleRate: Int) = maxOf(1, Math.round(tuning * sampleRate / 44_100.0).toInt())

    private val combsLeft = Array(COMB_TUNINGS.size) { Comb(scaled(COMB_TUNINGS[it], sampleRate)) }
    private val combsRight = Array(COMB_TUNINGS.size) { Comb(scaled(COMB_TUNINGS[it] + STEREO_SPREAD, sampleRate)) }
    private val allpassesLeft = Array(ALLPASS_TUNINGS.size) { Allpass(scaled(ALLPASS_TUNINGS[it], sampleRate)) }
    private val allpassesRight = Array(ALLPASS_TUNINGS.size) { Allpass(scaled(ALLPASS_TUNINGS[it] + STEREO_SPREAD, sampleRate)) }

    init {
        setSize(DEFAULT_ROOM_SIZE)
    }

    /** Room size 0..1; larger rooms ring longer. */
    fun setSize(size: Double) {
        val feedback = (0.7 + 0.28 * size.coerceIn(0.0, 1.0)).toFloat()
        for (comb in combsLeft) comb.feedback = feedback
        for (comb in combsRight) comb.feedback = feedback
    }

    /** Feeds `send[0 until n]` in and adds the reverb to `out`. */
    fun process(send: StereoBuffer, n: Int, out: StereoBuffer) {
        for (i in 0 until n) {
            val input = (send.left[i] + send.right[i]) * REVERB_INPUT_GAIN
            var left = 0f
            var right = 0f
            for (comb in combsLeft) left += comb.process(input)
            for (comb in combsRight) right += comb.process(input)
            for (allpass in allpassesLeft) left = allpass.process(left)
            for (allpass in allpassesRight) right = allpass.process(right)
            out.left[i] += left * REVERB_RETURN_GAIN
            out.right[i] += right * REVERB_RETURN_GAIN
        }
    }

    fun clear() {
        for (comb in combsLeft) comb.clear()
        for (comb in combsRight) comb.clear()
        for (allpass in allpassesLeft) allpass.clear()
        for (allpass in allpassesRight) allpass.clear()
    }
}
