package cosic.audio

import cosic.audio.dsp.DynamicsCompressor
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The compressor, CHAIN Task D (helpers.mjs:163-176, Blink's DynamicsCompressor): the numbers of the CHAIN table, the controls and their ranges, the latency
 * and the channel behaviour. The browser-exact comparison (fresh node, settled node, mono, stereo, 44.1 kHz, extreme settings, followed by a panner)
 * is in [StageFxGoldenTest].
 */
class CompressorStageTest {
    private val hardKnee = arrayOf("compressor" to -20.0, "compressorRatio" to 10.0, "compressorKnee" to 0.0, "compressorAttack" to 0.001, "compressorRelease" to 0.05)

    @Test
    fun theStageIsInTheChainWhenCompressorIsSet() {
        assertFalse(CompressorStage(SR).isInChain())
        assertFalse(CompressorStage(SR).isInChain("compressorRatio" to 4.0, "compressorKnee" to 3.0, "compressorAttack" to 0.1, "compressorRelease" to 0.1))
        assertTrue(CompressorStage(SR).isInChain("compressor" to -20.0))
        assertTrue(CompressorStage(SR).isInChain("compressor" to 0.0))
    }

    @Test
    fun theControlsDefaultTheWayGetCompressorDoes() {
        val stage = CompressorStage(SR).startedWith("compressor" to -3.0)
        assertEquals(-3.0, stage.threshold.constantValue)
        assertEquals(10.0, stage.ratio.constantValue)
        assertEquals(10.0, stage.knee.constantValue)
        assertEquals(0.005, stage.attack.constantValue)
        assertEquals(0.05, stage.release.constantValue)
        val set = CompressorStage(SR).startedWith("compressor" to -30.0, "compressorRatio" to 4.0, "compressorKnee" to 6.0, "compressorAttack" to 0.02, "compressorRelease" to 0.3)
        assertEquals(listOf(-30.0, 4.0, 6.0, 0.02, 0.3), listOf(set.threshold, set.ratio, set.knee, set.attack, set.release).map { it.constantValue })
    }

    @Test
    fun theParametersAreClampedToTheRangesOfTheNode() {
        val stage = CompressorStage(SR).startedWith("compressor" to 12.0, "compressorRatio" to 100.0, "compressorKnee" to -5.0, "compressorAttack" to 5.0, "compressorRelease" to 9.0)
        assertEquals(0.0, stage.threshold.constantValue, "threshold -100..0 dB")
        assertEquals(20.0, stage.ratio.constantValue, "ratio 1..20")
        assertEquals(0.0, stage.knee.constantValue, "knee 0..40 dB")
        assertEquals(1.0, stage.attack.constantValue, "attack 0..1 s")
        assertEquals(1.0, stage.release.constantValue, "release 0..1 s")
        val low = CompressorStage(SR).startedWith("compressor" to -500.0, "compressorRatio" to 0.2, "compressorKnee" to 90.0)
        assertEquals(-100.0, low.threshold.constantValue)
        assertEquals(1.0, low.ratio.constantValue)
        assertEquals(40.0, low.knee.constantValue)
    }

    @Test
    fun theParametersAreWhereModulatorsLookThem() {
        val stage = CompressorStage(SR).startedWith("compressor" to -10.0)
        val registry = NodeRegistry().also { stage.registerParams(it) }
        assertSame(stage.threshold, registry.find("compressor", "threshold"))
        assertSame(stage.ratio, registry.find("compressor", "ratio"))
        assertSame(stage.knee, registry.find("compressor", "knee"))
        assertSame(stage.attack, registry.find("compressor", "attack"))
        assertSame(stage.release, registry.find("compressor", "release"))
    }

    @Test
    fun theLookAheadIsSixMillisecondsOfFrames() {
        assertEquals(288, DynamicsCompressor(48_000).latencyFrames)
        assertEquals(264, DynamicsCompressor(44_100).latencyFrames)
        assertEquals(576, DynamicsCompressor(96_000).latencyFrames)
        assertEquals(1023, DynamicsCompressor(192_000).latencyFrames, "the pre-delay line has 1024 frames")
    }

    // ------------------------------------------------------------------------------------------------ the steady state

    /** `Saturate(a) * (1 / Saturate(1)) ^ 0.6` for the hard knee at [thresholdDb] and a ratio of [ratio]: the output for a constant input [a] above the threshold. */
    private fun steadyState(a: Double, thresholdDb: Double, ratio: Double): Double {
        fun saturate(x: Double): Double {
            val threshold = 10.0.pow(thresholdDb / 20)
            if (x <= threshold) return x
            return 10.0.pow((thresholdDb + (20 * log10(x) - thresholdDb) / ratio) / 20)
        }
        return saturate(a) * (1 / saturate(1.0)).pow(0.6)
    }

    @Test
    fun aConstantInputAboveTheThresholdSettlesAtTheStaticCurve() {
        val one = CompressorStage(SR).startedWith(*hardKnee).pushMono(FloatArray(SR) { 1f })
        assertEquals(0.4366, one.last().toDouble(), 0.004366, "DC 1.0, -20 dB 10:1 hard knee: 0.4366 +-1%")
        assertEquals(steadyState(1.0, -20.0, 10.0), one.last().toDouble(), 1e-3)
        val half = CompressorStage(SR).startedWith(*hardKnee).pushMono(FloatArray(SR) { 0.5f })
        assertEquals(0.4074, half.last().toDouble(), 0.004074, "DC 0.5: 0.4074 +-1%")
        assertEquals(steadyState(0.5, -20.0, 10.0), half.last().toDouble(), 1e-3)
    }

    @Test
    fun aSignalBelowTheThresholdIsOnlyScaledByTheMakeupGain() {
        // thr 0 dB, ratio 20, knee 0: a signal of 0.1 is below it: no compression; the makeup (1 / Saturate(1)) ^ 0.6 is 1
        val x = sineWave(300.0, 0.3, 0.1)
        val y = CompressorStage(SR).startedWith("compressor" to 0.0, "compressorRatio" to 20.0, "compressorKnee" to 0.0).pushMono(x)
        val delay = 288
        for (i in 2000 until x.size - delay) assertEquals(x[i], y[i + delay], 1e-4f, "frame $i")
    }

    @Test
    fun theOutputIsDelayedByTheLookAhead() {
        val x = FloatArray(2000)
        x[1000] = 0.01f
        val y = CompressorStage(SR).startedWith("compressor" to 0.0, "compressorRatio" to 20.0, "compressorKnee" to 0.0).pushMono(x)
        for (i in 0 until 1288) assertEquals(0f, y[i], 0f, "frame $i")
        assertEquals(0.01f, y[1288], 1e-5f)
    }

    // ------------------------------------------------------------------------------------------------ channels

    @Test
    fun aMonoInputComesOutAsTwoEqualChannels() {
        val x = FloatArray(6000) { ((it * 7919) % 2001 - 1000) / 1000f }
        val out = CompressorStage(SR).startedWith(*hardKnee).pushThrough(x)
        assertEquals(2, out.size, "the node always outputs two channels")
        assertContentEquals(out[0], out[1])
        assertTrue(out[0].any { it != 0f })
    }

    @Test
    fun aStereoInputIsCompressedByItsLoudestChannelForBoth() {
        // the left channel is quiet and the right one loud: both are reduced by the same gain (the detector looks at max |x|)
        val left = FloatArray(12_000) { 0.05f * sin(it) }
        val right = FloatArray(12_000) { 0.9f * sin(it) }
        val out = CompressorStage(SR).startedWith(*hardKnee).pushThrough(left, right)
        val delay = 288
        for (i in 8000 until 11_000) {
            if (abs(left[i - delay]) < 0.02f) continue
            val gainLeft = out[0][i] / left[i - delay]
            val gainRight = out[1][i] / right[i - delay]
            assertEquals(gainRight, gainLeft, 1e-4f * abs(gainRight) + 1e-5f, "frame $i")
        }
        // and the quiet channel on its own would not be compressed at all (it is under the threshold)
        val alone = CompressorStage(SR).startedWith(*hardKnee).pushThrough(left, left)
        assertTrue(abs(alone[0][10_000]) > abs(out[0][10_000]) * 1.5f, "alone ${alone[0][10_000]} with the loud neighbour ${out[0][10_000]}")
    }

    private fun sin(i: Int): Float = kotlin.math.sin(i * 0.07).toFloat()

    @Test
    fun aPannerAfterTheCompressorDoesNotTakeThreeDecibelsOffAMonoVoice() {
        // mono + compressor + pan 0.5: the compressor made it stereo, so the panner uses the stereo-input law, which is the identity in the middle
        val source = dcSource(2.0, 0.5f)
        val withPan = renderStereo(vp(source, 1.5, "compressor" to -20.0, "pan" to 0.5), SR)
        val without = renderStereo(vp(source, 1.5, "compressor" to -20.0), SR)
        for (i in 20_000 until 40_000) {
            assertEquals(without[2 * i], withPan[2 * i], 1e-5f, "left $i")
            assertEquals(without[2 * i + 1], withPan[2 * i + 1], 1e-5f, "right $i")
            assertEquals(withPan[2 * i], withPan[2 * i + 1], 1e-6f, "L = R")
        }
        // for comparison, a mono voice without the compressor does lose them: its pan law is the mono one (cos 45 degrees)
        val plain = renderStereo(vp(source, 1.5, "pan" to 0.5), SR)
        assertEquals(0.8f * 0.5f * 0.70710678f, plain[2 * 30_000], 1e-4f)
    }

    // ------------------------------------------------------------------------------------------------ time

    @Test
    fun aFreshNodeDucksTheStartWhereASettledOneDoesNot() {
        val x = FloatArray(4000) { 1f }
        val fresh = CompressorStage(SR, warmStart = false).startedWith(*hardKnee).pushMono(x)
        val settled = CompressorStage(SR).startedWith(*hardKnee).pushMono(x)
        // the browser renders a fresh node's first sample at 0.3165 (see the golden), a settled one starts nearer the final level
        assertEquals(0.3165, fresh[288].toDouble(), 1e-3)
        assertTrue(settled[288] > fresh[288] + 0.05f || settled[288] < fresh[288] - 0.05f, "settled ${settled[288]} fresh ${fresh[288]}")
        assertEquals(fresh.last(), settled.last(), 1e-3f, "both end up at the same level")
    }

    @Test
    fun theOutputDoesNotDependOnTheBlockSizeEvenWithMovingParameters() {
        val x = FloatArray(9000) { (kotlin.math.sin(it * 0.011) * (0.2 + 0.7 * kotlin.math.sin(it * 0.0007).let { s -> s * s })).toFloat() }
        fun run(split: IntArray): FloatArray {
            val stage = CompressorStage(SR).startedWith("compressor" to -10.0, "compressorRatio" to 6.0, "compressorKnee" to 8.0)
            stage.threshold.linearRampToValueAtTime(-40.0, 0.15, 0.0)
            stage.ratio.linearRampToValueAtTime(18.0, 0.15, 0.0)
            return stage.pushMono(x, split)
        }
        val whole = run(intArrayOf(256))
        for (split in listOf(intArrayOf(128), intArrayOf(97), intArrayOf(1, 31, 200, 255), intArrayOf(33))) assertContentEquals(whole, run(split), "blocks ${split.toList()}")
        // the parameter does move: a fixed threshold gives a different result
        val fixed = CompressorStage(SR).startedWith("compressor" to -10.0, "compressorRatio" to 6.0, "compressorKnee" to 8.0).pushMono(x)
        assertTrue((0 until x.size).any { abs(fixed[it] - whole[it]) > 1e-3f })
    }

    @Test
    fun hostileValuesAndInputStayFinite() {
        val noise = FloatArray(5000) { ((it * 7919) % 2001 - 1000) / 1000f }
        for (controls in listOf(
            arrayOf("compressor" to 1e300, "compressorRatio" to 1e300, "compressorKnee" to 1e300, "compressorAttack" to 1e300, "compressorRelease" to 1e300),
            arrayOf("compressor" to -1e300, "compressorRatio" to -1e300, "compressorKnee" to -1e300, "compressorAttack" to -1e300, "compressorRelease" to -1e300),
            arrayOf("compressor" to Double.POSITIVE_INFINITY, "compressorRatio" to Double.NEGATIVE_INFINITY),
            arrayOf("compressor" to -100.0, "compressorRatio" to 20.0, "compressorKnee" to 40.0, "compressorAttack" to 0.0, "compressorRelease" to 0.0),
        )) {
            val y = CompressorStage(SR).startedWith(*controls).pushThrough(noise, noise.reversedArray())
            assertTrue(y.all { allFinite(it) }, "controls ${controls.toList()}")
        }
        val bad = FloatArray(4000) { if (it == 100) Float.NaN else if (it == 200) Float.NEGATIVE_INFINITY else 0.4f }
        assertTrue(CompressorStage(SR).startedWith(*hardKnee).pushThrough(bad).all { allFinite(it) })
    }
}
