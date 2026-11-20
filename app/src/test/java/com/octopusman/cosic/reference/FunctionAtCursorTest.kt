package com.octopusman.cosic.reference

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FunctionAtCursorTest {
    private val known = setOf("s", "fast", "every", "euclidRot", "bd", "setcpm")::contains

    private fun at(code: String, marker: String = "|"): String? {
        val cursor = code.indexOf(marker)
        return functionAtCursor(code.replace(marker, ""), cursor, known)
    }

    @Test
    fun cursorInsideAWordPicksThatWord() {
        assertEquals("fast", at("s(\"bd\").fa|st(2)"))
    }

    @Test
    fun cursorRightAfterAWordPicksThatWord() {
        assertEquals("fast", at("s(\"bd\").fast|(2)"))
    }

    @Test
    fun cursorRightBeforeAWordPicksThatWord() {
        assertEquals("fast", at("s(\"bd\").|fast(2)"))
    }

    @Test
    fun wordsWithDigitsAndCapitalsAreWholeWords() {
        assertEquals("euclidRot", at("s(\"bd\").eucl|idRot(3, 8, 1)"))
    }

    @Test
    fun aSoundNameInsideAMiniNotationStringIsPicked() {
        assertEquals("bd", at("s(\"hh b|d\")"))
    }

    @Test
    fun anArgumentFallsBackToTheCallItIsAnArgumentOf() {
        assertEquals("fast", at("s(\"bd\").fast(|2)"))
        assertEquals("fast", at("s(\"bd\").fast(2|)"))
    }

    @Test
    fun theInnermostKnownCallWins() {
        assertEquals("fast", at("s(\"bd\").every(4, fast(|2))"))
    }

    @Test
    fun anUnknownInnerCallFallsBackToAKnownOuterOne() {
        assertEquals("every", at("s(\"bd\").every(4, foo(|2))"))
    }

    @Test
    fun aClosedCallBeforeTheCursorIsNotAnEnclosingCall() {
        assertEquals("s", at("s(\"bd ~|\")"))
        assertNull(at("setx(1) |"))
    }

    @Test
    fun nothingKnownAroundTheCursorGivesNothing() {
        assertNull(at("// a comment|"))
        assertNull(at("|"))
        assertNull(at("foo(|1)"))
    }

    @Test
    fun theCursorPastTheEndOfTheTextIsHandled() {
        assertEquals("setcpm", functionAtCursor("setcpm", 6, known))
        assertNull(functionAtCursor("", 0, known))
    }
}
