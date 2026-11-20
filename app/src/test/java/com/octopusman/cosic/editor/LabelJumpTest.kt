package com.octopusman.cosic.editor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Alt-W and Alt-Q of Strudel's editor: the cursor moves to the next or the previous `$:` label (`jumpToCharacter`, labelJump.mjs). */
class LabelJumpTest {
    @Test
    fun forwardGoesToTheNextLabelAndLoopsAround() {
        assertEquals(1, LabelJump.jump("a\$b\$c", 0, 1))
        assertEquals(3, LabelJump.jump("a\$b\$c", 1, 1))
        assertEquals(1, LabelJump.jump("a\$b\$c", 3, 1), "the last label goes back around to the first")
        assertEquals(1, LabelJump.jump("a\$b\$c", 5, 1))
    }

    @Test
    fun backwardGoesToThePreviousLabelAndLoopsAround() {
        assertEquals(3, LabelJump.jump("a\$b\$c", 4, -1))
        assertEquals(1, LabelJump.jump("a\$b\$c", 3, -1))
        assertEquals(3, LabelJump.jump("a\$b\$c", 0, -1), "the first label goes back around to the last")
        assertEquals(3, LabelJump.jump("a\$b\$c", 1, -1))
    }

    @Test
    fun aCursorRightAfterALabelGoesBackToItBackwards() {
        // positions count the character after the label: the cursor at 2 is right after the `$` at 1
        assertEquals(1, LabelJump.jump("a\$b\$c", 2, -1))
    }

    @Test
    fun aTextWithoutLabelsHasNowhereToJumpTo() {
        assertNull(LabelJump.jump("s(\"bd\")", 3, 1))
        assertNull(LabelJump.jump("", 0, -1))
    }

    @Test
    fun directionDefaultsToForward() {
        assertEquals(1, LabelJump.jump("a\$b\$c", 0))
    }

    @Test
    fun aProgramOfLabelledPatterns() {
        val code = "\$: s(\"bd\")\n\n\$: s(\"hh*2\")\n\n\$: note(\"c e\")"
        val second = code.indexOf('$', 1)
        val third = code.indexOf('$', second + 1)
        assertEquals(0, LabelJump.jump(code, code.length, 1))
        assertEquals(second, LabelJump.jump(code, 0, 1))
        assertEquals(third, LabelJump.jump(code, second, 1))
        assertEquals(second, LabelJump.jump(code, third, -1))
        assertEquals(0, LabelJump.jump(code, second, -1))
    }
}
