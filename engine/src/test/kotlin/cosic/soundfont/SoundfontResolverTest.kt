package cosic.soundfont

import cosic.audio.SR
import cosic.audio.SampleSourceParams
import cosic.audio.VoiceSource
import cosic.pattern.ControlMap
import cosic.pattern.CosicError
import cosic.pattern.Undefined
import cosic.player.SoundLookup
import cosic.player.SoundResolver
import cosic.player.resolve
import cosic.sound.RegistryLibrary
import java.util.concurrent.Executor
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SoundfontResolverTest {
    private val chaos = fontFixture("0160_Chaos_sf2_file")
    private val direct = Executor { it.run() }

    private fun library(store: FontStore = FontStore(fallback = chaos), executor: Executor? = direct): RegistryLibrary =
        RegistryLibrary(store, emptySet()).also {
            it.fontExecutor = executor
            GmFonts.register(it)
        }

    private fun controls(vararg entries: Pair<String, Any?>): ControlMap = mapOf(*entries)

    /** The sound of an event, asked twice: the first ask of a font starts its load, which the direct executor finishes before it returns. */
    private fun SoundResolver.ready(vararg entries: Pair<String, Any?>): SampleSourceParams {
        resolve(controls(*entries))
        val lookup = resolve(controls(*entries))
        return assertIs<VoiceSource.Sample>(assertIs<SoundLookup.Ready>(lookup).source).params
    }

    private fun SampleSourceParams.zoneOf(store: FontStore): String = store.datas.entries.single { it.value === data }.key

    // ------------------------------------------------------------------------------------------------ loading

    @Test
    fun aSoundfontIsLoadingUntilItsFontAndZoneAreDecoded() {
        val store = FontStore(fallback = chaos)
        val jobs = QueuedExecutor()
        val resolver = SoundResolver(SR, library(store, jobs))
        assertSame(SoundLookup.Loading, resolver.resolve(controls("s" to "gm_piano")))
        assertSame(SoundLookup.Loading, resolver.resolve(controls("s" to "gm_piano")))
        assertEquals(1, jobs.pending)
        jobs.runAll()
        assertIs<SoundLookup.Ready>(resolver.resolve(controls("s" to "gm_piano")))
    }

    @Test
    fun aNoteWhoseFontCannotBeLoadedStaysLoadingAndAnUnknownNameIsUnknown() {
        collectingLog { log ->
            val store = FontStore(fallback = chaos).also { it.offline = true }
            val resolver = SoundResolver(SR, library(store))
            assertSame(SoundLookup.Loading, resolver.resolve(controls("s" to "gm_piano")))
            assertSame(SoundLookup.Loading, resolver.resolve(controls("s" to "gm_piano")))
            assertEquals(1, log.size)
            assertEquals("gm_nothing", assertIs<SoundLookup.Unknown>(resolver.resolve(controls("s" to "gm_nothing"))).key)
        }
    }

    // ------------------------------------------------------------------------------------------------ what plays

    @Test
    fun theSourceIsTheSampleOfTheZoneThatCoversTheNoteTunedAndLoopedAsItsFontSays() {
        val store = FontStore(fallback = chaos)
        val resolver = SoundResolver(SR, library(store))
        // `note` c3 = 48 is in zone 0 (originalPitch 6800): (4800 - 6800) / 100 semitones, and its loop 50..262
        val c3 = resolver.ready("s" to "gm_piano", "note" to "c3")
        assertEquals("0000_JCLive_sf2_file#0", c3.zoneOf(store))
        assertEquals(-20.0, c3.transposeSemitones, 1e-12)
        assertEquals(0.3, c3.peakGain)
        assertEquals(50.0, c3.loopStartFrame, 1e-9)
        assertEquals(262.0, c3.loopEndFrame, 1e-9)
        assertFalse(c3.honoursSampleControls)
        assertEquals(0.0, c3.nudgeSeconds)
        // 60 is in zone 1 (the same recording at another range): (6000 - 6800) / 100
        val c4 = resolver.ready("s" to "gm_piano", "note" to 60.0)
        assertEquals("0000_JCLive_sf2_file#1", c4.zoneOf(store))
        assertEquals(-8.0, c4.transposeSemitones, 1e-12)
        // zone 2 is tuned by 44 cents: base 9000 - 44, so MIDI note 80 sounds (8000 - 8956) / 100 semitones
        val g5 = resolver.ready("s" to "gm_piano", "note" to 80.0)
        assertEquals("0000_JCLive_sf2_file#2", g5.zoneOf(store))
        assertEquals(-9.56, g5.transposeSemitones, 1e-12)
        assertEquals(19.0, g5.loopStartFrame, 1e-9)
        assertEquals(80.0, g5.loopEndFrame, 1e-9)
    }

    @Test
    fun anEventWithoutANoteIsC3() {
        val store = FontStore(fallback = chaos)
        val resolver = SoundResolver(SR, library(store))
        assertEquals(-20.0, resolver.ready("s" to "gm_piano").transposeSemitones, 1e-12)
        assertEquals(-20.0, resolver.ready("s" to "gm_piano", "note" to Undefined).transposeSemitones, 1e-12)
        assertEquals(-20.0, resolver.ready("s" to "gm_piano", "note" to "c").transposeSemitones, 1e-12)
    }

    @Test
    fun theLoopIsInFramesOfTheDataTheDecoderMade() {
        val store = FontStore(fallback = chaos, rate = 22_050)
        val params = SoundResolver(SR, library(store)).ready("s" to "gm_piano", "note" to "c3")
        assertEquals(25.0, params.loopStartFrame, 1e-9, "the font's 50 frames at 44.1 kHz are 25 at 22.05 kHz")
        assertEquals(131.0, params.loopEndFrame, 1e-9)
    }

    @Test
    fun aFrequencyIsUsedInsteadOfTheNoteWhenItIsNotZero() {
        val store = FontStore(fallback = chaos)
        val resolver = SoundResolver(SR, library(store))
        // 440 Hz is MIDI 69, in zone 1: (6900 - 6800) / 100
        assertEquals(1.0, resolver.ready("s" to "gm_piano", "freq" to 440.0).transposeSemitones, 1e-9)
        assertEquals(1.0, resolver.ready("s" to "gm_piano", "freq" to 440.0, "note" to "c3").transposeSemitones, 1e-9, "freq wins")
        assertEquals(-20.0, resolver.ready("s" to "gm_piano", "freq" to 0.0).transposeSemitones, 1e-12, "a freq of 0 is no freq")
        assertEquals(1.0, resolver.ready("s" to "gm_piano", "freq" to "440").transposeSemitones, 1e-9, "a string is a number to freqToMidi")
        assertEquals(-8.0, resolver.ready("s" to "gm_piano", "freq" to 0.0, "note" to 60.0).transposeSemitones, 1e-12)
    }

    @Test
    fun aNoteWithoutAZoneStaysLoadingWithoutAnError() {
        collectingLog { log ->
            val resolver = SoundResolver(SR, library())
            for (note in listOf(-1.0, 128.5, Double.NaN, Double.POSITIVE_INFINITY)) {
                assertSame(SoundLookup.Loading, resolver.resolve(controls("s" to "gm_piano", "note" to note)), "note $note")
            }
            assertSame(SoundLookup.Loading, resolver.resolve(controls("s" to "gm_piano", "freq" to 1.0)), "MIDI -39")
            assertTrue(log.all { "no soundfont zone found" in it }, log.toString())
        }
    }

    // ------------------------------------------------------------------------------------------------ errors of the event

    @Test
    fun aNoteThatIsNotANoteNameIsAnErrorAndSoIsOneOfAnotherType() {
        val resolver = SoundResolver(SR, library())
        assertEquals("not a note: \"x\"", assertFailsWith<CosicError> { resolver.resolve(controls("s" to "gm_piano", "note" to "x")) }.message)
        assertEquals("not a note: \"60\"", assertFailsWith<CosicError> { resolver.resolve(controls("s" to "gm_piano", "note" to "60")) }.message)
        assertEquals("unexpected \"note\" type \"boolean\"", assertFailsWith<CosicError> { resolver.resolve(controls("s" to "gm_piano", "note" to true)) }.message)
        assertEquals("unexpected \"note\" type \"object\"", assertFailsWith<CosicError> { resolver.resolve(controls("s" to "gm_piano", "note" to null)) }.message)
        assertEquals("unexpected \"note\" type \"object\"", assertFailsWith<CosicError> { resolver.resolve(controls("s" to "gm_piano", "note" to listOf(60.0))) }.message)
        assertEquals("unexpected \"note\" type \"object\"", assertFailsWith<CosicError> { resolver.resolve(controls("s" to "gm_piano", "note" to emptyMap<String, Any?>())) }.message)
    }

    // ------------------------------------------------------------------------------------------------ n and names

    @Test
    fun nPicksTheFontAndWrapsAroundCountingBackFromTheEnd() {
        val piano = GmFonts.all.getValue("gm_piano")
        for ((n, index) in listOf(null to 0, 0.0 to 0, 1.0 to 1, 5.0 to 5, 31.0 to 31, 32.0 to 0, 33.0 to 1, -1.0 to 31, -32.0 to 0, 2.5 to 3, 2.4 to 2, 1e9 to 1_000_000_000 % 32, Double.NaN to 0)) {
            val store = FontStore(fallback = chaos)
            val resolver = SoundResolver(SR, library(store))
            val entries = mutableListOf<Pair<String, Any?>>("s" to "gm_piano")
            if (n != null) entries += "n" to n
            resolver.resolve(controls(*entries.toTypedArray()))
            assertEquals(listOf("https://felixroos.github.io/webaudiofontdata/sound/${piano[index]}.js"), store.fetched, "n = $n")
        }
    }

    @Test
    fun theNameIsFoundInAnyCaseAndWithABankAndAFontsOwnNumberIsTheSampleNumber() {
        val store = FontStore(fallback = chaos)
        val resolver = SoundResolver(SR, library(store))
        resolver.ready("s" to "GM_Piano")
        assertIs<SoundLookup.Ready>(resolver.resolve(controls("bank" to "gm", "s" to "piano")))
        assertEquals(1, store.fetched.size, "all the same font")
        val flute = GmFonts.all.getValue("gm_flute")
        val other = FontStore(fallback = chaos)
        SoundResolver(SR, library(other)).resolve(controls("s" to "gm_flute", "n" to 3.0))
        assertEquals("https://felixroos.github.io/webaudiofontdata/sound/${flute[3]}.js", other.fetched.single())
    }

    // ------------------------------------------------------------------------------------------------ what a soundfont ignores

    @Test
    fun theSamplerControlsDoNothingToASoundfont() {
        val store = FontStore(fallback = chaos)
        val resolver = SoundResolver(SR, library(store))
        val plain = resolver.ready("s" to "gm_piano", "note" to 60.0)
        val busy = resolver.ready(
            "s" to "gm_piano", "note" to 60.0, "begin" to 0.5, "end" to 0.6, "speed" to -2.0, "unit" to "c", "loop" to 1.0, "loopBegin" to 0.1, "loopEnd" to 0.2,
            "cut" to 1.0, "nudge" to 0.5, "clip" to 1.0, "accelerate" to 3.0,
        )
        assertSame(plain.data, busy.data)
        assertEquals(plain.transposeSemitones, busy.transposeSemitones)
        assertEquals(plain.loopStartFrame, busy.loopStartFrame)
        assertEquals(plain.loopEndFrame, busy.loopEndFrame)
        assertEquals(0.0, busy.nudgeSeconds)
        assertFalse(busy.honoursSampleControls)
    }

    @Test
    fun theRateOfTheSourceIsTheOneStrudelsLoaderSets() {
        val store = FontStore(fallback = chaos)
        val resolver = SoundResolver(SR, library(store))
        val params = resolver.ready("s" to "gm_piano", "note" to "eb4")
        // zone 1: base detune 6800, MIDI 63
        assertEquals(2.0.pow((6300.0 - 6800.0) / 1200.0), 2.0.pow(params.transposeSemitones / 12.0), 1e-12)
        assertNotNull(params.data)
    }
}
