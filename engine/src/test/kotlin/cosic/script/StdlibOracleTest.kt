package cosic.script

import cosic.pattern.CosicError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.TimeZone
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Runs the snippets of `script/stdlib/cases.txt` in the interpreter, with the standard library, and compares what they
 * print with what V8 printed (`script/stdlib/oracle.json`, made by `node tools/strudel-ref/stdlib-oracle.mjs`). Same
 * format and prelude as [InterpreterOracleTest]; a snippet may also call `logs()` for the lines `console.*` wrote.
 *
 * [differences] lists the snippets whose answer deliberately differs from V8's, with Cosic's answer and the reason.
 */
class StdlibOracleTest {
    private var savedZone: TimeZone? = null

    /** The oracle ran with TZ=Europe/Paris, so the local time methods agree only in that zone. */
    @BeforeTest
    fun useTheOraclesTimeZone() {
        savedZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Paris"))
    }

    @AfterTest
    fun restoreTheTimeZone() {
        savedZone?.let { TimeZone.setDefault(it) }
    }

    private class Case(val name: String, val code: String)

    private fun resource(name: String): String =
        javaClass.getResourceAsStream("/script/$name")!!.bufferedReader().readText()

    private val prelude by lazy { resource("interpreter/prelude.js") }

    private val oracle: Map<String, String> by lazy {
        Json.parseToJsonElement(resource("stdlib/oracle.json")).jsonObject.mapValues { it.value.jsonPrimitive.content }
    }

    private val cases: List<Case> by lazy { parseCases(resource("stdlib/cases.txt")) }

    /** Snippets where Cosic answers otherwise than V8, and why. */
    private val differences: Map<String, String> = mapOf()

    private fun parseCases(text: String): List<Case> {
        val cases = ArrayList<Case>()
        var name: String? = null
        var json = false
        val lines = ArrayList<String>()
        fun flush() {
            val n = name ?: return
            val body = lines.joinToString("\n").trimEnd('\n')
            cases.add(Case(n, if (json) Json.parseToJsonElement(body).jsonPrimitive.content else body))
            lines.clear()
        }
        val header = Regex("^### (\\S+)( !json)?$")
        for (line in text.split('\n')) {
            if (line.startsWith("## ")) continue
            val m = header.find(line)
            if (m != null) {
                flush()
                name = m.groupValues[1]
                json = m.groupValues[2].isNotEmpty()
            } else if (name != null) {
                lines.add(line)
            }
        }
        flush()
        return cases
    }

    private fun run(c: Case): String {
        val host = TestHost()
        val realm = testRealm(host)
        realm.defineGlobalFunction("logs", 0) { _, _ ->
            val lines = host.logs.map { it.second }
            host.logs.clear()
            realm.newArray(lines)
        }
        return try {
            realm.runRaw("$prelude\nreturn run(function () {\n${c.code}\n});") as String
        } catch (e: CosicError) {
            "CosicError: ${e.message} @${e.range}"
        }
    }

    @Test
    fun theCasesAreAllInTheOracle() {
        val missing = cases.filter { it.name !in oracle }.map { it.name }
        assertTrue(missing.isEmpty(), "run node tools/strudel-ref/stdlib-oracle.mjs: no oracle result for $missing")
        assertTrue(cases.size > 150, "only ${cases.size} cases")
    }

    /** `...before[expected | actual]after` around the first character where the two texts differ. */
    private fun firstDifference(expected: String, actual: String): String {
        var i = 0
        while (i < expected.length && i < actual.length && expected[i] == actual[i]) i++
        val from = maxOf(0, i - 60)
        fun excerpt(s: String) = s.substring(minOf(i, s.length), minOf(s.length, i + 80)).replace("\n", "\\n")
        return "at $i: ...${expected.substring(from, i).replace("\n", "\\n")}[expected: ${excerpt(expected)} | actual: ${excerpt(actual)}]"
    }

    @Test
    fun theLibraryAnswersLikeV8() {
        val failures = ArrayList<String>()
        val dump = StringBuilder()
        for (c in cases) {
            val expected = differences[c.name] ?: oracle[c.name] ?: continue
            val actual = run(c)
            if (actual != expected) {
                failures.add("${c.name}\n    ${firstDifference(expected, actual)}")
                dump.append("### ${c.name}\nexpected: $expected\nactual:   $actual\ncode:\n${c.code}\n\n")
            }
        }
        if (failures.isNotEmpty()) {
            java.io.File("build/stdlib-oracle-failures.txt").also { it.parentFile?.mkdirs() }.writeText(dump.toString())
            fail("${failures.size} of ${cases.size} snippets differ from V8 (all in build/stdlib-oracle-failures.txt):\n" + failures.joinToString("\n"))
        }
    }
}
