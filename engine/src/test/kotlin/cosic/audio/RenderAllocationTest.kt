package cosic.audio

import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals

/** The audio thread must not allocate while rendering: garbage collection there means dropouts. */
class RenderAllocationTest {
    private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    /**
     * Voices of every kind, then a crowd above the polyphony limit that steals the oldest. A source plays once, so every voice has the source of its own, made here, off the
     * measured path, as the sound lookup makes them.
     */
    private fun workloadVoices(): List<VoiceParams> {
        val voices = ArrayList<VoiceParams>()
        voices.add(
            synthVp(
                "sawtooth", 0.4, "note" to 50.0, "cutoff" to 900.0, "resonance" to 5.0, "delay" to 0.5, "delayfeedback" to 0.7,
                "room" to 0.4, "pan" to 0.1, "release" to 0.2, "FXrelease" to 0.5,
            ),
        )
        voices.add(synthVp("square", 0.4, "note" to 62.0, "hcutoff" to 200.0, "bandf" to 2000.0, "shape" to 0.6, "pan" to 0.9, "orbit" to 2.0))
        voices.add(synthVp("pink", 0.3, "crush" to 5.0, "coarse" to 3.0, "room" to 0.6, "roomsize" to 0.9, "dry" to 0.5))
        voices.add(synthVp("brown", 0.3, "delay" to 0.3, "delaytime" to 0.12, "bus" to 1.0, "analyze" to 1.0))
        voices.add(
            vp(
                VoiceSource.Sample(SampleSourceParams(sineSample(200.0, 0.5), transposeSemitones = 12.0)), 0.5,
                "speed" to 1.3, "begin" to 0.1, "end" to 0.9, "cut" to 1.0,
            ),
        )
        voices.add(
            vp(
                VoiceSource.Sample(SampleSourceParams(sineSample(300.0, 0.5))), 0.5,
                "speed" to -1.0, "cut" to 1.0, "room" to 0.2, "delay" to 0.2, "channels" to listOf(1.0, 2.0),
            ),
        )
        voices.add(
            vp(
                VoiceSource.Sample(SampleSourceParams(stereoSample(0.5), nudgeSeconds = 0.003)), 0.5,
                "loop" to 1.0, "loopBegin" to 0.2, "loopEnd" to 0.7, "unit" to "c", "speed" to 2.0, "pan" to 0.3,
            ),
        )
        voices.add(synthVp("sine", 0.2, "note" to 64.0, "djf" to 0.3, "duckorbit" to 1.0, "duckdepth" to 0.5))
        // the families of sources that carry their own state: worklets that start on the render quantum grid, noise, FM and vibrato on a pitch bus, a drum with its own shaper
        voices.add(synthVp("supersaw", 0.4, "note" to 48.0, "unison" to 5.0, "spread" to 0.6, "detune" to 0.3, "pan" to 0.2))
        voices.add(synthVp("pulse", 0.4, "note" to 55.0, "pw" to 0.3, "pwrate" to 4.0, "pwsweep" to 0.2))
        voices.add(synthVp("bytebeat", 0.4, "n" to 3.0))
        voices.add(synthVp("sbd", 0.4, "note" to 31.0))
        voices.add(synthVp("white", 0.2, "gain" to 0.3))
        voices.add(synthVp("crackle", 0.3, "density" to 0.2))
        voices.add(synthVp("sine", 0.4, "note" to 57.0, "fmi" to 2.0, "fmh" to 1.5, "vib" to 5.0, "vibmod" to 0.4, "penv" to 7.0, "pattack" to 0.01))
        voices.add(synthVp("triangle", 0.4, "note" to 60.0, "partials" to listOf(1.0, 0.5, 0.25), "noise" to 0.3))
        // a crowd above the polyphony limit steals voices (the ramp is scheduled on the timelines)
        val crowd = listOf("sawtooth", "square", "pink", "brown")
        for (i in 0 until 140) voices.add(synthVp(crowd[i % 4], 0.4, "note" to 50.0 + i % 12))
        return voices
    }

    @Test
    fun renderingAndStartingVoicesDoNotAllocate() {
        val mixer = Mixer(SR)
        val out = FloatArray(512)

        fun workload(voices: List<VoiceParams>) {
            for (i in voices.indices) mixer.startVoice(voices[i])
            for (block in 0 until 200) mixer.render(out, 256)
            mixer.stopAll(5)
            mixer.render(out, 256)
        }

        workload(workloadVoices()) // warm up: class loading, lazy tables, orbits
        workload(workloadVoices())
        val measured = workloadVoices()
        val before = threads.currentThreadAllocatedBytes
        workload(measured)
        val allocated = threads.currentThreadAllocatedBytes - before
        assertEquals(0L, allocated, "rendering allocated $allocated bytes")
    }
}
