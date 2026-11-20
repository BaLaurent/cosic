package cosic.audio.dsp

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The `ladder-processor` worklet against the real processor's output (golden vectors from tools/strudel-ref/dsp-oracle.mjs). */
class LadderFilterTest {
    private val golden = DspGolden.load("ladder.json")
    private val cases = DspGolden.cases(golden["cases"] as kotlinx.serialization.json.JsonArray)

    private fun run(case: StreamCase, split: Int): Array<FloatArray> {
        val data = case.copyOfInput()
        val filter = LadderFilter(case.sampleRate)
        for (q in 0 until case.quanta) {
            val frequency = case.param(q, "frequency")
            val resonance = case.param(q, "q")
            val drive = case.param(q, "drive")
            var from = q * QUANTUM
            val end = from + QUANTUM
            while (from < end) {
                val to = minOf(end, from + split)
                filter.process(data[0], data.getOrNull(1), from, to, frequency, resonance, drive)
                from = to
            }
        }
        return data
    }

    @Test
    fun everyGoldenCaseMatchesTheWorklet() {
        for (case in cases) {
            val out = run(case, QUANTUM)
            for (ch in 0 until case.channels) assertClose("${case.name} ch$ch", case.output[ch], out[ch], atol = 1e-7, rtol = 1e-6)
        }
    }

    @Test
    fun callsThatSplitAQuantumDoNotChangeTheOutput() {
        for (case in cases) {
            val whole = run(case, QUANTUM)
            for (split in intArrayOf(1, 7, 37, 100)) {
                val pieces = run(case, split)
                for (ch in 0 until case.channels) assertContentEquals(whole[ch], pieces[ch], "${case.name} split $split ch$ch")
            }
        }
    }

    @Test
    fun theDcGainIsTheSumOfTheTaps() {
        // const 0.1, 1 kHz: out = 0.1 * (0.360891 + 0.41729 + 0.177896 + 0.0439725) = 0.10000495; drive and its makeup cancel
        for (drive in doubleArrayOf(0.0, 1.0)) {
            val data = FloatArray(8000) { 0.1f }
            val filter = LadderFilter(SR)
            for (q in 0 until data.size / QUANTUM) filter.process(data, null, q * QUANTUM, (q + 1) * QUANTUM, 1000.0, 0.0, drive)
            assertEquals(0.10000495, data[7000].toDouble(), 2e-6, "drive $drive")
        }
        // with resonance 10 the feedback k = 1.3 takes the level down to 0.07609
        val resonant = FloatArray(16000) { 0.1f }
        val filter = LadderFilter(SR)
        for (q in 0 until resonant.size / QUANTUM) filter.process(resonant, null, q * QUANTUM, (q + 1) * QUANTUM, 1000.0, 10.0, 0.0)
        assertEquals(0.07609, resonant[15000].toDouble(), 5e-4)
    }

    @Test
    fun resetReturnsToAFreshFilter() {
        val noise = FloatArray(QUANTUM * 4) { ((it * 7919) % 1000 - 500) / 600f }
        val first = noise.copyOf()
        val filter = LadderFilter(SR)
        for (q in 0 until 4) filter.process(first, null, q * QUANTUM, (q + 1) * QUANTUM, 800.0, 6.0, 0.69)
        filter.reset()
        val second = noise.copyOf()
        for (q in 0 until 4) filter.process(second, null, q * QUANTUM, (q + 1) * QUANTUM, 800.0, 6.0, 0.69)
        assertContentEquals(first, second)
    }

    @Test
    fun anUnstableSettingNeverLeavesNaNOrInfinityInTheChain() {
        val loud = FloatArray(QUANTUM * 20) { if (it % 2 == 0) 3e38f else -3e38f }
        val filter = LadderFilter(SR)
        for (q in 0 until 20) filter.process(loud, null, q * QUANTUM, (q + 1) * QUANTUM, -50000.0, 1e9, 20.0)
        assertTrue(loud.all { abs(it) < Float.MAX_VALUE }, "finite output")
        val nan = FloatArray(QUANTUM) { Float.NaN }
        filter.process(nan, null, 0, QUANTUM, 1000.0, 1.0, 0.69)
        filter.process(FloatArray(QUANTUM) { 0.5f }.also { assertTrue(it.all { v -> v == v }) }, null, 0, QUANTUM, 1000.0, 1.0, 0.69)
    }

    private companion object {
        const val SR = 48000
        const val QUANTUM = 128
    }
}
