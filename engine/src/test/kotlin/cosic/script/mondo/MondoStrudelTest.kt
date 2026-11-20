package cosic.script.mondo

import cosic.SourceRange
import cosic.pattern.CosicError
import cosic.pattern.Rational
import cosic.script.EmbeddedLanguage
import cosic.script.EmbeddedLanguages
import cosic.script.PatternValue
import cosic.script.ReplSession
import cosic.script.evaluateProgram
import cosic.script.js.PropFlags
import cosic.script.shown
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Mondo as the programs of the app meet it: ``mondo`...` `` and ``mondolang`...` `` in a program, the functions that
 * belong to them, and what the notation means for patterns. The parity cases `extra:mondo.*` compare hundreds of haps with
 * Strudel's; these tests say what each rule is for.
 */
class MondoStrudelTest {
    private fun play(code: String, cycles: Int = 1): List<String> = shown(evaluateProgram(code).pattern, cycles)

    private fun fails(code: String, cycles: Int = 1): CosicError = assertFailsWith<CosicError> { play(code, cycles) }

    private val bdHh = listOf("[ 0/1 → 1/2 | s:bd ]", "[ 1/2 → 1/1 | s:hh ]")

    @Test
    fun aMondoTemplateIsAPattern() {
        assertEquals(bdHh, play("mondo`s [bd hh]`"))
        assertEquals(bdHh, play("mondolang`s [bd hh]`"))
    }

    @Test
    fun aMondoTemplateIsAnExpressionLikeAnyOther() {
        assertEquals(play("s(\"bd hh\").fast(2)"), play("mondo`s [bd hh]`.fast(2)"))
        assertEquals(play("stack(s(\"bd\"), s(\"hh*2\"))"), play("stack(mondo`s bd`, mondo`s hh*2`)"))
        assertEquals(play("\$: s(\"bd\")\n\$: s(\"hh*2\")"), play("\$: mondo`s bd`\n\$: mondo`s hh*2`"))
    }

    @Test
    fun aCallTakesItsArgumentsAsTheyAre() {
        // `s bd hh` is `s("bd", "hh")`: the second argument is the pattern the sound is set on
        assertEquals(listOf("[ 0/1 → 1/1 | value:hh s:bd ]"), play("mondo`s bd hh`"))
        assertEquals(listOf("[ 0/1 → 1/1 | s:bd n:3 ]"), play("mondo`s bd:3`"))
    }

    @Test
    fun theNotationHasTheMeaningOfMiniNotation() {
        assertEquals(play("s(\"bd [hh cp]\")"), play("mondo`s [bd [hh cp]]`"))
        assertEquals(play("s(\"<bd hh>\")", 2), play("mondo`s <bd hh>`", 2))
        assertEquals(play("s(\"hh*2 bd/2\")", 2), play("mondo`s [hh*2 bd/2]`", 2))
        assertEquals(play("s(\"bd@3 hh\")"), play("mondo`s [bd@3 hh]`"))
        assertEquals(play("s(\"bd!2 hh\")"), play("mondo`s [bd!2 hh]`"))
        assertEquals(play("s(\"bd, hh cp\")"), play("mondo`s [bd, hh cp]`"))
        assertEquals(play("n(\"0 .. 3\")"), play("mondo`n 0..3`"))
        assertEquals(play("s(\"bd(3,8)\")"), play("mondo`s bd&3:8`"))
    }

    @Test
    fun aRangeCountsDownAsWellAsUp() {
        assertEquals(listOf("[ 0/1 → 1/4 | n:3 ]", "[ 1/4 → 1/2 | n:2 ]", "[ 1/2 → 3/4 | n:1 ]", "[ 3/4 → 1/1 | n:0 ]"), play("mondo`n 3..0`"))
        assertEquals(listOf("[ 0/1 → 1/1 | n:2 ]"), play("mondo`n 2..2`"))
    }

    @Test
    fun plusAndMinusShiftInTime() {
        // the shifted events are cut at the cycle line, as the cycles of a `[...]` are played one by one
        assertEquals(
            listOf(
                "[ -1/4 ⇜ (0/1 → 1/4) | s:hh ]", "[ 1/4 → 3/4 | s:bd ]", "[ (3/4 → 1/1) ⇝ 5/4 | s:hh ]",
                "[ 3/4 ⇜ (1/1 → 5/4) | s:hh ]", "[ 5/4 → 7/4 | s:bd ]", "[ (7/4 → 2/1) ⇝ 9/4 | s:hh ]",
            ),
            play("mondo`s [bd hh]+.25`", 2),
        )
        assertEquals(
            listOf(
                "[ -1/4 ⇜ (0/1 → 1/4) | s:bd ]", "[ 1/4 → 3/4 | s:hh ]", "[ (3/4 → 1/1) ⇝ 5/4 | s:bd ]",
                "[ 3/4 ⇜ (1/1 → 5/4) | s:bd ]", "[ 5/4 → 7/4 | s:hh ]", "[ (7/4 → 2/1) ⇝ 9/4 | s:bd ]",
            ),
            play("mondo`s [bd hh] - .25`", 2),
        )
    }

    @Test
    fun aHashChainsAndAFunctionCanBeMadeOfIt() {
        assertEquals(play("s(\"bd hh\").fast(2).slow(4)", 4), play("mondo`s [bd hh] # fast 2 # slow 4`", 4))
        assertEquals(play("s(\"bd hh\").every(2, x => x.fast(2))", 2), play("mondo`s [bd hh] # every 2 (# fast 2)`", 2))
    }

    @Test
    fun curlyBracketsLoseTheirNumberOfStepsSoPaceDoesNothingToThem() {
        // `{...}` is joined with `innerJoin`, which forgets the steps: `%` has nothing to pace (mondough.mjs:89)
        assertEquals(play("mondo`s {bd sd}`", 2), play("mondo`s {bd sd}%4`", 2))
        // `[...]` and `<...>` keep one step per cycle, so `%4` speeds the cycle up by four
        assertEquals(play("s(\"bd sd\").fast(4)", 2), play("mondo`s [bd sd]%4`", 2))
        assertEquals(play("s(\"<bd sd>*4\")", 2), play("mondo`s <bd sd>%4`", 2))
    }

    @Test
    fun aNameTheProgramDefinesShadowsTheLibrary() {
        assertEquals(listOf("[ 0/1 → 1/2 | n:bd ]", "[ 1/2 → 1/1 | n:hh ]"), play("mondo`(let ((s [bd hh])) (n s))`"))
        assertEquals(listOf("[ 0/1 → 1/1 | s:bd ]", "[ 1/1 → 2/1 | s:hh ]"), play("mondo`\$ def fast slow\n\$ s [bd hh] # fast 2`", 2))
    }

    @Test
    fun definedFunctionsAreCalledWithPatterns() {
        assertEquals(play("s(\"bd hh\").fast(2)"), play("mondo`\$ def (twice x) (x # fast 2)\n\$ twice (s [bd hh])`"))
    }

    @Test
    fun theFunctionsOfTheProgramAreFoundByName() {
        assertEquals(play("s(\"bd hh\").fast(2)"), play("register('twice', (pat) => pat.fast(2))\nmondo`twice (s [bd hh])`"))
    }

    @Test
    fun anUnknownNameIsAString() {
        assertEquals(listOf("[ 0/1 → 1/1 | s:notAFunctionAnywhere ]"), play("mondo`s notAFunctionAnywhere`"))
    }

    @Test
    fun theNameOfAFunctionMayBeAPatternOfFunctions() {
        // cycle 0 plays `fast 2`, cycle 1 `slow 2`, which shows the second half of its stretched pattern
        assertEquals(
            listOf(
                "[ 0/1 → 1/4 | s:bd ]", "[ 1/4 → 1/2 | s:hh ]", "[ 1/2 → 3/4 | s:bd ]", "[ 3/4 → 1/1 | s:hh ]",
                "[ 1/1 → 2/1 | s:hh ]",
            ),
            play("mondo`(<fast slow> 2 (s [bd hh]))`", 2),
        )
        // the first cycle decides whether it is a pattern of functions; a later cycle that is not one fails when it is played
        assertEquals("[mondough] expected function, got \"bd\"", fails("mondo`(bd 2 (s hh))`").message)
        assertEquals("[mondough] \"bd\" is not a function b", fails("mondo`(<fast bd> 2 (s hh))`", 2).message)
    }

    @Test
    fun theHapsKnowWhereTheirAtomsAre() {
        val code = "const x = 1\nmondo`s bd:3`"
        val haps = evaluateProgram(code).pattern.queryArc(Rational.ZERO, Rational.ONE)
        assertEquals(1, haps.size)
        val s = code.indexOf("s bd")
        assertEquals(
            setOf(SourceRange(s, s + 1), SourceRange(s + 2, s + 4), SourceRange(s + 5, s + 6)),
            haps[0].context.locations.toSet(),
        )
    }

    @Test
    fun mondolangCountsPositionsFromTheStartOfItsCode() {
        val haps = evaluateProgram("const x = 1\nmondolang`s bd`").pattern.queryArc(Rational.ZERO, Rational.ONE)
        assertEquals(setOf(SourceRange(0, 1), SourceRange(2, 4)), haps[0].context.locations.toSet())
    }

    @Test
    fun theAtomsAreReportedForHighlighting() {
        val code = "const x = 1\n\$: mondo`s [bd hh*2] # fast 2`"
        val open = code.indexOf('`') + 1
        // in the order getLocations walks the desugared tree: (fast 2 (s (square bd (* 2 hh))))
        assertEquals(
            listOf(
                SourceRange(open + 14, open + 18),
                SourceRange(open + 19, open + 20),
                SourceRange(open, open + 1),
                SourceRange(open + 3, open + 5),
                SourceRange(open + 9, open + 10),
                SourceRange(open + 6, open + 8),
            ),
            evaluateProgram(code).miniLocations,
        )
        // mondolang counts from zero
        assertEquals(listOf(SourceRange(0, 1), SourceRange(2, 4)), evaluateProgram("\$: mondolang`s bd`").miniLocations)
    }

    // ---- mistakes ---------------------------------------------------------------------------------------------------------

    @Test
    fun anErrorIsReportedAtWhatIsWrong() {
        val code = "const x = 1\nmondo`s bd ; hh`"
        val e = fails(code)
        assertEquals("mondo: could not match '; hh'", e.message)
        assertEquals(SourceRange(code.indexOf(';'), code.indexOf(';') + 1), e.range)
        val unfinished = "mondo`s [bd`"
        assertEquals("unexpected end of file", fails(unfinished).message)
        assertEquals(SourceRange(unfinished.indexOf('s'), unfinished.lastIndexOf('`')), fails(unfinished).range)
    }

    @Test
    fun mondolangReportsItsErrorsForItsCode() {
        val code = "const x = 1\nmondolang`s bd ; hh`"
        val e = fails(code)
        assertEquals("mondo: could not match '; hh'", e.message)
        assertEquals(SourceRange(code.indexOf("s bd"), code.lastIndexOf('`')), e.range)
    }

    @Test
    fun aProgramThatIsEmptyOrNotAPatternIsAnError() {
        assertEquals("mondo: empty program", fails("mondo``").message)
        assertEquals("mondo: empty program", fails("mondo`// nothing`").message)
        // `!` with only its first argument is a function waiting for the pattern
        assertEquals("mondo: expected a pattern", fails("mondo`(! 2)`").message)
    }

    @Test
    fun aFailureWhilePlayingPointsAtTheTemplate() {
        val code = "mondo`(<fast bd> 2 (s hh))`"
        assertEquals(SourceRange(0, code.length), fails(code, 2).range)
    }

    // ---- the functions ------------------------------------------------------------------------------------------------------

    private fun js(code: String): Any? = ReplSession.create().realm.runRaw(code)

    @Test
    fun getLocationsGivesTheAtomsOfSomeCode() {
        assertEquals("[[0,1],[2,4],[5,8]]", js("return JSON.stringify(getLocations('s bd rim'))"))
        assertEquals("[[10,11],[12,14],[15,18]]", js("return JSON.stringify(getLocations('s bd rim', 10))"))
        assertEquals("unexpected end of file", assertFailsWith<CosicError> { js("return getLocations('s [bd')") }.message)
    }

    @Test
    fun mondoAndMondolangAreFunctionsToo() {
        assertEquals(bdHh, play("mondo('s [bd hh]')"))
        assertEquals(bdHh, play("mondolang('s [bd hh]')"))
        assertEquals(bdHh, play("mondo(['s [bd', ' hh]'])"))
        // the offset is only where the atoms are said to be
        assertEquals(bdHh, play("mondo('s [bd hh]', 100)"))
    }

    @Test
    fun mondiWrapsItsCodeInSquareBrackets() {
        assertEquals(play("s(\"bd hh*2\")"), play("s(mondi('bd hh*2'))"))
        assertEquals(play("s(\"bd hh*2\")"), play("s(mondolang(['[bd', ' hh*2]']))"))
    }

    @Test
    fun aDoubleQuotedStringIsNotCodeForMondo() {
        assertEquals("mondo(): pass a single-quoted string or use a mondo`\u2026` template", fails("mondo(\"s bd\")").message)
        assertEquals("mondolang(): expected a string, got 5", fails("mondolang(5)").message)
        assertEquals("getLocations(): expected a string, got 5", fails("getLocations(5)").message)
    }

    @Test
    fun anErrorOfACallBelongsToTheCall() {
        val code = "mondo('s [bd', 100)"
        val e = fails(code)
        assertEquals("unexpected end of file", e.message)
        assertEquals(SourceRange(0, code.length), e.range)
    }

    // ---- the languages ------------------------------------------------------------------------------------------------------

    @Test
    fun makingARealmBringsTheLanguagesBack() {
        val fake = EmbeddedLanguage { _, _, _, _ -> "not mondo" }
        EmbeddedLanguages.register("mondo", fake)
        EmbeddedLanguages.register("mondolang", fake)
        try {
            assertEquals(listOf("[ 0/1 → 1/1 | s:bd ]"), play("mondo`s bd`"))
            assertEquals(listOf("[ 0/1 → 1/1 | s:bd ]"), play("mondolang`s bd`"))
        } finally {
            EmbeddedLanguages.register("mondo", MondoLanguage(withOffset = true))
            EmbeddedLanguages.register("mondolang", MondoLanguage(withOffset = false))
        }
    }

    @Test
    fun theMarkStyleIsAppliedByTheMethodOfTheRealmWhenThereIsOne() {
        val session = ReplSession.create()
        val realm = session.realm
        var seen: Pair<Any?, Any?>? = null
        realm.patternPrototype.defineOwn(
            "markcss", realm.function("markcss", 1) { self, args -> seen = self to args[0]; self }, PropFlags.HIDDEN,
        )
        val pattern = session.evaluate("mondo`s bd`").pattern
        assertEquals("color: var(--caret,--foreground);text-decoration:underline", seen!!.second)
        assertTrue(seen!!.first is PatternValue)
        assertEquals(listOf("[ 0/1 → 1/1 | s:bd ]"), shown(pattern))
    }
}
