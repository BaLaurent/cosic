package cosic.dough

import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The rules of the engine that the golden cases do not look at, and the promise that it never allocates while it renders. */
class DoughEngineTest {
    private val sr = 48000

    private fun render(engine: DoughEngine, frames: Int): FloatArray = FloatArray(frames * 2).also { engine.render(it, frames, 0) }

    private fun maxAbs(x: FloatArray, from: Int = 0, to: Int = x.size): Float {
        var m = 0f
        for (i in from until to) if (Math.abs(x[i]) > m) m = Math.abs(x[i])
        return m
    }

    @Test
    fun theThirtyThirdVoiceIsDroppedAndTheOthersPlay() {
        val engine = DoughEngine(sr)
        for (i in 0 until 40) {
            val index = engine.play(parseDoughEvent("s/sine/freq/${110 + i}"))
            assertEquals(if (i < 32) i else -1, index, "voice $i")
        }
        assertEquals(32, engine.activeVoices)
        assertTrue(maxAbs(render(engine, 512)) > 0.1f)
    }

    @Test
    fun aVoiceThatEndsIsFreedAndItsSlotReused() {
        val engine = DoughEngine(sr)
        engine.play(parseDoughEvent("s/sine/freq/220/duration/0.002"))
        engine.play(parseDoughEvent("s/saw/freq/220"))
        render(engine, 600)
        assertEquals(1, engine.activeVoices, "the short voice ended")
        assertEquals(1, engine.play(parseDoughEvent("s/tri")), "the slot after the voice that is left")
    }

    @Test
    fun filesAreRegisteredInTheFirst128SlotsUpToTheBudget() {
        val engine = DoughEngine(sr)
        val small = DoughFile(FloatArray(10), 10, 1, 65.46f)
        assertTrue(engine.registerFile(0, small))
        assertTrue(engine.registerFile(127, small))
        assertFalse(engine.registerFile(128, small))
        assertFalse(engine.registerFile(-1, small))
        assertEquals(small, engine.file(127))
        assertEquals(null, engine.file(1))
        assertEquals(null, engine.file(500))
        val huge = DoughFile(FloatArray(DOUGH_MAX_PCM_FLOATS), DOUGH_MAX_PCM_FLOATS, 1, 65.46f)
        assertFalse(engine.registerFile(1, huge), "over the budget")
        assertEquals(null, engine.file(1))
    }

    @Test
    fun aFileThatIsNotThereIsATriangle() {
        val engine = DoughEngine(sr)
        engine.play(parseDoughEvent("s/bd/file/9/freq/220"))
        val out = render(engine, 2400)
        assertTrue(maxAbs(out) > 0.05f)
        // the triangle of the engine: 0.2 * the wave, half the mix
        assertTrue(maxAbs(out) <= 0.1f + 1e-6f)
    }

    @Test
    fun anOutputThatWouldBeNaNIsSilence() {
        val engine = DoughEngine(sr)
        // `rmdepth` without `rm` modulates by a frequency that is NaN: the C code writes NaN, which would also poison a delay or reverb it sent to
        engine.play(parseDoughEvent("s/saw/freq/220/rmdepth/0.5/delay/0.5/verb/0.5"))
        engine.play(parseDoughEvent("s/sine/freq/330/delay/0.5/verb/0.5/duration/0.5"))
        val out = render(engine, 24000)
        for (v in out) assertTrue(v.isFinite(), "a value is not finite")
        assertTrue(maxAbs(out) > 0.01f, "the voice that was fine is gone too")
    }

    @Test
    fun theOutputIsAddedToWhatIsThereAndClamped() {
        val engine = DoughEngine(sr)
        engine.play(parseDoughEvent("s/const/gain/10"))
        val out = FloatArray(20) { 0.9f }
        engine.render(out, 2, 3) // frames 3 and 4: samples 6 to 9
        for (i in 0 until 6) assertEquals(0.9f, out[i], "before the frames rendered")
        for (i in 10 until 20) assertEquals(0.9f, out[i], "after the frames rendered")
        for (i in 6 until 10) assertTrue(out[i] <= 1f && out[i] >= 0.9f, "sample $i: ${out[i]}")
        engine.render(out, 1, 9)
        assertTrue(out[18] <= 1f)
    }

    @Test
    fun fadingOutSilencesTheVoicesTheDelayAndTheReverb() {
        val engine = DoughEngine(sr)
        engine.play(parseDoughEvent("s/saw/freq/110/delay/0.9/delaytime/0.1/delayfeedback/0.9/verb/0.9"))
        render(engine, 12000)
        assertTrue(maxAbs(render(engine, 2400)) > 0.01f)
        engine.fadeOut(480)
        val fade = render(engine, 480)
        // a ramp down: the end is quieter than the start
        assertTrue(maxAbs(fade, 0, 200) > maxAbs(fade, fade.size - 200, fade.size))
        assertEquals(0, engine.activeVoices)
        assertEquals(0f, maxAbs(render(engine, 48000)), "a tail came back")
    }

    @Test
    fun clearingTheTailsEmptiesTheOrbit() {
        val engine = DoughEngine(sr)
        engine.play(parseDoughEvent("s/sine/freq/440/duration/0.01/delay/1/delaytime/0.05/delayfeedback/0.8/verb/1"))
        render(engine, 4800)
        assertTrue(maxAbs(render(engine, 2400)) > 0.001f)
        engine.clearTails()
        assertEquals(0f, maxAbs(render(engine, 24000)))
    }

    @Test
    fun panicDropsEveryVoiceAndHushReleasesThem() {
        val engine = DoughEngine(sr)
        engine.play(parseDoughEvent("s/saw/freq/110"))
        engine.play(parseDoughEvent("s/sine/freq/220"))
        render(engine, 480)
        engine.run(parseDoughEvent("dough/hush"))
        render(engine, 480)
        assertEquals(0, engine.activeVoices, "released: the default release is 5 ms")
        engine.play(parseDoughEvent("s/saw/freq/110"))
        engine.run(parseDoughEvent("dough/panic"))
        assertEquals(0, engine.activeVoices)
        engine.play(parseDoughEvent("s/saw/freq/110/group/4"))
        engine.run(parseDoughEvent("dough/hush_group/group/5"))
        render(engine, 480)
        assertEquals(1, engine.activeVoices, "another group is not released")
        engine.run(parseDoughEvent("dough/hush_group/group/4"))
        render(engine, 480)
        assertEquals(0, engine.activeVoices)
    }

    // ---- no allocation -----------------------------------------------------------------------------------------------------------

    private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    private fun workload(): List<DoughEvent> = listOf(
        "s/sine/freq/220", "s/saw/freq/110/lpf/800/lpq/2/lpe/3/lpd/0.1/ftype/24db", "s/pulse/pw/0.2/hpf/400/hpe/2/hpa/0.01/ftype/48db", "s/pulze/bpf/900/bpq/3",
        "s/tri/penv/12/patt/0.01/pdec/0.1/vib/6/vibmod/1/fm/2/fmh/2/fme/1/fma/0.01", "s/zaw/am/5/amdepth/0.7/rm/30/rmdepth/0.5", "s/white/phaser/1/flanger/0.5/chorus/1",
        "s/pink/distort/2/crush/6/coarse/4/pan/0.2", "s/brown/delay/0.5/delaytime/0.1/delayfeedback/0.7/verb/0.6/verbpredelay/0.2/verbdecay/0.9", "s/const/gain/0.1/duration/0.05",
        "file/0/speed/1.5/begin/0.1/end/0.8", "file/1/speed/-1/lpf/2000/note/60", "file/0/speed/16",
        "s/sine/freq/300/group/1/voices/2/glide/0.5", "s/sine/freq/400/group/1/voices/2/glide/0.5", "s/sine/freq/500/group/1/voices/2/glide/0.5/reset/1",
        "dough/update_group/group/1/freq/600/lpf/900", "dough/update_all/gain/0.9/verb/0.2", "dough/hush_group/group/1",
    ).map { parseDoughEvent(it) } + List(40) { parseDoughEvent("s/saw/freq/${100 + it}/duration/0.05") } +
        listOf("dough/hush_endless", "dough/hush").map { parseDoughEvent(it) }

    @Test
    fun startingVoicesAndRenderingDoNotAllocate() {
        val engine = DoughEngine(sr)
        engine.registerFile(0, DoughFile(FloatArray(2400 * 2) { (it % 97) / 97f - 0.5f }, 2400, 2, 65.46f))
        engine.registerFile(1, DoughFile(FloatArray(1800) { (it % 31) / 31f - 0.5f }, 1800, 1, 65.46f))
        val out = FloatArray(512)

        fun run(events: List<DoughEvent>) {
            for (i in events.indices) { // an iterator would be an allocation of the test's own
                engine.run(events[i])
                engine.render(out, 256, 0)
            }
            for (block in 0 until 60) engine.render(out, 256, 0)
            engine.fadeOut(256)
            engine.render(out, 256, 0)
            engine.render(out, 256, 0)
            engine.panic()
        }
        run(workload())
        run(workload())
        val measured = workload()
        val before = threads.currentThreadAllocatedBytes
        run(measured)
        val allocated = threads.currentThreadAllocatedBytes - before
        assertEquals(0L, allocated, "the dough engine allocated $allocated bytes")
    }
}
