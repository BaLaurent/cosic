package cosic.io.input

import cosic.pattern.CosicError
import cosic.pattern.Pattern
import cosic.pattern.Undefined
import cosic.pattern.jsToString
import cosic.pattern.signal
import cosic.pattern.toBipolar
import java.util.concurrent.ConcurrentHashMap

/*
 * `gamepad(index)` of `@strudel/gamepad` (`gamepad.mjs`): the sticks and buttons of a game controller as patterns, with a toggle for each
 * button and a detector for button sequences (`uuddlrlrba`). The model here is the logic of that file without the browser: where Strudel
 * asks `navigator.getGamepads()[index]` it asks a source, and where it asks `Date.now()` it asks a clock, so that the app feeds it real
 * controllers and tests feed it a script.
 */

/** The state of one controller as the platform reports it: the axes between -1 and 1 and the buttons' values between 0 and 1. */
class RawPad(val axes: DoubleArray, val buttons: DoubleArray)

/**
 * The controllers that are connected, by the index a program asks for (`navigator.getGamepads()`): one per process, as a browser has one
 * list. The app's gamepad hub sets them from the events of the controllers; the patterns of `gamepad(i)` read them.
 */
object GamepadDevices {
    private val pads = ConcurrentHashMap<Int, RawPad>()

    fun set(index: Int, pad: RawPad?) {
        if (pad == null) pads.remove(index) else pads[index] = pad
    }

    fun get(index: Int): RawPad? = pads[index]

    fun clear() = pads.clear()
}

/** The names of the buttons, and the index each stands for (`buttonMap`); a button has more than one name (`l3`, `ls`). */
val buttonMap: Map<String, Int> = linkedMapOf(
    "a" to 0, "b" to 1, "x" to 2, "y" to 3, "lb" to 4, "rb" to 5, "lt" to 6, "rt" to 7, "back" to 8, "start" to 9,
    "l3" to 10, "ls" to 10, "r3" to 11, "rs" to 11, "u" to 12, "up" to 12, "d" to 13, "down" to 13,
    "l" to 14, "left" to 14, "r" to 15, "right" to 15,
)

/** What a button's toggle remembers: the last value it saw and whether the toggle is on (1) or off (0); `gamepadStates` of Strudel. */
class ToggleState(@Volatile var lastButtonState: Any?, @Volatile var toggleState: Double)

/**
 * The toggles of all buttons of all controllers, by `gamepad<index>_btn<i>`. They belong to the process, not to the program, so a toggle
 * that was switched on stays on when the program is evaluated again (`gamepadStates`, a module-level `Map` in Strudel).
 */
object GamepadStates {
    private val states = ConcurrentHashMap<String, ToggleState>()

    internal fun getOrCreate(key: String): ToggleState = states.getOrPut(key) { ToggleState(0.0, 0.0) }

    internal fun find(key: String): ToggleState? = states[key]

    /** `getGamepadStates()`: a copy of the table, in the order the toggles were made. */
    fun snapshot(): Map<String, ToggleState> = LinkedHashMap(states)

    /** `clearGamepadStates()`. The `gamepad()` objects that already exist fail the next time one of their toggles is read, as Strudel's do. */
    fun clear() = states.clear()
}

/**
 * Remembers the buttons that were pressed in the last [timeWindow] milliseconds (`ButtonSequenceDetector`): only a rising edge (a value of
 * exactly 1 after a 0) counts, the entry is the name of the button, and a gap longer than the window starts the sequence over.
 */
internal class ButtonSequenceDetector(private val timeWindow: Long = 1000, private val now: () -> Long) {
    private class Entry(val input: String, val timestamp: Long)

    private var sequence = ArrayList<Entry>()
    private var lastInputTime = 0L

    /** The previous value of each button; the first 16 start at 0, a higher button only after it was seen (`Array(16).fill(0)`). */
    private val buttonStates = HashMap<Int, Any?>().also { for (i in 0 until 16) it[i] = 0.0 }

    @Synchronized
    fun addInput(buttonIndex: Int, buttonValue: Any?) {
        val currentTime = now()
        if (buttonValue == 1.0 && buttonStates[buttonIndex] == 0.0) {
            if (currentTime - lastInputTime > timeWindow) sequence = ArrayList()
            val name = buttonMap.entries.firstOrNull { it.value == buttonIndex }?.key ?: buttonIndex.toString()
            sequence.add(Entry(name, currentTime))
            lastInputTime = currentTime
            sequence = sequence.filterTo(ArrayList()) { currentTime - it.timestamp <= timeWindow }
        }
        buttonStates[buttonIndex] = buttonValue
    }

    /** 1 when the last buttons pressed are [target] (a string of one letter per button, or a list of button names or indices), else 0. */
    @Synchronized
    fun checkSequence(target: Any?): Double {
        val names: List<String> = when (target) {
            is String -> target.map { it.toString().lowercase() }
            is List<*> -> target.map { jsToString(it).lowercase() }
            else -> return 0.0
        }
        if (sequence.size < names.size) return 0.0
        // `slice(-n)` with n = 0 is `slice(0)`: all of it
        val last = if (names.isEmpty()) sequence else sequence.subList(sequence.size - names.size, sequence.size)
        val ok = last.withIndex().all { (i, entry) ->
            val wanted = names.getOrNull(i) ?: return@all false
            val number = parseInt(wanted)
            // `buttonMap[input] === buttonMap[target]` is also true when neither is a button name (both undefined)
            entry.input == wanted || buttonMap[entry.input] == buttonMap[wanted] || (number != null && buttonMap[entry.input] == number)
        }
        return if (ok) 1.0 else 0.0
    }

    /** `parseInt(s)` for the digits a button index is written with; null for NaN. */
    private fun parseInt(s: String): Int? {
        val t = s.trimStart()
        var end = 0
        if (end < t.length && (t[end] == '-' || t[end] == '+')) end++
        val digits = end
        while (end < t.length && t[end].isDigit()) end++
        return if (end == digits) null else t.substring(0, end).toIntOrNull()
    }
}

/** The patterns of one button: its value (0..1) and its toggle (0 or 1). */
internal class ButtonPatterns(val value: Pattern, val toggle: Pattern)

/**
 * The patterns of `gamepad(index)`. Each query of any of them polls the controller ([source]) first, as every derived signal of Strudel
 * queries the one base signal that polls; only a controller that is there changes what the patterns read, so a controller that was
 * unplugged leaves its last values behind. [now] is the clock in milliseconds (`Date.now()`) for the button sequences.
 */
internal class GamepadModel(val index: Int, private val source: () -> RawPad?, now: () -> Long) {
    private var axes: List<Any?> = listOf(0.0, 0.0, 0.0, 0.0)
    private var buttonValues: List<Any?> = List(16) { 0.0 }
    private val detector = ButtonSequenceDetector(2000, now)

    private val pollLock = Any()

    /** Polls the controller and feeds the buttons to the sequence detector; returns what the base signal's value is made of. */
    private fun poll(t: Any?): Map<String, Any?> = synchronized(pollLock) {
        source()?.let { pad ->
            axes = pad.axes.map { (it + 1) / 2 }
            buttonValues = pad.buttons.toList()
        }
        buttonValues.forEachIndexed { i, v -> detector.addInput(i, v) }
        linkedMapOf("axes" to axes, "buttons" to buttonValues, "t" to t)
    }

    /** `raw`: the whole reading, `{ axes, buttons, t }`. */
    val raw: Pattern = signal { t, _ -> poll(t) }

    private fun reading(name: String, i: Int): Pattern = raw.fmap { state ->
        ((state as Map<*, *>)[name] as List<*>).getOrElse(i) { Undefined }
    }

    /** `x1`, `y1`, `x2`, `y2` (0..1) and `x1_2`... (-1..1), in the order Strudel's object has them. */
    val axisPatterns: Map<String, Pattern> = LinkedHashMap<String, Pattern>().also { m ->
        val base = listOf("x1", "y1", "x2", "y2").mapIndexed { i, name -> name to reading("axes", i) }
        for ((name, p) in base) m[name] = p
        for ((name, p) in base) m[name + "_2"] = p.toBipolar()
    }

    val buttons: List<ButtonPatterns> = List(16) { i ->
        val stateKey = "gamepad${jsToString(index.toDouble())}_btn$i"
        GamepadStates.getOrCreate(stateKey)
        val value = reading("buttons", i)
        val toggle = raw.fmap { state ->
            val current = ((state as Map<*, *>)["buttons"] as List<*>).getOrElse(i) { Undefined }
            val s = GamepadStates.find(stateKey)
                ?: throw CosicError("Cannot set properties of undefined (setting 'lastButtonState')")
            if (current == 1.0 && s.lastButtonState == 0.0) s.toggleState = if (s.toggleState == 0.0) 1.0 else 0.0
            s.lastButtonState = current
            s.toggleState
        }
        ButtonPatterns(value, toggle)
    }

    /** `btnSequence(sequence)`: 1 while the last buttons pressed are [sequence], else 0. */
    fun sequence(sequence: Any?): Pattern = raw.fmap { detector.checkSequence(sequence) }
}
