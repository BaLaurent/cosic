package cosic

import cosic.audio.SR
import cosic.audio.allFinite
import cosic.audio.channel
import cosic.audio.estimateFrequency
import cosic.audio.peak
import cosic.audio.rms
import cosic.render.OfflineRenderer
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The built-in sounds through the whole path a program takes: the language, the scheduler, the sound lookup that makes a source of each event from its controls, the voice
 * and the chain, rendered offline. Every source family sounds, and the controls that only they read (`unison`, `pw`, `fm`, `vib`, `penv`...) get from the pattern to them.
 */
class SynthSourcesTest {
    /** The left channel of [cycles] cycles of [code] after the start delay, at the engine's rate. */
    private fun render(code: String, cycles: Double = 1.0): FloatArray = channel(OfflineRenderer.render(code, cycles), 0)

    private fun assertSounds(code: String, cycles: Double = 1.0): FloatArray {
        val left = render(code, cycles)
        assertTrue(allFinite(left), "$code produced NaN or infinity")
        assertTrue(peak(left) <= 1.0f, "$code clips: ${peak(left)}")
        assertTrue(peak(left) > 0.01f, "$code is silent: peak ${peak(left)}")
        assertTrue(rms(left) > 0.001, "$code is silent: rms ${rms(left)}")
        return left
    }

    @Test
    fun theSuperSawAlternationOfTheAcceptanceProgramSoundsForFourCycles() {
        val out = OfflineRenderer.render("""note("c3 e3 g3").s("<supersaw sawtooth square triangle>").lpf(800).room(.3)""", cycles = 4.0)
        assertEquals((2400 + 4 * 96_000) * 2, out.size)
        assertTrue(allFinite(out), "NaN or infinity")
        assertTrue(peak(out) <= 1.0f)
        assertTrue(peak(out) > 0.02f, "silent: ${peak(out)}")
        // each of the four cycles has notes in it (the third note of a cycle starts at two thirds of it)
        for (cycle in 0 until 4) {
            val from = (2400 + cycle * 96_000) * 2
            assertTrue(rms(out, from, from + 96_000 * 2) > 0.001, "cycle $cycle is silent")
        }
    }

    @Test
    fun everySourceFamilySounds() {
        for (code in listOf(
            """s("sine")""", """s("sawtooth")""", """s("square")""", """s("triangle")""", """s("sin")""", """s("saw")""", """s("tri")""", """s("sqr")""",
            """s("user").n(5)""", """s("one")""",
            """s("pulse")""", """s("supersaw")""", """s("bytebeat")""", """s("sbd")""",
            """s("white")""", """s("pink")""", """s("brown")""", """s("crackle")""",
            """note("c3").s("sine").fm(2)""", """note("c3").s("sine").vib(4)""", """note("c3").s("sine").penv(12)""",
            """note("c3").s("supersaw").fm(1).vib(5).penv(7)""", """note("c3").s("pulse").pw(.2).pwrate(3).pwsweep(.4).vib(4)""",
        )) {
            assertSounds(code)
        }
    }

    @Test
    fun theZzfxSoundsAreSynthsOfTheRegistryAndSound() {
        for (name in listOf("zzfx", "z_sine", "z_sawtooth", "z_triangle", "z_square", "z_tan", "z_noise")) assertSounds("""note("c4").s("$name")""", 1.0)
    }

    @Test
    fun theFrequencyOfASynthIsItsNoteAndTheDefaultNoteIs36() {
        fun hz(code: String): Double = render(code).let { estimateFrequency(it, 6000, 40_000) }
        assertEquals(440.0, hz("""note("a4").s("sine").slow(2)"""), 0.8)
        assertEquals(220.0, hz("""note("a3").s("sawtooth").slow(2)"""), 0.8)
        assertEquals(65.4, hz("""s("sine").slow(2)"""), 0.5, "no note: 36, C2")
        assertEquals(880.0, hz("""freq(880).s("sine").slow(2)"""), 1.5, "freq")
        assertEquals(880.0, hz("""note("a4").octave(1).s("sine").slow(2)"""), 1.5, "octave")
    }

    @Test
    fun theControlsOfASourceReachItFromThePattern() {
        fun differs(a: String, b: String, what: String) = assertFalse(render(a).contentEquals(render(b)), what)
        differs("""s("supersaw")""", """s("supersaw").unison(2)""", "unison")
        differs("""s("supersaw").unison(4)""", """s("supersaw").unison(4).detune(1.5)""", "detune")
        differs("""s("pulse")""", """s("pulse").pw(.1)""", "pw")
        differs("""s("sine")""", """s("sine").fm(3)""", "fm")
        differs("""s("sine")""", """s("sine").vib(5).vibmod(1)""", "vib")
        differs("""s("sine")""", """s("sine").penv(12)""", "penv")
        differs("""s("sawtooth")""", """s("sawtooth:4")""", "n is a count of partials")
        differs("""s("sine")""", """s("sine").noise(.5)""", "noise")
        differs("""s("bytebeat")""", """s("bytebeat:3")""", "n picks the beat")
        differs("""s("sbd")""", """s("sbd").decay(.2)""", "decay")
        differs("""s("sine")""", """s("sine").attack(.3)""", "attack")
    }

    @Test
    fun aPitchEnvelopeBendsTheNoteUpToItsPitch() {
        // the default envelope rises over 0.2 s to the note, which the semitones of `penv` below it start from
        val left = render("""note("a4").s("sine").penv(12).slow(2)""")
        val early = estimateFrequency(left, 2400 + 200, 2400 + 2200)
        val late = estimateFrequency(left, 2400 + 24_000, 2400 + 40_000)
        assertTrue(early < 0.8 * late, "the note starts at $early Hz and settles at $late Hz")
        assertEquals(440.0, late, 1.0)
    }

    @Test
    fun aSuperSawIsStereoAndAPulseAndAByteBeatAreMonoSignalsOnTwoChannels() {
        val saw = OfflineRenderer.render("""note("c3").s("supersaw").unison(5).spread(1)""", 1.0)
        assertFalse(channel(saw, 0).contentEquals(channel(saw, 1)), "the voices of a supersaw are spread over the stereo field")
        for (code in listOf("""note("c3").s("pulse")""", """s("bytebeat")""", """note("c3").s("sine")""")) {
            val out = OfflineRenderer.render(code, 1.0)
            assertContentEquals(channel(out, 0), channel(out, 1), "$code at the centre")
        }
    }

    @Test
    fun renderingTheSameProgramTwiceGivesTheSameSamples() {
        for (code in listOf("""note("c3 e3").s("<supersaw crackle>").lpf(900)""", """s("white pink brown").room(.2)""", """note("a2").s("pulse").vib(3)""")) {
            assertContentEquals(OfflineRenderer.render(code, 1.0), OfflineRenderer.render(code, 1.0), code)
        }
    }

    @Test
    fun aBankRegisteredUnderASynthsNameReplacesTheSynth() {
        val library = libraryWithBank("supersaw", listOf(cosic.audio.constantSample(0.5, 0.5f)))
        val out = OfflineRenderer.render("""s("supersaw")""", 1.0, library = library)
        assertEquals(0.4, peak(out, 2400 * 2 + 480 * 2).toDouble(), 0.01, "the 0.5 sample times the gain of 0.8: not the 0.3 of a supersaw")
    }
}
