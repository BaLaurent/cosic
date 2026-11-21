package cosic.kabel

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Hand-computed vectors for the graph language, the compiler and the ugens (docs/parity/spec/kabelsalat-port.md section 4.1), all through `.out()` so
 * that both output channels carry the same signal. The numbers were derived from the sources of @kabelsalat/lib@0.4.1 by hand; the Chromium-free oracle of the real
 * compiler and worklet is KabelGoldenTest.
 */
class KabelVectorsTest {
    private fun assertConstant(code: String, expected: Double, tol: Double = 1e-9) {
        val out = renderKabel(code, 48000, 3)
        for (i in 0 until 3) {
            assertEquals(expected, out[0][i], tol, "left sample $i of $code")
            assertEquals(expected, out[1][i], tol, "right sample $i of $code")
        }
    }

    private fun assertSamples(code: String, expected: List<Double>, sampleRate: Int, tol: Double = 1e-9, rng: KabelRandom = FixedRandom(0.0)) {
        val out = renderKabel(code, sampleRate, expected.size, rng = rng)
        for (i in expected.indices) assertEquals(expected[i], out[0][i], tol, "sample $i of $code")
        assertContentEquals(out[0], out[1], "both channels of $code")
    }

    // ---- math and logic ----------------------------------------------------------------------------------------------

    @Test
    fun arithmetic() {
        assertConstant("n(2).mul(3).add(1).out()", 7.0)
        assertConstant("n(7).sub(2).div(2).out()", 2.5)
        assertConstant("n(-7).mod(4).out()", -3.0)
        assertConstant("n(2).pow(10).out()", 1024.0)
    }

    @Test
    fun rounding() {
        assertConstant("n(2.5).round().out()", 3.0)
        assertConstant("n(-2.5).round().out()", -2.0)
        assertConstant("n(0.5).round().out()", 1.0)
        assertConstant("n(1.5).floor().out()", 1.0)
        assertConstant("n(1.2).ceil().out()", 2.0)
        assertConstant("n(-3).sign().out()", -1.0)
    }

    @Test
    fun clampAndRange() {
        assertConstant("n(5).clamp(-1, 2).out()", 2.0)
        assertConstant("n(5).clamp().out()", 1.0)
        assertConstant("n(0).range(10, 20).out()", 15.0)
        assertConstant("n(0).range(0, 100, 2).out()", 25.0)
        assertConstant("n(69).midinote().out()", 440.0)
    }

    @Test
    fun variadicComparisons() {
        assertConstant("min(n(3), n(1), n(2)).out()", 1.0)
        assertConstant("max(n(3), n(1), n(2)).out()", 3.0)
        assertConstant("argmin(n(3), n(1), n(2)).out()", 1.0)
        assertConstant("argmax(n(3), n(1), n(2)).out()", 0.0)
    }

    @Test
    fun logic() {
        assertConstant("n(2).greater(1).out()", 1.0)
        assertConstant("xor(n(1), n(1)).out()", 0.0)
        assertConstant("and(n(1), n(0)).out()", 0.0)
        assertConstant("or(n(0), n(1)).out()", 1.0)
        assertConstant("not(n(0)).out()", 1.0)
        assertConstant("bool(n(3)).out()", 1.0)
        assertConstant("ifelse(n(1), 10, 20).out()", 10.0)
        assertConstant("ifelse(n(2), 10, 20).out()", 20.0)
    }

    @Test
    fun aComparisonIsABooleanThatIsNeverOneOrZero() {
        assertConstant("ifelse(greater(n(2), 1), 10, 20).out()", 20.0)
        assertConstant("not(greater(n(2), 1)).out()", 0.0)
        assertConstant("not(lower(n(2), 1)).out()", 0.0)
        assertConstant("bool(greater(n(0), 1)).out()", 1.0)
        assertConstant("greater(n(2), 1).mul(7).out()", 7.0) // arithmetic reads it as 1
    }

    @Test
    fun bipolarUnipolarAndRangex() {
        assertConstant("n(0.25).bipolar().out()", -0.5)
        assertConstant("n(0).unipolar().out()", 0.5)
        assertConstant("n(0).rangex(100, 10000).out()", 1000.0)
    }

    @Test
    fun aSawStartsAtItsRandomPhase() {
        assertConstant("saw().out()", -1.0)
    }

    // ---- oscillators -------------------------------------------------------------------------------------------------

    @Test
    fun zawPulseTriAndSine() {
        assertSamples("zaw(1).out()", listOf(-0.75, -0.5, -0.25, 0.0, 0.25, 0.5, 0.75, -1.0), 8)
        assertSamples("pulse(1, 0.5).out()", listOf(1.0, 1.0, 1.0, -1.0, -1.0, -1.0, -1.0, 1.0), 8)
        assertSamples("tri(1).out()", listOf(-0.5, 0.0, 0.5, 1.0, 0.5, 0.0, -0.5, -1.0), 8)
        val h = sqrt(0.5)
        assertSamples("sine(1).out()", listOf(0.0, h, 1.0, h, 0.0, -h, -1.0, -h), 8, tol = 1e-9)
    }

    @Test
    fun aSawWithPolyBlep() {
        assertSamples("saw(1).out()", listOf(0.0, -0.75, -0.5, -0.25, 0.0, 0.25, 0.5, 0.75, 0.0, -0.75), 8)
    }

    @Test
    fun impulseClockAndTime() {
        assertSamples("impulse(1).out()", listOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0), 4)
        assertSamples("clock(120).out()", listOf(-1.0, 1.0, -1.0, 1.0), 96)
        assertSamples("time().out()", listOf(0.0, 0.25, 0.5, 0.75), 4)
    }

    @Test
    fun aPolyOfFrequenciesIsTwoChannels() {
        val out = renderKabel("zaw([1, 2]).out()", 8, 8)
        assertContentEquals(doubleArrayOf(-0.75, -0.5, -0.25, 0.0, 0.25, 0.5, 0.75, -1.0), out[0])
        assertContentEquals(doubleArrayOf(-0.5, 0.0, 0.5, -1.0, -0.5, 0.0, 0.5, -1.0), out[1])
    }

    // ---- noise ---------------------------------------------------------------------------------------------------------

    @Test
    fun lcgNoiseFollowsItsSeed() {
        val out = kabelOut("rng().out()", 2)
        assertEquals(-11208378.0 / 16777216.0, out[0], 1e-12)
        assertEquals(-11111604.0 / 16777216.0, out[1], 1e-12)
        assertEquals(0.0, kabelOut("rng(0).out()", 1)[0])
    }

    @Test
    fun everyLcgNoiseHasItsOwnSeed() {
        val out = kabelOut("rng().add(rng()).out()", 1)
        assertEquals(2163988.0 / 16777216.0, out[0], 1e-12)
        val runtime = KabelRuntime()
        renderKabel("rng().add(rng()).out()", 48000, 1, runtime = runtime)
        assertEquals(2, runtime.noiseChannels)
        val program = compileKabel("rng().add(rng()).out()")
        val second = KabelInstance(program, 48000, KabelRuntime().also { it.noiseChannels = 1 }, FixedRandom(0.0))
        second.sample(0.0)
        // the seeds of a runtime that has made one generator are 680 and 1020
        assertTrue(second.left != out[0])
    }

    // ---- envelopes -----------------------------------------------------------------------------------------------------

    @Test
    fun adsr() {
        assertSamples("n(1).adsr(0.25, 0.2, 0.5, 0.4).out()", listOf(0.0, 0.4, 0.8, 1.0, 0.75, 0.5, 0.5, 0.5), 10)
        assertSamples("ad(n(1), 0.25, 0.2).out()", listOf(0.0, 0.4, 0.8, 1.0, 0.5, 0.0, 0.0, 0.0), 10)
    }

    @Test
    fun adsrReleasesWhenTheGateEnds() {
        val out = renderKabel("cc('strudel-gate-1').adsr(0.25, 0.2, 0.5, 0.4).out()", 10, 12, gateOffAt = 8)[0]
        assertEquals(0.5, out[8], 1e-9)
        assertEquals(0.375, out[9], 1e-9)
        assertEquals(0.25, out[10], 1e-9)
        assertEquals(0.125, out[11], 1e-9)
    }

    // ---- filters -------------------------------------------------------------------------------------------------------

    @Test
    fun twoPoleFilters() {
        assertSamples("n(1).lpf(1, 0).out()", listOf(1.0, 1.0, 0.75, 0.75, 0.8125), 48000)
        assertSamples("n(1).bpf(1, 0).out()", listOf(1.0, 0.5, 0.25, 0.375, 0.4375), 48000)
        assertSamples("n(1).hpf(1, 0).out()", listOf(0.0, 0.0, 0.25, 0.25, 0.1875), 48000)
    }

    @Test
    fun aNaNInAFilterKillsTheVoice() {
        val instance = KabelInstance(compileKabel("n(0).div(0).lpf(0.5).out()"), 48000, KabelRuntime(), FixedRandom(0.0))
        instance.sample(0.0)
        assertTrue(instance.dead)
    }

    // ---- shapers and smoothers ---------------------------------------------------------------------------------------------

    @Test
    fun shapers() {
        assertConstant("n(0.5).distort(0.5).out()", 0.745)
        assertConstant("n(0.5).fold(0).out()", 0.5)
        assertConstant("n(0.75).fold(1).out()", 0.5)
        assertConstant("n(5).clip(-1, 2).out()", 2.0)
    }

    @Test
    fun smoothers() {
        val lag = kabelOut("n(1).lag(1).out()", 2)
        assertEquals(1.0 / 4410, lag[0], 1e-15)
        assertEquals(8819.0 / 19448100, lag[1], 1e-15)
        assertEquals(1.0, kabelOut("n(1).lag(0).out()", 2)[1])
        assertSamples("n(1).slide(0.002).out()", listOf(0.5, 0.75, 0.875), 48000)
        assertSamples("n(1).slew(24000, 24000).out()", listOf(0.5, 1.0, 1.0), 48000, tol = 1e-12)
    }

    // ---- delay -------------------------------------------------------------------------------------------------------------

    @Test
    fun aDelayRepeatsItsInputLater() {
        assertSamples("n(1).delay(0.3).out()", listOf(0.0, 0.0, 0.0, 1.0, 1.0, 1.0), 10)
    }

    @Test
    fun aDelayInAFeedbackLoop() {
        assertSamples(
            "n(1).add(x => x.delay(0.2).mul(0.5)).out()", listOf(1.0, 1.0, 1.0, 1.5, 1.5, 1.5, 1.75, 1.75, 1.75, 1.875), 10,
        )
    }

    @Test
    fun aConstantDelayIsExactInASmallRingAndADynamicOneInTheLargeOne() {
        val small = compileKabel("n(1).delay(0.3).out()", 10)
        val large = compileKabel("n(1).delay(n(0.3).mul(1)).out()", 10)
        assertEquals(5, small.delayLens[small.ugens.indexOfFirst { it.type == "Delay" }])
        assertEquals(100, large.delayLens[large.ugens.indexOfFirst { it.type == "Delay" }])
        assertSamples("n(1).delay(n(0.3).mul(1)).out()", listOf(0.0, 0.0, 0.0, 1.0, 1.0, 1.0), 10)
    }

    // ---- sequencing ----------------------------------------------------------------------------------------------------------

    @Test
    fun sequencing() {
        // the clock rises at samples 7, 15 and 23: the step changes there, with a zero for the first sample of the step
        val steps = DoubleArray(24) { i -> if (i == 7 || i == 15 || i == 23) 0.0 else if (i < 15) (if (i < 7) 10.0 else 20.0) else 30.0 }
        assertContentEquals(steps, renderKabel("pulse(1, 0.5).seq(10, 20, 30).out()", 8, 24)[0])
        val trig = renderKabel("pulse(1, 0.5).trig().out()", 8, 24)[0]
        assertContentEquals(DoubleArray(24) { if (it == 0 || it == 7 || it == 15 || it == 23) 1.0 else 0.0 }, trig)
        val hold = renderKabel("time().hold(pulse(1, 0.5)).out()", 8, 16)[0]
        assertContentEquals(DoubleArray(16) { if (it < 7) 0.0 else if (it < 15) 0.875 else 1.875 }, hold)
        val div = renderKabel("pulse(1, 0.5).clockdiv(2).out()", 8, 16)[0]
        assertContentEquals(DoubleArray(16) { if (it < 7) 1.0 else if (it < 15) -1.0 else 1.0 }, div)
        assertConstant("n(1).pick(10, 20, 30).out()", 20.0)
        assertConstant("n(-1).pick(10, 20, 30).out()", 30.0)
        assertConstant("n(0).remap(-1, 1, 0, 100).out()", 50.0)
    }

    // ---- multichannel ------------------------------------------------------------------------------------------------------------

    @Test
    fun mixingAPolyDownToTwoChannels() {
        val out = renderKabel("mul([1, 2, 3], 1).mix(2).out()", 48000, 1)
        assertEquals(2.414213562373095, out[0][0], 1e-12)
        assertEquals(4.414213562373095, out[1][0], 1e-12)
    }

    @Test
    fun panning() {
        val left = renderKabel("n(1).pan(-1).out()", 48000, 1)
        assertEquals(1.0, left[0][0], 1e-12)
        assertEquals(0.0, left[1][0], 1e-12)
        val middle = renderKabel("n(1).pan(0).out()", 48000, 1)
        assertEquals(sqrt(0.5), middle[0][0], 1e-12)
        assertEquals(sqrt(0.5), middle[1][0], 1e-12)
    }

    // ---- constants and quirks --------------------------------------------------------------------------------------------------------

    @Test
    fun theMidiAndMouseUgensAreConstants() {
        assertConstant("midifreq().add(mouseX).add(midicc(74)).mul(0.1).out()", -0.1)
    }

    @Test
    fun onlyTheLastGateIsSwitchedOff() {
        val code = "cc('strudel-gate-1').add(cc('strudel-gate-1')).out()"
        val out = renderKabel(code, 48000, 4, gateOffAt = 2)[0]
        assertEquals(listOf(2.0, 2.0, 1.0, 1.0), out.toList())
    }

    // ---- the program -----------------------------------------------------------------------------------------------------------------

    @Test
    fun theCompiledProgramMatchesWhatTheOriginalMakes() {
        // docs/parity/spec/kabelsalat-port.md 1.2: nodes [n220, saw, n0.5, mul, n0, out0, n1, out1, exit]
        val p = compileKabel("saw(220).mul(0.5).out()")
        assertEquals(9, p.nodeCount)
        assertEquals(listOf("SawOsc", "Output", "Output"), p.ugens.map { it.type })
    }
}
