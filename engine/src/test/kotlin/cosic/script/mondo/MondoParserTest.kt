package cosic.script.mondo

import cosic.pattern.CosicError
import cosic.pattern.jsonStringify
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/** `mondo.test.mjs` for the tokenizer, the parser and the desugaring, plus a comparison with the real parser over a corpus. */
class MondoParserTest {
    private val parser = MondoParser()

    private fun p(code: String): MondoNode = parser.parse(code, -1)

    /** `printAst(parse(code), true)`. */
    private fun desugar(code: String): String = printAst(parser.parse(code, 0), true)

    // ---- the tokenizer ----------------------------------------------------------------------------------------------

    @Test
    fun tokensCarryTheirLocations() {
        assertEquals(
            "(=0-1 one=1-4 two=5-8 three=9-14 )=14-15",
            parser.tokenize("(one two three)").joinToString(" ") { it.value + "=" + it.loc!!.joinToString("-") },
        )
    }

    @Test
    fun locationsAreThoseOfTheAtoms() {
        assertEquals(listOf(listOf(0, 1), listOf(2, 4), listOf(5, 8)), parser.getLocations("s bd rim").map { it.toList() })
    }

    @Test
    fun locationsCountFromTheOffsetAndSkipWhitespace() {
        assertEquals(listOf(listOf(13, 14), listOf(16, 18)), parser.getLocations("  s  bd ", 11).map { it.toList() })
        assertEquals(emptyList(), parser.tokenize("a b", -1).mapNotNull { it.loc })
    }

    @Test
    fun textThatNoTokenMatchesIsAnError() {
        val e = assertFailsWith<CosicError> { parser.tokenize("s bd \"oops", 10) }
        assertEquals("mondo: could not match '\"oops'", e.message)
        assertEquals(cosic.SourceRange(15, 16), e.range)
        assertEquals(null, assertFailsWith<CosicError> { parser.tokenize("`x`", -1) }.range)
    }

    // ---- the s-expressions --------------------------------------------------------------------------------------------

    private fun assertNode(expected: String, node: MondoNode) = assertEquals(expected, shape(node))

    /** The tree as `{type value children}` text, with no positions. */
    private fun shape(node: MondoNode): String =
        if (node.type == "list") "{list [${node.children.joinToString(" ") { shape(it) }}]}" else "{${node.type} ${node.value}}"

    @Test
    fun anEmptyStringIsAnEmptyList() = assertNode("{list []}", p(""))

    @Test
    fun aSingleItemIsAListOfOne() = assertNode("{list [{plain a}]}", p("a"))

    @Test
    fun anEmptyListStaysEmpty() = assertNode("{list []}", p("()"))

    @Test
    fun aListWithOneItem() = assertNode("{list [{plain a}]}", p("(a)"))

    @Test
    fun aListWithTwoItems() = assertNode("{list [{plain a} {plain b}]}", p("(a b)"))

    @Test
    fun listsNest() = assertNode("{list [{plain a} {list [{plain b} {plain c}]}]}", p("(a (b c))"))

    @Test
    fun numbersKeepTheirText() =
        assertNode("{list [{number 1} {number .2} {number 1.2} {number 10} {number 22.3}]}", p("(1 .2 1.2 10 22.3)"))

    @Test
    fun commentsAreLeaves() = assertNode("{list [{plain a} {comment // hello}]}", p("a // hello"))

    @Test
    fun aListKnowsWhereItIs() {
        val list = parser.parse("  (a b)", 4)
        assertEquals(listOf(6, 11), list.loc!!.toList())
        assertEquals(null, p("(a b)").loc)
    }

    // ---- the sugar ------------------------------------------------------------------------------------------------------

    @Test
    fun squareAngleAndCurlyBracketsAreCallsOfTheirName() {
        assertEquals("(square a b c)", desugar("[a b c]"))
        assertEquals("(square a (square b c) d)", desugar("[a [b c] d]"))
        assertEquals("(angle a b c)", desugar("<a b c>"))
        assertEquals("(angle a (angle b c) d)", desugar("<a <b c> d>"))
        assertEquals("(square a (angle b c))", desugar("[a <b c>]"))
        assertEquals("(angle a (square b c))", desugar("<a [b c]>"))
        assertEquals("(curly a b c)", desugar("{a b c}"))
    }

    @Test
    fun aPipeMakesWhatIsBeforeItTheLastArgumentOfWhatFollows() {
        assertEquals("(fast 2 (s jazz))", desugar("s jazz # fast 2"))
        assertEquals("(fast 2 (square bd cp))", desugar("[bd cp # fast 2]"))
        assertEquals("(slow 2 (fast 2 (s jazz)))", desugar("s jazz # fast 2 # slow 2"))
        assertEquals("(fast 2 (s cp))", desugar("(s cp # fast 2)"))
        assertEquals("(stack (fast 2 (square bd cp)) x)", desugar("[bd cp # fast 2, x]"))
    }

    @Test
    fun commasAndBarsStackAndChoose() {
        assertEquals("(stack bd (or hh oh))", desugar("[bd, hh | oh]"))
        assertEquals("(stack bd (or hh (square oh rim)))", desugar("[bd, hh | [oh rim]]"))
        assertEquals("(stack bd hh)", desugar("[bd, hh]"))
        assertEquals("(stack bd (square hh oh))", desugar("[bd, hh oh]"))
        assertEquals("(stack (square bd cp) (square hh oh))", desugar("[bd cp, hh oh]"))
        assertEquals("(stack bd hh)", desugar("<bd, hh>"))
        assertEquals("(stack bd (angle hh oh))", desugar("<bd, hh oh>"))
        assertEquals("(stack (angle bd cp) (angle hh oh))", desugar("<bd cp, hh oh>"))
        assertEquals("(stack (s bd) (s cp))", desugar("(s bd, s cp)"))
    }

    @Test
    fun infixOperatorsBecomeCallsWithTheOperandsReversed() {
        assertEquals("(square a (* 2 b) c (/ 3 d) e)", desugar("[a b*2 c d/3 e]"))
        assertEquals("(square a (* 3 (square b c)))", desugar("[a [b c]*3]"))
        assertEquals("(square a (* (angle 2 3) b) c)", desugar("[a b*<2 3> c]"))
        assertEquals("(: y x)", desugar("x:y"))
        assertEquals("(: z (: y x))", desugar("x:y:z"))
        assertEquals("(* 2 (: 0 bd))", desugar("bd:0*2"))
        assertEquals("(& (: 8 3) bd)", desugar("bd&3:8"))
        assertEquals("(.. 2 0)", desugar("0..2"))
    }

    @Test
    fun theReadmeExample() {
        assertEquals(
            "(speed .8 (s (square bd (* 2 hh) (crush 4 cp) (angle mt ht lt))))",
            desugar("s [bd hh*2 (cp # crush 4) <mt ht lt>] # speed .8"),
        )
    }

    @Test
    fun aPipeWithNothingBeforeItMakesAFunction() {
        assertEquals("(fn (_) _)", desugar("(#)"))
        assertEquals("(fn (_) (fast 2 _))", desugar("(# fast 2)"))
        assertEquals("((fn (_) (mul 2 _)) 2)", desugar("((# mul 2) 2)"))
        assertEquals("(fn (_) (room 1 (fast 2 _)))", desugar("(# fast 2 # room 1)"))
    }

    @Test
    fun anOperatorWithNothingToOperateOnIsAName() {
        assertEquals("(a *)", desugar("a *"))
        assertEquals("(* a)", desugar("* a"))
        assertEquals("(* 2 x)", desugar("x # * 2"))
        assertEquals("(! 2 x)", desugar("x # ! 2"))
        assertEquals("(square c -)", desugar("[c -]"))
        assertEquals("(square a !)", desugar("[a !]"))
    }

    @Test
    fun twoOperatorsInARowAreAnError() {
        assertEquals(
            "got 2 ops in a row: \"-*\". you probably want a rest, which is \"_\" in mondo!",
            assertFailsWith<CosicError> { desugar("[a - *b]") }.message,
        )
        assertEquals("got 2 ops in a row: \"*-\"", assertFailsWith<CosicError> { desugar("a * - b") }.message)
        assertEquals("got 2 ops in a row: \"!*\"", assertFailsWith<CosicError> { desugar("a !* 2") }.message)
        assertEquals(
            "got 2 ops in a row: \"--\". you probably want a rest, which is \"_\" in mondo!",
            assertFailsWith<CosicError> { desugar("s [bd - - bd]") }.message,
        )
    }

    @Test
    fun aQuestionMarkTakesItsProbabilityFromTheRight() {
        assertEquals("(a ?)", desugar("a?"))
        assertEquals("(? .5 a)", desugar("a?.5"))
    }

    @Test
    fun theStepCountOfCurlyBracketsIsTheArgumentOfAPercentSign() {
        assertEquals("(% 4 (curly a b c))", desugar("{a b c}%4"))
        assertEquals("(s (: 3 bd))", desugar("s bd:3"))
    }

    @Test
    fun anUnfinishedProgramIsAnError() {
        assertEquals("unexpected end of file", assertFailsWith<CosicError> { p("[a b") }.message)
        assertEquals("unexpected end of file", assertFailsWith<CosicError> { p("(a (b c)") }.message)
    }

    @Test
    fun theAstPrintsLikeALisp() {
        val ast = p("(+ (* 3 (+ (* 2 4) (+ 3 5))) (+ (- 10 7) 6))")
        assertEquals(
            "(+ \n (* 3 \n  (+ \n   (* 2 4) \n   (+ 3 5)\n  )\n ) \n (+ \n  (- 10 7) 6\n )\n)",
            printAst(ast, false),
        )
    }

    // ---- the real parser ----------------------------------------------------------------------------------------------------

    private fun position(n: MondoNode): String = n.loc?.let { "@" + it.joinToString("-") } ?: ""

    /** Every node with its type and position, as `tools/strudel-ref/mondo-oracle.mjs` prints it. */
    private fun typed(n: MondoNode): String =
        if (n.type == "list") "(${n.children.joinToString(" ") { typed(it) }})${position(n)}"
        else "${n.type}:${jsonStringify(n.value)}${position(n)}"

    /** What [parser] makes of [code] at [offset], in the fields of the oracle's entries. */
    private fun cosicResult(code: String, offset: Int, full: Boolean): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        if (full) {
            try {
                out["tokens"] = MondoParser().tokenize(code, offset).map { "${it.type}:${jsonStringify(it.value)}${position(it)}" }
            } catch (e: CosicError) {
                out["tokensError"] = e.message
            }
        }
        try {
            val ast = MondoParser().parse(code, offset)
            out["ast"] = typed(ast)
            if (full) {
                out["compact"] = printAst(ast, true)
                out["pretty"] = printAst(ast, false)
            }
        } catch (e: CosicError) {
            out["error"] = e.message
        }
        if (full) {
            try {
                out["locations"] = MondoParser().getLocations(code, offset).map { it.toList() }
            } catch (e: CosicError) {
                out["locationsError"] = e.message
            }
        }
        return out
    }

    private fun oracle(): JsonArray =
        Json.parseToJsonElement(MondoParserTest::class.java.getResource("/mondo/parser-oracle.json")!!.readText()).jsonArray

    private fun expected(entry: JsonObject, key: String): Any? {
        val value = entry[key] ?: return null
        return when (key) {
            "tokens" -> value.jsonArray.map { it.jsonPrimitive.content }
            "locations" -> value.jsonArray.map { pair -> pair.jsonArray.map { it.jsonPrimitive.int } }
            else -> value.jsonPrimitive.content
        }
    }

    @Test
    fun theParserMakesWhatTheRealOneMakesOfEveryProgramOfTheCorpus() {
        val mismatches = ArrayList<String>()
        var checked = 0
        for (element in oracle()) {
            val entry = element.jsonObject
            val code = entry.getValue("code").jsonPrimitive.content
            val offset = entry.getValue("offset").jsonPrimitive.int
            val actual = cosicResult(code, offset, full = entry.containsKey("tokens") || entry.containsKey("tokensError"))
            for (key in listOf("tokens", "tokensError", "ast", "compact", "pretty", "error", "locations", "locationsError")) {
                val want = expected(entry, key)
                if (want != actual[key]) {
                    mismatches.add("${jsonStringify(code)} at $offset, $key\n    expected: $want\n    actual:   ${actual[key]}")
                }
            }
            checked++
        }
        assertTrue(checked > 1500, "the corpus has $checked programs")
        if (mismatches.isNotEmpty()) {
            fail("${mismatches.size} differences from the real parser in $checked programs:\n" + mismatches.take(8).joinToString("\n"))
        }
    }
}
