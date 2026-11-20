package cosic.io.serial

import cosic.EvalEffects
import cosic.pattern.CosicError
import cosic.pattern.ScheduleHook
import cosic.pattern.ScheduledHap
import cosic.player.LOOKAHEAD_SECONDS

/** `latency` of serial.mjs:46: a message is written this long after the time its hap is due. */
internal const val SERIAL_LATENCY_SECONDS = 0.1

/**
 * The hook `.serial(...)` attaches (dominant, so the hap does not sound): turns the value of the hap into bytes and gives them to the
 * serial output of the engine for the time the hap is due plus [SERIAL_LATENCY_SECONDS]. The due time is the system time of the hap's
 * frame; while the audio clock is not known it is taken to be the look-ahead of the scheduler from now.
 */
internal class SerialHook(private val route: SerialRoute, private val effects: EvalEffects) : ScheduleHook {
    override fun run(hap: ScheduledHap) {
        val outputs = effects.outputs
        val sink = outputs.serial ?: throw CosicError("serial output is not available")
        val bytes = serialBytes(hap.hap.value, route)
        val due = (outputs.clock?.nanoTimeAtFrame(hap.frame) ?: (System.nanoTime() + Math.round(LOOKAHEAD_SECONDS * 1e9))) +
            Math.round(SERIAL_LATENCY_SECONDS * 1e9)
        if (!sink.send(route.name, bytes, due)) throw CosicError("serial port '${route.name}' is not open")
    }
}
