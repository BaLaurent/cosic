package cosic.io.input

import cosic.io.InputState
import cosic.pattern.KeyboardState
import cosic.pattern._keyDown
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeyboardModelTest {
    @AfterTest
    fun leave() {
        KeyboardState.provider = null
    }

    @Test
    fun theAliasesAreStrudelsInItsOrder() {
        assertEquals(
            listOf("control" to "Control", "ctrl" to "Control", "alt" to "Alt", "shift" to "Shift", "down" to "ArrowDown", "up" to "ArrowUp", "left" to "ArrowLeft", "right" to "ArrowRight"),
            KeyboardModel.keyAlias.toList(),
        )
    }

    @Test
    fun withoutAKeyboardThereIsNoState() {
        assertNull(KeyboardModel.currentState())
    }

    @Test
    fun theStateIsACopyOfTheKeysHeld() {
        val inputs = InputState()
        inputs.bindToSignals()
        inputs.keyDown("Control")
        inputs.keyDown("j")
        val state = KeyboardModel.currentState()!!
        assertEquals(mapOf("Control" to true, "j" to true), state)
        inputs.keyUp("j")
        assertTrue(state.getValue("j"), "a copy does not change afterwards")
        assertFalse(KeyboardModel.currentState()!!.getValue("j"))
    }

    @Test
    fun controlAndJTogetherAreAChord() {
        val inputs = InputState()
        inputs.bindToSignals()
        inputs.keyDown("Control")
        assertFalse(_keyDown(listOf("Control", "j")))
        inputs.keyDown("j")
        assertTrue(_keyDown(listOf("Control", "j")))
        assertTrue(_keyDown(listOf("ctrl", "j")), "ctrl is Control")
    }
}
