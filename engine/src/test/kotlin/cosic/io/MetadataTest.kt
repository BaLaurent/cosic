package cosic.io

import cosic.EvalReport
import cosic.render.emptyLibrary
import cosic.CosicEngine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The metadata comments of a program: the `it`s of test/metadata.test.mjs of Strudel, one by one, then the same function of Strudel run by
 * `tools/strudel-ref/share-oracle.mjs` over more programs than anyone would write by hand: edge cases of the regular expressions of the
 * original (line breaks of every kind, white space of every kind, comments that are not closed) and every program of the examples and tunes.
 */
class MetadataTest {
    private fun tags(vararg pairs: Pair<String, Any>): Map<String, Any> = linkedMapOf(*pairs)

    private fun parse(code: String) = Metadata.parse(code)

    // ---- test/metadata.test.mjs ---------------------------------------------------------------------------------------------

    @Test
    fun loadsATagFromInlineComment() {
        assertEquals(tags("title" to "Awesome song"), parse("// @title Awesome song"))
    }

    @Test
    fun loadsManyTagsFromInlineComments() {
        assertEquals(tags("title" to "Awesome song", "by" to listOf("Sam")), parse("// @title Awesome song\n// @by Sam"))
    }

    @Test
    fun loadsManyTagsFromOneInlineComment() {
        assertEquals(tags("title" to "Awesome song", "by" to listOf("Sam")), parse("// @title Awesome song @by Sam"))
    }

    @Test
    fun loadsATagFromABlockComment() {
        assertEquals(tags("title" to "Awesome song"), parse("/* @title Awesome song */"))
    }

    @Test
    fun loadsManyTagsFromABlockComment() {
        assertEquals(tags("title" to "Awesome song", "by" to listOf("Sam")), parse("/*\n@title Awesome song\n@by Sam\n*/"))
    }

    @Test
    fun loadsManyTagsFromManyBlockComments() {
        assertEquals(tags("title" to "Awesome song", "by" to listOf("Sam")), parse("/* @title Awesome song */\n/* @by Sam */"))
    }

    @Test
    fun loadsManyTagsFromMixedComments() {
        assertEquals(tags("title" to "Awesome song", "by" to listOf("Sam")), parse("/* @title Awesome song */\n// @by Sam\n"))
    }

    @Test
    fun loadsATitleTagWithQuotesSyntax() {
        assertEquals(tags("title" to "Awesome song"), parse("// \"Awesome song\""))
    }

    @Test
    fun loadsATitleTagWithQuotesSyntaxAmongOtherTags() {
        assertEquals(tags("title" to "Awesome song", "by" to listOf("Sam")), parse("// \"Awesome song\" made @by Sam"))
    }

    @Test
    fun loadsATitleTagWithQuotesSyntaxFromBlockComment() {
        assertEquals(tags("title" to "Awesome song", "by" to listOf("Sam")), parse("/* \"Awesome song\"\n@by Sam */"))
    }

    @Test
    fun doesNotLoadATitleTagWithQuotesSyntaxAfterAPrefix() {
        assertEquals(tags(), parse("// I don't care about those \"metadata\"."))
    }

    @Test
    fun doesNotLoadATitleTagWithQuotesSyntaxAfterAnOtherComment() {
        assertEquals(tags(), parse("// I don't care about those\n// \"metadata\""))
    }

    @Test
    fun doesNotLoadATitleTagWithQuotesSyntaxAfterOtherTags() {
        assertEquals(
            tags("by" to listOf("Sam aka \"Lady Strudel\"", "\"Sandyyy\"")),
            parse("/*\n@by Sam aka \"Lady Strudel\"\n    \"Sandyyy\"\n*/"),
        )
    }

    @Test
    fun loadsATagListWithCommaSeparatedValuesSyntax() {
        assertEquals(tags("by" to listOf("Sam", "Sandy")), parse("// @by Sam, Sandy"))
    }

    @Test
    fun loadsATagListWithDuplicateKeysSyntax() {
        assertEquals(tags("by" to listOf("Sam", "Sandy")), parse("// @by Sam\n// @by Sandy"))
    }

    @Test
    fun loadsATagListWithDuplicateKeysSyntaxWithPrefixes() {
        assertEquals(tags("by" to listOf("Sam", "Sandy")), parse("// song @by Sam\n// samples @by Sandy"))
    }

    @Test
    fun loadsManyTagListsWithDuplicateKeysSyntaxWithinCode() {
        val tune = "note(\"a3 c#4 e4 a4\") // @by Sam @license CC0\n    s(\"bd hh sd hh\") // @by Sandy @license CC BY-NC-SA"
        assertEquals(tags("by" to listOf("Sam", "Sandy"), "license" to listOf("CC0", "CC BY-NC-SA")), parse(tune))
    }

    @Test
    fun loadsATagListWithDuplicateKeysSyntaxFromBlockComment() {
        assertEquals(tags("by" to listOf("Sam", "Sandy")), parse("/* @by Sam\n@by Sandy */"))
    }

    @Test
    fun loadsATagListWithNewlineSyntax() {
        assertEquals(tags("by" to listOf("Sam", "Sandy")), parse("/*\n@by Sam\n    Sandy */"))
    }

    @Test
    fun loadsAMultilineTagFromBlockComment() {
        val tune = "/*\n@details I wrote this song in February 19th, 2023.\n         It was around midnight and I was lying on\n         the sofa in the living room.\n*/"
        assertEquals(
            tags("details" to "I wrote this song in February 19th, 2023. It was around midnight and I was lying on the sofa in the living room."),
            parse(tune),
        )
    }

    @Test
    fun loadsAMultilineTagFromBlockCommentWithDuplicateKeys() {
        val tune = "/*\n@details I wrote this song in February 19th, 2023.\n@details It was around midnight and I was lying on\n         the sofa in the living room.\n*/"
        assertEquals(
            tags("details" to "I wrote this song in February 19th, 2023. It was around midnight and I was lying on the sofa in the living room."),
            parse(tune),
        )
    }

    @Test
    fun loadsAMultilineTagFromInlineComments() {
        val tune = "// @details I wrote this song in February 19th, 2023.\n// @details It was around midnight and I was lying on\n// @details the sofa in the living room.\n*/"
        assertEquals(
            tags("details" to "I wrote this song in February 19th, 2023. It was around midnight and I was lying on the sofa in the living room."),
            parse(tune),
        )
    }

    @Test
    fun loadsEmptyTagsFromInlineComments() {
        assertEquals(tags("title" to "", "by" to emptyList<String>()), parse("// @title\n// @by"))
    }

    @Test
    fun loadsTagsWithWhitespacesFromInlineComments() {
        assertEquals(
            tags("title" to "Awesome song", "by" to listOf("Sam Tagada")),
            parse("   //   @title   Awesome   song   \n   //    @by   Sam   Tagada   "),
        )
    }

    @Test
    fun loadsTagsWithWhitespacesFromBlockComment() {
        assertEquals(
            tags("title" to "Awesome song", "by" to listOf("Sam Tagada")),
            parse("   /*   @title   Awesome   song   \n       @by   Sam   Tagada   */   "),
        )
    }

    @Test
    fun loadsEmptyTagsFromBlockComment() {
        assertEquals(tags("title" to "", "by" to emptyList<String>()), parse("/* @title\n@by */"))
    }

    @Test
    fun doesNotLoadTagsIfThereIsNot() {
        assertEquals(tags(), parse("note(\"a3 c#4 e4 a4\")"))
    }

    @Test
    fun doesNotLoadCodeThatLooksLikeAMetadataTag() {
        assertEquals(tags(), parse("const str1 = '@title Awesome song'"))
    }

    // ---- more ------------------------------------------------------------------------------------------------------------

    @Test
    fun aProgramOfNothingHasNoMetadata() {
        assertEquals(tags(), Metadata.parse(null))
        assertEquals(tags(), parse(""))
    }

    @Test
    fun theVersionIsATagLikeTheRest() {
        assertEquals("1.0", parse("// @version 1.0")["version"])
    }

    @Test
    fun tagsThatAreWholeNumbersComeFirstAscendingAsInAJavaScriptObject() {
        val keys = parse("// @1 one\n// @0 zero\n// @a letter\n// @10 ten\n// @title t").keys.toList()
        assertEquals(listOf("0", "1", "10", "a", "title"), keys)
    }

    // ---- against Strudel ---------------------------------------------------------------------------------------------------

    private val oracle: JsonObject by lazy {
        val text = javaClass.getResourceAsStream("/share/oracle.json")?.bufferedReader()?.readText()
            ?: error("/share/oracle.json is missing: run `node tools/strudel-ref/share-oracle.mjs`")
        Json.parseToJsonElement(text).jsonObject
    }

    private fun valueOf(element: JsonElement): Any = if (element is JsonArray) element.map { it.jsonPrimitive.content } else element.jsonPrimitive.content

    private fun expected(case: JsonObject): List<Pair<String, Any>> =
        case["tags"]!!.jsonArray.map { pair -> pair.jsonArray[0].jsonPrimitive.content to valueOf(pair.jsonArray[1]) }

    /** What Metadata makes of [code], as pairs in the order of its keys (a map equal to another says nothing of the order). */
    private fun actual(code: String): List<Pair<String, Any>> = parse(code).map { it.key to it.value }

    @Test
    fun theTagsOfEveryProgramAreStrudelsKeyByKeyAndInOrder() {
        val cases = oracle["metadata"]!!.jsonArray.map { it.jsonObject }.filter { it["code"] != null }
        assertTrue(cases.size > 80, "the oracle has the cases of the test file and the hand-written ones")
        for (case in cases) {
            val code = case["code"]!!.jsonPrimitive.content
            assertEquals(expected(case), actual(code), "metadata of ${code.replace("\n", "\\n").take(80)}")
        }
    }

    @Test
    fun theProgramsOfTheExamplesAndTunesHaveTheTagsStrudelFindsInThem() {
        val withTags = oracle["metadata"]!!.jsonArray.map { it.jsonObject }.filter { it["source"] != null }
            .associate { it["source"]!!.jsonPrimitive.content to expected(it) }
        val corpus = oracle["corpus"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(corpus.size > 500)
        assertTrue(withTags.isNotEmpty(), "some programs of the corpus have comments with an @")
        val codes = corpusCodes()
        for (source in corpus) {
            val code = codes[source] ?: error("no code for $source")
            assertEquals(withTags[source] ?: emptyList(), actual(code), "metadata of $source")
        }
    }

    private fun corpusCodes(): Map<String, String> {
        fun load(path: String) = Json.parseToJsonElement(javaClass.getResourceAsStream(path)!!.bufferedReader().readText()).jsonArray.map { it.jsonObject }
        val codes = HashMap<String, String>()
        for (e in load("/parity/examples.json")) codes["examples:${e["name"]!!.jsonPrimitive.content}#${e["index"]!!.jsonPrimitive.content}"] = e["code"]!!.jsonPrimitive.content
        for (t in load("/parity/tunes.json")) codes["tunes:${t["name"]!!.jsonPrimitive.content}"] = t["code"]!!.jsonPrimitive.content
        return codes
    }

    // ---- through the engine ------------------------------------------------------------------------------------------------

    @Test
    fun aProgramReportsItsMetadataByDefault() {
        val engine = CosicEngine(48_000, emptyLibrary())
        val report = engine.evaluate("// @title Awesome song\n// @by Sam\ns(\"sine\")")
        assertIs<EvalReport.Ok>(report)
        assertEquals(tags("title" to "Awesome song", "by" to listOf("Sam")), report.program.metadata)
        assertEquals(report.program, engine.program)
    }

    @Test
    fun withoutTheParserThereIsNoMetadata() {
        val engine = CosicEngine(48_000, emptyLibrary())
        engine.metadataParser = null
        val report = engine.evaluate("// @title Awesome song\ns(\"sine\")")
        assertIs<EvalReport.Ok>(report)
        assertEquals(emptyMap(), report.program.metadata)
    }
}
