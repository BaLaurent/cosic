package com.octopusman.cosic.editor

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InsertionMapTest {
    private val nbsp9 = "\u00A0".repeat(9)

    private fun InsertionMap.o2t(vararg offsets: Int) = offsets.map { originalToTransformed(it) }

    private fun InsertionMap.t2o(vararg offsets: Int) = offsets.map { transformedToOriginal(it) }

    @Test
    fun blockRowsAreInsertedAtTheEndOfTheCallAndTheCaretStaysInFrontOfThem() {
        val map = InsertionMap.of(3, listOf(Insertion(1, "\n\n\n", false, "b")))
        assertEquals("a\n\n\n\nb", map.apply("a\nb"))
        assertEquals(listOf(0, 1, 5, 6), map.o2t(0, 1, 2, 3))
        assertEquals(listOf(0, 1, 1, 1, 1, 2, 3), map.t2o(0, 1, 2, 3, 4, 5, 6))
        assertEquals(1, map.transformedStart(0))
        assertEquals(4, map.transformedEnd(0))
        assertEquals(6, map.transformedLength)
    }

    @Test
    fun aSliderGapIsInsertedInFrontOfTheNumberAndTheCaretGoesBehindIt() {
        val map = InsertionMap.of(11, listOf(Insertion(7, nbsp9, true, "s")))
        assertEquals(20, map.transformedLength)
        assertEquals(listOf(6, 16, 17, 20), map.o2t(6, 7, 8, 11))
        assertEquals(List(10) { 7 }, map.t2o(*IntArray(10) { 7 + it }))
        assertEquals(8, map.transformedToOriginal(17))
        assertEquals(16, map.spanStart(7))
        assertEquals(7, map.spanEnd(7))
        assertEquals(17, map.spanStart(8))
        assertEquals(17, map.spanEnd(8))
    }

    @Test
    fun twoInsertionsAtOnePlaceKeepTheOneTheCaretGoesBehindFirst() {
        val map = InsertionMap.of(
            6,
            listOf(Insertion(3, "\n\n", false, "b"), Insertion(3, "XX", true, "g")), // given in the other order
        )
        assertEquals("abcXX\n\ndef", map.apply("abcdef"))
        assertEquals(5, map.originalToTransformed(3))
        assertEquals(8, map.originalToTransformed(4))
        assertEquals(10, map.originalToTransformed(6))
        assertEquals(listOf(0, 1, 2, 3, 3, 3, 3, 3, 4, 5, 6), map.t2o(*IntArray(11) { it }))
        assertEquals(0, map.indexOf("g"))
        assertEquals(1, map.indexOf("b"))
        assertEquals(-1, map.indexOf("nothing"))
    }

    @Test
    fun anEmptyMapIsTheIdentity() {
        val map = InsertionMap.of(5, emptyList())
        assertEquals("hello", map.apply("hello"))
        for (o in 0..5) {
            assertEquals(o, map.originalToTransformed(o))
            assertEquals(o, map.transformedToOriginal(o))
            assertEquals(o, map.spanStart(o))
            assertEquals(o, map.spanEnd(o))
        }
    }

    @Test
    fun everyOffsetIsClampedAndAPlaceBeyondTheEndIsClamped() {
        val map = InsertionMap.of(4, listOf(Insertion(99, "\n\n", false, "late"), Insertion(-5, "<", true, "early"), Insertion(2, "", true, "empty")))
        assertEquals(2, map.size)
        assertEquals("<abcd\n\n", map.apply("abcd"))
        assertEquals(0, map.transformedToOriginal(-3))
        assertEquals(4, map.transformedToOriginal(100))
        assertEquals(map.transformedLength, map.originalToTransformed(100) + 2)
        assertEquals(1, map.originalToTransformed(-1))
    }

    @Test
    fun applyRefusesTextOfAnotherLength() {
        val map = InsertionMap.of(3, listOf(Insertion(1, "\n", false, "b")))
        kotlin.test.assertFailsWith<IllegalArgumentException> { map.apply("abcd") }
    }

    @Test
    fun theMapsAreMonotoneAndRoundTripForRandomInsertions() {
        val random = Random(42)
        repeat(200) {
            val length = random.nextInt(0, 60)
            val insertions = List(random.nextInt(0, 8)) { i ->
                Insertion(random.nextInt(-2, length + 3), "\n".repeat(random.nextInt(0, 6)), random.nextBoolean(), "k$i")
            }
            val map = InsertionMap.of(length, insertions)
            var previous = -1
            for (o in 0..length) {
                val t = map.originalToTransformed(o)
                assertTrue(t >= previous, "originalToTransformed is not monotone at $o")
                assertTrue(t in 0..map.transformedLength)
                assertEquals(o, map.transformedToOriginal(t), "round trip at $o")
                previous = t
            }
            previous = -1
            for (t in 0..map.transformedLength) {
                val o = map.transformedToOriginal(t)
                assertTrue(o >= previous, "transformedToOriginal is not monotone at $t")
                assertTrue(o in 0..length)
                previous = o
            }
            // the shown text has exactly the length the map says
            assertEquals(map.transformedLength, map.apply("x".repeat(length)).length)
        }
    }
}
