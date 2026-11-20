package cosic.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Plan step 4 "done when" checks plus the building blocks behind them. */
class DspTest {

    // ------------------------------------------------------------------ filters

    private fun filterGainDb(kind: FilterKind, cutoff: Double, q: Double, freq: Double): Double {
        val input = sineWave(freq, 1.0, 0.5)
        val output = input.copyOf()
        SvFilter(SR, kind).also { it.configure(cutoff, q) }.process(output, output.size)
        return dbOf(rms(output, 4800) / rms(input, 4800))
    }

    @Test
    fun lowPassCutsAFiveKilohertzSineByMoreThan20dB() {
        val attenuation = filterGainDb(FilterKind.LOW_PASS, 500.0, 0.707, 5000.0)
        assertTrue(attenuation < -20.0, "5 kHz sine was attenuated by only ${-attenuation} dB")
    }

    @Test
    fun lowPassPassesFrequenciesBelowTheCutoffAndRingsAtHighQ() {
        assertEquals(0.0, filterGainDb(FilterKind.LOW_PASS, 500.0, 0.707, 100.0), 0.5)
        // At the cutoff a low-pass has gain Q (Butterworth: -3 dB).
        assertEquals(-3.01, filterGainDb(FilterKind.LOW_PASS, 1000.0, 1.0 / sqrt(2.0), 1000.0), 0.1)
        assertEquals(20.0, filterGainDb(FilterKind.LOW_PASS, 1000.0, 10.0, 1000.0), 0.3)
    }

    @Test
    fun highPassCutsLowFrequenciesAndPassesHighOnes() {
        assertEquals(0.0, filterGainDb(FilterKind.HIGH_PASS, 1000.0, 0.707, 8000.0), 0.5)
        assertTrue(filterGainDb(FilterKind.HIGH_PASS, 1000.0, 0.707, 100.0) < -30.0)
    }

    @Test
    fun bandPassHasUnityGainAtTheCenterAndRejectsFarFrequencies() {
        assertEquals(0.0, filterGainDb(FilterKind.BAND_PASS, 1000.0, 4.0, 1000.0), 0.3)
        assertTrue(filterGainDb(FilterKind.BAND_PASS, 1000.0, 4.0, 100.0) < -25.0)
        assertTrue(filterGainDb(FilterKind.BAND_PASS, 1000.0, 4.0, 10_000.0) < -25.0)
    }

    @Test
    fun filtersStayFiniteAtExtremeSettings() {
        val noise = FloatArray(SR / 2).also { Oscillator(SR).apply { start(Waveform.WHITE, 0.0, 3) }.fill(it, it.size) }
        for (kind in FilterKind.entries) {
            for (cutoff in doubleArrayOf(-5.0, 0.0, 1.0, 20.0, 1e4, 1e9, Double.POSITIVE_INFINITY)) {
                for (q in doubleArrayOf(-1.0, 0.0, 1e-6, 0.707, 30.0, 1e9)) {
                    val buffer = noise.copyOf()
                    SvFilter(SR, kind).also { it.configure(cutoff, q) }.process(buffer, buffer.size)
                    assertTrue(allFinite(buffer), "$kind cutoff=$cutoff q=$q produced NaN or infinity")
                    assertTrue(peak(buffer) < 1000f, "$kind cutoff=$cutoff q=$q blew up to ${peak(buffer)}")
                }
            }
        }
    }

    // ----------------------------------------------------------------- envelope

    private fun adsr(gate: Double = 0.2) =
        Envelope(SR).also { it.start(attack = 0.01, decay = 0.02, sustain = 0.5, release = 0.03, gateSeconds = gate) }

    private fun Envelope.take(n: Int) = FloatArray(n) { next() }

    @Test
    fun adsrRampsLinearlyToFullLevelAtTheEndOfTheAttack() {
        val levels = adsr().take(1000)
        assertEquals(0.5f, levels[239], 0.01f)
        assertEquals(1.0f, levels[479], 0.01f)
        assertTrue(levels.max() <= 1.0f, "level overshot to ${levels.max()}")
    }

    @Test
    fun adsrDecaysToTheSustainLevelAndHoldsIt() {
        val levels = adsr().take(9000)
        assertEquals(0.75f, levels[480 + 479], 0.01f)
        assertEquals(0.5f, levels[480 + 959], 0.01f)
        for (i in 2000 until 9000) assertEquals(0.5f, levels[i], 1e-6f)
    }

    @Test
    fun adsrReleasesLinearlyToZeroWhenTheGateEnds() {
        val env = adsr(gate = 0.2) // 9600 frames
        val levels = env.take(12_000)
        assertEquals(0.5f, levels[9599], 1e-6f)
        assertEquals(0.25f, levels[9600 + 719], 0.01f)
        assertEquals(0f, levels[9600 + 1439], 1e-3f)
        assertTrue(env.isDone)
        for (i in 9600 + 1440 until 12_000) assertEquals(0f, levels[i])
    }

    @Test
    fun aGateShorterThanTheAttackReleasesFromWhereTheAttackReached() {
        val env = Envelope(SR).also { it.start(0.01, 0.02, 0.5, 0.03, 0.005) } // gate = 240 frames
        val levels = env.take(2000)
        assertEquals(0.5f, levels.max(), 0.01f)
        assertEquals(0f, levels[240 + 1439], 1e-3f)
        assertTrue(env.isDone)
    }

    @Test
    fun zeroAttackAndDecayStartAtTheSustainLevel() {
        val env = Envelope(SR).also { it.start(0.0, 0.0, 0.7, 0.0, 1.0) }
        assertEquals(0.7f, env.next(), 1e-6f)
    }

    @Test
    fun aForcedFadeSilencesTheEnvelopeInTheRequestedFrames() {
        val env = Envelope(SR).also { it.start(0.001, 0.0, 1.0, 0.5, Double.POSITIVE_INFINITY) }
        env.take(1000)
        assertFalse(env.isFading)
        env.fade(240)
        assertTrue(env.isFading)
        val levels = env.take(300)
        assertEquals(0.5f, levels[119], 0.01f)
        assertEquals(0f, levels[239], 1e-3f)
        assertTrue(env.isDone)
    }

    @Test
    fun aFadeNeverLengthensAShorterRelease() {
        val env = Envelope(SR).also { it.start(0.0, 0.0, 1.0, 0.002, 0.01) } // gate 480 frames, release 96 frames
        env.take(490) // ten frames into the release
        env.fade(4800)
        env.take(200)
        assertTrue(env.isDone, "fade(4800) must not stretch the 96-frame release")
    }

    // ------------------------------------------------------------------ limiter

    private fun loudSine(amplitude: Double, seconds: Double) = sineWave(220.0, seconds, amplitude)

    @Test
    fun limiterNeverExceedsFullScaleOnAPlus12dBInput() {
        val left = loudSine(10.0.pow(12.0 / 20.0), 1.0)
        val right = left.copyOf()
        Limiter(SR).process(left, right, left.size)
        assertTrue(peak(left) <= 1.0f, "left peaked at ${peak(left)}")
        assertTrue(peak(right) <= 1.0f, "right peaked at ${peak(right)}")
    }

    @Test
    fun limiterHoldsASettledLoudSignalNearThresholdInsteadOfClippingIt() {
        val left = loudSine(10.0.pow(12.0 / 20.0), 1.0)
        val right = left.copyOf()
        Limiter(SR).process(left, right, left.size)
        val settled = peak(left, SR / 2)
        assertTrue(settled in 0.85f..1.0f, "settled peak $settled")
        var clipped = 0
        for (i in SR / 2 until left.size) if (abs(left[i]) >= 1.0f) clipped++
        assertTrue(clipped < (left.size - SR / 2) / 100, "$clipped samples sit on the hard clip")
    }

    @Test
    fun limiterLeavesQuietSignalsUntouchedAndRecoversAfterAPeak() {
        val quiet = loudSine(0.5, 0.5)
        val untouched = quiet.copyOf()
        Limiter(SR).process(untouched, untouched.copyOf(), untouched.size)
        assertContentEquals(quiet, untouched)

        val loudThenQuiet = loudSine(4.0, 0.2) + loudSine(0.5, 1.0)
        val right = loudThenQuiet.copyOf()
        Limiter(SR).process(loudThenQuiet, right, loudThenQuiet.size)
        assertEquals(0.5f, peak(loudThenQuiet, loudThenQuiet.size - SR / 10), 0.01f, "gain did not recover")
    }

    @Test
    fun limiterTurnsNaNIntoSilenceWithoutPoisoningItsState() {
        val left = floatArrayOf(0.5f, Float.NaN, 0.5f)
        val right = floatArrayOf(0.5f, 0.5f, Float.POSITIVE_INFINITY)
        Limiter(SR).process(left, right, 3)
        assertTrue(allFinite(left) && allFinite(right))
        assertTrue(peak(left) <= 1f && peak(right) <= 1f)
        assertEquals(0.5f, left[0])
    }

    // -------------------------------------------------------------- oscillators

    private fun noteHz(note: Double) = 440.0 * 2.0.pow((note - 69.0) / 12.0)

    private fun oscillate(waveform: Waveform, hz: Double, frames: Int, seed: Int = 7, sr: Int = SR): FloatArray {
        val osc = Oscillator(sr).also { it.start(waveform, hz, seed) }
        return FloatArray(frames).also { osc.fill(it, frames) }
    }

    @Test
    fun everyOscillatorStaysFiniteAndBoundedForTenSeconds() {
        val osc = Oscillator(SR)
        val block = FloatArray(4800)
        for (waveform in Waveform.entries) {
            for (note in doubleArrayOf(0.0, 36.0, 69.0, 96.0, 127.0)) {
                osc.start(waveform, noteHz(note), 99)
                var worst = 0f
                var finite = true
                repeat(100) { // 100 blocks of 0.1 s
                    osc.fill(block, block.size)
                    for (x in block) if (x.isFinite()) worst = max(worst, abs(x)) else finite = false
                }
                assertTrue(finite, "$waveform at note $note produced NaN or infinity")
                assertTrue(worst in 0.05f..2.0f, "$waveform at note $note peaked at $worst")
            }
        }
    }

    @Test
    fun tonalOscillatorsRunAtTheRequestedFrequency() {
        for (sr in intArrayOf(44_100, 48_000)) {
            val cases = listOf(
                Waveform.SINE to 440.0,
                Waveform.SAWTOOTH to 220.0,
                Waveform.SQUARE to 330.0,
                Waveform.TRIANGLE to 262.0,
            )
            for ((waveform, hz) in cases) {
                val x = oscillate(waveform, hz, sr, sr = sr)
                assertEquals(hz, estimateFrequency(x, 0, x.size, sr), 0.2, "$waveform at $sr Hz")
            }
        }
    }

    @Test
    fun tonalOscillatorsHaveUnitPeak() {
        assertEquals(1.0f, peak(oscillate(Waveform.SINE, 220.0, SR)), 0.001f)
        assertEquals(1.0f, peak(oscillate(Waveform.TRIANGLE, 220.0, SR)), 0.01f)
        assertTrue(peak(oscillate(Waveform.SAWTOOTH, 220.0, SR)) in 0.95f..1.1f)
        assertTrue(peak(oscillate(Waveform.SQUARE, 220.0, SR)) in 0.95f..1.05f)
    }

    @Test
    fun sineOscillatorIsSpectrallyPure() {
        val x = oscillate(Waveform.SINE, 440.0, SR) // exactly 440 cycles
        var cosSum = 0.0
        var sinSum = 0.0
        for (i in x.indices) {
            val phase = 2.0 * PI * 440.0 * i / SR
            cosSum += x[i] * cos(phase)
            sinSum += x[i] * sin(phase)
        }
        val a = 2.0 * cosSum / x.size
        val b = 2.0 * sinSum / x.size
        var fundamental = 0.0
        var residual = 0.0
        for (i in x.indices) {
            val phase = 2.0 * PI * 440.0 * i / SR
            val fit = a * cos(phase) + b * sin(phase)
            fundamental += fit * fit
            residual += (x[i] - fit) * (x[i] - fit)
        }
        val distortion = dbOf(sqrt(residual / fundamental))
        assertTrue(distortion < -100.0, "table sine distortion $distortion dB")
    }

    @Test
    fun oscillatorsStartAtZeroSoNoteOnsetsDoNotClick() {
        for (waveform in listOf(Waveform.SINE, Waveform.SAWTOOTH, Waveform.SQUARE, Waveform.TRIANGLE)) {
            assertEquals(0f, oscillate(waveform, 220.0, 4)[0], 1e-6f, "$waveform")
        }
    }

    @Test
    fun polyBlepSmoothsTheSawtoothAndSquareEdges() {
        for (waveform in listOf(Waveform.SAWTOOTH, Waveform.SQUARE)) {
            val x = oscillate(waveform, 123.4, SR)
            var jump = 0f
            for (i in 1 until x.size) jump = max(jump, abs(x[i] - x[i - 1]))
            assertTrue(jump < 1.6f, "$waveform steps by $jump between samples; a naive edge steps by 2.0")
        }
    }

    @Test
    fun whiteNoiseIsUniformAndSeededDeterministically() {
        val a = oscillate(Waveform.WHITE, 0.0, SR, seed = 5)
        assertContentEquals(a, oscillate(Waveform.WHITE, 0.0, SR, seed = 5))
        assertFalse(a.contentEquals(oscillate(Waveform.WHITE, 0.0, SR, seed = 6)))
        assertTrue(peak(a) <= 1.0f)
        assertEquals(0.577, rms(a), 0.01) // uniform in [-1, 1)
        assertTrue(abs(a.average()) < 0.01)
    }

    @Test
    fun noiseColoursTiltTowardsTheLowEnd() {
        fun lowFraction(waveform: Waveform) = energyBelow(oscillate(waveform, 0.0, SR * 2), 500.0)
        assertTrue(lowFraction(Waveform.WHITE) < 0.06, "white ${lowFraction(Waveform.WHITE)}")
        assertTrue(lowFraction(Waveform.PINK) in 0.15..0.7, "pink ${lowFraction(Waveform.PINK)}")
        assertTrue(lowFraction(Waveform.BROWN) > 0.8, "brown ${lowFraction(Waveform.BROWN)}")
    }

    @Test
    fun synthNamesResolveIncludingAliases() {
        assertEquals(listOf("sine", "sawtooth", "square", "triangle", "white", "pink", "brown"), SYNTH_NAMES)
        for (name in SYNTH_NAMES) assertNotNull(Waveform.byName(name), name)
        assertEquals(Waveform.SAWTOOTH, Waveform.byName("saw"))
        assertEquals(Waveform.TRIANGLE, Waveform.byName("tri"))
        assertNull(Waveform.byName("bd"))
        assertNull(Waveform.byName("Sine"))
    }

    // ----------------------------------------------------------------- drum kit

    @Test
    fun everyDrumSoundIsAudibleNormalisedAndFinite() {
        val kit = DrumKit(SR)
        for (name in DRUM_NAMES) {
            for (variant in 0..3) {
                val data = assertNotNull(kit.sample(name, variant), "$name:$variant")
                assertEquals(SR, data.sampleRate)
                assertTrue(allFinite(data.samples), "$name:$variant has NaN or infinity")
                val p = peak(data.samples)
                assertTrue(p <= 0.9f + 1e-6f, "$name:$variant peaks at $p")
                assertTrue(p >= 0.9f - 1e-3f, "$name:$variant is not normalised ($p)")
                assertTrue(rms(data.samples) > 0.01, "$name:$variant is silent")
            }
        }
    }

    @Test
    fun drumSoundsHaveTheDocumentedLengths() {
        val kit = DrumKit(SR)
        val seconds = mapOf(
            "bd" to 0.6, "sd" to 0.35, "hh" to 0.15, "oh" to 0.8, "cp" to 0.4,
            "rim" to 0.08, "lt" to 0.8, "mt" to 0.8, "ht" to 0.8, "cr" to 2.5,
        )
        assertEquals(DRUM_NAMES.toSet(), seconds.keys)
        assertEquals(listOf("bd", "sd", "hh", "oh", "cp", "rim", "lt", "mt", "ht", "cr"), DRUM_NAMES)
        for ((name, length) in seconds) {
            assertEquals((length * SR).roundToInt(), kit.sample(name, 0)!!.samples.size, name)
        }
    }

    @Test
    fun drumVariantsDifferWrapModuloFourAndAreCached() {
        val kit = DrumKit(SR)
        for (name in DRUM_NAMES) {
            val first = kit.sample(name, 0)!!
            assertFalse(first.samples.contentEquals(kit.sample(name, 1)!!.samples), "$name variants 0 and 1 are identical")
            assertSame(first, kit.sample(name, 0))
            assertSame(first, kit.sample(name, 4))
            assertSame(kit.sample(name, 3), kit.sample(name, -1))
        }
        assertNull(kit.sample("nope", 0))
    }

    @Test
    fun warmUpRendersEverySoundOfTheKitAndTheMachinesInEveryVariantExactlyOnce() {
        val kit = DrumKit(SR)
        val expected = (DRUM_NAMES.size + DRUM_MACHINES.values.sumOf { it.size }) * 4
        assertEquals(0, kit.renderedCount, "nothing is rendered before it is asked for")
        kit.warmUp()
        assertEquals(expected, kit.renderedCount)
        val snare = kit.sample("sd", 3)
        kit.warmUp()
        assertEquals(expected, kit.renderedCount)
        assertSame(snare, kit.sample("sd", 3), "warming up again renders nothing twice")
    }

    @Test
    fun drumRenderingIsDeterministicAndFollowsTheEngineSampleRate() {
        val a = DrumKit(SR)
        val b = DrumKit(SR)
        for (name in DRUM_NAMES) assertContentEquals(a.sample(name, 2)!!.samples, b.sample(name, 2)!!.samples, name)
        val cd = DrumKit(44_100).sample("sd", 0)!!
        assertEquals(44_100, cd.sampleRate)
        assertEquals((0.35 * 44_100).roundToInt(), cd.samples.size)
    }

    @Test
    fun drumSoundsHaveTheirCharacteristicSpectra() {
        val kit = DrumKit(SR)
        assertTrue(energyBelow(kit.sample("bd", 0)!!.samples, 300.0) > 0.6, "bd should be mostly low frequency")
        assertTrue(energyBelow(kit.sample("hh", 0)!!.samples, 1000.0) < 0.05, "hh should be mostly high frequency")
        assertTrue(energyBelow(kit.sample("oh", 0)!!.samples, 1000.0) < 0.05, "oh should be mostly high frequency")

        // Toms ring at 80, 120 and 180 Hz once their 80 ms pitch sweep has finished.
        fun crossings(name: String): Int {
            val x = kit.sample(name, 0)!!.samples
            var count = 0
            for (i in SR / 10 + 1 until SR * 3 / 10) if (x[i - 1] < 0f && x[i] >= 0f) count++
            return count
        }
        assertTrue(crossings("lt") < crossings("mt") && crossings("mt") < crossings("ht"))
    }

    @Test
    fun hatsAndCymbalDecayAtDifferentRates() {
        val kit = DrumKit(SR)
        // hh has τ = 0.03 s (e^-3.3 after 100 ms), oh τ = 0.25 s (e^-0.4), cr τ = 1.2 s (e^-0.83 after 1 s).
        assertTrue(remainingAt(kit.sample("hh", 0)!!.samples, 0.10) < 0.1)
        assertTrue(remainingAt(kit.sample("oh", 0)!!.samples, 0.10) > 0.4)
        assertTrue(remainingAt(kit.sample("cr", 0)!!.samples, 1.0) > 0.25)
    }
}
