package cosic.audio.dsp

/**
 * Chromium's `DynamicsCompressorNode` DSP (Blink `platform/audio/dynamics_compressor.cc`), the compressor of `.compressor(threshold:ratio:knee:attack:release)`
 * (superdough `getCompressor`, helpers.mjs:163-176). Ported with the same single-precision arithmetic, operation by operation: the Kotlin `Float`
 * is the C++ `float`, `fdlibm::powf`/`asinf`/`exp`/`sin` are the `Math` functions (rounded to float), so the output agrees with the browser's to the
 * last bits of a float32 (see `CompressorStageTest`, which compares against a real `OfflineAudioContext`).
 *
 * How it works, per sample: the input of both channels is written into a pre-delay line of 6 ms (so the output lags the input by `floor(0.006 * sr)`
 * frames), and the *undelayed* peak `max |x|` over the channels drives a detector: the gain reduction `Saturate(|x|) / |x|` of a static curve (linear up
 * to the threshold, an exponential knee, then a constant ratio), smoothed by a fast-attack/slow-release average. The detector's value is turned into a desired
 * gain (`asin(avg) / (pi/2)`), the compressor gain slews toward it (attack: a rate from the largest reduction seen in this attack; release: an adaptive
 * polynomial of the reduction in dB), and the output is `delayed * postGain * sin(pi/2 * gain)`, where `postGain = (1 / Saturate(1)) ^ 0.6` is a
 * makeup gain. The slew rate is recomputed every 32 frames ("divisions"); the parameters are read once per 128-frame render quantum.
 *
 * The node always has two output channels: a mono input is duplicated, so the output is stereo with L = R ([process] with `stereo = false`).
 *
 * Time is the absolute frame counter: the divisions are the multiples of 32 frames of it, so the output does not depend on how a voice's blocks were
 * cut, as long as [setParameters] is called with the values at the start of the quantum before the first piece of every quantum.
 *
 * The metering output (`reduction`, which superdough never reads) is not computed. A NaN or infinite input sample is taken as 0 (the node would carry it
 * through its pre-delay line). Memory: two pre-delay lines of `nextPowerOfTwo(0.006 sr + 1)` floats.
 */
internal class DynamicsCompressor(sampleRate: Int) {
    private val sr = sampleRate.toFloat()
    private val preDelayFrames: Int = Math.min((K_PRE_DELAY * sr).toInt(), MAX_PRE_DELAY_FRAMES - 1)
    private val ringSize: Int = Integer.highestOneBit(preDelayFrames) shl 1
    private val mask = ringSize - 1
    private val ring0 = FloatArray(ringSize)
    private val ring1 = FloatArray(ringSize)

    private var readIndex = 0
    private var writeIndex = 0

    // The state of the detector and the gain.
    private var detectorAverage = 0f
    private var compressorGain = 1f
    private var dbMaxAttackCompressionDiff = -1f

    // The static curve (recomputed when threshold, knee or ratio change).
    private var ratio = UNINITIALIZED
    private var slope = UNINITIALIZED
    private var linearThreshold = UNINITIALIZED
    private var dbThreshold = UNINITIALIZED
    private var dbKnee = UNINITIALIZED
    private var kneeThreshold = UNINITIALIZED
    private var dbKneeThreshold = UNINITIALIZED
    private var dbYkneeThreshold = UNINITIALIZED
    private var kneeK = UNINITIALIZED

    // Constants of the quantum, from the parameters.
    private var k = 0f
    private var linearPostGain = 1f
    private var attackFrames = 0f
    private var satReleaseFrames = 0f
    private var satReleaseRateFloor = 0f
    private var a = 0f
    private var b = 0f
    private var c = 0f
    private var d = 0f
    private var e = 0f

    // The division in progress: the rate of this stretch of up to 32 frames and the gain it slews toward.
    private var divisionLeft = 0
    private var scaledDesiredGain = 0f
    private var envelopeRate = 0f

    // sin(pi/2 * gain) of the last gain that was warped.
    private var warpedGain = Float.NaN
    private var warp = 0f

    /**
     * Where the detector of a node that has been fed silence for a long time rests: it creeps toward 1 by `(1 - avg) * rate` per frame and stops when
     * that step is below half a float ulp, a little short of 1 (0.99998 at 48 kHz), which a node rests at for as long as it hears nothing.
     */
    private val settledDetector: Float

    init {
        val rate = decibelsToLinear(2f / (K_SAT_RELEASE_TIME * sr)) - 1f
        var average = 0f
        while (true) {
            var next = average + (1f - average) * rate
            next = if (next < 1f) next else 1f
            if (next == average) break
            average = next
        }
        settledDetector = average
        reset(false)
    }

    /** The frames between an input sample and its output: the 6 ms pre-delay (288 at 48 kHz, 264 at 44.1 kHz). */
    val latencyFrames: Int get() = preDelayFrames

    /**
     * Back to the state of a node that has just been created ([warm] false: nothing heard yet: the detector is 0, so that the first frames of a loud
     * signal are reduced too far while it catches up), or to the state of a node that has been fed silence for a while ([warm] true: the detector at its
     * resting value and the gain where it slews to, which is what a node recycled from superdough's pool is in between two notes; the output of both is
     * the same as the browser's after a second of silence, to the last float).
     */
    fun reset(warm: Boolean) {
        detectorAverage = if (warm) settledDetector else 0f
        compressorGain = if (warm) Math.asin(settledDetector.toDouble()).toFloat() / PI_OVER_TWO else 1f
        dbMaxAttackCompressionDiff = -1f
        java.util.Arrays.fill(ring0, 0f)
        java.util.Arrays.fill(ring1, 0f)
        readIndex = 0
        writeIndex = preDelayFrames
        divisionLeft = 0
        ratio = UNINITIALIZED
        dbThreshold = UNINITIALIZED
        dbKnee = UNINITIALIZED
        warpedGain = Float.NaN
    }

    /**
     * Sets the five AudioParams as the node reads them at the start of a render quantum (`FinalValue()`, already clamped to their ranges: threshold
     * -100..0 dB, knee 0..40 dB, ratio 1..20, attack 0..1 s, release 0..1 s). Call it before the first piece of every quantum.
     */
    fun setParameters(threshold: Double, knee: Double, ratio: Double, attack: Double, release: Double) {
        k = updateStaticCurveParameters(threshold.toFloat(), knee.toFloat(), ratio.toFloat())
        // Makeup gain with empirical/perceptual tuning.
        linearPostGain = Math.pow((1f / saturate(1f, k)).toDouble(), 0.6f.toDouble()).toFloat()
        attackFrames = stdMax(0.001f, attack.toFloat()) * sr
        val releaseFrames = sr * release.toFloat()
        satReleaseFrames = K_SAT_RELEASE_TIME * sr
        // A 4th order polynomial through four points that gives the release time in frames from the amount of compression.
        a = releaseFrames * K_A_BASE
        b = releaseFrames * K_B_BASE
        c = releaseFrames * K_C_BASE
        d = releaseFrames * K_D_BASE
        e = releaseFrames * K_E_BASE
        satReleaseRateFloor = decibelsToLinear(2f / satReleaseFrames) - 1f
    }

    /**
     * Compresses frames `[from, to)` in place. [left] and [right] hold the input; the output is written to both, delayed by [latencyFrames]. With [stereo]
     * false only [left] is read (the mono input is duplicated, as the node does) and [right] is written. [startFrame] is the absolute frame of index 0 of
     * the arrays; the pieces of one voice must follow each other without gaps.
     */
    fun process(left: FloatArray, right: FloatArray, from: Int, to: Int, startFrame: Long, stereo: Boolean) {
        var i = from
        while (i < to) {
            if (divisionLeft == 0) beginDivision(startFrame + i)
            val count = Math.min(divisionLeft, to - i)
            runDivision(left, right, i, i + count, stereo)
            divisionLeft -= count
            i += count
        }
    }

    /** The work at the start of a division: the desired gain and the slew rate, from the detector and the gain as they are now. */
    private fun beginDivision(absoluteFrame: Long) {
        detectorAverage = ensureFinite(detectorAverage, 1f)
        val desiredGain = detectorAverage
        // Pre-warp so we get desiredGain after sin() warp.
        scaledDesiredGain = Math.asin(desiredGain.toDouble()).toFloat() / PI_OVER_TWO
        val isReleasing = scaledDesiredGain > compressorGain
        var dbCompressionDiff: Float = if (scaledDesiredGain == 0f) {
            if (isReleasing) -1f else 1f
        } else {
            linearToDecibels(compressorGain / scaledDesiredGain)
        }
        if (isReleasing) {
            // Release mode: dbCompressionDiff is negative dB, and a higher compression (lower value) releases faster.
            dbMaxAttackCompressionDiff = -1f
            dbCompressionDiff = ensureFinite(dbCompressionDiff, -1f)
            var x = clampTo(dbCompressionDiff, -12f, 0f)
            x = 0.25f * (x + 12)
            val x2 = x * x
            val x3 = x2 * x
            val x4 = x2 * x2
            val calcReleaseFrames = a + b * x + c * x2 + d * x3 + e * x4
            val dbPerFrame = 5f / calcReleaseFrames
            envelopeRate = decibelsToLinear(dbPerFrame)
        } else {
            // Attack mode: dbCompressionDiff is positive dB; the rate follows the largest reduction seen so far in this attack.
            dbCompressionDiff = ensureFinite(dbCompressionDiff, 1f)
            if (dbMaxAttackCompressionDiff == -1f || dbMaxAttackCompressionDiff < dbCompressionDiff) dbMaxAttackCompressionDiff = dbCompressionDiff
            val dbEffAttenDiff = stdMax(0.5f, dbMaxAttackCompressionDiff)
            val x = 0.25f / dbEffAttenDiff
            envelopeRate = 1f - Math.pow(x.toDouble(), (1f / attackFrames).toDouble()).toFloat()
        }
        divisionLeft = DIVISION_FRAMES - (absoluteFrame and (DIVISION_FRAMES - 1).toLong()).toInt()
    }

    /** The inner loop of one division (or a part of it): shaped power average, gain slew and output, sample by sample. */
    private fun runDivision(left: FloatArray, right: FloatArray, from: Int, to: Int, stereo: Boolean) {
        var readIdx = readIndex
        var writeIdx = writeIndex
        var detector = detectorAverage
        var gain = compressorGain
        val rate0 = envelopeRate
        val desired = scaledDesiredGain
        val kk = k
        val postGain = linearPostGain
        val line0 = ring0
        val line1 = ring1
        for (n in from until to) {
            var inL = left[n]
            if (inL - inL != 0f) inL = 0f // a non-finite sample would come out of the pre-delay line as it is
            line0[writeIdx] = inL
            var compressorInput = 0f
            val absL = if (inL > 0f) inL else -inL
            if (compressorInput < absL) compressorInput = absL
            if (stereo) {
                var inR = right[n]
                if (inR - inR != 0f) inR = 0f
                line1[writeIdx] = inR
                val absR = if (inR > 0f) inR else -inR
                if (compressorInput < absR) compressorInput = absR
            }

            // The shaped power of the undelayed input, through the static curve.
            val absInput = if (compressorInput > 0f) compressorInput else -compressorInput
            val attenuation = if (absInput <= 0.0001f) 1f else saturate(absInput, kk) / absInput

            // A reduction of less than 2 dB is released at the rate of 2 dB (`max(2, -dB)`), so most frames need no logarithm.
            if (attenuation > detector) {
                val rate = if (attenuation >= 0.8f) {
                    satReleaseRateFloor
                } else {
                    val dbAttenuation = stdMax(2f, -linearToDecibels(attenuation))
                    decibelsToLinear(dbAttenuation / satReleaseFrames) - 1f
                }
                detector += (attenuation - detector) * rate
            } else {
                detector += (attenuation - detector) * 1f
            }
            detector = if (detector < 1f) detector else 1f
            detector = ensureFinite(detector, 1f)

            // Exponential approach to the desired gain.
            if (rate0 < 1f) {
                // Attack: reduce the gain to the desired one.
                gain += (desired - gain) * rate0
            } else {
                // Release: exponentially increase the gain to 1.
                gain *= rate0
                gain = if (gain < 1f) gain else 1f
            }

            // Warp the gain to smooth out sharp exponential transition points.
            if (gain != warpedGain) {
                warp = Math.sin((PI_OVER_TWO * gain).toDouble()).toFloat()
                warpedGain = gain
            }
            val totalGain = postGain * warp

            val delayedL = line0[readIdx] * totalGain
            val delayedR = if (stereo) line1[readIdx] * totalGain else delayedL
            left[n] = delayedL
            right[n] = delayedR
            readIdx = (readIdx + 1) and mask
            writeIdx = (writeIdx + 1) and mask
        }
        readIndex = readIdx
        writeIndex = writeIdx
        detectorAverage = flushDenormal(detector)
        compressorGain = flushDenormal(gain)
    }

    /** Exponential curve for the knee: linear up to the threshold, first-derivative matched there, approaching `threshold + 1 / k`. */
    private fun kneeCurve(x: Float, k: Float): Float {
        if (x < linearThreshold) return x
        return linearThreshold + (1f - Math.exp((-k * (x - linearThreshold)).toDouble()).toFloat()) / k
    }

    /** The full compression curve: the knee, then a constant ratio. */
    private fun saturate(x: Float, k: Float): Float {
        if (x < kneeThreshold) return kneeCurve(x, k)
        val dbX = linearToDecibels(x)
        val dbY = dbYkneeThreshold + slope * (dbX - dbKneeThreshold)
        return decibelsToLinear(dbY)
    }

    /** The `k` of the knee curve whose slope at the end of the knee is [desiredSlope], found by 15 steps of a geometric bisection. */
    private fun kAtSlope(desiredSlope: Float): Float {
        val dbX = dbThreshold + dbKnee
        val x = decibelsToLinear(dbX)
        var x2 = 1f
        var dbX2 = 0f
        if (!(x < linearThreshold)) {
            x2 = (x.toDouble() * 1.001).toFloat()
            dbX2 = linearToDecibels(x2)
        }
        var minK = 0.1f
        var maxK = 10000f
        var candidate = 5f
        var curveSlope = 1f
        for (i in 0 until 15) {
            // The slope of the curve in dB at the end of the knee is the inverse of the compression ratio.
            if (!(x < linearThreshold)) {
                val dbY = linearToDecibels(kneeCurve(x, candidate))
                val dbY2 = linearToDecibels(kneeCurve(x2, candidate))
                curveSlope = (dbY2 - dbY) / (dbX2 - dbX)
            }
            if (curveSlope < desiredSlope) maxK = candidate else minK = candidate // candidate is too high / too low
            candidate = Math.sqrt((minK * maxK).toDouble()).toFloat()
        }
        return candidate
    }

    private fun updateStaticCurveParameters(dbThresholdNew: Float, dbKneeNew: Float, ratioNew: Float): Float {
        if (dbThresholdNew != dbThreshold || dbKneeNew != dbKnee || ratioNew != ratio) {
            dbThreshold = dbThresholdNew
            linearThreshold = decibelsToLinear(dbThresholdNew)
            dbKnee = dbKneeNew
            ratio = ratioNew
            slope = 1f / ratio
            val kNew = kAtSlope(1f / ratio)
            dbKneeThreshold = dbThresholdNew + dbKneeNew
            kneeThreshold = decibelsToLinear(dbKneeThreshold)
            dbYkneeThreshold = linearToDecibels(kneeCurve(kneeThreshold, kNew))
            kneeK = kNew
        }
        return kneeK
    }

    private companion object {
        const val UNINITIALIZED = -1f
        const val K_PRE_DELAY = 0.006f
        const val K_SAT_RELEASE_TIME = 0.0025f
        const val MAX_PRE_DELAY_FRAMES = 1024
        const val DIVISION_FRAMES = 32
        val PI_OVER_TWO: Float = (Math.PI / 2).toFloat()

        // The release polynomial through four zones (0.09, 0.16, 0.42, 0.98): the coefficients are those of Blink's constexpr floats.
        const val Z1 = 0.09f
        const val Z2 = 0.16f
        const val Z3 = 0.42f
        const val Z4 = 0.98f
        val K_A_BASE: Float = 0.9999999999999998f * Z1 + 1.8432219684323923e-16f * Z2 - 1.9373394351676423e-16f * Z3 + 8.824516011816245e-18f * Z4
        val K_B_BASE: Float = -1.5788320352845888f * Z1 + 2.3305837032074286f * Z2 - 0.9141194204840429f * Z3 + 0.1623677525612032f * Z4
        val K_C_BASE: Float = 0.5334142869106424f * Z1 - 1.272736789213631f * Z2 + 0.9258856042207512f * Z3 - 0.18656310191776226f * Z4
        val K_D_BASE: Float = 0.08783463138207234f * Z1 - 0.1694162967925622f * Z2 + 0.08588057951595272f * Z3 - 0.00429891410546283f * Z4
        val K_E_BASE: Float = -0.042416883008123074f * Z1 + 0.1115693827987602f * Z2 - 0.09764676325265872f * Z3 + 0.028494263462021576f * Z4

        /** `powf(10, 0.05f * decibels)`. */
        fun decibelsToLinear(decibels: Float): Float = Math.pow(10.0, (0.05f * decibels).toDouble()).toFloat()

        /** `20 * log10f(linear)`; 0 is negative infinity (the callers guard it with [ensureFinite]). */
        fun linearToDecibels(linear: Float): Float = 20f * Math.log10(linear.toDouble()).toFloat()

        /** `x` when it is a finite number, else [default]. */
        fun ensureFinite(x: Float, default: Float): Float = if (x - x == 0f) x else default

        /** `std::max(a, b)`: [b] only when it is greater (a NaN [b] gives [a]). */
        fun stdMax(a: Float, b: Float): Float = if (a < b) b else a

        /** Blink's `ClampTo(value, min, max)`. */
        fun clampTo(value: Float, min: Float, max: Float): Float = if (value >= max) max else if (value <= min) min else value

        /** `DenormalDisabler::FlushDenormalFloatToZero`. */
        fun flushDenormal(x: Float): Float = if ((if (x >= 0f) x else -x) < java.lang.Float.MIN_NORMAL) 0f else x
    }
}
