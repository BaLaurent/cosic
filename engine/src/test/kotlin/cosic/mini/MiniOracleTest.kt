package cosic.mini

import cosic.pattern.CosicError
import cosic.pattern.Rational
import cosic.script.catalogue.Catalogue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Compares the mini-notation package with Strudel's. `tools/strudel-ref/mini-oracle.mjs` runs mini-notation strings
 * through `m(src, 10)` of the pinned commit (packages/mini: the Peggy parser and mini.mjs) and stores the haps of the
 * cycles from -2 to 2, the source ranges of every hap, the steps of the pattern, the atoms in the order Strudel visits them and
 * the error message of a string that does not parse. Hand-written strings cover the grammar rule by rule, a generator
 * with a fixed seed adds a thousand more, a few of them broken.
 *
 * Strings that use euclidean rhythms are only compared when the registered `euclid` and `euclidRot` behave as Strudel's
 * do (see [euclidIsStrudels]): they are the functions of another task, which mini-notation calls.
 */
class MiniOracleTest {
    private class Case(val src: String, val json: JsonObject) {
        val error: String? get() = json["error"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull
        val begin: Int get() = json["begin"]!!.jsonPrimitive.int
        val cycles: Int get() = json["cycles"]!!.jsonPrimitive.int
        val haps: List<String> get() = json["haps"]!!.jsonArray.map { it.jsonPrimitive.content }
        val locs: List<List<List<Int>>> get() = json["locs"]!!.jsonArray.map { hap -> ranges(hap.jsonArray) }
        val steps: String? get() = json["steps"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.contentOrNull

        /** Evaluating the string changed Strudel's global `silence`, which makes its steps depend on how that leak went. */
        val sharedMutated: Boolean get() = json["sharedMutated"]?.jsonPrimitive?.booleanOrNull == true
        val leaves: List<List<Int>>? get() = json["leaves"]?.let { ranges(it.jsonArray) }
        val usesEuclid: Boolean get() = '(' in src

        private fun ranges(array: JsonArray): List<List<Int>> = array.map { r -> r.jsonArray.map { it.jsonPrimitive.int } }
    }

    @BeforeTest
    fun registerTheFunctions() = Catalogue.installAll()

    private fun parseCases(text: String): List<Case> =
        Json.parseToJsonElement(text).jsonArray.map { Case(it.jsonObject["src"]!!.jsonPrimitive.content, it.jsonObject) }

    private val cases: List<Case> by lazy {
        parseCases(
            javaClass.getResourceAsStream("/mini/oracle.json")?.bufferedReader()?.readText()
                ?: error("/mini/oracle.json is missing: run `node tools/strudel-ref/mini-oracle.mjs`"),
        )
    }

    /** `m(src, 10)`, the call the transpiler writes for a string that starts at offset 10, and the haps of its cycles. */
    private fun evaluate(c: Case): Result<Evaluated> = try {
        val pat = m(c.src, OFFSET)
        // like Strudel's queryArc, which logs an error of the query and answers nothing
        val haps = pat.sortHapsByPart().queryArcLogged(Rational(c.begin.toLong()), Rational((c.begin + c.cycles).toLong()))
        Result.success(
            Evaluated(
                haps.map { it.show(true) },
                haps.map { h -> h.context.locations.map { listOf(it.start, it.end) }.sortedWith(compareBy({ it[0] }, { it[1] })) },
                pat.steps?.show(),
            ),
        )
    } catch (e: CosicError) {
        Result.failure(e)
    }

    private class Evaluated(val haps: List<String>, val locs: List<List<List<Int>>>, val steps: String?)

    /** Why [c] does not come out as in Strudel, or null when it does. */
    private fun mismatch(c: Case): String? {
        val error = c.error
        val result = evaluate(c)
        if (error != null) {
            val failure = result.exceptionOrNull()
                ?: return "Strudel fails (${error.take(100)}), the string evaluates"
            // a notation that does not parse fails with Strudel's message; any other failure (a query that divides by zero) only has to fail
            if (error.startsWith("[mini]") && failure.message != error) return "error\n    expected $error\n    actual   ${failure.message}"
            return null
        }
        val actual = result.getOrElse { return "evaluates in Strudel, fails here: ${it.message}" }
        if (actual.haps != c.haps) {
            val first = actual.haps.zip(c.haps).indexOfFirst { (a, b) -> a != b }.let { if (it < 0) minOf(actual.haps.size, c.haps.size) else it }
            return "haps differ at $first (${actual.haps.size} here, ${c.haps.size} in Strudel)\n    expected ${c.haps.getOrNull(first)}\n    actual   ${actual.haps.getOrNull(first)}"
        }
        if (actual.locs != c.locs) {
            val first = actual.locs.zip(c.locs).indexOfFirst { (a, b) -> a != b }
            return "source ranges differ at hap $first\n    expected ${c.locs[first]}\n    actual   ${actual.locs[first]}"
        }
        if (!c.sharedMutated && actual.steps != c.steps) return "steps differ: expected ${c.steps}, actual ${actual.steps}"
        val leaves = c.leaves
        if (leaves != null) {
            val here = try {
                getLeafLocations("\"${c.src}\"", OFFSET).map { it.toList() }
            } catch (e: CosicError) {
                return "getLeafLocations fails here: ${e.message}"
            }
            if (here != leaves) return "leaves differ\n    expected $leaves\n    actual   $here"
        }
        return null
    }

    /** Whether the euclidean rhythms of the registry are Strudel's: the strings of the corpus that use them are compared only if so. */
    private val euclidIsStrudels: Boolean by lazy {
        PROBES.all { probe -> cases.firstOrNull { it.src == probe }?.let { mismatch(it) == null } ?: true }
    }

    /** Compares [cases] with Strudel's output; returns how many were compared. */
    private fun compare(cases: List<Case>): Int {
        val failures = ArrayList<String>()
        var skipped = 0
        for (c in cases) {
            if (c.usesEuclid && !euclidIsStrudels) {
                skipped++
                continue
            }
            val why = mismatch(c) ?: continue
            failures.add("${c.src.replace("\n", "\\n").replace("\t", "\\t")}: $why")
        }
        if (failures.isNotEmpty()) {
            fail("${failures.size} of ${cases.size - skipped} cases differ from Strudel" + failures.take(25).joinToString("\n  ", "\n  "))
        }
        println("mini oracle: compared ${cases.size - skipped} of ${cases.size} cases, ${cases.count { it.error != null }} of them errors; euclid is Strudel's: $euclidIsStrudels")
        return cases.size - skipped
    }

    @Test
    fun theCorpusComesOutAsInStrudel() {
        assertTrue(compare(cases) > 800, "fewer than 800 cases were compared")
    }

    /** A corpus of your own: `node tools/strudel-ref/mini-oracle.mjs --strings in.json out.json`, then `COSIC_MINI_ORACLE=out.json ./gradlew :engine:test --rerun --tests 'cosic.mini.MiniOracleTest'`. */
    @Test
    fun aCorpusNamedByTheEnvironmentComesOutAsInStrudel() {
        val path = System.getenv("COSIC_MINI_ORACLE")
        assumeTrue("COSIC_MINI_ORACLE names no corpus", path != null)
        compare(parseCases(java.io.File(path!!).readText()))
    }

    private companion object {
        const val OFFSET = 10

        /** Strings of the corpus that tell the euclid of the registry apart from the one of Strudel: rotation, inversion, legato, patterns. */
        val PROBES = listOf("a(3,8,2)", "a(-3,8)", "a(3,-8)", "a(-3,-8)", "a(<3 5>,8,<0 2>)")
    }
}
