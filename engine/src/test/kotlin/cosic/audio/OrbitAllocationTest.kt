package cosic.audio

import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Rendering with every orbit effect must not allocate: the delay, the convolver reverb (whose impulse responses are built at the first voice that asks, never on a
 * later one), the DJF filter, ducking, buses, analysers and the pooled `FX(...)` delay and reverb. [RenderAllocationTest] demands exactly 0 bytes for its own workload; the JVM
 * itself allocates a few dozen bytes now and then while it recompiles (measured on the code before the orbits were written, too), so this one allows a small allowance
 * for a 200-block workload: a per-block allocation would show up as kilobytes.
 */
class OrbitAllocationTest {
    private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    /** Each kind of voice three times: a source plays once, so each voice has the source of its own, made here, off the measured path. */
    private fun workloadVoices(): List<VoiceParams> {
        val element = mapOf("delay" to 0.4, "delaytime" to 0.07, "delayfeedback" to 0.5, "room" to 0.3, "roomsize" to 0.5, "gain" to 0.8)
        val kinds = listOf<() -> VoiceParams>(
            { synthVp("sawtooth", 0.4, "note" to 50.0, "delay" to 0.5, "delaytime" to 0.2, "delayfeedback" to 0.7, "room" to 0.4, "roomsize" to 1.0) },
            { synthVp("square", 0.4, "note" to 62.0, "FX" to listOf(element), "orbit" to 2.0, "delay" to 0.3, "delaytime" to 0.1) },
            { synthVp("pink", 0.3, "room" to 0.6, "roomsize" to 1.0, "dry" to 0.5, "djf" to 0.3) },
            { synthVp("sine", 0.3, "note" to 64.0, "bus" to 1.0, "analyze" to 2.0, "fft" to 6.0, "djf" to 0.7) },
            { synthVp("sine", 0.2, "note" to 67.0, "duckorbit" to listOf(1.0, 2.0), "duckdepth" to 0.5, "duckattack" to 0.1, "gain" to 0.1) },
        )
        return List(3 * kinds.size) { kinds[it % kinds.size]() }
    }

    @Test
    fun theOrbitEffectsDoNotAllocateWhileRendering() {
        val mixer = Mixer(SR)
        val out = FloatArray(512)

        fun workload(voices: List<VoiceParams>) {
            for (i in voices.indices) mixer.startVoice(voices[i])
            for (block in 0 until 200) mixer.render(out, 256)
            mixer.stopAll(5)
            mixer.render(out, 256)
        }

        repeat(3) { workload(workloadVoices()) } // warm up: orbits, analysers, the rooms, the in-chain pools, class loading, the JIT
        val measured = listOf(workloadVoices(), workloadVoices())
        val before = threads.currentThreadAllocatedBytes
        for (i in measured.indices) workload(measured[i])
        val allocated = threads.currentThreadAllocatedBytes - before
        assertTrue(allocated < 2048, "two workloads allocated $allocated bytes")
    }
}
