package cosic.soundfont

import cosic.audio.Mixer
import cosic.audio.SR
import cosic.audio.SampleSourceParams
import cosic.audio.VoiceSource
import cosic.audio.constantSample
import cosic.audio.channel
import cosic.audio.renderFrames
import cosic.audio.vp
import cosic.pattern.ControlMap
import cosic.pattern.CosicError
import cosic.pattern.Undefined
import cosic.player.SoundLookup
import cosic.player.SoundResolver
import cosic.sound.RegistryLibrary
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.Executor
import kotlin.math.abs
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Replays what `tools/strudel-ref/soundfont-oracle.mjs` made Strudel's own soundfont code do (`packages/soundfonts/fontloader.mjs`, run in Node over recorded font files):
 * which font file `s("gm_x").n(n)` fetches, which zone plays a `note` or `freq` and the rate and loop the buffer source gets, and the gain envelope of the registered sound.
 */
class SoundfontOracleTest {
    private val oracle = Json.parseToJsonElement(
        SoundfontOracleTest::class.java.getResourceAsStream("/soundfont/oracle.json")!!.use { it.readBytes().toString(Charsets.UTF_8) },
    ).jsonObject
    private val direct = Executor { it.run() }

    /** The JSON of the oracle as values: `{"$": "NaN"}` and its kin (what JSON cannot say) become the numbers and `undefined`. */
    private fun decode(e: JsonElement): Any? = when (e) {
        is JsonNull -> null
        is JsonPrimitive -> when {
            e.isString -> e.content
            e.content == "true" -> true
            e.content == "false" -> false
            else -> e.double
        }
        is JsonArray -> e.map { decode(it) }
        is JsonObject -> if (e.size == 1 && "$" in e) {
            when (val tag = e.getValue("$").jsonPrimitive.content) {
                "undefined" -> Undefined
                "NaN" -> Double.NaN
                "Infinity" -> Double.POSITIVE_INFINITY
                "-Infinity" -> Double.NEGATIVE_INFINITY
                "-0" -> -0.0
                else -> error("unknown tag $tag")
            }
        } else {
            LinkedHashMap<String, Any?>().also { map -> for ((key, value) in e) map[key] = decode(value) }
        }
    }

    private fun library(store: FontStore): RegistryLibrary = RegistryLibrary(store, emptySet()).also { it.fontExecutor = direct }

    // ------------------------------------------------------------------------------------------------ the font a sound picks

    @Test
    fun theFontAnEventPicksIsTheOneStrudelFetches() {
        val cases = oracle.getValue("fontChoice").jsonArray
        assertTrue(cases.size > 900)
        val chaos = fontFixture("0160_Chaos_sf2_file")
        for (case in cases) {
            val c = case.jsonObject
            val name = c.getValue("name").jsonPrimitive.content
            val store = FontStore(fallback = chaos)
            val library = library(store)
            library.register(SoundfontBank(name, GmFonts.all.getValue(name)))
            val entries = mutableListOf<Pair<String, Any?>>("s" to name)
            val n = decode(c.getValue("n"))
            if (n !== Undefined) entries += "n" to n
            val controls: ControlMap = mapOf(*entries.toTypedArray())
            val lookup = SoundResolver(SR, library).resolve(controls)
            assertTrue(c.getValue("ok").jsonPrimitive.boolean, "$c")
            val expected = c.getValue("font").jsonPrimitive.content
            if (expected == "undefined") {
                // `fonts[NaN]`: Strudel asks for a file called undefined.js; no font is the Cosic answer, and nothing plays
                assertEquals(SoundLookup.Loading, lookup, "$c")
                assertEquals(emptyList(), store.fetched, "$c")
            } else {
                assertEquals(listOf("https://felixroos.github.io/webaudiofontdata/sound/$expected.js"), store.fetched, "$c")
            }
        }
    }

    @Test
    fun theSoundfontUrlIsWhereFontsAreFetchedFrom() {
        for (case in oracle.getValue("urls").jsonArray) {
            val c = case.jsonObject
            val store = FontStore(fallback = fontFixture("0160_Chaos_sf2_file"))
            val library = library(store)
            GmFonts.register(library)
            (c.getValue("base") as? JsonPrimitive)?.takeIf { it !is JsonNull }?.let { library.setSoundfontUrl(it.content) }
            SoundResolver(SR, library).resolve(mapOf("s" to "gm_flute", "n" to 3.0))
            assertEquals(c.getValue("url").jsonPrimitive.content, store.fetched.single())
        }
    }

    // ------------------------------------------------------------------------------------------------ the zone a note plays

    @Test
    fun theZoneTheRateAndTheLoopAreThoseOfStrudelsLoaderForEveryNoteAndFrequency() {
        val cases = oracle.getValue("zones").jsonArray
        assertTrue(cases.size > 600)
        val libraries = HashMap<String, Pair<FontStore, SoundResolver>>()
        var ok = 0
        var failed = 0
        for (case in cases) {
            val c = case.jsonObject
            val font = c.getValue("font").jsonPrimitive.content
            val (store, resolver) = libraries.getOrPut(font) {
                val store = FontStore(mapOf(font to fontFixture(font)))
                val library = library(store)
                library.register(SoundfontBank("test_font", listOf(font)))
                store to SoundResolver(SR, library)
            }
            val value = decode(c.getValue("value")) as Map<*, *>
            val controls = LinkedHashMap<String, Any?>()
            controls["s"] = "test_font"
            for ((key, v) in value) controls[key as String] = v
            val what = "$font $value"
            if (c.getValue("ok").jsonPrimitive.boolean) {
                ok++
                resolver.resolve(controls)
                val params = assertIs<VoiceSource.Sample>(assertIs<SoundLookup.Ready>(resolver.resolve(controls), what).source).params
                val index = store.datas.entries.single { it.value === params.data }.key.substringAfterLast('#').toInt()
                assertEquals(c.getValue("zone").jsonPrimitive.double.toInt(), index, "zone of $what")
                val rate = c.getValue("rate").jsonPrimitive.double
                assertEquals(rate, 2.0.pow(params.transposeSemitones / 12.0), abs(rate) * 1e-12, "rate of $what")
                if (c.getValue("loop").jsonPrimitive.boolean) {
                    val seconds = { frames: Double -> frames / params.data.sampleRate }
                    assertEquals(c.getValue("loopStart").jsonPrimitive.double, seconds(params.loopStartFrame), 1e-12, "loopStart of $what")
                    assertEquals(c.getValue("loopEnd").jsonPrimitive.double, seconds(params.loopEndFrame), 1e-12, "loopEnd of $what")
                } else {
                    assertTrue(params.loopStartFrame.isNaN() && params.loopEndFrame.isNaN(), "no loop for $what")
                }
                assertEquals(false, params.honoursSampleControls)
            } else {
                failed++
                val error = c.getValue("error").jsonPrimitive.content
                if (error.startsWith("unexpected \"note\" type") || error.startsWith("not a note:")) {
                    assertEquals(error, assertFailsWith<CosicError>(what) { resolver.resolve(controls) }.message, what)
                } else {
                    // no zone, or a zone without a buffer: nothing is decoded and the event keeps waiting
                    assertEquals(SoundLookup.Loading, resolver.resolve(controls), what)
                    assertEquals(SoundLookup.Loading, resolver.resolve(controls), what)
                }
            }
        }
        assertTrue(ok > 400 && failed > 100, "ok $ok, failed $failed")
    }

    // ------------------------------------------------------------------------------------------------ the gain envelope

    /**
     * The value of an AudioParam timeline made of `setValueAtTime(value, time)` and `linearRampToValueAtTime(value, time)` at [t]: a ramp starts at the time and value
     * of the event before it, a set holds the value before it until its time.
     */
    private fun automation(events: List<List<Any?>>, t: Double): Double {
        var time = events[0][2] as Double
        var value = events[0][1] as Double
        for (event in events.drop(1)) {
            val to = event[1] as Double
            val at = event[2] as Double
            when (event[0]) {
                "setValueAtTime" -> {
                    if (t < at) return value
                    time = at
                    value = to
                }
                "linearRampToValueAtTime" -> {
                    if (t < at) return if (at == time) value else value + (to - value) * (t - time) / (at - time)
                    time = at
                    value = to
                }
                else -> error("unexpected ${event[0]}")
            }
        }
        return value
    }

    @Test
    fun theVoiceFollowsTheGainEnvelopeOfStrudelsSoundfontAndStopsWhereItsSourceStops() {
        val cases = oracle.getValue("envelopes").jsonArray.map { it.jsonObject }.filter { it.getValue("time").jsonPrimitive.double == 0.0 }
        assertTrue(cases.size >= 16)
        for (c in cases) {
            val input = decode(c.getValue("value")) as Map<*, *>
            val events = c.getValue("adsr").jsonArray.map { event -> event.jsonArray.map { decode(it) } }
            val stop = (decode(c.getValue("stopped").jsonArray[0]) as Double)
            val duration = (input["duration"] as Double)
            val controls = mutableListOf<Pair<String, Any?>>("gain" to 1.0)
            for ((key, v) in input) if (key != "duration") controls += (key as String) to v
            val source = VoiceSource.Sample(SampleSourceParams(constantSample(5.0, 1f), peakGain = 0.3, honoursSampleControls = false))
            val frames = (stop * SR).toInt() + SR / 10
            val mixer = Mixer(SR)
            assertTrue(mixer.startVoice(vp(source, duration, *controls.toTypedArray())))
            val left = channel(mixer.renderFrames(frames), 0)
            var worst = 0.0
            for (k in 0 until frames step 7) {
                val expected = automation(events, k.toDouble() / SR)
                worst = maxOf(worst, abs(left[k] - expected))
            }
            assertTrue(worst < 5e-4, "$input: the envelope is off by $worst")
            // the source stops at `stop`: the voice makes no more sound from there (the envelope is 0 by then), and counts as sounding until then
            val end = Math.ceil(stop * SR).toInt()
            for (k in end until frames) assertTrue(left[k] == 0f, "$input: sound after the stop at frame $k")
            val check = Mixer(SR)
            check.startVoice(vp(source, duration, *controls.toTypedArray()))
            check.renderFrames(end - 1)
            assertTrue(check.isSounding, "$input: still sounding one frame before the stop")
            check.renderFrames(2)
            assertTrue(!check.isSounding, "$input: not sounding after the stop")
        }
    }
}
