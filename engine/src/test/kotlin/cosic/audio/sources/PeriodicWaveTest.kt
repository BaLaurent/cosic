package cosic.audio.sources

import cosic.audio.Fft
import java.util.IdentityHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PeriodicWaveTest {
    private val sr = 48000
    private fun basic(type: BasicWave) = PeriodicWave.basic(type, sr)

    private fun select(wave: PeriodicWave, hz: Double): TableSelection = TableSelection().also { wave.select(hz, it) }

    @Test
    fun tableSizeAndRangeCountFollowTheSampleRate() {
        // (sample rate, table size, ranges): 2048 up to 24 kHz, 4096 up to 88.2 kHz, 16384 above; three ranges an octave
        for ((rate, size, ranges) in listOf(
            Triple(8000, 2048, 33), Triple(22050, 2048, 33), Triple(24000, 2048, 33), Triple(24001, 4096, 36), Triple(44100, 4096, 36),
            Triple(48000, 4096, 36), Triple(88200, 4096, 36), Triple(88201, 16384, 42), Triple(96000, 16384, 42),
        )) {
            val wave = PeriodicWave.basic(BasicWave.SINE, rate)
            assertEquals(size, wave.size, "size at $rate")
            assertEquals(ranges, wave.rangeCount, "ranges at $rate")
            assertEquals(size, wave.table(0).size)
            assertEquals(size, wave.table(ranges - 1).size)
            assertEquals(rate, wave.sampleRate)
        }
        val wave = basic(BasicWave.SINE)
        assertEquals(11.71875, wave.lowestFundamental, 0.0)
        assertEquals(0.0853333333, wave.rateScale, 1e-9)
        assertEquals(4096.0 / 48000.0, wave.rateScale, 0.0)
        assertEquals(44100.0 / 4096.0, PeriodicWave.basic(BasicWave.SINE, 44100).lowestFundamental, 0.0)
    }

    @Test
    fun partialsPerRangeHalveEveryThreeRanges() {
        val wave = basic(BasicWave.SINE)
        assertEquals(2048, wave.partialsInRange(0))
        assertEquals(1024, wave.partialsInRange(3))
        assertEquals(64, wave.partialsInRange(15))
        assertEquals(50, wave.partialsInRange(16))
        assertEquals(40, wave.partialsInRange(17))
        assertEquals(0, wave.partialsInRange(35))
        // all 36, as Chromium's float32 arithmetic gives them (checked against the double-precision values)
        val expected = intArrayOf(
            2048, 1625, 1290, 1024, 812, 645, 512, 406, 322, 256, 203, 161, 128, 101, 80, 64, 50, 40, 32, 25, 20, 16, 12, 10, 8, 6, 5, 4, 3, 2, 2, 1, 1, 1, 0, 0,
        )
        for (r in expected.indices) assertEquals(expected[r], wave.partialsInRange(r), "range $r")
        assertEquals(1024, PeriodicWave.basic(BasicWave.SINE, 22050).partialsInRange(0))
        assertEquals(8192, PeriodicWave.basic(BasicWave.SINE, 96000).partialsInRange(0))
        assertEquals(1625, PeriodicWave.basic(BasicWave.SINE, 96000).partialsInRange(7))
    }

    @Test
    fun selectBlendsTheTwoTablesAroundThePitch() {
        val wave = basic(BasicWave.SAWTOOTH)
        val a = select(wave, 440.0)
        assertEquals(16, a.higher)
        assertEquals(17, a.lower)
        assertEquals(0.692, a.factor, 0.001)
        val zero = select(wave, 0.0)
        assertEquals(0, zero.higher)
        assertEquals(1, zero.lower)
        assertEquals(0.0, zero.factor)
        val nan = select(wave, Double.NaN)
        assertEquals(0, nan.higher)
        assertEquals(0.0, nan.factor)
        // the sign does not matter
        val negative = select(wave, -440.0)
        assertEquals(16, negative.higher)
        assertEquals(a.factor, negative.factor)
    }

    @Test
    fun aNoteOnATableBoundaryPicksThatTableExactly() {
        val wave = basic(BasicWave.SAWTOOTH)
        // 11.71875 Hz times 2^(k/3) is the note of table k + 1; for k a multiple of 3 the ratio is a power of two, which must come out exact
        for (k in 0..9 step 3) {
            val s = select(wave, wave.lowestFundamental * (1 shl (k / 3)))
            assertEquals(k + 1, s.higher, "k $k")
            assertEquals(k + 2, s.lower)
            assertEquals(0.0, s.factor, "k $k")
        }
        val s = select(wave, 93.75)
        assertEquals(10, s.higher)
        assertEquals(0.0, s.factor)
    }

    @Test
    fun theTopOfTheRangeClampsToTheLastTable() {
        val wave = basic(BasicWave.SQUARE)
        // the Nyquist frequency is 11 octaves above the lowest fundamental: table 1 + 3 * 11, which has no partial left; anything about 30.2 kHz and up is clamped
        val nyquist = select(wave, 24000.0)
        assertEquals(34, nyquist.higher)
        assertEquals(35, nyquist.lower)
        assertEquals(0.0, nyquist.factor)
        assertEquals(0, wave.partialsInRange(34))
        for (hz in doubleArrayOf(30300.0, 1e6, Double.POSITIVE_INFINITY)) {
            val s = select(wave, hz)
            assertEquals(35, s.higher, "$hz")
            assertEquals(35, s.lower)
            assertEquals(0.0, s.factor)
        }
        val tiny = select(wave, 1e-300)
        assertEquals(0, tiny.higher)
        assertEquals(1, tiny.lower)
        // the position between tables, higher + factor, is 1 plus three times the octaves above the lowest fundamental, within [0, 35], and never goes down
        var previous = -1.0
        var f = 5.0
        while (f < 30000.0) {
            val s = select(wave, f)
            val position = s.higher + s.factor
            assertEquals((1.0 + 3.0 * Math.log(f / wave.lowestFundamental) / Math.log(2.0)).coerceIn(0.0, 35.0), position, 1e-9, "$f Hz")
            assertEquals(minOf(s.higher + 1, 35), s.lower)
            assertTrue(s.factor >= 0.0 && s.factor < 1.0)
            assertTrue(position >= previous, "$f Hz")
            previous = position
            f *= 1.013
        }
    }

    @Test
    fun theSineTableIsOneSineWave() {
        val t = basic(BasicWave.SINE).table(0)
        assertEquals(0.0f, t[0], 1e-6f)
        assertEquals(1.0f, t[1024], 1e-6f)
        assertEquals(0.70710678f, t[512], 1e-6f)
        assertEquals(0.0f, t[2048], 1e-6f)
        assertEquals(-1.0f, t[3072], 1e-6f)
        for (n in 0 until 4096 step 37) assertEquals(sin(2.0 * PI * n / 4096).toFloat(), t[n], 2e-6f, "entry $n")
    }

    @Test
    fun everyWaveHasTheLevelOfItsFirstTablePeakAtOne() {
        for (type in BasicWave.entries) {
            val t = basic(type).table(0)
            assertEquals(1.0f, t.maxOf { abs(it) }, 1e-6f, "$type")
        }
    }

    @Test
    fun theTriangleSquareAndSawtoothTablesHaveTheirShapes() {
        assertEquals(1.0f, basic(BasicWave.TRIANGLE).table(0)[1024], 1e-4f)
        assertEquals(-1.0f, basic(BasicWave.TRIANGLE).table(0)[3072], 1e-4f)
        // the flat top of a square is below its Gibbs overshoot, which is what the table is normalised to
        assertEquals(0.8484f, basic(BasicWave.SQUARE).table(0)[1024], 0.002f)
        val saw = basic(BasicWave.SAWTOOTH).table(0)
        assertEquals(0.424f, saw[1024], 0.002f)
        assertEquals(0.0f, saw[0], 1e-6f)
        for (i in 1 until 4096) assertEquals(-saw[4096 - i], saw[i], 2e-6f, "entry $i")
    }

    @Test
    fun aTableHoldsOnlyThePartialsOfItsRange() {
        val wave = basic(BasicWave.SAWTOOTH)
        val re = DoubleArray(4096)
        val im = DoubleArray(4096)
        val fft = Fft(4096)
        for (range in intArrayOf(0, 3, 15, 16, 27, 33, 35)) {
            val table = wave.table(range)
            for (n in 0 until 4096) {
                re[n] = table[n].toDouble()
                im[n] = 0.0
            }
            fft.forward(re, im)
            val kept = minOf(wave.partialsInRange(range), 2047)
            // sin(k x) has the DFT -i N/2 at bin k: a sawtooth's k-th partial is (-1)^(k+1) * 2 / (k pi) of that, scaled by one factor for all k
            val scale = if (kept >= 1) -im[1] / (2.0 / PI * 2048) else 0.0
            for (k in 1 until 2048) {
                val expected = if (k <= kept) scale * (if (k % 2 == 1) 1.0 else -1.0) * 2.0 / (k * PI) * 2048 else 0.0
                assertEquals(expected, -im[k], 5e-3, "range $range partial $k")
                assertEquals(0.0, re[k], 5e-3, "range $range cosine $k")
            }
            if (range == 0) assertEquals(1.0 / 1.179, scale, 0.002, "the peak of the first table is 1 (Gibbs overshoot of 17.9%)")
        }
    }

    @Test
    fun customWavesAreTheirSeriesNormalisedByTheFirstTable() {
        val real = doubleArrayOf(0.0, 0.5, 0.0, -0.25, 0.1, 0.0, 0.3)
        val imag = doubleArrayOf(0.0, 0.2, 0.7, 0.0, -0.4, 0.15, 0.0)
        val wave = PeriodicWave.custom(real, imag, sr)
        fun raw(n: Int, partials: Int): Double {
            var s = 0.0
            for (k in 1..minOf(partials, real.size - 1)) s += real[k] * cos(2.0 * PI * k * n / 4096) + imag[k] * sin(2.0 * PI * k * n / 4096)
            return s
        }
        var peak = 0.0
        for (n in 0 until 4096) peak = maxOf(peak, abs(raw(n, 6)))
        for (range in intArrayOf(0, 20, 26, 27, 28, 29, 30, 31, 33)) {
            val partials = wave.partialsInRange(range)
            for (n in 0 until 4096 step 13) assertEquals(raw(n, partials) / peak, wave.table(range)[n].toDouble(), 2e-6, "range $range entry $n")
        }
        assertEquals(1.0f, wave.table(0).maxOf { abs(it) }, 1e-6f)
    }

    @Test
    fun theOffsetComponentAndComponentsBeyondHalfTheTableAreIgnored() {
        val withDc = PeriodicWave.custom(doubleArrayOf(5.0, 0.5, 0.2), doubleArrayOf(7.0, 0.1, -0.3), sr)
        val without = PeriodicWave.custom(doubleArrayOf(0.0, 0.5, 0.2), doubleArrayOf(0.0, 0.1, -0.3), sr)
        for (range in intArrayOf(0, 10, 28, 33)) assertTrue(withDc.table(range).contentEquals(without.table(range)), "range $range")

        val random = java.util.Random(5)
        val long = DoubleArray(3000) { random.nextGaussian() }
        val longImag = DoubleArray(3000) { random.nextGaussian() }
        val big = PeriodicWave.custom(long, longImag, sr)
        val cut = PeriodicWave.custom(long.copyOf(2048), longImag.copyOf(2048), sr)
        for (range in intArrayOf(0, 1, 12, 30)) assertTrue(big.table(range).contentEquals(cut.table(range)), "range $range")
    }

    @Test
    fun nonFiniteCoefficientsCountAsZero() {
        val dirty = PeriodicWave.custom(doubleArrayOf(0.0, Double.NaN, 1.0), doubleArrayOf(0.0, 1.0, Double.POSITIVE_INFINITY), sr)
        val clean = PeriodicWave.custom(doubleArrayOf(0.0, 0.0, 1.0), doubleArrayOf(0.0, 1.0, 0.0), sr)
        for (range in intArrayOf(0, 5, 30)) assertTrue(dirty.table(range).contentEquals(clean.table(range)), "range $range")
        assertTrue(dirty.table(0).all { it.isFinite() })
    }

    @Test
    fun aSilentWaveHasSilentTablesAndRealAndImagMustMatch() {
        val silent = PeriodicWave.custom(DoubleArray(8), DoubleArray(8), sr)
        for (range in 0 until silent.rangeCount) assertTrue(silent.table(range).all { it == 0f }, "range $range")
        assertTrue(PeriodicWave.custom(DoubleArray(1), DoubleArray(1), sr).table(0).all { it == 0f })
        assertFailsWith<IllegalArgumentException> { PeriodicWave.custom(DoubleArray(3), DoubleArray(4), sr) }
        assertFailsWith<IllegalArgumentException> { PeriodicWave.basic(BasicWave.SINE, 0) }
    }

    @Test
    fun rangesWithTheSamePartialsShareOneTable() {
        // five partials: every range that keeps all five is the same table, then it changes with each partial that is cut
        val user = PeriodicWave.custom(doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0), doubleArrayOf(0.0, 1.0, 1.0, 1.0, 1.0, 1.0), sr)
        assertEquals(5, user.partialsInRange(26))
        assertSame(user.table(0), user.table(26))
        assertNotSame(user.table(26), user.table(27))
        assertEquals(4, user.partialsInRange(27))
        assertSame(user.table(34), user.table(35))
        assertTrue(user.table(35).all { it == 0f })
        // a table with fewer partials is not louder than one with more (the first is the normalised one) and differs from it
        assertTrue(user.table(0).maxOf { abs(it) } >= user.table(30).maxOf { abs(it) } * 0.99f)
    }

    @Test
    fun theBasicWavesAreBuiltOnceAndTakeAFewMegabytes() {
        for (type in BasicWave.entries) assertSame(basic(type), basic(type))
        assertNotSame(basic(BasicWave.SINE), PeriodicWave.basic(BasicWave.SINE, 44100))
        val distinct = IdentityHashMap<FloatArray, Boolean>()
        for (type in BasicWave.entries) for (r in 0 until basic(type).rangeCount) distinct[basic(type).table(r)] = true
        val bytes = distinct.keys.sumOf { it.size.toLong() * 4 }
        assertTrue(bytes <= 5_000_000, "$bytes bytes")
    }

    @Test
    fun selectDoesNotAllocate() {
        val wave = basic(BasicWave.TRIANGLE)
        val out = TableSelection()
        fun work() {
            var f = 1.0
            repeat(20_000) {
                wave.select(f, out)
                f = if (f > 30000.0) 1.0 else f * 1.0007
            }
        }
        repeat(5) { work() }
        assertEquals(0L, steadyAllocation { work() })
    }
}
