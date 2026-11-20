package cosic.script

import cosic.SliderConfig
import cosic.SourceRange
import cosic.WidgetConfig
import cosic.parity.ParityData
import cosic.pattern.CosicError
import cosic.script.catalogue.Catalogue
import cosic.script.kabel.Kabelsalat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.security.MessageDigest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Compares the transpiler with Strudel's. `tools/strudel-ref/transpile-oracle.mjs` runs programs through the transpiler
 * of the pinned commit (with the mini-notation, bare-samples, widget and kabelsalat plugins, the ones the website REPL loads) and
 * stores what came out: the tree of the output with positions and the `raw` of literals taken away, the mini locations,
 * the widgets and the sliders, or the error. The programs are the whole parity corpus and the hand-written snippets of
 * `script/transpile/cases.txt`, the latter with four sets of options.
 *
 * The mini package is not part of this: the oracle also stores the atoms of every mini-notation string it met, and the
 * transpiler is given a locator that answers from them (and fails when asked for anything else, or in another order).
 * The embedded languages `mondo` and `mondolang`, which the script registers, take their whole code as their only
 * atom here as well. Strudel rewrites ``mondo`...` `` into a call of `mondo`, where Cosic leaves the template for the
 * interpreter; the comparison reads the one as the other.
 */
class TranspileOracleTest {
    private class Expected(
        val error: String?,
        val evalError: String?,
        val tree: String?,
        val digest: String?,
        val miniLocations: List<SourceRange>?,
        val literals: List<Triple<Int, String, List<IntArray>>>,
        val widgets: List<WidgetConfig>,
        val sliders: List<SliderConfig>,
    )

    /** One program run with one set of options; [name] is the snippet or program it belongs to. */
    private class Run(val name: String, val label: String, val code: String, val options: TranspileOptions, val expected: Expected)

    @BeforeTest
    fun registerLanguages() {
        // `tidal` is registered with the catalogue, which another test may or may not have installed before this one (Gradle runs the classes that failed last time first)
        Catalogue.installAll()
        for (tag in listOf("mondo", "mondolang")) EmbeddedLanguages.register(tag, WholeCodeLanguage)
        Kabelsalat.install()
    }

    private val handWritten: List<Run> by lazy {
        val text = resource("/script/transpile/oracle.json")
        Json.parseToJsonElement(text).jsonArray.flatMap { element ->
            val o = element.jsonObject
            val name = o.getValue("name").jsonPrimitive.content
            val code = o.getValue("code").jsonPrimitive.content
            o.getValue("variants").jsonArray.map { variant ->
                val v = variant.jsonObject
                val options = options(v.getValue("options").jsonObject)
                Run(name, "$name $options", code, options, expected(v))
            }
        }
    }

    private val corpus: List<Run> by lazy {
        val oracle = Json.parseToJsonElement(resource("/script/transpile/corpus-oracle.json")).jsonObject
        ParityData.cases.flatMap { case ->
            case.codes.mapIndexed { index, code -> (if (case.repl) "${case.id}/$index" else case.id) to code }
        }.mapNotNull { (id, code) -> oracle[id]?.let { Run(id, id, code, TranspileOptions(), expected(it.jsonObject)) } }
    }

    // ---- the hand-written snippets -----------------------------------------------------------------------------------------

    /**
     * Snippets Cosic deliberately transpiles differently. A null message marks code that Strudel makes into text that
     * does not even run (a double-quoted property name becomes a call, LANG DEV-1) and that Cosic leaves alone; a message
     * is that of the error Cosic reports where Strudel's transpiler crashes (a label on something that is not an expression).
     */
    private val differences: Map<String, String?> = mapOf(
        "mini.object-key-string" to null,
        "mini.object-key-and-value" to null,
        "mini.object-method-key" to null,
        "mini.pattern-key" to null,
        "label.error-block" to "Labeled statements must label an expression",
        "label.error-nested" to "Labeled statements must label an expression",
        "label.error-loop" to "Labeled statements must label an expression",
        "label.error-if" to "Labeled statements must label an expression",
    )

    /**
     * Sliders Cosic records differently (ENV D-5, INLINE 3.11): a number literal with a sign counts as a literal, as the first
     * argument and as `min`, `max` and `step`; Strudel takes a unary expression for no literal and falls back to the defaults.
     * Everything else about these programs, the tree included, is compared as usual; the sliders are what is listed here, and
     * what Strudel recorded is checked against [strudelSliders] so that the list cannot outlive the difference.
     */
    private val signedSliders: Map<String, List<SliderConfig>> = mapOf(
        "slider.negative-first" to listOf(SliderConfig(7, 9, "-1", -2.0, 2.0, 0.5, "slider_7")),
        "slider.negative-second" to listOf(SliderConfig(7, 8, "5", 1.0, -3.0, null, "slider_7")),
        "slider.signed-all" to listOf(SliderConfig(7, 11, "-0.5", -1.0, 1.0, -0.1, "slider_7")),
        "slider.signed-bounds" to listOf(SliderConfig(7, 8, "0", -1.0, 1.0, null, "slider_7")),
        "slider.plus-first" to listOf(SliderConfig(7, 9, "+1", 0.0, 1.0, null, "slider_7")),
        "slider.negative-hex-first" to listOf(SliderConfig(7, 12, "-0x10", 0.0, 1.0, null, "slider_7")),
    )

    /** What Strudel's transpiler records for the programs of [signedSliders]: the signs are lost. */
    private val strudelSliders: Map<String, List<SliderConfig>> = mapOf(
        "slider.negative-first" to listOf(SliderConfig(7, 9, null, 0.0, 2.0, 0.5, "slider_7")),
        "slider.negative-second" to listOf(SliderConfig(7, 8, "5", 1.0, 1.0, null, "slider_7")),
        "slider.signed-all" to listOf(SliderConfig(7, 11, null, 0.0, 1.0, null, "slider_7")),
        "slider.signed-bounds" to listOf(SliderConfig(7, 8, "0", 0.0, 1.0, null, "slider_7")),
        "slider.plus-first" to listOf(SliderConfig(7, 9, null, 0.0, 1.0, null, "slider_7")),
        "slider.negative-hex-first" to listOf(SliderConfig(7, 12, null, 0.0, 1.0, null, "slider_7")),
    )

    @Test
    fun everyHandWrittenSnippetIsTranspiledLikeStrudel() {
        val failures = ArrayList<String>()
        var compared = 0
        for (run in handWritten) {
            val difference = differences.containsKey(run.name)
            val problem = when {
                difference -> deliberateDifference(run, differences.getValue(run.name))
                signedSliders.containsKey(run.name) -> signedSliderDifference(run)
                else -> compare(run, withTree = true)
            }
            if (problem != null) failures.add(problem) else if (!difference && !skipped(run)) compared++
        }
        if (failures.isNotEmpty()) fail("${failures.size} of ${handWritten.size} runs differ from Strudel's transpiler:\n\n" + failures.take(40).joinToString("\n\n"))
        assertTrue(compared > 600, "only $compared runs were compared")
    }

    @Test
    fun theDeliberateDifferencesAreAllRealSnippets() {
        assertEquals(emptySet(), differences.keys - handWritten.map { it.name }.toSet())
        assertEquals(emptySet(), signedSliders.keys - handWritten.map { it.name }.toSet())
        assertEquals(signedSliders.keys, strudelSliders.keys)
    }

    /** The program is transpiled like Strudel's in every respect but the sliders, which are those of [signedSliders]. */
    private fun signedSliderDifference(run: Run): String? {
        val header = "${run.label}: ${show(run.code)}"
        val recorded = run.options.emitMiniLocations
        val strudel = if (recorded) strudelSliders.getValue(run.name) else emptyList()
        if (run.expected.sliders != strudel) return "$header\n  Strudel's sliders were supposed to be $strudel, they are ${run.expected.sliders}"
        compare(run, withTree = true, withSliders = false)?.let { return it }
        val expected = if (recorded) signedSliders.getValue(run.name) else emptyList()
        val actual = transpile(run.code, run.options, DEFAULT_WIDGET_TYPES, Replay(run.expected.literals)).sliders
        return if (actual == expected) null else "$header\n  sliders differ\n  expected: $expected\n  Cosic:    $actual"
    }

    private fun deliberateDifference(run: Run, message: String?): String? {
        val header = "${run.label}: ${show(run.code)}"
        val expected = run.expected
        if (message == null) {
            if (expected.evalError == null) return "$header\n  Strudel's text was supposed not to run, but it did"
            return try {
                transpile(run.code, run.options, DEFAULT_WIDGET_TYPES, MiniLeafLocator { _, _ -> emptyList() })
                null
            } catch (e: CosicError) {
                "$header\n  Cosic fails with '${e.message}' where it should transpile the code"
            }
        }
        if (expected.error == null) return "$header\n  Strudel's transpiler was supposed to fail, but it did not"
        return try {
            transpile(run.code, run.options, DEFAULT_WIDGET_TYPES, MiniLeafLocator { _, _ -> emptyList() })
            "$header\n  expected the error '$message' but the code transpiled"
        } catch (e: CosicError) {
            if (e.message == message) null else "$header\n  expected the error '$message', got '${e.message}'"
        }
    }

    // ---- the corpus --------------------------------------------------------------------------------------------------------

    @Test
    fun everyProgramOfTheCorpusIsTranspiledLikeStrudel() {
        val failures = ArrayList<String>()
        var compared = 0
        for (run in corpus) {
            val problem = compare(run, withTree = false)
            if (problem != null) failures.add(problem) else if (!skipped(run)) compared++
        }
        if (failures.isNotEmpty()) fail("${failures.size} of ${corpus.size} programs differ from Strudel's transpiler:\n\n" + failures.take(30).joinToString("\n\n"))
        assertTrue(compared > 550, "only $compared programs were compared")
    }

    @Test
    fun everyProgramOfTheCorpusHasAnOracleEntry() {
        val programs = ParityData.cases.sumOf { it.codes.size }
        assertEquals(programs, corpus.size, "programs without an entry in corpus-oracle.json: run tools/strudel-ref/transpile-oracle.mjs")
    }

    // ---- one run -----------------------------------------------------------------------------------------------------------

    /** Strudel's transpiler fails on a mini-notation it cannot parse; the mini package is not the subject here. */
    private fun skipped(run: Run): Boolean = run.expected.error?.startsWith("[mini] parse error") == true

    private fun compare(run: Run, withTree: Boolean, withSliders: Boolean = true): String? {
        if (skipped(run)) return null
        val header = "${run.label}: ${show(run.code)}"
        val expected = run.expected
        val replay = Replay(expected.literals)
        val result = try {
            transpile(run.code, run.options, DEFAULT_WIDGET_TYPES, replay)
        } catch (e: CosicError) {
            val message = expected.error ?: expected.evalError ?: return "$header\n  Cosic fails with '${e.message}' at ${e.range} where Strudel transpiles it"
            return if (e.message == message) null else "$header\n  expected the error '$message'\n  got '${e.message}' at ${e.range}"
        } catch (e: AssertionError) {
            return "$header\n  ${e.message}"
        }
        if (expected.error != null) return "$header\n  Strudel fails with '${expected.error}' but Cosic transpiles the code"
        if (expected.evalError != null) return "$header\n  Strudel's output does not run ('${expected.evalError}') but Cosic transpiles the code"
        replay.finish()?.let { return "$header\n  $it" }

        val shape = normalize(dumpAst(embeddedLanguagesAsCalls(result.program)))
        if (withTree) {
            if (shape != expected.tree) return "$header\n" + firstDifference(expected.tree!!, shape)
        } else if (digest(shape) != expected.digest) {
            val file = File("build/transpile-oracle/" + run.label.replace(Regex("[^A-Za-z0-9._#-]"), "_") + ".txt")
            file.parentFile.mkdirs()
            file.writeText(shape + "\n")
            return "$header\n  the tree differs from Strudel's (digest ${digest(shape)}, expected ${expected.digest}); Cosic's is in ${file.path}, " +
                "Strudel's comes from `node tools/strudel-ref/transpile-oracle.mjs --show '${run.name}'`"
        }
        if (result.miniLocations != (expected.miniLocations ?: emptyList<SourceRange>())) {
            return "$header\n  mini locations differ\n  Strudel: ${expected.miniLocations}\n  Cosic:   ${result.miniLocations}"
        }
        if (result.widgets != expected.widgets) return "$header\n  widgets differ\n  Strudel: ${expected.widgets}\n  Cosic:   ${result.widgets}"
        if (withSliders && result.sliders != expected.sliders) return "$header\n  sliders differ\n  Strudel: ${expected.sliders}\n  Cosic:   ${result.sliders}"
        return null
    }

    /** Answers the locator calls of one run from what the oracle recorded, and checks they come in that order. */
    private class Replay(private val literals: List<Triple<Int, String, List<IntArray>>>) : MiniLeafLocator {
        private var next = 0

        override fun leafLocations(quoted: String, start: Int): List<IntArray> {
            val expected = literals.getOrNull(next) ?: throw AssertionError("the locator was asked for $quoted at $start but Strudel found no more mini strings")
            if (expected.first != start || expected.second != quoted) {
                throw AssertionError("the locator was asked for $quoted at $start but Strudel's ${next + 1}th mini string is ${expected.second} at ${expected.first}")
            }
            next++
            return expected.third
        }

        fun finish(): String? = if (next == literals.size) null else "the locator was asked for $next of the ${literals.size} mini strings Strudel found"
    }

    private object WholeCodeLanguage : EmbeddedLanguage {
        override fun evaluate(code: String, codeStart: Int, realm: Realm, range: SourceRange): Any? = error("the transpiler does not evaluate")
        override fun locations(code: String, codeStart: Int): List<SourceRange> = listOf(SourceRange(codeStart, codeStart + code.length))
    }

    // ---- the tree as text ------------------------------------------------------------------------------------------------------

    /** Strudel prints ``mondo`code` `` as the call `mondo('code', offset)`; this is that call for the template Cosic keeps. */
    private fun embeddedLanguagesAsCalls(node: Node): Node {
        if (node is TaggedTemplateExpression) {
            val tag = node.tag
            if (tag is Identifier && EmbeddedLanguages.lookup(tag.name) != null) {
                val code = node.quasi.quasis[0].raw
                val offset = node.quasi.start + 1
                val arguments = listOf<Node>(Literal(node.start, node.end, code, "\"\""), Literal(node.start, node.end, offset.toDouble(), offset.toString()))
                return CallExpression(node.start, node.end, tag, arguments, optional = false)
            }
        }
        return node.mapChildren { embeddedLanguagesAsCalls(it) }
    }

    /** [dumpAst] without positions and without the `raw` of literals: what the oracle script's `normalize` does to acorn's tree. */
    private fun normalize(dump: String): String {
        val line = Regex("^(\\s*)(\\w+) \\d+-\\d+(.*)$", RegexOption.DOT_MATCHES_ALL) // a value may hold U+2028
        val raw = Regex("^ raw=\"(?:[^\"\\\\]|\\\\.)*\"")
        return dump.split('\n').joinToString("\n") { text ->
            val m = line.matchEntire(text) ?: return@joinToString text
            val type = m.groupValues[2]
            val attributes = if (type == "Literal") raw.replace(m.groupValues[3], "") else m.groupValues[3]
            m.groupValues[1] + type + attributes
        }
    }

    private fun digest(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(16)

    private fun firstDifference(expected: String, actual: String): String {
        val e = expected.split('\n')
        val a = actual.split('\n')
        for (i in 0 until maxOf(e.size, a.size)) {
            if (e.getOrNull(i) != a.getOrNull(i)) {
                val from = maxOf(i - 2, 0)
                return "  first difference at line ${i + 1}\n" +
                    "  Strudel: " + e.subList(from, minOf(e.size, i + 3)).joinToString("\n           ") + "\n" +
                    "  Cosic:   " + a.subList(from, minOf(a.size, i + 3)).joinToString("\n           ")
            }
        }
        return "  (no difference found)"
    }

    private fun show(code: String): String = "\"" + code.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\"", "\\\"") + "\""

    // ---- the oracle files --------------------------------------------------------------------------------------------------------

    private fun resource(path: String): String =
        javaClass.getResourceAsStream(path)?.bufferedReader()?.readText() ?: error("$path is missing: run `node tools/strudel-ref/transpile-oracle.mjs`")

    private fun options(o: JsonObject) = TranspileOptions(
        addReturn = o["addReturn"]?.jsonPrimitive?.booleanOrNull ?: true,
        emitMiniLocations = o["emitMiniLocations"]?.jsonPrimitive?.booleanOrNull ?: true,
        emitWidgets = o["emitWidgets"]?.jsonPrimitive?.booleanOrNull ?: true,
        id = o["id"]?.jsonPrimitive?.contentOrNull,
    )

    private fun expected(o: JsonObject): Expected =
        Expected(
            error = o["error"]?.jsonPrimitive?.contentOrNull,
            evalError = o["evalError"]?.jsonPrimitive?.contentOrNull,
            tree = o["tree"]?.jsonPrimitive?.contentOrNull,
            digest = o["digest"]?.jsonPrimitive?.contentOrNull,
            miniLocations = (o["miniLocations"] as? JsonArray)?.map { range(it) },
            literals = (o["literals"] as? JsonArray)?.map { literal ->
                val parts = literal.jsonArray
                Triple(
                    parts[0].jsonPrimitive.int,
                    parts[1].jsonPrimitive.content,
                    parts[2].jsonArray.map { pair -> intArrayOf(pair.jsonArray[0].jsonPrimitive.int, pair.jsonArray[1].jsonPrimitive.int) },
                )
            } ?: emptyList(),
            widgets = (o["widgets"] as? JsonArray)?.map { widget ->
                val w = widget.jsonObject
                WidgetConfig(
                    type = w.getValue("type").jsonPrimitive.content,
                    from = w.getValue("from").jsonPrimitive.int,
                    to = w.getValue("to").jsonPrimitive.int,
                    index = w.getValue("index").jsonPrimitive.int,
                    id = w["id"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull,
                )
            } ?: emptyList(),
            sliders = (o["sliders"] as? JsonArray)?.map { slider ->
                val s = slider.jsonObject
                val from = s.getValue("from").jsonPrimitive.int
                SliderConfig(
                    from = from,
                    to = s.getValue("to").jsonPrimitive.int,
                    value = s["value"]?.jsonPrimitive?.contentOrNull,
                    min = s.getValue("min").jsonPrimitive.double,
                    max = s.getValue("max").jsonPrimitive.double,
                    step = s["step"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.double,
                    id = "slider_$from",
                )
            } ?: emptyList(),
        )

    private fun range(element: JsonElement): SourceRange = SourceRange(element.jsonArray[0].jsonPrimitive.int, element.jsonArray[1].jsonPrimitive.int)
}
