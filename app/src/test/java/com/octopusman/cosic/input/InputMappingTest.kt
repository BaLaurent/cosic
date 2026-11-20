package com.octopusman.cosic.input

import android.hardware.Sensor
import android.view.KeyEvent
import android.view.MotionEvent
import cosic.io.InputState
import cosic.io.MotionChannel
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KeyMapTest {
    @Test
    fun modifiersAndNamedKeysUseTheBrowsersNames() {
        assertEquals("Control", KeyMap.w3cKey(KeyEvent.KEYCODE_CTRL_LEFT, 0))
        assertEquals("Control", KeyMap.w3cKey(KeyEvent.KEYCODE_CTRL_RIGHT, 0))
        assertEquals("Alt", KeyMap.w3cKey(KeyEvent.KEYCODE_ALT_LEFT, 0))
        assertEquals("Shift", KeyMap.w3cKey(KeyEvent.KEYCODE_SHIFT_RIGHT, 0))
        assertEquals("Meta", KeyMap.w3cKey(KeyEvent.KEYCODE_META_LEFT, 0))
        assertEquals("ArrowUp", KeyMap.w3cKey(KeyEvent.KEYCODE_DPAD_UP, 0))
        assertEquals("ArrowDown", KeyMap.w3cKey(KeyEvent.KEYCODE_DPAD_DOWN, 0))
        assertEquals("ArrowLeft", KeyMap.w3cKey(KeyEvent.KEYCODE_DPAD_LEFT, 0))
        assertEquals("ArrowRight", KeyMap.w3cKey(KeyEvent.KEYCODE_DPAD_RIGHT, 0))
        assertEquals("Enter", KeyMap.w3cKey(KeyEvent.KEYCODE_ENTER, 10))
        assertEquals(" ", KeyMap.w3cKey(KeyEvent.KEYCODE_SPACE, 32))
        assertEquals("Escape", KeyMap.w3cKey(KeyEvent.KEYCODE_ESCAPE, 0))
        assertEquals("Tab", KeyMap.w3cKey(KeyEvent.KEYCODE_TAB, 9))
        assertEquals("Backspace", KeyMap.w3cKey(KeyEvent.KEYCODE_DEL, 0))
        assertEquals("F5", KeyMap.w3cKey(KeyEvent.KEYCODE_F5, 0))
    }

    @Test
    fun charactersAreTheKeyThatIsTyped() {
        assertEquals("j", KeyMap.w3cKey(KeyEvent.KEYCODE_J, 'j'.code))
        assertEquals("J", KeyMap.w3cKey(KeyEvent.KEYCODE_J, 'J'.code))
        assertEquals("é", KeyMap.w3cKey(KeyEvent.KEYCODE_E, 'é'.code))
        assertEquals("j", KeyMap.w3cKey(KeyEvent.KEYCODE_J, 0), "a letter that types nothing under a modifier is still the letter")
        assertEquals("7", KeyMap.w3cKey(KeyEvent.KEYCODE_7, 0))
    }

    @Test
    fun keysWithoutAnameAreIgnored() {
        assertNull(KeyMap.w3cKey(KeyEvent.KEYCODE_VOLUME_UP, 0))
        assertNull(KeyMap.w3cKey(KeyEvent.KEYCODE_E, Int.MIN_VALUE or 0x301), "a dead key's accent")
    }
}

class PadStateTest {
    private fun PadState.axesOf() = toRaw().axes.toList()

    @Test
    fun sticksAndTriggers() {
        val pad = PadState()
        pad.setAxis(MotionEvent.AXIS_X, -1f)
        pad.setAxis(MotionEvent.AXIS_Y, 0.5f)
        pad.setAxis(MotionEvent.AXIS_Z, 1f)
        pad.setAxis(MotionEvent.AXIS_RZ, -0.25f)
        assertEquals(listOf(-1.0, 0.5, 1.0, -0.25), pad.axesOf())
        pad.setAxis(MotionEvent.AXIS_LTRIGGER, 0.75f)
        pad.setAxis(MotionEvent.AXIS_GAS, 1f)
        assertEquals(0.75, pad.toRaw().buttons[6])
        assertEquals(1.0, pad.toRaw().buttons[7])
    }

    @Test
    fun theRightStickMayBeOnRxAndRy() {
        val pad = PadState(MotionEvent.AXIS_RX, MotionEvent.AXIS_RY)
        pad.setAxis(MotionEvent.AXIS_RX, 0.5f)
        pad.setAxis(MotionEvent.AXIS_RY, -0.5f)
        pad.setAxis(MotionEvent.AXIS_Z, 1f)
        assertEquals(listOf(0.0, 0.0, 0.5, -0.5), pad.axesOf())
        assertFalse(pad.setAxis(MotionEvent.AXIS_WHEEL, 1f), "not a controller axis")
    }

    @Test
    fun faceButtonsFollowTheStandardLayout() {
        val pad = PadState()
        val layout = mapOf(
            KeyEvent.KEYCODE_BUTTON_A to 0, KeyEvent.KEYCODE_BUTTON_B to 1, KeyEvent.KEYCODE_BUTTON_X to 2, KeyEvent.KEYCODE_BUTTON_Y to 3,
            KeyEvent.KEYCODE_BUTTON_L1 to 4, KeyEvent.KEYCODE_BUTTON_R1 to 5, KeyEvent.KEYCODE_BUTTON_SELECT to 8, KeyEvent.KEYCODE_BUTTON_START to 9,
            KeyEvent.KEYCODE_BUTTON_THUMBL to 10, KeyEvent.KEYCODE_BUTTON_THUMBR to 11,
        )
        for ((key, index) in layout) {
            assertTrue(pad.setKey(key, true))
            assertEquals(1.0, pad.toRaw().buttons[index], "down $key")
            assertEquals(1, pad.toRaw().buttons.count { it == 1.0 })
            pad.setKey(key, false)
            assertEquals(0, pad.toRaw().buttons.count { it == 1.0 })
        }
        assertFalse(pad.setKey(KeyEvent.KEYCODE_A, true), "a letter key is not a controller button")
    }

    @Test
    fun theDpadComesFromTheHatOrFromKeys() {
        val pad = PadState()
        pad.setAxis(MotionEvent.AXIS_HAT_X, -1f)
        assertEquals(1.0, pad.toRaw().buttons[14])
        pad.setAxis(MotionEvent.AXIS_HAT_X, 1f)
        assertEquals(0.0, pad.toRaw().buttons[14])
        assertEquals(1.0, pad.toRaw().buttons[15])
        pad.setAxis(MotionEvent.AXIS_HAT_Y, -1f)
        assertEquals(1.0, pad.toRaw().buttons[12])
        pad.setAxis(MotionEvent.AXIS_HAT_Y, 0f)
        assertEquals(0.0, pad.toRaw().buttons[12])
        pad.setKey(KeyEvent.KEYCODE_DPAD_DOWN, true)
        assertEquals(1.0, pad.toRaw().buttons[13])
        pad.setKey(KeyEvent.KEYCODE_DPAD_DOWN, false)
        assertEquals(0.0, pad.toRaw().buttons[13])
        pad.setKey(KeyEvent.KEYCODE_DPAD_LEFT, true)
        pad.setAxis(MotionEvent.AXIS_HAT_X, 0f)
        assertEquals(1.0, pad.toRaw().buttons[14], "the key keeps it down while the hat lets go")
    }

    @Test
    fun theRawPadHasFourAxesAndSixteenButtons() {
        val raw = PadState().toRaw()
        assertEquals(4, raw.axes.size)
        assertEquals(16, raw.buttons.size)
    }
}

class SensorMapperTest {
    private fun near(expected: Double, actual: Double, eps: Double = 1e-5) = assertTrue(abs(expected - actual) <= eps, "expected $expected but was $actual")

    @Test
    fun linearAccelerationAndGravityKeepTheirUnits() {
        val inputs = InputState()
        SensorMapper.apply(Sensor.TYPE_LINEAR_ACCELERATION, floatArrayOf(1f, -2f, 3f), inputs)
        assertEquals(1.0, inputs.motion(MotionChannel.ACC_X))
        assertEquals(-2.0, inputs.motion(MotionChannel.ACC_Y))
        assertEquals(3.0, inputs.motion(MotionChannel.ACC_Z))
        SensorMapper.apply(Sensor.TYPE_ACCELEROMETER, floatArrayOf(0f, 0f, 9.81f), inputs)
        near(9.81, inputs.motion(MotionChannel.GRAV_Z))
        assertTrue(inputs.hasMotion(MotionChannel.GRAV_X))
        assertFalse(inputs.hasMotion(MotionChannel.ROT_ALPHA))
    }

    @Test
    fun theGyroscopeIsInDegreesPerSecondAboutZXAndY() {
        val inputs = InputState()
        SensorMapper.apply(Sensor.TYPE_GYROSCOPE, floatArrayOf(Math.PI.toFloat() / 2, Math.PI.toFloat(), Math.PI.toFloat() / 4), inputs)
        near(90.0, inputs.motion(MotionChannel.ROT_BETA), 1e-4)
        near(180.0, inputs.motion(MotionChannel.ROT_GAMMA), 1e-4)
        near(45.0, inputs.motion(MotionChannel.ROT_ALPHA), 1e-4)
    }

    @Test
    fun theRotationVectorOfNoRotationIsTheIdentity() {
        val m = SensorMapper.rotationMatrix(floatArrayOf(0f, 0f, 0f, 1f))
        assertEquals(listOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0), m.toList())
        val noW = SensorMapper.rotationMatrix(floatArrayOf(0f, 0f, 0f))
        assertEquals(m.toList(), noW.toList(), "w is made up when the sensor leaves it out")
    }

    @Test
    fun aQuarterTurnAboutZIsAnAlphaOf90() {
        val inputs = InputState()
        val half = Math.sin(Math.PI / 4).toFloat() // a rotation of 90 degrees about z
        SensorMapper.apply(Sensor.TYPE_GAME_ROTATION_VECTOR, floatArrayOf(0f, 0f, half, half), inputs)
        near(90.0, inputs.motion(MotionChannel.ORI_ALPHA), 1e-3)
        near(0.0, inputs.motion(MotionChannel.ORI_BETA), 1e-3)
        near(0.0, inputs.motion(MotionChannel.ORI_GAMMA), 1e-3)
        assertFalse(inputs.hasMotion(MotionChannel.ABS_ALPHA))
    }

    @Test
    fun theRotationVectorIsTheAbsoluteOrientationAndSetsTheRelativeOneWhenThereIsNoGameVector() {
        val a = InputState()
        SensorMapper.apply(Sensor.TYPE_ROTATION_VECTOR, floatArrayOf(0f, 0f, 0f, 1f), a)
        assertTrue(a.hasMotion(MotionChannel.ABS_ALPHA))
        assertFalse(a.hasMotion(MotionChannel.ORI_ALPHA))
        val b = InputState()
        SensorMapper.apply(Sensor.TYPE_ROTATION_VECTOR, floatArrayOf(0f, 0f, 0f, 1f), b, orientationToo = true)
        assertTrue(b.hasMotion(MotionChannel.ORI_ALPHA))
    }

    @Test
    fun otherSensorsAreIgnored() {
        val inputs = InputState()
        SensorMapper.apply(Sensor.TYPE_LIGHT, floatArrayOf(10f), inputs)
        SensorMapper.apply(Sensor.TYPE_LINEAR_ACCELERATION, floatArrayOf(1f), inputs)
        for (c in MotionChannel.entries) assertFalse(inputs.hasMotion(c))
    }
}

class PointerFractionTest {
    @Test
    fun thePositionIsAFractionOfTheExtent() {
        assertEquals(0.5, pointerFraction(540f, 1080))
        assertEquals(1.25, pointerFraction(1350f, 1080), "not clamped")
        assertEquals(-0.5, pointerFraction(-540f, 1080))
        assertNull(pointerFraction(10f, 0))
    }
}
