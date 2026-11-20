package cosic.audio

import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals

/** Starting and rendering voices that use the distortion, modulation and dynamics stages must not allocate on the audio thread (see also [RenderAllocationTest]). */
class StagesAllocationTest {
    private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    @Test
    fun voicesWithTheDistortionModulationAndDynamicsStagesDoNotAllocate() {
        val mixer = Mixer(SR)
        val out = FloatArray(512)
        val voices = arrayOf(
            vp(
                synth(Waveform.SAWTOOTH), 0.4, "note" to 50.0, "coarse" to 3.0, "crush" to 6.0, "shape" to 0.4, "shapevol" to 0.8, "distort" to 1.2, "distorttype" to "fold",
                "tremolo" to 5.0, "tremolodepth" to 0.6, "tremoloshape" to "sine", "compressor" to -20.0, "compressorRatio" to 6.0, "phaserrate" to 2.0, "transient" to 0.5,
                "transsustain" to -0.3, "release" to 0.2,
            ),
            vp(
                plain(sineSample(300.0, 0.5)), 0.5, "distort" to 2.0, "distorttype" to 4.0, "distortvol" to 0.5, "tremolosync" to 2.0, "tremoloskew" to 0.3,
                "tremolophase" to 0.2, "tremoloshape" to 3.0, "compressor" to -10.0, "compressorKnee" to 3.0, "compressorAttack" to 0.01, "compressorRelease" to 0.1, "phaserrate" to 5.0,
                "phaserdepth" to 0.9, "phasercenter" to 700.0, "phasersweep" to 500.0, "pan" to 0.2, "coarse" to 2.5,
            ),
            vp(synth(Waveform.PINK), 0.3, "crush" to 4.0, "room" to 0.3, "delay" to 0.3, "orbit" to 2.0, "FX" to listOf(mapOf("distort" to 2.0, "coarse" to 2.0), mapOf("tremolo" to 3.0, "compressor" to -10.0, "phaserrate" to 1.0))),
            vp(synth(Waveform.SQUARE), 0.3, "note" to 40.0, "coarse" to 1e300, "crush" to 1e9, "shape" to -3.0, "distort" to 1e9, "tremolo" to 1e12, "compressor" to 1e30, "phaserrate" to 1e12, "transient" to 1e300),
        )

        fun workload() {
            for (i in voices.indices) mixer.startVoice(voices[i])
            // a crowd above the polyphony limit steals voices
            for (i in 0 until 140) mixer.startVoice(voices[i % 3])
            for (block in 0 until 200) mixer.render(out, 256)
            // blocks that cut the render quanta
            for (block in 0 until 20) mixer.render(out, 97)
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
