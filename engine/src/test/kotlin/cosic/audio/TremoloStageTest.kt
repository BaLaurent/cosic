package cosic.audio

import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Tremolo, CHAIN Task D (superdough.mjs:818-849): the stage against the numbers of the CHAIN table and the rules of the code. The browser-exact comparison of the
 * whole stage (shapes, skew, phase, sync, depth above 1, the LFO ending, stereo) is in [StageFxGoldenTest].
 */
class TremoloStageTest {
    private val dc = FloatArray(14_000) { 1f }

    /** The gain of a tremolo on a signal of ones, which is the gain itself. */
    private fun gain(vararg controls: Pair<String, Any?>, timing: VoiceTiming = stageTiming()): FloatArray =
        TremoloStage(SR).startedWith(*controls, timing = timing).pushMono(dc)

    @Test
    fun theStageIsInTheChainWhenTremoloOrTremolosyncIsSet() {
        assertFalse(TremoloStage(SR).isInChain())
        assertFalse(TremoloStage(SR).isInChain("tremolodepth" to 0.5, "tremoloshape" to "sine", "tremoloskew" to 0.3))
        assertTrue(TremoloStage(SR).isInChain("tremolo" to 4.0))
        assertTrue(TremoloStage(SR).isInChain("tremolosync" to 2.0))
        assertTrue(TremoloStage(SR).isInChain("tremolo" to 0.0), "a rate of 0 is a rate")
    }

    @Test
    fun theDefaultTremoloIsARisingRampWithGainPhaseToTheOnePointFive() {
        // tremolo 4 Hz: depth 1, skew 1 (no shape): wave(phase) = phase, gain = max(1 - 1, 0) + clamp(phase ^ 1.5)
        val g = gain("tremolo" to 4.0)
        // the LFO starts with the first quantum after the onset (frame 128), at phase 0: gain 0
        assertEquals(0f, g[128], 1e-6f)
        // phase 0.5 after half a period (6000 frames at 4 Hz): 0.5 ^ 1.5
        assertEquals(0.5.pow(1.5).toFloat(), g[128 + 6000], 1e-3f)
        assertEquals(0.5.pow(1.5), 0.35355, 1e-5)
        // a quarter and three quarters
        assertEquals(0.25.pow(1.5).toFloat(), g[128 + 3000], 1e-3f)
        assertEquals(0.75.pow(1.5).toFloat(), g[128 + 9000], 1e-3f)
        // the ramp wraps: just before the period ends the gain is almost 1, just after it starts from 0 again
        assertTrue(g[128 + 11990] > 0.99f, "just before the wrap the gain is almost 1")
        assertTrue(g[128 + 12100] < 2e-3f, "and a hundred frames into the next period it is almost 0")
    }

    @Test
    fun halfDepthRidesOnAHalfBase() {
        val g = gain("tremolo" to 4.0, "tremolodepth" to 0.5)
        // the base is 0.5 until the LFO runs; at phase -> 1: 0.5 + (1 * 0.5) ^ 1.5 = 0.8536
        for (i in 0 until 128) assertEquals(0.5f, g[i], 0f, "frame $i")
        assertEquals(0.5f, g[128], 1e-6f)
        assertEquals(0.5f + 0.5f.pow(1.5f), g[128 + 11999], 1e-3f)
        assertEquals(0.8536, 0.5 + 0.5.pow(1.5), 1e-4)
        assertTrue(g.all { it in 0.5f..0.8537f })
    }

    @Test
    fun aDepthAboveOneClipsTheLfoAtOneAndNeverLeavesTheUnitRange() {
        val g = gain("tremolo" to 4.0, "tremolodepth" to 2.0)
        // base max(1 - 2, 0) = 0; the LFO is clamp((2 * phase) ^ 1.5, 0, 1): 1 from phase 0.5 on
        assertEquals(1f, g[128 + 7000], 0f)
        assertTrue(g.all { it in 0f..1f })
    }

    @Test
    fun theLfoIsSilentUntilTheQuantumAfterTheOnset() {
        // an onset at frame 200 (quantum 1): the LFO starts with quantum 2 at frame 256, before that the gain is the base
        val t = 200.0 / SR
        val g = TremoloStage(SR).startedWith("tremolo" to 6.0, "tremolodepth" to 0.4, timing = stageTiming(t = t)).pushMono(FloatArray(512) { 1f }, startFrame = 200)
        for (i in 0 until 56) assertEquals(0.6f, g[i], 0f, "frame ${200 + i}")
        assertTrue(g[56 + 100] > 0.6f)
    }

    @Test
    fun theStartPhaseCountsCyclesNotSeconds() {
        // time = cycle / cps = 0.125 s at rate 4 Hz: the LFO starts at phase 0.5
        val g = gain("tremolo" to 4.0, timing = stageTiming(cps = 0.5, cycle = 0.0625))
        assertEquals(0.5.pow(1.5).toFloat(), g[128], 1e-3f)
        // tremolosync is in cycles: 8 per cycle at cps 0.5 is 4 Hz, the same tremolo
        val sync = gain("tremolosync" to 8.0, timing = stageTiming(cps = 0.5, cycle = 0.0625))
        assertContentEquals(g, sync)
        // and it wins over tremolo
        assertContentEquals(g, gain("tremolo" to 40.0, "tremolosync" to 8.0, timing = stageTiming(cps = 0.5, cycle = 0.0625)))
    }

    @Test
    fun skewDefaultsToOneWithoutAShapeAndToAHalfWithOne() {
        fun skewOf(vararg controls: Pair<String, Any?>): Double {
            val registry = NodeRegistry()
            TremoloStage(SR).startedWith(*controls, "tremolo" to 3.0).registerParams(registry)
            return registry.find("tremolo", "skew")!!.constantValue
        }
        assertEquals(1.0, skewOf())
        assertEquals(0.5, skewOf("tremoloshape" to "sine"))
        assertEquals(0.5, skewOf("tremoloshape" to 0.0), "a number is a shape too")
        assertEquals(0.25, skewOf("tremoloskew" to 0.25))
        assertEquals(0.25, skewOf("tremoloskew" to 0.25, "tremoloshape" to "square"))
    }

    @Test
    fun aSineShapeFollowsTheSineAroundTheBase() {
        // sine: wave = sin(2 pi phase) / 2 + 0.5 at depth 1: gain = wave ^ 1.5, phase 0.25 is the peak (1), phase 0.75 the trough (0)
        val g = gain("tremolo" to 4.0, "tremoloshape" to "sine")
        assertEquals(1f, g[128 + 3000], 1e-3f)
        assertEquals(0f, g[128 + 9000], 1e-3f)
        assertEquals(0.5.pow(1.5).toFloat(), g[128], 1e-3f)
    }

    @Test
    fun theParametersAreWhereModulatorsLookThem() {
        val registry = NodeRegistry()
        val stage = TremoloStage(SR).startedWith("tremolo" to 5.0, "tremolodepth" to 0.3).also { it.registerParams(registry) }
        assertEquals(5.0, registry.find("tremolo", "frequency")!!.constantValue)
        assertEquals(0.3, registry.find("tremolo", "depth")!!.constantValue, 1e-6, "the LFO's parameters are float32, like an AudioParam")
        assertEquals(1.5, registry.find("tremolo", "curve")!!.constantValue)
        assertEquals(0.0, registry.find("tremolo", "dcoffset")!!.constantValue)
        assertSame(stage.gain, registry.find("tremolo_gain", "gain"))
        assertEquals(0.7, stage.gain.constantValue, 1e-6, "the base of the gain node is 1 - depth")
        assertTrue(stage.gain.hasInputs, "the LFO is connected to it")
    }

    @Test
    fun theGainIsTheSameForBothChannelsAndForAnyBlockSize() {
        val x = FloatArray(3000) { ((it * 31) % 200 - 100) / 100f }
        val whole = TremoloStage(SR).startedWith("tremolo" to 9.0, "tremolodepth" to 0.8).pushMono(x)
        assertContentEquals(whole, TremoloStage(SR).startedWith("tremolo" to 9.0, "tremolodepth" to 0.8).pushMono(x, intArrayOf(97, 31)))
        val stereo = TremoloStage(SR).startedWith("tremolo" to 9.0, "tremolodepth" to 0.8).pushThrough(x, x)
        assertContentEquals(whole, stereo[0])
        assertContentEquals(whole, stereo[1])
    }

    @Test
    fun hostileValuesStayFinite() {
        val x = FloatArray(2048) { ((it * 7919) % 2001 - 1000) / 1000f }
        for (controls in listOf(
            arrayOf("tremolo" to 1e12),
            arrayOf("tremolo" to -50.0, "tremolodepth" to 1e30),
            arrayOf("tremolo" to 3.0, "tremolodepth" to -1e9),
            arrayOf("tremolo" to Double.POSITIVE_INFINITY),
            arrayOf("tremolo" to 3.0, "tremoloshape" to 1e300, "tremoloskew" to -4.0),
            arrayOf("tremolo" to 3.0, "tremoloshape" to -1.0),
            arrayOf("tremolosync" to 1e300, "tremolophase" to 1e300),
        )) {
            val y = TremoloStage(SR).startedWith(*controls, timing = stageTiming(cps = 0.0, cycle = 1e9)).pushMono(x)
            assertTrue(allFinite(y), "controls ${controls.toList()}")
        }
    }
}
