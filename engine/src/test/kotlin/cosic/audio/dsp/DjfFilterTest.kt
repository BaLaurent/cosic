package cosic.audio.dsp

import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray

/** The `djf-processor` worklet against the real processor's output (worklets.mjs:297-362). */
class DjfFilterTest {
    private val cases = DspGolden.cases(DspGolden.load("djf.json")["cases"] as JsonArray)

    private fun run(case: StreamCase, split: Int): Array<FloatArray> {
        val data = case.copyOfInput()
        val djf = DjfFilter(case.sampleRate)
        for (q in 0 until case.quanta) {
            var from = q * 128
            val end = from + 128
            while (from < end) {
                val to = minOf(end, from + split)
                djf.process(data[0], data.getOrNull(1), from, to, case.param(q, "value"))
                from = to
            }
        }
        return data
    }

    @Test
    fun everyGoldenCaseMatchesTheWorklet() {
        for (case in cases) {
            val out = run(case, 128)
            for (ch in 0 until case.channels) assertClose("${case.name} ch$ch", case.output[ch], out[ch], atol = 1e-7, rtol = 1e-6)
        }
    }

    @Test
    fun callsThatSplitAQuantumDoNotChangeTheOutput() {
        for (case in cases) {
            val whole = run(case, 128)
            for (split in intArrayOf(1, 7, 50)) {
                val pieces = run(case, split)
                for (ch in 0 until case.channels) assertContentEquals(whole[ch], pieces[ch], "${case.name} split $split ch$ch")
            }
        }
    }

    @Test
    fun theMiddleOfTheKnobPassesTheSignalUntouched() {
        val noise = FloatArray(256) { ((it * 37) % 19 - 9) / 10f }
        for (value in doubleArrayOf(0.5, 0.49, 0.51, 0.495, 0.505)) {
            val io = noise.copyOf()
            DjfFilter(SR).process(io, io.copyOf(), 0, 256, value)
            assertContentEquals(noise, io, "value $value")
        }
    }

    @Test
    fun theDcGainOfTheFilterIsNotOne() {
        // value 0.25: low-pass at 915 Hz, DC gain 1 / (1 + r^2) = 0.92382; value 0.75: high-pass = input - low-pass, so 1 - 0.92382 = 0.07618 of DC leaks
        for ((value, expected) in listOf(0.25 to 0.92382, 0.75 to 0.07618)) {
            val dc = FloatArray(157 * 128) { 1f }
            val djf = DjfFilter(SR)
            for (q in 0 until dc.size / 128) djf.process(dc, null, q * 128, (q + 1) * 128, value)
            assertEquals(expected, dc[dc.size - 1].toDouble(), 1e-3, "value $value")
        }
    }

    @Test
    fun aFiveKilohertzSineIsAttenuatedByTheLowpassSide() {
        val n = 9600
        val sine = FloatArray(n) { (0.5 * sin(2 * PI * 5000 * it / SR)).toFloat() }
        val out = sine.copyOf()
        val djf = DjfFilter(SR)
        for (q in 0 until n / 128) djf.process(out, null, q * 128, (q + 1) * 128, 0.25)
        fun rms(a: FloatArray) = sqrt(a.drop(4800).sumOf { (it * it).toDouble() } / (a.size - 4800))
        assertTrue(20 * log10(rms(out) / rms(sine)) < -15, "attenuated more than 15 dB")
    }

    @Test
    fun theFilterMemoryStaysFrozenWhileTheKnobIsInTheMiddleAndResetClearsIt() {
        val noise = FloatArray(128) { ((it * 37) % 19 - 9) / 10f }
        val a = noise.copyOf()
        val b = noise.copyOf()
        val withPause = DjfFilter(SR)
        withPause.process(a, null, 0, 128, 0.25)
        withPause.process(FloatArray(128) { 0.7f }, null, 0, 128, 0.5) // 'none': passes, state untouched
        val c = noise.copyOf()
        withPause.process(c, null, 0, 128, 0.25)
        val straight = DjfFilter(SR)
        straight.process(b, null, 0, 128, 0.25)
        val d = noise.copyOf()
        straight.process(d, null, 0, 128, 0.25)
        assertContentEquals(d, c)
        straight.reset()
        val e = noise.copyOf()
        straight.process(e, null, 0, 128, 0.25)
        assertContentEquals(a, e)
    }

    @Test
    fun nonFiniteInputNeverPoisonsTheFilterMemory() {
        val clean = FloatArray(128) { ((it * 37) % 19 - 9) / 10f }
        val dirty = clean.copyOf()
        dirty[10] = Float.NaN
        dirty[20] = Float.POSITIVE_INFINITY
        val djf = DjfFilter(SR)
        djf.process(dirty, null, 0, 128, 0.25)
        assertTrue(dirty.all { it - it == 0f }, "no NaN or infinity at the output")
        val next = clean.copyOf()
        djf.process(next, null, 0, 128, 0.25)
        val fresh = clean.copyOf()
        DjfFilter(SR).process(fresh, null, 0, 128, 0.25)
        assertContentEquals(fresh, next, "the memory restarted from silence")
    }

    private companion object {
        const val SR = 48000
    }
}
