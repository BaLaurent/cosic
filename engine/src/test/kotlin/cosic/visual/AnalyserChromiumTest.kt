package cosic.visual

import cosic.io.ScopeTaps
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Compares [Analyser] with the `AnalyserNode` of Chromium. `tools/strudel-ref/analyser-oracle.mjs` renders signals through a real node (fftSize 2^(fft+5), smoothing 0.5)
 * in an offline context that is suspended at chosen frames, and stores what `getFloatTimeDomainData` and `getFloatFrequencyData` give into arrays of `frequencyBinCount` values;
 * here the same signals go through [ScopeTaps] and [Analyser] block by block (128 frames, as the engine renders) and every read must agree: the waveform to the float, the
 * decibels to 0.02 dB wherever the level is above the floor of single precision (-100 dB).
 */
class AnalyserChromiumTest {
    private fun floats(base64: String): FloatArray {
        val bytes = Base64.getDecoder().decode(base64)
        val out = FloatArray(bytes.size / 4)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(out)
        return out
    }

    private fun signal(s: JsonObject, n: Int, frames: Int): FloatArray {
        val type = s.getValue("type").jsonPrimitive.content
        val amp = s["amp"]?.jsonPrimitive?.double ?: 0.0
        var x = (s["seed"]?.jsonPrimitive?.double?.toLong() ?: 0L) and 0xFFFFFFFFL
        return FloatArray(frames) { i ->
            when (type) {
                "sine" -> (amp * Math.sin((2 * Math.PI * s.getValue("bin").jsonPrimitive.double * i) / n)).toFloat()
                "two" -> (amp * (Math.sin((2 * Math.PI * s.getValue("binA").jsonPrimitive.double * i) / n) + Math.sin((2 * Math.PI * s.getValue("binB").jsonPrimitive.double * i) / n)) * 0.5).toFloat()
                "noise" -> {
                    x = (x * 1664525L + 1013904223L) % 4294967296L
                    ((x / 2147483648.0 - 1) * amp).toFloat()
                }
                "ramp" -> (amp * (((i % 512) / 512.0) * 2 - 1)).toFloat()
                else -> error(type)
            }
        }
    }

    @Test
    fun theAnalyserReadsWhatChromiumsAnalyserNodeReads() {
        val doc = Json.parseToJsonElement(
            javaClass.getResourceAsStream("/visual/analyser.json")?.bufferedReader()?.readText()
                ?: error("/visual/analyser.json is missing: run `node tools/strudel-ref/analyser-oracle.mjs`"),
        ).jsonObject
        val problems = ArrayList<String>()
        var reads = 0
        for (case in doc.getValue("cases").jsonArray.map { it.jsonObject }) {
            val name = case.getValue("name").jsonPrimitive.content
            val n = case.getValue("fftSize").jsonPrimitive.int
            val samples = signal(case.getValue("signal").jsonObject, n, case.getValue("frames").jsonPrimitive.int)
            val taps = ScopeTaps()
            val analyser = Analyser(n)
            var fed = 0
            for (read in case.getValue("reads").jsonArray.map { it.jsonObject }) {
                val frame = read.getValue("frame").jsonPrimitive.int
                while (fed < frame) {
                    taps.add("a", samples.copyOfRange(fed, fed + 128), 0, 128)
                    taps.endBlock(128)
                    fed += 128
                }
                val time = FloatArray(n / 2)
                val bins = FloatArray(n / 2)
                assertTrue(analyser.timeDomain(taps, "a", time))
                assertTrue(analyser.frequency(taps, "a", bins))
                reads++
                val expectedTime = floats(read.getValue("td").jsonPrimitive.content)
                val expectedBins = floats(read.getValue("fd").jsonPrimitive.content)
                val worstTime = time.indices.maxOf { Math.abs(time[it] - expectedTime[it]) }
                if (worstTime > 1e-6f) problems.add("$name @$frame: waveform differs by up to $worstTime")
                var worst = 0.0
                var worstBin = -1
                for (k in bins.indices) {
                    val e = expectedBins[k].toDouble()
                    val a = bins[k].toDouble()
                    if (e <= -100.0 && a <= -100.0) continue
                    val d = if (e.isInfinite() || a.isInfinite()) Double.POSITIVE_INFINITY else Math.abs(e - a)
                    if (d > worst) {
                        worst = d
                        worstBin = k
                    }
                }
                if (worst > 0.02) problems.add("$name @$frame: decibels differ by up to $worst at bin $worstBin (Chromium ${expectedBins[worstBin]}, here ${bins[worstBin]})")
            }
        }
        if (problems.isNotEmpty()) fail("${problems.size} reads differ from Chromium" + problems.take(15).joinToString("\n  ", "\n  "))
        println("analyser: $reads reads match Chromium's AnalyserNode")
    }
}
