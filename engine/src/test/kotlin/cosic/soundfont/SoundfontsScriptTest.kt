package cosic.soundfont

import cosic.audio.SR
import cosic.audio.channel
import cosic.audio.rms
import cosic.pattern.CosicError
import cosic.render.OfflineRenderer
import cosic.script.evaluateProgram
import cosic.script.shown
import cosic.soundfont.sf2.sf2Resource
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** `loadSoundfont(url)` and `.soundfont(sf, n)` in programs, over the generated `test.sf2` that the oracle of sfumato used. */
class SoundfontsScriptTest {
    private val fetched = ArrayList<String>()
    private val oldFetch = SoundfontFiles.fetch

    @BeforeTest
    fun serveTheFont() {
        SoundfontFiles.clear()
        SoundfontFiles.fetch = { url ->
            fetched += url
            if (url.endsWith("missing.sf2")) throw IOException("404 $url")
            if (url.endsWith("garbage.sf2")) "not a soundfont".toByteArray() else sf2Resource("test.sf2")
        }
    }

    @AfterTest
    fun restore() {
        SoundfontFiles.fetch = oldFetch
        SoundfontFiles.clear()
    }

    private val load = """const sf = await loadSoundfont('https://example.test/a.sf2')"""

    private fun haps(code: String): List<String> = shown(evaluateProgram(code).pattern)

    @Test
    fun theLoadedSoundfontHasTheObjectsOfTheFile() {
        // two presets, the second is number 1; 3 zones in the first instrument ... 2, the sample rate of the second sample
        assertEquals(
            listOf("[ 0/1 → 1/1 | n:2 gain:1 speed:2 cutoff:44100 ]"),
            haps("$load\nn(sf.presets.length).gain(sf.presets[1].header.preset).speed(sf.instruments[0].zones.length).lpf(sf.samples[1].header.sampleRate)"),
        )
        assertEquals(
            listOf("[ 0/1 → 1/1 | n:0 gain:-250 speed:60 cutoff:2 ]"),
            haps("$load\nn(0).gain(sf.instruments[0].zones[1].generators[17].value).speed(sf.presets[0].zones[0].instrument.zones[1].keyRange.lo).lpf(sf.metaData.version.length - 1)"),
        )
    }

    @Test
    fun theSameUrlIsFetchedOnce() {
        haps("$load\nconst again = await loadSoundfont('https://example.test/a.sf2')\nn(again === sf ? 1 : 0)")
        assertEquals(1, fetched.size)
        assertEquals(listOf("https://example.test/a.sf2"), fetched)
    }

    @Test
    fun soundfontKeepsTheValuesOfTheHaps() {
        val plain = haps("""note("c3 e3").s("sawtooth").lpf(300)""")
        assertEquals(plain, haps("$load\nnote(\"c3 e3\").s(\"sawtooth\").lpf(300).soundfont(sf)"))
        assertEquals(plain, haps("$load\nnote(\"c3 e3\").s(\"sawtooth\").lpf(300).soundfont(sf, 1)"))
    }

    @Test
    fun aFileThatCannotBeLoadedOrReadIsAnErrorOfTheProgram() {
        assertTrue(assertFailsWith<CosicError> { haps("""await loadSoundfont('https://example.test/missing.sf2')""") }.message!!.startsWith("could not load the soundfont https://example.test/missing.sf2: 404"))
        assertTrue(assertFailsWith<CosicError> { haps("""await loadSoundfont('https://example.test/garbage.sf2')""") }.message!!.startsWith("could not read the soundfont"))
    }

    @Test
    fun soundfontNeedsASoundfontAndAPresetThatExists() {
        assertTrue(assertFailsWith<CosicError> { haps("""note("c").soundfont()""") }.message!!.startsWith("soundfont() needs a soundfont"))
        assertTrue(assertFailsWith<CosicError> { haps("""note("c").soundfont('x')""") }.message!!.startsWith("soundfont() needs a soundfont"))
        assertTrue(assertFailsWith<CosicError> { haps("$load\nnote(\"c\").soundfont(sf, 0.5)") }.message!!.contains("no preset"))
        assertTrue(assertFailsWith<CosicError> { haps("$load\nnote(\"c\").soundfont(sf, -1)") }.message!!.contains("no preset"))
        // n counts around: 3 % 2 is preset 1
        haps("$load\nnote(\"c\").soundfont(sf, 3)")
    }

    // ------------------------------------------------------------------------------------------------ sound

    private fun render(code: String, cycles: Double = 1.0): Pair<FloatArray, FloatArray> {
        val out = OfflineRenderer.render(code, cycles)
        return channel(out, 0) to channel(out, 1)
    }

    private fun window(x: FloatArray, from: Double, to: Double) = rms(x, (from * SR).toInt(), (to * SR).toInt())

    @Test
    fun aNoteSoundsThroughTheEngineUntilItsHapLengthInCyclesHasPassedInSeconds() {
        // a4 is in the second zone of Inst One, which loops and is panned left of centre; its hap is one cycle (2 s), and sfumato stops it 1 s (the cycles) after it starts
        val (left, right) = render("$load\nnote(\"a4\").soundfont(sf)")
        assertTrue(window(left, 0.5, 0.9) > 0.01, "sounding: ${window(left, 0.5, 0.9)}")
        assertTrue(window(left, 0.5, 0.9) > window(right, 0.5, 0.9), "left of centre")
        assertTrue(window(left, 1.3, 1.9) < 1e-4, "stopped after one second, not two: ${window(left, 1.3, 1.9)}")
    }

    @Test
    fun theControlsOfTheEventDoNothingToASoundfontNote() {
        val plain = render("$load\nnote(\"a4\").soundfont(sf)").first
        val busy = render("$load\nnote(\"a4\").gain(0).lpf(100).pan(1).room(1).s(\"sawtooth\").soundfont(sf)").first
        assertEquals(window(plain, 0.5, 0.9), window(busy, 0.5, 0.9), 1e-9)
    }

    @Test
    fun aNoteWithoutAZoneOrANameMakesNoSound() {
        // preset 1 covers 20..100 only
        val (silent, _) = render("$load\nnote(\"c0\").soundfont(sf, 1)")
        assertTrue(window(silent, 0.0, 1.9) < 1e-9)
        val (named, _) = render("$load\ns(\"bd\").soundfont(sf)")
        assertTrue(window(named, 0.0, 1.9) < 1e-9)
    }

    @Test
    fun anEventWithoutANoteIsReportedAndSilent() {
        collectingLog { log ->
            val preset = cosic.soundfont.sf2.Sf2File.parse(sf2Resource("test.sf2")).presets[0]
            val ref = PresetRef(preset)
            assertEquals(cosic.player.SoundLookup.Silent, resolvePreset(ref, mapOf(PRESET_CONTROL to ref, PRESET_ERROR_CONTROL to "cannot find a playable note for {\"s\":\"bd\"}")))
            assertEquals(listOf("error: cannot find a playable note for {\"s\":\"bd\"}"), log)
            assertTrue(resolvePreset(ref, mapOf(PRESET_CONTROL to ref, PRESET_NOTE_CONTROL to "a4")) is cosic.player.SoundLookup.Ready)
            assertEquals(cosic.player.SoundLookup.Silent, resolvePreset(ref, mapOf(PRESET_CONTROL to ref, PRESET_NOTE_CONTROL to "g10")), "MIDI 139 is above every key range")
        }
    }

    @Test
    fun playableNotesAreNamesAndNothingElse() {
        assertEquals("c3" to null, playableNote(mapOf("note" to "c3")))
        assertEquals("e4" to null, playableNote(mapOf("n" to "e4", "note" to 0.0)))
        assertEquals("Eb4" to null, playableNote("Eb4"))
        assertEquals(null to "not a note: \"NaN\"", playableNote(mapOf("note" to 60.0)))
        assertEquals(null to "not a note: \"261.6255653005986\"", playableNote(60.0))
        assertEquals(null to "not a note: \"x\"", playableNote(mapOf("note" to "x")))
        assertEquals(null to "cannot find a playable note for {\"s\":\"bd\"}", playableNote(mapOf("s" to "bd")))
        assertEquals(null to "cannot find a playable note for {\"n\":0}", playableNote(mapOf("n" to 0.0)))
    }
}
