package cosic.io.midi

import cosic.io.OutputBus
import cosic.pattern.CosicError
import cosic.pattern.ScheduleHook
import cosic.pattern.ScheduledHap

/** The offset that makes the sink send a message at once: a time long gone, as WebMidi's time stamp `false` is. */
internal const val MIDI_SEND_NOW_OFFSET_MILLIS = -1.0e9

/**
 * What `.midi()` attaches to the haps of a pattern: the handler of `Pattern.prototype.midi`. It runs on the dispatcher of the [outputs] (never
 * the audio thread), works out the messages of the hap ([MidiEncoder]) and gives them to the MIDI sink with the frame of the hap and the
 * time each is due after it; the sink sends them when that frame is heard. The hook is dominant: a hap with `.midi()` does not sound.
 *
 * The lines Strudel logs are written to the console, and the error that ended the handler is thrown after the messages before it were
 * sent, so that the bus reports it as `[getSchedule] error: ...`.
 */
internal class MidiHook(private val config: MidiConfig, private val outputs: OutputBus) : ScheduleHook {
    override fun run(hap: ScheduledHap) {
        val sink = outputs.midi
        if (sink == null) {
            outputs.console.log("Midi not enabled")
            return
        }
        // the time of the hap in the system's clock: before the audio device has reported one there is none, and Strudel skips the hap
        if (outputs.clock?.nanoTimeAtFrame(hap.frame) == null) {
            outputs.console.log("[midi] clockbridge not ready")
            return
        }
        val result = MidiEncoder.encode(hap, config, outputs.midiHub.maps, sink.outputNames())
        for (line in result.logs) outputs.console.log(line)
        val device = result.device
        if (device != null) {
            for (packet in result.packets) {
                sink.send(device, packet.bytes, hap.frame, if (packet.offsetMs.isNaN()) MIDI_SEND_NOW_OFFSET_MILLIS else packet.offsetMs)
            }
        }
        result.error?.let { throw CosicError(it) }
    }
}
