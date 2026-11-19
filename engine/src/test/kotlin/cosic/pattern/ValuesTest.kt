package cosic.pattern

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ValuesTest {
    // ---- truthiness -------------------------------------------------------------------

    @Test
    fun numbersAreTruthyWhenNotZero() {
        assertTrue(truthy(1.0))
        assertTrue(truthy(-0.5))
        assertFalse(truthy(0.0))
    }

    @Test
    fun stringsAreTruthyUnlessFalseLike() {
        assertTrue(truthy("x"))
        assertTrue(truthy("t"))
        assertTrue(truthy("1"))
        assertFalse(truthy("f"))
        assertFalse(truthy("false"))
        assertFalse(truthy("0"))
    }

    @Test
    fun booleansAreThemselvesAndOtherValuesAreTruthy() {
        assertTrue(truthy(true))
        assertFalse(truthy(false))
        assertTrue(truthy(mapOf("s" to "bd")))
    }

    // ---- sounds and control maps ------------------------------------------------------

    @Test
    fun soundNamesWithANumericSuffixSetN() {
        assertEquals(mapOf<String, Any>("s" to "bd"), soundControl("bd"))
        assertEquals(mapOf<String, Any>("s" to "bd", "n" to 3.0), soundControl("bd:3"))
        assertEquals(mapOf<String, Any>("s" to "bd", "n" to 2.5), soundControl("bd:2.5"))
        assertEquals(mapOf<String, Any>("s" to "bd", "n" to -1.0), soundControl("bd:-1"))
        assertEquals(mapOf<String, Any>("s" to "a:b", "n" to 3.0), soundControl("a:b:3"))
    }

    @Test
    fun soundNamesWithoutANumericSuffixStayWhole() {
        assertEquals(mapOf<String, Any>("s" to "bd:x"), soundControl("bd:x"))
        assertEquals(mapOf<String, Any>("s" to ":3"), soundControl(":3"))
        assertEquals(mapOf<String, Any>("s" to "bd:"), soundControl("bd:"))
        assertEquals(mapOf<String, Any>("s" to "bd:1e3"), soundControl("bd:1e3"))
        assertEquals(mapOf<String, Any>("s" to "bd:NaN"), soundControl("bd:NaN"))
    }

    @Test
    fun valuesBecomeControlMaps() {
        val map = mapOf<String, Any>("gain" to 0.5)
        assertEquals(map, asControlMap(map))
        assertEquals(mapOf<String, Any>("note" to 60.0), asControlMap(60.0))
        assertEquals(mapOf<String, Any>("s" to "bd", "n" to 3.0), asControlMap("bd:3"))
        val e = assertFailsWith<CosicError> { asControlMap(true) }
        assertEquals("Expected sounds or notes, got 'true'", e.message)
    }

    @Test
    fun withControlsSetsEntriesOnEveryValue() {
        val p = fastcat(pure("bd"), pure(60.0)).withControls("pan" to 1.0, "gain" to 0.5)
        assertEquals(
            listOf(mapOf<String, Any>("s" to "bd", "pan" to 1.0, "gain" to 0.5), mapOf<String, Any>("note" to 60.0, "pan" to 1.0, "gain" to 0.5)),
            p.cycleValues(),
        )
    }

    // ---- arithmetic -------------------------------------------------------------------

    @Test
    fun numbersCombineAsUsual() {
        assertEquals(7.0, arith(ArithOp.ADD, 3.0, 4.0))
        assertEquals(-1.0, arith(ArithOp.SUB, 3.0, 4.0))
        assertEquals(12.0, arith(ArithOp.MUL, 3.0, 4.0))
        assertEquals(0.75, arith(ArithOp.DIV, 3.0, 4.0))
    }

    @Test
    fun noteNamesCountAsTheirMidiNumber() {
        assertEquals(60.0, arith(ArithOp.ADD, "c3", 12.0))
        assertEquals(4.0, arith(ArithOp.SUB, "e", "c"))
        assertEquals(96.0, arith(ArithOp.MUL, 2.0, "c3"))
    }

    @Test
    fun arithmeticNeedsNumbers() {
        assertEquals("'add' expects a number, got 'bd'", assertFailsWith<CosicError> { arith(ArithOp.ADD, "bd", 1.0) }.message)
        assertEquals("'mul' expects a number, got 'true'", assertFailsWith<CosicError> { arith(ArithOp.MUL, 2.0, true) }.message)
    }

    @Test
    fun aNumberChangesTheNoteOfAControlMapBeforeItsN() {
        val both = mapOf<String, Any>("note" to 48.0, "n" to 2.0)
        assertEquals(mapOf<String, Any>("note" to 60.0, "n" to 2.0), arith(ArithOp.ADD, both, 12.0))
        assertEquals(mapOf<String, Any>("n" to 6.0), arith(ArithOp.MUL, mapOf<String, Any>("n" to 2.0), 3.0))
        assertEquals(mapOf<String, Any>("note" to 90.0), arith(ArithOp.ADD, mapOf<String, Any>("note" to 48.0), "f#2"))
    }

    @Test
    fun aControlMapWithNothingNumericCannotBeChanged() {
        val s = mapOf<String, Any>("s" to "bd")
        assertEquals("add: nothing numeric to change", assertFailsWith<CosicError> { arith(ArithOp.ADD, s, 1.0) }.message)
        assertEquals("sub: nothing numeric to change", assertFailsWith<CosicError> { arith(ArithOp.SUB, s, 1.0) }.message)
        assertEquals("div: nothing numeric to change", assertFailsWith<CosicError> { arith(ArithOp.DIV, mapOf<String, Any>("gain" to 1.0), 2.0) }.message)
    }

    @Test
    fun twoControlMapsCombineSharedNumbersAndTakeTheRestFromTheRight() {
        val left = mapOf<String, Any>("n" to 2.0, "s" to "a", "gain" to 1.0)
        val right = mapOf<String, Any>("n" to 1.0, "s" to "b", "pan" to 0.5)
        assertEquals(mapOf<String, Any>("n" to 3.0, "s" to "b", "gain" to 1.0, "pan" to 0.5), arith(ArithOp.ADD, left, right))
        assertEquals(mapOf<String, Any>("note" to 48.0, "n" to 1.0), arith(ArithOp.ADD, mapOf<String, Any>("note" to 48.0), mapOf<String, Any>("n" to 1.0)))
    }

    @Test
    fun aNumberOnTheLeftOfAControlMapStartsFromANote() {
        assertEquals(mapOf<String, Any>("note" to 13.0), arith(ArithOp.ADD, 12.0, mapOf<String, Any>("note" to 1.0)))
        assertEquals(mapOf<String, Any>("note" to 12.0, "n" to 1.0), arith(ArithOp.ADD, 12.0, mapOf<String, Any>("n" to 1.0)))
    }
}
