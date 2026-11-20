package cosic.audio.dsp

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlinx.serialization.json.JsonArray

/** The shape, crush and coarse worklets against the real processors' output (worklets.mjs:193-295). */
class ShapeCrushCoarseTest {
    private val golden = DspGolden.load("shapecrushcoarse.json")

    private fun cases(key: String) = DspGolden.cases(golden[key] as JsonArray)

    @Test
    fun shapeMatchesTheWorklet() {
        for (case in cases("shape")) {
            val data = case.copyOfInput()
            for (q in 0 until case.quanta) {
                ShapeCrushCoarse.shape(data[0], data.getOrNull(1), q * 128, (q + 1) * 128, case.param(q, "shape"), case.param(q, "postgain"))
            }
            for (ch in 0 until case.channels) assertClose("shape ${case.name} ch$ch", case.output[ch], data[ch], atol = 1e-7, rtol = 1e-6)
        }
    }

    @Test
    fun crushMatchesTheWorklet() {
        for (case in cases("crush")) {
            val data = case.copyOfInput()
            for (q in 0 until case.quanta) ShapeCrushCoarse.crush(data[0], data.getOrNull(1), q * 128, (q + 1) * 128, case.param(q, "crush"))
            for (ch in 0 until case.channels) assertClose("crush ${case.name} ch$ch", case.output[ch], data[ch], atol = 1e-7, rtol = 1e-6)
        }
    }

    private fun runCoarse(case: StreamCase, split: Int): Array<FloatArray> {
        val data = case.copyOfInput()
        val coarse = CoarseProcessor()
        for (q in 0 until case.quanta) {
            var from = q * 128
            val end = from + 128
            while (from < end) {
                val to = minOf(end, from + split)
                coarse.process(data[0], data.getOrNull(1), from, to, case.param(q, "coarse"), 0L)
                from = to
            }
        }
        return data
    }

    @Test
    fun coarseMatchesTheWorkletAndDoesNotDependOnHowTheCallsAreSplit() {
        for (case in cases("coarse")) {
            val whole = runCoarse(case, 128)
            for (ch in 0 until case.channels) assertClose("coarse ${case.name} ch$ch", case.output[ch], whole[ch], atol = 0.0)
            for (split in intArrayOf(1, 5, 37, 100)) {
                val pieces = runCoarse(case, split)
                for (ch in 0 until case.channels) assertContentEquals(whole[ch], pieces[ch], "split $split ch$ch")
            }
        }
    }

    @Test
    fun shapeHasTheDocumentedValues() {
        val io = floatArrayOf(0.5f, -0.5f)
        ShapeCrushCoarse.shape(io, null, 0, 2, 0.5, 1.0)
        assertEquals(0.75f, io[0], "(1 + 2) * 0.5 / (1 + 2 * 0.5)")
        assertEquals(-0.75f, io[1], "odd")
        val hard = floatArrayOf(0.5f, -0.5f)
        ShapeCrushCoarse.shape(hard, null, 0, 2, 1.0, 1.0)
        assertEquals(1.0, hard[0].toDouble(), 1e-6, "shape 1 is 1 - 4e-10: a hard clip")
        assertEquals(-1.0, hard[1].toDouble(), 1e-6)
        val quiet = floatArrayOf(0.5f)
        ShapeCrushCoarse.shape(quiet, null, 0, 1, 0.5, 0.5)
        assertEquals(0.375f, quiet[0], "postgain scales the result")
    }

    @Test
    fun crushRoundsHalvesUpAndAwayFromTheBankersRule() {
        val one = floatArrayOf(0.5f)
        ShapeCrushCoarse.crush(one, null, 0, 1, 1.0)
        assertEquals(1.0f, one[0], "crush 1: 0.5 rounds up to 1")
        val four = floatArrayOf(0.3f, -0.3f, 0.0625f, -0.0625f)
        ShapeCrushCoarse.crush(four, null, 0, 4, 4.0)
        assertEquals(0.25f, four[0])
        assertEquals(-0.25f, four[1])
        assertEquals(0.125f, four[2], "0.0625 * 8 = 0.5 rounds up")
        assertEquals(0.0, four[3].toDouble(), 0.0, "-0.5 rounds toward +inf: to -0")
        val zero = floatArrayOf(0.3f)
        ShapeCrushCoarse.crush(zero, null, 0, 1, 0.0)
        assertEquals(0.0f, zero[0], "crush below 1 behaves as 1: one step per unit")
    }

    @Test
    fun coarseHoldsEveryThirdFrameAndRestartsAtTheQuantumBoundary() {
        val ramp = FloatArray(256) { it.toFloat() }
        val first = ramp.copyOf()
        CoarseProcessor().process(first, null, 0, 10, 3.0, 0L)
        assertContentEquals(floatArrayOf(0f, 0f, 0f, 3f, 3f, 3f, 6f, 6f, 6f, 9f), first.copyOf(10))

        val io = ramp.copyOf()
        CoarseProcessor().process(io, null, 0, 256, 3.0, 0L)
        assertEquals(126f, io[127], "out[127] = in[126]: 127 % 3 = 1 holds")
        assertEquals(128f, io[128], "a new quantum starts: 0 % 3 = 0 passes")
        assertEquals(128f, io[129])
        assertEquals(128f, io[130])
        assertEquals(131f, io[131])
    }

    @Test
    fun coarseCountsFramesFromTheAbsoluteFrameCounter() {
        // The same signal two quanta later, and 100 frames into a quantum, gives the same holds relative to the quantum grid.
        val a = FloatArray(128) { it.toFloat() }
        CoarseProcessor().process(a, null, 0, 128, 2.5, 256L)
        val b = FloatArray(128) { it.toFloat() }
        CoarseProcessor().process(b, null, 0, 128, 2.5, 0L)
        assertContentEquals(a, b)
        val c = FloatArray(60) { (100 + it).toFloat() }
        CoarseProcessor().process(c, null, 0, 60, 2.5, 100L)
        for (i in 0 until 28) assertEquals(if ((100 + i) % 2.5 < 1.0 || i == 0) (100 + i).toFloat() else c[i - 1], c[i], "frame ${100 + i}")
        assertEquals(128f, c[28], "a quantum boundary at absolute frame 128")
    }

    @Test
    fun coarseOfOneOrLessAndNaNIsABypass() {
        val noise = FloatArray(200) { ((it * 31) % 17 - 8) / 10f }
        for (coarse in doubleArrayOf(1.0, 0.3, 0.0, -4.0, Double.NaN)) {
            val io = noise.copyOf()
            CoarseProcessor().process(io, null, 0, 200, coarse, 0L)
            assertContentEquals(noise, io, "coarse $coarse")
        }
    }
}
