package cosic.audio

import kotlin.math.abs
import kotlin.math.log10
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The reverb's impulse responses against the real `reverbGen.mjs` and `adjustLength` run in Chromium (`orbit-reverbgen.json`, `orbit-adjust.json`). */
class ReverbGenTest {
    @Test
    fun generatedResponsesMatchChromiumWithTheSameRandomNumbers() {
        for (case in OrbitGolden.cases("reverbgen")) {
            val name = OrbitGolden.string(case, "name")
            val sr = OrbitGolden.int(case, "sr")
            val ir = ReverbGen.generate(
                sr, OrbitGolden.number(case, "d"), OrbitGolden.number(case, "fade"), OrbitGolden.number(case, "lp"), OrbitGolden.number(case, "dim"),
                LcgRandom(OrbitGolden.number(case, "seed").toLong()),
            )
            assertEquals(OrbitGolden.int(case, "length"), ir.length, "$name: length")
            val expected = OrbitGolden.outputs(case)
            for (ch in 0..1) assertWithin("$name, channel $ch", expected[ch], ir.channels[ch], 2e-6)
        }
    }

    @Test
    fun adjustLengthMatchesTheRealCode() {
        val ir = ImpulseResponse(arrayOf(lcg(31, 1500), lcg(32, 1500)), 48000)
        for (case in OrbitGolden.cases("adjust")) {
            val name = OrbitGolden.string(case, "name")
            val adjusted = ReverbGen.adjustLength(OrbitGolden.number(case, "duration"), ir, OrbitGolden.number(case, "speed"), OrbitGolden.number(case, "offset"))
            val expected = OrbitGolden.outputs(case)
            for (ch in 0..1) assertContentEquals(expected[ch], adjusted.channels[ch], "$name, channel $ch")
        }
    }

    @Test
    fun aRoomOfTwoSecondsHasAnImpulseResponseOfThreeAndAFadeInOverTheFirstTenthOfASecond() {
        val ir = ReverbGen.generate(SR, 2.0, 0.1, 0.0, 0.0, LcgRandom(1))
        assertEquals(144000, ir.length)
        assertEquals(2, ir.channels.size)
        for (channel in ir.channels) {
            assertEquals(0.0, channel[0].toDouble(), 0.0, "the fade starts from silence")
            // the ramp j / 4800 over the first 4800 frames: the energy of the first half is a fraction of the second half's
            assertTrue(rms(channel, 0, 2400) < 0.5 * rms(channel, 2400, 4800))
            // the decay is 60 dB over `decay` seconds: the mean level at 2 s is a thousandth of the one near the start
            val head = rms(channel, 4800, 9600)
            val tail = rms(channel, 2 * SR - 2400, 2 * SR + 2400)
            assertTrue(tail / head < 0.0025 && tail / head > 0.0004, "level at the decay time relative to the start: ${tail / head}")
        }
    }

    @Test
    fun theLowPassClosesFrom15000ToTheDimFrequencyOverTheDecayTime() {
        val ir = ReverbGen.generate(SR, 2.0, 0.1, 15000.0, 1000.0, LcgRandom(2)).channels[0]
        // the share of energy above 8 kHz (a crude high-pass: first differences twice) in two 100 ms windows
        fun highShare(from: Int): Double {
            var high = 0.0
            var all = 0.0
            for (i in from + 2 until from + 4800) {
                val d = ir[i] - 2 * ir[i - 1] + ir[i - 2]
                high += d.toDouble() * d
                all += ir[i].toDouble() * ir[i]
            }
            return high / all
        }
        val early = highShare(2400)
        val late = highShare(3 * SR - 4800 - 2)
        assertTrue(20 * log10(early / late) > 10.0, "the high-frequency share drops by ${20 * log10(early / late)} dB")
    }

    @Test
    fun aRoomOfNoLengthHasNoImpulseResponse() {
        assertEquals(0, ReverbGen.frames(0.0, SR))
        assertEquals(0, ReverbGen.frames(-1.0, SR))
        assertEquals(0, ReverbGen.frames(1e-9, SR))
        assertEquals(144000, ReverbGen.frames(2.0, SR))
        assertEquals(1, ReverbGen.frames(0.00001, SR))
    }

    @Test
    fun aRoomTakenFromASampleIsReadAtItsOwnSpeedFromItsStart() {
        val data = FloatArray(100) { it + 1f }
        val ir = ImpulseResponse(arrayOf(data), 1000)
        // duration 0.05 s at 1000 Hz: the first 50 frames of the 100; at speed 2 the positions are 0, 2, 4, ... (wrapping)
        val fast = ReverbGen.adjustLength(0.05, ir, 2.0, 0.0).channels[0]
        for (i in 0 until 50) assertEquals(data[(i * 2) % 100], fast[i])
        for (i in 50 until 100) assertEquals(0f, fast[i], "the rest is silence")
        // below speed 1 the position is negated, so the response is read backwards from the start of the buffer
        val slow = ReverbGen.adjustLength(0.05, ir, 0.5, 0.0).channels[0]
        assertEquals(data[0], slow[0])
        assertEquals(data[0], slow[1], "index -0.5 truncates to -0, the first frame")
        assertEquals(data[99], slow[2])
    }

    @Test
    fun aSampleAtAnotherRateBecomesAResponseAtTheEngineRate() {
        val mono = cosic.SampleData(FloatArray(441) { 1f }, 44100)
        val ir = mono.toImpulseResponse(48000)
        assertEquals(1, ir.channels.size)
        assertTrue(abs(ir.length - 480) <= 1)
        assertEquals(1f, ir.channels[0][100], 1e-6f)
    }
}
