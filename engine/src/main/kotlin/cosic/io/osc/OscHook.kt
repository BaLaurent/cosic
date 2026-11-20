package cosic.io.osc

import cosic.io.OutputBus
import cosic.pattern.CosicError
import cosic.pattern.ScheduleHook
import cosic.pattern.ScheduledHap
import cosic.pattern.Undefined
import cosic.pattern.jsToNumber
import cosic.pattern.jsToString

/** The address SuperDirt listens on for notes. */
internal const val DIRT_PLAY = "/dirt/play"

/**
 * `oscOutput` of `@strudel/osc` (`osc.mjs:62-84`) as a hook of the [outputs]' dispatcher: sends the hap to [OutputBus.osc] as `/dirt/play` with
 * the controls of [OscControls.fromHap]. The hap's own `oschost` and `oscport` are where it goes; without them the sink's settings decide.
 * A hap that cannot be sent (no device, a value that is not an object, a control that is not a number, an argument OSC cannot hold) is an
 * error on the console, and the next hap is tried.
 */
internal class OscHook(private val outputs: OutputBus) : ScheduleHook {
    override fun run(hap: ScheduledHap) {
        val sink = outputs.osc ?: throw CosicError("Could not connect to OSC server. Is it running?")
        val args = OscControls.fromHap(hap)
        OscCodec.check(args)
        val value = hap.hap.value as? Map<*, *>
        val host = value?.get("oschost")?.takeIf { it !== Undefined }?.let(::jsToString)
        val port = value?.get("oscport")?.takeIf { it !== Undefined }?.let { jsToNumber(it).takeIf(Double::isFinite)?.toInt() ?: Int.MIN_VALUE }
        sink.send(host, port, DIRT_PLAY, args, hap.frame)
    }
}
