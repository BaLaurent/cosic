package cosic.script.catalogue

import cosic.pattern.Registry
import cosic.pattern.Undefined
import cosic.pattern._keyDown
import cosic.pattern.callFn
import cosic.pattern.closeSignal
import cosic.pattern.cosine
import cosic.pattern.cosine2
import cosic.pattern.cyclesPer
import cosic.pattern.isaw
import cosic.pattern.isaw2
import cosic.pattern.isquare
import cosic.pattern.isquare2
import cosic.pattern.itri
import cosic.pattern.itri2
import cosic.pattern.mousex
import cosic.pattern.mousey
import cosic.pattern.openSignal
import cosic.pattern.per
import cosic.pattern.perx
import cosic.pattern.reg1
import cosic.pattern.reg3
import cosic.pattern.saw
import cosic.pattern.saw2
import cosic.pattern.signal
import cosic.pattern.sine
import cosic.pattern.sine2
import cosic.pattern.square
import cosic.pattern.square2
import cosic.pattern.steady
import cosic.pattern.callMethod
import cosic.pattern.time
import cosic.pattern.tri
import cosic.pattern.tri2

/**
 * The continuous patterns of `signal.mjs` that are not random (the random ones are in Random.kt): the waves, `time`, the
 * length of events, the pointer, the keyboard, and `signal` and `steady` to make more.
 */
internal fun registerSignals() {
    // functions that make signals
    Registry.function(listOf("signal")) { a ->
        val func = a.getOrElse(0) { Undefined }
        signal { t, controls -> callFn(func, t, controls) }
    }
    Registry.function(listOf("steady")) { a -> steady(a.getOrElse(0) { Undefined }) }

    // waves
    Registry.constant(listOf("saw"), saw)
    Registry.constant(listOf("saw2"), saw2)
    Registry.constant(listOf("isaw"), isaw)
    Registry.constant(listOf("isaw2"), isaw2)
    Registry.constant(listOf("sine"), sine)
    Registry.constant(listOf("sine2"), sine2)
    Registry.constant(listOf("cosine"), cosine)
    Registry.constant(listOf("cosine2"), cosine2)
    Registry.constant(listOf("square"), square)
    Registry.constant(listOf("square2"), square2)
    Registry.constant(listOf("isquare"), isquare)
    Registry.constant(listOf("isquare2"), isquare2)
    Registry.constant(listOf("tri"), tri)
    Registry.constant(listOf("tri2"), tri2)
    Registry.constant(listOf("itri"), itri)
    Registry.constant(listOf("itri2"), itri2)
    Registry.constant(listOf("time"), time)

    // the length of events
    Registry.constant(listOf("cyclesPer"), cyclesPer)
    Registry.constant(listOf("per", "perCycle"), per)
    Registry.constant(listOf("perx"), perx)
    Registry.constant(listOf("open"), openSignal)
    Registry.constant(listOf("close"), closeSignal)

    // pointer and keyboard
    Registry.constant(listOf("mousex", "mouseX"), mousex)
    Registry.constant(listOf("mousey", "mouseY"), mousey)
    Registry.function(listOf("_keyDown")) { a -> _keyDown(a.getOrElse(0) { Undefined }) }
    Registry.reg1("keyDown") { pat -> pat.fmap { key -> _keyDown(key) } }
    Registry.reg3("whenKey") { input, func, pat -> pat.callMethod("when", _keyDown(input), func) }
}
