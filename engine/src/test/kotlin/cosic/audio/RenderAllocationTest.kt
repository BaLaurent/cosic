package cosic.audio

import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals

/** The audio thread must not allocate while rendering: garbage collection there means dropouts. */
class RenderAllocationTest {
    private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    @Test
    fun renderingAndStartingVoicesDoNotAllocate() {
        val mixer = Mixer(SR)
        val out = FloatArray(512)
        val voices = arrayOf(
            vp(
                synth(Waveform.SAWTOOTH), 0.4, "note" to 50.0, "cutoff" to 900.0, "resonance" to 5.0, "delay" to 0.5, "delayfeedback" to 0.7,
                "room" to 0.4, "pan" to 0.1, "release" to 0.2, "FXrelease" to 0.5,
            ),
            vp(synth(Waveform.SQUARE), 0.4, "note" to 62.0, "hcutoff" to 200.0, "bandf" to 2000.0, "shape" to 0.6, "pan" to 0.9, "orbit" to 2.0),
            vp(synth(Waveform.PINK), 0.3, "crush" to 5.0, "coarse" to 3.0, "room" to 0.6, "roomsize" to 0.9, "dry" to 0.5),
            vp(synth(Waveform.BROWN), 0.3, "delay" to 0.3, "delaytime" to 0.12, "bus" to 1.0, "analyze" to 1.0),
            vp(VoiceSource.Sample(sineSample(200.0, 0.5), 48), 0.5, "note" to 60.0, "speed" to 1.3, "begin" to 0.1, "end" to 0.9, "cut" to 1.0),
            vp(VoiceSource.Sample(sineSample(300.0, 0.5), null), 0.5, "speed" to -1.0, "cut" to 1.0, "room" to 0.2, "delay" to 0.2, "channels" to listOf(1.0, 2.0)),
            vp(synth(Waveform.SINE), 0.2, "note" to 64.0, "djf" to 0.3, "duckorbit" to 1.0, "duckdepth" to 0.5),
        )

        fun workload() {
            for (i in voices.indices) mixer.startVoice(voices[i])
            // a crowd above the polyphony limit steals voices (the ramp is scheduled on the timelines)
            for (i in 0 until 140) mixer.startVoice(voices[i % 4])
            for (block in 0 until 200) mixer.render(out, 256)
            mixer.stopAll(5)
            mixer.render(out, 256)
        }

        workload() // warm up: class loading, lazy tables, orbits
        workload()
        val before = threads.currentThreadAllocatedBytes
        workload()
        val allocated = threads.currentThreadAllocatedBytes - before
        assertEquals(0L, allocated, "rendering allocated $allocated bytes")
    }
}
