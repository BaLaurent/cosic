package cosic.io.input

import cosic.EngineEffects
import cosic.InputKind
import cosic.io.InputState
import cosic.io.MotionChannel
import cosic.pattern.KeyboardState
import cosic.pattern.PointerState
import cosic.pattern.Rational
import cosic.script.ReplSession
import cosic.script.SessionHost
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What a program sees of the motion sensors, a controller and the keyboard names. */
class InputCatalogueTest {
    private val effects = EngineEffects()
    private val session = ReplSession(SessionHost(effects = effects))

    @AfterTest
    fun leave() {
        GamepadDevices.clear()
        GamepadStates.clear()
        PointerState.x = 0.0
        PointerState.y = 0.0
        KeyboardState.provider = null
    }

    private fun shown(code: String, cycles: Int = 1): List<String> {
        val result = session.evaluate(code)
        return result.pattern.queryArc(Rational.ZERO, Rational(cycles.toLong())).map { it.show(true) }
    }

    @Test
    fun motionSignalsAreZeroUntilASensorReportsThenNormalised() {
        assertEquals(listOf("[ 0/1 → 1/1 | n:0 ]"), shown("n(accX.segment(1))"))
        effects.inputs.setMotion(MotionChannel.ACC_X, 0.0)
        effects.inputs.setMotion(MotionChannel.GRAV_Z, 9.81)
        effects.inputs.setMotion(MotionChannel.ORI_BETA, -90.0)
        assertEquals(listOf("[ 0/1 → 1/1 | n:0.5 ]"), shown("n(accelerationX.segment(1))"))
        assertEquals(listOf("[ 0/1 → 1/1 | n:1 ]"), shown("n(gravZ.segment(1))"))
        assertEquals(listOf("[ 0/1 → 1/1 | n:0.25 ]"), shown("n(oriX.segment(1))"))
        assertEquals(listOf("[ 0/1 → 1/1 | n:0.25 ]"), shown("n(orientationBeta.segment(1))"))
        assertEquals(listOf("[ 0/1 → 1/1 | n:0 ]"), shown("n(absOriA.segment(1))"))
    }

    @Test
    fun theAliasesAreTheSameSignal() {
        effects.inputs.setMotion(MotionChannel.ROT_GAMMA, 90.0)
        for (name in listOf("rotationGamma", "rotG", "rotationY", "rotY")) {
            assertEquals(listOf("[ 0/1 → 1/1 | n:0.75 ]"), shown("n($name.segment(1))"), name)
        }
    }

    @Test
    fun enableMotionReportsTheSensorsAsUsed() {
        shown("enableMotion()\nn(accX.segment(1))")
        assertTrue(InputKind.MOTION in effects.inputsUsed())
    }

    @Test
    fun theExampleOfAccelerationReadsTilt() {
        effects.inputs.setMotion(MotionChannel.ACC_X, 1.0) // normalised: 1
        val haps = shown("n(accelerationX.segment(4).range(0,7)).scale(\"C:minor\")")
        assertEquals(4, haps.size)
        assertEquals("[ 0/1 → 1/4 | note:C4 ]", haps.first())
    }

    @Test
    fun aPadDrivesTheAxesAndTheButtons() {
        shown("const pad = gamepad(0)\nn(pad.x1.segment(1))")
        assertTrue(InputKind.GAMEPAD in effects.inputsUsed())
        GamepadDevices.set(0, RawPad(doubleArrayOf(1.0, -1.0, 0.0, 0.0), DoubleArray(17).also { it[0] = 1.0 }))
        assertEquals(listOf("[ 0/1 → 1/1 | n:1 ]"), shown("const pad = gamepad(0)\nn(pad.x1.segment(1))"))
        assertEquals(listOf("[ 0/1 → 1/1 | n:0 ]"), shown("const pad = gamepad()\nn(pad.y1.segment(1))"))
        assertEquals(listOf("[ 0/1 → 1/1 | n:1 ]"), shown("const pad = gamepad(0)\nn(pad.x1_2.segment(1))"))
        assertEquals(listOf("[ 0/1 → 1/1 | n:1 ]"), shown("const pad = gamepad(0)\nn(pad.a.segment(1))"))
        assertEquals(listOf("[ 0/1 → 1/1 | n:1 ]"), shown("const pad = gamepad(0)\nn(pad.A.segment(1))"))
        assertEquals(listOf("[ 0/1 → 1/1 | n:1 ]"), shown("const pad = gamepad(0)\nn(pad.buttons[0].value.segment(1))"))
        assertEquals(listOf("[ 0/1 → 1/1 | n:0 ]"), shown("const pad = gamepad(0)\nn(pad.b.segment(1))"))
        assertEquals(listOf("[ 0/1 → 1/1 | n:1 ]"), shown("const pad = gamepad(0)\nn(pad.tglA.segment(1))"))
    }

    @Test
    fun theObjectHasTheKeysOfStrudels() {
        val keys = session.evaluate("""
            const pad = gamepad(0)
            $: s("bd").n(pad.checkSequence('a').segment(1))
            console.log(Object.keys(pad).join(','))
        """.trimIndent())
        val logged = session.host.recentLines().joinToString("\n") { it.message }
        assertTrue(logged.startsWith("x1,y1,x2,y2,x1_2,y1_2,x2_2,y2_2,buttons,a,A,tgla,tglA,b,B,tglb,tglB,"), logged)
        assertTrue(logged.endsWith("checkSequence,btnSequence,btnSeq,btnseq,raw"), logged)
        assertTrue(keys.pattern != null)
    }

    @Test
    fun aSequenceOfTheWrongKindIsReported() {
        shown("const pad = gamepad(0)\nn(pad.btnSeq(3).segment(1))")
        assertTrue(session.host.recentLines().any { "targetSequence must be an array or string" in it.message })
    }

    @Test
    fun keyAliasIsAMapAndTheKeyboardStateACopy() {
        assertEquals(listOf("[ 0/1 → 1/1 | s:Control ]"), shown("""s(keyAlias.get('ctrl')).segment(1)"""))
        assertEquals(listOf("[ 0/1 → 1/1 | n:8 ]"), shown("n(keyAlias.size)"))
        // no keyboard feeds the signals: undefined, as in Node
        session.evaluate("console.log(String(getCurrentKeyboardState()))")
        assertTrue(session.host.recentLines().last().message == "undefined")
        val inputs = InputState()
        inputs.bindToSignals()
        inputs.keyDown("Control")
        inputs.keyDown("j")
        inputs.keyUp("j")
        session.evaluate("console.log(JSON.stringify(getCurrentKeyboardState()))")
        assertEquals("""{"Control":true,"j":false}""", session.host.recentLines().last().message)
    }

    @Test
    fun theKeyboardSignalsFollowTheKeysHeld() {
        val inputs = InputState()
        inputs.bindToSignals()
        val code = """s("bd(5,8)").whenKey("Control:j", x => x.segment(16).color("red"))"""
        assertEquals(5, shown(code).size)
        inputs.keyDown("Control")
        inputs.keyDown("j")
        assertEquals(10, shown(code).size, "segment(16) of five 1/8 hits: ten slots")
        inputs.keyUp("j")
        assertEquals(5, shown(code).size)
        assertEquals(listOf("[ 0/1 → 1/1 | true ]"), shown("""keyDown("ctrl:Control")"""))
        assertFalse(inputs.isDown("j"))
    }

    @Test
    fun theMouseSignalFollowsThePointer() {
        val inputs = InputState()
        inputs.bindToSignals()
        inputs.mouseX = 0.75
        assertEquals(listOf("[ 0/1 → 1/1 | n:0.75 ]"), shown("n(mouseX.segment(1))"))
    }

    @Test
    fun buttonMapHasTheNamesOfTheButtons() {
        assertEquals(listOf("[ 0/1 → 1/1 | n:10 ]"), shown("n(buttonMap.ls)"))
        assertEquals(listOf("[ 0/1 → 1/1 | n:15 ]"), shown("n(buttonMap.right)"))
    }
}
