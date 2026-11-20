package cosic.visual

import cosic.io.ScopeTaps
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnalyserTest {
    private fun sine(n: Int, bin: Int, amplitude: Double = 1.0): FloatArray = FloatArray(n) { (amplitude * Math.sin(2 * Math.PI * bin * it / n)).toFloat() }

    @Test
    fun aUnitSineOnBinOneHundredReadsMinusNineteenPointFiveEightDecibelsAndThenRisesWithTheSmoothing() {
        val analyser = Analyser(8192)
        val out = FloatArray(analyser.frequencyBinCount)
        val block = sine(8192, 100)
        analyser.frequencyOf(block, out)
        // 0.5 * 0 + 0.5 * 0.21: the Blackman window has a mean of 0.42, half of the sine's amplitude is in the bin
        assertEquals(-19.58, out[100].toDouble(), 0.3)
        analyser.frequencyOf(block, out)
        assertEquals(-16.05, out[100].toDouble(), 0.3)
        repeat(8) { analyser.frequencyOf(block, out) }
        assertEquals(-13.56, out[100].toDouble(), 0.3) // 0.21 * (1 - 2^-10), the level the smoothing settles at
    }

    @Test
    fun silenceIsMinusInfinityDecibels() {
        val analyser = Analyser(1024)
        val out = FloatArray(512)
        analyser.frequencyOf(FloatArray(1024), out)
        assertTrue(out.all { it == Float.NEGATIVE_INFINITY })
    }

    @Test
    fun sizesAreTwoToTheFftPlusFiveHeldToWhatANodeAccepts() {
        assertEquals(8192, Analyser.sizeOf(8.0))
        assertEquals(32, Analyser.sizeOf(0.0))
        assertEquals(32768, Analyser.sizeOf(10.0))
        assertEquals(32768, Analyser.sizeOf(99.0))
        assertEquals(32, Analyser.sizeOf(-3.0))
        assertEquals(8192, Analyser.sizeOf(Double.NaN))
        assertEquals(2048, Analyser.sizeOf(6.7))
    }

    @Test
    fun theWaveformIsTheOldestHalfOfTheLatestBlock() {
        val taps = ScopeTaps()
        val analyser = Analyser(64)
        val out = FloatArray(analyser.frequencyBinCount)
        assertFalse(analyser.timeDomain(taps, "a", out), "no sound has played yet")
        taps.add("a", FloatArray(128) { it.toFloat() }, 0, 128)
        taps.endBlock(128)
        assertTrue(analyser.timeDomain(taps, "a", out))
        // the latest 64 samples are 64..127, and the array holds the first 32 of them
        assertEquals((64 until 96).map { it.toFloat() }, out.toList())
        assertFalse(analyser.timeDomain(taps, "another", out))
    }

    @Test
    fun aTapFeedMakesOneAnalyserPerSizeAndAnswersNullWithoutSound() {
        val taps = ScopeTaps()
        val feed = TapFeed("1") { taps }
        assertEquals(null, feed.read(64, false))
        taps.add("1", sine(64, 4), 0, 64)
        taps.endBlock(64)
        val waveform = feed.read(64, false)!!
        assertEquals(32, waveform.size)
        val bins = feed.read(64, true)!!
        assertEquals(32, bins.size)
        // the first read of the smoothing: 0.5 * 0.21 = 0.105
        assertEquals(-19.58, bins[4].toDouble(), 0.3)
        assertEquals(null, TapFeed("1") { null }.read(64, false))
    }
}
