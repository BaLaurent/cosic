package cosic.audio

import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Filters, filter envelopes, LFOs, the ladder, the 24 dB pair and the vowel filter run on the audio thread: starting such voices and rendering
 * them must not allocate (the stages work on arrays they own from the start).
 */
class FilterAllocationTest {
    private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    /** Every kind of voice once, then the kinds over again: a source plays once, so each voice has the source of its own, made here, off the measured path. */
    private fun workloadVoices(): List<VoiceParams> {
        val kinds = listOf<() -> VoiceParams>(
            {
                synthVp(
                    "sawtooth", 0.4, "note" to 50.0, "cutoff" to 900.0, "resonance" to 5.0, "lpenv" to 3.0, "lpattack" to 0.01,
                    "lpdepth" to 0.5, "lprate" to 6.0, "fanchor" to 0.5,
                )
            },
            {
                synthVp(
                    "square", 0.4, "note" to 62.0, "hcutoff" to 200.0, "hpenv" to -2.0, "bandf" to 2000.0, "bpdepth" to 0.3, "bpshape" to "sine",
                    "ftype" to "24db", "pan" to 0.8,
                )
            },
            { synthVp("pink", 0.3, "cutoff" to 700.0, "ftype" to "ladder", "lpenv" to 2.0, "vowel" to "a", "lpshape" to 4.0, "lpdepth" to 1.0) },
            {
                synthVp(
                    "sawtooth", 0.3, "note" to 40.0, "cutoff" to 1500.0, "ftype" to "ladder", "lpdepthfrequency" to 200.0, "lpsync" to 3.0, "drive" to 1.2,
                    "FX" to listOf(mapOf("hcutoff" to 300.0, "hpenv" to 2.0, "vowel" to "o", "ftype" to "ladder")),
                )
            },
            {
                synthVp(
                    "brown", 0.3, "bandf" to 400.0, "bandq" to 4.0, "bpattack" to 0.02, "bpdepthfrequency" to 100.0, "bprate" to 9.0, "ftype" to 2.0,
                    "FX" to listOf(mapOf("cutoff" to 600.0, "lpenv" to 1.0), mapOf("vowel" to "i")),
                )
            },
            { vp(plain(sineSample(200.0, 0.5)), 0.5, "note" to 60.0, "cutoff" to 300.0, "hcutoff" to 100.0, "bandf" to 700.0, "vowel" to "e") },
        )
        return kinds.map { it() } + List(40) { kinds[it % kinds.size]() }
    }

    @Test
    fun startingAndRenderingFilteredVoicesDoesNotAllocate() {
        val mixer = Mixer(SR)
        val out = FloatArray(512)

        fun workload(voices: List<VoiceParams>) {
            for (i in voices.indices) mixer.startVoice(voices[i])
            for (block in 0 until 200) mixer.render(out, 256)
            mixer.stopAll(5)
            mixer.render(out, 256)
        }

        // warm up: class loading, lazy tables, orbits, and the JIT (in its early tiers `Math.pow` of the envelopes allocates a few bytes per call)
        repeat(5) { workload(workloadVoices()) }
        val measured = workloadVoices()
        val before = threads.currentThreadAllocatedBytes
        workload(measured)
        val allocated = threads.currentThreadAllocatedBytes - before
        assertEquals(0L, allocated, "rendering allocated $allocated bytes")
    }
}
