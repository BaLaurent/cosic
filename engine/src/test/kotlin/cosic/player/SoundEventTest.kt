package cosic.player

import cosic.audio.MAX_FX
import cosic.audio.ModKind
import cosic.audio.Param
import cosic.audio.synth
import cosic.pattern.Rational
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** How the controls of a hap become the parameters of a voice, and how long the hap lasts. */
class SoundEventTest {
    private val source = synth("sine", 1.5, emptyMap())

    private fun params(vararg controls: Pair<String, Any?>) = voiceParams(mapOf(*controls), source, 1.5, 0.5, 2.0)

    @Test
    fun numbersLandInTheParamOfTheirCanonicalName() {
        val p = params(
            "gain" to 0.5, "cutoff" to 800.0, "resonance" to 3.0, "hcutoff" to 100.0, "bandf" to 1000.0, "roomsize" to 4.0, "postgain" to 2.0,
            "release" to 0.3, "speed" to -1.0,
        )
        assertSame(source, p.source)
        assertEquals(1.5, p.durationSec)
        assertEquals(0.5, p.cps)
        assertEquals(2.0, p.cycle)
        val main = p.main
        assertEquals(0.5, main[Param.gain])
        assertEquals(800.0, main[Param.cutoff])
        assertEquals(3.0, main[Param.resonance])
        assertEquals(100.0, main[Param.hcutoff])
        assertEquals(1000.0, main[Param.bandf])
        assertEquals(4.0, main[Param.roomsize])
        assertEquals(2.0, main[Param.postgain])
        assertEquals(0.3, main[Param.release])
        assertEquals(-1.0, main[Param.speed])
        assertTrue(main[Param.velocity].isNaN(), "controls that were not set stay NaN, so the voice applies its defaults")
        assertTrue(p.fx.isEmpty() && p.mods.isEmpty())
    }

    @Test
    fun sourceChoosersAndUnknownKeysAreNotParameters() {
        // `room("0.9:1")` makes `{room: 0.9, size: 1}`; superdough never reads `size`, nor `s`, `n` or `bank` as parameters
        val main = params("s" to "bd", "n" to 3.0, "bank" to "RolandTR909", "room" to 0.9, "size" to 1.0, "unheard" to 5.0).main
        assertEquals(0.9, main[Param.room])
        assertTrue(Param.entries.filter { it != Param.room }.all { main[it].isNaN() })
    }

    @Test
    fun aPitchIsNoParameterAndABadNoteNameIsNotThisFunctionsError() {
        // `note`, `freq` and `octave` are read from the control map by the sound that plays the event (a source, or the sound lookup of a bank), as superdough does:
        // a name that is no note is an error there, and a sound that has no pitch does not mind
        val main = params("note" to "foo", "freq" to 440.0, "octave" to 1.0).main
        assertTrue(Param.entries.all { main[it].isNaN() })
    }

    @Test
    fun aGainThatIsNoNumberCountsAsOne() {
        assertEquals(1.0, params("gain" to Double.NaN).main[Param.gain])
        assertEquals(1.0, params("gain" to "loud").main[Param.gain])
        assertEquals(0.25, params("gain" to "0.25").main[Param.gain], "a string that reads as a number is that number")
        assertTrue(params().main[Param.gain].isNaN(), "an absent gain stays unset: the default is 0.8")
    }

    @Test
    fun booleansAndNumericStringsAreReadAsNumbers() {
        val main = params("loop" to true, "clip" to false, "coarse" to "4", "crush" to "many").main
        assertEquals(1.0, main[Param.loop])
        assertEquals(0.0, main[Param.clip])
        assertEquals(4.0, main[Param.coarse])
        assertTrue(main[Param.crush].isNaN())
    }

    @Test
    fun textControlsAndListControls() {
        val main = params(
            "vowel" to "a", "ir" to "ir1", "ftype" to "ladder", "lpshape" to "sine", "channels" to listOf(2.0, 3.0), "duckorbit" to 2.0,
            "duckattack" to listOf(0.1, 0.4),
        ).main
        assertEquals("a", main.str("vowel"))
        assertEquals("ir1", main.str("ir"))
        assertEquals("ladder", main.str("ftype"))
        assertEquals("sine", main.str("lpshape"))
        assertContentEquals(doubleArrayOf(2.0, 3.0), main.list("channels"))
        assertContentEquals(doubleArrayOf(2.0), main.list("duckorbit"), "a number is a list of one")
        assertEquals(2.0, main[Param.duckorbit])
        assertContentEquals(doubleArrayOf(0.1, 0.4), main.list("duckattack"))
        assertNull(main.list("duckonset"))
        // a number where text is allowed stays a number
        assertEquals(2.0, params("ftype" to 2.0).main[Param.ftype])
    }

    @Test
    fun fxElementsAndModulatorsAreSplitOutPerEffectChain() {
        val lfo = mapOf(
            "__ids" to linkedSetOf<Any?>(0.0),
            "0" to mapOf("control" to "cutoff", "rate" to 4.0, "shape" to "sine", "sync" to 2.0),
        )
        val env = mapOf("__ids" to linkedSetOf<Any?>("a"), "a" to mapOf("control" to "gain", "subControl" to "postgain", "attack" to 0.5, "fxi" to 1.0))
        val p = params(
            "s" to "saw", "cutoff" to 500.0, "lfo" to lfo,
            "FX" to listOf(mapOf("room" to 0.5), mapOf("hcutoff" to 300.0, "env" to env)),
        )
        assertEquals(2, p.fx.size)
        assertEquals(0.5, p.fx[0][Param.room])
        assertEquals(300.0, p.fx[1][Param.hcutoff])
        assertTrue(p.fx[0][Param.cutoff].isNaN(), "each element has its own controls")
        assertEquals(500.0, p.main[Param.cutoff])

        assertEquals(2, p.mods.size)
        val inFx = p.mods[0]
        assertEquals(ModKind.ENV, inFx.kind)
        assertEquals("a", inFx.id)
        assertEquals("1", inFx.fxi, "fxi from the options wins over the chain the modulator was declared in")
        assertEquals("gain", inFx.control)
        assertEquals("postgain", inFx.subControl)
        assertEquals(mapOf("attack" to 0.5), inFx.numbers)
        val inMain = p.mods[1]
        assertEquals(ModKind.LFO, inMain.kind)
        assertEquals("0", inMain.id)
        assertEquals("main", inMain.fxi)
        assertEquals("cutoff", inMain.control)
        assertNull(inMain.subControl)
        assertEquals(mapOf("rate" to 4.0, "sync" to 2.0), inMain.numbers)
        assertEquals(mapOf("shape" to "sine"), inMain.strings)
    }

    @Test
    fun effectChainsBeyondTheLimitAreDropped() {
        val elements = List(MAX_FX + 3) { mapOf("room" to it.toDouble()) }
        val p = params("FX" to elements)
        assertEquals(MAX_FX, p.fx.size)
        assertEquals((MAX_FX - 1).toDouble(), p.fx.last()[Param.room])
    }

    // ------------------------------------------------------------------ duration

    @Test
    fun aHapLastsItsWholeUnlessDurationOrClipSayOtherwise() {
        val cycle = Rational(1)
        assertEquals(2.0, hapSeconds(emptyMap(), cycle, 0.5))
        assertEquals(1.0, hapSeconds(mapOf("clip" to 0.5), cycle, 0.5), "whole = 1 cycle at cps 0.5, clip 0.5")
        assertEquals(1.0, hapSeconds(mapOf("duration" to 0.25, "clip" to 2.0), cycle, 0.5), "duration (in cycles) replaces the whole, then clip multiplies")
        assertEquals(0.5, hapSeconds(mapOf("duration" to 0.25), cycle, 0.5))
        assertEquals(2.0, hapSeconds(mapOf("clip" to "long"), cycle, 0.5), "a clip that is no number does nothing")
        assertEquals(0.125, hapSeconds(emptyMap(), Rational(1, 4), 2.0))
    }
}
