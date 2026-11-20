package cosic.script

import cosic.pattern.CosicError
import cosic.pattern.Registry
import cosic.pattern._fast
import cosic.pattern.alignHook
import cosic.pattern.reg2
import cosic.script.catalogue.CallContext
import cosic.script.catalogue.catalogue
import cosic.script.js.toStr
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The bridge between the kernel and the interpreter: functions of the registry as globals and methods, `register` from a
 * program, and what happens to values (haps, fractions, objects, functions) where the two worlds meet.
 */
class PatternBridgeTest {
    private fun play(code: String, cycles: Int = 1): List<String> = shown(evaluateProgram(code).pattern, cycles)

    private fun failure(code: String): CosicError = assertFailsWith<CosicError>(code) { evaluateProgram(code) }

    @Test
    fun aRegisteredFunctionIsAGlobalThatTakesThePatternLastAndAMethod() {
        val method = play("""s("bd sd").fast(2)""")
        assertEquals(method, play("""fast(2, s("bd sd"))"""))
        assertEquals(method, play("""fast(2)(s("bd sd"))"""))
        assertEquals(method, play("""const double = fast(2); double(s("bd sd"))"""))
    }

    @Test
    fun anArgumentCanBeAPatternAndTheResultJoinsIt() {
        assertEquals(play("""s("bd sd").fast("<2 4>")""", 2), play("""fast("<2 4>", s("bd sd"))""", 2))
    }

    @Test
    fun theConstructorsTakeArraysAsSequences() {
        assertEquals(play("""stack(s("bd"), s("hh*2"))"""), play("""stack(...[s("bd"), s("hh*2")])"""))
        assertEquals(play("""seq("a", ["b", "c"])"""), play("""fastcat("a", fastcat("b", "c"))"""))
        assertEquals(listOf("[ 0/1 → 1/1 | e4 ]"), play("pure('e4')"))
    }

    @Test
    fun constantsOfTheRegistryAreGlobals() {
        assertEquals(emptyList(), play("silence"))
        assertEquals(listOf("[ 0/1 → 1/1 | a ]"), play("""stack(silence, pure('a'))"""))
    }

    @Test
    fun registerFromAProgramMakesAMethodAndTheCurriedFunction() {
        val expected = play("""s("bd sd").fast(2)""")
        assertEquals(expected, play("register('f', (a, pat) => pat.fast(a))\ns(\"bd sd\").f(2)"))
        assertEquals(expected, play("const f = register('f2', (n, pat) => pat.fast(n))\nf(2)(s(\"bd sd\"))"))
        assertEquals(expected, play("const f = register('f3', (n, pat) => pat.fast(n))\nf(2, s(\"bd sd\"))"))
        assertEquals(expected, play("register('f4', (a, pat) => pat.fast(a))\ns(\"bd sd\")._f4(2)"))
        assertEquals(expected, play("register('twice', pat => pat.fast(2))\ns(\"bd sd\").twice()"))
    }

    @Test
    fun theArgumentOfARegisteredFunctionCanBeAPattern() {
        assertEquals(
            play("""s("bd sd").fast("<2 4>")""", 2),
            play("register('f', (a, pat) => pat.fast(a))\ns(\"bd sd\").f(\"<2 4>\")", 2),
        )
    }

    @Test
    fun aRegisteredFunctionIsNotAGlobalAndBelongsToItsSession() {
        assertEquals("hh2 is not defined", failure("register('hh2', pat => pat)\nhh2(s(\"bd\"))").message)
        val session = ReplSession.create()
        session.evaluate("register('mine', pat => pat.fast(2))")
        assertEquals(play("""s("bd").fast(2)"""), shown(session.evaluate("s(\"bd\").mine()").pattern))
        assertTrue("mine is not a function" in assertFailsWith<CosicError> { evaluateProgram("s(\"bd\").mine()") }.message.orEmpty())
    }

    @Test
    fun registerComplainsAboutWrongArityAndAPatternAsName() {
        assertEquals(
            ".foo3() expects 2 inputs but got 1.",
            failure("register('foo3', (a, b, pat) => pat)\ns(\"bd\").foo3(1)").message,
        )
        assertEquals(
            "Name argument for register is a pattern, try using single quotes ('name') instead of double quotes (\"name\")",
            failure("""register("x", pat => pat)""").message,
        )
    }

    @Test
    fun registerWithoutPatternifyHandsOverTheArgumentsAsTheyAre() {
        val code = "register('g2', (n, pat) => pat.fast(2), false)\ns(\"bd\").g2(\"<1 2>\")"
        assertEquals(play("""s("bd").fast(2)"""), play(code))
    }

    @Test
    fun aMethodCanBeAddedToPatternPrototype() {
        val code = "Pattern.prototype.twice = function () { return this.fast(2) }\ns(\"bd sd\").twice()"
        assertEquals(play("""s("bd sd").fast(2)"""), play(code))
    }

    @Test
    fun aHapValueIsAnObjectTheProgramMayChangeInPlace() {
        val code = "s(\"bd sd\").withHap(h => { h.value.n = 2; return h })"
        assertEquals(listOf("[ 0/1 → 1/2 | s:bd n:2 ]", "[ 1/2 → 1/1 | s:sd n:2 ]"), play(code))
    }

    @Test
    fun withValueGivesValuesAsPlainObjectsAndTakesObjectsBack() {
        assertEquals(listOf("[ 0/1 → 1/1 | s:bd gain:0.5 ]"), play("s(\"bd\").withValue(v => ({ ...v, gain: 0.5 }))"))
        assertEquals(listOf("[ 0/1 → 1/1 | ab ]"), play("pure('a').fmap(v => v + 'b')"))
    }

    @Test
    fun aFractionActsAsANumberInArithmeticAndKeepsItsMethods() {
        // the haps of bd*4 begin at 0, 1/4, 1/2 and 3/4
        assertEquals(
            listOf("[ 0/1 → 1/4 | s:bd ]", "[ 1/4 → 1/2 | s:bd ]"),
            play("""s("bd*4").filterHaps(h => (h.whole.begin % 1) < 0.5)"""),
        )
        assertEquals(listOf("[ 0/1 → 1/1 | 1 ]"), play("pure(pure(7).queryArc(0, 1)[0].whole.end.add(2).valueOf() / 3)"))
    }

    @Test
    fun queryArcGivesAnArrayOfHaps() {
        assertEquals(listOf("[ 0/1 → 1/1 | 7 ]"), play("pure(pure(7).queryArc(0, 1)[0].value)"))
        assertEquals(listOf("[ 0/1 → 1/1 | true ]"), play("pure(pure(7).queryArc(0, 1)[0].hasOnset())"))
        assertEquals(listOf("[ 0/1 → 1/1 | 0/1 → 1/1 ]"), play("pure(pure(7).queryArc(0, 1)[0].wholeOrPart().show())"))
    }

    @Test
    fun withHapsTakesAndReturnsArrays() {
        val code = "\"a b\".withHaps(haps => [haps[1]])"
        assertEquals(listOf("[ 1/2 → 1/1 | b ]"), play(code))
    }

    @Test
    fun aFunctionOfTheProgramCanBeAPatternValue() {
        assertEquals(play("""s("bd sd").every(2, x => x.fast(2))""", 2), play("""s("bd sd").every(2, fast(2))""", 2))
    }

    @Test
    fun aMethodOfPatternsCannotBeCalledOnAString() {
        val error = failure("'bd sd'.fast(2)")
        assertTrue("fast is not a function" in error.message.orEmpty(), error.message)
    }

    @Test
    fun anOperatorHasAlignmentsWhenTheCatalogueSetsTheHook() {
        Registry.reg2("bridgeTestOp", alignable = true) { n, pat -> pat._fast(n) }
        val saved = alignHook
        try {
            alignHook = null
            assertEquals("alignment 'out' is not available", failure("""s("bd").bridgeTestOp.out(2)""").message)
            val seen = ArrayList<String>()
            alignHook = { pat, op, how, args ->
                seen.add("$op.$how(${args.joinToString()})")
                pat
            }
            assertEquals(listOf("[ 0/1 → 1/1 | s:bd ]"), play("""s("bd").bridgeTestOp.squeeze(2)"""))
            assertEquals(listOf("bridgeTestOp.squeeze(2.0)"), seen)
            // the plain call of an operator is its default alignment
            assertEquals(play("""s("bd").fast(2)"""), play("""s("bd").bridgeTestOp(2)"""))
        } finally {
            alignHook = saved
        }
    }

    @Test
    fun aDefinitionOfACategoryIsAGlobalOfEveryRealmThatIsInstalledAfterwards() {
        catalogue("Test") {
            function(listOf("bridgeTestSum"), 2) { ctx: CallContext -> ctx.realm.toStr(ctx.arg(0)) + ctx.realm.toStr(ctx.arg(1)) }
            method(listOf("bridgeTestTag"), 1) { ctx: CallContext -> ctx.bridge.patternArg(ctx.self)._fast(ctx.arg(0)) }
        }
        assertEquals(listOf("[ 0/1 → 1/1 | 12 ]"), play("pure(bridgeTestSum(1, 2))"))
        assertEquals(play("""s("bd sd").fast(2)"""), play("""s("bd sd").bridgeTestTag(2)"""))
    }

    @Test
    fun samplesIsDefinedByTheLibraryCategory() {
        assertTrue("error loading" in failure("samples('github:a/b')").message.orEmpty())
    }

    @Test
    fun evalScopeAndStrudelScopeExist() {
        assertEquals(listOf("[ 0/1 → 1/1 | 1 ]"), play("evalScope(); pure(1)"))
        assertEquals(listOf("[ 0/1 → 1/1 | 2 ]"), play("register('viaScope', (n, pat) => pat.fast(n)); pure(strudelScope.viaScope(2, pure(2)).queryArc(0, 1)[0].value)"))
    }
}
