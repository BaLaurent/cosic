package cosic.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The phaser, CHAIN Task D (superdough.mjs:352-372, 871-885): one notch whose detune follows an LFO. The browser-exact comparison with sample-by-sample
 * modulation, stereo, the LFO ending and a 44.1 kHz context is in [StageFxGoldenTest]; these tests state the numbers of the CHAIN table and the rules.
 */
class PhaserStageTest {
    private fun registryOf(stage: PhaserStage) = NodeRegistry().also { stage.registerParams(it) }

    @Test
    fun theStageIsInTheChainWhenPhaserrateIsSetAndPhaserdepthIsAboveZero() {
        assertFalse(PhaserStage(SR).isInChain())
        assertFalse(PhaserStage(SR).isInChain("phaserdepth" to 0.5, "phasercenter" to 800.0, "phasersweep" to 100.0), "no rate, no phaser")
        assertTrue(PhaserStage(SR).isInChain("phaserrate" to 1.0))
        assertTrue(PhaserStage(SR).isInChain("phaserrate" to 0.0))
        assertFalse(PhaserStage(SR).isInChain("phaserrate" to 1.0, "phaserdepth" to 0.0), "phaserdepth 0: the stage is absent")
        assertFalse(PhaserStage(SR).isInChain("phaserrate" to 1.0, "phaserdepth" to -0.5))
        assertTrue(PhaserStage(SR).isInChain("phaserrate" to 1.0, "phaserdepth" to 0.001))
    }

    @Test
    fun theDefaultNotchIsAt1282HzWithAQOfAHalf() {
        val registry = registryOf(PhaserStage(SR).startedWith("phaserrate" to 2.0))
        assertEquals(1282.0, registry.find("phaser", "frequency")!!.constantValue, "1000 + the 282 Hz of the backward-compatible offset")
        assertEquals(0.5, registry.find("phaser", "Q")!!.constantValue, 1e-12, "depth 0.75: 2 - clamp(1.5, 0, 1.9)")
        assertEquals(0.0, registry.find("phaser", "detune")!!.constantValue)
        assertEquals(4000.0, registry.find("phaser_lfo", "depth")!!.constantValue, "the LFO swings twice phasersweep (2000)")
        assertEquals(2.0, registry.find("phaser_lfo", "frequency")!!.constantValue)
    }

    @Test
    fun centerDepthAndSweepMoveTheNotchAndItsWidth() {
        fun registry(vararg controls: Pair<String, Any?>) = registryOf(PhaserStage(SR).startedWith("phaserrate" to 1.0, *controls))
        assertEquals(782.0, registry("phasercenter" to 500.0).find("phaser", "frequency")!!.constantValue)
        assertEquals(1.4, registry("phaserdepth" to 0.3).find("phaser", "Q")!!.constantValue, 1e-12)
        assertEquals(0.1, registry("phaserdepth" to 1.0).find("phaser", "Q")!!.constantValue, 1e-12)
        assertEquals(0.1, registry("phaserdepth" to 9.0).find("phaser", "Q")!!.constantValue, 1e-12, "2 * depth is clamped at 1.9: Q never goes below 0.1")
        assertEquals(1.98, registry("phaserdepth" to 0.01).find("phaser", "Q")!!.constantValue, 1e-12)
        assertEquals(2000.0, registry("phasersweep" to 1000.0).find("phaser_lfo", "depth")!!.constantValue)
    }

    /** The detune AudioParam over [frames] frames from engine frame 0, in blocks of 256 (the LFO is a stream: the blocks must follow each other). */
    private fun detuneOver(stage: PhaserStage, frames: Int): FloatArray {
        val out = FloatArray(frames)
        val buffer = FloatArray(BLOCK_FRAMES)
        val ctx = BlockContext(SR)
        var frame = 0
        while (frame < frames) {
            ctx.startFrame = frame.toLong()
            ctx.n = minOf(BLOCK_FRAMES, frames - frame)
            stage.detune.fill(buffer, ctx)
            System.arraycopy(buffer, 0, out, frame, ctx.n)
            frame += ctx.n
        }
        return out
    }

    @Test
    fun theDetuneSwingsPlusMinusTheSweepInCentsAroundZero() {
        val stage = PhaserStage(SR).startedWith("phaserrate" to 5.0)
        val cents = detuneOver(stage, 20_000) // two periods of 9600 frames
        assertEquals(0f, cents[0], 0f, "the LFO has not run yet")
        assertEquals(0f, cents[127], 0f)
        val steady = cents.copyOfRange(128, cents.size)
        assertEquals(-2000f, steady.min(), 20f)
        assertEquals(2000f, steady.max(), 20f)
        // a triangle that starts at the bottom (phase 0 is the lowest point of tri(0, 0.5) - 0.5) and is at 0 a quarter of the way through
        assertEquals(-2000f, cents[128], 1f)
        assertEquals(0f, cents[128 + 2400], 25f)
    }

    @Test
    fun theSweepIsInCentsAndASweepOfZeroLeavesAStaticNotch() {
        val wide = detuneOver(PhaserStage(SR).startedWith("phaserrate" to 5.0, "phasersweep" to 600.0), 12_000).copyOfRange(128, 12_000)
        assertEquals(600f, wide.max(), 8f)
        assertEquals(-600f, wide.min(), 8f)
        val still = detuneOver(PhaserStage(SR).startedWith("phaserrate" to 5.0, "phasersweep" to 0.0), 6_000)
        assertTrue(still.all { it == 0f })
    }

    // ------------------------------------------------------------------------------------------------ the notch

    /** |H| of the notch at [hz] for a notch at [notchHz] with the linear [q] (the analytic response of the biquad). */
    private fun notchGain(hz: Double, notchHz: Double, q: Double): Double {
        val w0 = PI * notchHz / (SR / 2.0)
        val alpha = sin(w0) / (2 * q)
        val k = cos(w0)
        val w = 2 * PI * hz / SR
        fun mag(b0: Double, b1: Double, b2: Double): Double {
            val re = b0 + b1 * cos(w) + b2 * cos(2 * w)
            val im = -(b1 * sin(w) + b2 * sin(2 * w))
            return sqrt(re * re + im * im)
        }
        return mag(1.0, -2 * k, 1.0) / mag(1 + alpha, -2 * k, 1 - alpha)
    }

    private fun measuredGain(hz: Double, vararg controls: Pair<String, Any?>): Double {
        val x = sineWave(hz, 0.4, 0.5)
        val y = PhaserStage(SR).startedWith(*controls).pushMono(x)
        return rms(y, 9600, y.size) / rms(x, 9600, x.size)
    }

    @Test
    fun aStaticNotchHasTheAnalyticResponseAndIsDeepAtItsCenter() {
        // phasersweep 0: no movement; the notch is at 1282 Hz; depth 0.75 (Q 0.5)
        val controls = arrayOf("phaserrate" to 3.0, "phasersweep" to 0.0)
        assertTrue(measuredGain(1282.0, *controls) < 0.001, "the center is rejected")
        for (hz in listOf(150.0, 400.0, 900.0, 2000.0, 5000.0, 12000.0)) {
            val expected = notchGain(hz, 1282.0, 0.5)
            assertEquals(expected, measuredGain(hz, *controls), 0.01 * expected + 1e-3, "$hz Hz")
        }
        // a high Q is narrower: depth 0.05 gives Q 1.9
        val narrow = arrayOf("phaserrate" to 3.0, "phasersweep" to 0.0, "phaserdepth" to 0.05)
        assertEquals(notchGain(1000.0, 1282.0, 1.9), measuredGain(1000.0, *narrow), 0.01)
        assertTrue(measuredGain(1000.0, *narrow) > measuredGain(1000.0, *controls))
    }

    @Test
    fun theNotchMovesWithTheLfoOnceItRuns() {
        // 1282 Hz through a notch that is at 1282 Hz at the start (the LFO has not started: detune 0) and then sweeps away from it
        val x = sineWave(1282.0, 0.2, 0.5)
        val y = PhaserStage(SR).startedWith("phaserrate" to 2.0).pushMono(x)
        assertTrue(peak(y, 100, 128) < 0.05f, "while the LFO is silent the notch sits on the tone: ${peak(y, 100, 128)}")
        assertTrue(peak(y, 1000, 3000) > 0.2f, "the LFO's first point is -2000 cents: the notch is at 404 Hz and the tone gets through: ${peak(y, 1000, 3000)}")
    }

    @Test
    fun theFilterIsPerChannelWithTheSameCoefficients() {
        val noise = FloatArray(4000) { ((it * 7919) % 2001 - 1000) / 1000f }
        val mono = PhaserStage(SR).startedWith("phaserrate" to 7.0).pushMono(noise)
        val stereo = PhaserStage(SR).startedWith("phaserrate" to 7.0).pushThrough(noise, noise)
        assertContentEquals(mono, stereo[0])
        assertContentEquals(mono, stereo[1])
        val other = FloatArray(4000) { ((it * 104729) % 2001 - 1000) / 1000f }
        val mixed = PhaserStage(SR).startedWith("phaserrate" to 7.0).pushThrough(noise, other)
        assertContentEquals(mono, mixed[0], "a channel is not influenced by the other")
        assertContentEquals(PhaserStage(SR).startedWith("phaserrate" to 7.0).pushMono(other), mixed[1])
    }

    @Test
    fun aMonoBlocksRightChannelIsNotTouched() {
        val stage = PhaserStage(SR).startedWith("phaserrate" to 7.0)
        val block = AudioBlock(BLOCK_FRAMES)
        val ctx = BlockContext(SR)
        block.channels = 1
        for (i in 0 until 200) {
            block.l[i] = 0.5f
            block.r[i] = 9f
        }
        ctx.n = 200
        stage.process(block, ctx)
        assertTrue((0 until 200).all { block.r[it] == 9f })
    }

    @Test
    fun theOutputDoesNotDependOnTheBlockSize() {
        val noise = FloatArray(5000) { ((it * 7919) % 2001 - 1000) / 1000f }
        val whole = PhaserStage(SR).startedWith("phaserrate" to 11.0, "phasersweep" to 2500.0).pushMono(noise)
        for (split in listOf(intArrayOf(97), intArrayOf(1), intArrayOf(33, 17, 200, 255), intArrayOf(128))) {
            assertContentEquals(whole, PhaserStage(SR).startedWith("phaserrate" to 11.0, "phasersweep" to 2500.0).pushMono(noise, split), "blocks ${split.toList()}")
        }
    }

    @Test
    fun theLfoParametersAndTheFilterAreWhereModulatorsLookThem() {
        val stage = PhaserStage(SR).startedWith("phaserrate" to 3.0)
        val registry = registryOf(stage)
        assertSame(stage.frequency, registry.find("phaser", "frequency"))
        assertSame(stage.q, registry.find("phaser", "Q"))
        assertSame(stage.detune, registry.find("phaser", "detune"))
        assertTrue(stage.detune.hasInputs, "the LFO is connected to the detune")
        for (name in listOf("frequency", "depth", "skew", "curve", "dcoffset")) assertTrue(registry.find("phaser_lfo", name) != null, name)
        assertEquals(-0.5, registry.find("phaser_lfo", "dcoffset")!!.constantValue)
        assertEquals(0.5, registry.find("phaser_lfo", "skew")!!.constantValue)
    }

    @Test
    fun hostileValuesStayFinite() {
        val noise = FloatArray(3000) { ((it * 7919) % 2001 - 1000) / 1000f }
        for (controls in listOf(
            arrayOf("phaserrate" to 1e12),
            arrayOf("phaserrate" to 5.0, "phasersweep" to 1e30),
            arrayOf("phaserrate" to 5.0, "phasersweep" to -1e30),
            arrayOf("phaserrate" to 5.0, "phasercenter" to 1e30),
            arrayOf("phaserrate" to 5.0, "phasercenter" to -1e30),
            arrayOf("phaserrate" to 5.0, "phaserdepth" to 1e300),
            arrayOf("phaserrate" to Double.POSITIVE_INFINITY, "phasersweep" to Double.POSITIVE_INFINITY),
            arrayOf("phaserrate" to -3.0, "phasersweep" to 153600.0, "phasercenter" to 0.0),
        )) {
            val y = PhaserStage(SR).startedWith(*controls).pushMono(noise)
            assertTrue(allFinite(y), "controls ${controls.toList()}")
            assertTrue(y.all { abs(it) < 1e6f }, "controls ${controls.toList()} stay bounded")
        }
        // a non-finite input must not stick in the filter
        val stage = PhaserStage(SR).startedWith("phaserrate" to 5.0)
        val bad = FloatArray(2000) { if (it == 100) Float.NaN else if (it == 200) Float.POSITIVE_INFINITY else 0.3f }
        assertTrue(allFinite(stage.pushMono(bad)))
    }
}
