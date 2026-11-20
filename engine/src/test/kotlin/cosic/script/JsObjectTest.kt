package cosic.script

import cosic.pattern.JsFn
import cosic.pattern.Undefined
import cosic.script.js.AccessorProp
import cosic.script.js.JsArray
import cosic.script.js.JsFunction
import cosic.script.js.JsObject
import cosic.script.js.JsThrow
import cosic.script.js.PropFlags
import cosic.script.js.arrayIndexOf
import cosic.script.js.looseEquals
import cosic.script.js.toNumber
import cosic.script.js.toPrimitive
import cosic.script.js.toStr
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The objects, arrays and functions of the interpreter, and the conversions that read their properties. */
class JsObjectTest {
    private val realm = testRealm()

    @Test
    fun anObjectLooksUpItsPrototypeChainAndAssignmentMakesAnOwnProperty() {
        val base = JsObject()
        base.defineOwn("x", 1.0)
        val derived = JsObject(base)
        assertEquals(1.0, derived.get("x"))
        assertTrue(derived.has("x"))
        assertFalse(derived.hasOwn("x"))
        assertTrue(derived.set("x", 2.0))
        assertEquals(2.0, derived.get("x"))
        assertEquals(1.0, base.get("x"))
        assertTrue(derived.hasOwn("x"))
        assertSame(Undefined, derived.get("missing"))
        assertFalse(derived.has("missing"))
    }

    @Test
    fun ownKeysAreIntegerLikeKeysAscendingThenTheOthersInTheOrderTheyWereAdded() {
        val o = JsObject()
        for (k in listOf("b", "10", "a", "9", "-1", "01", "2")) o.defineOwn(k, 1.0)
        assertEquals(listOf("2", "9", "10", "b", "a", "-1", "01"), o.ownKeys())
        o.defineOwn("b", 2.0)
        assertEquals(listOf("2", "9", "10", "b", "a", "-1", "01"), o.ownKeys(), "a property that is set again keeps its place")
        o.delete("b")
        o.defineOwn("b", 3.0)
        assertEquals(listOf("2", "9", "10", "a", "-1", "01", "b"), o.ownKeys())
    }

    @Test
    fun aSharedObjectHasTheSameKeyOrder() {
        val o = realm.newSharedObject()
        assertTrue(o.isShared)
        for (k in listOf("b", "10", "a", "9")) o.defineOwn(k, 1.0)
        o.defineOwn("b", 2.0)
        assertEquals(listOf("9", "10", "b", "a"), o.ownKeys())
        assertEquals(2.0, o.get("b"))
        o.set("a", 5.0)
        assertEquals(5.0, o.get("a"))
        assertEquals(listOf("9", "10", "b", "a"), o.ownKeys())
    }

    @Test
    fun aReadOnlyPropertyRefusesAssignmentAlsoWhenItIsInherited() {
        val base = JsObject()
        base.defineOwn("ro", 1.0, PropFlags.ENUMERABLE)
        val derived = JsObject(base)
        assertFalse(base.set("ro", 2.0))
        assertFalse(derived.set("ro", 2.0))
        assertEquals(1.0, derived.get("ro"))
        assertFalse(derived.hasOwn("ro"))
    }

    @Test
    fun aSetterOnAPrototypeRunsWithTheReceiver() {
        var receiver: Any? = null
        val base = JsObject()
        base.defineAccessor("v", { self -> "got from ${(self as JsObject).get("name")}" }, { self, value -> receiver = self; (self as JsObject).defineOwn("seen", value) })
        val o = JsObject(base)
        o.defineOwn("name", "o")
        assertEquals("got from o", o.get("v"))
        assertTrue(o.set("v", 7.0))
        assertSame(o, receiver)
        assertEquals(7.0, o.get("seen"))
        assertFalse(o.hasOwn("v"))
    }

    @Test
    fun anAccessorWithoutASetterRefusesAssignmentAndOneWithoutAGetterReadsAsUndefined() {
        val o = JsObject()
        o.defineAccessor("ro", { _ -> 1.0 })
        o.defineAccessor("wo", null, { _, _ -> })
        assertFalse(o.set("ro", 2.0))
        assertSame(Undefined, o.get("wo"))
        assertTrue(o.getOwnProp("ro") is AccessorProp)
        assertTrue(o.set("wo", 1.0))
    }

    @Test
    fun deleteRespectsConfigurable() {
        val o = JsObject()
        o.defineOwn("a", 1.0, PropFlags.WRITABLE)
        o.defineOwn("b", 1.0)
        assertFalse(o.delete("a"))
        assertTrue(o.hasOwn("a"))
        assertTrue(o.delete("b"))
        assertTrue(o.delete("never there"))
        assertFalse(o.hasOwn("b"))
    }

    @Test
    fun aNonExtensibleObjectRefusesNewPropertiesButNotChangesToOldOnes() {
        val o = JsObject()
        o.defineOwn("a", 1.0)
        o.extensible = false
        assertFalse(o.set("b", 1.0))
        assertTrue(o.set("a", 2.0))
        assertEquals(2.0, o.get("a"))
    }

    @Test
    fun onlyEnumerablePropertiesAreEnumerated() {
        val o = JsObject()
        o.defineOwn("visible", 1.0)
        o.defineOwn("hidden", 1.0, PropFlags.HIDDEN)
        o.defineAccessor("acc", { _ -> 1.0 })
        assertEquals(listOf("visible", "hidden", "acc"), o.ownKeys())
        assertEquals(listOf("visible", "acc"), o.ownEnumerableKeys())
    }

    @Test
    fun arrayIndicesAreCanonicalIntegers() {
        assertEquals(0, arrayIndexOf("0"))
        assertEquals(12, arrayIndexOf("12"))
        assertEquals(-1, arrayIndexOf("01"))
        assertEquals(-1, arrayIndexOf("-1"))
        assertEquals(-1, arrayIndexOf("1.5"))
        assertEquals(-1, arrayIndexOf(""))
        assertEquals(-1, arrayIndexOf("4294967295"))
        assertEquals(-1, arrayIndexOf("x"))
    }

    @Test
    fun anArrayHasElementsAWritableLengthAndOrdinaryPropertiesToo() {
        val a = realm.newArray(listOf(1.0, 2.0, 3.0))
        assertEquals(3.0, a.get("length"))
        assertEquals(2.0, a.get("1"))
        assertSame(Undefined, a.get("3"))
        assertTrue(a.set("5", 6.0))
        assertEquals(6.0, a.get("length"))
        assertSame(Undefined, a.get("4"))
        a.set("length", 2.0)
        assertEquals(listOf<Any?>(1.0, 2.0), a.items)
        a.set("tag", "t")
        assertEquals(listOf("0", "1", "length", "tag"), a.ownKeys())
        assertEquals(listOf("0", "1", "tag"), a.ownEnumerableKeys())
        assertTrue(a.has("1"))
        assertFalse(a.has("2"))
        assertFalse(a.delete("length"))
        assertTrue(a.delete("0"))
        assertSame(Undefined, a.items[0])
        assertEquals(listOf<Any?>(Undefined, 2.0), a.items)
    }

    @Test
    fun anInvalidLengthIsARangeError() {
        val a = realm.newArray(emptyList())
        for (bad in listOf(-1.0, 1.5, Double.NaN, 4.0e9)) {
            val e = assertFailsWith<JsThrow>("length $bad") { a.set("length", bad) }
            assertEquals("RangeError: Invalid array length", e.message)
        }
        assertEquals(0, a.items.size)
    }

    @Test
    fun anArrayIteratesLiveAndAStringByCodePoints() {
        val a = realm.newArray(listOf(1.0, 2.0))
        val it = a.jsIterator()
        assertEquals(1.0, it.next())
        a.items.add(3.0)
        assertEquals(2.0, it.next())
        assertEquals(3.0, it.next())
        assertFalse(it.hasNext())
        assertEquals(listOf<Any?>("a", "\uD83D\uDE00", "b"), cosic.script.js.iteratorOf("a\uD83D\uDE00b")!!.asSequence().toList())
        assertNull(cosic.script.js.iteratorOf(5.0))
        assertNull(cosic.script.js.iteratorOf(realm.newObject()))
    }

    @Test
    fun aFunctionHasNameAndLengthThatAreReadOnlyAndNotEnumerable() {
        val f = realm.function("max", 2) { _, _ -> Undefined }
        assertEquals("max", f.get("name"))
        assertEquals(2.0, f.get("length"))
        assertEquals(2, (f as JsFn).arity)
        assertFalse(f.set("name", "other"))
        assertEquals(listOf("length", "name"), f.ownKeys())
        assertEquals(emptyList(), f.ownEnumerableKeys())
        assertTrue(f.set("extra", 1.0))
        assertEquals(listOf("extra"), f.ownEnumerableKeys())
        assertFalse(f.isConstructor)
        assertFailsWith<JsThrow> { f.construct(emptyList()) }
    }

    @Test
    fun aBoundFunctionKeepsThisAndTheLeadingArgumentsAndTheTargetsConstructor() {
        val target = realm.runRaw("return function (a, b, c) { return [this, a, b, c].join('|'); }") as JsFunction
        val bound = realm.runRaw("return function (a, b, c) { return [this, a, b, c].join('|'); }.bind('T', 1)") as JsFunction
        assertEquals("T|1|2|3", bound.call(Undefined, listOf(2.0, 3.0)))
        assertEquals("1|2|3", target.call(Undefined, listOf(1.0, 2.0, 3.0)).toString().removePrefix("|"))
        assertEquals("bound ", bound.name)
        assertEquals(2.0, bound.get("length"))
        assertTrue(bound.isConstructor)
        assertEquals(3.0, target.get("length"))
    }

    @Test
    fun conversionsCallValueOfAndToStringOfObjects() {
        val o = realm.runRaw("return { valueOf() { return 42; }, toString() { return 'str'; } }") as JsObject
        assertEquals(42.0, realm.toPrimitive(o))
        assertEquals("str", realm.toPrimitive(o, cosic.script.js.Hint.STRING))
        assertEquals(42.0, realm.toNumber(o))
        assertEquals("str", realm.toStr(o))
        assertTrue(realm.looseEquals(o, 42.0))
        assertTrue(realm.looseEquals("42", o))
        assertFalse(realm.looseEquals("str", o))
        assertFalse(realm.looseEquals(o, realm.newObject()))
        assertEquals("1,2,3", realm.toStr(realm.newArray(listOf(1.0, 2.0, 3.0))))
        assertEquals("[object Object]", realm.toStr(realm.newObject()))
        assertEquals(Double.NaN, realm.toNumber(realm.newObject()))
        assertEquals(0.0, realm.toNumber(realm.newArray(emptyList())))
    }

    @Test
    fun newErrorsAreErrorObjectsOfTheirClass() {
        val e = realm.newError(cosic.script.js.ErrorKind.RANGE, "too big")
        assertEquals("Error", e.className)
        assertEquals("too big", e.get("message"))
        assertEquals("RangeError", e.get("name"))
        assertSame(realm.errorPrototypes.getValue(cosic.script.js.ErrorKind.RANGE), e.prototype)
        assertSame(realm.errorPrototype, e.prototype!!.prototype)
        assertEquals("RangeError: too big", realm.toStr(e))
        assertEquals("RangeError: too big", realm.rangeError("too big").message)
    }
}
