package cosic.script.next

import cosic.pattern.CosicError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Runs the snippets of `script/interpreter/cases.txt` in the interpreter and compares what they print with what V8
 * printed (`oracle.json`, made by `node tools/strudel-ref/interpreter-oracle.mjs`): values with their key order, and the
 * `name: message` of the errors they throw. Each snippet is the body of a function; `prelude.js` makes the text of the
 * value (`show`) and of a throw (`run`), and is the same code in both engines.
 *
 * [differences] lists the snippets whose answer deliberately differs from V8's, with Cosic's answer and the reason.
 */
class InterpreterOracleTest {
    private class Case(val name: String, val code: String)

    private fun resource(name: String): String =
        javaClass.getResourceAsStream("/script/interpreter/$name")!!.bufferedReader().readText()

    private val prelude by lazy { resource("prelude.js") }

    private val oracle: Map<String, String> by lazy {
        Json.parseToJsonElement(resource("oracle.json")).jsonObject.mapValues { it.value.jsonPrimitive.content }
    }

    private val cases: List<Case> by lazy { parseCases(resource("cases.txt")) }

    /** Snippets where Cosic answers otherwise than V8, and why. */
    private val differences: Map<String, String> = mapOf(
        // An array has no holes (the specification: a hole reads as undefined), so every index below the length is a key.
        "arr.index-write-grows" to "[4,undefined,\"x\",[\"0\",\"1\",\"2\",\"3\"]]",
    )

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
        val realm = testRealm()
        return try {
            realm.runRaw("$prelude\nreturn run(function () {\n${c.code}\n});") as String
        } catch (e: CosicError) {
            "CosicError: ${e.message} @${e.range}"
        }
    }

    @Test
    fun theCasesAreAllInTheOracle() {
        val missing = cases.filter { it.name !in oracle }.map { it.name }
        assertTrue(missing.isEmpty(), "run node tools/strudel-ref/interpreter-oracle.mjs: no oracle result for $missing")
        assertTrue(cases.size > 250, "only ${cases.size} cases")
    }

    @Test
    fun theInterpreterAnswersLikeV8() {
        val failures = ArrayList<String>()
        for (c in cases) {
            val expected = differences[c.name] ?: oracle.getValue(c.name)
            val actual = run(c)
            if (actual != expected) failures.add("${c.name}\n    expected: $expected\n    actual:   $actual\n    code:     ${c.code.replace("\n", "\n              ")}")
        }
        if (failures.isNotEmpty()) fail("${failures.size} of ${cases.size} snippets differ from V8:\n" + failures.take(25).joinToString("\n"))
    }
}
