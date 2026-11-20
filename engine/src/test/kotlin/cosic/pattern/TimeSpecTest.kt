package cosic.pattern

import cosic.script.catalogue.Catalogue
import cosic.script.evaluateProgram
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The time and structure functions (CORE task T1) against what Strudel's own `@strudel/core` prints for the same programs:
 * every expected line below comes from running the program in the pinned checkout (the parity snapshots and the extra
 * snippets of `parity/extra-snippets/2.1.json` check the rest). Programs that need the operators (`mul`, `struct`,
 * `restart`, the alignments) are skipped until those are registered.
 */
class TimeSpecTest {
    init {
        Catalogue.installAll()
    }

    private fun pattern(code: String): Pattern = evaluateProgram(code).pattern

    private fun lines(code: String, cycles: Long = 1): List<String> = pattern(code).lines(0, cycles)

    private fun stepsOf(code: String): String? = pattern(code).steps?.show()

    private val operatorsAreThere: Boolean get() = alignHook != null && Registry.lookup("restart") != null

    private val identity = jsFn(1) { it[0] }

    private val doubleSpeed = jsFn(1) { a -> (a[0] as Pattern)._fast(2.0) }

    @Test
    fun everyNameIsRegistered() {
        val names = listOf(
            "ply", "hurry", "inside", "outside", "lastOf", "firstOf", "every", "apply", "applyN", "cpm", "zoom", "zoomArc", "zoomarc",
            "bite", "isobite", "cyclestruct", "cycleStruct", "linger", "segment", "seg", "swingBy", "swing", "invert", "inv", "when",
            "off", "brak", "rev", "revv", "pressBy", "press", "palindrome", "juxBy", "juxby", "juxFlipBy", "juxflipby", "fluxBy",
            "fluxby", "jux", "juxFlip", "juxflip", "flux", "echoWith", "echowith", "stutWith", "stutwith", "echo", "stut", "plyWith",
            "plywith", "plyForEach", "plyforeach", "iter", "iterBack", "iterback", "repeatCycles", "chunk", "slowchunk", "slowChunk",
            "chunkBack", "chunkback", "fastChunk", "fastchunk", "chunkInto", "chunkinto", "chunkBackInto", "chunkbackinto", "bypass",
            "ribbon", "rib", "filter", "filterWhen", "within", "superimpose",
        )
        for (name in names) assertNotNull(Registry.lookup(name), "no function '$name'")
        // plain methods exist for every registered function of more than one parameter, and for none of the others
        assertTrue(Registry.lookup("ply")!!.hasPlain)
        assertTrue(!Registry.lookup("rev")!!.hasPlain)
        // the methods of the class itself are definitions of the catalogue and work on programs
        assertEquals(emptyList(), lines("s(\"bd\").hush()"))
        assertEquals(1, lines("s(\"bd\").tag('x')").size)
        assertEquals(3, lines("s(\"bd\").layer(rev, fast(2))").size)
    }

    // ---- the spec's table --------------------------------------------------------------------------------------------------

    @Test
    fun aNegativeFactorHasNoHaps() {
        assertEquals(emptyList(), fastcat("a", "b")._fast(-2.0).lines(0, 1))
        assertEquals(emptyList(), lines("s(\"bd sd\").ply(-1)"))
    }

    @Test
    fun iterStartsOneStepFurtherEachCycle() {
        assertEquals(
            listOf(
                "[ 0/1 → 1/2 | x1 ]", "[ 1/2 → 1/1 | x2 ]", "[ 1/1 → 3/2 | x2 ]", "[ 3/2 → 2/1 | y1 ]",
                "[ 2/1 → 5/2 | y1 ]", "[ 5/2 → 3/1 | y2 ]", "[ 3/1 → 7/2 | y2 ]", "[ 7/2 → 4/1 | x1 ]",
            ),
            cat(listOf(fastcat("x1", "x2"), fastcat("y1", "y2")))._iter(2.0).lines(0, 4),
        )
    }

    @Test
    fun chunkAppliesTheFunctionToOnePartPerCycleOfTheRepeatedPattern() {
        val upper = jsFn(1) { a -> (a[0] as Pattern).fmap { (it as String).uppercase() } }
        assertEquals(
            listOf(
                "[ 0/1 → 1/2 | X1 ]", "[ 1/2 → 1/1 | x2 ]", "[ 1/1 → 3/2 | x1 ]", "[ 3/2 → 2/1 | X2 ]",
                "[ 2/1 → 5/2 | Y1 ]", "[ 5/2 → 3/1 | y2 ]", "[ 3/1 → 7/2 | y1 ]", "[ 7/2 → 4/1 | Y2 ]",
            ),
            cat(listOf(fastcat("x1", "x2"), fastcat("y1", "y2")))._chunk(2.0, upper).lines(0, 4),
        )
    }

    @Test
    fun everyNeedsALengthArrayAccepts() {
        assertEquals("Invalid array length", assertFailsWith<CosicError> { pure(1.0)._firstOf(0.0, identity) }.message)
        assertEquals("Invalid array length", assertFailsWith<CosicError> { pure(1.0)._firstOf(2.5, identity) }.message)
        assertEquals("Invalid array length", assertFailsWith<CosicError> { pure(1.0)._lastOf(-3.0, identity) }.message)
        assertEquals("Invalid array length", assertFailsWith<CosicError> { pure(1.0)._lastOf(Double.NaN, identity) }.message)
        assertEquals("Invalid array length", assertFailsWith<CosicError> { pure(1.0)._chunk(0.0, identity) }.message)
        // `n - 1` is JavaScript arithmetic on whatever n is
        assertEquals(listOf("[ 0/1 → 1/1 | 1 ]"), pure(1.0)._firstOf("1", identity).lines(0, 1))
        assertEquals("func is not a function", assertFailsWith<CosicError> { pure(1.0)._firstOf(2.0, 5.0) }.message)
    }

    @Test
    fun everyWorksOnTheCycleWithoutBuildingAnArray() {
        // Strudel spreads an array of n - 1 patterns into slowcatPrime, which a million of overflows the stack of
        val last = pure(1.0)._lastOf(1_000_000.0, doubleSpeed)
        assertEquals(1, last.lines(0, 1).size)
        assertEquals(2, last.lines(999_999, 1_000_000).size)
        val first = pure(1.0)._firstOf(1_000_000.0, doubleSpeed)
        assertEquals(2, first.lines(0, 1).size)
        assertEquals(1, first.lines(1, 2).size)
    }

    @Test
    fun juxPansTheCopiesApart() {
        assertEquals(
            listOf("[ 0/1 → 1/1 | s:bd pan:0 ]", "[ 0/1 → 1/1 | s:bd pan:1 ]"),
            pure(linkedMapOf<String, Any?>("s" to "bd"))._jux(identity).lines(0, 1),
        )
        assertEquals(
            listOf("[ 0/1 → 1/1 | pan:0.04999999999999999 ]", "[ 0/1 → 1/1 | pan:0.55 ]"),
            pure(linkedMapOf<String, Any?>("pan" to 0.3))._juxBy(0.5, identity).lines(0, 1),
        )
        assertEquals(
            listOf(
                "[ 0/1 → 1/1 | s:bd pan:0.25 ]", "[ 0/1 → 1/1 | s:bd pan:0.75 ]",
                "[ 1/1 → 2/1 | s:bd pan:0.75 ]", "[ 1/1 → 2/1 | s:bd pan:0.25 ]",
            ),
            pure(linkedMapOf<String, Any?>("s" to "bd"))._juxFlipBy(0.5, identity).lines(0, 2),
        )
    }

    @Test
    fun juxNeedsControlMaps() {
        val logged = ArrayList<String?>()
        val before = errorLogger
        errorLogger = { logged.add(it.message) }
        try {
            assertEquals(emptyList(), pure("bd")._jux(identity).lines(0, 1))
        } finally {
            errorLogger = before
        }
        val message = "Cannot use 'in' operator to search for 'pan' in bd"
        assertEquals<List<String?>>(listOf(message, message), logged)
    }

    @Test
    fun lingerOfANegativeNumberIsATypeError() {
        assertEquals("t.add is not a function", assertFailsWith<CosicError> { pure("a")._linger(-1.0) }.message)
        assertEquals(emptyList(), pure("a")._linger(0.0).lines(0, 1))
        assertEquals(emptyList(), pure("a")._linger("0").lines(0, 1))
    }

    // ---- what a registered one-argument function does with extra arguments ---------------------------------------------------------

    @Test
    fun revTakesTheFirstArgumentAsThePatternButThePatternifiedFunctionsTakeTheLast() {
        val ab = fastcat("a", "b")
        // echoWith(…, rev) calls rev with the pattern and the number of the echo; rev is registered without patternify
        assertEquals(listOf("[ 0/1 → 1/2 | b ]", "[ 1/2 → 1/1 | a ]"), (Registry.call("rev", listOf(ab, 7.0)) as Pattern).lines(0, 1))
        assertEquals(listOf("[ 0/1 → 1/1 | 7 ]"), (Registry.call("revv", listOf(ab, 7.0)) as Pattern).lines(0, 1))
        assertEquals(
            listOf(
                "[ -1/4 ⇜ (0/1 → 1/4) | s:sd ]", "[ 0/1 → 1/2 | s:sd ]", "[ 1/4 → 3/4 | s:bd ]", "[ 1/2 → 1/1 | s:bd ]",
                "[ (3/4 → 1/1) ⇝ 5/4 | s:sd ]",
            ),
            lines("s(\"bd sd\").echoWith(2, 0.25, rev)"),
        )
    }

    // ---- steps ------------------------------------------------------------------------------------------------------------------

    @Test
    fun stepsFollowStrudel() {
        // sequences made by seq, so that the steps do not depend on what the mini-notation says of them
        val ab = "seq(pure('a'), pure('b'))"
        val abcd = "seq(pure('a'), pure('b'), pure('c'), pure('d'))"
        val sab = "$ab.s()"
        val expected = listOf(
            "$ab.ply(3)" to "6/1",
            "$ab.ply(\"<2 3>\")" to null,
            "$abcd.zoom(0.25, 0.75)" to "2/1",
            "$abcd.zoom(\"<0 .25>\", 0.75)" to null,
            "$abcd.zoom(0.75, 0.25)" to "0/1",
            "$abcd.linger(0.5)" to "4/1",
            "$abcd.linger(\"<1 .5>\")" to "4/1",
            "$ab.rev()" to "2/1",
            "$ab.revv()" to null,
            "$ab.iter(2)" to "2/1",
            "$sab.jux(rev)" to "2/1",
            "$sab.jux(x => x.fast(3))" to "2/1",
            "$ab.palindrome()" to "2/1",
            "$ab.chunk(2, rev)" to "2/1",
            "$ab.fastChunk(2, rev)" to "2/1",
            "$sab.swingBy(1/3, 2)" to "2/1",
            "$ab.off(.25, rev)" to "2/1",
            "$sab.echo(3, .125, .5)" to "2/1",
            "$ab.plyWith(3, x => x)" to "6/1",
            "$ab.plyForEach(3, (x, i) => x)" to "6/1",
            "$ab.every(2, rev)" to null,
            "$ab.hush()" to "1/1",
            "$ab.bite(2, \"0 1\")" to null,
            "$ab.press()" to null,
            "$ab.brak()" to null,
            "$ab.inside(2, rev)" to "2/1",
            "$ab.outside(2, rev)" to "2/1",
            "$ab.when(1, rev)" to "2/1",
            "$ab.when(\"<0 1>\", rev)" to null,
            "seq(pure(1), pure(0)).invert()" to "2/1",
            "slowcat(pure('a'), pure('b')).repeatCycles(2)" to "1/1",
            "$ab.bypass(0)" to "2/1",
            "$ab.layer(rev, fast(2))" to "2/1",
            "$ab.superimpose(rev)" to "2/1",
            "applyN(2, fast(2), $ab)" to "2/1",
            "$ab.apply(fast(2))" to "2/1",
            "$ab.cpm(120)" to "2/1",
            "$ab.pressBy(.25)" to null,
        )
        for ((code, steps) in expected) assertEquals(steps, stepsOf(code), code)
    }

    @Test
    fun segmentSetsTheStepsToTheRate() {
        assumeTrue(operatorsAreThere)
        assertEquals("3/1", stepsOf("seq(pure('a'), pure('b')).segment(3)"))
        assertNull(stepsOf("seq(pure('a'), pure('b')).segment(\"<2 4>\")"))
        assertEquals(
            listOf("[ 0/1 → 1/3 | a ]", "[ (1/3 → 1/2) ⇝ 2/3 | a ]", "[ 1/3 ⇜ (1/2 → 2/3) | b ]", "[ 2/3 → 1/1 | b ]"),
            lines("\"a b\".segment(3)"),
        )
    }

    // ---- the functions that call functions of another category ---------------------------------------------------------------------

    @Test
    fun hurryMultipliesTheSpeedIn() {
        assumeTrue(operatorsAreThere)
        assertEquals(listOf("[ 0/1 → 1/2 | s:bd speed:2 ]", "[ 1/2 → 1/1 | s:bd speed:2 ]"), lines("pure({s: 'bd'}).hurry(2)"))
        assertEquals(listOf("[ 0/1 → 1/2 | s:bd speed:6 ]", "[ 1/2 → 1/1 | s:bd speed:6 ]"), lines("pure({s: 'bd', speed: 3}).hurry(2)"))
    }

    @Test
    fun echoSetsTheGain() {
        val expected = listOf(
            "[ -3/4 ⇜ (0/1 → 1/4) | s:bd gain:0.5 ]",
            "[ -1/2 ⇜ (0/1 → 1/2) | s:bd gain:0.25 ]",
            "[ 0/1 → 1/1 | s:bd gain:1 ]",
            "[ (1/4 → 1/1) ⇝ 5/4 | s:bd gain:0.5 ]",
            "[ (1/2 → 1/1) ⇝ 3/2 | s:bd gain:0.25 ]",
        )
        assertEquals(expected, lines("pure({s: 'bd'}).echo(3, 1/4, .5)"))
        assertEquals(expected, lines("pure({s: 'bd'}).stut(3, .5, 1/4)"))
    }

    @Test
    fun ribbonLoopsTheCyclesFromTheOffset() {
        assumeTrue(operatorsAreThere)
        assertEquals(
            listOf("[ 0/1 → 1/1 | b ]", "[ 1/1 → 2/1 | c ]", "[ 2/1 → 3/1 | b ]", "[ 3/1 → 4/1 | c ]"),
            lines("\"<a b c d>\".ribbon(1, 2)", 4),
        )
        assertNull(stepsOf("slowcat(pure('a'), pure('b'), pure('c')).ribbon(1, 2)"))
    }

    @Test
    fun intoLoopsThePieceUnderEachTrueHap() {
        assumeTrue(operatorsAreThere)
        assertEquals(
            listOf("[ 0/1 → 1/8 | a ]", "[ 1/8 → 1/4 | b ]", "[ 1/4 → 3/8 | a ]", "[ 3/8 → 1/2 | b ]", "[ 1/2 → 3/4 | c ]", "[ 3/4 → 1/1 | d ]"),
            lines("\"a b c d\".into(\"1 0\", fast(2))"),
        )
        assertNull(stepsOf("seq(pure('a'), pure('b'), pure('c'), pure('d')).into(\"1 0\", fast(2))"))
    }

    // ---- filtering (the oracle's runtime replaces `filter` with the identity, so these come from a runtime without its mocks) ------------

    @Test
    fun filterKeepsTheHapsTheTestAcceptsWithTheirIndex() {
        assertEquals(
            listOf(
                "[ 0/1 → 1/8 | s:hh ]", "[ 1/8 → 1/4 | s:hh ]", "[ 1/4 → 3/8 | s:hh ]", "[ 3/8 → 1/2 | s:hh ]",
                "[ 1/2 → 5/8 | s:hh ]", "[ 5/8 → 3/4 | s:hh ]", "[ 3/4 → 7/8 | s:hh ]",
            ),
            lines("s(\"hh!7 oh\").filter(hap => hap.value.s === 'hh')"),
        )
        assertEquals(listOf("[ 0/1 → 1/4 | s:bd ]", "[ 1/2 → 3/4 | s:hh ]"), lines("s(\"bd sd hh cp\").filter((hap, i) => i % 2 == 0)"))
        assertEquals(
            listOf("[ 1/2 → 3/4 | n:2 ]", "[ 3/4 → 1/1 | n:3 ]", "[ 3/2 → 7/4 | n:2 ]", "[ 7/4 → 2/1 | n:3 ]"),
            lines("n(\"0 1 2 3\").filter(hap => hap.value.n > 1)", 2),
        )
        assertEquals(emptyList(), lines("s(\"bd sd\").filter(hap => false)"))
        assertEquals(listOf("[ 1/2 → 3/4 | n:2 ]", "[ 3/4 → 1/1 | n:3 ]"), lines("filter(hap => hap.value.n > 1, n(\"0 1 2 3\"))"))
        // a test that is no function is an error whatever the haps are (Strudel's queryArc logs it and answers nothing)
        assertEquals("5 is not a function", assertFailsWith<CosicError> { lines("n(\"0 1 2 3\").filter(5)") }.message)
        assertEquals("5 is not a function", assertFailsWith<CosicError> { pure("a")._filter(5.0).lines(0, 1) }.message)
        assertEquals(emptyList(), pure("a")._filter(5.0).queryArcLogged(Rational.ZERO, Rational.ONE))
    }

    @Test
    fun filterWhenTestsTheBeginOfTheWhole() {
        assertEquals(
            listOf("[ 0/1 → 1/4 | s:bd ]", "[ 1/4 → 1/2 | s:bd ]", "[ 1/2 → 3/4 | s:bd ]", "[ 3/4 → 1/1 | s:bd ]"),
            lines("s(\"bd*4\").filterWhen((t) => t < 1)", 2),
        )
        assertEquals(listOf("[ 3/2 → 7/4 | s:bd ]", "[ 7/4 → 2/1 | s:bd ]"), lines("s(\"bd*4\").filterWhen((t) => t >= 1.5)", 2))
        assertEquals(
            listOf("[ 1/2 → 3/4 | s:bd ]", "[ 3/4 → 1/1 | s:bd ]", "[ 3/2 → 7/4 | s:bd ]", "[ 7/4 → 2/1 | s:bd ]"),
            lines("s(\"bd*4\").filterWhen((t) => t.cyclePos() > 0.4)", 2),
        )
    }

    @Test
    fun withinAppliesTheFunctionToTheHapsThatBeginInsideTheSpan() {
        assertEquals(
            listOf(
                "[ 0/1 → 1/8 | n:0 ]", "[ 1/8 → 1/4 | n:1 ]", "[ 1/4 → 3/8 | n:2 ]",
                "[ 1/2 → 5/8 | n:0 ]", "[ 5/8 → 3/4 | n:1 ]", "[ 3/4 → 7/8 | n:2 ]", "[ 3/4 → 1/1 | n:3 ]",
            ),
            lines("n(\"0 1 2 3\").within(0, 0.5, x => x.fast(2))"),
        )
        assertEquals(
            listOf(
                "[ 0/1 → 1/8 | n:0 ]", "[ 1/8 → 1/4 | n:1 ]", "[ 1/4 → 3/8 | n:9 ]", "[ 3/8 → 1/2 | n:9 ]",
                "[ 1/2 → 5/8 | n:9 ]", "[ 5/8 → 3/4 | n:9 ]", "[ 3/4 → 7/8 | n:9 ]", "[ 7/8 → 1/1 | n:7 ]",
            ),
            lines("n(\"0 1 2 3 4 5 6 7\").within(0.25, 0.75, x => x.n(9))"),
        )
        assertEquals(
            listOf(
                "[ 0/1 → 1/4 | n:9 ]", "[ 1/4 → 1/2 | n:9 ]", "[ 1/2 → 3/4 | n:9 ]", "[ 3/4 → 1/1 | n:9 ]",
                "[ 1/1 → 5/4 | n:0 ]", "[ 5/4 → 3/2 | n:1 ]", "[ 3/2 → 7/4 | n:9 ]", "[ 7/4 → 2/1 | n:9 ]",
            ),
            lines("n(\"0 1 2 3\").within(\"<0 .5>\", 0.75, x => x.n(9))", 2),
        )
        assertEquals(
            listOf("[ 0/1 → 1/4 | n:9 ]", "[ 1/4 → 1/2 | n:9 ]", "[ 1/2 → 3/4 | n:9 ]", "[ 3/4 → 1/1 | n:3 ]"),
            lines("within(0, 0.5, x => x.n(9), n(\"0 1 2 3\"))"),
        )
        assertEquals(
            listOf("[ 0/1 → 1/4 | n:0 ]", "[ 1/4 → 1/2 | n:1 ]", "[ 1/2 → 3/4 | n:9 ]", "[ 3/4 → 1/1 | n:3 ]"),
            lines("n(\"0 1 2 3\").within(0.5, 0.5, x => x.n(9))"),
        )
    }

    // ---- tags, layer, superimpose, applyN --------------------------------------------------------------------------------------

    @Test
    fun tagsAccumulateInTheContext() {
        val hap = pure("a").tag("x").tag("y").firstCycle(withContext = true).single()
        assertEquals(listOf("x", "y"), hap.context.tags)
        assertTrue(hap.hasTag("x") && hap.hasTag("y") && !hap.hasTag("z"))
        assertNull(pure("a").firstCycle(withContext = true).single().context.tags)
        assertEquals(
            listOf("[ 0/1 → 1/2 | s:bd ]", "[ 1/2 → 1/1 | s:sd ]"),
            lines("s(\"bd sd\").tag('a').withHaps(hs => hs.filter(h => h.hasTag('a')))"),
        )
        assertEquals(emptyList(), lines("s(\"bd sd\").tag('a').withHaps(hs => hs.filter(h => h.hasTag('c')))"))
    }

    @Test
    fun layerAndSuperimposeApplyTheirFunctionsToTheReceiver() {
        assertEquals(emptyList(), lines("n(\"0 1\").layer()"))
        assertEquals(2, lines("n(\"0 1\").layer(rev)").size)
        assertEquals(2, lines("n(\"0 1\").superimpose()").size)
        assertEquals(8, lines("n(\"0 1\").superimpose(fast(2), rev)").size)
        assertEquals(lines("n(\"0 1\").superimpose(fast(2), rev)"), lines("superimpose([fast(2), rev], n(\"0 1\"))"))
        assertEquals(lines("n(\"0 1\").superimpose(fast(2))"), lines("superimpose([fast(2)])(n(\"0 1\"))"))
        assertEquals("func is not a function", assertFailsWith<CosicError> { pure("a").layer(listOf(1.0)) }.message)
    }

    @Test
    fun applyNAppliesTheFunctionManyTimesOver() {
        val p = pure("a")
        assertEquals(8, (applyN(3.0, doubleSpeed, p) as Pattern).lines(0, 1).size)
        assertEquals(8, (applyN(2.5, doubleSpeed, p) as Pattern).lines(0, 1).size)
        assertEquals(1, (applyN("x", doubleSpeed, p) as Pattern).lines(0, 1).size)
        assertEquals(1, (applyN(-1.0, doubleSpeed, p) as Pattern).lines(0, 1).size)
    }
}
