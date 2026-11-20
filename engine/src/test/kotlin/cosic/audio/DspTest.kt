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
        var state = 3 // xorshift32 white noise
        val noise = FloatArray(SR / 2) {
            state = state xor (state shl 13)
            state = state xor (state ushr 17)
            state = state xor (state shl 5)
            state * (1f / 2_147_483_648f)
        }
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
