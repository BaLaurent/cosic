package cosic.audio

import cosic.kabel.FixedRandom
import cosic.kabel.GATE_CALL
import cosic.kabel.KabelFactory
import cosic.kabel.KabelInstance
import cosic.kabel.KabelRuntime
import cosic.kabel.compileKabel
import cosic.kabel.substituteWorklet
import cosic.audio.sources.asSourceControls
import cosic.audio.sources.frequencyFromValue
import cosic.pattern.CosicError
import cosic.player.voiceParams
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val sharedFactory = KabelFactory(SR)

/**
 * The voice params of an event with the kabelsalat program [workletSrc] (what `K(...)` leaves in the control map as `workletSrc` and `workletInputs`) and the hidden source
 * `s` it also has (a sawtooth unless said otherwise), made as the scheduler does it.
 */
internal fun kabelVp(
    durationSec: Double,
    workletSrc: String,
    workletInputs: List<Any?> = emptyList(),
    vararg controls: Pair<String, Any?>,
    source: String = "sawtooth",
    cps: Double = 0.5,
    factory: KabelFactory = sharedFactory,
): VoiceParams {
    val map = mapOf(*controls, "workletSrc" to workletSrc, "workletInputs" to workletInputs)
    return voiceParams(map, synth(source, durationSec, map, cps), durationSec, cps, 0.0, factory)
}

class KabelChainTest {
    // ---- the text of the program -------------------------------------------------------------------------------------

    @Test
    fun theFrequencyAndTheInputsAreSubstituted() {
        assertEquals("saw(466.1637615180899 / 2)", substituteWorklet("saw(sFreq / pat[0])", listOf(2.0), 466.1637615180899))
        assertEquals("mypat[0] undefined xpat[1] undefined", substituteWorklet("mypat[0] pat[10] xpat[1] pat[01]", listOf(1.0, 2.0, 3.0), 1.0))
        assertEquals("0.1 x true null", substituteWorklet("pat[0] pat[1] pat[2] pat[3]", listOf(0.1, "x", true, null), 1.0))
        assertEquals("5", substituteWorklet("pat[0]", listOf("sFreq"), 5.0)) // sFreq is replaced after the inputs
        assertEquals("a $GATE_CALL b $GATE_CALL", substituteWorklet("a sGate b sGate", emptyList(), 1.0))
        assertEquals("1,2 [object Object]", substituteWorklet("pat[0] pat[1]", listOf(listOf(1.0, 2.0), mapOf("a" to 1.0)), 1.0))
    }

    @Test
    fun theFrequencyIsTheOneOfTheNoteTheEventPlays() {
        fun f(vararg c: Pair<String, Any?>) = frequencyFromValue(mapOf(*c).asSourceControls())
        assertEquals(466.1637615180899, f("note" to "Bb4"), 466.1637615180899 * 1e-12)
        assertEquals(130.8127826502993, f("note" to "c3"), 130.8127826502993 * 1e-12)
        assertEquals(440.0, f("note" to 69.0), 1e-9)
        assertEquals(200.0, f("freq" to 100.0, "octave" to 1.0), 1e-9)
        assertEquals(65.40639132514966, f(), 65.40639132514966 * 1e-12)
        assertEquals(65.40639132514966, f("note" to 0.0), 65.40639132514966 * 1e-12)
        assertEquals(130.8127826502993, f("note" to 60.0, "octave" to -1.0), 130.8127826502993 * 1e-12)
    }

    // ---- the quanta of the worklet ---------------------------------------------------------------------------------------

    private fun runStage(code: String, start: Double, gateEnd: Double, end: Double, frames: Int, blockSizes: IntArray): FloatArray {
        val instance = KabelInstance(compileKabel(code), SR, KabelRuntime(), FixedRandom(0.0))
        val stage = KabelStage()
        stage.reset()
        assertTrue(stage.configure(ParamSet(kabel = instance), VoiceTiming(start, gateEnd, end, 0.5, 0.0), true))
        val block = AudioBlock(BLOCK_FRAMES)
        val ctx = BlockContext(SR)
        val out = FloatArray(frames)
        var frame = 0
        var turn = 0
        while (frame < frames) {
            val n = minOf(blockSizes[turn++ % blockSizes.size], frames - frame)
            block.clear(n)
            ctx.startFrame = frame.toLong()
            ctx.n = n
            stage.process(block, ctx)
            assertEquals(2, block.channels)
            System.arraycopy(block.l, 0, out, frame, n)
            frame += n
        }
        return out
    }

    @Test
    fun theProgramStartsAndEndsOnTheQuantumGrid() {
        // onset 0.01 s = frame 480: the first quantum that starts at or after it is frame 512; the end 0.02 s = frame 960: the first quantum at or after it is 1024
        for (blocks in listOf(intArrayOf(256), intArrayOf(37, 91, 128, 200))) {
            val out = runStage("n(1).out()", 0.01, 0.015, 0.02, 1400, blocks)
            for (i in 0 until 512) assertEquals(0f, out[i], "frame $i before the first quantum")
            for (i in 512 until 1024) assertEquals(1f, out[i], "frame $i while it runs")
            for (i in 1024 until 1400) assertEquals(0f, out[i], "frame $i after it is gone")
        }
    }

    @Test
    fun theGateEndsAtTheFirstQuantumAfterItsEnd() {
        // the gate ends at 0.015 s = frame 720: the quantum at 768 is the first that starts after it
        for (blocks in listOf(intArrayOf(256), intArrayOf(37, 91, 128, 200))) {
            val out = runStage("cc('strudel-gate-1').out()", 0.01, 0.015, 0.02, 1100, blocks)
            for (i in 512 until 768) assertEquals(1f, out[i], "frame $i with the gate on")
            for (i in 768 until 1024) assertEquals(0f, out[i], "frame $i with the gate off")
        }
    }

    @Test
    fun aNoteThatIsOverBeforeItsFirstQuantumNeverHasItsGateOn() {
        // the onset is at 0.0123456 s (frame 592.6) and the gate ends 0.0005 s later (frame 616.6): the first quantum that runs starts at frame 640, past the end of the
        // gate, so the gate is off from the first sample and the envelope never starts
        val out = runStage("cc('strudel-gate-1').adsr(0, 0.1, 1, 0.1).out()", 0.0123456, 0.0128456, 0.2, 2000, intArrayOf(256))
        assertTrue(out.all { it == 0f }, "the envelope was triggered")
        // with the gate on at the first quantum it is
        val triggered = runStage("cc('strudel-gate-1').adsr(0, 0.1, 1, 0.1).out()", 0.0123456, 0.1, 0.2, 2000, intArrayOf(256))
        assertTrue(triggered.any { it > 0.5f })
    }

    @Test
    fun theProgramReadsTheIncomingAudioAsAudioin() {
        val instance = KabelInstance(compileKabel("audioin().mul(0.5).out()"), SR, KabelRuntime(), FixedRandom(0.0))
        val stage = KabelStage()
        stage.reset()
        stage.configure(ParamSet(kabel = instance), VoiceTiming(0.0, 1.0, 1.0, 0.5, 0.0), true)
        val block = AudioBlock(BLOCK_FRAMES)
        val ctx = BlockContext(SR)
        block.clear(256)
        for (i in 0 until 256) block.l[i] = (i % 7).toFloat() / 7f
        val expected = FloatArray(256) { block.l[it] * 0.5f }
        ctx.startFrame = 0
        ctx.n = 256
        stage.process(block, ctx)
        assertContentEquals(expected, block.l.copyOf(256))
        assertContentEquals(expected, block.r.copyOf(256))
    }

    @Test
    fun aChainWithoutAProgramLeavesTheStageOut() {
        val stage = KabelStage()
        assertTrue(!stage.configure(ParamSet(), VoiceTiming(), true))
    }

    // ---- the voice -----------------------------------------------------------------------------------------------------------

    @Test
    fun aVoiceWithAProgramPlaysTheProgram() {
        val params = kabelVp(0.2, "saw(sFreq).mul(sGate.ad(0.01, 0.1)).out()", emptyList(), "note" to 69.0)
        val mixer = Mixer(SR)
        assertTrue(mixer.startVoice(params))
        val out = channel(mixer.renderFrames(SR / 2), 0)
        // the program is a sawtooth at 440 Hz with an envelope; the hidden source is a sawtooth at the same pitch, which the program does not play
        assertEquals(440.0, estimateFrequency(out, 2400, 7200), 3.0)
        var peak = 0f
        for (i in 2400 until 7200) peak = maxOf(peak, abs(out[i]))
        assertTrue(peak > 0.05f, "the program is silent: peak $peak")
        // the envelope ad(0.01, 0.1) has died by 0.12 s plus the release; the program is gone at 0.21 s and so is the voice
        for (i in (0.26 * SR).toInt() until out.size) assertEquals(0f, out[i], "frame $i after the voice")
    }

    @Test
    fun aProgramThatReadsAudioinHearsTheHiddenSource() {
        // `audioin()` is the source of the event, after its own envelope: a K effect on a sawtooth
        val params = kabelVp(0.3, "audioin().mul(0.5).out()", emptyList(), "note" to 57.0)
        val mixer = Mixer(SR)
        assertTrue(mixer.startVoice(params))
        val out = channel(mixer.renderFrames(SR / 2), 0)
        assertEquals(220.0, estimateFrequency(out, 4800, 12000), 2.0)
    }

    @Test
    fun theFxElementsAndTheEventEachRunTheirOwnProgram() {
        val fx = mapOf("workletSrc" to "audioin().mul(0.5).out()", "workletInputs" to emptyList<Any?>())
        val map = mapOf(
            "note" to 69.0, "FX" to listOf(fx), "workletSrc" to "audioin().add(saw(sFreq).mul(0.2)).out()", "workletInputs" to emptyList<Any?>(),
        )
        val params = voiceParams(map, synth("sawtooth", 0.3, map), 0.3, 0.5, 0.0, sharedFactory)
        assertNotNull(params.fx[0].kabel)
        assertNotNull(params.main.kabel)
        val mixer = Mixer(SR)
        assertTrue(mixer.startVoice(params))
        val out = mixer.renderFrames(SR / 4)
        assertTrue(out.any { abs(it) > 0.01f })
        assertTrue(out.all { it.isFinite() })
    }

    @Test
    fun anEventWithoutAProgramIsUnchanged() {
        val params = synthVp("sawtooth", 0.2, "note" to 69.0)
        assertNull(params.main.kabel)
    }

    // ---- the factory -----------------------------------------------------------------------------------------------------------

    @Test
    fun aProgramIsCompiledOnceForTheSameText() {
        val factory = KabelFactory(SR)
        val controls = mapOf("note" to 69.0)
        val a = factory.create("saw(sFreq).out()", emptyList<Any?>(), controls.asSourceControls())
        val b = factory.create("saw(sFreq).out()", emptyList<Any?>(), controls.asSourceControls())
        assertNotNull(a)
        assertNotNull(b)
        assertEquals(1, factory.compiled)
        assertTrue(a !== b)
        factory.create("saw(sFreq * 2).out()", emptyList<Any?>(), controls.asSourceControls())
        assertEquals(2, factory.compiled)
    }

    @Test
    fun aProgramThatDoesNotCompileIsAnErrorOfTheEventAndRemembered() {
        val factory = KabelFactory(SR)
        val controls = mapOf("note" to 69.0).asSourceControls()
        val first = assertFailsWith<CosicError> { factory.create("sawz(220).out()", emptyList<Any?>(), controls) }
        assertEquals("sawz is not defined", first.message)
        assertNull(first.range)
        val again = assertFailsWith<CosicError> { factory.create("sawz(220).out()", emptyList<Any?>(), controls) }
        assertEquals("sawz is not defined", again.message)
        assertEquals(0, factory.compiled)
    }

    @Test
    fun aProgramWhoseWorkletWouldNotLoadIsSilent() {
        val factory = KabelFactory(SR)
        val instance = factory.create("n(-2).pow(2).out()", emptyList<Any?>(), emptyMap<String, Any?>().asSourceControls())
        assertNotNull(instance)
        assertTrue(instance.dead)
        instance.sample(0.0)
        assertEquals(0.0, instance.left)
    }

    @Test
    fun aChainWithoutAWorkletSrcGetsNoInstance() {
        val factory = KabelFactory(SR)
        assertNull(factory.create(null, null, emptyMap<String, Any?>().asSourceControls()))
        assertNull(factory.create(cosic.pattern.Undefined, null, emptyMap<String, Any?>().asSourceControls()))
    }

    @Test
    fun warmingTheCacheLooksAheadWithoutUsingUpTheNoiseChannels() {
        val runtime = KabelRuntime()
        val factory = KabelFactory(SR, runtime = runtime)
        factory.warm(mapOf("workletSrc" to "rng().add(rng()).out()", "workletInputs" to emptyList<Any?>(), "note" to 69.0))
        assertEquals(0, runtime.noiseChannels)
        assertEquals(1, factory.compiled)
        factory.create("rng().add(rng()).out()", emptyList<Any?>(), mapOf("note" to 69.0).asSourceControls())
        assertEquals(2, runtime.noiseChannels)
        assertEquals(1, factory.compiled) // the warm-up compiled it
    }

    @Test
    fun theProgramsOfOneEventAreMadeInTheOrderOfTheChains() {
        // the generators take their seeds in the order they are made: the FX element's first, the event's last, as superdough sends them to the worklet
        val runtime = KabelRuntime()
        val factory = KabelFactory(SR, runtime = runtime)
        val fx = mapOf("workletSrc" to "rng().out()", "workletInputs" to emptyList<Any?>())
        val map = mapOf("note" to 69.0, "FX" to listOf(fx), "workletSrc" to "rng().out()", "workletInputs" to emptyList<Any?>())
        val params = voiceParams(map, synth("sawtooth", 0.3, map), 0.3, 0.5, 0.0, factory)
        val first = params.fx[0].kabel!!
        val second = params.main.kabel!!
        first.sample(0.0)
        second.sample(0.0)
        // channel 0 has seed 340, channel 1 seed 680: their first values differ
        assertTrue(first.left != second.left)
        assertEquals(2, runtime.noiseChannels)
    }
}
