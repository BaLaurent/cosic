package cosic.script.catalogue

import cosic.EvalEffects
import cosic.pattern.Controls
import cosic.pattern.Registry
import cosic.pattern.ScheduleHook
import cosic.pattern.ScheduledHap
import cosic.pattern.Undefined
import cosic.pattern.jsTruthy
import cosic.player.LOOKAHEAD_SECONDS
import cosic.script.HapValue
import cosic.script.LogLevel

/*
 * What a pattern can ask for when its haps are scheduled, besides `onSchedule`, `log` and `logValues` (which the pattern class of the
 * bridge has): the deprecated `onTrigger`, `onTriggerTime`, which runs a function when the hap sounds rather than when it is scheduled, and
 * the `markcss` control that styles the mark of the hap in the editor. The hooks run on the dispatcher of the engine's output bus, off
 * the audio thread (see [cosic.io.OutputBus]).
 */

/** How many nanoseconds until [sh] sounds: by the clock of the outputs, or by the look-ahead of the scheduler while the clock is not known. */
private fun nanosUntilDue(effects: EvalEffects, sh: ScheduledHap): Long {
    val due = effects.outputs.clock?.nanoTimeAtFrame(sh.frame) ?: return Math.round(LOOKAHEAD_SECONDS * 1e9)
    return due - System.nanoTime()
}

/** `getClockTime()` of Strudel's scheduler for the hap [sh]: the engine time in seconds now, in the clock `targetTimeSec` is in. */
private fun clockNow(effects: EvalEffects, sh: ScheduledHap): Double = sh.targetTimeSec - nanosUntilDue(effects, sh) / 1e9

internal fun registerHooks() {
    // `export const { markcss } = registerControl('markcss')` of codemirror.mjs: a style for the mark of the hap in the editor (a
    // CSS declaration list). Like any control it is a method of patterns and a global function.
    val markcss = Controls.registerCustom(listOf("markcss"), false, emptyList())
    Registry.dual(
        listOf("markcss"),
        global = { a -> Controls.applyControl(markcss, a.getOrElse(0) { Undefined }, a.getOrNull(1)) },
        method = { pat, a -> Controls.applyControl(markcss, a.getOrElse(0) { Undefined }, pat) },
    )

    catalogue("Hooks") {
        // `onTrigger(fn, dominant = true)` of pattern.mjs: the old form of `onSchedule`, whose function gets the time now and the time
        // the hap sounds at: `fn(hap, currentTime, cps, targetTime)`.
        method(listOf("onTrigger"), 2) { ctx ->
            ctx.host.log("onTrigger is deprecated.. use onSchedule(hap, time, cps) instead", LogLevel.WARNING)
            val fn = ctx.arg(0)
            val dominant = if (ctx.arg(1) === Undefined) true else jsTruthy(ctx.arg(1))
            val bridge = ctx.bridge
            val effects = ctx.effects
            ctx.bridge.receiverOf(ctx.self, "onTrigger").onSchedule(
                ScheduleHook { sh -> bridge.callJs(fn, HapValue(bridge, sh.hap), clockNow(effects, sh), sh.cps, sh.targetTimeSec) },
                dominant,
            )
        }

        // `onTriggerTime(fn)` of pattern.mjs: `fn(hap)` at the time the hap sounds (a timeout of the difference between that time and now,
        // so it is as accurate as a timer is). Not dominant: the hap is played as well.
        method(listOf("onTriggerTime"), 1) { ctx ->
            val fn = ctx.arg(0)
            val bridge = ctx.bridge
            val effects = ctx.effects
            val host = ctx.host
            ctx.bridge.receiverOf(ctx.self, "onTriggerTime").onSchedule(
                ScheduleHook { sh ->
                    val wait = Math.round(nanosUntilDue(effects, sh) / 1e6)
                    host.runLater(wait) { bridge.callJs(fn, HapValue(bridge, sh.hap)) }
                },
                false,
            )
        }
    }
}
