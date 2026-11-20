package cosic.script.next

import cosic.pattern.CosicError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
        assertTrue(cases.size > 30, "only ${cases.size} cases")
    }

    @Test
    fun theLibraryAnswersLikeV8() {
        val failures = ArrayList<String>()
        for (c in cases) {
            val expected = differences[c.name] ?: oracle[c.name] ?: continue
            val actual = run(c)
            if (actual != expected) {
                failures.add("${c.name}\n    expected: ${expected.take(600)}\n    actual:   ${actual.take(600)}\n    code:     ${c.code.take(300).replace("\n", "\n              ")}")
            }
        }
        if (failures.isNotEmpty()) fail("${failures.size} of ${cases.size} snippets differ from V8:\n" + failures.take(40).joinToString("\n"))
    }
}
