package cosic.script

import cosic.SourceRange
import cosic.pattern.CosicError
import cosic.pattern.JsFn
import cosic.pattern.Undefined
import cosic.script.js.JsUndefined
import cosic.script.js.JsArray
import cosic.script.js.JsFunction
import cosic.script.js.JsObject
import cosic.script.js.stdlib.JsPromise
import cosic.script.js.JsThrow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * What the interpreter promises to the rest of the engine, one fact per test. The language itself (scopes, coercions,
 * control flow, the text of the errors) is compared with V8 by [InterpreterOracleTest]; [ErrorMessageTest] covers the
 * errors and their ranges, [RealmLimitsTest] the budgets and threads.
 */
class InterpreterTest {
    private fun run(code: String, realm: Realm = testRealm()): Any? = realm.runRaw(code)

    @Test
    fun aProgramIsTheBodyOfAFunctionAndReturnsWhatItReturns() {
        assertEquals(6.0, Realm(TestHost()).runRaw("const f = x => x * 2; return f(3)"))
        assertSame(Undefined, run("let a = 1; a + 1"))
        assertEquals("x", run("return 'x'; return 'y'"))
        assertNull(run("return null"))
    }

    @Test
    fun declarationsAtTheTopLevelStayLocalToTheRun() {
        val realm = testRealm()
        realm.runRaw("var a = 1; let b = 2; const c = 3; function d() {}")
        assertEquals("undefinedundefinedundefinedundefined", realm.runRaw("return typeof a + typeof b + typeof c + typeof d"))
        assertFalse(realm.global.has("a"))
    }

    @Test
    fun thisIsUndefinedInPlainCallsAndAtTheTopLevel() {
        assertSame(Undefined, run("return this"))
        assertSame(Undefined, run("function f() { return this; } return f();"))
        assertSame(Undefined, run("return (() => this)();"))
    }

    @Test
    fun aMethodAddedToAPrototypeSeesTheReceiverAsThis() {
        val realm = testRealm()
        val pattern = realm.constructor("Pattern", 0, realm.patternPrototype, { _, _ -> Undefined }, { JsObject(realm.patternPrototype) })
        realm.defineGlobal("Pattern", pattern)
        val result = realm.runRaw(
            """
            Pattern.prototype.twice = function () { return [this.v, this.v]; };
            Pattern.prototype.chain = function (n) { this.v = n; return this; };
            const p = new Pattern();
            return [p.chain(4).twice(), p instanceof Pattern, 'twice' in p, typeof p.chain];
            """.trimIndent(),
        )
        assertIs<JsArray>(result)
        assertEquals("4,4", realm.text(result.items[0]))
        assertEquals(true, result.items[1])
        assertEquals(true, result.items[2])
        assertEquals("function", result.items[3])
    }

    @Test
    fun aFunctionOfTheProgramIsAFunctionOfTheKernelToo() {
        val f = run("return (a, b = 1, ...c) => a + b") as JsFn
        assertEquals(1, f.arity)
        assertEquals(5.0, f.call(listOf(2.0, 3.0)))
        assertEquals(3.0, f.call(listOf(2.0)))
        val g = run("return function (a, b) { return this === undefined ? a * b : 'this'; }") as JsFunction
        assertEquals(2, g.length)
        assertEquals(12.0, g.call(listOf(3.0, 4.0)))
        assertEquals("this", g.call(Unit, listOf(3.0, 4.0)))
        assertEquals("g", (run("const g = () => 1; return g") as JsFunction).name)
    }

    @Test
    fun aFunctionOfTheProgramCanBeCalledAfterTheRunThatMadeItIsOver() {
        val realm = testRealm()
        val counter = realm.runRaw("let n = 0; return () => ++n;") as JsFunction
        assertEquals(1.0, counter.call(emptyList()))
        assertEquals(2.0, counter.call(emptyList()))
        assertEquals(3.0, realm.callFunction(counter, Undefined, emptyList()))
    }

    @Test
    fun anAccessorMadeInKotlinIsReadByTheProgramAndTheOtherWayRound() {
        val realm = testRealm()
        var stored: Any? = Undefined
        val o = realm.newObject()
        o.defineAccessor("k", { _ -> "from kotlin" }, { _, v -> stored = v })
        realm.defineGlobal("o", o)
        assertEquals("from kotlin", realm.runRaw("return o.k"))
        realm.runRaw("o.k = 7")
        assertEquals(7.0, stored)

        val made = realm.runRaw("return { get a() { return this.b + 1; }, b: 1, set c(v) { this.b = v; } }") as JsObject
        assertEquals(2.0, made.get("a"))
        made.set("c", 10.0)
        assertEquals(11.0, made.get("a"))
    }

    @Test
    fun anAccessorOnTheGlobalObjectIsReadLikeAVariable() {
        val realm = testRealm()
        var reads = 0
        realm.global.defineAccessor("tick", { _ -> (++reads).toDouble() })
        assertEquals("1,2", realm.text(realm.runRaw("return [tick, tick]")))
        assertEquals("number", realm.runRaw("return typeof tick"))
    }

    @Test
    fun globalVariablesAreThePropertiesOfTheGlobalObject() {
        val realm = testRealm()
        realm.defineGlobal("answer", 42.0)
        assertEquals(42.0, realm.runRaw("return answer"))
        assertEquals(42.0, realm.runRaw("return globalThis.answer"))
        realm.runRaw("globalThis.fresh = 'f'")
        assertEquals("f", realm.runRaw("return fresh"))
        assertEquals(5.0, realm.runRaw("answer = 5; return globalThis.answer"))
        assertEquals(5.0, realm.global.get("answer"))
        val e = assertFailsWith<CosicError> { realm.runRaw("nobody = 1") }
        assertEquals("nobody is not defined", e.message)
        assertEquals("undefined", realm.runRaw("return typeof nobody"))
    }

    @Test
    fun undefinedNaNAndInfinityAreConstantsUnlessShadowed() {
        assertSame(Undefined, run("return undefined"))
        assertEquals(true, run("return NaN !== NaN"))
        assertEquals(Double.POSITIVE_INFINITY, run("return Infinity"))
        assertEquals(5.0, run("const f = (undefined) => undefined; return f(5)"))
        val e = assertFailsWith<CosicError> { run("undefined = 1") }
        assertEquals("Cannot assign to read only property 'undefined' of object '#<Object>'", e.message)
    }

    @Test
    fun aNativeFunctionGetsTheCallSiteAndTheArguments() {
        val realm = testRealm()
        var site: Node? = null
        var seen: List<Any?> = emptyList()
        realm.defineGlobal("probe", realm.functionAt("probe", 0) { _, args, s ->
            site = s
            seen = args
            "probed"
        })
        val code = "const x = 1;\nreturn probe(1, 'two', x);"
        assertEquals("probed", realm.runRaw(code))
        assertEquals(listOf<Any?>(1.0, "two", 1.0), seen)
        val call = "probe(1, 'two', x)"
        assertEquals(SourceRange(code.indexOf(call), code.indexOf(call) + call.length), site!!.range)
    }

    @Test
    fun aKernelErrorThrownByANativeCanBeCaughtByTheProgram() {
        val realm = testRealm()
        realm.defineTestFunction("boom") { throw CosicError("kernel says no") }
        realm.defineTestFunction("bad") { throw realm.typeError("bad argument") }
        assertEquals("Error: kernel says no", realm.runRaw("try { boom(); } catch (e) { return e.name + ': ' + e.message; }"))
        assertEquals("TypeError: bad argument", realm.runRaw("try { bad(); } catch (e) { return e.name + ': ' + e.message; }"))
        val code = "const a = 1;\nboom();"
        val e = assertFailsWith<CosicError> { realm.runRaw(code) }
        assertEquals("kernel says no", e.message)
        assertEquals(SourceRange(code.indexOf("boom()"), code.indexOf("boom()") + 6), e.range)
        val f = assertFailsWith<CosicError> { realm.runRaw("bad()") }
        assertEquals("bad argument", f.message)
        assertEquals(SourceRange(0, 5), f.range)
    }

    @Test
    fun aTaggedTemplateGetsCookedAndRawStringsAndTheValues() {
        assertEquals("a|b|#1,2#3", run("function t(s, ...v) { return s.join('|') + '#' + v.join(',') + '#' + s.length; } return t`a\${1}b\${2}`;"))
        assertEquals("\\u{zz}|undefined", run("function t(s) { return s.raw[0] + '|' + s[0]; } return t`\\u{zz}`;"))
    }

    @Test
    fun aTaggedTemplateOfAnEmbeddedLanguageIsHandedToThatLanguage() {
        val realm = testRealm()
        var seen: List<Any?> = emptyList()
        EmbeddedLanguages.register("interpreterTestLang") { code, codeStart, r, range ->
            seen = listOf(code, codeStart, r, range)
            "evaluated: $code"
        }
        val code = "const x = 1;\nreturn interpreterTestLang`c3 e3`;"
        assertEquals("evaluated: c3 e3", realm.runRaw(code))
        val open = code.indexOf('`')
        assertEquals("c3 e3", seen[0])
        assertEquals(open + 1, seen[1])
        assertSame(realm, seen[2])
        assertEquals(SourceRange(code.indexOf("interpreterTestLang"), code.lastIndexOf('`') + 1), seen[3])
        // Only the first piece of the template is used, as in Strudel: `${}` and what follows are dropped.
        assertEquals("evaluated: c3 ", realm.runRaw("const x = 1; return interpreterTestLang`c3 \${x} e3`;"))
    }

    @Test
    fun anAsyncFunctionReturnsAPromiseThatAwaitUnwrapsUnlessTheHostReplacesTheRealmsAsyncSupport() {
        val realm = testRealm()
        assertEquals(5.0, realm.runRaw("return await 5"))
        assertEquals(6.0, realm.runRaw("const f = async (x) => (await x) + 1; return await f(5)"))
        assertIs<JsPromise>(realm.runRaw("const f = async (x) => x; return f(5)"))

        val log = ArrayList<String>()
        realm.async = object : AsyncSupport {
            override fun runAsync(body: () -> Any?): Any? {
                log.add("async")
                return "promise of ${body()}"
            }

            override fun await(value: Any?): Any? {
                log.add("await $value")
                return "awaited"
            }
        }
        assertEquals("awaited", realm.runRaw("return await 5"))
        assertEquals("promise of awaited!", realm.runRaw("const f = async (x) => (await x) + '!'; return f(5)"))
        assertEquals(listOf("await 5.0", "async", "await 5.0"), log)
    }

    @Test
    fun aParsedProgramCanBeRunSeveralTimesAndTheLastExpressionCanBeMadeItsValue() {
        val code = "const a = 2; a * 21"
        val parsed = parseProgram(code)
        val last = parsed.program.body.last() as ExpressionStatement
        val program = Program(0, code.length, parsed.program.body.dropLast(1) + ReturnStatement(last.start, last.end, last.expression))
        val realm = testRealm()
        assertEquals(42.0, realm.runProgram(program, code))
        assertEquals(42.0, realm.runProgram(program, code))
        assertEquals(42.0, testRealm().runProgram(program, code))
    }

    @Test
    fun aClosureRemembersItsSourceForToString() {
        assertEquals("(a, b) => a + b", run("const f = (a, b) => a + b; return `\${f}`"))
        val f = run("return function  named ( x ) { return x }") as JsFunction
        assertEquals("function  named ( x ) { return x }", f.sourceText())
        assertEquals("function max() { [native code] }", testRealm().function("max", 2) { _, _ -> Undefined }.sourceText())
    }

    @Test
    fun theValueModelIsTheKernelsUndefinedKotlinNullDoublesAndStrings() {
        assertSame(Undefined, JsUndefined)
        assertSame(Undefined, run("return undefined"))
        assertSame(Undefined, run("return void 0"))
        assertSame(Undefined, run("return [][0]"))
        assertNull(run("return null"))
        assertEquals(listOf<Any?>(1.0, "s", true, null, Undefined), (run("return [1, 's', true, null, undefined]") as JsArray).items)
        // A number is always a Double, whatever way it was made.
        assertEquals(listOf(true, true, true), (run("return [1, 0x10 + 1, 7 / 2 * 2]") as JsArray).items.map { it is Double })
    }

    @Test
    fun theKeysOfAnObjectAreInJavaScriptOrder() {
        val o = run("const o = {b: 1, 2: 1, a: 1, 1: 1, '-1': 1, '01': 1}; return o") as JsObject
        assertEquals(listOf("1", "2", "b", "a", "-1", "01"), o.ownKeys())
    }

    @Test
    fun theMethodsOfABuiltInAreNotListedByForIn() {
        assertEquals("", run("let r = ''; for (const k in {}) r += k; for (const k in []) r += k; return r"))
        assertEquals("a", run("let r = ''; for (const k in {a: 1}) r += k; return r"))
    }

    @Test
    fun aFunctionCanBeUsedAsAConstructorOnlyWhenItWasMadeByFunction() {
        val realm = testRealm()
        val code = "function F() { this.x = 1; } const a = () => 1; const o = { m() {} }; return [F, a, o.m, async function () {}];"
        val fns = (realm.runRaw(code) as JsArray).items.map { it as JsFunction }
        assertEquals(listOf(true, false, false, false), fns.map { it.isConstructor })
        assertEquals(1.0, (realm.construct(fns[0], emptyList()) as JsObject).get("x"))
        assertFailsWith<JsThrow> { realm.construct(fns[1], emptyList()) }
    }
}
