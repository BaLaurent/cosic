package cosic.parity

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Kotlin features against the numbers tools/strudel-ref/audio/features.mjs made on the same signals (the file feature-vectors.mjs writes). */
class AudioFeaturesTest {
    private fun segment(s: JsonObject): FloatArray {
        s["zeros"]?.let { return FloatArray(it.jsonPrimitive.int) }
        s["saw"]?.jsonObject?.let {
            val length = it.getValue("length").jsonPrimitive.int
            val amp = it.getValue("amp").jsonPrimitive.double
            val step = it.getValue("step").jsonPrimitive.int
            return FloatArray(length) { i -> ((((i.toLong() * step) % 1000) / 500.0 - 1.0) * amp).toFloat() }
        }
        val n = s.getValue("noise").jsonObject
        val length = n.getValue("length").jsonPrimitive.int
        val amp = n.getValue("amp").jsonPrimitive.double
        var x = n.getValue("seed").jsonPrimitive.double.toLong() and 0xFFFFFFFFL
        return FloatArray(length) {
            x = (x * 1664525L + 1013904223L) % 4294967296L
            ((x / 2147483648.0 - 1.0) * amp).toFloat()
        }
    }

    private fun doubles(a: JsonArray): DoubleArray = DoubleArray(a.size) { a[it].jsonPrimitive.double }

    @Test
    fun theKotlinFeaturesAreTheOnesTheScriptMakes() {
        val file = File(ParityData.dir, "audio-feature-vectors.json")
        val doc = Json.parseToJsonElement(file.readText()).jsonObject
        val cases = doc.getValue("cases").jsonArray
        assertTrue(cases.size >= 4)
        for (c in cases) {
            val case = c.jsonObject
            val name = case.getValue("name").jsonPrimitive.content
            val sampleRate = case.getValue("sampleRate").jsonPrimitive.int
            val signal = case.getValue("segments").jsonArray.flatMap { segment(it.jsonObject).toList() }.toFloatArray()
            val expected = case.getValue("features").jsonObject.getValue("channels").jsonArray[0].jsonObject
            val got = AudioFeatureSpec.channelFeatures(signal, 0, 1, signal.size, sampleRate)
            val rms = doubles(expected.getValue("rms").jsonArray)
            assertEquals(rms.size, got.frames, "$name: frames")
            for (i in rms.indices) assertEquals(rms[i], got.rms[i], 1e-6, "$name: rms of frame $i")
            val centroid = doubles(expected.getValue("centroid").jsonArray)
            for (i in centroid.indices) assertTrue(abs(centroid[i] - got.centroid[i]) <= 1e-6 * maxOf(1.0, centroid[i]), "$name: centroid of frame $i: ${centroid[i]} vs ${got.centroid[i]}")
            val bands = doubles(expected.getValue("bands").jsonArray)
            assertEquals(bands.size, got.bands.size, "$name: band values")
            for (i in bands.indices) assertEquals(bands[i], got.bands[i], 1e-6, "$name: band ${i % 8} of frame ${i / 8}")
            val onsets = expected.getValue("onsets").jsonArray.map { it.jsonPrimitive.int }
            assertEquals(onsets, got.onsets.toList(), "$name: onsets")
        }
    }

    @Test
    fun theBurstsHaveTheirOnsetsAndTheSilenceNone() {
        val silent = FloatArray(8192)
        val f = AudioFeatureSpec.channelFeatures(silent, 0, 1, silent.size, 48000)
        assertTrue(f.rms.all { it == -100.0 } && f.onsets.isEmpty() && f.centroid.all { it == 0.0 })
        val burst = FloatArray(12288) { if (it < 4096) 0f else (((it * 37L) % 1000) / 500.0 - 1.0).toFloat() * 0.5f }
        val g = AudioFeatureSpec.channelFeatures(burst, 0, 1, burst.size, 48000)
        assertTrue(g.onsets.isNotEmpty() && g.onsets.size <= 2, "the burst starts once: ${g.onsets.toList()}")
    }

    @Test
    fun onsetsAreMatchedWithinOneFrameAndOnlyOnce() {
        val (lostReference, lostGot) = AudioCompare.pair(intArrayOf(3, 10, 20), intArrayOf(4, 9, 25), 1)
        assertEquals(listOf(20), lostReference)
        assertEquals(listOf(25), lostGot)
        val (a, b) = AudioCompare.pair(intArrayOf(5), intArrayOf(5, 6), 1)
        assertEquals(emptyList(), a)
        assertEquals(listOf(6), b, "a reference onset pairs with one render onset only")
    }

    private fun features(rms: DoubleArray, onsets: IntArray) = ChannelFeatures(rms, DoubleArray(rms.size), DoubleArray(rms.size * 8), onsets)

    @Test
    fun anOnsetTheOtherRenderNearlyDetectsIsTheSameOnsetButAMissingOneIsNot() {
        val quiet = -40.0
        // the reference rises 6.2 dB at frame 3, the render 5.0 dB (a threshold flicker: within the 1.5 dB margin)
        val reference = features(doubleArrayOf(quiet, quiet, quiet, quiet + 6.2, quiet + 6.2), intArrayOf(3))
        val flicker = features(doubleArrayOf(quiet, quiet, quiet, quiet + 5.0, quiet + 5.0), intArrayOf())
        assertEquals(emptyList(), AudioCompare.unmatched(reference, flicker, 1).first)
        assertEquals(emptyList(), AudioCompare.unmatched(flicker, reference, 1).second)
        // the render rises 3 dB only, or on a frame two away: the hit is missing
        val soft = features(doubleArrayOf(quiet, quiet, quiet, quiet + 3.0, quiet + 3.0), intArrayOf())
        assertEquals(listOf(3), AudioCompare.unmatched(reference, soft, 1).first)
        val late = features(doubleArrayOf(quiet, quiet, quiet, quiet, quiet, quiet + 6.2), intArrayOf(5))
        assertEquals(listOf(3), AudioCompare.unmatched(reference, late, 1).first)
        assertEquals(listOf(5), AudioCompare.unmatched(reference, late, 1).second)
    }
}
