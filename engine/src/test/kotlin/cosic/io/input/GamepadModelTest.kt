package cosic.io.input

import cosic.pattern.Rational
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class GamepadModelTest {
    private var pad: RawPad? = null
    private var clock = 0L

    @BeforeTest
    fun fresh() = GamepadStates.clear()

    @AfterTest
    fun clean() = GamepadStates.clear()

    private fun model(index: Int = 0) = GamepadModel(index, { pad }, { clock })

    private fun press(vararg down: Int, axes: DoubleArray = doubleArrayOf(0.0, 0.0, 0.0, 0.0)): RawPad {
        val buttons = DoubleArray(17)
        for (i in down) buttons[i] = 1.0
        return RawPad(axes, buttons)
    }

    private fun read(p: cosic.pattern.Pattern): Any? = p.queryArc(Rational.ZERO, Rational.ONE).single().value

    @Test
    fun axesAreZeroToOneAndBipolar() {
        val m = model()
        assertEquals(0.0, read(m.axisPatterns.getValue("x1")), "nothing has been polled: Strudel starts at 0, not 0.5")
        pad = RawPad(doubleArrayOf(-1.0, 0.0, 1.0, 0.5), DoubleArray(16))
        assertEquals(0.0, read(m.axisPatterns.getValue("x1")))
        assertEquals(0.5, read(m.axisPatterns.getValue("y1")))
        assertEquals(1.0, read(m.axisPatterns.getValue("x2")))
        assertEquals(0.75, read(m.axisPatterns.getValue("y2")))
        assertEquals(-1.0, read(m.axisPatterns.getValue("x1_2")))
        assertEquals(0.0, read(m.axisPatterns.getValue("y1_2")))
        assertEquals(1.0, read(m.axisPatterns.getValue("x2_2")))
        assertEquals(0.5, read(m.axisPatterns.getValue("y2_2")))
    }

    @Test
    fun anUnpluggedPadLeavesItsLastValues() {
        val m = model()
        pad = RawPad(doubleArrayOf(1.0, 1.0, 1.0, 1.0), DoubleArray(16))
        assertEquals(1.0, read(m.axisPatterns.getValue("x1")))
        pad = null
        assertEquals(1.0, read(m.axisPatterns.getValue("x1")))
    }

    @Test
    fun buttonValuesAreAnalog() {
        val m = model()
        pad = RawPad(DoubleArray(4), DoubleArray(16).also { it[6] = 0.4 })
        assertEquals(0.4, read(m.buttons[6].value))
        assertEquals(0.0, read(m.buttons[0].value))
    }

    @Test
    fun aToggleFlipsOnTheRisingEdgeOfAValueOfExactlyOne() {
        val m = model()
        val seen = ArrayList<Any?>()
        for (down in listOf(false, true, false, true)) {
            pad = press(*(if (down) intArrayOf(0) else intArrayOf()))
            seen += read(m.buttons[0].toggle)
        }
        assertEquals<List<Any?>>(listOf(0.0, 1.0, 1.0, 0.0), seen)
        // an analog value that is not 1 never flips it
        pad = RawPad(DoubleArray(4), DoubleArray(16).also { it[0] = 0.9 })
        assertEquals(0.0, read(m.buttons[0].toggle))
    }

    @Test
    fun theToggleOutlivesTheProgram() {
        pad = press(0)
        assertEquals(1.0, read(model().buttons[0].toggle))
        pad = press()
        read(model().buttons[0].toggle)
        assertEquals(1.0, GamepadStates.snapshot().getValue("gamepad0_btn0").toggleState)
        assertEquals(false, GamepadStates.snapshot().containsKey("gamepad1_btn3"))
        model(1)
        assertEquals(true, GamepadStates.snapshot().containsKey("gamepad1_btn3"))
    }

    private fun tap(m: GamepadModel, vararg buttons: Int) {
        for (b in buttons) {
            pad = press(b); read(m.raw)
            pad = press(); read(m.raw)
        }
    }

    @Test
    fun theKonamiCodeIsFoundInsideTheWindow() {
        val m = model()
        val seq = m.sequence("uuddlrlrba")
        tap(m, 12, 12, 13, 13, 14, 15, 14, 15, 1)
        assertEquals(0.0, read(seq), "the last button is missing")
        tap(m, 0)
        assertEquals(1.0, read(seq))
        assertEquals(1.0, read(m.sequence(listOf("u", "u", "d", "d", "l", "r", "l", "r", "b", "a"))))
        assertEquals(1.0, read(m.sequence("UUDDLRLRBA")), "case does not matter")
        assertEquals(0.0, read(m.sequence("uuddlrlrab")))
    }

    @Test
    fun aLongPauseStartsTheSequenceOver() {
        val m = model()
        tap(m, 12, 12, 13, 13, 14, 15, 14, 15, 1)
        clock = 2100
        tap(m, 0)
        assertEquals(0.0, read(m.sequence("uuddlrlrba")))
        assertEquals(1.0, read(m.sequence("a")))
    }

    @Test
    fun aButtonMayBeNamedByItsIndex() {
        val m = model()
        tap(m, 1, 0)
        assertEquals(1.0, read(m.sequence(listOf("1", "0"))))
        assertEquals(1.0, read(m.sequence(listOf("b", "a"))))
        assertEquals(1.0, read(m.sequence(listOf("1", "a"))))
        assertEquals(0.0, read(m.sequence(listOf("a", "b"))))
    }

    @Test
    fun aSequenceNeedsARisingEdge() {
        val m = model()
        pad = press(0); read(m.raw); read(m.raw); read(m.raw)
        assertEquals(1.0, read(m.sequence("a")))
        assertEquals(0.0, read(m.sequence("aa")), "holding a button is one press")
    }

    @Test
    fun theHomeButtonIsOnlyCountedAfterItWasSeenReleased() {
        val m = model()
        pad = press(16); read(m.raw)
        assertEquals(0.0, read(m.sequence("a")))
        pad = press(); read(m.raw)
        pad = press(16); read(m.raw)
        assertEquals(1.0, read(m.sequence(listOf("16"))))
    }
}
