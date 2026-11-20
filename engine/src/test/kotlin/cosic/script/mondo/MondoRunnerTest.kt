package cosic.script.mondo

import cosic.pattern.CosicError
import cosic.pattern.JsFn
import cosic.pattern.Undefined
import cosic.pattern.jsFn
import cosic.pattern.jsLess
import cosic.pattern.jsStrictEq
import cosic.pattern.jsToNumber
import cosic.pattern.jsToPrimitive
import cosic.pattern.jsToString
import cosic.pattern.jsTruthy
import cosic.pattern.jsTypeof
import cosic.script.onBigStack
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The evaluator of the arithmetic tests of `mondo.test.mjs`, which shows that the runner is a lisp that does not know what
 * its names mean: numbers, lists and functions of JavaScript, with the arithmetic JavaScript does on them.
 */
internal class ArithmeticEvaluator : MondoEvaluator {
    private fun multi(op: (Any?, Any?) -> Any?): JsFn = jsFn(1) { args ->
        var acc: Any? = args.getOrElse(0) { Undefined }
        for (arg in args.drop(1)) acc = op(acc, arg)
        acc
    }

    private fun add(a: Any?, b: Any?): Any? {
        val x = jsToPrimitive(a)
        val y = jsToPrimitive(b)
        if (x is String || y is String) return jsToString(x) + jsToString(y)
        return jsToNumber(x) + jsToNumber(y)
    }

    private fun length(items: Any?): Any? = when (items) {
        is List<*> -> items.size.toDouble()
        is String -> items.length.toDouble()
        else -> Undefined
    }

    val lib: Map<String, Any?> = mapOf(
        "+" to multi(::add),
        "add" to multi(::add),
        "-" to multi { a, b -> jsToNumber(a) - jsToNumber(b) },
        "sub" to multi { a, b -> jsToNumber(a) - jsToNumber(b) },
        "*" to multi { a, b -> jsToNumber(a) * jsToNumber(b) },
        "/" to multi { a, b -> jsToNumber(a) / jsToNumber(b) },
        "mod" to multi { a, b -> jsToNumber(a) % jsToNumber(b) },
        "eq" to jsFn(2) { a -> jsStrictEq(a.getOrElse(0) { Undefined }, a.getOrElse(1) { Undefined }) },
        "lt" to jsFn(2) { a -> jsLess(a.getOrElse(0) { Undefined }, a.getOrElse(1) { Undefined }) == true },
        "gt" to jsFn(2) { a -> jsLess(a.getOrElse(1) { Undefined }, a.getOrElse(0) { Undefined }) == true },
        "and" to jsFn(2) { a -> a.getOrElse(0) { Undefined }.let { if (!jsTruthy(it)) it else a.getOrElse(1) { Undefined } } },
        "or" to jsFn(2) { a -> a.getOrElse(0) { Undefined }.let { if (jsTruthy(it)) it else a.getOrElse(1) { Undefined } } },
        "not" to jsFn(1) { a -> !jsTruthy(a.getOrElse(0) { Undefined }) },
        "run" to jsFn(0) { a -> a.lastOrNull() ?: Undefined },
        "def" to jsFn(0) { 0.0 },
        // fdlibm, like the engine of the original
        "sin" to jsFn(1) { a -> StrictMath.sin(jsToNumber(a.getOrElse(0) { Undefined })) },
        "cos" to jsFn(1) { a -> StrictMath.cos(jsToNumber(a.getOrElse(0) { Undefined })) },
        "PI" to Math.PI,
        "cons" to jsFn(2) { a -> listOf(a.getOrElse(0) { Undefined }) + (a.getOrElse(1) { Undefined }.let { if (it is List<*>) it else listOf(it) }) },
        "car" to jsFn(1) { a ->
            when (val pair = a.getOrElse(0) { Undefined }) {
                is List<*> -> pair.getOrElse(0) { Undefined }
                Undefined, null -> throw CosicError("Cannot read properties of ${if (pair == null) "null" else "undefined"} (reading '0')")
                else -> Undefined
            }
        },
        "cdr" to jsFn(1) { a -> (a.getOrElse(0) { Undefined } as List<*>).drop(1) },
        "list" to jsFn(0) { a -> a },
        "nil" to emptyList<Any?>(),
        "isnull" to jsFn(1) { a -> length(a.getOrElse(0) { Undefined }) == 0.0 },
        "concat" to jsFn(0) { a -> a.joinToString("") { if (it == null || it === Undefined) "" else jsToString(it) } },
        "error" to jsFn(0) { a -> throw CosicError(a.joinToString(" ") { if (it == null || it === Undefined) "" else jsToString(it) }) },
    )

    override fun leaf(leaf: MondoLeaf, scope: MondoScope): Any? {
        val key = jsToString(leaf.value)
        val local = scope[key]
        if (local != null && local !== Undefined) return local
        val global = lib[key]
        if (global != null && global !== Undefined) return global
        return leaf.value
    }

    override fun call(children: List<Any?>, scope: MondoScope): Any? {
        val fn = children.firstOrNull() ?: Undefined
        if (fn !is JsFn) throw CosicError("\"${jsToString(fn)}\": expected function, got ${jsTypeof(fn)} \"${jsToString(fn)}\"")
        return fn.call(children.drop(1))
    }
}

/** `mondo.test.mjs` for the runner (run through the real one, see `tools/strudel-ref/mondo-oracle.mjs`), and the special forms. */
class MondoRunnerTest {
    private val runner = MondoRunner(ArithmeticEvaluator())

    private fun oracle(): JsonObject =
        Json.parseToJsonElement(MondoRunnerTest::class.java.getResource("/mondo/runner-oracle.json")!!.readText()).jsonObject

    /** A value the oracle recorded as a value of the runner. */
    private fun decode(e: JsonElement): Any? = when (e) {
        is JsonNull -> null
        is JsonArray -> e.map { decode(it) }
        is JsonObject -> when {
            "u" in e -> Undefined
            "n" in e -> e.getValue("n").jsonPrimitive.content.let { if (it == "NaN") Double.NaN else if (it == "Infinity") Double.POSITIVE_INFINITY else Double.NEGATIVE_INFINITY }
            "fn" in e -> jsFn(0) { Undefined }
            else -> fail("unexpected $e")
        }
        is JsonPrimitive -> if (e.isString) e.content else e.booleanOrNull ?: e.doubleOrNull
    }

    private fun assertSameValue(expected: Any?, actual: Any?, context: String) {
        when {
            expected is Double && actual is Double ->
                assertTrue(expected == actual || expected.isNaN() && actual.isNaN(), "$context: expected $expected but got $actual")
            expected is List<*> && actual is List<*> -> {
                assertEquals(expected.size, actual.size, "$context: expected $expected but got $actual")
                for (i in expected.indices) assertSameValue(expected[i], actual[i], context)
            }
            expected is JsFn -> assertTrue(actual is JsFn, "$context: expected a function but got $actual")
            else -> assertEquals(expected, actual, context)
        }
    }

    /** Runs the programs in order, each in the scope the oracle gave it (a scope lives on from one program to the next). */
    private fun runPrograms(entries: List<JsonObject>, check: (JsonObject, () -> Any?) -> Unit) {
        val scopes = HashMap<Int, MondoScope>()
        for (entry in entries) {
            val program = entry.getValue("program").jsonObject
            val id = program["scope"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.toInt()
            val scope: MondoScope = if (id == null) HashMap() else scopes.getOrPut(id) {
                HashMap<String, Any?>().also { map ->
                    program["init"]?.jsonObject?.forEach { (key, value) -> map[key] = decode(value) }
                }
            }
            check(entry) { runner.run(program.getValue("code").jsonPrimitive.content, scope) }
        }
    }

    @Test
    fun everyTestOfTheOriginalGivesTheValueItExpects() {
        val entries = oracle().getValue("recorded").jsonArray.map { it.jsonObject }
        assertTrue(entries.size >= 120, "the oracle has ${entries.size} tests")
        onBigStack {
            runPrograms(entries) { entry, run ->
                val name = entry.getValue("name").jsonPrimitive.content
                assertSameValue(decode(entry.getValue("expected")), run(), name)
            }
        }
    }

    @Test
    fun theRunnerDoesWhatTheRealOneDoesWithMistakesAndSpecialForms() {
        val entries = oracle().getValue("programs").jsonArray.map { it.jsonObject }
        val differences = ArrayList<String>()
        onBigStack {
            runPrograms(entries) { entry, run ->
                val name = entry.getValue("name").jsonPrimitive.content
                val expectedError = entry["error"]?.jsonPrimitive?.content
                try {
                    val actual = run()
                    if (expectedError != null) differences.add("$name: expected the error '$expectedError' but got $actual")
                    else try {
                        assertSameValue(decode(entry.getValue("expected")), actual, name)
                    } catch (e: AssertionError) {
                        differences.add(e.message ?: name)
                    }
                } catch (e: CosicError) {
                    if (e.message != expectedError) differences.add("$name: expected ${expectedError?.let { "the error '$it'" } ?: "a value"} but got the error '${e.message}'")
                }
            }
        }
        assertEquals(emptyList(), differences)
    }

    @Test
    fun aDefinedFunctionSeesNamesDefinedAfterItIsMade() {
        assertEquals(4.0, runner.run("run (def (f x) (* x k)) (def k 2) (f 2)"))
    }

    @Test
    fun aDefinitionInsideAFunctionIsLocalToTheCall() {
        val scope: MondoScope = HashMap()
        assertEquals(11.0, runner.run("run (def (g n) (def m 10) (+ n m)) (g 1)", scope))
        assertTrue("m" !in scope)
    }

    @Test
    fun theScopeOfTheCallerIsNotChangedByAFunctionsParameters() {
        val scope: MondoScope = hashMapOf("x" to 5.0)
        assertEquals(3.0, runner.run("run ((fn (x) x) 3)", scope))
        assertEquals(5.0, scope["x"])
    }

    @Test
    fun anUnknownNameIsItsOwnValue() {
        assertEquals("abc", runner.run("run abc"))
    }

    @Test
    fun aRunawayRecursionIsReportedNotCrashed() {
        val e = assertFailsWith<CosicError> { onBigStack { runner.run("run (def (f x) (f x)) (f 1)") } }
        assertEquals(cosic.pattern.TOO_DEEP, e.message)
    }

    @Test
    fun commentsAreLeftOutOfAList() {
        assertEquals(3.0, runner.run("run (+ 1 // one\n 2)"))
        assertSame(Undefined, runner.run("run // nothing\n"))
    }
}
