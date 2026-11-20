package cosic.script

import cosic.SourceRange
import cosic.pattern.CosicError
import cosic.pattern.Undefined
import cosic.script.js.JsFunction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * The errors of the interpreter, one row of the table of the specification per test: the class (what a program that
 * catches the error sees as `e.name`), the message in V8's words, and the range of the code it points at when it
 * leaves the interpreter as a [CosicError]. The wording itself is also compared with V8 snippet by snippet in
 * [InterpreterOracleTest].
 */
class ErrorMessageTest {
    /** Runs [code] and checks the [CosicError] it ends in: [message], and the range of [at] (its first [length] characters). */
    private fun check(code: String, name: String, message: String, at: String, length: Int = at.length, limits: Limits? = null) {
        val realm = testRealm()
        if (limits != null) realm.limits = limits
        val e = assertFailsWith<CosicError>("running $code") { realm.runRaw(code) }
        assertEquals(message, e.message, "message of $code")
        val start = code.indexOf(at)
        assertEquals(SourceRange(start, start + length), e.range, "range of $code")

        val caught = testRealm()
        if (limits != null) caught.limits = limits
        assertEquals(name, caught.runRaw("try {\n$code\n} catch (e) { return e.name; }"), "class of $code")
    }

    @Test
    fun aNameNobodyDeclaredIsAReferenceError() {
        check("const a = 1;\nreturn nothing + a;", "ReferenceError", "nothing is not defined", "nothing")
    }

    @Test
    fun readingALetBeforeItsDeclarationIsAReferenceError() {
        check("return x;\nlet x = 1;", "ReferenceError", "Cannot access 'x' before initialization", "x")
    }

    @Test
    fun assigningToAConstantIsATypeError() {
        check("const c = 1;\nc = 2;", "TypeError", "Assignment to constant variable.", "c = 2", 1)
        check("const c = 1;\nc++;", "TypeError", "Assignment to constant variable.", "c++", 1)
    }

    @Test
    fun callingSomethingThatIsNotAFunctionIsATypeErrorAtTheCall() {
        check("const o = {};\no.f(1);", "TypeError", "o.f is not a function", "o.f(1)")
        check("const a = 1;\na();", "TypeError", "a is not a function", "a()")
        check("const o = {};\nconst k = 'a';\nreturn o[k]();", "TypeError", "o[k] is not a function", "o[k]()")
        check("const f = () => 1;\nf()();", "TypeError", "f(...) is not a function", "f()()")
    }

    @Test
    fun newOnSomethingThatIsNotAConstructorIsATypeErrorAtTheNew() {
        check("const o = {};\nnew o();", "TypeError", "o is not a constructor", "new o()")
        check("const f = () => 1;\nnew f();", "TypeError", "f is not a constructor", "new f()")
    }

    @Test
    fun readingAPropertyOfUndefinedOrNullIsATypeErrorAtTheAccess() {
        check("const u = undefined;\nu.x;", "TypeError", "Cannot read properties of undefined (reading 'x')", "u.x")
        check("const u = null;\nu[0];", "TypeError", "Cannot read properties of null (reading '0')", "u[0]")
        check("const o = {};\no.a.b.c;", "TypeError", "Cannot read properties of undefined (reading 'b')", "o.a.b")
    }

    @Test
    fun settingAPropertyOfUndefinedOrNullIsATypeErrorAtTheTarget() {
        check("const u = undefined;\nu.x = 1;", "TypeError", "Cannot set properties of undefined (setting 'x')", "u.x")
        check("const u = null;\nu[1] = 1;", "TypeError", "Cannot set properties of null (setting '1')", "u[1]")
    }

    @Test
    fun destructuringUndefinedOrNullIsATypeErrorAtThePattern() {
        check("const {x} = undefined;", "TypeError", "Cannot destructure property 'x' of 'undefined' as it is undefined.", "{x}")
        check("const y = null;\nconst {x} = y;", "TypeError", "Cannot destructure property 'x' of 'y' as it is null.", "{x}")
        check("const {} = null;", "TypeError", "Cannot destructure 'null' as it is null.", "{}")
    }

    @Test
    fun spreadingOrIteratingSomethingThatIsNotIterableIsATypeError() {
        check("return [...5];", "TypeError", "5 is not iterable", "5")
        check("const o = {};\nreturn [...o];", "TypeError", "o is not iterable", "o]", 1)
        check("for (const v of 5) {}", "TypeError", "5 is not iterable", "5")
        check("const [a] = undefined;", "TypeError", "undefined is not iterable", "[a]")
        check("const f = (...a) => a;\nreturn f(...5);", "TypeError", "Spread syntax requires ...iterable[Symbol.iterator] to be a function", "5")
    }

    @Test
    fun theInOperatorOnANonObjectIsATypeError() {
        check("'x' in 5;", "TypeError", "Cannot use 'in' operator to search for 'x' in 5", "'x' in 5")
    }

    @Test
    fun instanceofNeedsAFunctionOnTheRight() {
        check("const o = {};\no instanceof 5;", "TypeError", "Right-hand side of 'instanceof' is not an object", "o instanceof 5")
        check("const o = {};\no instanceof o;", "TypeError", "Right-hand side of 'instanceof' is not callable", "o instanceof o")
    }

    @Test
    fun anInvalidArrayLengthIsARangeError() {
        check("const a = [];\na.length = -1;", "RangeError", "Invalid array length", "a.length = -1")
        check("const a = [];\na.length = 1.5;", "RangeError", "Invalid array length", "a.length = 1.5")
    }

    @Test
    fun recursionWithoutEndIsARangeErrorAtTheInnermostCall() {
        check("function f() { f(); }\nf();", "RangeError", "Maximum call stack size exceeded", "f();", 3, limits = Limits(maxDepth = 100))
    }

    @Test
    fun anObjectThatCannotBecomeAPrimitiveIsATypeError() {
        check("const o = {valueOf() { return {}; }, toString() { return {}; }};\nreturn o + 1;", "TypeError", "Cannot convert object to primitive value", "o + 1")
    }

    @Test
    fun aRefusedAssignmentIsATypeErrorWithTheReason() {
        check("const o = {get a() { return 1; }};\no.a = 2;", "TypeError", "Cannot set property a of #<Object> which has only a getter", "o.a")
        check("function f() {}\nf.name = 'g';", "TypeError", "Cannot assign to read only property 'name' of function 'function f() {}'", "f.name")
        check("'abc'.x = 1;", "TypeError", "Cannot create property 'x' on string 'abc'", "'abc'.x")
        check("const a = [];\ndelete a.length;", "TypeError", "Cannot delete property 'length' of [object Array]", "delete a.length")
    }

    @Test
    fun aThrownErrorOfTheProgramIsReportedWithItsMessageAndTheRangeOfTheThrow() {
        check("const a = 1;\nthrow new RangeError('boom');", "RangeError", "boom", "throw new RangeError('boom');")
        check("throw new Error('plain');", "Error", "plain", "throw new Error('plain');")
    }

    @Test
    fun aThrownValueThatIsNotAnErrorIsReportedAsText() {
        val e = assertFailsWith<CosicError> { testRealm().runRaw("throw 'just text';") }
        assertEquals("just text", e.message)
        assertEquals(SourceRange(0, "throw 'just text';".length), e.range)
        assertEquals("[object Object]", assertFailsWith<CosicError> { testRealm().runRaw("throw {a: 1};") }.message)
        assertEquals("42", assertFailsWith<CosicError> { testRealm().runRaw("throw 42;") }.message)
    }

    @Test
    fun anErrorInAnArrowFunctionIsReportedAtTheFailingExpression() {
        val code = "const f = (x) => x.a.b;\nreturn f(undefined);"
        val e = assertFailsWith<CosicError> { testRealm().runRaw(code) }
        assertEquals("Cannot read properties of undefined (reading 'a')", e.message)
        assertEquals(SourceRange(code.indexOf("x.a"), code.indexOf("x.a") + 3), e.range)
    }

    @Test
    fun anErrorInACallbackCalledFromOutsideCarriesTheRangeOfTheFailingNode() {
        val realm = testRealm()
        val code = "return (x) => x.a.b;"
        val f = realm.runRaw(code) as JsFunction
        val e = assertFailsWith<CosicError> { f.call(listOf(Undefined)) }
        assertEquals("Cannot read properties of undefined (reading 'a')", e.message)
        assertEquals(SourceRange(code.indexOf("x.a"), code.indexOf("x.a") + 3), e.range)

        val g = realm.runRaw("return function (x) {\n  if (x) throw new Error('cb ' + x);\n  return 1;\n}") as JsFunction
        assertEquals(1.0, g.call(listOf(false)))
        val t = assertFailsWith<CosicError> { g.call(listOf("now")) }
        assertEquals("cb now", t.message)
        assertNotNull(t.range)
    }

    @Test
    fun aSyntaxErrorIsAnErrorWithTheRangeOfTheProblem() {
        val e = assertFailsWith<CosicError> { testRealm().runRaw("const a = 1;\nlet b = ;") }
        assertEquals("Unexpected token", e.message)
        assertEquals(SourceRange("const a = 1;\nlet b = ".length, "const a = 1;\nlet b = ;".length), e.range)
        assertEquals("'return' outside of function", assertFailsWith<CosicError> { parseProgram("return 1") }.message)
    }

    @Test
    fun anErrorObjectHasTheNameMessageAndStackThatProgramsExpect() {
        val realm = testRealm()
        assertEquals("TypeError: x", realm.text(realm.runRaw("return new TypeError('x')")))
        assertEquals("Error", realm.text(realm.runRaw("return new Error()")))
        assertEquals("TypeError: x", realm.runRaw("return new TypeError('x').stack"))
        assertEquals("boom", realm.runRaw("try { null.x; } catch (e) { return e instanceof TypeError ? 'boom' : 'wrong'; }"))
    }
}
