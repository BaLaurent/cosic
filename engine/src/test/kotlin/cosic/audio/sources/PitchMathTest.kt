package cosic.audio.sources

import cosic.pattern.CosicError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PitchMathTest {
    private fun hz(vararg controls: Pair<String, Any?>, defaultNote: Double = 36.0) =
        frequencyFromValue(mapOf(*controls).asSourceControls(), defaultNote)

    @Test
    fun frequencyFromValueFollowsGetFrequencyFromValue() {
        assertEquals(65.40639132514966, hz(), 1e-9, "the default note is 36")
        assertEquals(440.0, hz("note" to 69.0), 1e-9)
        assertEquals(130.8127826502993, hz("note" to "c3"), 1e-9)
        assertEquals(220.0, hz("note" to "a"), 1e-9, "a name without an octave is in octave 3")
        assertEquals(233.0818807590, hz("note" to "bb3"), 1e-8)
        assertEquals(65.4064, hz("note" to 0.0), 1e-3, "note 0 is falsy: it plays the default")
        assertEquals(261.6255653006, hz("note" to 48.0, "octave" to 1.0), 1e-8)
        assertEquals(110.0, hz("freq" to 220.0, "octave" to -1.0), 1e-9)
        assertEquals(123.0, hz("freq" to 123.0, "note" to 60.0), 1e-9, "freq wins over note")
        assertEquals(43.6535289291, hz(defaultNote = 29.0), 1e-8, "sbd's default note")
        assertEquals(65.40639132514966, hz("note" to "", "freq" to 0.0), 1e-9, "empty text and freq 0 are falsy too")
    }

    @Test
    fun aNoteThatIsNotAPitchIsAnError() {
        assertFailsWith<CosicError> { hz("note" to "x") }
    }

    @Test
    fun theVoicePathGivesTheSameFrequenciesFromAParamSet() {
        fun fromParams(vararg controls: Pair<String, Any?>) = frequencyFromParams(cosic.player.paramSetOf(mapOf(*controls)))
        assertEquals(65.40639132514966, fromParams(), 1e-9)
        assertEquals(130.8127826502993, fromParams("note" to "c3"), 1e-9)
        assertEquals(65.4064, fromParams("note" to 0.0), 1e-3)
        assertEquals(261.6255653006, fromParams("note" to 48.0, "octave" to 1.0), 1e-8)
        assertEquals(123.0, fromParams("freq" to 123.0, "note" to 60.0), 1e-9)
    }

    @Test
    fun sourceControlsReadNumbersLeniently() {
        val c = mapOf<String, Any?>("a" to 2.0, "b" to "3.5", "c" to true, "d" to "x", "e" to Double.NaN, "f" to listOf(1.0, "2"), "g" to "text").asSourceControls()
        assertEquals(2.0, c.number("a"))
        assertEquals(3.5, c.number("b"))
        assertEquals(1.0, c.number("c"))
        assertEquals(null, c.number("d"))
        assertEquals(null, c.number("e"))
        assertEquals(null, c.number("missing"))
        assertEquals("text", c.text("g"))
        assertEquals(null, c.text("a"))
        assertEquals(listOf(1.0, 2.0), c.list("f")!!.toList())
        assertEquals(true, c.isSet("a"))
        assertEquals(false, c.isSet("missing"))
    }
}
