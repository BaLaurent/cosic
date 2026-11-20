package cosic.script.next

import cosic.pattern.CosicError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Compares the parser with acorn on the hand-written snippets of `script/parser/cases.txt`, whose acorn results
 * `tools/strudel-ref/parser-oracle.mjs` stored in `oracle.json` (acorn 8.14 in strict mode, as Strudel evaluates code):
 * a snippet acorn parses must give the same tree down to every offset, and one it rejects must be rejected with the same
 * message at the same position.
 *
 * [differences] lists where Cosic deliberately answers otherwise: syntax acorn reads but Cosic does not run (classes,
 * generators, regular expressions, BigInt, private names, `import()`, `new.target`, `debugger`, `for await`) and the few
 * errors that Cosic words or places differently. Anything else that differs is a bug.
 */
class ParserOracleTest {
    private class Case(val name: String, val code: String, val ast: String?, val error: Pair<String, Int>?, val unsupported: List<String>?)

    private val cases: List<Case> by lazy {
        val text = javaClass.getResourceAsStream("/script/parser/oracle.json")!!.bufferedReader().readText()
        Json.parseToJsonElement(text).jsonArray.map { element ->
            val o = element.jsonObject
            Case(
                name = o.getValue("name").jsonPrimitive.content,
                code = o.getValue("code").jsonPrimitive.content,
                ast = o["ast"]?.jsonPrimitive?.content,
                error = o["error"]?.jsonObject?.let { it.getValue("message").jsonPrimitive.content to it.getValue("pos").jsonPrimitive.int },
                unsupported = (o["unsupported"] as? JsonArray)?.map { it.jsonPrimitive.content },
            )
        }
    }

    private val regex = "Regular expressions are not supported"
    private val generator = "Generators are not supported"
    private val bigInt = "BigInt literals are not supported"
    private val privateId = "Private identifiers are not supported"
    private val classes = "Classes are not supported"
    private val newTarget = "new.target is not supported"
    private val forAwait = "for await is not supported"
    private val constInit = "Missing initializer in const declaration"
    private val reserved = "Unexpected reserved word"
    private val unaryExponent =
        "Unary operator used immediately before exponentiation expression. Parenthesis must be used to disambiguate operator precedence"

    /** Snippet name to the message and offset Cosic reports. */
    private val differences: Map<String, Pair<String, Int>> = mapOf(
        // syntax acorn reads and Cosic does not run
        "stmt.for-await-top-level" to (forAwait to 4),
        "await.in-async-for-await" to (forAwait to 25),
        "body.for-await-in-error" to (forAwait to 4),
        "stmt.debugger" to ("The debugger statement is not supported" to 0),
        "fn.generator-error" to (generator to 8),
        "fn.generator-expression-error" to (generator to 12),
        "fn.generator-async-error" to (generator to 14),
        "fn.generator-method-error" to (generator to 6),
        "fn.generator-async-method-error" to (generator to 12),
        "lit.bigint-error" to (bigInt to 0),
        "lit.bigint-hex-error" to (bigInt to 0),
        "lit.regex-error" to (regex to 0),
        "lit.regex-in-call-error" to (regex to 2),
        "lit.regex-after-return-error" to (regex to 22),
        "lit.regex-class-slash-error" to (regex to 4),
        "lit.regex-after-assign-error" to (regex to 4),
        "lit.regex-after-comma-error" to (regex to 5),
        "lit.regex-after-typeof-error" to (regex to 7),
        "lit.regex-after-open-brace-error" to (regex to 2),
        "lit.regex-statement-start-error" to (regex to 0),
        "lit.regex-after-paren-statement-error" to (regex to 7),
        "member.new-regex-error" to (regex to 4),
        "member.new-target-error" to (newTarget to 15),
        "member.import-call-error" to ("Dynamic import is not supported" to 0),
        "member.class-error" to (classes to 0),
        "member.class-expression-error" to (classes to 4),
        "member.class-extends-error" to (classes to 0),
        "body.class-as-if-body-error" to (classes to 7),
        // where acorn reads a regular expression or a private name and fails later, or fails with a message of its own
        "lit.regex-unterminated-error" to (regex to 4),
        "member.new-target-top-level-error" to (newTarget to 0),
        "ident.hash-error" to (privateId to 0),
        "ident.private-access-error" to (privateId to 2),
        "member.private-in-error" to (privateId to 0),
        "strudel.hash-error" to (privateId to 3),
        "ident.lone-surrogate-error" to ("Unexpected character '\uD800'" to 0),
        // errors worded differently or placed on the name
        "decl.const-no-init-error" to (constInit to 6),
        "decl.const-no-init-second-error" to (constInit to 13),
        "decl.const-destructure-no-init-error" to (constInit to 6),
        "body.for-const-no-init-in-classic-error" to (constInit to 11),
        // `await` is an operator here, and Strudel runs the code in an async function, which reserves it
        "decl.await-binding-top-level" to (reserved to 4),
        "object.shorthand-await-top-level" to (reserved to 6),
        "cover.async-arrow-await-shorthand-error" to (reserved to 9),
        // the same place, in V8's words
        "op.exponent-unary-error" to (unaryExponent to 3),
        "op.exponent-typeof-error" to (unaryExponent to 9),
        "op.exponent-await-error" to (unaryExponent to 20),
        "op.exponent-negative-number-error" to (unaryExponent to 3),
        "await.with-exponent-error" to (unaryExponent to 8),
    )

    @Test
    fun everyHandWrittenSnippetIsReadLikeAcorn() {
        val failures = cases.mapNotNull { problem(it) }
        if (failures.isNotEmpty()) fail("${failures.size} of ${cases.size} snippets differ from acorn:\n\n" + failures.take(80).joinToString("\n\n"))
    }

    @Test
    fun theDifferencesAreAllRealSnippets() {
        val names = cases.map { it.name }.toSet()
        assertEquals(emptySet(), differences.keys - names)
    }

    @Test
    fun theOracleDataIsBigEnoughToMeanSomething() {
        assertTrue(cases.size > 800, "only ${cases.size} snippets")
        assertTrue(cases.count { it.ast != null } > 450)
        assertTrue(cases.count { it.error != null } > 250)
    }

    private fun problem(case: Case): String? {
        val deliberate = differences[case.name]
        val header = "${case.name}: ${show(case.code)}"
        val result = try {
            parseProgram(case.code)
        } catch (e: CosicError) {
            val expected = deliberate ?: case.error ?: return "$header\n  Cosic fails with '${e.message}' at ${e.range}, but acorn ${if (case.unsupported != null) "reads it (${case.unsupported.joinToString()})" else "parses it"}"
            if (e.message != expected.first) return "$header\n  expected '${expected.first}' at ${expected.second}\n  got      '${e.message}' at ${e.range}"
            val range = e.range ?: return "$header\n  the error has no range"
            // An error at the very end of the code (acorn reports offset = length) is reported on the last character.
            val atEnd = expected.second >= case.code.length
            val rightPlace = if (atEnd) range.end == case.code.length else range.start == expected.second
            if (!rightPlace) return "$header\n  expected '${expected.first}' at ${expected.second}\n  got      '${e.message}' at ${e.range}"
            return null
        }
        if (deliberate != null) return "$header\n  expected the error '${deliberate.first}' but the code parsed"
        if (case.error != null) return "$header\n  acorn fails with '${case.error.first}' at ${case.error.second}, but the code parsed"
        if (case.unsupported != null) return "$header\n  acorn reads ${case.unsupported.joinToString()}, which Cosic does not support, but the code parsed"
        val actual = dumpAst(result.program)
        if (actual == case.ast) return null
        return "$header\n" + firstDifference(case.ast!!, actual)
    }

    private fun show(code: String): String = "\"" + code.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r").replace("\"", "\\\"") + "\""

    private fun firstDifference(expected: String, actual: String): String {
        val e = expected.lines()
        val a = actual.lines()
        for (i in 0 until maxOf(e.size, a.size)) {
            if (e.getOrNull(i) != a.getOrNull(i)) {
                val from = maxOf(i - 2, 0)
                return "  first difference at line ${i + 1}\n" +
                    "  acorn:  " + e.subList(from, minOf(e.size, i + 3)).joinToString("\n          ") + "\n" +
                    "  Cosic:  " + a.subList(from, minOf(a.size, i + 3)).joinToString("\n          ")
            }
        }
        return "  (no difference found)"
    }
}
