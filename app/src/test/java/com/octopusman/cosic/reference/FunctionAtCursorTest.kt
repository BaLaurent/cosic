package com.octopusman.cosic.reference

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FunctionAtCursorTest {
    private val known = setOf("s", "fast", "every", "euclidRot", "bd", "setcpm", "bank", "minor")::contains

    private fun at(code: String, marker: String = "|"): CursorHit? {
        val cursor = code.indexOf(marker)
        return functionAtCursor(code.replace(marker, ""), cursor, known)
    }

    private fun word(name: String) = CursorHit(name, insideCall = false)

    private fun call(name: String) = CursorHit(name, insideCall = true)

    @Test
    fun cursorInsideAWordPicksThatWord() {
        assertEquals(word("fast"), at("s(\"bd\").fa|st(2)"))
    }

    @Test
    fun cursorRightAfterAWordPicksThatWord() {
        assertEquals(word("fast"), at("s(\"bd\").fast|(2)"))
    }

    @Test
    fun cursorRightBeforeAWordPicksThatWord() {
        assertEquals(word("fast"), at("s(\"bd\").|fast(2)"))
    }

    @Test
    fun wordsWithDigitsAndCapitalsAreWholeWords() {
        assertEquals(word("euclidRot"), at("s(\"bd\").eucl|idRot(3, 8, 1)"))
    }

    @Test
    fun aSoundNameInsideAMiniNotationStringIsPicked() {
        assertEquals(word("bd"), at("s(\"hh b|d\")"))
    }

    @Test
    fun aScaleModeAfterItsRootIsPicked() {
        assertEquals(word("minor"), at("n(\"0 2\").scale(\"C:mi|nor\")"))
    }

    @Test
    fun anArgumentFallsBackToTheCallItIsAnArgumentOf() {
        assertEquals(call("fast"), at("s(\"bd\").fast(|2)"))
        assertEquals(call("fast"), at("s(\"bd\").fast(2|)"))
        assertEquals(call("bank"), at("s(\"kick\").bank(\"|\")"))
    }

    @Test
    fun theInnermostKnownCallWins() {
        assertEquals(call("fast"), at("s(\"bd\").every(4, fast(|2))"))
    }

    @Test
    fun anUnknownInnerCallFallsBackToAKnownOuterOne() {
        assertEquals(call("every"), at("s(\"bd\").every(4, foo(|2))"))
    }

    @Test
    fun aClosedCallBeforeTheCursorIsNotAnEnclosingCall() {
        assertEquals(call("s"), at("s(\"bd ~|\")"))
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
        assertEquals(word("setcpm"), functionAtCursor("setcpm", 6, known))
        assertNull(functionAtCursor("", 0, known))
    }
}
