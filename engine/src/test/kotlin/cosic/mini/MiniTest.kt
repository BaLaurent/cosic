package cosic.mini

import cosic.SourceRange
import cosic.pattern.CosicError
import cosic.pattern.Pattern
import cosic.pattern.Rational
import cosic.pattern.TOO_DEEP
import cosic.pattern.TOO_DENSE
import cosic.pattern.callMethod
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The mini-notation: the tests of Strudel's package (packages/mini/test/mini.test.mjs) and the corners of the grammar
 * that its tests do not reach. MiniOracleTest compares the whole grammar with Strudel's own output; these say what the
 * results are meant to be. Tests of what the notation makes of `euclid` and `degradeBy` run once those functions are
 * Strudel's (see [StrudelRegistry]).
 */
class MiniTest {
    init {
        StrudelRegistry // registers the functions
    }

    // ---- mini.test.mjs ----------------------------------------------------------------------------------------------

    @Test
    fun supportsSingleElements() {
        assertEquals(listOf<Any?>("a"), minV("a"))
    }

    @Test
    fun supportsRest() {
        assertEquals(emptyList(), minV("~"))
    }

    @Test
    fun supportsCat() {
        assertEquals(listOf("a: 0 - 1/2", "b: 1/2 - 1"), minS("a b"))
        assertEquals(listOf("a: 0 - 1/3", "b: 1/3 - 2/3", "c: 2/3 - 1"), minS("a b c"))
    }

    @Test
    fun supportsFast() {
        assertEquals(minS("[a a a] b"), minS("a*3 b"))
    }

    @Test
    fun supportsPatternedFast() {
        assertEquals(minS("[a a a] [a a a a a]"), minS("[a*<3 5>]*2"))
    }

    @Test
    fun supportsSlow() {
        assertEquals(minS("a b"), minS("[a a a]/3 b"))
    }

    @Test
    fun supportsPatternedSlow() {
        assertEquals(minS("[a a] a"), minS("[a a a a a a a a]/[2 4]"))
    }

    @Test
    fun supportsSlowcat() {
        assertEquals(listOf<Any?>("a"), minV("<a b>"))
    }

    @Test
    fun supportsDivision() {
        assertEquals(listOf("a: 0 - 2"), minS("a/2"))
        assertEquals(listOf("c3: 0 - 1"), minS("[c3 d3]/2"))
    }

    @Test
    fun supportsMultiplication() {
        assertEquals(listOf("c3: 0 - 1/2", "c3: 1/2 - 1"), minS("c3*2"))
        assertEquals(listOf<Any?>("c3", "d3", "c3", "d3"), minV("[c3 d3]*2"))
    }

    @Test
    fun supportsBrackets() {
        assertEquals(listOf("c3: 0 - 1/2", "d3: 1/2 - 3/4", "e3: 3/4 - 1"), minS("c3 [d3 e3]"))
        assertEquals(listOf("c3: 0 - 1/2", "d3: 1/2 - 3/4", "e3: 3/4 - 7/8", "f3: 7/8 - 1"), minS("c3 [d3 [e3 f3]]"))
    }

    @Test
    fun supportsCurlyBrackets() {
        assertEquals(minS("[a b a b a b, c d e c d e]"), minS("{a b, c d e}*3"))
        assertEquals(minS("[a b a b a b, c [d e] f c [d e] f]"), minS("{a b, c [d e] f}*3"))
        assertEquals(minS("[a b c a b c, d e d e d e]"), minS("{a b c, d e}*2"))
    }

    @Test
    fun supportsCurlyBracketsWithExplicitStepPerCycle() {
        assertEquals(minS("[a b a, c d e]"), minS("{a b, c d e}%3"))
        assertEquals(minS("[a b a b a, c d e c d]"), minS("{a b, c d e}%5"))
        assertEquals(minS("[a b a b a b, c d e c d e]"), minS("{a b, c d e}%6"))
    }

    @Test
    fun supportsCommas() {
        assertEquals(listOf("c3: 0 - 1", "e3: 0 - 1", "g3: 0 - 1"), minS("c3,e3,g3"))
        assertEquals(listOf("c3: 0 - 1/2", "e3: 0 - 1/2", "g3: 0 - 1/2", "f3: 1/2 - 1"), minS("[c3,e3,g3] f3"))
    }

    @Test
    fun supportsElongation() {
        assertEquals(listOf("a: 0 - 3/4", "b: 3/4 - 1"), minS("a@3 b"))
        assertEquals(listOf("a: 0 - 2/5", "b: 2/5 - 1"), minS("a@2 b@3"))
    }

    @Test
    fun supportsReplication() {
        assertEquals(listOf("a: 0 - 1/4", "a: 1/4 - 1/2", "a: 1/2 - 3/4", "b: 3/4 - 1"), minS("a!3 b"))
        assertEquals(minS("<a b c> <a b c> <a b c> d"), minS("[<a b c>]!3 d"))
    }

    @Test
    fun supportsReplicationViaRepeatedBang() {
        assertEquals(listOf("a: 0 - 1/4", "a: 1/4 - 1/2", "a: 1/2 - 3/4", "b: 3/4 - 1"), minS("a ! ! b"))
        assertEquals(minS("<a b c> <a b c> <a b c> d"), minS("[<a b c>]!! d"))
    }

    @Test
    fun supportsEuclideanRhythms() {
        assumeTrue(StrudelRegistry.euclid)
        assertEquals(listOf("a: 0 - 1/8", "a: 3/8 - 1/2", "a: 3/4 - 7/8"), minS("a(3, 8)"))
    }

    @Test
    fun supportsPatterningEuclideanRhythms() {
        assumeTrue(StrudelRegistry.euclid)
        assertEquals(minS("a(3,8) a(5,16)"), minS("[a(<3 5>, <8 16>)]*2"))
    }

    @Test
    fun supportsInvertedEuclideanRhythms() {
        assumeTrue(StrudelRegistry.euclid)
        assertEquals(listOf("a: 1/8 - 1/4", "a: 1/4 - 3/8", "a: 1/2 - 5/8", "a: 5/8 - 3/4", "a: 7/8 - 1"), minS("a(-3, 8)"))
    }

    @Test
    fun supportsEuclideanLegatoRhythms() {
        assumeTrue(StrudelRegistry.euclid)
        assertEquals(listOf("a: 0 - 3/8", "a: 3/8 - 3/4", "a: 3/4 - 1"), minS("a(3, -8)"))
    }

    @Test
    fun supportsInvertedEuclideanLegatoRhythms() {
        assumeTrue(StrudelRegistry.euclid)
        assertEquals(
            listOf("a: -1/8 - 1/8", "a: 1/8 - 1/4", "a: 1/4 - 1/2", "a: 1/2 - 5/8", "a: 5/8 - 7/8", "a: 7/8 - 9/8"),
            minS("a(-3, -8)"),
        )
    }

    @Test
    fun reproducesToussaintsExampleEuclideanAlgorithms() {
        assumeTrue(StrudelRegistry.euclid)
        // Toussaint's own table is wrong for (3,4), (5,6), (5,16) and (7,8); the corrected rows are what Strudel checks
        val cases = listOf(
            1 to 2 to "x ~", 1 to 3 to "x ~ ~", 1 to 4 to "x ~ ~ ~", 4 to 12 to "x ~ ~ x ~ ~ x ~ ~ x ~ ~", 2 to 5 to "x ~ x ~ ~",
            3 to 4 to "x x x ~", 3 to 5 to "x ~ x ~ x", 3 to 7 to "x ~ x ~ x ~ ~", 3 to 8 to "x ~ ~ x ~ ~ x ~", 4 to 7 to "x ~ x ~ x ~ x",
            4 to 9 to "x ~ x ~ x ~ x ~ ~", 4 to 11 to "x ~ ~ x ~ ~ x ~ ~ x ~", 5 to 6 to "x x x x x ~", 5 to 7 to "x ~ x x ~ x x",
            5 to 8 to "x ~ x x ~ x x ~", 5 to 9 to "x ~ x ~ x ~ x ~ x", 5 to 11 to "x ~ x ~ x ~ x ~ x ~ ~",
            5 to 12 to "x ~ ~ x ~ x ~ ~ x ~ x ~", 5 to 16 to "x ~ ~ x ~ ~ x ~ ~ x ~ ~ x ~ ~ ~", 7 to 8 to "x x x x x x x ~",
            7 to 12 to "x ~ x x ~ x ~ x x ~ x ~", 7 to 16 to "x ~ ~ x ~ x ~ x ~ ~ x ~ x ~ x ~", 9 to 16 to "x ~ x x ~ x ~ x ~ x x ~ x ~ x ~",
            11 to 24 to "x ~ ~ x ~ x ~ x ~ x ~ x ~ ~ x ~ x ~ x ~ x ~ x ~", 13 to 24 to "x ~ x x ~ x ~ x ~ x ~ x ~ x x ~ x ~ x ~ x ~ x ~",
        )
        for ((spec, target) in cases) assertEquals(minS(target), minS("x(${spec.first},${spec.second})"), "x(${spec.first},${spec.second})")
    }

    @Test
    fun reproducesToussaintsExampleEuclideanLegatoAlgorithms() {
        assumeTrue(StrudelRegistry.euclid)
        val cases = listOf(
            1 to 2 to "x _", 1 to 3 to "x _ _", 1 to 4 to "x _ _ _", 4 to 12 to "x _ _ x _ _ x _ _ x _ _", 2 to 5 to "x _ x _ _",
            3 to 4 to "x x x _", 3 to 5 to "x _ x _ x", 3 to 7 to "x _ x _ x _ _", 3 to 8 to "x _ _ x _ _ x _", 4 to 7 to "x _ x _ x _ x",
            4 to 9 to "x _ x _ x _ x _ _", 4 to 11 to "x _ _ x _ _ x _ _ x _", 5 to 6 to "x x x x x _", 5 to 7 to "x _ x x _ x x",
            5 to 8 to "x _ x x _ x x _", 5 to 9 to "x _ x _ x _ x _ x", 5 to 11 to "x _ x _ x _ x _ x _ _",
            5 to 12 to "x _ _ x _ x _ _ x _ x _", 5 to 16 to "x _ _ x _ _ x _ _ x _ _ x _ _ _", 7 to 8 to "x x x x x x x _",
            7 to 12 to "x _ x x _ x _ x x _ x _", 7 to 16 to "x _ _ x _ x _ x _ _ x _ x _ x _", 9 to 16 to "x _ x x _ x _ x _ x x _ x _ x _",
            11 to 24 to "x _ _ x _ x _ x _ x _ x _ _ x _ x _ x _ x _ x _", 13 to 24 to "x _ x x _ x _ x _ x _ x _ x x _ x _ x _ x _ x _",
        )
        for ((spec, target) in cases) assertEquals(minS(target), minS("x(${spec.first},-${spec.second})"), "x(${spec.first},-${spec.second})")
    }

    @Test
    fun supportsTheMinusAliasForTilde() {
        assertEquals(minS("a ~ b [~ c]"), minS("a - b [- c]"))
    }

    @Test
    fun supportsTheQuestionMarkOperator() {
        assumeTrue(StrudelRegistry.degradeBy)
        val wholes = { p: Pattern -> p.queryArc(Rational.ZERO, Rational(20)).map { it.whole!!.begin } }
        assertEquals(wholes(mini("a").callMethod("degradeBy", 0.5)), wholes(mini("a?")))
    }

    // statistical tests: they work with the random stream of Strudel and are meant to succeed with p > 0.99 for any stream

    @Test
    fun supportsDegradeByWithDefaultOf50Percent() {
        val haps = mini("a?").queryArc(Rational.ZERO, Rational(1000))
        assertTrue(haps.size in 459..541, "${haps.size} of 1000 cycles kept their event")
    }

    @Test
    fun supportsDegradeByWithAnArgument() {
        val haps = mini("a?0.8").queryArc(Rational.ZERO, Rational(1000))
        assertTrue(haps.isNotEmpty(), "at least one event should have been kept at 0.8")
        assertTrue(haps.size < 230, "${haps.size} events kept at 0.8")
    }

    @Test
    fun supportsMultipleIndependentUsesOfTheRandomChoiceOperator() {
        val values = mini("[a|b] [a|b]").queryArc(Rational.ZERO, Rational(1000)).map { it.value }
        val observed = HashMap<String, Int>()
        for (i in values.indices step 2) observed.merge(values[i].toString() + values[i + 1], 1, Int::plus)
        for (key in listOf("aa", "ab", "ba", "bb")) {
            assertTrue((observed[key] ?: 0) in 215..286, "$key came up ${observed[key]} times of 1000")
        }
    }

    @Test
    fun supportsTheRandomChoiceOperatorWithNesting() {
        val cycles = 900
        val haps = mini("a | [b | c] | [d | e | f]").queryArc(Rational.ZERO, Rational(cycles.toLong()))
        // 1/3 a, 1/6 each of b and c, 1/9 each of d, e and f: a chi-squared test with 5 degrees of freedom at 99%
        val observed = haps.groupingBy { it.value.toString() }.eachCount()
        val expected = mapOf("a" to cycles / 3.0, "b" to cycles / 6.0, "c" to cycles / 6.0, "d" to cycles / 9.0, "e" to cycles / 9.0, "f" to cycles / 9.0)
        var chi = -cycles.toDouble()
        for ((k, e) in expected) chi += (observed[k] ?: 0).let { it * it } / e
        assertTrue(chi <= 15.086, "chi-squared $chi")
    }

    @Test
    fun supportsLists() {
        assertEquals(
            listOf<Any?>(listOf("a", "b"), listOf("c", "d", listOf("e", "f")), "g"),
            minV("a:b c:d:[e:f] g"),
        )
    }

    @Test
    fun supportsRanges() {
        assertEquals(listOf<Any?>(0.0, 1.0, 2.0, 3.0, 4.0), minV("0 .. 4"))
    }

    @Test
    fun supportsPatternedRanges() {
        assertEquals(minS("[0 1 2] [1 2 3 4]"), minS("[<0 1> .. <2 4>]*2"))
    }

    @Test
    fun supportsTheDotOperator() {
        assertEquals(minS("a [b c]"), minS("a . b c"))
        assertEquals(minS("a [b c] [[d e f] [g h]]"), minS("a . b c . [d e f . g h]"))
    }

    @Test
    fun supportsTheUnderscoreOperator() {
        assertEquals(minS("a@2 b@3"), minS("a _ b _ _"))
    }

    @Test
    fun underscoreAndAtAreAlmostInterchangeable() {
        assertEquals(minS("a _2 b _3"), minS("a @ b @ @"))
    }

    @Test
    fun supportsCaretStepMarking() {
        val steps = mapOf(
            "a [^b c]" to 4L, "[^b c]!3" to 6L, "[a b c] [d [e f]]" to 2L, "^[a b c] [d [e f]]" to 2L, "[a b c] [d [^e f]]" to 8L,
            "[a b c] [^d [e f]]" to 4L, "[^a b c] [^d [e f]]" to 12L, "[^a b c] [d [^e f]]" to 24L, "[^a b c d e]" to 5L,
        )
        for ((src, expected) in steps) assertEquals(Rational(expected), mini(src).steps, src)
    }

    // ---- getLeafLocation(s) -----------------------------------------------------------------------------------------

    @Test
    fun getsTheLocationOfLeafNodes() {
        val code = "\"bd sd\""
        val ast = mini2ast(code)
        val bd = (ast.source[0] as ElementNode).source as AtomNode
        val sd = (ast.source[1] as ElementNode).source as AtomNode
        assertEquals(listOf(1, 3), getLeafLocation(code, bd).toList())
        assertEquals(listOf(4, 6), getLeafLocation(code, sd).toList())
    }

    @Test
    fun getsTheLocationsOfLeafNodes() {
        fun leaves(code: String) = getLeafLocations(code, 0).map { it.toList() }
        assertEquals(listOf(listOf(1, 3), listOf(4, 6)), leaves("\"bd sd\""))
        assertEquals(listOf(listOf(1, 3), listOf(7, 9), listOf(10, 12), listOf(4, 5)), leaves("\"bd*2 [sd cp]\""))
        assertEquals(listOf(listOf(1, 3), listOf(5, 6), listOf(7, 8)), leaves("\"bd*<2 3>\""))
    }

    @Test
    fun theLocationsOfAtomsAreShiftedByTheStartOfTheString() {
        assertEquals(listOf(listOf(11, 13), listOf(14, 16), listOf(17, 18)), getLeafLocations("\"bd sd*2\"", 10).map { it.toList() })
    }

    @Test
    fun onlySpacesAreTakenOffTheLocationOfAnAtom() {
        // a newline is part of the range of the atom before it, and spaces after the newline are not
        assertEquals(listOf(listOf(1, 3), listOf(4, 5)), getLeafLocations("\"a\n b\"", 0).map { it.toList() })
        assertEquals(listOf(listOf(1, 2), listOf(4, 5)), getLeafLocations("\"a  b\"", 0).map { it.toList() })
    }

    @Test
    fun theHapsOfAnAtomCarryItsLocation() {
        val haps = m("bd sd", 4).queryArc(Rational.ZERO, Rational.ONE)
        assertEquals(listOf(listOf(SourceRange(5, 7)), listOf(SourceRange(8, 10))), haps.map { it.context.locations })
    }

    @Test
    fun anArgumentThatIsAPatternAddsItsLocationToTheHapsOfTheOperator() {
        // `bd*2`: the `2` is read as an argument of `fast`, and `fast` adds where it was written to the haps
        val locations = m("bd*2", 0).queryArc(Rational.ZERO, Rational.ONE).map { h -> h.context.locations.toSet() }
        assertEquals(listOf(setOf(SourceRange(1, 3), SourceRange(4, 5)), setOf(SourceRange(1, 3), SourceRange(4, 5))), locations)
    }

    @Test
    fun theZeroOfAnEuclideanRotationLeavesNoLocation() {
        assumeTrue(StrudelRegistry.euclid)
        fun locations(src: String) = m(src, 0).queryArc(Rational.ZERO, Rational.ONE).first().context.locations.toSet()
        // the atom 0 is falsy, and a falsy constant argument does not remember where it was written
        assertEquals(setOf(SourceRange(1, 3), SourceRange(4, 5), SourceRange(6, 7)), locations("bd(3,8,0)"))
        assertEquals(setOf(SourceRange(1, 3), SourceRange(4, 5), SourceRange(6, 7), SourceRange(8, 9)), locations("bd(3,8,2)"))
    }

    @Test
    fun theLocationsOfEuclideanArgumentsIgnoreTheirOperators() {
        val ast = mini2ast("\"bd(3*2,8)\"")
        val leaves = collectLeaves(ast).map { it.source }
        assertEquals(listOf("bd", "3", "8"), leaves)
    }

    @Test
    fun theLeavesAreInTheOrderThePatternsAreMadeIn() {
        val sources = listOf(
            "a*2 [b c]", "a*<2 3> b", "a(3,8,2) b(<3 5>,8)", "{a b c}%4 d", "{a b, c d e}%<2 3>", "a:b c:[d e]", "0 .. 3 a .. [b c]",
            "<a b>!2 c? d@3", "a | b | c", "a . b c . d", "[a, b c]*2", "a(<3 5>,[8 16]) b:3", "~ - a",
        )
        for (src in sources) {
            val code = "\"$src\""
            val ast = mini2ast(code)
            val entered = ArrayList<AtomNode>()
            patternifyAST(ast, code, { if (it is AtomNode) entered.add(it) }, -1)
            assertEquals(entered.map { it.start }, collectLeaves(ast).map { it.start }, src)
        }
    }

    // ---- the grammar ------------------------------------------------------------------------------------------------

    private fun degradeSeeds(src: String): Map<String, Int> {
        val seeds = LinkedHashMap<String, Int>()
        fun visit(node: MiniNode) {
            when (node) {
                is AtomNode -> Unit
                is ElementNode -> {
                    for (op in node.ops) if (op is Op.DegradeBy) seeds[(node.source as? AtomNode)?.source ?: "?"] = op.seed
                    visit(node.source)
                }
                is PatternNode -> {
                    node.source.forEach(::visit)
                    if (node.seed != null) seeds[node.alignment.name.lowercase()] = node.seed
                }
            }
        }
        visit(mini2ast("\"$src\""))
        return seeds
    }

    @Test
    fun theRandomStreamsOfQuestionMarksAreNumberedInTheOrderTheyAreRead() {
        assertEquals(mapOf("a" to 0, "b" to 1, "c" to 2), degradeSeeds("a? b? c?"))
    }

    @Test
    fun aChoiceTakesItsNumberAfterTheStreamsInsideIt() {
        assertEquals(mapOf("a" to 0, "b" to 1, "rand" to 2), degradeSeeds("a? | b?"))
    }

    @Test
    fun everyChoiceInAStringHasItsOwnStream() {
        val choices = ArrayList<Int>()
        fun visit(node: MiniNode) {
            if (node is PatternNode) {
                node.source.forEach(::visit)
                if (node.alignment == Alignment.RAND) choices.add(node.seed!!)
            } else if (node is ElementNode) {
                visit(node.source)
            }
        }
        visit(mini2ast("\"[a|b] [c|d]\""))
        assertEquals(listOf(0, 1), choices)
    }

    @Test
    fun feetTakeASeedThatTheyDoNotUse() {
        assertEquals(mapOf("b" to 0, "c" to 1, "feet" to 2), degradeSeeds("a . b? . c?"))
    }

    @Test
    fun theStreamsRestartForEachString() {
        assertEquals(degradeSeeds("a? b?"), degradeSeeds("a? b?"))
        assertEquals(mapOf("a" to 0), degradeSeeds("a?"))
    }

    @Test
    fun aWeightInsideAnAlternationLastsThatManyCycles() {
        val p = mini("<a@3 b>")
        assertEquals(listOf<Any?>("a", "a", "a", "b", "a"), (0L..4L).map { c -> p.queryArc(Rational(c), Rational(c + 1)).single().value })
    }

    @Test
    fun weightsAndRepetitionsCombineInTheOrderTheyAreWritten() {
        // a@3!2: the weight is the number of copies (2), a!2@3: the copies and then the weight (2 + 3 - 1)
        assertEquals(listOf("a: 0 - 1/3", "a: 1/3 - 2/3", "b: 2/3 - 1"), minS("a@3!2 b"))
        assertEquals(listOf("a: 0 - 2/5", "a: 2/5 - 4/5", "b: 4/5 - 1"), minS("a!2@3 b"))
    }

    @Test
    fun aWeightOfZeroCountsAsOne() {
        assertEquals(minS("a b"), minS("a@0 b"))
        assertEquals(minS("a"), minS("a@0"))
    }

    @Test
    fun anUnderscoreWithANumberIsAWeight() {
        assertEquals(minS("bd@3 sd"), minS("bd _3 sd"))
    }

    @Test
    fun zeroCopiesLeaveASilentStep() {
        assertEquals(listOf("b: 1/2 - 1"), minS("a!0 b"))
    }

    @Test
    fun aRangeNeedsSpacesAroundItsDots() {
        assertEquals(listOf<Any?>(0.0, 1.0, 2.0, 3.0), minV("0 .. 3"))
        assertEquals(listOf<Any?>(0.0, 1.0, 2.0, 3.0), minV("0 ..3"))
        assertEquals(listOf<Any?>("0..3"), minV("0..3"))
    }

    @Test
    fun rangesCountDownAndAcceptFractions() {
        assertEquals(listOf<Any?>(3.0, 2.0, 1.0, 0.0), minV("3 .. 0"))
        assertEquals(listOf<Any?>(0.0, 1.0, 2.0), minV("0 .. 2.5"))
        assertEquals(listOf<Any?>(0.5, 1.5, 2.5), minV("0.5 .. 3"))
        assertEquals(listOf<Any?>(2.0), minV("2 .. 2"))
    }

    @Test
    fun aRangeOfWordsIsEmpty() {
        assertEquals(emptyList(), minV("a .. c"))
    }

    @Test
    fun aRangeThatIsTooLongIsRefused() {
        assertEquals(TOO_DENSE, assertFailsWith<CosicError> { mini("0 .. 100000").firstValues() }.message)
        assertEquals("Invalid array length", assertFailsWith<CosicError> { mini("0 .. 1e300").firstValues() }.message)
    }

    @Test
    fun theTailOfAnAtomMakesAList() {
        assertEquals(listOf<Any?>(listOf("a", 3.0)), minV("a:3"))
        assertEquals(listOf<Any?>(listOf("a", 3.0, "b")), minV("a:3:b"))
        assertEquals(listOf<Any?>(listOf("a", "b")), minV("a:<b c>"))
    }

    @Test
    fun numbersAreReadTheWayJavaScriptReadsThem() {
        assertEquals(listOf<Any?>(16.0), minV("0x10"))
        assertEquals(listOf<Any?>(1000.0), minV("1e3"))
        assertEquals(listOf<Any?>(3000.0), minV("3e3"))
        assertEquals(listOf<Any?>(Double.POSITIVE_INFINITY), minV("Infinity"))
        assertEquals(listOf<Any?>(-1.0), minV("-1"))
        assertEquals(listOf<Any?>(7.0), minV("007"))
        assertEquals(listOf<Any?>("e3"), minV("e3"))
        assertEquals(listOf<Any?>("1_000"), minV("1_000"))
        assertEquals(listOf<Any?>("~~"), minV("~~"))
    }

    @Test
    fun wordsMayHoldAnyLetter() {
        assertEquals(listOf<Any?>("é", "日本語", "Ω", "c#3", "a.b", "a_b", "a^b"), minV("é 日本語 Ω c#3 a.b a_b a^b"))
    }

    @Test
    fun anAtomThatIsASingleDotOrUnderscoreIsNotAnAtom() {
        // a `.` needs a sequence after it, and a `_` before it is a weight that has nothing to weigh
        assertTrue(assertFailsWith<CosicError> { mini("a .") }.message!!.startsWith("[mini] parse error at line 1: Expected \"<\", "))
        assertEquals("[mini] parse error at line 1: Expected whitespace but \"a\" found.", assertFailsWith<CosicError> { mini("_ a") }.message)
        assertEquals(listOf<Any?>("a"), minV("a _"))
    }

    @Test
    fun euclidArgumentsIgnoreTheirOwnOperators() {
        assumeTrue(StrudelRegistry.euclid)
        assertEquals(minS("bd(3,8)"), minS("bd(3*2,8)"))
    }

    @Test
    fun theSecondCommaOfAnEuclideanRhythmIsOptional() {
        assumeTrue(StrudelRegistry.euclid)
        assertEquals(minS("bd(3,8,2)"), minS("bd(3,8 2)"))
    }

    @Test
    fun aQuoteInsideTheNotationEndsItAndThatIsAnError() {
        assertEquals(
            "[mini] parse error at line 1: Expected \"//\", end of input, or whitespace but \"m\" found.",
            assertFailsWith<CosicError> { mini("c'major") }.message,
        )
    }

    @Test
    fun aQuoteThatEndsTheNotationLeavesACommentAfterIt() {
        // `"a" // x"` is the notation `a` and a comment: what Strudel's grammar reads
        assertEquals(listOf<Any?>("a"), minV("a\" // x"))
    }

    @Test
    fun anErrorSaysWhatWasExpectedWhereItWentWrong() {
        assertEquals(
            "[mini] parse error at line 1: Expected \"<\", \"[\", \"^\", \"{\", a letter, a number, \"-\", \"#\", \".\", \"^\", \"_\", or whitespace but \"\\\"\" found.",
            assertFailsWith<CosicError> { mini("") }.message,
        )
        assertEquals(
            "[mini] parse error at line 1: Expected \"!\", \"(\", \"*\", \",\", \".\", \"..\", \"/\", \":\", \"<\", \"?\", \"[\", \"{\", \"|\", [\"'], [@_], a letter, a number, \"-\", \"#\", \".\", \"^\", \"_\", or whitespace but \"]\" found.",
            assertFailsWith<CosicError> { mini("a b]") }.message,
        )
    }

    @Test
    fun anErrorCountsTheLinesOfTheNotation() {
        val message = assertFailsWith<CosicError> { mini("a\nb\n]") }.message!!
        assertTrue(message.startsWith("[mini] parse error at line 3: "), message)
    }

    @Test
    fun anErrorPointsAtTheCharacterThatWentWrong() {
        // `m('a b ]', 20)` is the string "a b ]" that starts at 20: its `]` is at 20 + 1 + 4
        val error = assertFailsWith<CosicError> { m("a b ]", 20) }
        assertEquals(SourceRange(25, 26), error.range)
        // a notation that ends too early fails at its closing quote
        assertEquals(SourceRange(26, 27), assertFailsWith<CosicError> { m("a b (", 20) }.range)
    }

    @Test
    fun theErrorOfRuntimeMiniHasNoRange() {
        assertEquals(null, assertFailsWith<CosicError> { mini("a b ]") }.range)
    }

    @Test
    fun theLineOfAnErrorCanBeCountedInTheProgram() {
        val program = "n(1)\ns(\"a ]\")"
        val start = program.indexOf('"')
        val message = assertFailsWith<CosicError> { getLeafLocations("\"a ]\"", start, program) }.message!!
        assertTrue(message.startsWith("[mini] parse error at line 2: "), message)
    }

    @Test
    fun nestingTooDeepIsAnError() {
        val deep = "[".repeat(300) + "a" + "]".repeat(300)
        assertEquals(TOO_DEEP, assertFailsWith<CosicError> { mini(deep) }.message)
        val ok = "[".repeat(50) + "a" + "]".repeat(50)
        assertEquals(listOf<Any?>("a"), minV(ok))
    }

    @Test
    fun aPolymeterTakesTheStepsOfItsFirstSequence() {
        assertEquals(minS("a b c a"), minS("{a b c}%4"))
        assertEquals(minS("a b c"), minS("{a b c}"))
        assertEquals(minS("[a b, c d]"), minS("{a b, c d e}"))
    }

    @Test
    fun aSequenceOfFeetIsASequenceOfSequences() {
        assertEquals(minS("[a b] [c d]"), minS("a b . c d"))
    }

    @Test
    fun theRestsOfASequenceTakeUpTheirTime() {
        assertEquals(listOf("a: 0 - 1/3", "b: 2/3 - 1"), minS("a ~ b"))
    }

    @Test
    fun aStringOfSpacesAndTabsIsReadAsASequence() {
        assertEquals(minS("a b c"), minS("  a\tb\n c "))
    }

    @Test
    fun theStepsOfASequenceAreTheSumOfTheWeights() {
        assertEquals(Rational(5), mini("a _ b _ _").steps)
        assertEquals(Rational(3), mini("a b c").steps)
        assertEquals(Rational.ONE, mini("a").steps)
    }

    @Test
    fun aNotationIsNotAConstantPatternEvenWhenItIsOneAtom() {
        // the steps of a sequence are set after the atoms were read, which makes a new pattern: `fast("3")` joins, in Strudel too
        assertEquals(null, mini("3").pureValue)
        assertEquals(null, m("3", 0).pureValue)
    }
}
