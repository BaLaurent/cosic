package cosic.io

import cosic.pattern.Rational
import cosic.pattern.KeyboardState
import cosic.pattern.PointerState
import cosic.script.evaluateProgram
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** What the person playing does to the device, and how the signals of the kernel come to read it. */
class InputStateTest {
    @AfterTest
    fun leaveTheSignalsAsTheyWere() {
        PointerState.x = 0.0
        PointerState.y = 0.0
        KeyboardState.provider = null
    }

    @Test
    fun everythingIsZeroAtFirst() {
        val inputs = InputState()
        assertEquals(0.0, inputs.mouseX)
        assertEquals(0.0, inputs.mouseY)
        assertFalse(inputs.isDown("Control"))
        for (channel in MotionChannel.entries) assertEquals(0.0, inputs.motion(channel), channel.name)
        assertNull(inputs.sliders.get("slider_0"))
    }

    @Test
    fun keysAreHeldBetweenDownAndUp() {
        val inputs = InputState()
        inputs.keyDown("Control")
        inputs.keyDown("j")
        assertTrue(inputs.isDown("Control"))
        assertTrue(inputs.isDown("j"))
        assertFalse(inputs.isDown("k"))
        inputs.keyUp("j")
        assertTrue(inputs.isDown("Control"))
        assertFalse(inputs.isDown("j"))
    }

    @Test
    fun theMouseIsNotClamped() {
        val inputs = InputState()
        inputs.mouseX = 1.5
        inputs.mouseY = -0.25
        assertEquals(1.5, inputs.mouseX)
        assertEquals(-0.25, inputs.mouseY)
    }

    @Test
    fun everyMotionChannelHoldsItsOwnValue() {
        val inputs = InputState()
        for (channel in MotionChannel.entries) inputs.setMotion(channel, channel.ordinal + 0.5)
        for (channel in MotionChannel.entries) assertEquals(channel.ordinal + 0.5, inputs.motion(channel), channel.name)
        assertEquals(15, MotionChannel.entries.size)
    }

    @Test
    fun slidersKeepWhatIsSet() {
        val inputs = InputState()
        inputs.sliders.set("slider_23", 0.75)
        assertEquals(0.75, inputs.sliders.get("slider_23"))
        inputs.sliders.set("slider_23", 0.25)
        assertEquals(0.25, inputs.sliders.get("slider_23"))
    }

    @Test
    fun theSignalsDoNotSeeAStateThatWasNotBoundToThem() {
        val inputs = InputState()
        inputs.mouseX = 0.4
        inputs.keyDown("Control")
        assertEquals(0.0, PointerState.x)
        assertNull(KeyboardState.provider)
    }

    @Test
    fun aBoundStateFeedsThePointerAndKeyboardSignals() {
        val inputs = InputState()
        inputs.mouseX = 0.2 // set before it was bound: it is passed on when it is
        inputs.bindToSignals()
        assertEquals(0.2, PointerState.x)
        inputs.mouseX = 0.3
        inputs.mouseY = 0.9
        assertEquals(0.3, PointerState.x)
        assertEquals(0.9, PointerState.y)

        val mouse = evaluateProgram("""n(mousex.segment(1))""").pattern.queryArc(Rational.ZERO, Rational.ONE).single()
        assertEquals("[ 0/1 → 1/1 | n:0.3 ]", mouse.show(true))

        inputs.keyDown("Control")
        val keyboard = KeyboardState.provider!!()
        assertEquals(true, keyboard["Control"])
        val held = evaluateProgram("""keyDown("ctrl")""").pattern.queryArc(Rational.ZERO, Rational.ONE).single()
        assertEquals("[ 0/1 → 1/1 | true ]", held.show(true))
        inputs.keyUp("Control")
        val released = evaluateProgram("""keyDown("ctrl")""").pattern.queryArc(Rational.ZERO, Rational.ONE).single()
        assertEquals("[ 0/1 → 1/1 | false ]", released.show(true))
    }

    @Test
    fun unbindingLeavesTheSignalsAsAProcessWithoutInputHasThem() {
        val inputs = InputState()
        inputs.bindToSignals()
        inputs.mouseX = 0.6
        inputs.unbindFromSignals()
        assertEquals(0.0, PointerState.x)
        assertNull(KeyboardState.provider)
        inputs.mouseX = 0.7
        assertEquals(0.0, PointerState.x, "an unbound state does not move the pointer")
    }

    @Test
    fun aStateThatDidNotBindCannotUnbindTheOneThatDid() {
        val bound = InputState()
        val other = InputState()
        bound.bindToSignals()
        val provider = KeyboardState.provider
        other.unbindFromSignals()
        assertSame(provider, KeyboardState.provider)
    }
}
