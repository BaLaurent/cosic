package cosic.script.catalogue

import cosic.io.osc.OscHook
import cosic.pattern.patternOf

/**
 * `osc` of `@strudel/osc`: sends every hap as an OSC message to SuperDirt (`/dirt/play`, with the controls of the hap and the time it sounds at) and
 * does not play it. The hook is [OscHook], run on the dispatcher of the output bus; the device behind it, and the host and port that
 * haps without `oschost` and `oscport` go to, belong to the app. `osc` is a method (`s("bd").osc()`) and a function (`all(osc)`). The controls
 * `oschost`, `oscport` and `nudge` are in the table of controls with the others.
 */
internal fun registerOsc() {
    catalogue("Osc") {
        function(listOf("osc"), 1) { ctx -> patternOf(ctx.kernelArg(0)).onSchedule(OscHook(ctx.effects.outputs), dominant = true) }
        method(listOf("osc"), 0) { ctx -> ctx.bridge.receiverOf(ctx.self, "osc").onSchedule(OscHook(ctx.effects.outputs), dominant = true) }
    }
    registerSpeech()
}
