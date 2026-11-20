package com.octopusman.cosic.visual

import kotlin.test.Test
import kotlin.test.assertEquals

class PrintCardTest {
    @Test
    fun capacityIsTheLinesThatFitAndAtLeastOne() {
        assertEquals(5, printCapacity(100f, 16f, 6f)) // 88 / 16 = 5.5
        assertEquals(1, printCapacity(10f, 16f, 6f))
        assertEquals(1, printCapacity(100f, 0f, 6f))
    }

    @Test
    fun theTailKeepsTheNewestLinesInOrder() {
        val lines = listOf("a", "b", "c", "d")
        assertEquals(listOf("c", "d"), printTail(lines, 2))
        assertEquals(lines, printTail(lines, 4))
        assertEquals(lines, printTail(lines, 9))
        assertEquals(emptyList(), printTail(emptyList(), 3))
    }
}
