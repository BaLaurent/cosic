package com.octopusman.cosic.input

import android.content.Context
import android.hardware.input.InputManager
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import cosic.io.input.GamepadDevices
import cosic.io.input.RawPad

/**
 * The state of one game controller in the shape the Gamepad API of a browser gives it, which `gamepad(index)` reads: four axes (left stick x/y,
 * right stick x/y, each -1..1) and 16 buttons (0..1) in the standard layout: A B X Y, bumpers, triggers, back, start, stick clicks, d-pad.
 * It knows nothing of the device: events are fed in as numbers, so the mapping can be checked without one.
 *
 * [rightX] and [rightY] are the axes the controller's right stick is on (`AXIS_Z` and `AXIS_RZ` on most, `AXIS_RX` and `AXIS_RY` on the others).
 */
class PadState(private val rightX: Int = MotionEvent.AXIS_Z, private val rightY: Int = MotionEvent.AXIS_RZ) {
    private val axes = DoubleArray(4)
    private val buttons = DoubleArray(16)
    private var hatX = 0.0
    private var hatY = 0.0
    private val dpadKeys = BooleanArray(4) // up, down, left, right

    /** Notes the new [value] of the motion axis [axis]. Returns whether it is an axis of a controller. */
    fun setAxis(axis: Int, value: Float): Boolean {
        val v = value.toDouble()
        when (axis) {
            MotionEvent.AXIS_X -> axes[0] = v
            MotionEvent.AXIS_Y -> axes[1] = v
            rightX -> axes[2] = v
            rightY -> axes[3] = v
            MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_BRAKE -> buttons[6] = v
            MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_GAS -> buttons[7] = v
            MotionEvent.AXIS_HAT_X -> hatX = v
            MotionEvent.AXIS_HAT_Y -> hatY = v
            else -> return false
        }
        refreshDpad()
        return true
    }

    /** Notes that the button [keyCode] went down or up. Returns whether it is a button of a controller. */
    fun setKey(keyCode: Int, down: Boolean): Boolean {
        val v = if (down) 1.0 else 0.0
        val index = BUTTON_OF_KEY[keyCode]
        if (index != null) {
            buttons[index] = v
            return true
        }
        val dpad = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> 0
            KeyEvent.KEYCODE_DPAD_DOWN -> 1
            KeyEvent.KEYCODE_DPAD_LEFT -> 2
            KeyEvent.KEYCODE_DPAD_RIGHT -> 3
            else -> return false
        }
        dpadKeys[dpad] = down
        refreshDpad()
        return true
    }

    /** The d-pad buttons are down while the hat or the d-pad key says so. */
    private fun refreshDpad() {
        buttons[12] = if (hatY < 0 || dpadKeys[0]) 1.0 else 0.0
        buttons[13] = if (hatY > 0 || dpadKeys[1]) 1.0 else 0.0
        buttons[14] = if (hatX < 0 || dpadKeys[2]) 1.0 else 0.0
        buttons[15] = if (hatX > 0 || dpadKeys[3]) 1.0 else 0.0
    }

    /** What the controller reads now. */
    fun toRaw(): RawPad = RawPad(axes.copyOf(), buttons.copyOf())

    companion object {
        /** The button of the standard layout a controller key stands for; the d-pad keys and the triggers' keys are handled apart. */
        private val BUTTON_OF_KEY: Map<Int, Int> = mapOf(
            KeyEvent.KEYCODE_BUTTON_A to 0, KeyEvent.KEYCODE_BUTTON_B to 1, KeyEvent.KEYCODE_BUTTON_X to 2, KeyEvent.KEYCODE_BUTTON_Y to 3,
            KeyEvent.KEYCODE_BUTTON_L1 to 4, KeyEvent.KEYCODE_BUTTON_R1 to 5, KeyEvent.KEYCODE_BUTTON_L2 to 6, KeyEvent.KEYCODE_BUTTON_R2 to 7,
            KeyEvent.KEYCODE_BUTTON_SELECT to 8, KeyEvent.KEYCODE_BUTTON_START to 9, KeyEvent.KEYCODE_BUTTON_THUMBL to 10, KeyEvent.KEYCODE_BUTTON_THUMBR to 11,
        )

        /** The motion axes of a controller that are read. */
        val AXES = intArrayOf(
            MotionEvent.AXIS_X, MotionEvent.AXIS_Y, MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ, MotionEvent.AXIS_RX, MotionEvent.AXIS_RY,
            MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_BRAKE, MotionEvent.AXIS_GAS,
            MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y,
        )
    }
}

/**
 * The game controllers of the device, published as `navigator.getGamepads()` would list them: the n-th connected controller is index n
 * ([GamepadDevices]). The activity hands it the key and motion events it gets; while the hub is [enabled] (a program that called `gamepad()`
 * is playing) it takes the events of controllers, and only those, so the keyboard and the touch screen keep working as they do.
 */
class GamepadHub(context: Context) {
    private val inputManager = context.applicationContext.getSystemService(Context.INPUT_SERVICE) as InputManager
    private val states = HashMap<Int, PadState>()

    @Volatile
    var enabled = false
        private set

    private val deviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = publish()

        override fun onInputDeviceRemoved(deviceId: Int) {
            synchronized(states) { states.remove(deviceId) }
            publish()
        }

        override fun onInputDeviceChanged(deviceId: Int) = publish()
    }

    /** Starts taking controller events (and watching controllers come and go), or stops and forgets them all. */
    @Synchronized
    fun setEnabled(on: Boolean) {
        if (on == enabled) return
        enabled = on
        if (on) {
            inputManager.registerInputDeviceListener(deviceListener, null)
            publish()
        } else {
            inputManager.unregisterInputDeviceListener(deviceListener)
            synchronized(states) { states.clear() }
            GamepadDevices.clear()
            published = 0
        }
    }

    /** Takes [event] when it is a stick or trigger movement of a controller and the hub is enabled; true when it did and the event is used up. */
    fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (!enabled || event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK || event.action != MotionEvent.ACTION_MOVE) return false
        val state = stateOf(event.deviceId) ?: return false
        synchronized(states) { for (axis in PadState.AXES) state.setAxis(axis, event.getAxisValue(axis)) }
        publish()
        return true
    }

    /** Takes [event] when it is a button of a controller and the hub is enabled; true when it did. */
    fun onKeyEvent(event: KeyEvent): Boolean {
        if (!enabled || event.source and InputDevice.SOURCE_GAMEPAD != InputDevice.SOURCE_GAMEPAD) return false
        val state = stateOf(event.deviceId) ?: return false
        val handled = synchronized(states) {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> state.setKey(event.keyCode, true)
                KeyEvent.ACTION_UP -> state.setKey(event.keyCode, false)
                else -> false
            }
        }
        if (handled) publish()
        return handled
    }

    private fun isController(device: InputDevice?): Boolean {
        val sources = device?.sources ?: return false
        return sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD || sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
    }

    /** The ids of the connected controllers, in the order that gives them their indices. */
    private fun controllerIds(): List<Int> = InputDevice.getDeviceIds().filter { isController(InputDevice.getDevice(it)) }

    private fun stateOf(deviceId: Int): PadState? = synchronized(states) {
        states[deviceId] ?: run {
            val device = InputDevice.getDevice(deviceId)
            if (!isController(device)) return null
            // the right stick is on Z and RZ unless the controller has the other pair and not that one
            val hasZ = device?.getMotionRange(MotionEvent.AXIS_Z) != null && device.getMotionRange(MotionEvent.AXIS_RZ) != null
            val hasRx = device?.getMotionRange(MotionEvent.AXIS_RX) != null && device.getMotionRange(MotionEvent.AXIS_RY) != null
            val state = if (hasZ || !hasRx) PadState() else PadState(MotionEvent.AXIS_RX, MotionEvent.AXIS_RY)
            states[deviceId] = state
            state
        }
    }

    /** Hands the state of every controller to the patterns under its index. */
    @Synchronized
    private fun publish() {
        val ids = controllerIds()
        val raws = synchronized(states) { ids.map { states[it]?.toRaw() ?: RawPad(DoubleArray(4), DoubleArray(16)) } }
        raws.forEachIndexed { index, raw -> GamepadDevices.set(index, raw) }
        for (index in raws.size until published) GamepadDevices.set(index, null)
        published = raws.size
    }

    private var published = 0
}
