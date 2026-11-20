package cosic.io

import cosic.pattern.Rational
import cosic.script.ReplSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** `slider(...)`: what the program makes of it, how its value is written into the code. */
class SliderTest {
    private fun gains(session: ReplSession, code: String): Pair<cosic.script.ProgramResult, () -> List<String>> {
        val program = session.evaluate(code)
        return program to { program.pattern.queryArc(Rational.ZERO, Rational.ONE).map { it.show(true) } }
    }

    @Test
    fun aSliderIsDescribedByTheTranspilerAndPlaysItsValueAndThenWhateverTheBankHolds() {
        val session = ReplSession.create()
        val code = """$: s("bd").gain(slider(0.5, 0, 1))"""
        val (program, query) = gains(session, code)
        val start = code.indexOf("0.5")
        val spec = program.sliders.single()
        assertEquals("slider_$start", spec.id)
        assertEquals(start, spec.from)
        assertEquals(start + 3, spec.to)
        assertEquals("0.5", spec.value)
        assertEquals(0.5, spec.initial)
        assertEquals(0.0, spec.min)
        assertEquals(1.0, spec.max)
        assertNull(spec.step)
        assertEquals(listOf("[ 0/1 → 1/1 | s:bd gain:0.5 ]"), query())
        // the editor sets the value; the next query has it, with no new evaluation
        session.host.sliderSet(spec.id, 0.75)
        assertEquals(listOf("[ 0/1 → 1/1 | s:bd gain:0.75 ]"), query())
    }

    @Test
    fun aSliderWithASignedNumberHasTheSignInItsRangeAndItsBounds() {
        val session = ReplSession.create()
        val code = """$: s("bd").gain(slider(-0.5, -1, 1))"""
        val (program, query) = gains(session, code)
        val spec = program.sliders.single()
        assertEquals(code.indexOf("-0.5"), spec.from)
        assertEquals("-0.5", code.substring(spec.from, spec.to))
        assertEquals("-0.5", spec.value)
        assertEquals(-0.5, spec.initial)
        assertEquals(-1.0, spec.min)
        assertEquals(1.0, spec.max)
        assertEquals(listOf("[ 0/1 → 1/1 | s:bd gain:-0.5 ]"), query())
        session.host.sliderSet(spec.id, -0.25)
        assertEquals(listOf("[ 0/1 → 1/1 | s:bd gain:-0.25 ]"), query())
    }

    @Test
    fun theInitialValueIsTheLiteralWrittenInTheCodeOrNothing() {
        fun initial(code: String) = ReplSession.create().evaluate(code).sliders.single().initial
        assertEquals(1000.0, initial("slider(1e3, 0, 1e4)"))
        assertEquals(16.0, initial("slider(0x10, 0, 255)"))
        assertEquals(-16.0, initial("slider(-0x10, -255, 255)"))
        assertEquals(1.0, initial("slider(+1)"))
        assertNull(initial("const x = 1; slider(x)"))
    }

    // ---- SliderCodec ---------------------------------------------------------------------------------------------------

    @Test
    fun aValueIsSnappedToTheDefaultStepOfAThousandthOfTheRange() {
        assertEquals("0.123", SliderCodec.format(0.123456, 0.0, 1.0, null))
        assertEquals("1231.9", SliderCodec.format(1234.0, 100.0, 5000.0, null))
        assertEquals("0", SliderCodec.format(-0.0, 0.0, 1.0, null))
        assertEquals("0.5", SliderCodec.format(0.5, 0.0, 1.0, null))
        assertEquals("1", SliderCodec.format(1.0, 0.0, 1.0, null))
    }

    @Test
    fun aValueIsSnappedToTheStepFromTheMinimum() {
        assertEquals("0.3", SliderCodec.format(0.3000000004, 0.0, 1.0, 0.1))
        assertEquals("0.3", SliderCodec.format(0.26, 0.0, 1.0, 0.1))
        assertEquals("7", SliderCodec.format(6.6, 1.0, 10.0, 2.0)) // 1, 3, 5, 7: the grid starts at the minimum
        assertEquals("1.25", SliderCodec.format(1.3, 0.0, 2.0, 0.25))
        assertEquals("-0.4", SliderCodec.format(-0.43, -1.0, 1.0, 0.1))
    }

    @Test
    fun aValueIsClampedToTheBounds() {
        assertEquals("1", SliderCodec.format(5.0, 0.0, 1.0, null))
        assertEquals("-1", SliderCodec.format(-5.0, -1.0, 1.0, null))
        assertEquals("0", SliderCodec.format(Double.NaN, 0.0, 1.0, null))
        assertEquals("0.5", SliderCodec.format(0.5, 1.0, -1.0, null)) // reversed bounds are sorted
    }

    @Test
    fun theDecimalsAreThoseOfTheStepAndTheMinimumAndNoNegativeZeroIsWritten() {
        assertEquals("0.01", SliderCodec.format(0.0123, 0.0, 1.0, 0.01))
        assertEquals("0.125", SliderCodec.format(0.12, 0.125, 1.0, 0.5))
        assertEquals("0", SliderCodec.format(-0.0004, -1.0, 1.0, 0.001))
        assertEquals("100", SliderCodec.format(99.99, 0.0, 1000.0, 10.0))
        assertEquals("0.3", SliderCodec.format(0.1 + 0.2, 0.0, 1.0, 0.1)) // 0.30000000000000004 is not written
    }

    @Test
    fun aDegenerateSliderHasItsOnlyValue() {
        assertEquals("2", SliderCodec.format(7.0, 2.0, 2.0, null))
        assertEquals("0.5", SliderCodec.format(0.5, 0.0, 1.0, 0.0)) // a step of zero is no step
        assertEquals(0.5, SliderCodec.stepOf(0.0, 1.0, 0.5))
        assertEquals(0.5, SliderCodec.stepOf(0.0, 1.0, -0.5))
        assertEquals(0.002, SliderCodec.stepOf(0.0, 2.0, null))
    }
}
