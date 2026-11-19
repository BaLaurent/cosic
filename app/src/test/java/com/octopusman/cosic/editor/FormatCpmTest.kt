package com.octopusman.cosic.editor

import kotlin.test.Test
import kotlin.test.assertEquals

class FormatCpmTest {
    @Test
    fun wholeTemposHaveNoDecimals() {
        assertEquals("30", formatCpm(0.5))
        assertEquals("26", formatCpm(26.0 / 60.0))
        assertEquals("120", formatCpm(2.0))
    }

    @Test
    fun otherTemposShowOneDecimal() {
        assertEquals("27.5", formatCpm(27.5 / 60.0))
        assertEquals("0.6", formatCpm(0.6 / 60.0))
    }
}
