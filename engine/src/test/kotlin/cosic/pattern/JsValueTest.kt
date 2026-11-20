package cosic.pattern

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The JavaScript value semantics of JsValue.kt (SIG Task C1 and the helpers of the kernel). */
class JsValueTest {
    private fun same(expected: Double, actual: Double) = assertEquals(expected.toRawBits(), actual.toRawBits(), "$actual should be $expected")

    @Test
    fun jsModStaysPositive() {
        assertEquals(2.0, jsMod(-1.0, 3.0))
        assertEquals(1.0, jsMod(-5.0, 3.0))
        assertEquals(0.33333333333333326, jsMod(1.0 / 3, 1.0))
    }

    @Test
    fun jsMathRoundRoundsHalvesUp() {
        assertEquals(3.0, jsMathRound(2.5))
        assertEquals(-2.0, jsMathRound(-2.5))
        assertEquals(0.0, jsMathRound(0.49999999999999994))
        same(-0.0, jsMathRound(-0.2))
        assertTrue(jsMathRound(Double.NaN).isNaN())
        assertEquals(Double.POSITIVE_INFINITY, jsMathRound(Double.POSITIVE_INFINITY))
    }

    @Test
    fun jsStringToNumberReadsNumericLiterals() {
        assertEquals(16.0, jsStringToNumber("0x10"))
        assertEquals(0.001, jsStringToNumber("1e-3"))
        assertEquals(0.5, jsStringToNumber(".5"))
        assertEquals(5.0, jsStringToNumber("5."))
        assertEquals(Double.POSITIVE_INFINITY, jsStringToNumber("Infinity"))
        assertEquals(Double.NEGATIVE_INFINITY, jsStringToNumber("-Infinity"))
        assertTrue(jsStringToNumber("1_000").isNaN())
        assertEquals(0.0, jsStringToNumber(""))
        assertEquals(7.0, jsStringToNumber("007"))
        assertTrue(jsStringToNumber("1.2.3").isNaN())
        assertEquals(5.0, jsStringToNumber("0b101"))
        assertEquals(15.0, jsStringToNumber("0o17"))
        assertEquals(12.0, jsStringToNumber("  12\n"))
        assertTrue(jsStringToNumber("-0x10").isNaN())
        assertTrue(jsStringToNumber("12px").isNaN())
    }

    @Test
    fun jsRotateAndSplitAtClampLikeSlice() {
        val xs = listOf(0, 1, 2, 3)
        assertEquals(listOf(2, 3, 0, 1), jsRotate(xs, 2.0))
        assertEquals(listOf(1, 2, 3, 0), jsRotate(xs, -3.0))
        assertEquals(listOf(3, 0, 1, 2), jsRotate(xs, 3.0))
        assertEquals(listOf(0), jsRotate(listOf(0), 3.0))
        assertEquals(emptyList(), jsRotate(emptyList<Int>(), 3.0))
        assertEquals(xs, jsRotate(xs, 9.0))
        assertEquals(listOf(0) to listOf(1, 2, 3), jsSplitAt(-3.0, xs))
        assertEquals(emptyList<Int>() to xs, jsSplitAt(0.0, xs))
    }

    @Test
    fun truthiness() {
        for (falsy in listOf(false, 0.0, -0.0, Double.NaN, "", null, Undefined)) assertFalse(jsTruthy(falsy), "$falsy")
        for (truthy in listOf(true, 1.0, -0.5, "0", "false", "f", listOf<Any?>(), emptyMap<String, Any?>(), Rational.ZERO)) {
            assertTrue(jsTruthy(truthy), "$truthy")
        }
    }

    @Test
    fun toNumberAndToString() {
        assertEquals(0.0, jsToNumber(null))
        assertTrue(jsToNumber(Undefined).isNaN())
        assertEquals(1.0, jsToNumber(true))
        assertEquals(0.0, jsToNumber(emptyList<Any?>()))
        assertEquals(5.0, jsToNumber(listOf("5")))
        assertTrue(jsToNumber(listOf(1.0, 2.0)).isNaN())
        assertTrue(jsToNumber(mapOf("a" to 1.0)).isNaN())
        assertEquals("1,,3", jsToString(listOf(1.0, null, 3.0)))
        assertEquals("1,2,3", jsToString(listOf(1.0, listOf(2.0, 3.0))))
        assertEquals("undefined", jsToString(Undefined))
        assertEquals("[object Object]", jsToString(mapOf("a" to 1.0)))
        assertEquals("0.(3)", jsToString(Rational(1, 3)))
    }

    @Test
    fun comparisons() {
        assertEquals(true, jsLess(1.0, 2.0))
        assertEquals(false, jsLess(2.0, 1.0))
        assertNull(jsLess(Double.NaN, 1.0))
        assertEquals(true, jsLess("a", "b"))
        assertEquals(false, jsLess("10", 9.0), "a string against a number compares as numbers")
        assertEquals(false, jsLess("9", "10"), "two strings compare as text")
        assertTrue(jsLooseEq(1.0, "1"))
        assertTrue(jsLooseEq(true, 1.0))
        assertTrue(jsLooseEq(null, Undefined))
        assertFalse(jsLooseEq(null, 0.0))
        assertTrue(jsLooseEq(listOf("a", "b"), "a,b"))
        assertFalse(jsStrictEq(1.0, "1"))
        assertFalse(jsStrictEq(Double.NaN, Double.NaN))
        assertTrue(jsStrictEq(0.0, -0.0))
        val list = listOf(1.0)
        assertTrue(jsStrictEq(list, list))
        assertFalse(jsStrictEq(listOf(1.0), listOf(1.0)))
    }

    @Test
    fun parseIntAndToInt32() {
        assertEquals(12.0, jsParseInt("12px"))
        assertEquals(31.0, jsParseInt("0x1f"))
        assertEquals(-7.0, jsParseInt(" -7"))
        assertTrue(jsParseInt("abc").isNaN())
        assertEquals(3.0, jsParseInt(3.9))
        assertEquals(5, jsToInt32(4294967301.0))
        assertEquals(Int.MIN_VALUE, jsToInt32(2147483648.0))
        assertEquals(0, jsToInt32(Double.NaN))
        assertEquals(-1, jsToInt32(-1.9))
    }

    @Test
    fun nanFallbackKeepsNumbersAndReplacesTheRest() {
        assertEquals("3", nanFallback("3"))
        assertEquals(0.0, nanFallback("x"))
        assertEquals(7.0, nanFallback(Double.NaN, 7.0))
    }

    @Test
    fun numbersPrintLikeJavaScript() {
        val vectors = listOf(
            0.5 to "0.5", 100.0 to "100", 1e21 to "1e+21", 1e-7 to "1e-7", 0.000001 to "0.000001",
            123456789012345680000.0 to "123456789012345680000", -0.0 to "0", 0.1 + 0.2 to "0.30000000000000004",
            1.0 / 3 to "0.3333333333333333", 0.1 to "0.1", 4.35 to "4.35", -1.5 to "-1.5", 123456.789 to "123456.789",
            1e15 to "1000000000000000", 1.2e21 to "1.2e+21", 2e-7 to "2e-7", 5e-324 to "5e-324",
            Double.MAX_VALUE to "1.7976931348623157e+308", Double.NaN to "NaN", Double.NEGATIVE_INFINITY to "-Infinity",
        )
        for ((number, text) in vectors) assertEquals(text, jsNumberToString(number), "jsNumberToString($number)")
    }

    @Test
    fun toFixedRoundsTheExactValueHalfUp() {
        assertEquals("0.99", jsToFixed(0.995, 2), "0.995 is a little below 0.995 as a double")
        assertEquals("0.300000000000", jsToFixed(0.30000000000000004, 12))
        assertEquals("-0.000000000000", jsToFixed(-0.0000000000004, 12))
        assertEquals("2.5", jsToFixed(2.5, 1))
    }

    @Test
    fun keyOrderPutsWholeNumberKeysFirst() {
        assertEquals(listOf("2", "10", "b", "a"), jsKeyOrder(listOf("b", "10", "a", "2")))
        assertTrue(isArrayIndex("0") && isArrayIndex("42") && !isArrayIndex("01") && !isArrayIndex("-1") && !isArrayIndex("4294967295"))
    }

    @Test
    fun typeofAndFunctions() {
        assertEquals("undefined", jsTypeof(Undefined))
        assertEquals("object", jsTypeof(null))
        assertEquals("number", jsTypeof(1.0))
        assertEquals("object", jsTypeof(listOf<Any?>()))
        assertEquals("function", jsTypeof(jsFn(1) { it[0] }))
        assertEquals("object", jsTypeof(pure(1.0)))
    }
}
