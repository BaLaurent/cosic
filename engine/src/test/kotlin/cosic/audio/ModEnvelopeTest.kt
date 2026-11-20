package cosic.audio

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The `envelope-processor` worklet against the real processor's output. */
class ModEnvelopeTest {
    private class Case(
        val a: Double, val d: Double, val s: Double, val r: Double, val aCurve: Double, val dCurve: Double, val rCurve: Double,
        val depth: Double, val min: Double, val max: Double, val begin: Double, val end: Double, val quanta: Int,
    )

    /** The parameters worklet-oracle.mjs ran the processor with; keep them in step. */
    private val cases = mapOf(
        "envDefault" to Case(0.005, 0.14, 0.0, 0.1, 0.0, 0.0, 0.0, 1.0, -1e9, 1e9, 0.0003, 1.0, 420),
        "envCurved" to Case(0.1, 0.05, 0.4, 0.2, 0.5, -0.5, 1.0, 1.0, -1e9, 1e9, 0.5, 0.8, 400),
        "envScaled" to Case(0.02, 0.03, 0.5, 0.1, 0.0, 0.0, 0.0, 3.0, 0.0, 2.0, 0.01, 0.3, 120),
    )

    private fun envelope(c: Case) = ModEnvelope(SR).also {
        it.start(c.a, c.d, c.s, c.r, c.aCurve, c.dCurve, c.rCurve, c.depth, c.min, c.max, c.begin, c.end)
    }

    private fun render(env: ModEnvelope, frames: Int, sizes: IntArray): FloatArray {
        val ctx = BlockContext(SR)
        val out = FloatArray(frames)
        var done = 0
        var i = 0
        while (done < frames) {
            val n = minOf(sizes[i++ % sizes.size], frames - done)
            ctx.startFrame = done.toLong()
            ctx.n = n
            val block = FloatArray(n)
            env.addInto(block, ctx)
            block.copyInto(out, done)
            done += n
        }
        return out
    }

    @Test
    fun theNodeReproducesTheProcessorOfTheOracleFrameForFrame() {
        val vectors = mapOf(
            "envDefault" to WorkletOracleVectors.envDefault, "envCurved" to WorkletOracleVectors.envCurved,
            "envScaled" to WorkletOracleVectors.envScaled,
        )
        for ((name, expected) in vectors) {
            val case = cases.getValue(name)
            val out = render(envelope(case), case.quanta * QUANTUM, intArrayOf(256))
            for ((frame, value) in expected) assertEquals(value, out[frame], 2e-6f, "$name frame $frame")
        }
    }

    @Test
    fun theValueStepsOncePerQuantumAndJumpsWhereTheAttackEnds() {
        val out = render(envelope(cases.getValue("envDefault")), 1024, intArrayOf(256))
        assertEquals(0f, out[127])
        assertEquals(0.47333f, out[128], 1e-4f, "the first quantum after begin: (0.002667 - 0.0003) / 0.005")
        assertEquals(out[128], out[255], "constant within a quantum")
        assertEquals(1f, out[256], "the quantum the attack ends in starts at its peak...")
        assertEquals(0.96529f, out[257], 1e-4f, "...and the decay, measured from begin over attack plus decay, takes over after one frame")
    }

    @Test
    fun theOutputIsTheSameHoweverTheBlocksAreCut() {
        for ((name, case) in cases) {
            val frames = 4096
            val whole = render(envelope(case), frames, intArrayOf(256))
            assertContentEquals(whole, render(envelope(case), frames, intArrayOf(97)), "$name in blocks of 97")
            assertContentEquals(whole, render(envelope(case), frames, intArrayOf(1, 127, 128, 129, 33, 200)), "$name in odd blocks")
        }
    }

    @Test
    fun beginZeroStillTriggersTheEnvelope() {
        val c = Case(0.005, 0.14, 0.0, 0.1, 0.0, 0.0, 0.0, 1.0, -1e9, 1e9, 0.0, 1.0, 100)
        val out = render(envelope(c), 4864 + 1, intArrayOf(256))
        assertEquals(0f, out[127])
        assertEquals(0.3011f, out[4864], 1e-3f, "frame 38 * 128 of the decay that started at 0")
    }

    @Test
    fun theNodeStopsAtItsEndAndFeedsAParamThroughAddInput() {
        val c = cases.getValue("envCurved")
        val out = render(envelope(c), 40_000, intArrayOf(256))
        assertEquals(0.4f, out[38_399], 1e-6f)
        for (i in 38_400 until 40_000) assertEquals(0f, out[i])

        val target = AParam(500.0, 0.0, 20_000.0)
        target.addInput(envelope(cases.getValue("envScaled")))
        val ctx = BlockContext(SR)
        ctx.startFrame = 14_400
        ctx.n = 8
        val values = FloatArray(8)
        target.fill(values, ctx)
        assertTrue(values.all { it in 500f..502f }, "the envelope adds its value, at most 2, to the base")
    }
}
