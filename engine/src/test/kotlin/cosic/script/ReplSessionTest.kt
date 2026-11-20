package cosic.script

import cosic.pattern.CosicError
import cosic.pattern.Pattern
import cosic.pattern.Rational
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The haps of [pattern] over [cycles] cycles as `hap.show(true)` prints them, sorted by part like Strudel's `queryCode`. */
internal fun shown(pattern: Pattern, cycles: Int = 1): List<String> =
    pattern.sortHapsByPart().queryArc(Rational.ZERO, Rational(cycles.toLong())).map { it.show(true) }

/**
 * What the REPL does with a program (LANG 3.2): the labels it registers, muting and solo, `hush`, `all` and `each`, and
 * the tempo, which outlives an evaluation and a failure.
 */
class ReplSessionTest {
    private fun play(code: String, cycles: Int = 1): List<String> = shown(evaluateProgram(code).pattern, cycles)

    @Test
    fun withoutLabelsTheValueOfTheLastStatementPlays() {
        assertEquals(listOf("[ 0/1 → 1/2 | s:bd ]", "[ 1/2 → 1/1 | s:hh ]"), play("""s("bd hh")"""))
    }

    @Test
    fun anonymousLabelsAllPlayInTheOrderTheyAreWritten() {
        assertEquals(
            listOf("[ 0/1 → 1/2 | s:hh ]", "[ 0/1 → 1/1 | s:bd ]", "[ 1/2 → 1/1 | s:hh ]"),
            play("\$: s(\"bd\")\n\$: s(\"hh*2\")"),
        )
    }

    @Test
    fun namedLabelsStackAndTheLastOfARepeatedNameWins() {
        assertEquals(listOf("[ 0/1 → 1/1 | s:hh ]"), play("a: s(\"bd\")\na: s(\"hh\")"))
        assertEquals(listOf("[ 0/1 → 1/1 | s:sd ]", "[ 0/1 → 1/1 | s:hh ]"), play("b: s(\"bd\")\na: s(\"hh\")\nb: s(\"sd\")"))
    }

    @Test
    fun aLabelStartingOrEndingWithUnderscoreIsMuted() {
        assertEquals(listOf("[ 0/1 → 1/1 | s:bd ]"), play("kick: s(\"bd\")\n_hats: s(\"hh*2\")"))
        assertEquals(listOf("[ 0/1 → 1/1 | s:bd ]"), play("kick: s(\"bd\")\nhats_: s(\"hh*2\")"))
    }

    @Test
    fun onlyMutedLabelsLeaveTheLastExpressionToPlay() {
        assertEquals(listOf("[ 0/1 → 1/1 | s:hh ]"), play("_\$: s(\"bd\")\ns(\"hh\")"))
        assertEquals(emptyList(), play("hats_: s(\"hh\")"))
    }

    @Test
    fun aLabelStartingWithACapitalSIsASolo() {
        assertEquals(listOf("[ 0/1 → 1/1 | s:bd ]"), play("S\$: s(\"bd\")\n\$: s(\"hh\")"))
        assertEquals(listOf("[ 0/1 → 1/1 | s:sd ]"), play("Snare: s(\"sd\")\nhats: s(\"hh\")"))
    }

    @Test
    fun unlabelledExpressionsAreIgnoredOnceThereIsALabel() {
        assertEquals(listOf("[ 0/1 → 1/1 | s:bd ]"), play("s(\"sd\")\n\$: s(\"bd\")"))
    }

    @Test
    fun hushForgetsWhatWasLabelledBeforeIt() {
        assertEquals(listOf("[ 0/1 → 1/1 | s:hh ]"), play("\$: s(\"bd\")\nhush()\n\$: s(\"hh\")"))
        assertEquals(emptyList(), play("\$: s(\"bd\")\nhush()"))
    }

    @Test
    fun allAppliesToTheStackAndEachToEveryPattern() {
        assertEquals(play("\$: s(\"bd\").fast(2)"), play("\$: s(\"bd\")\nall(x => x.fast(2))"))
        assertEquals(
            play("\$: s(\"bd sd\").fast(2)\n\$: s(\"hh*2\").fast(2)"),
            play("\$: s(\"bd sd\")\n\$: s(\"hh*2\")\neach(x => x.fast(2))"),
        )
        // `all` transforms are applied in the order they were called, `each` keeps the last one
        assertEquals(play("\$: s(\"bd\").s(\"sd\").fast(2)"), play("\$: s(\"bd\")\nall(x => x.s(\"sd\"))\nall(x => x.fast(2))"))
        assertEquals(play("\$: s(\"bd\").fast(3)"), play("\$: s(\"bd\")\neach(x => x.fast(2))\neach(x => x.fast(3))"))
    }

    @Test
    fun theTempoIsSetAtOnceAndOutlivesTheEvaluation() {
        val session = ReplSession.create()
        assertEquals(0.5, session.evaluate("\$: s(\"bd\")").cps)
        assertEquals(1.0, session.evaluate("setcps(1)\n\$: s(\"bd\")").cps)
        assertEquals(1.0, session.evaluate("\$: s(\"hh\")").cps)
        assertEquals(2.0, session.evaluate("setcpm(120)").cps)
        assertEquals(0.5, session.evaluate("setCps(0.5)").cps)
        assertEquals(1.0, session.evaluate("""setcps("1")""").cps)
    }

    @Test
    fun aProgramThatFailsAfterSettingTheTempoLeavesItSet() {
        val session = ReplSession.create()
        val error = assertFailsWith<CosicError> { session.evaluate("setcps(2)\nfoo") }
        assertEquals("foo is not defined", error.message)
        assertEquals(2.0, session.host.cps)
    }

    @Test
    fun theTempoCanBeAskedForOnlyInTheRangeTheEngineCanPlay() {
        for (code in listOf("""setcps("<1 2>")""", "setcps(0)", "setcps(-1)", "setcps(11)", "setcpm('x')")) {
            val error = assertFailsWith<CosicError>(code) { evaluateProgram(code) }
            assertEquals("Tempo out of range", error.message, code)
        }
    }

    @Test
    fun cpmPlaysAPatternAtTheTempoItIsGiven() {
        // the pattern is sped up by cpm / 60 / cps: 120 cpm is 2 cycles per second, 4 times the default tempo
        assertEquals(play("s(\"bd sd\").fast(4)"), play("s(\"bd sd\").cpm(120)"))
        assertEquals(play("s(\"bd sd\").fast(2)"), play("setcps(1)\ns(\"bd sd\").cpm(120)"))
    }

    @Test
    fun theRangesOfTheMiniNotationAreReportedForHighlighting() {
        val result = evaluateProgram("\$: s(\"bd\")\n\$: s(\"hh*2\")")
        assertEquals(listOf(6 to 8, 17 to 19, 20 to 21), result.miniLocations.map { it.start to it.end })
    }

    @Test
    fun aSliderIsReportedWithTheIdTheTranspilerGaveIt() {
        val code = "s(\"bd\").gain(slider(0.5, 0, 1))"
        val result = evaluateProgram(code)
        assertEquals(listOf("slider_${code.indexOf("0.5")}"), result.sliders.map { it.id })
    }

    @Test
    fun theSliderGivesTheValueTheHostHoldsEveryTimeItIsQueried() {
        val code = "s(\"bd\").gain(slider(0.25, 0, 1))"
        val id = "slider_${code.indexOf("0.25")}"
        val session = ReplSession.create()
        val pattern = session.evaluate(code).pattern
        assertEquals(listOf("[ 0/1 → 1/1 | s:bd gain:0.25 ]"), shown(pattern))
        session.host.sliderSet(id, 0.75)
        assertEquals(listOf("[ 0/1 → 1/1 | s:bd gain:0.75 ]"), shown(pattern))
    }

    @Test
    fun aProgramWithoutAnExpressionIsSilent() {
        assertEquals(emptyList(), play("// nothing"))
        assertEquals(emptyList(), play("const a = 1"))
    }

    @Test
    fun errorsCarryTheRangeOfTheCallThatFailed() {
        val code = "s(\"bd\")\n  .notAFunction()"
        val error = assertFailsWith<CosicError> { evaluateProgram(code) }
        assertEquals("s(...).notAFunction is not a function", error.message)
        val range = error.range!!
        assertTrue(range.start <= code.indexOf(".notAFunction") && range.end == code.length, "range is $range")
    }

    @Test
    fun anErrorWhileAPatternIsQueriedIsReportedAtTheCallThatMadeIt() {
        val code = "s(\"bd\").fast(\"<1 x>\")"
        val pattern = evaluateProgram(code).pattern
        val error = assertFailsWith<CosicError> { pattern.queryArc(Rational.ZERO, Rational(2)) }
        assertTrue(error.range != null, "an error raised when the pattern is queried has no range")
    }

    @Test
    fun aSessionKeepsNothingButTheTempoWhenAProgramHasNoLabels() {
        val session = ReplSession.create()
        session.evaluate("\$: s(\"bd\")")
        assertEquals(listOf("[ 0/1 → 1/1 | s:hh ]"), shown(session.evaluate("s(\"hh\")").pattern))
    }
}
