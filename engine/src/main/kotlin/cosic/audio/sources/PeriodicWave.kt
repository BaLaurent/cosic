package cosic.audio.sources

import cosic.audio.Fft
import java.util.concurrent.ConcurrentHashMap

/** The four waveforms of a Web Audio `OscillatorNode` (`type`); `sine` is its default, the others are band-limited. */
internal enum class BasicWave { SINE, SQUARE, SAWTOOTH, TRIANGLE }

/** Where [PeriodicWave.select] put its answer: blend table [higher] (more partials) with table [lower] (fewer), [factor] of the way to [lower]. */
internal class TableSelection {
    var higher = 0
    var lower = 0
    var factor = 0.0
}

/**
 * The band-limited wave tables of a Web Audio `PeriodicWave` (Chromium's), which is what superdough's native oscillators (`sine`, `sawtooth`,
 * `square`, `triangle`, and every `partials`/`n` waveform built with `createPeriodicWave`) play. docs/parity/spec/superdough-sources.md section 2.4
 * describes it; it was checked against the tables and the output of Chromium 155 (`PeriodicOscillatorTest`, `OscillatorOracleVectors`).
 *
 * One period is a table of [size] entries (2048 up to 24 kHz, 4096 up to 88.2 kHz, 16384 above). The wave has [rangeCount] tables, three per octave
 * (400 cents): table `r` keeps only the partials `1..partialsInRange(r)`, so a note picks the two tables whose partials stay below the Nyquist
 * frequency and blends them ([select]). All tables are scaled by one factor, that which makes the peak of table 0 (all partials) 1.
 *
 * A wave is immutable and shared; play it with a [PeriodicOscillator]. The tables of the four [BasicWave]s are built on first use and kept.
 */
internal class PeriodicWave private constructor(val sampleRate: Int, real: DoubleArray, imag: DoubleArray) {
    /** Entries of one table (one period). */
    val size: Int = tableSize(sampleRate)

    /** How many band-limited tables the wave has. */
    val rangeCount: Int = (0.5 + 3.0 * log2(size.toDouble())).toInt()

    /** Table entries per frame at 1 Hz: a note of `f` Hz advances `f * rateScale` entries per frame. */
    val rateScale: Double = size.toDouble() / sampleRate

    /** The frequency whose partials just fill table 0: the table range of a note is counted from it. */
    val lowestFundamental: Double = (0.5 * sampleRate) / (size / 2)

    private val partials = IntArray(rangeCount) { partialsForRange(it, size) }
    private val tables: Array<FloatArray> = buildTables(real, imag)

    /** The number of partials table [range] was meant to keep (superdough's wave tables are cut at the Nyquist frequency of their range). */
    fun partialsInRange(range: Int): Int = partials[range]

    /** The [size] entries of table [range]. Shared: do not modify. */
    fun table(range: Int): FloatArray = tables[range]

    /**
     * Picks the tables for a note of [fundamentalHz] (its sign is ignored; 0 and NaN count as a very low note): `out.higher` and `out.lower` are the
     * indices of two adjacent tables (the same one at the ends) and `out.factor` in `[0, 1)` how far to blend towards `lower`.
     * Allocation-free.
     */
    fun select(fundamentalHz: Double, out: TableSelection) {
        val f = Math.abs(fundamentalHz)
        val ratio = if (f > 0.0) f / lowestFundamental else 0.5
        val centsAboveLowest = log2(ratio) * 1200.0
        // one is added so that a frequency just under a range boundary does not pick the range below it
        var pitchRange = 1.0 + centsAboveLowest / CENTS_PER_RANGE
        val last = (rangeCount - 1).toDouble()
        if (pitchRange < 0.0) pitchRange = 0.0 else if (pitchRange > last) pitchRange = last
        val higher = pitchRange.toInt()
        out.higher = higher
        out.lower = if (higher < rangeCount - 1) higher + 1 else higher
        out.factor = pitchRange - higher
    }

    private fun buildTables(real: DoubleArray, imag: DoubleArray): Array<FloatArray> {
        val half = size / 2
        // partial 0 (DC) is ignored and only the first size/2 components count: the highest partial a table can hold is size/2 - 1
        val highest = minOf(real.size, half) - 1
        val fft = fftOf(size)
        val re = DoubleArray(size)
        val im = DoubleArray(size)
        val silent = FloatArray(size)
        val built = Array(rangeCount) { silent }
        var scale = 0.5 // Blink's value when table 0 is all zero (then every table is)
        var previousTop = -1
        for (range in 0 until rangeCount) {
            val top = maxOf(minOf(partials[range], highest), 0)
            if (top == previousTop) { // the same partials, the same table
                built[range] = built[range - 1]
                continue
            }
            previousTop = top
            if (top == 0) continue // no partial left: the silent table
            java.util.Arrays.fill(re, 0.0)
            java.util.Arrays.fill(im, 0.0)
            // x[n] = sum real[k] cos(2 pi k n / size) + imag[k] sin(2 pi k n / size): the spectrum of that sum, scaled up by size / 2 because the
            // inverse transform divides by size
            for (k in 1..top) {
                val r = finiteOrZero(real[k]) * half
                val i = finiteOrZero(imag[k]) * half
                re[k] = r
                im[k] = -i
                re[size - k] = r
                im[size - k] = i
            }
            fft.inverse(re, im)
            if (range == 0) {
                var peak = 0.0
                for (n in 0 until size) peak = Math.max(peak, Math.abs(re[n]))
                if (peak > 0.0) scale = 1.0 / peak
            }
            val data = FloatArray(size)
            for (n in 0 until size) data[n] = (re[n] * scale).toFloat()
            built[range] = data
        }
        return built
    }

    companion object {
        /** Cents between two neighbouring tables: three per octave. */
        private const val CENTS_PER_RANGE = 400.0

        private const val INV_LN2 = 1.4426950408889634

        private val basics = ConcurrentHashMap<Long, PeriodicWave>()
        private val ffts = ConcurrentHashMap<Int, Fft>()

        /**
         * The wave of a native oscillator `type` at [sampleRate]. Built on first use (3 to 30 ms, about 2.4 MB for the four waves at 48 kHz) and shared; call
         * it once when a sound is created, not per block.
         */
        fun basic(type: BasicWave, sampleRate: Int): PeriodicWave =
            basics.computeIfAbsent((sampleRate.toLong() shl 8) or type.ordinal.toLong()) {
                val (real, imag) = basicCoefficients(type, tableSize(sampleRate) / 2)
                PeriodicWave(sampleRate, real, imag)
            }

        /**
         * `createPeriodicWave(real, imag)` of Web Audio: `real[k]` and `imag[k]` are the cosine and the sine amplitude of partial `k` (index 0, the DC
         * offset, is ignored; components beyond the first `size / 2` are dropped; NaN and infinities count as 0). The wave is normalised so that its
         * loudest table peaks at 1. It costs one inverse FFT per distinct table (a millisecond for a handful of partials, a few for 2000, with up to 590 KB
         * of tables), so build it when the sound is created and keep it for the next note with the same partials.
         */
        fun custom(real: DoubleArray, imag: DoubleArray, sampleRate: Int): PeriodicWave {
            require(real.size == imag.size) { "real has ${real.size} components and imag ${imag.size}" }
            return PeriodicWave(sampleRate, real, imag)
        }

        /** Entries of a table at [sampleRate]: Chromium's choice, which grows with the rate so that the highest partial keeps its resolution. */
        private fun tableSize(sampleRate: Int): Int {
            require(sampleRate > 0) { "sample rate $sampleRate" }
            return if (sampleRate <= 24000) 2048 else if (sampleRate <= 88200) 4096 else 16384
        }

        /** Partials kept by table [range]: the fraction `2^(-range / 3)` of the `size / 2` that fit below the Nyquist frequency, rounded down. */
        private fun partialsForRange(range: Int, size: Int): Int {
            val centsToCull = range * CENTS_PER_RANGE
            val cullingScale = Math.pow(2.0, -centsToCull / 1200.0)
            return (cullingScale * (size / 2)).toInt()
        }

        private fun fftOf(size: Int): Fft = ffts.computeIfAbsent(size) { Fft(it) }

        private fun finiteOrZero(x: Double): Double = if (x.isNaN() || x.isInfinite()) 0.0 else x

        /** The coefficients of the [half] partials of [type]: all four waves are odd functions of the phase, so they have sine terms only. */
        private fun basicCoefficients(type: BasicWave, half: Int): Pair<DoubleArray, DoubleArray> {
            val imag = DoubleArray(half)
            for (n in 1 until half) {
                val piFactor = 2.0 / (n * Math.PI)
                imag[n] = when (type) {
                    BasicWave.SINE -> if (n == 1) 1.0 else 0.0
                    BasicWave.SQUARE -> if (n and 1 == 1) 2.0 * piFactor else 0.0
                    BasicWave.SAWTOOTH -> if (n and 1 == 1) piFactor else -piFactor
                    BasicWave.TRIANGLE -> if (n and 1 == 1) 2.0 * piFactor * piFactor * (if (((n - 1) shr 1) and 1 == 1) -1.0 else 1.0) else 0.0
                }
            }
            return DoubleArray(half) to imag
        }

        /** `log2(x)` for a positive [x], exact for powers of two (`ln(8) / ln(2)` is not exactly 3, which would put a note on a table boundary on the wrong side). */
        private fun log2(x: Double): Double {
            val exponent = Math.getExponent(x)
            if (exponent < java.lang.Double.MIN_EXPONENT || exponent > java.lang.Double.MAX_EXPONENT) return Math.log(x) * INV_LN2
            return exponent + Math.log(Math.scalb(x, -exponent)) * INV_LN2
        }
    }
}
