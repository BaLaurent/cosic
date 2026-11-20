package cosic.script

import cosic.SourceRange
import cosic.pattern.cycleSpan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScriptTest {
    // ---- programs ---------------------------------------------------------------------

    @Test
    fun multiLineMethodChains() {
        val code = """
            s("bd sd")
              .fast(2)
              .gain(0.5)
        """.trimIndent()
        assertEquals(
            listOf("gain=0.5 s=bd@0-1/4", "gain=0.5 s=sd@1/4-1/2", "gain=0.5 s=bd@1/2-3/4", "gain=0.5 s=sd@3/4-1"),
            run(code),
        )
    }

    @Test
    fun anonymousLabelsStack() {
        assertEquals(listOf("s=hh@0-1/2", "s=bd@0-1", "s=hh@1/2-1"), run("\$: s(\"bd\")\n\$: s(\"hh*2\")"))
    }

    @Test
    fun namedLabelsStack() {
        assertEquals(listOf("s=hh@0-1/2", "s=bd@0-1", "s=hh@1/2-1"), run("kick: s(\"bd\")\nhats: s(\"hh*2\")"))
    }

    @Test
    fun labelsStartingWithAnUnderscoreAreMuted() {
        assertEquals(listOf("s=bd@0-1"), run("\$: s(\"bd\")\n_\$: s(\"hh*2\")"))
        assertEquals(listOf("s=bd@0-1"), run("kick: s(\"bd\")\n_hats: s(\"hh*2\")"))
    }

    @Test
    fun aProgramOfOnlyMutedLabelsIsSilent() {
        assertEquals(emptyList(), run("_\$: s(\"bd\")\ns(\"sd\")"))
    }

    @Test
    fun unlabelledExpressionsAreIgnoredWhenLabelsExist() {
        assertEquals(listOf("s=bd@0-1"), run("s(\"sd\")\n\$: s(\"bd\")"))
    }

    @Test
    fun withoutLabelsTheLastPatternExpressionPlays() {
        assertEquals(listOf("s=sd@0-1"), run("s(\"bd\")\ns(\"sd\")"))
        assertEquals(listOf("s=bd@0-1"), run("s(\"bd\")\nsetcpm(60)"))
        assertEquals(emptyList(), run("let x = s(\"bd\")"))
        assertEquals(emptyList(), run("5"))
    }

    @Test
    fun hushSilencesTheWholeProgram() {
        assertEquals(emptyList(), run("\$: s(\"bd\")\nhush()"))
        assertEquals(emptyList(), run("hush()\n\$: s(\"bd\")"))
    }

    @Test
    fun commentsAreIgnored() {
        assertEquals(listOf("s=bd@0-1"), run("// a comment\ns(\"bd\") /* inline */ // trailing"))
    }

    @Test
    fun semicolonsAndTrailingCommasAreAllowed() {
        assertEquals(listOf("s=bd@0-1"), run("s(\"bd\");;"))
        assertEquals(listOf("s=bd@0-1", "s=hh@0-1"), run("stack(s(\"bd\"), s(\"hh\"),)"))
        assertEquals(listOf("s=bd@0-1", "s=hh@0-1"), run("\$: s(\"bd\"); \$: s(\"hh\")"))
    }

    // ---- tempo ------------------------------------------------------------------------

    @Test
    fun tempoDefaultsToHalfACyclePerSecond() {
        assertEquals(0.5, evaluateProgram("s(\"bd\")").cps)
    }

    @Test
    fun setcpmAndSetcps() {
        assertEquals(0.5, evaluateProgram("setcpm(30)").cps)
        assertEquals(1.0, evaluateProgram("setcpm(60)").cps)
        assertEquals(1.0, evaluateProgram("setcps(1)").cps)
        assertEquals(10.0, evaluateProgram("setcps(10)").cps)
        assertEquals(10.0, evaluateProgram("setcpm(600)").cps)
    }

    @Test
    fun theLastTempoCallWins() {
        assertEquals(0.5, evaluateProgram("setcps(1)\nsetcpm(30)").cps)
        assertEquals(1.0, evaluateProgram("setcpm(30)\nsetcps(1)").cps)
    }

    @Test
    fun tempoMustBeInRange() {
        for (code in listOf("setcps(0)", "setcps(10.5)", "setcpm(-5)", "setcpm(601)", "setcps(1/0 - 1/0)")) {
            assertEquals("Tempo out of range", failure(code).message, code)
        }
        assertEquals(SourceRange(0, 9), failure("setcps(0)").range)
    }

    @Test
    fun tempoMustBeAPlainNumber() {
        val notNumbers = listOf(
            "\"30\"", "\"0.5\"", "'x'", "'fast'", "true", "sine", "[1, 2]", "s(\"bd\")", "rev", "1 + \"2\"",
        )
        for (setting in listOf("setcps", "setcpm")) {
            for (argument in notNumbers) {
                assertEquals("Tempo out of range", failure("$setting($argument)").message, "$setting($argument)")
            }
        }
        assertEquals(SourceRange(0, 12), failure("setcpm(\"30\")").range)
    }

    @Test
    fun tempoTakesExactlyOneArgument() {
        assertEquals("'setcps' expects 1 arguments, got 0", failure("setcps()").message)
        assertEquals("'setcpm' expects 1 arguments, got 2", failure("setcpm(30, 60)").message)
    }

    // ---- variables and functions ------------------------------------------------------

    @Test
    fun letBindsValues() {
        assertEquals(listOf("s=bd@0-1/2", "s=bd@1/2-1"), run("let kick = s(\"bd*2\")\n\$: kick"))
        assertEquals(6, run("const k = 2\n\$: s(\"bd\").fast(k * 3)").size)
    }

    @Test
    fun arithmeticOnNumbersFollowsPrecedence() {
        assertEquals(5, run("let x = 1 + 2 * 3 - 4 / 2\n\$: s(\"bd\").fast(x)").size)
        assertEquals(9, run("\$: s(\"bd\").fast((1 + 2) * 3)").size)
        assertEquals(listOf("s=sd@0-1/2", "s=bd@1/2-1"), run("\$: s(\"bd sd\").fast(-1)"))
        assertEquals(listOf("s=bd@0-1/2", "s=sd@1/2-1"), run("\$: s(\"bd sd\").fast(- -1)"))
    }

    @Test
    fun everyWithAPartiallyAppliedFunction() {
        val code = "s(\"bd sd\").every(2, fast(2))"
        assertEquals(4, run(code, 0).size)
        assertEquals(2, run(code, 1).size)
        assertEquals(4, run(code, 2).size)
    }

    @Test
    fun everyWithALambda() {
        val code = "n(\"0 1 2\").every(2, x => x.rev())"
        assertEquals(listOf("n=2@0-1/3", "n=1@1/3-2/3", "n=0@2/3-1"), run(code, 0))
        assertEquals(listOf("n=0@1-4/3", "n=1@4/3-5/3", "n=2@5/3-2"), run(code, 1))
    }

    @Test
    fun methodsWorkAtTopLevelWithThePatternLast() {
        assertEquals(run("s(\"bd sd\").every(2, fast(2))", 0), run("every(2, fast(2), s(\"bd sd\"))", 0))
        assertEquals(run("s(\"bd sd\").rev()", 0), run("rev(s(\"bd sd\"))", 0))
    }

    @Test
    fun bareFunctionNamesAreFunctions() {
        assertEquals(run("s(\"bd sd\").every(2, rev)", 0), run("s(\"bd sd\").every(2, x => x.rev())", 0))
    }

    @Test
    fun lambdasAreClosures() {
        assertEquals(2, run("let twice = x => x.fast(2)\n\$: s(\"bd\").every(2, twice)").size)
        assertEquals(3, run("let f = (p, n) => p.fast(n)\n\$: f(s(\"bd\"), 3)").size)
        assertEquals(2, run("let k = 2\nlet g = x => x.fast(k)\n\$: s(\"bd\").every(1, g)").size)
        assertEquals(1, run("let f = () => s(\"bd\")\n\$: f()").size)
    }

    @Test
    fun arraysBecomeSequences() {
        assertEquals(listOf("s=bd@0-1/2", "s=sd@1/2-1"), run("\$: [s(\"bd\"), s(\"sd\")]"))
    }

    @Test
    fun stringLiteralsAreOneValuePerCycle() {
        assertEquals(listOf("s=bd@0-1"), run("s('bd')"))
        assertEquals(listOf("s=bd@0-1/2", "s=bd@1/2-1"), run("s('bd').fast(2)"))
    }

    @Test
    fun backtickStringsMayRunOverSeveralLines() {
        assertEquals(listOf("s=bd@0-1/2", "s=sd@1/2-1"), run("s(`bd\nsd`)"))
    }

    @Test
    fun numberLiteralsAcceptALeadingDot() {
        assertEquals(listOf("s=bd@0-2|0-1"), run("s(\"bd\").fast(.5)"))
        assertEquals(listOf("s=bd@0-2|0-1"), run("s(\"bd\").slow(2)"))
    }

    @Test
    fun aParenthesisOnANewLineStartsANewStatement() {
        assertEquals(listOf("s=sd@0-1"), run("s(\"bd\")\n(\"sd\")"))
    }

    // ---- values -----------------------------------------------------------------------

    @Test
    fun scaleTurnsDegreesIntoNotes() {
        assertEquals(listOf("note=48@0-1/3", "note=52@1/3-2/3", "note=55@2/3-1"), run("\"0 2 4\".scale(\"C:major\")"))
        assertEquals(listOf("note=48@0-1/3", "note=52@1/3-2/3", "note=55@2/3-1"), run("n(\"0 2 4\").scale(\"C:major\")"))
    }

    @Test
    fun addShiftsNotesAndNoteNames() {
        assertEquals(listOf("note=60@0-1/2", "note=64@1/2-1"), run("note(\"c e\").add(12)"))
        assertEquals(listOf("note=60@0-1/2", "note=64@1/2-1"), run("\"c3 e3\".add(12)"))
    }

    @Test
    fun soundNamesCanCarryASampleNumber() {
        assertEquals(listOf("n=3 s=bd@0-1"), run("s(\"bd:3\")"))
        assertEquals(mapOf<String, Any>("s" to "bd", "n" to 3.0), values("s(\"bd:3\")").single())
    }

    @Test
    fun operatorsOnPatternsKeepTheStructureOfTheLeft() {
        assertEquals(listOf("note=11@0-1/2", "note=12@1/2-1"), run("\"1 2\" + 10"))
        assertEquals(listOf("note=0@0-1/2", "note=1@1/2-1"), run("\"1 2\" - 1"))
        assertEquals(listOf("note=2@0-1/2", "note=4@1/2-1"), run("\"1 2\" * 2"))
        assertEquals(listOf("note=2@0-1/2", "note=4@1/2-1"), run("\"4 8\" / 2"))
        assertEquals(listOf("n=2@0-1/2", "n=3@1/2-1"), run("n(\"0 1\") + 2"))
    }

    @Test
    fun aNumberOnTheLeftHasNoStructureOfItsOwn() {
        assertEquals(listOf("note=2@0-1/2", "note=4@1/2-1"), run("2 * \"1 2\""))
        assertEquals(listOf("note=11@0-1/2", "note=12@1/2-1"), run("10 + \"1 2\""))
    }

    @Test
    fun negatingAPattern() {
        assertEquals(listOf("note=-1@0-1/2", "note=-2@1/2-1"), run("-\"1 2\""))
    }

    @Test
    fun operatorsNeedNumbers() {
        val e = queryFailure("\$: \"bd\" + 1")
        assertEquals("'add' expects a number, got 'bd'", e.message)
        assertEquals(SourceRange(3, 11), e.range)
        val control = queryFailure("s(\"bd\") + 1")
        assertEquals("add: nothing numeric to change", control.message)
        assertEquals(SourceRange(0, 11), control.range)
    }

    // ---- errors -----------------------------------------------------------------------

    @Test
    fun unknownFunctions() {
        val e = failure("foo(1)")
        assertEquals("Unknown function 'foo'", e.message)
        assertEquals(SourceRange(0, 6), e.range)
        assertEquals(SourceRange(8, 14), failure("s(\"bd\")\nfoo(1)").range)
        val method = failure("s(\"bd\").foo(1)")
        assertEquals("Unknown function 'foo'", method.message)
        assertEquals(SourceRange(8, 14), method.range)
    }

    @Test
    fun unknownVariables() {
        val e = failure("\$: foo")
        assertEquals("Unknown variable 'foo'", e.message)
        assertEquals(SourceRange(3, 6), e.range)
    }

    @Test
    fun samplesIsNotSupportedYet() {
        val e = failure("samples('x')")
        assertEquals("samples() isn't supported yet \u2014 import a sample folder from the Sounds tab of the editor panel instead", e.message)
        assertEquals(SourceRange(0, 12), e.range)
    }

    @Test
    fun wrongNumberOfArguments() {
        val e = failure("s(\"bd\").fast()")
        assertEquals("'fast' expects 1 arguments, got 0", e.message)
        assertEquals(SourceRange(8, 14), e.range)
        assertEquals("'s' expects 1 arguments, got 0", failure("s()").message)
        assertEquals("'every' expects 2 arguments, got 1", failure("every(2)").message)
        assertEquals("'rev' expects 0 arguments, got 2", failure("rev(1, 2)").message)
        assertEquals("'rev' expects 0 arguments, got 1", failure("s(\"bd\").rev(1)").message)
        assertEquals("'run' expects 1 arguments, got 2", failure("run(1, 2)").message)
    }

    @Test
    fun wrongKindsOfArguments() {
        val number = failure("s(\"bd\").fast('x')")
        assertEquals("'fast' expects a number, got 'x'", number.message)
        assertEquals(SourceRange(8, 17), number.range)
        assertEquals("'fast' expects a number, got a function", failure("s(\"bd\").fast(rev)").message)
        assertEquals("'gain' expects a number, got 'x'", failure("s(\"bd\").gain('x')").message)
        assertEquals("'every' expects a function, got a pattern", failure("s(\"bd\").every(2, \"x\")").message)
        assertEquals("'every' expects a function, got '5'", failure("s(\"bd\").every(2, 5)").message)
        assertEquals("Expected a pattern, got a function", failure("\$: rev").message)
        assertEquals(SourceRange(3, 6), failure("\$: rev").range)
    }

    @Test
    fun patternedArgumentsAreCheckedWhenQueried() {
        val e = queryFailure("s(\"bd\").gain(\"x\")")
        assertEquals("'gain' expects a number, got 'x'", e.message)
        assertEquals(SourceRange(8, 17), e.range)
        assertEquals("'fast' expects a number, got 'bd'", queryFailure("s(\"bd\").fast(\"bd\")").message)
    }

    @Test
    fun functionsThatAreNotFunctions() {
        assertEquals("'a' is not a function", failure("let a = 5\na(1)").message)
        assertEquals("Not a function", failure("s(\"bd\")(\"sd\")").message)
        assertEquals("Function expects 2 arguments, got 1", failure("let f = (a, b) => a\n\$: f(s(\"bd\"))").message)
    }

    @Test
    fun functionNamesAloneNeedArguments() {
        val e = failure("\$: fast")
        assertEquals("'fast' must be called, like fast(...)", e.message)
        assertEquals(SourceRange(3, 7), e.range)
    }

    @Test
    fun methodsOnlyExistForMethodsAndControls() {
        assertEquals("'seq' can't be used as a method", failure("s(\"bd\").seq(1)").message)
    }

    @Test
    fun syntaxErrorsNameTheUnexpectedToken() {
        assertEquals("Syntax error: unexpected end of code", failure("s(\"bd\"").message)
        val stray = failure("s(\"bd\"))")
        assertEquals("Syntax error: unexpected ')'", stray.message)
        assertEquals(SourceRange(7, 8), stray.range)
        assertEquals("Syntax error: unexpected end of code", failure("s(\"bd\").").message)
        assertEquals("Syntax error: unexpected end of code", failure("s(\"bd\").fast").message)
        assertEquals("Syntax error: unexpected end of code", failure("1 +").message)
        assertEquals("Syntax error: unexpected end of code", failure("x =>").message)
        val missingEquals = failure("let x 5")
        assertEquals("Syntax error: unexpected '5'", missingEquals.message)
        assertEquals(SourceRange(6, 7), missingEquals.range)
        assertEquals("Syntax error: unexpected '='", failure("let = 5").message)
        assertEquals("Syntax error: unexpected ','", failure("(a, 1) => a").message)
        assertEquals("Syntax error: unexpected '='", failure("x = 5").message)
        assertEquals("Syntax error: unexpected '@'", failure("s(\"bd\") @").message)
        assertEquals("Syntax error: unexpected '\"sd\"'", failure("s(\"bd\" \"sd\")").message)
    }

    @Test
    fun unclosedStringsReportTheirRange() {
        val e = failure("s(\"bd")
        assertEquals("Unclosed string", e.message)
        assertEquals(SourceRange(2, 5), e.range)
        assertEquals("Unclosed string", failure("s('bd").message)
    }

    // ---- highlighting -----------------------------------------------------------------

    @Test
    fun hapsRememberWhereTheirAtomsAre() {
        val haps = evaluateProgram("s(\"bd sd\").fast(1)").pattern.query(cycleSpan(0)).sortedBy { it.part.begin }
        assertEquals(listOf(listOf(SourceRange(3, 5)), listOf(SourceRange(6, 8))), haps.map { it.locations })
    }

    @Test
    fun patternedArgumentsAddTheirLocations() {
        val hap = evaluateProgram("s(\"bd\").n(\"1\")").pattern.query(cycleSpan(0)).single()
        assertEquals(listOf(SourceRange(3, 5), SourceRange(11, 12)), hap.locations)
    }

    // ---- tokenize ---------------------------------------------------------------------

    @Test
    fun tokenizeKindsForASampleSnippet() {
        val kinds = tokenize("setcpm(30)\n\$: s(\"bd\") // go").map { it.kind }
        assertEquals(
            listOf(
                TokenKind.CALL, TokenKind.PUNCT, TokenKind.NUMBER, TokenKind.PUNCT, TokenKind.LABEL, TokenKind.PUNCT,
                TokenKind.CALL, TokenKind.PUNCT, TokenKind.MINI, TokenKind.PUNCT, TokenKind.COMMENT,
            ),
            kinds,
        )
        assertTrue(TokenKind.STRING in tokenize("s('x')").map { it.kind })
    }

    // ---- edge cases -------------------------------------------------------------------

    @Test
    fun emptyProgramsAreSilent() {
        for (code in listOf("", "   \n  ", "// nothing here", "/* nothing */")) {
            assertEquals(emptyList(), run(code), "'$code'")
            assertEquals(0.5, evaluateProgram(code).cps)
        }
    }

    @Test
    fun listsWorkAsArgumentsAndReceivers() {
        assertEquals(listOf("s=bd@0-1|0-1/2", "s=bd@1/2-1"), run("s(\"bd\").fast([1, 2])"))
        assertEquals(
            listOf("s=bd@0-1/4", "s=sd@1/4-1/2", "s=bd@1/2-3/4", "s=sd@3/4-1"),
            run("[s(\"bd\"), s(\"sd\")].fast(2)"),
        )
    }

    @Test
    fun booleanLiteralsWorkAsMasks() {
        assertEquals(listOf("s=bd@0-1"), run("s(\"bd\").mask(true)"))
        assertEquals(emptyList(), run("s(\"bd\").mask(false)"))
        assertEquals("'fast' expects a number, got 'true'", failure("s(\"bd\").fast(true)").message)
    }

    @Test
    fun partiallyAppliedFunctionsCanBeStoredAndCalled() {
        assertEquals(4, run("let f = fast(2)\n\$: f(s(\"bd sd\"))").size)
        assertEquals(
            run("s(\"bd sd\").every(2, fast(2))", 1),
            run("let f = every(2, fast(2))\n\$: f(s(\"bd sd\"))", 1),
        )
    }

    @Test
    fun numbersWrittenAsStringsAreNotNumbers() {
        assertEquals("'n' expects a number, got '3'", failure("n('3')").message)
    }

    @Test
    fun countsAreLimited() {
        val e = failure("s(\"bd\").euclid(3, 1000000)")
        assertEquals("'euclid' expects a number up to 10000, got 1000000", e.message)
        assertEquals(SourceRange(8, 26), e.range)
        assertEquals("'run' expects a number up to 10000, got 1000000000", failure("n(run(1000000000))").message)
        assertEquals("'chop' expects a number up to 10000, got 10001", failure("s(\"bd\").chop(10001)").message)
        assertEquals(4, run("s(\"bd sd hh cp\").chunk(10000, rev)").size)
    }

    @Test
    fun speedsAreLimitedToWhatCanBePlayed() {
        val fast = failure("s(\"bd\").fast(1000000)")
        assertEquals("Too fast: at most 10000 events per cycle", fast.message)
        assertEquals(SourceRange(8, 21), fast.range)
        assertEquals(emptyList(), run("s(\"bd\").slow(0.000001)"))
        assertEquals("Too fast: at most 10000 events per cycle", failure("s(\"bd\").segment(1000000)").message)
        val mini = failure("s(\"bd*1000000\")")
        assertEquals("Mini-notation: number out of range", mini.message)
        assertEquals(SourceRange(6, 13), mini.range)
        assertEquals("Too fast: at most 10000 events per cycle", queryFailure("s(\"bd\").fast(\"<1 1000000>\")", 1).message)
        assertEquals(10_000, run("s(\"bd\").fast(10000)").size)
    }

    @Test
    fun numbersThatAreNotNumbersAreReported() {
        val e = failure("s(\"bd\").fast(1/0)")
        assertEquals("'fast': Invalid number", e.message)
        assertEquals(SourceRange(8, 17), e.range)
    }

    @Test
    fun aFunctionThatCallsItselfIsReportedInsteadOfCrashing() {
        val e = failure("let f = x => f(x)\n\$: s(\"bd\").every(1, f)")
        assertEquals("The program nests or calls itself too deeply", e.message)
    }
}
