package cosic.script.next

import cosic.SourceRange
import cosic.pattern.CosicError
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The transpiler, one rule at a time. The rules are T1 to T19 of the language appendix (docs/parity/spec/lang-runtime.md
 * section 1.1); the vectors of the first group are those of packages/transpiler/test/transpiler.test.mjs, the others
 * were checked against Strudel's transpiler itself (tools/strudel-ref/transpile-oracle.mjs runs it, and
 * [TranspileOracleTest] compares the whole parity corpus and a set of hand-written programs with it).
 */
class TranspileTest {
    /** Stands in for the mini package: the atoms of a notation without any syntax are its words. */
    private val words = MiniLeafLocator { quoted, start ->
        Regex("[^\\s\"]+").findAll(quoted).map { intArrayOf(start + it.range.first, start + it.range.last + 1) }.toList()
    }

    @BeforeTest
    fun registerLanguages() {
        EmbeddedLanguages.register("mondo", WholeCodeLanguage)
    }

    private fun transpiled(
        code: String,
        addReturn: Boolean = false,
        locator: MiniLeafLocator = words,
        widgetTypes: Set<String> = emptySet(),
        emitWidgets: Boolean = true,
        emitMiniLocations: Boolean = true,
        id: String? = null,
    ): TranspileResult = transpile(code, TranspileOptions(addReturn, emitMiniLocations, emitWidgets, id), widgetTypes, locator)

    private fun text(code: String, addReturn: Boolean = false, widgetTypes: Set<String> = emptySet()): String =
        printAst(transpiled(code, addReturn, widgetTypes = widgetTypes).program)

    private fun statements(code: String, addReturn: Boolean = false): List<Node> = transpiled(code, addReturn).program.body

    private fun firstCall(program: Program): CallExpression = (program.body[0] as ExpressionStatement).expression as CallExpression

    // ---- the vectors of packages/transpiler/test/transpiler.test.mjs ----------------------------------------------------

    @Test
    fun wrapsDoubleQuoteStringWithMiniAndAddsLocation() {
        assertEquals("m('c3', 0);", text("\"c3\""))
        assertEquals("stack(m('c3', 6), m('bd sd', 11));", text("stack(\"c3\",\"bd sd\")"))
    }

    @Test
    fun wrapsBacktickStringWithMiniAndAddsLocation() {
        assertEquals("m('c3', 0);", text("`c3`"))
    }

    @Test
    fun keepsTaggedTemplateLiteralAsIs() {
        assertEquals("xxx`c3`;", text("xxx`c3`"))
    }

    @Test
    fun supportsTopLevelAwait() {
        assertEquals("await samples('xxx');", text("await samples('xxx');"))
    }

    @Test
    fun addsAwaitToBareSamplesCall() {
        assertEquals("await samples('xxx');", text("samples('xxx');"))
    }

    @Test
    fun collectsMiniLocations() {
        val result = transpiled("s(\"bd\", \"hh oh\")")
        assertEquals(listOf(SourceRange(3, 5), SourceRange(9, 11), SourceRange(12, 14)), result.miniLocations)
    }

    @Test
    fun allowsDisablingMini() {
        val code = "/* mini-off */\n" +
            "      const randPrefix = Math.random() > 0.5 ? \"b\" : \"s\";\n" +
            "      const drumPat = `\${randPrefix}d`;\n" +
            "      // mini-on\n" +
            "      s(drumPat).lpf(\"5000 10000\") // make sure mini still runs;\n" +
            "    "
        val result = transpiled(code)
        val output = printAst(result.program)
        assertTrue("m('b'" !in output)
        assertTrue("m('s'" !in output)
        assertTrue("const drumPat = `\${randPrefix}d`;" in output, output)
        val cutoff = code.indexOf("5000 10000")
        assertEquals(listOf(SourceRange(cutoff, cutoff + 4), SourceRange(cutoff + 5, cutoff + 10)), result.miniLocations)
    }

    // ---- T1, T2: parsing and options -------------------------------------------------------------------------------------

    @Test
    fun aSyntaxErrorIsACosicErrorAtTheOffendingCode() {
        val unclosed = assertFailsWith<CosicError> { transpile("s(\"bd\"") }
        assertEquals("Unexpected token", unclosed.message)
        val misplaced = assertFailsWith<CosicError> { transpile("return 1") }
        assertEquals("'return' outside of function", misplaced.message)
        assertEquals(SourceRange(0, 6), misplaced.range)
    }

    @Test
    fun theProgramReturnsItsLastExpressionByDefault() {
        val result = transpile("s(\"a\")", leafLocations = words)
        assertEquals("return s(m('a', 2));", printAst(result.program))
        assertEquals(SourceRange(0, 6), result.program.range)
    }

    @Test
    fun withoutAddReturnTheLastStatementStaysAStatement() {
        assertEquals("s(m('a', 2));", text("s(\"a\")", addReturn = false))
    }

    @Test
    fun theProgramRangeIsTheWholeCodeEvenWhenTheBodyIsRewritten() {
        val code = "  const a = 1 // nothing else\n"
        val program = transpiled(code, addReturn = true).program
        assertEquals(0, program.start)
        assertEquals(code.length, program.end)
    }

    @Test
    fun withoutEmitMiniLocationsTheResultReportsNothingButTheProgramIsTheSame() {
        val code = "\$: s(\"a b\")._scope()\nslider(1)"
        val full = transpiled(code, widgetTypes = DEFAULT_WIDGET_TYPES)
        val bare = transpiled(code, widgetTypes = DEFAULT_WIDGET_TYPES, emitMiniLocations = false)
        assertTrue(full.miniLocations.isNotEmpty() && full.widgets.isNotEmpty() && full.sliders.isNotEmpty())
        assertEquals(emptyList(), bare.miniLocations)
        assertEquals(emptyList(), bare.widgets)
        assertEquals(emptyList(), bare.sliders)
        assertEquals(printAst(full.program), printAst(bare.program))
    }

    @Test
    fun theLocatorIsNotAskedWithoutEmitMiniLocations() {
        val locator = MiniLeafLocator { _, _ -> throw AssertionError("the locator must not be called") }
        transpiled("s(\"a b\")", locator = locator, emitMiniLocations = false)
    }

    // ---- T3: the walk ------------------------------------------------------------------------------------------------------

    @Test
    fun theWalkMeetsTheOutermostCallOfAChainFirst() {
        val result = transpiled("x._scope()._pianoroll()._scope()", widgetTypes = DEFAULT_WIDGET_TYPES, id = "ed")
        assertEquals(
            "x._scope('ed_widget__scope_1')._pianoroll('ed_widget__pianoroll_0')._scope('ed_widget__scope_0');",
            printAst(result.program),
        )
        assertEquals(
            listOf(
                WidgetConfig("_scope", 0, 32, 0, "ed"),
                WidgetConfig("_pianoroll", 0, 23, 0, "ed"),
                WidgetConfig("_scope", 0, 10, 1, "ed"),
            ),
            result.widgets,
        )
    }

    @Test
    fun theCallUnderAnAwaitAddedByTheWalkIsNotWrappedAgain() {
        assertEquals("await samples(m('a', 8));", text("samples(\"a\")"))
    }

    @Test
    fun aReplacedMiniStringIsNotWalkedAndItsLocationsAreCollectedOnce() {
        assertEquals(2, transpiled("\"a b\"").miniLocations.size)
    }

    // ---- T4, T5, T8: which strings are mini-notation ----------------------------------------------------------------------

    @Test
    fun aDoubleQuotedStringIsMiniNotationAnywhere() {
        assertEquals("f(m('a', 2), [m('b', 8)], { k: m('c', 19) });", text("f(\"a\", [\"b\"], { k: \"c\" })"))
        assertEquals("x => m('a b', 5);", text("x => \"a b\""))
        assertEquals("true ? m('a', 7) : m('b', 13);", text("true ? \"a\" : \"b\""))
        assertEquals("x[m('k', 2)];", text("x[\"k\"]"))
    }

    @Test
    fun theValueOfAMiniStringIsTheCookedString() {
        assertEquals("s(m('A b', 2));", text("s(\"\\x41 b\")"))
        assertEquals("s(m('a\\nb', 2));", text("s(\"a\\nb\")"))
        assertEquals("s(m('\u00e9 b', 2));", text("s(\"\\u00e9 b\")"))
    }

    @Test
    fun aSingleQuotedStringIsNeverMiniNotation() {
        assertEquals("s('bd sd');", text("s('bd sd')"))
        assertEquals("'c e g'.note();", text("'c e g'.note()"))
    }

    @Test
    fun aDoubleQuotedStringMethodIsAPatternMethod() {
        assertEquals("m('c e g', 0).note();", text("\"c e g\".note()"))
    }

    @Test
    fun aTemplateLiteralKeepsOnlyItsFirstPiece() {
        assertEquals("m('a ', 0);", text("`a \${x} b`"))
        assertEquals("m('a ', 0);", text("`a \${x} b \${y} c`"))
        assertEquals("m('', 0);", text("``"))
    }

    @Test
    fun theTextOfATemplateIsItsRawText() {
        val result = transpiled("s(`a\\nb`)", locator = MiniLeafLocator.NONE)
        assertEquals("s(m('a\\\\nb', 2));", printAst(result.program))
    }

    @Test
    fun theLineBreaksOfATemplateAreNormalised() {
        val result = transpiled("s(`a\r\nb`)", locator = MiniLeafLocator.NONE)
        assertEquals("s(m('a\\nb', 2));", printAst(result.program))
    }

    @Test
    fun aTaggedTemplateIsNotMiniNotationButItsExpressionsAreWalked() {
        assertEquals("xxx`a \${m('b c', 8)} d`;", text("xxx`a \${\"b c\"} d`"))
        assertEquals("a.b`c3`;", text("a.b`c3`"))
    }

    // ---- DEV-1: the name of a property is not mini-notation -------------------------------------------------------------

    @Test
    fun aStringThatNamesAPropertyStaysAString() {
        assertEquals("({ 'a b': 1 });", text("({ \"a b\": 1 })"))
        assertEquals("({ 'a b': m('c d', 10) });", text("({ \"a b\": \"c d\" })"))
        assertEquals("({ [m('a b', 4)]: 1 });", text("({ [\"a b\"]: 1 })"))
    }

    @Test
    fun aStringThatNamesAPropertyOfAPatternStaysAString() {
        val declaration = statements("const { \"a b\": x } = y").first() as VariableDeclaration
        val pattern = declaration.declarations[0].id as ObjectPattern
        val key = (pattern.properties[0] as Property).key
        assertTrue(key is Literal && key.value == "a b", key.toString())
    }

    // ---- T6: embedded languages ------------------------------------------------------------------------------------------

    @Test
    fun aTemplateTaggedWithALanguageIsLeftForTheInterpreterAndItsLocationsAreCollected() {
        val result = transpiled("s(mondo`a b`).fast(2)")
        assertEquals("s(mondo`a b`).fast(2);", printAst(result.program))
        // the code starts after the backtick
        assertEquals(listOf(SourceRange(8, 11)), result.miniLocations)
    }

    @Test
    fun theLocationsOfALanguageAreCollectedInTheOrderOfTheWalk() {
        val result = transpiled("s(\"a\").stack(mondo`b c`, \"d\")")
        assertEquals(listOf(SourceRange(3, 4), SourceRange(19, 22), SourceRange(26, 27)), result.miniLocations)
    }

    @Test
    fun theExpressionsOfATemplateInALanguageAreNotWalked() {
        assertEquals("mondo`a \${'b'} c`;", text("mondo`a \${\"b\"} c`"))
    }

    @Test
    fun aLanguageIsNotAffectedByMiniOff() {
        val result = transpiled("// mini-off\nmondo`a b`;\n\"c d\"")
        assertEquals(listOf(SourceRange(18, 21)), result.miniLocations)
    }

    @Test
    fun aTemplateTaggedWithAnUnknownNameStaysACall() {
        val result = transpiled("foo`a b`")
        assertEquals("foo`a b`;", printAst(result.program))
        assertEquals(emptyList(), result.miniLocations)
    }

    @Test
    fun aLanguageIsFoundByTheTagNameOnly() {
        assertEquals("a.mondo`a b`;", text("a.mondo`a b`"))
    }

    @Test
    fun aLanguageIsOnlyAskedForLocationsWhenTheyAreWanted() {
        val language = CountingLanguage()
        EmbeddedLanguages.register("countedForTest", language)
        transpiled("countedForTest`a b`", emitMiniLocations = false)
        assertEquals(0, language.asked)
        transpiled("countedForTest`a b`")
        assertEquals(1, language.asked)
    }

    @Test
    fun aLanguageGetsTheRawFirstPieceAndTheOffsetAfterTheBacktick() {
        val language = CountingLanguage()
        EmbeddedLanguages.register("rawForTest", language)
        transpiled("  rawForTest`a\\n \${x} b`")
        assertEquals("a\\n ", language.lastCode)
        assertEquals(13, language.lastStart)
    }

    // ---- T7: mini-off and mini-on ---------------------------------------------------------------------------------------

    @Test
    fun miniOffDisablesUntilMiniOn() {
        val result = transpiled("// mini-off\ns(\"a b\");\n// mini-on\ns(\"c d\")")
        assertEquals("s('a b');\ns(m('c d', 35));", printAst(result.program))
        assertEquals(listOf(SourceRange(36, 37), SourceRange(38, 39)), result.miniLocations)
    }

    @Test
    fun anUnmatchedMiniOffRunsToTheEndOfTheCode() {
        assertEquals("s('a b');", text("// mini-off\ns(\"a b\")"))
    }

    @Test
    fun anUnmatchedMiniOnDoesNothing() {
        assertEquals("s(m('a b', 13));", text("// mini-on\ns(\"a b\")"))
    }

    @Test
    fun miniOffAndMiniOnNestLikeBrackets() {
        val code = "// mini-off\n// mini-off\ns(\"a\");\n// mini-on\ns(\"b\");\n// mini-on\ns(\"c\")"
        assertEquals("s('a');\ns('b');\ns(m('c', ${code.lastIndexOf("\"c\"")}));", text(code))
    }

    @Test
    fun aStringRightAfterMiniOffIsDisabledAndOneRightAfterMiniOnIsNot() {
        val code = "\"a\"\n/* mini-off */\"b\"\n/* mini-on */\"c\""
        assertEquals("m('a', 0);\n'b';\nm('c', ${code.lastIndexOf("\"c\"")});", text(code))
    }

    @Test
    fun theCommentTextOnlyHasToStartWithTheMarker() {
        assertEquals("'a b';", text("// mini-offline\n\"a b\""))
        assertEquals("m('a', 29);\nm('b', 48);", text("// not mini-off at the start\n\"a\"\n/* mini-ofx */\n\"b\""))
    }

    @Test
    fun theCommentTextIsTrimmedLikeJavaScriptTrims() {
        assertEquals("'a';", text("/*\n  mini-off\n*/\n\"a\""))
        assertEquals("'a';", text("//\tmini-off\n\"a\""))
        assertEquals("'a';\nm('b', 32);", text("/*\u00a0mini-off*/ \"a\";\n/*\u00a0mini-on*/ \"b\""))
    }

    @Test
    fun miniOffAlsoCoversATemplateWithItsExpressions() {
        assertEquals("`\${x}d`;", text("// mini-off\n`\${x}d`"))
    }

    // ---- T9: labels --------------------------------------------------------------------------------------------------------

    @Test
    fun aLabelBecomesACallOfP() {
        assertEquals("s(m('bd', 5)).p('\$');", text("\$: s(\"bd\")"))
        assertEquals("note(m('c2', 11)).p('bass');", text("bass: note(\"c2\")"))
        assertEquals("s(m('bd', 5)).p('\$');", text("\$: s(\"bd\");"))
    }

    @Test
    fun everyLabelIsRewrittenAtAnyDepth() {
        assertEquals("{\n    a.p('a');\n}\nsilence;", text("{ a: a }"))
        assertEquals("f(() => {\n    y.p('x');\n});", text("f(() => { x: y })"))
        assertEquals("a.p('a');\nb.p('b');", text("a: a\nb: b"))
    }

    @Test
    fun theBodyOfALabelIsStillWalked() {
        val result = transpiled("a: samples(\"x\"), slider(1)", widgetTypes = DEFAULT_WIDGET_TYPES)
        assertEquals("(await samples(m('x', 11)), sliderWithID('slider_24', 1)).p('a');", printAst(result.program))
        assertEquals(listOf("slider_24"), result.sliders.map { it.id })
    }

    @Test
    fun aLabelThatDoesNotLabelAnExpressionIsASyntaxError() {
        for (code in listOf("a: { s(\"bd\") }", "a: b: c", "a: for (;;) { break a }", "a: if (x) y")) {
            val e = assertFailsWith<CosicError>(code) { transpile(code) }
            assertEquals("Labeled statements must label an expression", e.message, code)
            assertEquals(SourceRange(0, 1), e.range, code)
        }
    }

    @Test
    fun theNodesOfALabelCarryTheRangeOfTheStatement() {
        val code = "  drums: s(\"bd\")"
        val statement = statements(code).single() as ExpressionStatement
        assertEquals(SourceRange(2, code.length), statement.range)
        val call = statement.expression as CallExpression
        assertEquals(SourceRange(2, code.length), call.range)
        val member = call.callee as MemberExpression
        val method = member.property as Identifier
        assertEquals("p", method.name)
        assertEquals(SourceRange(2, 7), method.range)
        val name = call.arguments.single() as Literal
        assertEquals("drums", name.value)
        assertEquals(SourceRange(2, 7), name.range)
        assertEquals(SourceRange(9, code.length), member.obj.range)
    }

    // ---- T10: the value of the program ------------------------------------------------------------------------------------

    @Test
    fun aProgramWithoutStatementsIsSilence() {
        val result = transpiled("", addReturn = true)
        assertEquals("return silence;", printAst(result.program))
        assertEquals(listOf("empty body -> fallback to silence"), result.warnings)
        assertEquals(listOf("empty body -> fallback to silence"), transpiled("// only a comment", addReturn = true).warnings)
    }

    @Test
    fun aProgramThatEndsInADeclarationIsSilence() {
        assertEquals("const a = 1;\nreturn silence;", text("const a = 1", addReturn = true))
        val body = statements("function f() {}", addReturn = true)
        assertTrue(body[0] is FunctionDeclaration)
        assertEquals("silence", ((body[1] as ReturnStatement).argument as Identifier).name)
        assertEquals(emptyList(), transpiled("const a = 1", addReturn = true).warnings)
    }

    @Test
    fun anyLastStatementThatIsNotAnExpressionStatementIsFollowedBySilence() {
        for (code in listOf("if (x) { s(\"a\") }", "{ s(\"a\") }", "for (const i of [1]) { s(\"a\") }", "throw 1", "try { s(\"a\") } catch (e) {}")) {
            val body = statements(code, addReturn = true)
            assertEquals(2, body.size, code)
            assertEquals("silence", ((body[1] as ReturnStatement).argument as Identifier).name, code)
        }
        assertEquals("const f = async () => {\n    await samples(m('x', 32));\n};\nsilence;", text("const f = async () => { samples(\"x\") }"))
    }

    @Test
    fun anEmptyStatementAtTheEndIsNotTheValue() {
        assertEquals("s(m('a', 2));\n;\nreturn silence;", text("s(\"a\");;", addReturn = true))
    }

    @Test
    fun theLastExpressionStatementBecomesAReturn() {
        val body = statements("s(\"a\"); s(\"b\")", addReturn = true)
        assertTrue(body[0] is ExpressionStatement)
        val last = body[1] as ReturnStatement
        assertEquals(SourceRange(8, 14), last.range)
        assertEquals("return s(m('b', 10));", printAst(last))
    }

    @Test
    fun theValueOfALabelledLastStatementIsTheCallOfP() {
        assertEquals("return s(m('a', 5)).p('\$');", text("\$: s(\"a\")", addReturn = true))
    }

    @Test
    fun theSilenceAddedAtTheEndHasNoLength() {
        val code = "const a = 1\n"
        val silence = (statements(code, addReturn = true)[1] as ReturnStatement).argument as Identifier
        assertEquals(SourceRange(code.length, code.length), silence.range)
    }

    // ---- T11: bare samples calls -------------------------------------------------------------------------------------------

    @Test
    fun aBareSamplesCallGetsAwaitAtAnyDepth() {
        assertEquals("f(await samples('a'));", text("f(samples('a'))"))
        assertEquals("1 + await samples('a');", text("1 + samples('a')"))
        assertEquals("(await samples('a')).then(f);", text("samples('a').then(f)"))
    }

    @Test
    fun aCallOfSamplesUnderAnAwaitIsLeftAlone() {
        assertEquals("await samples('a');", text("await samples('a')"))
        assertEquals("await samples('a');", text("await (samples('a'))"))
        assertEquals("await (await samples('a')).then(f);", text("await samples('a').then(f)"))
    }

    @Test
    fun aMethodCalledSamplesIsNotTheSamplesFunction() {
        assertEquals("x.samples('a');", text("x.samples('a')"))
    }

    @Test
    fun theAwaitAddedToASamplesCallHasTheRangeOfTheCall() {
        val await = (statements("  samples('a')")[0] as ExpressionStatement).expression as AwaitExpression
        assertEquals(SourceRange(2, 14), await.range)
        assertEquals(SourceRange(2, 14), await.argument.range)
    }

    @Test
    fun anAwaitAddedInAnOrdinaryFunctionIsASyntaxError() {
        for (code in listOf("const f = () => { samples('x') }", "function f() { samples('x') }", "({ m() { samples('x') } })")) {
            val e = assertFailsWith<CosicError>(code) { transpile(code) }
            assertEquals("await is only valid in async functions and the top level bodies of modules", e.message, code)
            assertEquals(code.indexOf("samples"), e.range!!.start, code)
        }
        // an ordinary function inside an async one that does not call it is fine
        transpile("const f = async () => { const g = () => 1; return samples('a') }")
        transpile("async function f() { return samples('a') }")
    }

    // ---- T12: sliders --------------------------------------------------------------------------------------------------------

    @Test
    fun aSliderGetsItsIdFromTheOffsetOfItsFirstArgument() {
        val result = transpiled("slider(0.5, 0, 1)")
        assertEquals("sliderWithID('slider_7', 0.5, 0, 1);", printAst(result.program))
        assertEquals(listOf(SliderConfig(7, 10, "0.5", 0.0, 1.0, null, "slider_7")), result.sliders)
    }

    @Test
    fun aSliderHasBoundsOfZeroAndOneAndNoStepUnlessGiven() {
        assertEquals(SliderConfig(7, 8, "1", 0.0, 1.0, null, "slider_7"), transpiled("slider(1)").sliders.single())
        assertEquals(SliderConfig(7, 8, "1", 0.0, 10.0, 0.5, "slider_7"), transpiled("slider(1, 0, 10, 0.5)").sliders.single())
        assertEquals(SliderConfig(7, 8, "1", 2.0, 1.0, null, "slider_7"), transpiled("slider(1, 2)").sliders.single())
    }

    @Test
    fun aSliderTakesItsBoundsFromNumberLiteralsOnly() {
        // `-2` is a unary expression, not a number literal; a literal that is not a number counts as absent
        assertEquals(SliderConfig(7, 9, null, 0.0, 2.0, 0.5, "slider_7"), transpiled("slider(-1, -2, 2, 0.5)").sliders.single())
        assertEquals(SliderConfig(7, 8, "1", 0.0, 1.0, null, "slider_7"), transpiled("slider(1, a, b)").sliders.single())
        assertEquals(SliderConfig(7, 8, "1", 0.0, 1.0, null, "slider_7"), transpiled("slider(1, null, null)").sliders.single())
        assertEquals(SliderConfig(7, 11, "0x10", 0.0, 255.0, null, "slider_7"), transpiled("slider(0x10, 0, 0xff)").sliders.single())
        assertEquals(SliderConfig(7, 10, "1e3", 0.0, 10000.0, null, "slider_7"), transpiled("slider(1e3, 0, 1e4)").sliders.single())
    }

    @Test
    fun theSourceTextOfTheValueIsKeptForLiteralsOnly() {
        assertNull(transpiled("slider(x, 0, 1)").sliders.single().value)
        assertNull(transpiled("slider(...args)").sliders.single().value)
        assertEquals("sliderWithID('slider_7', ...args);", text("slider(...args)"))
    }

    @Test
    fun everySliderIsRecordedInTheOrderOfTheWalk() {
        val result = transpiled("n(slider(1, 0, 8)).add(slider(2, 0, 8))")
        assertEquals(listOf("slider_9", "slider_30"), result.sliders.map { it.id })
        assertEquals("n(sliderWithID('slider_9', 1, 0, 8)).add(sliderWithID('slider_30', 2, 0, 8));", printAst(result.program))
    }

    @Test
    fun aSliderInsideASliderIsRecordedToo() {
        val result = transpiled("slider(slider(1))")
        assertEquals("sliderWithID('slider_7', sliderWithID('slider_14', 1));", printAst(result.program))
        assertEquals(listOf("slider_7", "slider_14"), result.sliders.map { it.id })
    }

    @Test
    fun aSliderWithoutArgumentsFailsLikeTheOriginal() {
        val e = assertFailsWith<CosicError> { transpile("slider()") }
        assertEquals("Cannot read properties of undefined (reading 'start')", e.message)
        assertEquals(SourceRange(0, 8), e.range)
    }

    @Test
    fun aMethodCalledSliderIsNotTheSliderFunction() {
        val result = transpiled("x.slider(1)")
        assertEquals("x.slider(1);", printAst(result.program))
        assertEquals(emptyList(), result.sliders)
    }

    @Test
    fun theNodesOfASliderKeepTheRangesOfTheOriginal() {
        val call = firstCall(transpiled("  slider(0.5, 0, 1)").program)
        assertEquals(SourceRange(2, 19), call.range)
        assertEquals(SourceRange(2, 8), call.callee.range)
        assertEquals("sliderWithID", (call.callee as Identifier).name)
        assertEquals(SourceRange(9, 12), call.arguments[0].range)
        assertEquals("slider_9", (call.arguments[0] as Literal).value)
    }

    @Test
    fun aSliderWithAMiniNotationStringStillHasItsStringWalked() {
        val result = transpiled("slider(\"1\", 0, 1)")
        assertEquals("sliderWithID('slider_7', m('1', 7), 0, 1);", printAst(result.program))
        assertEquals("\"1\"", result.sliders.single().value)
    }

    // ---- T13: widgets -----------------------------------------------------------------------------------------------------------

    @Test
    fun aWidgetCallGetsItsIdAsFirstArgument() {
        val code = "s(\"bd\")._pianoroll({ fold: 1 })"
        val result = transpiled(code, widgetTypes = DEFAULT_WIDGET_TYPES)
        assertEquals("s(m('bd', 2))._pianoroll('_widget__pianoroll_0', { fold: 1 });", printAst(result.program))
        assertEquals(listOf(WidgetConfig("_pianoroll", 0, code.length, 0, null)), result.widgets)
        assertEquals("_widget__pianoroll_0", result.widgets.single().widgetId)
    }

    @Test
    fun theWidgetIdHasThePrefixOfTheOptions() {
        val result = transpiled("x._scope()", widgetTypes = DEFAULT_WIDGET_TYPES, id = "editor1")
        assertEquals("x._scope('editor1_widget__scope_0');", printAst(result.program))
        assertEquals("editor1_widget__scope_0", result.widgets.single().widgetId)
        assertEquals("_widget__scope_0", WidgetConfig("_scope", 0, 0, 0, "").widgetId)
    }

    @Test
    fun theIndexCountsTheWidgetsOfTheSameTypeOnly() {
        val result = transpiled("\$: s(\"a\")._scope()\n\$: s(\"b\")._scope()._pianoroll()", widgetTypes = DEFAULT_WIDGET_TYPES)
        // the second line is walked outermost call first: the _pianoroll, then the _scope inside it
        assertEquals(listOf("_scope" to 0, "_pianoroll" to 0, "_scope" to 1), result.widgets.map { it.type to it.index })
    }

    @Test
    fun withoutEmitWidgetsTheCallIsRewrittenButNotRecordedAndTheIndexStaysZero() {
        val result = transpiled("x._scope()._scope()", widgetTypes = DEFAULT_WIDGET_TYPES, emitWidgets = false)
        assertEquals("x._scope('_widget__scope_0')._scope('_widget__scope_0');", printAst(result.program))
        assertEquals(emptyList(), result.widgets)
    }

    @Test
    fun onlyTheRegisteredMethodNamesAreWidgets() {
        assertEquals("s(m('bd', 2)).pianoroll();", text("s(\"bd\").pianoroll()", widgetTypes = DEFAULT_WIDGET_TYPES))
        assertEquals("x._scope();", text("x._scope()"))
        assertEquals("x._scope('_widget__scope_0');", text("x._scope()", widgetTypes = setOf("_scope")))
        assertEquals("x._scope;", text("x._scope", widgetTypes = DEFAULT_WIDGET_TYPES))
        assertEquals("_scope();", text("_scope()", widgetTypes = DEFAULT_WIDGET_TYPES))
    }

    @Test
    fun theSevenWidgetTypesOfTheEditor() {
        assertEquals(setOf("_pianoroll", "_punchcard", "_spiral", "_scope", "_pitchwheel", "_spectrum", "_print"), DEFAULT_WIDGET_TYPES)
    }

    @Test
    fun aComputedPropertyThatIsAnIdentifierNamedLikeAWidgetIsOne() {
        // the original looks at the name of the property and not at `computed`
        assertEquals("x[_scope]('_widget__scope_0');", text("x[_scope]()", widgetTypes = DEFAULT_WIDGET_TYPES))
        assertEquals("x[m('_scope', 2)]();", text("x[\"_scope\"]()", widgetTypes = DEFAULT_WIDGET_TYPES))
    }

    @Test
    fun aWidgetInAnArgumentOfAWidgetCallIsCountedAfterIt() {
        val result = transpiled("x._scope(y._scope())", widgetTypes = DEFAULT_WIDGET_TYPES)
        assertEquals("x._scope('_widget__scope_0', y._scope('_widget__scope_1'));", printAst(result.program))
        assertEquals(listOf(WidgetConfig("_scope", 0, 20, 0, null), WidgetConfig("_scope", 9, 19, 1, null)), result.widgets)
    }

    @Test
    fun theIdOfAWidgetCarriesTheRangeOfTheMethodName() {
        val call = firstCall(transpiled("x._scope()", widgetTypes = DEFAULT_WIDGET_TYPES).program)
        assertEquals(SourceRange(0, 10), call.range)
        assertEquals(SourceRange(2, 8), call.arguments[0].range)
        assertTrue(call.callee is MemberExpression)
        assertEquals(SourceRange(0, 8), call.callee.range)
    }

    // ---- T18: things that are not done ---------------------------------------------------------------------------------------

    @Test
    fun aNoteNameIsNotAVariable() {
        assertEquals("return c3;", text("c3", addReturn = true))
    }

    // ---- hooks --------------------------------------------------------------------------------------------------------------------

    @Test
    fun aSpecialFormIsRewrittenAfterTheMiniStringsBelowItWere() {
        TranspileHooks.special("twiceForTest") { call, _ ->
            CallExpression(call.start, call.end, Identifier(call.callee.start, call.callee.end, "twice"), call.arguments + call.arguments, false)
        }
        assertEquals("twice(m('a b', 13), m('a b', 13));", text("twiceForTest(\"a b\")"))
    }

    @Test
    fun aSpecialFormIsFoundAsAFunctionAndAsAMethodButNotAsAComputedMember() {
        TranspileHooks.special("methodForTest") { call, _ -> CallExpression(call.start, call.end, Identifier(call.start, call.start, "done"), emptyList(), false) }
        assertEquals("done();", text("methodForTest(1)"))
        assertEquals("done();", text("x.methodForTest(1)"))
        assertEquals("done();", text("x?.methodForTest(1)"))
        assertEquals("x['methodForTest'](1);", text("x['methodForTest'](1)"))
        assertEquals("x[methodForTest](1);", text("x[methodForTest](1)"))
    }

    @Test
    fun theInnermostSpecialFormIsRewrittenFirst() {
        TranspileHooks.special("nestedForTest") { call, _ ->
            CallExpression(call.start, call.end, Identifier(call.callee.start, call.callee.end, "wrapped"), call.arguments + call.arguments, false)
        }
        assertEquals("wrapped(wrapped(1, 1), wrapped(1, 1));", text("nestedForTest(nestedForTest(1))"))
    }

    @Test
    fun aSpecialFormReceivesTheCallAndTheSource() {
        var seen: Pair<CallExpression, String>? = null
        TranspileHooks.special("seenForTest") { call, source ->
            seen = call to source
            call
        }
        val code = "  seenForTest(1, \"a b\")"
        transpiled(code)
        val (call, source) = assertNotNull(seen)
        assertEquals(code, source)
        assertEquals("seenForTest(1, m('a b', 17))", printExpression(call))
        assertEquals(SourceRange(2, code.length), call.range)
    }

    @Test
    fun theNodeASpecialFormReturnsIsNotWalkedAgain() {
        // it holds a double-quoted string that would otherwise become a mini-notation call
        TranspileHooks.special("freshForTest") { call, _ ->
            CallExpression(call.start, call.end, Identifier(call.start, call.start, "fresh"), listOf(Literal(call.start, call.end, "x y", "\"x y\"")), false)
        }
        assertEquals("fresh('x y');", text("freshForTest(1)"))
    }

    @Test
    fun aLaterRegistrationOfTheSameNameReplacesTheEarlierOne() {
        TranspileHooks.special("replacedForTest") { call, _ -> Identifier(call.start, call.end, "first") }
        TranspileHooks.special("replacedForTest") { call, _ -> Identifier(call.start, call.end, "second") }
        assertEquals("second;", text("replacedForTest()"))
    }

    // ---- the mini locator ---------------------------------------------------------------------------------------------------------

    @Test
    fun anErrorOfTheLocatorIsTheErrorOfTheTranspiler() {
        val locator = MiniLeafLocator { quoted, start -> throw CosicError("[mini] parse error in $quoted", SourceRange(start, start + quoted.length)) }
        val e = assertFailsWith<CosicError> { transpiled("s(\"a (\")", locator = locator) }
        assertEquals("[mini] parse error in \"a (\"", e.message)
        assertEquals(SourceRange(2, 7), e.range)
    }

    @Test
    fun theLocatorIsAskedForEveryMiniStringWithTheQuotesAndTheOffsetOfTheOpeningQuote() {
        val asked = ArrayList<Pair<String, Int>>()
        val locator = MiniLeafLocator { quoted, start -> asked.add(quoted to start); emptyList() }
        transpiled("s(\"a b\").n(`c`)", locator = locator)
        assertEquals(listOf("\"a b\"" to 2, "\"c\"" to 11), asked)
    }

    // ---- the printer the tests rely on ---------------------------------------------------------------------------------------------

    @Test
    fun thePrinterLaysOutObjectsAndArraysLikeEscodegen() {
        assertEquals("f([\n    1,\n    2\n], { a: 1 }, {\n    a: 1,\n    b: 2\n});", text("f([1, 2], { a: 1 }, { a: 1, b: 2 })"))
    }

    private object WholeCodeLanguage : EmbeddedLanguage {
        override fun evaluate(code: String, codeStart: Int, realm: Realm, range: SourceRange): Any? = error("the transpiler does not evaluate")
        override fun locations(code: String, codeStart: Int): List<SourceRange> = listOf(SourceRange(codeStart, codeStart + code.length))
    }

    private class CountingLanguage : EmbeddedLanguage {
        var asked = 0
        var lastCode: String? = null
        var lastStart = -1
        override fun evaluate(code: String, codeStart: Int, realm: Realm, range: SourceRange): Any? = error("the transpiler does not evaluate")
        override fun locations(code: String, codeStart: Int): List<SourceRange> {
            asked++
            lastCode = code
            lastStart = codeStart
            return emptyList()
        }
    }
}
