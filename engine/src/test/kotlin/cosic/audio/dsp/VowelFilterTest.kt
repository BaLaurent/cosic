package cosic.audio.dsp

import kotlin.math.PI
import kotlin.math.log10
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive

/** The vowel filter against a real Chromium `OfflineAudioContext` rendering superdough's own `VowelNode` (tools/strudel-ref/vowel-oracle.mjs). */
class VowelFilterTest {
    private val golden = DspGolden.load("vowel.json")

    @Test
    fun everyGoldenCaseMatchesChromium() {
        for (entry in golden["cases"] as JsonArray) {
            val json = entry as kotlinx.serialization.json.JsonObject
            val name = json["name"]!!.jsonPrimitive.content
            val rate = json["sampleRate"]!!.jsonPrimitive.content.toInt()
            val input = (json["input"] as JsonArray).map { DspGolden.floats(it.jsonPrimitive.content) }
            val output = (json["output"] as JsonArray).map { DspGolden.floats(it.jsonPrimitive.content) }
            val filter = VowelFilter(rate)
            assertTrue(filter.configure(json["vowel"]!!.jsonPrimitive.content), name)
            val data = input.map { it.copyOf() }
            filter.process(data[0], data.getOrNull(1), 0, data[0].size)
            for (ch in data.indices) assertClose("$name ch$ch", output[ch], data[ch], atol = 1e-7, rtol = 1e-6)
        }
    }

    @Test
    fun callsThatSplitTheBlockDoNotChangeTheOutput() {
        val entry = (golden["cases"] as JsonArray)[2] as kotlinx.serialization.json.JsonObject // stereo
        val input = (entry["input"] as JsonArray).map { DspGolden.floats(it.jsonPrimitive.content) }
        val whole = input.map { it.copyOf() }
        VowelFilter(48000).apply { configure("i") }.process(whole[0], whole[1], 0, whole[0].size)
        val pieces = input.map { it.copyOf() }
        val filter = VowelFilter(48000).apply { configure("i") }
        var from = 0
        while (from < pieces[0].size) {
            val to = minOf(pieces[0].size, from + 77)
            filter.process(pieces[0], pieces[1], from, to)
            from = to
        }
        assertContentEquals(whole[0], pieces[0])
        assertContentEquals(whole[1], pieces[1])
    }

    @Test
    fun theTableHasTheFifteenVowelsAndSevenAliases() {
        assertEquals(15 + 7, VowelFormants.table.size)
        for (name in listOf("a", "e", "i", "o", "u", "ae", "aa", "oe", "ue", "y", "uh", "un", "en", "an", "on")) {
            val formant = assertNotNull(VowelFormants.resolve(name), name)
            assertEquals(5, formant.freqs.size)
            assertEquals(5, formant.gains.size)
            assertEquals(5, formant.qs.size)
            assertEquals(1.0, formant.gains[0])
        }
        assertEquals(listOf(660.0, 1120.0, 2750.0, 3000.0, 3350.0), VowelFormants.resolve("a")!!.freqs.toList())
        assertSame(VowelFormants.resolve("ae"), VowelFormants.resolve("æ"))
        assertSame(VowelFormants.resolve("oe"), VowelFormants.resolve("ø"))
        assertSame(VowelFormants.resolve("aa"), VowelFormants.resolve("ɑ"))
        assertSame(VowelFormants.resolve("aa"), VowelFormants.resolve("å"))
        assertSame(VowelFormants.resolve("oe"), VowelFormants.resolve("ö"))
        assertSame(VowelFormants.resolve("ue"), VowelFormants.resolve("ü"))
        assertSame(VowelFormants.resolve("y"), VowelFormants.resolve("ı"))
        assertNull(VowelFormants.resolve("A"), "lookup is exact")
        assertNull(VowelFormants.resolve("x"))
    }

    @Test
    fun anUnknownVowelLeavesTheFilterUnconfiguredAndTheSignalAlone() {
        val filter = VowelFilter(48000)
        assertFalse(filter.isConfigured)
        assertFalse(filter.configure("q"))
        assertFalse(filter.isConfigured)
        val noise = FloatArray(300) { ((it * 31) % 17 - 8) / 10f }
        val io = noise.copyOf()
        filter.process(io, null, 0, 300)
        assertContentEquals(noise, io)
        assertTrue(filter.configure("a"))
        assertTrue(filter.isConfigured)
    }

    private fun gainDb(vowel: String, freq: Double): Double {
        val n = 24000
        val sine = FloatArray(n) { (0.1 * sin(2 * PI * freq * it / 48000)).toFloat() }
        val out = sine.copyOf()
        VowelFilter(48000).apply { configure(vowel) }.process(out, null, 0, n)
        fun rms(a: FloatArray) = sqrt(a.drop(12000).sumOf { (it * it).toDouble() } / (a.size - 12000))
        return 20 * log10(rms(out) / rms(sine))
    }

    @Test
    fun theFilterBoostsTheFormantsOfTheVowel() {
        // 'a': first formant 660 Hz at gain 1 (times the makeup 8), nothing near 5 kHz
        val atFormant = gainDb("a", 660.0)
        val far = gainDb("a", 5000.0)
        assertTrue(atFormant - far >= 6, "boost near 660 Hz versus 5 kHz: $atFormant vs $far dB")
        assertEquals(20 * log10(8.0), atFormant, 0.5, "a formant at gain 1 sees the makeup gain of 8")
        assertTrue(gainDb("a", 1120.0) < gainDb("a", 660.0), "the second formant has gain 0.5012")
        // 'i' puts its first formant near 270 Hz, 'a' near 660 Hz
        assertTrue(gainDb("i", 270.0) > gainDb("i", 660.0) + 6)
        assertTrue(gainDb("a", 660.0) > gainDb("a", 270.0) + 6)
    }

    @Test
    fun nonFiniteInputIsTakenAsSilence() {
        val clean = FloatArray(500) { ((it * 31) % 17 - 8) / 10f }
        val dirty = clean.copyOf()
        dirty[100] = Float.NaN
        dirty[200] = Float.NEGATIVE_INFINITY
        val zeroed = clean.copyOf()
        zeroed[100] = 0f
        zeroed[200] = 0f
        VowelFilter(48000).apply { configure("o") }.process(dirty, null, 0, 500)
        VowelFilter(48000).apply { configure("o") }.process(zeroed, null, 0, 500)
        assertContentEquals(zeroed, dirty)
    }

    @Test
    fun theAliasSoundsLikeItsVowel() {
        val noise = FloatArray(2000) { ((it * 8191) % 2001 - 1000) / 2000f }
        val a = noise.copyOf()
        val b = noise.copyOf()
        VowelFilter(48000).apply { configure("æ") }.process(a, null, 0, 2000)
        VowelFilter(48000).apply { configure("ae") }.process(b, null, 0, 2000)
        assertContentEquals(a, b)
    }
}
