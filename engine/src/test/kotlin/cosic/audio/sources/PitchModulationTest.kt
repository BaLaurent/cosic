package cosic.audio.sources

import cosic.audio.BLOCK_FRAMES
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PitchModulationTest {
    private fun spec(vararg controls: Pair<String, Any?>, durationSec: Double = 1.0, sampleRate: Int = 48000) =
        SourceSpec(sampleRate, mapOf(*controls).asSourceControls(), durationSec, 1.0, 1L)

    /** The cents the modulation adds for the first [frames] frames, in blocks of [block] (the sizes cycle). */
    private fun trace(pm: PitchModulation, frames: Int, vararg block: Int): DoubleArray {
        val sizes = if (block.isEmpty()) intArrayOf(BLOCK_FRAMES) else block
        val bus = PitchBus(BLOCK_FRAMES)
        val out = DoubleArray(frames)
        var done = 0
        var turn = 0
        while (done < frames) {
            val n = minOf(sizes[turn++ % sizes.size], frames - done)
            bus.clear(n)
            pm.add(bus, n)
            assertTrue(bus.detuneActive)
            System.arraycopy(bus.detuneCents, 0, out, done, n)
            done += n
        }
        return out
    }

    private fun create(vararg controls: Pair<String, Any?>, durationSec: Double = 1.0): PitchModulation? =
        PitchModulation.create(spec(*controls, durationSec = durationSec), durationSec)

    @Test
    fun vibratoIsASineInCents() {
        val cents = trace(create("vib" to 4.0)!!, 12000)
        assertEquals(0.0, cents[0], 1e-9)
        assertEquals(50.0, cents[3000], 0.01, "a quarter period: the peak, vibmod 0.5 semitones")
        assertEquals(0.0, cents[6000], 0.01)
        assertEquals(-50.0, cents[9000], 0.01)
    }

    @Test
    fun noModulationWithoutVibratoOrEnvelope() {
        assertNull(create())
        assertNull(create("vib" to 0.0))
        assertNull(create("vib" to -1.0))
        assertNull(create("vibmod" to 12.0))
        assertNull(create("note" to 60.0, "s" to "sawtooth"))
    }

    @Test
    fun vibratoDepthIsVibmodSemitones() {
        val cents = trace(create("vib" to 5.0, "vibmod" to 2.0)!!, 12000)
        var peak = 0.0
        for (c in cents) peak = maxOf(peak, abs(c))
        assertEquals(200.0, peak, 0.01)
    }

    @Test
    fun pitchEnvelopeIsAnAdsrOnTheDetune() {
        // penv 12, the default envelope [0.2, 0.001, 1, 0.001] anchored at the sustain: from a semitone octave below up to the note over the attack
        val cents = trace(create("penv" to 12.0)!!, 48100)
        assertEquals(-1200.0, cents[0], 1e-3)
        assertEquals(-600.0, cents[4800], 0.5)
        assertEquals(0.0, cents[9600], 0.5)
        assertEquals(0.0, cents[48000 - 10], 1.0)
    }

    @Test
    fun anchorMovesTheSwing() {
        val cents = trace(create("penv" to 12.0, "panchor" to 0.0, "pattack" to 0.01, "pdecay" to 0.1, "psustain" to 0.5)!!, 49000)
        assertEquals(0.0, cents[0], 1e-6)
        assertEquals(600.0, cents[240], 0.5, "halfway up the attack")
        assertEquals(1200.0, cents[480], 0.5, "the top")
        assertEquals(900.0, cents[2880], 0.5, "halfway down the decay")
        assertEquals(600.0, cents[5280], 0.5, "the sustain")
        assertEquals(600.0, cents[24000], 0.5)
        assertEquals(300.0, cents[48240], 1.0, "halfway through the release")
    }

    @Test
    fun exponentialCurveStartsFromAFloorOfNearlyZero() {
        val cents = trace(create("penv" to 12.0, "pcurve" to 1.0, "panchor" to 0.0, "pattack" to 0.01)!!, 1000)
        assertEquals(1.0954, cents[240], 1e-3, "0.001 * 1200000 ^ 0.5")
    }

    @Test
    fun aPitchDecayAloneMakesAnEnvelopeThatSettlesOnTheNote() {
        // getADSRValues([N, .5, N, N]) is [.001, .5, .001, .01] and the anchor is that sustain: the swing (penv 1 semitone) is from -0.1 to 99.9 cents and back to 0
        val cents = trace(create("pdecay" to 0.5)!!, 48000)
        assertEquals(-0.1, cents[0], 1e-4)
        assertEquals(99.9, cents[48], 0.01, "the top after the 1 ms attack")
        assertEquals(49.95, cents[48 + 12000], 0.01, "halfway down the decay")
        assertEquals(0.0, cents[40000], 1e-4, "the sustain")
    }

    @Test
    fun envelopeAndVibratoAdd() {
        val both = trace(create("vib" to 5.0, "vibmod" to 1.0, "penv" to 7.0, "panchor" to 0.0, "pattack" to 0.05)!!, 9000)
        val onlyEnvelope = trace(create("penv" to 7.0, "panchor" to 0.0, "pattack" to 0.05)!!, 9000)
        val onlyVibrato = trace(create("vib" to 5.0, "vibmod" to 1.0)!!, 9000)
        for (i in 0 until 9000) assertEquals(onlyEnvelope[i] + onlyVibrato[i], both[i], 1e-3, "frame $i")
    }

    @Test
    fun theResultDoesNotDependOnTheBlockSize() {
        val a = trace(create("vib" to 5.0, "vibmod" to 1.0, "penv" to 7.0, "pattack" to 0.05)!!, 6000, 256)
        val b = trace(create("vib" to 5.0, "vibmod" to 1.0, "penv" to 7.0, "pattack" to 0.05)!!, 6000, 97, 250, 31, 128)
        for (i in 0 until 6000) assertEquals(a[i], b[i], 0.0, "frame $i")
    }

    @Test
    fun restartRewindsTheModulation() {
        val pm = create("vib" to 5.0, "vibmod" to 1.0, "penv" to 7.0, "pattack" to 0.05)!!
        val first = trace(pm, 3000)
        pm.restart()
        val second = trace(pm, 3000)
        assertTrue(first.contentEquals(second))
    }

    @Test
    fun matchesTheRealParamAutomationOfChromium() {
        for (g in SourceGolden.load("pitch.json")) {
            val spec = g.spec()
            val pm = PitchModulation.create(spec, g.durationSec)
            val expected = g.left
            val actual = DoubleArray(expected.size)
            if (pm != null) {
                val trace = trace(pm, expected.size, 256)
                System.arraycopy(trace, 0, actual, 0, trace.size)
            } else {
                assertTrue(expected.all { it == 0f }, "${g.name}: Chromium has a modulation, this has none")
            }
            var worst = 0.0
            var at = -1
            for (i in expected.indices) {
                val error = abs(actual[i] - expected[i].toDouble())
                if (error > worst) {
                    worst = error
                    at = i
                }
            }
            if (System.getenv("COSIC_SOURCES_VERBOSE") == "true") println("${g.name}: worst error $worst at frame $at")
            assertNotNull(g.name)
            assertTrue(worst < 1.0, if (at < 0) g.name else "${g.name}: frame $at is ${actual[at]}, Chromium has ${expected[at]}")
        }
    }
}
