package cosic.script

import cosic.SourceRange
import cosic.pattern.CosicError
import cosic.script.js.JsFunction
import cosic.script.js.JsObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * The transpiler's output run by the interpreter: the two meet at the tree (synthetic nodes with the ranges of what they
 * replace) and at the names the transpiled code calls (`m`, `p`, `sliderWithID`, `await`).
 */
class TranspiledProgramTest {
    /** A realm with a toy pattern: `m(text, offset)` makes one, `s(text)` makes one too, and `.p(label)` records it. */
    private class Toy {
        val realm = testRealm()
        val labelled = ArrayList<Pair<String, String>>()

        init {
            realm.defineGlobal("m", realm.function("m", 2) { _, args -> pattern("m(${realm.text(args[0])}@${realm.text(args[1])})") })
            realm.defineGlobal("s", realm.function("s", 1) { _, args -> pattern("s(${show(args[0])})") })
            realm.defineGlobal("samples", realm.function("samples", 1) { _, _ -> "samples loaded" })
            realm.defineGlobal("sliderWithID", realm.function("sliderWithID", 4) { _, args -> "slider ${realm.text(args[0])} = ${realm.text(args[1])}" })
            realm.defineMethod(realm.patternPrototype, "p", 1) { self, args ->
                labelled.add(realm.text(args[0]) to realm.text((self as JsObject).get("text")))
                self
            }
            realm.defineMethod(realm.patternPrototype, "fast", 1) { self, args ->
                pattern("${realm.text((self as JsObject).get("text"))}.fast(${realm.text(args[0])})")
            }
        }

        fun pattern(text: String): JsObject = JsObject(realm.patternPrototype).also { it.defineOwn("text", text) }

        private fun show(v: Any?): String = if (v is JsObject && v.hasOwn("text")) realm.text(v.get("text")) else realm.text(v)

        fun run(code: String): Any? = realm.runProgram(transpile(code).program, code)
    }

    @Test
    fun theLastExpressionIsTheValueAndDoubleQuotedStringsArePatterns() {
        val toy = Toy()
        val code = """s("bd sd").fast(2)"""
        val value = toy.run(code)
        assertIs<JsObject>(value)
        assertEquals("s(m(bd sd@${code.indexOf('"')})).fast(2)", value.get("text"))
    }

    @Test
    fun labelsBecomeCallsOfPWithTheLabelAsText() {
        val toy = Toy()
        val code = "\$: s(\"bd\")\n_muted: s(\"hh\").fast(2)\nlead: s(\"cp\")"
        toy.run(code)
        assertEquals(
            listOf(
                "\$" to "s(m(bd@${code.indexOf("\"bd\"")}))",
                "_muted" to "s(m(hh@${code.indexOf("\"hh\"")})).fast(2)",
                "lead" to "s(m(cp@${code.indexOf("\"cp\"")}))",
            ),
            toy.labelled,
        )
    }

    @Test
    fun aBareSamplesCallIsAwaitedAndASliderGetsItsId() {
        val toy = Toy()
        assertEquals("samples loaded", toy.run("samples('github:x/y')"))
        assertEquals("slider slider_7 = 0.5", toy.run("slider(0.5, 0, 1)"))
        assertEquals("slider slider_7 = 3", toy.run("slider(3)"))
    }

    @Test
    fun aProgramWithoutAnExpressionAtTheEndGivesSilence() {
        val toy = Toy()
        toy.realm.defineGlobal("silence", "silent")
        assertEquals("silent", toy.run("const a = 1;"))
        assertEquals("silent", toy.run(""))
    }

    @Test
    fun errorsInTranspiledCodeAreReportedAtTheRangeInTheOriginalCode() {
        val toy = Toy()
        val code = "const a = 1;\ns(\"bd\").nope(2)"
        val e = assertFailsWith<CosicError> { toy.run(code) }
        assertEquals("s(...).nope is not a function", e.message)
        val call = "s(\"bd\").nope(2)"
        assertEquals(SourceRange(code.indexOf(call), code.indexOf(call) + call.length), e.range)

        // V8 prints the transpiled call for a double-quoted string, and so does the interpreter.
        val f = assertFailsWith<CosicError> { toy.run("const x = \"c3 e3\".nope()") }
        assertEquals("m(...).nope is not a function", f.message)
    }

    @Test
    fun aFunctionOfTheTranspiledProgramCanBeCalledWithAPatternFromOutside() {
        val toy = Toy()
        val f = toy.run("const twice = (pat) => pat.fast(2); twice") as JsFunction
        val result = f.call(listOf(toy.pattern("x"))) as JsObject
        assertEquals("x.fast(2)", result.get("text"))
        val e = assertFailsWith<CosicError> { f.call(listOf(null)) }
        assertEquals("Cannot read properties of null (reading 'fast')", e.message)
    }
}
