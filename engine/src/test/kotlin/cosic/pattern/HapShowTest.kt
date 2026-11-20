package cosic.pattern

import cosic.SourceRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** `Hap.show` must print exactly what Strudel's `hap.show(true)` prints, character for character. */
class HapShowTest {
    private fun span(beginNum: Long, beginDen: Long, endNum: Long, endDen: Long) =
        TimeSpan(Rational(beginNum, beginDen), Rational(endNum, endDen))

    private fun show(hap: Hap) = hap.show(true)

    @Test
    fun aFullHapShowsItsControlMap() {
        val part = span(0, 1, 1, 2)
        assertEquals("[ 0/1 \u2192 1/2 | s:bd ]", show(Hap(part, part, linkedMapOf("s" to "bd"))))
    }

    @Test
    fun anEighthOfACycleShowsAsInTheFastExample() {
        val part = span(0, 1, 1, 8)
        assertEquals("[ 0/1 \u2192 1/8 | s:bd ]", show(Hap(part, part, linkedMapOf("s" to "bd"))))
        assertEquals("[ 0/1 \u2192 1/8 | s:bd pan:0 ]", show(Hap(part, part, linkedMapOf("s" to "bd", "pan" to 0.0))))
    }

    @Test
    fun aFragmentThatEndsTheWholeShowsTheOpeningArrowAndParentheses() {
        val hap = Hap(span(0, 1, 1, 1), span(1, 2, 1, 1), 1.0)
        assertEquals("[ 0/1 \u21DC (1/2 \u2192 1/1) | 1 ]", show(hap))
    }

    @Test
    fun aFragmentThatStartsTheWholeShowsTheClosingArrow() {
        val hap = Hap(span(1, 3, 5, 12), span(1, 3, 3, 8), linkedMapOf("note" to "Bb4", "s" to "saw"))
        assertEquals("[ (1/3 \u2192 3/8) \u21DD 5/12 | note:Bb4 s:saw ]", show(hap))
    }

    @Test
    fun aFragmentInTheMiddleShowsBothArrows() {
        val hap = Hap(span(0, 1, 1, 1), span(1, 4, 1, 2), "x")
        assertEquals("[ 0/1 \u21DC (1/4 \u2192 1/2) \u21DD 1/1 | x ]", show(hap))
    }

    @Test
    fun theSnapshotConfirmedFragmentVectors() {
        val note = linkedMapOf<String, Any?>("note" to "c3")
        assertEquals("[ -1/5 \u21DC (0/1 \u2192 1/5) | note:c3 ]", show(Hap(span(-1, 5, 1, 5), span(0, 1, 1, 5), note)))
        assertEquals("[ (4/5 \u2192 1/1) \u21DD 6/5 | note:c3 ]", show(Hap(span(4, 5, 6, 5), span(4, 5, 1, 1), note)))
        assertEquals("[ 4/5 \u21DC (1/1 \u2192 6/5) | note:c3 ]", show(Hap(span(4, 5, 6, 5), span(1, 1, 6, 5), note)))
    }

    @Test
    fun pureOverTwoCyclesShowsEachCycle() {
        assertEquals(
            listOf("[ 0/1 \u2192 1/1 | a ]", "[ 1/1 \u2192 2/1 | a ]"),
            pure("a").lines(0, 2),
        )
    }

    @Test
    fun aContinuousHapShowsTildeAndThePart() {
        assertEquals("[ ~0/1 \u2192 1/1 | 0.5 ]", show(Hap(null, span(0, 1, 1, 1), 0.5)))
    }

    @Test
    fun negativeAndZeroFractionsKeepTheirSign() {
        assertEquals("-1/2", Rational(-1, 2).show())
        assertEquals("0/1", Rational(0).show())
        assertEquals("3/1", Rational(3).show())
    }

    @Test
    fun numbersInAControlMapLoseTheirDecimalPoint() {
        assertEquals("s:hh n:0", stringifyValues(linkedMapOf("s" to "hh", "n" to 0.0), true))
        assertEquals("gain:0.5", stringifyValues(linkedMapOf("gain" to 0.5), true))
    }

    @Test
    fun nestedListsAndCommasInStringsBecomeSpaces() {
        val value = linkedMapOf("FX" to listOf(linkedMapOf("a" to 1.0), linkedMapOf("b" to "x,y")))
        assertEquals("FX:[{a:1} {b:x y}]", stringifyValues(value, true))
    }

    @Test
    fun anEmptyMapLeavesTwoSpacesBetweenTheBars() {
        val part = span(0, 1, 1, 1)
        assertEquals("[ 0/1 \u2192 1/1 |  ]", show(Hap(part, part, LinkedHashMap<String, Any?>())))
    }

    @Test
    fun plainValuesPrintAsJavaScriptStrings() {
        assertEquals("c3", stringifyValues("c3", true))
        assertEquals("1", stringifyValues(1.0, true))
        assertEquals("0.25", stringifyValues(0.25, true))
        assertEquals("true", stringifyValues(true, true))
        assertEquals("a b", stringifyValues(listOf("a", "b"), true))
        assertEquals("undefined", stringifyValues(Undefined, true))
        assertEquals("ul", stringifyValues(null, true))
    }

    @Test
    fun theFullFormKeepsTheBracketsAndQuotes() {
        val part = span(0, 1, 1, 1)
        assertEquals("""[ 0/1 → 1/1 | {"s":"bd","n":3} ]""", Hap(part, part, linkedMapOf("s" to "bd", "n" to 3.0)).show(false))
    }

    @Test
    fun mapKeysFollowJavaScriptOrder() {
        val value = linkedMapOf<String, Any?>("b" to 1.0, "10" to 2.0, "a" to 3.0, "2" to 4.0)
        assertEquals("""{"2":4,"10":2,"b":1,"a":3}""", jsonStringify(value))
        assertEquals("2:4 10:2 b:1 a:3", stringifyValues(value, true))
    }

    @Test
    fun jsonStringifyFollowsJsonStringify() {
        assertEquals("\"a\\\"b\\\\c\\n\\u0001\"", jsonStringify("a\"b\\c\n\u0001"))
        assertEquals("[1,null,null,\"x\"]", jsonStringify(listOf(1.0, Double.NaN, Double.POSITIVE_INFINITY, "x")))
        assertEquals("0", jsonStringify(-0.0))
        assertEquals("""{"a":null}""", jsonStringify(mapOf("a" to null)))
        assertEquals("\"\\ud800\"", jsonStringify("\uD800"))
        assertEquals("{}", jsonStringify(mapOf("a" to Undefined)))
        assertEquals("[null]", jsonStringify(listOf(Undefined)))
    }

    @Test
    fun aFractionCannotBeSerialised() {
        assertEquals("Do not know how to serialize a BigInt", assertFailsWith<CosicError> { jsonStringify(Rational(1, 2)) }.message)
    }

    @Test
    fun showWholeIsWhatLogPrints() {
        val whole = span(0, 1, 1, 1)
        assertEquals("0/1 \u2192 1/1: a", Hap(whole, whole, "a").showWhole(true))
        assertEquals("value:a note:c#", Hap(whole, whole, linkedMapOf("value" to "a", "note" to "c#")).showWhole(true).substringAfter(": "))
        assertEquals("~: 1", Hap(null, whole, 1.0).showWhole(true))
    }

    @Test
    fun ensureObjectValueSaysWhatToAppend() {
        val whole = span(0, 1, 1, 1)
        Hap(whole, whole, mapOf("s" to "bd")).ensureObjectValue()
        assertEquals(
            "expected hap.value to be an object, but got \"bd\". Hint: append .note() or .s() to the end",
            assertFailsWith<CosicError> { Hap(whole, whole, "bd").ensureObjectValue() }.message,
        )
    }

    // ---- sorting ----------------------------------------------------------------------

    @Test
    fun hapsAreSortedByPartThenWhole() {
        val late = Hap(span(1, 2, 1, 1), span(1, 2, 1, 1), "late")
        val early = Hap(span(0, 1, 1, 1), span(0, 1, 1, 1), "early")
        val shortOne = Hap(span(0, 1, 1, 1), span(0, 1, 1, 2), "short")
        assertEquals(listOf("short", "early", "late"), sortedByPart(listOf(late, early, shortOne)).map { it.value })
    }

    @Test
    fun theSortKeepsTheQueryOrderOfEqualHaps() {
        val a = Hap(span(0, 1, 1, 1), span(0, 1, 1, 1), "a")
        val b = Hap(span(0, 1, 1, 1), span(0, 1, 1, 1), "b")
        assertEquals(listOf("a", "b"), sortedByPart(listOf(a, b)).map { it.value })
        assertEquals(listOf("b", "a"), sortedByPart(listOf(b, a)).map { it.value })
    }

    @Test
    fun aHapWithoutAWholeBreaksTheSortOfTwoOrMoreHaps() {
        val continuous = Hap(null, span(0, 1, 1, 1), 0.5)
        val discrete = Hap(span(0, 1, 1, 1), span(0, 1, 1, 1), "x")
        assertFailsWith<CosicError> { sortedByPart(listOf(continuous, discrete)) }
        assertEquals(1, sortedByPart(listOf(continuous)).size)
        assertFailsWith<CosicError> { stack(saw, pure("a")).sortHapsByPart().queryArc(Rational.ZERO, Rational.ONE) }
    }

    @Test
    fun sortHapsByPartIsAPatternOperation() {
        val p = stack(fastcat("a", "b"), fastcat("c", "d", "e"))
        assertEquals(
            listOf("[ 0/1 \u2192 1/3 | c ]", "[ 0/1 \u2192 1/2 | a ]", "[ 1/3 \u2192 2/3 | d ]", "[ 1/2 \u2192 1/1 | b ]", "[ 2/3 \u2192 1/1 | e ]"),
            p.lines(0, 1),
        )
    }

    @Test
    fun locationsNeverShowUp() {
        assertEquals(listOf("[ 0/1 \u2192 1/1 | a ]"), pure("a").withLoc(SourceRange(1, 2)).lines(0, 1))
    }
}
