package cosic.io

import cosic.pattern.KeyboardState
import cosic.pattern.PointerState
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLongArray

/**
 * The motion signals of the device, one per channel of a sensor reading. A channel holds the value the sensor gave, in the unit the sensor
 * uses (m/s², degrees, degrees per second); the signals normalise them (`accX` is `(v + 1) / 2`). All 0 until a sensor reports.
 */
enum class MotionChannel {
    ACC_X, ACC_Y, ACC_Z,
    GRAV_X, GRAV_Y, GRAV_Z,
    ROT_ALPHA, ROT_BETA, ROT_GAMMA,
    ORI_ALPHA, ORI_BETA, ORI_GAMMA,
    ABS_ALPHA, ABS_BETA, ABS_GAMMA,
}

/**
 * What the person playing does to the device that patterns can react to: where the pointer is, which keys are held, what the motion
 * sensors say, and the values of the sliders. The app sets it from its input events, on any thread; patterns read it whenever they are
 * queried, on the audio thread or the UI thread. Nothing here is ever null or blocks.
 *
 * The signals `mousex`/`mousey` and the keyboard functions of the pattern kernel read process-wide state (there is one pointer and one
 * keyboard per process, as in a browser). [bindToSignals] makes this the state that feeds them; it is the host's call, made once for the
 * state the user's input goes to, so that engines that render offline never touch it.
 */
class InputState {
    @Volatile
    private var x = 0.0

    @Volatile
    private var y = 0.0

    @Volatile
    private var bound = false

    /** Where the pointer is, in fractions of the width and height of the screen (`clientX / body.clientWidth`). Not clamped. 0 at first. */
    var mouseX: Double
        get() = x
        set(value) {
            x = value
            if (bound) PointerState.x = value
        }

    var mouseY: Double
        get() = y
        set(value) {
            y = value
            if (bound) PointerState.y = value
        }

    /** The keys held, by their `KeyboardEvent.key` name; released keys stay with `false`, as `keyState` of Strudel does. */
    private val keys = ConcurrentHashMap<String, Boolean>()

    fun keyDown(w3cKey: String) {
        keys[w3cKey] = true
    }

    fun keyUp(w3cKey: String) {
        keys[w3cKey] = false
    }

    /** Whether the key called [key] (`Control`, `Shift`, `ArrowUp`, `a`...) is held. */
    fun isDown(key: String): Boolean = keys[key] == true

    private val motion = AtomicLongArray(MotionChannel.entries.size)

    fun motion(c: MotionChannel): Double = java.lang.Double.longBitsToDouble(motion.get(c.ordinal))

    fun setMotion(c: MotionChannel, v: Double) {
        motion.set(c.ordinal, java.lang.Double.doubleToRawLongBits(v))
    }

    val sliders = SliderBank()

    private val keyboard: () -> Map<String, Boolean> = { keys }

    /**
     * Makes this the state the `mousex`/`mousey` signals and `keyDown`/`whenKey` read: the pointer is passed on from now on, and the
     * keyboard is this one's. Call it for the one state that the user's input goes to. [unbindFromSignals] undoes it.
     */
    fun bindToSignals() {
        bound = true
        PointerState.x = x
        PointerState.y = y
        KeyboardState.provider = keyboard
    }

    /** Leaves the signals as a process with no pointer and no keyboard has them (pointer 0, no keyboard state), if this state fed them. */
    fun unbindFromSignals() {
        if (!bound) return
        bound = false
        PointerState.x = 0.0
        PointerState.y = 0.0
        if (KeyboardState.provider === keyboard) KeyboardState.provider = null
    }
}
