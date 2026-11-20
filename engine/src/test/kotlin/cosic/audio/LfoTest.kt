package cosic.audio

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The `lfo-processor` worklet: its waveforms, its oscillator, and the node's quantum behaviour against the real processor's output. */
class LfoTest {
    // ------------------------------------------------------------------- waves

    @Test
    fun theWaveformsMatchTheWorklet() {
        assertEquals(0.5, lfoWave(0, 0.25, 0.5))
        assertEquals(1.0, lfoWave(0, 0.5, 0.5))
        assertEquals(0.5, lfoWave(0, 0.75, 0.5))
        assertEquals(0.3, lfoWave(0, 0.3, 1.0), 1e-12, "with skew 1 the triangle is a rising ramp")
        assertEquals(1.0, lfoWave(1, 0.25, 0.5), 1e-12)
        assertEquals(0.5, lfoWave(1, 0.0, 0.5), 1e-12)
        assertEquals(0.3, lfoWave(2, 0.3, 0.5))
        assertEquals(0.7, lfoWave(3, 0.3, 0.5), 1e-12)
        assertEquals(1.0, lfoWave(4, 0.25, 0.5))
        assertEquals(0.0, lfoWave(4, 0.75, 0.5))
        assertEquals(1.0, lfoWave(4, 0.25, 0.3))
        assertEquals(0.0, lfoWave(4, 0.3, 0.3))
    }

    @Test
    fun shapeIndicesAreNumbersModuloFiveOrNames() {
        assertEquals(1, lfoShapeIndex("sine"))
        assertEquals(0, lfoShapeIndex("tri"))
        assertEquals(0, lfoShapeIndex("triangle"))
        assertEquals(2, lfoShapeIndex("ramp"))
        assertEquals(3, lfoShapeIndex("saw"))
        assertEquals(4, lfoShapeIndex("square"))
        assertEquals(0, lfoShapeIndex("wobble"), "an unknown name is the triangle")
        assertEquals(0, lfoShapeIndex(null))
        assertEquals(2, lfoShapeIndex(7.0))
        assertEquals(4, lfoShapeIndex(4.0))
        assertEquals(0, lfoShapeIndex(5.0))
        assertEquals(-1, lfoShapeIndex(-1.0), "no valid index: the worklet would throw")
        assertEquals(-1, lfoShapeIndex(1.5))
        assertEquals(4, lfoShapeIndex(null, "square"))
        assertEquals(3, lfoShapeIndex(3.0, "tri"), "a number wins over a name")
    }

    // ------------------------------------------------------------- oscillator

    @Test
    fun theOscillatorFollowsTheDefaultsOfGetLfo() {
        val osc = LfoOscillator(SR)
        val samples = DoubleArray(36_001) { osc.next() }
        assertEquals(-0.5, samples[0])
        assertEquals(0.0, samples[12_000], 1e-9)
        assertEquals(0.5, samples[24_000], 1e-9)
        assertEquals(0.0, samples[36_000], 1e-9)
        assertTrue(samples.all { it in -0.5..0.5 }, "clamped to dcoffset * depth .. dcoffset * depth + depth")
    }

    @Test
    fun resetStartsThePhaseFromTheTimeAndThePhaseOffset() {
        val osc = LfoOscillator(SR)
        osc.reset(0.25)
        assertEquals(0.0, osc.next(), 1e-12)
        val offset = LfoOscillator(SR, phaseoffset = 0.5)
        offset.reset(0.0)
        assertEquals(0.5, offset.next(), 1e-12)
        val deep = LfoOscillator(SR, depth = 0.3)
        assertEquals(-0.15, deep.next(), 1e-12)
        val wide = LfoOscillator(SR, depth = 2.0, dcoffset = 0.0, min = -5.0, max = 5.0)
        wide.reset(0.25)
        assertEquals(1.0, wide.next(), 1e-12, "a triangle a quarter period in is 0.5, times depth 2")
    }

    @Test
    fun aFractionalCurveOfANegativeValueIsNaNForTheCallerToDealWith() {
        val osc = LfoOscillator(SR, curve = 1.5)
        assertTrue(osc.next().isNaN(), "(0 - 0.5) ^ 1.5")
    }

    // ------------------------------------------------------------------- node

    private class Case(
        val shape: Int, val skew: Double, val depth: Double, val dcoffset: Double, val freq: Double, val time: Double,
        val phaseoffset: Double, val curve: Double, val min: Double, val max: Double, val begin: Double, val end: Double, val quanta: Int,
    )

    /** The parameters worklet-oracle.mjs ran the processor with; keep them in step. */
    private val cases = mapOf(
        "lfoTri" to Case(0, 0.5, 1.0, -0.5, 1.0, 0.0, 0.0, 1.0, -0.5, 0.5, 0.0, 10.0, 400),
        "lfoSineGated" to Case(1, 0.3, 0.5, 0.0, 3.7, 0.003, 0.2, 1.5, -1.0, 1.0, 0.003, 0.02, 10),
        "lfoSquare" to Case(4, 0.3, 1.0, 0.0, 100.0, 0.0, 0.0, 1.0, 0.0, 1.0, 0.0, 10.0, 8),
        "lfoRamp" to Case(2, 0.5, 1.0, 0.0, 100.0, 0.0, 0.0, 1.0, 0.0, 1.0, 0.0, 10.0, 8),
        "lfoSaw" to Case(3, 0.5, 1.0, 0.0, 100.0, 0.0, 0.0, 1.0, 0.0, 1.0, 0.0, 10.0, 8),
        "lfoTriSkew1" to Case(0, 1.0, 1.0, 0.0, 375.0, 0.0, 0.0, 1.5, 0.0, 1.0, 0.0, 10.0, 6),
    )

    private fun osc(c: Case): LfoOsc = LfoOsc(SR).also {
        it.start(c.shape, c.skew, c.depth, c.dcoffset, null, c.freq, c.time, c.phaseoffset, c.curve, c.min, c.max, c.begin, c.end)
    }

    /** Renders [frames] frames of [lfo] in blocks of the sizes in [sizes] (repeated). */
    private fun render(lfo: LfoOsc, frames: Int, sizes: IntArray): FloatArray {
        val ctx = BlockContext(SR)
        val out = FloatArray(frames)
        var done = 0
        var i = 0
        while (done < frames) {
            val n = minOf(sizes[i++ % sizes.size], frames - done)
            ctx.startFrame = done.toLong()
            ctx.n = n
            val block = FloatArray(n)
            lfo.addInto(block, ctx)
            block.copyInto(out, done)
            done += n
        }
        return out
    }

    @Test
    fun theNodeReproducesTheProcessorOfTheOracleFrameForFrame() {
        val vectors = mapOf(
            "lfoTri" to WorkletOracleVectors.lfoTri, "lfoSineGated" to WorkletOracleVectors.lfoSineGated,
            "lfoSquare" to WorkletOracleVectors.lfoSquare, "lfoRamp" to WorkletOracleVectors.lfoRamp,
            "lfoSaw" to WorkletOracleVectors.lfoSaw, "lfoTriSkew1" to WorkletOracleVectors.lfoTriSkew1,
        )
        for ((name, expected) in vectors) {
            val case = cases.getValue(name)
            val out = render(osc(case), case.quanta * QUANTUM, intArrayOf(256))
            for ((frame, value) in expected) {
                // the worklet writes NaN where a skew-1 triangle divides by zero; the node writes silence for it
                val want = if (value.isNaN()) 0f else value
                assertEquals(want, out[frame], 2e-6f, "$name frame $frame")
            }
        }
    }

    @Test
    fun theOutputIsTheSameHoweverTheBlocksAreCut() {
        for ((name, case) in cases) {
            val frames = case.quanta.coerceAtMost(10) * QUANTUM
            val whole = render(osc(case), frames, intArrayOf(256))
            assertContentEquals(whole, render(osc(case), frames, intArrayOf(97)), "$name in blocks of 97")
            assertContentEquals(whole, render(osc(case), frames, intArrayOf(1, 127, 128, 129, 33, 200)), "$name in odd blocks")
        }
    }

    @Test
    fun theNodeIsSilentUntilTheQuantumAfterBeginAndGoneFromTheQuantumAtEnd() {
        val c = cases.getValue("lfoTri")
        val out = render(osc(c), 512, intArrayOf(256))
        for (i in 0 until 128) assertEquals(0f, out[i], "begin 0: the first quantum is silent, its time is not above begin")
        assertEquals(-0.5f, out[128])

        // begin 0.003 s is frame 144: quantum 1 (frame 128) starts before it, quantum 2 (frame 256) is the first one running
        val late = cases.getValue("lfoSineGated")
        val l = render(osc(late), 1280, intArrayOf(256))
        assertEquals(0f, l[255])
        assertTrue(l[256] != 0f)
        // end 0.02 s: quantum 7 (frame 896, 0.01867 s) still runs, quantum 8 (1024, 0.02133 s) does not
        assertTrue(l[1023] != 0f)
        for (i in 1024 until 1280) assertEquals(0f, l[i], "frame $i")
    }

    @Test
    fun aShapeThatKillsTheWorkletGivesSilence() {
        val c = cases.getValue("lfoTri")
        val dead = LfoOsc(SR).also {
            it.start(lfoShapeIndex(-1.0), c.skew, c.depth, c.dcoffset, null, c.freq, c.time, c.phaseoffset, c.curve, c.min, c.max, c.begin, c.end)
        }
        assertEquals(0f, peak(render(dead, 1024, intArrayOf(256))))
    }

    @Test
    fun theFrequencyIsReadOncePerQuantumAtItsFirstFrame() {
        // 100 Hz saw; a second parameter takes the frequency to 200 Hz at frame 300 (inside quantum 2: frames 256 to 383)
        val c = cases.getValue("lfoSaw")
        val moving = AParam(100.0, -1e9, 1e9)
        moving.setValueAtTime(200.0, 300.0 / SR)
        val lfo = LfoOsc(SR)
        lfo.start(c.shape, c.skew, c.depth, c.dcoffset, moving, 100.0, c.time, c.phaseoffset, c.curve, c.min, c.max, c.begin, c.end)
        val still = render(osc(c), 1024, intArrayOf(256))
        val out = render(lfo, 1024, intArrayOf(256))
        for (i in 0 until 384) assertEquals(still[i], out[i], 1e-6f, "frame $i: still 100 Hz while the quantum that contains frame 300 runs")
        var different = false
        for (i in 384 until 1024) if (abs(still[i] - out[i]) > 1e-3f) different = true
        assertTrue(different, "and 200 Hz from the next quantum")
    }

    @Test
    fun aLfoCanModulateAParamThroughAddInput() {
        val c = cases.getValue("lfoTri")
        val target = AParam(1000.0, 0.0, 20_000.0)
        target.addInput(osc(c))
        val ctx = BlockContext(SR)
        ctx.startFrame = 128
        ctx.n = 4
        val out = FloatArray(4)
        target.fill(out, ctx)
        assertEquals(1000f - 0.5f, out[0], 1e-3f)
    }
}
