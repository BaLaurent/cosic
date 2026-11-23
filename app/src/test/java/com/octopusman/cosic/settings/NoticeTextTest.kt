package com.octopusman.cosic.settings

import kotlin.test.Test
import kotlin.test.assertEquals

class NoticeTextTest {
    private val long1 = "a".repeat(70)
    private val long2 = "b".repeat(70)
    private val long3 = "c".repeat(70)

    @Test
    fun aWrappedParagraphBecomesOneLine() {
        assertEquals("$long1 $long2 $long3 end.", reflowNotices("$long1\n$long2\n$long3\nend."))
    }

    @Test
    fun theLeadingWhitespaceOfAJoinedLineIsDropped() {
        assertEquals("$long1 next", reflowNotices("$long1\n      next"))
    }

    @Test
    fun aListItemAfterALongLineIsNotJoined() {
        val text = "$long1\n- item\n$long1\n* star\n$long1\n2. second\n$long1\na) letter"
        assertEquals(text, reflowNotices(text))
    }

    @Test
    fun aShortLineDoesNotAbsorbTheNextOne() {
        val text = "Gossner and contributors.\n  License: CC0"
        assertEquals(text, reflowNotices(text))
    }

    @Test
    fun theEndOfAJoinedParagraphDoesNotSwallowTheNextLine() {
        assertEquals("$long1 end.\n  License: CC0", reflowNotices("$long1\nend.\n  License: CC0"))
    }

    @Test
    fun blankAndRuleLinesAreKept() {
        val text = "$long1\n\n$long2\n=====\n$long3\n-------\nTitle\n-----"
        assertEquals(text, reflowNotices(text))
    }
}
