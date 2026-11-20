package cosic.script.catalogue

import cosic.InputKind
import cosic.io.MotionChannel
import cosic.io.input.GamepadDevices
import cosic.io.input.GamepadModel
import cosic.io.input.GamepadStates
import cosic.io.input.KeyboardModel
import cosic.io.input.MotionModel
import cosic.io.input.buttonMap
import cosic.pattern.Pattern
import cosic.pattern.Undefined
import cosic.pattern.jsToNumber
import cosic.pattern.signal
import cosic.script.Bridge
import cosic.script.LogLevel
import cosic.script.Realm
import cosic.script.js.JsObject
import cosic.script.js.stdlib.JsMapObject
import cosic.script.js.stdlib.std

/*
 * What a program can read from the device it runs on: the motion sensors (`enableMotion()`, `accX`, `rotA`, `oriB`...), a game controller
 * (`gamepad(index)`) and the names of the keyboard (`keyAlias`, `getCurrentKeyboardState`). Each follows `@strudel/motion` (`motion.mjs`),
 * `@strudel/gamepad` (`gamepad.mjs`) and `util.mjs`. The logic is in `cosic.io.input`; this file makes it callable.
 *
 * Not here: `mousex`/`mousey` and `keyDown`/`whenKey`, which are in Signals.kt and read the state that `InputState.bindToSignals` makes
 * the process's (the app tracks the pointer and the keys all the time; there is nothing to switch on, so a program does not report
 * `MOUSE` or `KEYBOARD` as used).
 */

/** The channel each motion signal reads, with the names a program knows it by (`motion.mjs`: the aliases are the same signal). */
private val MOTION_SIGNALS: List<Pair<MotionChannel, List<String>>> = listOf(
    MotionChannel.ACC_X to listOf("accelerationX", "accX"),
    MotionChannel.ACC_Y to listOf("accelerationY", "accY"),
    MotionChannel.ACC_Z to listOf("accelerationZ", "accZ"),
    MotionChannel.GRAV_X to listOf("gravityX", "gravX"),
    MotionChannel.GRAV_Y to listOf("gravityY", "gravY"),
    MotionChannel.GRAV_Z to listOf("gravityZ", "gravZ"),
    MotionChannel.ROT_ALPHA to listOf("rotationAlpha", "rotA", "rotationZ", "rotZ"),
    MotionChannel.ROT_BETA to listOf("rotationBeta", "rotB", "rotationX", "rotX"),
    MotionChannel.ROT_GAMMA to listOf("rotationGamma", "rotG", "rotationY", "rotY"),
    MotionChannel.ORI_ALPHA to listOf("orientationAlpha", "orientationA", "oriA", "orientationZ", "oriZ"),
    MotionChannel.ORI_BETA to listOf("orientationBeta", "orientationB", "oriB", "orientationX", "oriX"),
    MotionChannel.ORI_GAMMA to listOf("orientationGamma", "orientationG", "oriG", "orientationY", "oriY"),
    MotionChannel.ABS_ALPHA to listOf("absoluteOrientationAlpha", "absOriA", "absoluteOrientationZ", "absOriZ"),
    MotionChannel.ABS_BETA to listOf("absoluteOrientationBeta", "absOriB", "absoluteOrientationX", "absOriX"),
    MotionChannel.ABS_GAMMA to listOf("absoluteOrientationGamma", "absOriG", "absoluteOrientationY", "absOriY"),
)

/** The signal of [channel] over the sensors in [bridge]'s host: 0 until the sensor has reported, then the normalised reading. */
private fun motionSignal(bridge: Bridge, channel: MotionChannel): Pattern {
    val inputs = bridge.realm.host.effects.inputs
    return signal { _, _ -> if (inputs.hasMotion(channel)) MotionModel.normalise(channel, inputs.motion(channel)) else 0.0 }
}

internal fun registerInput() = catalogue("Input") {
    for ((channel, names) in MOTION_SIGNALS) {
        for (name in names) global(name) { bridge -> bridge.wrap(motionSignal(bridge, channel)) }
    }

    // `enableMotion()`: Strudel asks the browser for permission and starts listening; here it is the app that starts the sensors, for a
    // program that reports `MOTION` as used (nothing moves before this is called, as in Strudel).
    function(listOf("enableMotion"), 0) { ctx ->
        ctx.effects.markInputUsed(InputKind.MOTION)
        Undefined
    }

    function(listOf("gamepad"), 0) { ctx ->
        ctx.effects.markInputUsed(InputKind.GAMEPAD)
        val index = if (ctx.arg(0) === Undefined) 0.0 else jsToNumber(ctx.arg(0))
        gamepadObject(ctx.bridge, GamepadModel(index.toInt(), { GamepadDevices.get(index.toInt()) }, System::currentTimeMillis), ctx)
    }
    global("buttonMap") { bridge ->
        bridge.realm.newObject().also { o -> for ((name, i) in buttonMap) o.defineOwn(name, i.toDouble()) }
    }
    function(listOf("getGamepadStates"), 0) { ctx ->
        ctx.realm.newObject().also { o ->
            for ((key, state) in GamepadStates.snapshot()) {
                o.defineOwn(
                    key,
                    ctx.realm.newObject().also { s ->
                        s.defineOwn("lastButtonState", state.lastButtonState)
                        s.defineOwn("toggleState", state.toggleState)
                    },
                )
            }
        }
    }
    function(listOf("clearGamepadStates"), 0) {
        GamepadStates.clear()
        Undefined
    }

    // the keyboard: `keyAlias` is a `Map`, `getCurrentKeyboardState()` a copy of the keys held (undefined without a keyboard)
    global("keyAlias") { bridge ->
        val realm = bridge.realm
        JsMapObject(realm.std.mapPrototype, realm).also { m -> for ((k, v) in KeyboardModel.keyAlias) m.table.put(k, v) }
    }
    function(listOf("getCurrentKeyboardState"), 0) { ctx ->
        val state = KeyboardModel.currentState() ?: return@function Undefined
        ctx.realm.newObject().also { o -> for ((k, v) in state) o.defineOwn(k, v) }
    }
}

/** The object `gamepad(index)` gives a program: the axes, the buttons (`buttons[i].value/.toggle`, one pattern per name) and the sequence checks. */
private fun gamepadObject(bridge: Bridge, pad: GamepadModel, ctx: CallContext): JsObject {
    val realm: Realm = bridge.realm
    val o = realm.newObject()
    for ((name, pattern) in pad.axisPatterns) o.defineOwn(name, bridge.wrap(pattern))
    o.defineOwn(
        "buttons",
        realm.newArray(pad.buttons.map { b -> realm.newObject().also { it.defineOwn("value", bridge.wrap(b.value)); it.defineOwn("toggle", bridge.wrap(b.toggle)) } }),
    )
    for ((key, index) in buttonMap) {
        val button = pad.buttons[index]
        o.defineOwn(key.lowercase(), bridge.wrap(button.value))
        o.defineOwn(key.uppercase(), bridge.wrap(button.value))
        o.defineOwn("tgl" + key.lowercase(), bridge.wrap(button.toggle))
        o.defineOwn("tgl" + key.uppercase(), bridge.wrap(button.toggle))
    }
    for (name in listOf("checkSequence", "btnSequence", "btnSeq", "btnseq")) {
        o.defineOwn(
            name,
            realm.function(name, 1) { _, args ->
                val target = bridge.toKernel(args.getOrElse(0) { Undefined })
                if (target !is String && target !is List<*>) ctx.host.log("ButtonSequenceDetector: targetSequence must be an array or string", LogLevel.ERROR)
                bridge.wrap(pad.sequence(target))
            },
        )
    }
    o.defineOwn("raw", bridge.wrap(pad.raw))
    return o
}
