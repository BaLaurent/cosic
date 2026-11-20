package cosic.audio

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** [AnalyserTap] against Chromium's `AnalyserNode` (`orbit-analyser.json`) and the level of a bin-exact sine. */
class AnalyserTapTest {
    private fun signal(spec: JsonObject, frames: Int, sr: Int, fftSize: Int): FloatArray {
        spec["noise"]?.let { return lcg(it.jsonPrimitive.content.toLong(), frames, spec["amp"]?.jsonPrimitive?.content?.toDouble() ?: 1.0) }
        val sine = spec["sine"]!!.jsonObject
        val f = sine["bin"]!!.jsonPrimitive.content.toDouble() * sr / fftSize
        val amp = sine["amp"]!!.jsonPrimitive.content.toDouble()
        return FloatArray(frames) { (amp * sin(2.0 * PI * f * it / sr)).toFloat() }
    }

    /** Writes [signal] (one or two channels) into [tap] in blocks of 128 frames, from frame [from] to frame [to]. */
    private fun feed(tap: AnalyserTap, channels: List<FloatArray>, from: Int, to: Int) {
        val block = AudioBlock(BLOCK_FRAMES)
        var pos = from
        while (pos < to) {
            val n = minOf(128, to - pos)
            channels[0].copyInto(block.l, 0, pos, pos + n)
            if (channels.size == 2) channels[1].copyInto(block.r, 0, pos, pos + n)
            block.channels = channels.size
            tap.add(block, n)
            tap.commit(n)
            pos += n
        }
    }

    @Test
    fun matchesTheAnalyserNodeOfChromiumUpdateByUpdate() {
        for (case in OrbitGolden.cases("analyser")) {
            val name = OrbitGolden.string(case, "name")
            val sr = OrbitGolden.int(case, "sr")
            val fftSize = OrbitGolden.int(case, "fftSize")
            val times = OrbitGolden.array(case, "times").map { it.jsonPrimitive.content.toInt() }
            val specs = OrbitGolden.array(case, "input").map { it.jsonObject }
            val snapshots = OrbitGolden.array(case, "snapshots").map { it.jsonObject }.associateBy { OrbitGolden.int(it, "frame") }
            val channels = specs.map { signal(it, times.last() + 128, sr, fftSize) }
            val tap = AnalyserTap(1, fftSize)
            val frequency = FloatArray(fftSize / 2)
            val time = FloatArray(fftSize / 2)
            var written = 0
            for (frame in times) {
                feed(tap, channels, written, frame)
                written = frame
                assertEquals(fftSize / 2, tap.frequencyDb(frequency))
                val snapshot = snapshots[frame] ?: continue
                assertEquals(fftSize / 2, tap.timeDomain(time))
                val expectedFrequency = OrbitGolden.floats(snapshot["frequency"]!!.jsonPrimitive)
                val expectedTime = OrbitGolden.floats(snapshot["time"]!!.jsonPrimitive)
                assertWithin("$name at $frame, time domain", expectedTime, time, 1e-7)
                // below -100 dB the bins are float rounding noise (a few dB apart between implementations); above it they must agree
                for (k in expectedFrequency.indices) {
                    if (expectedFrequency[k] > -100f) {
                        assertEquals(expectedFrequency[k].toDouble(), frequency[k].toDouble(), 0.02, "$name at $frame, bin $k (dB)")
                    } else {
                        assertTrue(frequency[k] < -100f, "$name at $frame, bin $k is ${frequency[k]} dB, expected below -100")
                    }
                }
            }
        }
    }

    @Test
    fun aBinExactUnitSineReadsMinus13Point56DecibelsAfterTenUpdates() {
        val fftSize = 8192
        val tap = AnalyserTap(1, fftSize)
        val f = 100.0 * SR / fftSize
        val sine = FloatArray(fftSize + 10 * 1024) { sin(2.0 * PI * f * it / SR).toFloat() }
        val out = FloatArray(fftSize / 2)
        feed(tap, listOf(sine), 0, fftSize)
        tap.frequencyDb(out)
        for (u in 1 until 10) {
            feed(tap, listOf(sine), fftSize + (u - 1) * 1024, fftSize + u * 1024)
            tap.frequencyDb(out)
        }
        assertEquals(-13.56, out[100].toDouble(), 0.3)
    }

    @Test
    fun theSmoothingOnlyAdvancesWhenThereIsNewAudio() {
        val tap = AnalyserTap(1, 2048)
        val sine = FloatArray(4096) { sin(2.0 * PI * 37.0 * it / 2048).toFloat() }
        feed(tap, listOf(sine), 0, 2048)
        val a = FloatArray(1024)
        val b = FloatArray(1024)
        tap.frequencyDb(a)
        tap.frequencyDb(b)
        assertEquals(a.toList(), b.toList(), "a second call without new audio changes nothing")
        feed(tap, listOf(sine), 2048, 2560)
        tap.frequencyDb(b)
        assertTrue(b[37] > a[37], "new audio moves the average")
    }

    @Test
    fun buses_gives_analysersToTheVoicesThatFeedThem() {
        val mixer = Mixer(SR)
        assertNull(mixer.analyser(1))
        mixer.startVoice(vp(dcSource(2.0, 0.5f), 1.0, "analyze" to 1.0, "fft" to 6.0, "attack" to 0.001, "decay" to 0.0, "sustain" to 1.0))
        mixer.renderFrames(SR / 4)
        val tap = mixer.analyser(1)!!
        assertEquals(2048, tap.fftSize, "fft 6 is 2^(6 + 5)")
        val out = FloatArray(1024)
        assertEquals(1024, tap.timeDomain(out))
        // the voice's `post` signal: 0.5 at the default gain 0.8 once the 1 ms attack is over
        assertEquals(0.4, out[1000].toDouble(), 1e-3)
        assertNull(mixer.analyser(2))
    }
}
