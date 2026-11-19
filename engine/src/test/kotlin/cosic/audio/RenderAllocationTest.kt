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
            VoiceParams(VoiceSource.Synth(Waveform.SAWTOOTH), 0.4, note = 50.0, lpf = 900.0, lpq = 5.0, delay = 0.5, delayfeedback = 0.7, room = 0.4, pan = 0.1),
            VoiceParams(VoiceSource.Synth(Waveform.SQUARE), 0.4, note = 62.0, hpf = 200.0, bpf = 2000.0, shape = 0.6, pan = 0.9),
            VoiceParams(VoiceSource.Synth(Waveform.PINK), 0.3, crush = 5.0, coarse = 3.0, room = 0.6, size = 0.9),
            VoiceParams(VoiceSource.Synth(Waveform.BROWN), 0.3, delay = 0.3, delaytime = 0.12),
            VoiceParams(VoiceSource.Sample(sineSample(200.0, 0.5), 48), 0.5, note = 60.0, speed = 1.3, begin = 0.1, end = 0.9, cut = 1.0),
            VoiceParams(VoiceSource.Sample(sineSample(300.0, 0.5), null), 0.5, speed = -1.0, cut = 1.0, room = 0.2, delay = 0.2),
        )

        fun workload() {
            for (i in voices.indices) mixer.startVoice(voices[i])
            for (block in 0 until 200) mixer.render(out, 256)
            mixer.stopAll(5)
            mixer.render(out, 256)
        }

        workload() // warm up: class loading, lazy tables
        workload()
        val before = threads.currentThreadAllocatedBytes
        workload()
        val allocated = threads.currentThreadAllocatedBytes - before
        assertEquals(0L, allocated, "rendering allocated $allocated bytes")
    }
}
