package cosic.script.kabel

import cosic.parity.ParityWorld
import cosic.pattern.CosicError
import cosic.pattern.Rational
import cosic.script.CallExpression
import cosic.script.ExpressionStatement
import cosic.script.Identifier
import cosic.script.Literal
import cosic.script.MemberExpression
import cosic.script.MiniLeafLocator
import cosic.script.PatternValue
import cosic.script.TranspileOptions
import cosic.script.catalogue.Catalogue
import cosic.script.parseProgram
import cosic.script.printExpression
import cosic.script.transpile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `K(...)` and `worklet` (plugin-kabelsalat.mjs, pattern.mjs:4487-4498). The printer is checked against escodegen 2.1.0
 * itself (resources/kabel/printer-vectors.json, made by tools/strudel-ref/kabel-printer-vectors.mjs); the rewrite against
 * the vectors of the task description and the two `example "K"` snapshots (the parity suite runs those).
 */
class KabelTest {
    /** Stands in for the mini package: the atoms of a notation without any syntax are its words. */
    private val words = MiniLeafLocator { quoted, start ->
        Regex("[^\\s\"]+").findAll(quoted).map { intArrayOf(start + it.range.first, start + it.range.last + 1) }.toList()
    }

    @BeforeTest
    fun installCatalogue() {
        Catalogue.installAll()
    }

    // ---- the printer -----------------------------------------------------------------------------------------------

    @Test
    fun printerMatchesEscodegenOnEveryVector() {
        val text = checkNotNull(javaClass.getResourceAsStream("/kabel/printer-vectors.json")) { "printer-vectors.json is missing" }
            .bufferedReader().readText()
        val vectors = Json.parseToJsonElement(text) as JsonArray
        assertTrue(vectors.size > 250, "the vector file looks truncated: ${vectors.size}")
        val failures = ArrayList<String>()
        for (v in vectors) {
            val o = v as JsonObject
            val code = o.getValue("code").jsonPrimitive.content
            val expected = o.getValue("expected").jsonPrimitive.content
            val actual = try {
                val statement = parseProgram(code).program.body.single() as ExpressionStatement
                printJs(statement.expression)
            } catch (e: Exception) {
                "<${e::class.simpleName}: ${e.message}>"
            }
            if (actual != expected) failures.add("${show(code)}\n    expected ${show(expected)}\n    actual   ${show(actual)}")
        }
        assertTrue(failures.isEmpty(), "${failures.size} of ${vectors.size} differ:\n" + failures.take(25).joinToString("\n"))
    }

    private fun show(s: String) = s.replace("\n", "\\n")

    @Test
    fun numbersPrintTheirValueNotTheirSource() {
        assertEquals("0.13", print(".13"))
        assertEquals("1000", print("1_000"))
        assertEquals("16", print("0x10"))
        assertEquals("1e+21", print("1e21"))
    }

    @Test
    fun arrowCalledOnTheSpotKeepsItsParenthesesAndFourSpaceBlock() {
        assertEquals("(() => {\n    return 1 + 2\n})()", print("(() => { return 1 + 2 })()"))
    }

    private fun print(code: String): String = printJs((parseProgram(code).program.body.single() as ExpressionStatement).expression)

    @Test
    fun anUnsupportedSyntaxIsAnError() {
        val e = assertFailsWith<CosicError> { print("() => { try { f() } catch (e) {} }") }
        assertTrue(e.message.startsWith("K(...): unsupported syntax TryStatement"), e.message)
    }

    // ---- the rewrite -----------------------------------------------------------------------------------------------

    private fun rewritten(code: String): CallExpression {
        val program = transpile(code, TranspileOptions(addReturn = false), emptySet(), words).program
        return (program.body[0] as ExpressionStatement).expression as CallExpression
    }

    private fun template(call: CallExpression): String = (call.arguments[0] as Literal).value as String

    private fun inputs(call: CallExpression): List<String> = call.arguments.drop(1).map { printExpression(it) }

    @Test
    fun aMiniNotationStringBecomesAnInput() {
        val call = rewritten("K(\"bd sd\")")
        assertEquals("worklet", (call.callee as Identifier).name)
        assertEquals("pat[0]", template(call))
        assertEquals(listOf("m('bd sd', 2)"), inputs(call))
    }

    @Test
    fun aPlainExpressionHasNoInputs() {
        val call = rewritten("K(1+2)")
        assertEquals("1 + 2", template(call))
        assertEquals(emptyList(), inputs(call))
    }

    @Test
    fun aFunctionWithABlockIsCalled() {
        val call = rewritten("K(() => { return 1 + 2 })")
        assertEquals("(() => {\n    return 1 + 2\n})()", template(call))
        assertEquals(emptyList(), inputs(call))
    }

    @Test
    fun sWrapsAPatternExpression() {
        val call = rewritten("K(S(\"bd\".fast(4)))")
        assertEquals("pat[0]", template(call))
        assertEquals(listOf("m('bd', 4).fast(4)"), inputs(call))
    }

    @Test
    fun inputsAreNumberedBySourcePositionAndOnlyDoubleQuotedStringsAreInputs() {
        val call = rewritten("K(sine(S(irand(8))).mul(\"1 2\").add('x').add(`3`))")
        assertEquals("sine(pat[0]).mul(pat[1]).add('x').add(pat[2])", template(call))
        assertEquals(3, inputs(call).size)
        assertEquals("irand(8)", inputs(call)[0])
    }

    @Test
    fun theMemberFormKeepsTheReceiver() {
        val call = rewritten("s(\"saw\").K(sine(sFreq).mul(\"1 2\"), 5)")
        val callee = call.callee as MemberExpression
        assertEquals("worklet", (callee.property as Identifier).name)
        assertEquals("s(m('saw', 2))", printExpression(callee.obj))
        assertEquals("sine(sFreq).mul(pat[0])", template(call))
        assertEquals(listOf("m('1 2', 27)", "5"), inputs(call))
    }

    @Test
    fun theTemplateKeepsTheStringsItHasInSingleQuotes() {
        val call = rewritten("K(f('it\\'s', 1.50, .5))")
        assertEquals("f('it\\'s', 1.5, 0.5)", template(call))
    }

    @Test
    fun aKWithoutAnExpressionIsAnError() {
        val e = assertFailsWith<CosicError> { rewritten("K()") }
        assertEquals("K(...) requires an expression", e.message)
        assertFailsWith<CosicError> { rewritten("K(S())") }
    }

    // ---- the two documented examples ---------------------------------------------------------------------------------

    /** The program of `example "K"` number [index] and the text its first snapshot hap shows after `workletSrc:`. */
    private fun documentedExample(index: Int): Pair<String, String> {
        val text = checkNotNull(javaClass.getResourceAsStream("/parity/examples.json")) { "examples.json is missing" }
            .bufferedReader().readText()
        val example = (Json.parseToJsonElement(text) as JsonArray).map { it as JsonObject }
            .single { it.getValue("name").jsonPrimitive.content == "K" && it.getValue("index").jsonPrimitive.content.toInt() == index }
        val code = example.getValue("code").jsonPrimitive.content
        val firstHap = (example.getValue("haps") as JsonArray).first().jsonPrimitive.content
        return code to Regex("workletSrc:(.*) workletInputs:").find(firstHap)!!.groupValues[1]
    }

    /** What `hap.show(true)` makes of a string value inside a control map: JSON-escaped, quotes dropped, commas turned to spaces. */
    private fun shown(template: String) = cosic.pattern.jsonStringify(template)!!.drop(1).dropLast(1).replace("\"", "").replace(",", " ")

    @Test
    fun theTemplatesOfTheDocumentedExamplesAreTheOnesStrudelProduces() {
        val inputCounts = listOf(5, 4)
        for (index in 0..1) {
            val (code, expectedShown) = documentedExample(index)
            var call = rewritten(code)
            // the program goes on after the K: `.fxr(1).room(0.3)`
            while (((call.callee as? MemberExpression)?.property as? Identifier)?.name != "worklet") call = (call.callee as MemberExpression).obj as CallExpression
            assertEquals(expectedShown, shown(template(call)), "example K#$index")
            assertEquals(inputCounts[index], call.arguments.size - 1, "inputs of example K#$index")
        }
    }

    // ---- worklet at run time ---------------------------------------------------------------------------------------

    private fun haps(code: String): List<String> {
        val value = ParityWorld.plain().run(code) as PatternValue
        return value.pattern.sortHapsByPart().queryArc(Rational.ZERO, Rational.ONE).map { it.show(true) }
    }

    @Test
    fun workletStoresTheSourceAndTheValuesOfItsInputs() {
        assertEquals(
            listOf(
                "[ (0/1 → 1/2) ⇝ 1/1 | s:saw workletSrc:sine(sFreq).mul(pat[0]) workletInputs:[1] ]",
                "[ 0/1 ⇜ (1/2 → 1/1) | s:saw workletSrc:sine(sFreq).mul(pat[0]) workletInputs:[2] ]",
            ),
            haps("s(\"saw\").K(sine(sFreq).mul(\"1 2\"))"),
        )
    }

    @Test
    fun theInputsOfAnEarlierWorkletComeFirst() {
        assertEquals(
            listOf("[ 0/1 → 1/1 | s:saw workletSrc:b(pat[0]) workletInputs:[1 2 3] ]"),
            haps("s(\"saw\").worklet('a', 1, 2).worklet('b(pat[0])', 3)"),
        )
    }

    @Test
    fun workletAsAFunctionStartsFromAnEmptyControlMap() {
        assertEquals(
            listOf("[ 0/1 → 1/1 | workletSrc:saw(pat[0]) workletInputs:[440] ]"),
            haps("worklet('saw(pat[0])', 440)"),
        )
    }

    @Test
    fun theStructureComesFromTheReceiverAndTheInputsAreSampledOverItsWholes() {
        assertEquals(
            listOf(
                "[ 0/1 → 1/4 | s:a workletSrc:x workletInputs:[1 5] ]",
                "[ 1/4 → 1/2 | s:b workletSrc:x workletInputs:[1 5] ]",
                "[ 1/2 → 3/4 | s:c workletSrc:x workletInputs:[2 5] ]",
                "[ 3/4 → 1/1 | s:d workletSrc:x workletInputs:[2 5] ]",
            ),
            haps("s(\"a b c d\").worklet('x', \"1 2\", 5)"),
        )
    }

    @Test
    fun sOutsideKIsAnUnknownFunction() {
        assertFailsWith<CosicError> { haps("S(\"bd\")") }
    }
}
