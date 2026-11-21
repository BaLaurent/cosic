package cosic.audio

import cosic.io.ScopeTaps
import cosic.player.analyzeIdOf
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The `analyze` control reaches the [ScopeTaps] of the engine: voices that have one add their mono sound to the tap of that id, others leave no trace. */
class AnalyserFeedTest {
    private val frames = 1024

    /** Renders [frames] frames of [mixer] in two calls, as the engine does at an event boundary, and ends the block of the taps. */
    private fun renderBlock(mixer: Mixer, split: Int = 300): FloatArray {
        val out = FloatArray(frames * 2)
        mixer.render(out, split, 0)
        // a second call into the same block: the voices add where they sound, `frameOffset` frames after the start of the block
        mixer.render(out, frames - split, split)
        return out
    }

    @Test
    fun theIdOfTheAnalyserIsTheStringTheVisualsReadItUnder() {
        assertEquals("1", analyzeIdOf(1.0))
        assertEquals("12", analyzeIdOf(12.0))
        assertEquals("0.5", analyzeIdOf(0.5))
        assertEquals("_widget__scope_0", analyzeIdOf("_widget__scope_0"))
        assertEquals("1", analyzeIdOf("1"))
        assertNull(analyzeIdOf(0.0), "if (analyze) is false for 0")
        assertNull(analyzeIdOf(Double.NaN))
        assertNull(analyzeIdOf(""))
        assertNull(analyzeIdOf(null))
        assertNull(analyzeIdOf(false))
        assertEquals("true", analyzeIdOf(true))
    }

    @Test
    fun aStringOrNumericAnalyzeControlLandsInTheParamSetAsText() {
        for ((written, id) in listOf<Pair<Any?, String?>>(1.0 to "1", "1" to "1", "_widget__scope_0" to "_widget__scope_0", 0.0 to null, "" to null)) {
            val params = synthVp("sine", 0.1, "analyze" to written)
            assertEquals(id, params.main.str("analyze"), "analyze: $written")
        }
        assertNull(synthVp("sine", 0.1).main.str("analyze"))
    }

    @Test
    fun aVoiceWithAnalyzeAddsItsMonoSoundToTheTapOfItsId() {
        val mixer = Mixer(SR)
        assertTrue(mixer.startVoice(synthVp("sine", 0.5, "note" to 69.0, "analyze" to "w", "pan" to 0.2)))
        val out = renderBlock(mixer)
        mixer.scopes.endBlock(frames)

        val tap = FloatArray(frames)
        assertTrue(mixer.scopes.read("w", frames, tap), "the voice made sound: the tap answers")
        // with nothing else playing, what the voice sounded after its chain is what reaches the output: the down-mix of both channels
        val expected = FloatArray(frames) { (out[2 * it] + out[2 * it + 1]) * 0.5f }
        for (i in 0 until frames) assertEquals(expected[i], tap[i], 1e-6f, "frame $i")
        assertTrue(tap.maxOf { Math.abs(it) } > 0.05f, "a sine is not silence")
        // 440 Hz
        assertEquals(440.0, estimateFrequency(tap, 0, frames), 5.0)
    }

    @Test
    fun aVoiceWithoutAnalyzeLeavesNoTap() {
        val mixer = Mixer(SR)
        assertTrue(mixer.startVoice(synthVp("sine", 0.5, "note" to 69.0)))
        renderBlock(mixer)
        mixer.scopes.endBlock(frames)
        assertFalse(mixer.scopes.read("1", 8, FloatArray(8)))
        assertFalse(mixer.scopes.read("0", 8, FloatArray(8)))
        assertFalse(mixer.scopes.read("undefined", 8, FloatArray(8)))
        // not even an empty tap was made for it: a tap exists only for an id that something named
        val taps = ScopeTaps()
        val quiet = Mixer(SR, IrExecutor.INLINE, taps)
        quiet.startVoice(synthVp("sine", 0.5, "note" to 60.0, "analyze" to 0.0))
        renderBlock(quiet)
        taps.endBlock(frames)
        assertFalse(taps.read("0", 8, FloatArray(8)))
    }

    @Test
    fun voicesWithTheSameIdAreSummedAndDifferentIdsAreApart() {
        val mixer = Mixer(SR)
        mixer.startVoice(synthVp("sine", 0.5, "note" to 69.0, "analyze" to 1.0))
        mixer.startVoice(synthVp("sine", 0.5, "note" to 69.0, "analyze" to "1"))
        mixer.startVoice(synthVp("sine", 0.5, "note" to 81.0, "analyze" to 2.0))
        renderBlock(mixer)
        mixer.scopes.endBlock(frames)
        val one = FloatArray(frames)
        val two = FloatArray(frames)
        assertTrue(mixer.scopes.read("1", frames, one))
        assertTrue(mixer.scopes.read("2", frames, two))
        assertEquals(440.0, estimateFrequency(one, 0, frames), 5.0)
        assertEquals(880.0, estimateFrequency(two, 0, frames), 10.0)

        val single = Mixer(SR)
        single.startVoice(synthVp("sine", 0.5, "note" to 69.0, "analyze" to 1.0))
        renderBlock(single)
        single.scopes.endBlock(frames)
        val alone = FloatArray(frames)
        single.scopes.read("1", frames, alone)
        for (i in 0 until frames) assertEquals(2 * alone[i], one[i], 1e-5f, "two voices of id 1 are twice one voice")
    }

    @Test
    fun theTapKeepsTheLatestFramesAcrossBlocksAndAnEndedVoiceFeedsSilence() {
        val mixer = Mixer(SR)
        mixer.startVoice(synthVp("sine", 0.01, "note" to 69.0, "analyze" to "s", "release" to 0.0))
        val before = FloatArray(frames)
        renderBlock(mixer)
        mixer.scopes.endBlock(frames)
        mixer.scopes.read("s", frames, before)
        repeat(8) {
            renderBlock(mixer)
            mixer.scopes.endBlock(frames)
        }
        val after = FloatArray(frames)
        assertTrue(mixer.scopes.read("s", frames, after))
        assertTrue(after.all { it == 0f }, "the voice is over: the analyser hears silence, as the node keeps reading")
        assertTrue(before.any { it != 0f })
        assertContentEquals(FloatArray(frames), after)
    }
}
