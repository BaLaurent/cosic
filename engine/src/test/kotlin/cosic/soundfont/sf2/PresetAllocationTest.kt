package cosic.soundfont.sf2

import cosic.audio.Mixer
import cosic.audio.SR
import cosic.audio.VoiceSource
import cosic.audio.vp
import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The audio thread must not allocate while a preset note starts and renders, as for every other source (RenderAllocationTest). */
class PresetAllocationTest {
    private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    @Test
    fun startingAndRenderingPresetNotesDoesNotAllocate() {
        val file = Sf2File.parse(sf2Resource("test.sf2"))
        val one = presetNote(file.presets[0], 64.0)
        val three = presetNote(file.presets[1], 45.0)
        assertEquals(1, one.size)
        assertEquals(3, three.size)
        val voices = arrayOf(
            vp(VoiceSource.Preset(one), 0.4, "gain" to 1.0),
            vp(VoiceSource.Preset(three), 0.5, "room" to 0.3, "pan" to 0.2),
        )
        val mixer = Mixer(SR)
        val out = FloatArray(512)

        fun workload(): Float {
            var peak = 0f
            for (i in voices.indices) mixer.startVoice(voices[i])
            for (i in 0 until 40) mixer.startVoice(voices[i % 2])
            for (block in 0 until 200) {
                mixer.render(out, 256)
                for (v in out) if (v > peak) peak = v
            }
            mixer.stopAll(5)
            mixer.render(out, 256)
            return peak
        }

        assertTrue(workload() > 0.01f, "the notes sound")
        workload()
        workload()
        // a run that allocates nothing shows the path does not; the JIT compiler can make a run on a busy machine allocate a few bytes of its own
        val allocated = (1..3).minOf {
            val before = threads.currentThreadAllocatedBytes
            workload()
            threads.currentThreadAllocatedBytes - before
        }
        assertEquals(0L, allocated, "rendering allocated $allocated bytes")
    }
}
