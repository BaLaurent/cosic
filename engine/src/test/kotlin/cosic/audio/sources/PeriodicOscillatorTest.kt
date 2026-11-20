package cosic.audio.sources

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PeriodicOscillatorTest {
    private val sr = 48000
    private fun wave(type: BasicWave) = PeriodicWave.basic(type, sr)
    private fun play(type: BasicWave, hz: Double, frames: Int = 4800, bus: PitchBus? = null) =
        renderOscillator(wave(type), hz, frames, bus = bus, fillBus = null)

    /** The frequency of a periodic signal from its upward zero crossings (interpolated between the two frames around each). */
    private fun measuredFrequency(x: FloatArray): Double {
        val crossings = ArrayList<Double>()
        for (i in 1 until x.size) if (x[i - 1] < 0f && x[i] >= 0f) crossings += (i - 1) + (-x[i - 1] / (x[i] - x[i - 1])).toDouble()
        return (crossings.size - 1) * sr / (crossings.last() - crossings.first())
    }

    @Test
    fun aSineRunsAtItsFrequencyAndPeaksAtOne() {
        val x = play(BasicWave.SINE, 440.0)
        assertEquals(440.0, measuredFrequency(x), 0.01)
        assertEquals(1.0f, x.max(), 1e-3f)
        assertEquals(-1.0f, x.min(), 1e-3f)
        assertEquals(0.0f, x[0])
        // the second frame is sin(2 pi 440 / 48000)
        assertEquals(sin(2.0 * PI * 440.0 / sr).toFloat(), x[1], 1e-6f)
        assertEquals(sqrt(0.5), sqrt(x.sumOf { it.toDouble() * it } / x.size), 1e-3)
    }

    @Test
    fun aSawtoothStartsAtZeroAndRepeatsEveryPeriod() {
        val x = play(BasicWave.SAWTOOTH, 100.0) // 480 frames a period
        assertEquals(0.0f, x[0])
        assertTrue(x[1] > 0f && x[100] > x[1], "rising from zero")
        assertEquals(0.0f, x[480], 1e-4f)
        for (i in 0 until 4000) assertEquals(x[i], x[i + 480], 1e-5f, "frame $i")
        // it rises, jumps at the half period (frame 240) and rises on from the bottom
        assertTrue(x[239] > 0.5f && x[241] < -0.5f)
    }

    @Test
    fun aNegativeFrequencyRunsBackwards() {
        for (type in BasicWave.entries) {
            val forward = play(type, 440.0)
            val backward = play(type, -440.0)
            assertTrue(backward.all { !it.isNaN() })
            for (i in forward.indices) assertEquals(-forward[i], backward[i], 3e-6f, "$type frame $i")
        }
    }

    @Test
    fun aFrequencyBeyondTheNyquistFrequencyIsTheNyquistFrequency() {
        for (type in BasicWave.entries) {
            val atNyquist = play(type, 24000.0, 1000)
            assertContentEquals(atNyquist, play(type, 40000.0, 1000), "$type")
            assertContentEquals(play(type, -24000.0, 1000), play(type, -1e9, 1000))
            assertTrue(atNyquist.all { it == 0f }, "no partial fits at the Nyquist frequency")
        }
        // just below it only the fundamental is left, quietly
        val high = play(BasicWave.SAWTOOTH, 23000.0)
        assertTrue(high.max() in 0.05f..0.15f, "${high.max()}")
        // NaN counts as the Nyquist frequency: silence, never NaN
        assertTrue(play(BasicWave.SINE, Double.NaN, 500).all { it == 0f })
        assertTrue(play(BasicWave.SQUARE, Double.POSITIVE_INFINITY, 500).all { it == 0f })
    }

    @Test
    fun theFrequencyInputAddsToTheBaseFrequency() {
        val bus = PitchBus(256)
        val constant = renderOscillator(wave(BasicWave.SAWTOOTH), 440.0, 4800, bus = bus) { b, _, n ->
            for (i in 0 until n) b.frequencyAddHz[i] = 200.0
            b.frequencyActive = true
        }
        assertContentEquals(play(BasicWave.SAWTOOTH, 640.0), constant)
        // the same note with an input that is not quite constant (it alternates by a trillionth of a Hertz): the per-frame path gives the same sound
        val moving = renderOscillator(wave(BasicWave.SAWTOOTH), 440.0, 4800, bus = bus) { b, first, n ->
            for (i in 0 until n) b.frequencyAddHz[i] = 200.0 + 1e-12 * ((first + i) and 1)
            b.frequencyActive = true
        }
        val expected = play(BasicWave.SAWTOOTH, 640.0)
        for (i in expected.indices) assertEquals(expected[i], moving[i], 1e-6f, "frame $i")
        // a frequency that is negative for a while runs the table backwards without a break
        val through = renderOscillator(wave(BasicWave.SINE), 100.0, 4800, bus = bus) { b, first, n ->
            for (i in 0 until n) b.frequencyAddHz[i] = -300.0 * (first + i) / 4800
            b.frequencyActive = true
        }
        assertTrue(through.all { !it.isNaN() && abs(it) <= 1.0001f })
    }

    @Test
    fun theDetuneInputBendsThePitchInCents() {
        val bus = PitchBus(256)
        fun detuned(cents: Double, base: Double) = renderOscillator(wave(BasicWave.SAWTOOTH), base, 4800, bus = bus) { b, _, n ->
            for (i in 0 until n) b.detuneCents[i] = cents
            b.detuneActive = true
        }
        assertContentEquals(play(BasicWave.SAWTOOTH, 880.0), detuned(1200.0, 440.0))
        assertContentEquals(play(BasicWave.SAWTOOTH, 220.0), detuned(-1200.0, 440.0))
        assertContentEquals(play(BasicWave.SAWTOOTH, 440.0 * Math.pow(2.0, 700.0 / 1200.0)), detuned(700.0, 440.0))
        assertContentEquals(play(BasicWave.SAWTOOTH, 440.0), detuned(0.0, 440.0))
        // detune over the Nyquist frequency is the Nyquist frequency (silence)
        assertTrue(detuned(1200.0, 20000.0).all { it == 0f })
        // a moving detune is a pitch that moves: a sine slid up an octave over its length (440 * 2^s for s from 0 to 1) is slower at the start than at the end
        // and averages 440 / ln 2 Hz
        val slide = renderOscillator(wave(BasicWave.SINE), 440.0, 4800, bus = bus) { b, first, n ->
            for (i in 0 until n) b.detuneCents[i] = 1200.0 * (first + i) / 4800
            b.detuneActive = true
        }
        assertTrue(measuredFrequency(slide.copyOfRange(0, 1200)) < measuredFrequency(slide.copyOfRange(3600, 4800)))
        assertEquals(440.0 / Math.log(2.0), measuredFrequency(slide), 8.0)
    }

    @Test
    fun aNaNInAPitchInputSilencesThatFrameAndNothingElse() {
        val bus = PitchBus(256)
        val x = renderOscillator(wave(BasicWave.SAWTOOTH), 440.0, 4800, bus = bus) { b, first, n ->
            for (i in 0 until n) {
                b.frequencyAddHz[i] = if ((first + i) % 100 == 50) Double.NaN else 0.0
                b.detuneCents[i] = if ((first + i) % 100 == 70) Double.NaN else 0.0
            }
            b.frequencyActive = true
            b.detuneActive = true
        }
        assertTrue(x.all { it.isFinite() })
        for (i in x.indices) if (i % 100 == 50 || i % 100 == 70) assertEquals(0f, x[i], "frame $i")
        // the frames in between are a sawtooth, whose phase was only moved by the Nyquist-frequency step at each NaN
        assertTrue(x.count { it != 0f } > 4000)
    }

    @Test
    fun theOutputDoesNotDependOnTheBlockSize() {
        fun vibrato(blockSize: Int): FloatArray {
            val bus = PitchBus(blockSize)
            return renderOscillator(wave(BasicWave.TRIANGLE), 330.0, 4800, blockSize, bus) { b, first, n ->
                for (i in 0 until n) {
                    val t = (first + i).toDouble() / sr
                    b.detuneCents[i] = 60.0 * sin(2.0 * PI * 5.0 * t)
                    b.frequencyAddHz[i] = 40.0 * cos(2.0 * PI * 3.0 * t)
                }
                b.detuneActive = true
                b.frequencyActive = true
            }
        }
        val reference = vibrato(256)
        for (size in intArrayOf(1, 7, 100, 128, 300, 1000, 4800)) assertContentEquals(reference, vibrato(size), "block of $size")
        for (size in intArrayOf(1, 33, 4800)) {
            assertContentEquals(play(BasicWave.SAWTOOTH, 1234.5), renderOscillator(wave(BasicWave.SAWTOOTH), 1234.5, 4800, size), "block of $size, no bus")
        }
    }

    @Test
    fun resetStartsTheTableAgain() {
        val osc = PeriodicOscillator(wave(BasicWave.SAWTOOTH))
        val first = FloatArray(100)
        val second = FloatArray(100)
        osc.render(first, 100, 440.0, null)
        osc.render(second, 100, 440.0, null)
        assertFalse(first.contentEquals(second), "the second block continues the wave")
        osc.reset()
        val again = FloatArray(100)
        osc.render(again, 100, 440.0, null)
        assertContentEquals(first, again)
        osc.render(FloatArray(10), 0, 440.0, null) // nothing to render
    }

    @Test
    fun theBaseFrequencyMayChangeBetweenBlocksWithoutABreakInPhase() {
        val osc = PeriodicOscillator(wave(BasicWave.SINE))
        val a = FloatArray(1000)
        val b = FloatArray(1000)
        osc.render(a, 1000, 300.0, null)
        osc.render(b, 1000, 600.0, null)
        // the phase after the first block is 1000 * 300 / 48000 = 6.25 periods: the next block starts a quarter period in, at the peak
        assertEquals(1.0f, b[0], 1e-4f)
        assertEquals(sin(2.0 * PI * (6.25 + 600.0 / sr)).toFloat(), b[1], 1e-5f)
    }

    private fun worstError(c: OscillatorOracleCase, mine: FloatArray): Pair<Double, Int> {
        var worst = 0.0
        var worstAt = 0
        for (i in c.values.indices) {
            val frame = c.frameOf(i)
            val error = abs(mine[frame] - c.values[i]).toDouble()
            if (error > worst) {
                worst = error
                worstAt = frame
            }
        }
        return worst to worstAt
    }

    @Test
    fun chromiumsOscillatorsAreReproduced() {
        val failures = ArrayList<String>()
        for (c in OscillatorOracleVectors.cases) {
            val mine = renderOracleCase(c)
            assertEquals(c.frames, mine.size)
            val limit = pointTolerance(c)
            val (worst, worstAt) = worstError(c, mine)
            val rms = sqrt(mine.sumOf { it.toDouble() * it } / mine.size)
            if (worst > limit) failures += "${c.name}: error ${"%.2e".format(worst)} at frame $worstAt, over ${"%.0e".format(limit)}"
            if (abs(rms - c.rms) > maxOf(c.rms, 0.01) * 1e-4) failures += "${c.name}: rms ${"%.6f".format(rms)} against ${"%.6f".format(c.rms)}"
        }
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    /**
     * How far a frame may be from Chromium's. The oscillators agree to float32 precision: 3e-5 for the smooth waves. A sawtooth or a square has steep
     * edges, where a frame or two a period are off by up to 3.4e-4 (measured over every frame of a 65 Hz sawtooth; the sampled frames of the oracle are
     * off by at most 2.1e-4), as if the read position differed by a thousandth of a table entry. The tables, the choice of tables and the phase increment
     * match Chromium's (checked separately), so presumably it is the float32 arithmetic of Chromium's read position; the tolerance there is 5e-4. The slow
     * cases are half a second long, so the same drift reaches 6e-5 at their edges; the interpolation order of those is checked by
     * [theInterpolationOrderFollowsTheStepOfTheReadIndex]. Frequency modulation with a large index amplifies the differences of the modulator's own
     * float32 phase.
     */
    private fun pointTolerance(c: OscillatorOracleCase): Double = when {
        c.fmGain != 0.0 -> 1e-3
        isSlow(c) -> 2e-4
        c.wave == "sine" || c.wave == "triangle" -> 3e-5
        else -> 5e-4
    }

    private fun isSlow(c: OscillatorOracleCase) =
        c.frequencyHz in -4.0..4.0 && c.fmGain == 0.0 && c.vibratoCents == 0.0 && c.detuneCents == 0.0 && c.rampEndSec == 0.0

    /** The oracle case (one constant frequency) read with a Lagrange polynomial through [points] table entries, whose weights come from the definition. */
    private fun readWithLagrange(c: OscillatorOracleCase, points: Int): FloatArray {
        val wave = c.wave()
        val selection = TableSelection().also { wave.select(c.frequencyHz, it) }
        val higher = wave.table(selection.higher)
        val lower = wave.table(selection.lower)
        val first = when (points) { // the polynomial runs through the entries first .. first + points - 1 of the integer part of the position
            2 -> 0
            3 -> -1
            5 -> -2
            else -> error("$points points")
        }
        val mask = wave.size - 1
        val incr = c.frequencyHz * wave.rateScale
        val out = FloatArray(c.frames)
        var index = 0.0
        for (n in out.indices) {
            val i0 = index.toInt()
            val t = index - i0
            var h = 0.0
            var l = 0.0
            for (k in 0 until points) {
                var weight = 1.0
                for (j in 0 until points) if (j != k) weight *= (t - (first + j)) / (k - j).toDouble()
                h += weight * higher[(i0 + first + k) and mask]
                l += weight * lower[(i0 + first + k) and mask]
            }
            out[n] = ((1.0 - selection.factor) * h + selection.factor * l).toFloat()
            index += incr
            index -= Math.floor(index / wave.size) * wave.size
        }
        return out
    }

    @Test
    fun theInterpolationOrderFollowsTheStepOfTheReadIndex() {
        // 0.16 and 0.3 table entries a frame are 1.875 Hz and 3.5156 Hz at 48 kHz: a 5-point polynomial below the first, 3 points up to the second, then
        // 2 (linear). The squares cross their edges, where the wrong order is off by 0.08 to 0.15; the sawtooth only shows a few millionths (the right
        // order is 2e-7 off, the others 3e-6 and 7e-6), which is what the factor of ten asks for
        val expected = mapOf("saw0_5" to 5, "square1_8" to 5, "square2_2" to 3, "square3_4" to 3, "square3_6" to 2)
        val cases = OscillatorOracleVectors.cases.filter { it.name in expected }
        assertEquals(expected.keys, cases.map { it.name }.toSet())
        for (c in cases) {
            val errors = intArrayOf(2, 3, 5).associateWith { worstError(c, readWithLagrange(c, it)).first }
            val right = errors.getValue(expected.getValue(c.name))
            for ((points, error) in errors) if (points != expected[c.name]) assertTrue(error > 10 * right, "${c.name}: $points points ${"%.2e".format(error)}, right order ${"%.2e".format(right)}")
        }
    }

    @Test
    fun renderingDoesNotAllocate() {
        val saw = PeriodicOscillator(wave(BasicWave.SAWTOOTH))
        val sine = PeriodicOscillator(wave(BasicWave.SINE))
        val slow = PeriodicOscillator(wave(BasicWave.SQUARE))
        val bus = PitchBus(256)
        val out = FloatArray(256)
        fun work() {
            for (block in 0 until 100) {
                bus.clear(256)
                for (i in 0 until 256) {
                    bus.detuneCents[i] = 40.0 * sin(0.01 * (block * 256 + i))
                    bus.frequencyAddHz[i] = 15.0 * cos(0.02 * i)
                }
                bus.detuneActive = true
                bus.frequencyActive = true
                saw.render(out, 256, 300.0, bus)
                bus.clear(256)
                sine.render(out, 256, 440.0, null)
                slow.render(out, 256, 1.0, null) // the 5-point path
                slow.render(out, 256, 2.5, null) // the 3-point path
                saw.render(out, 256, -50.0, bus) // inactive bus
            }
        }
        repeat(5) { work() }
        assertEquals(0L, steadyAllocation { work() })
    }
}
