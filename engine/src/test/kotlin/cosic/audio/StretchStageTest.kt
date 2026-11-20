package cosic.audio

import cosic.audio.dsp.PhaseVocoder
import cosic.pattern.Undefined
import cosic.player.paramSetOf
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** `stretch`: the phase vocoder as a stage of the chain, its pool, and the early start that cancels its latency. */
class StretchStageTest {
    /** One FFT bin of the vocoder (2048 points). The vocoder moves peaks by whole bins, so a tone does not land on exactly twice its frequency. */
    private val bin = SR / 2048.0

    private fun vocoderPool() = GrowablePool(8, 0) { PhaseVocoder() }

    /** Runs [seconds] of a stage fed by [fill] (left, right as functions of the absolute frame) and returns the output of the first channel(s). */
    private fun run(
        stage: StretchStage, seconds: Double, channels: Int = 1, block: Int = 256,
        left: (Int) -> Float, right: (Int) -> Float = { 0f },
    ): Pair<FloatArray, FloatArray> {
        val frames = (seconds * SR).toInt()
        val outLeft = FloatArray(frames)
        val outRight = FloatArray(frames)
        val b = AudioBlock(BLOCK_FRAMES)
        val ctx = BlockContext(SR)
        var done = 0
        while (done < frames) {
            val n = Math.min(block, frames - done)
            b.channels = channels
            for (i in 0 until n) {
                b.l[i] = left(done + i)
                b.r[i] = right(done + i)
            }
            ctx.startFrame = done.toLong()
            ctx.n = n
            stage.process(b, ctx)
            System.arraycopy(b.l, 0, outLeft, done, n)
            System.arraycopy(b.r, 0, outRight, done, n)
            done += n
        }
        return outLeft to outRight
    }

    private fun sine(hz: Double): (Int) -> Float = { sin(2.0 * PI * hz * it / SR).toFloat() }

    /** The frequency of the strongest component of 16384 frames of [x] from [from]: a Hann-windowed FFT, the peak refined by a parabola through its log magnitude. */
    private fun dominantFrequency(x: FloatArray, from: Int): Double {
        val n = 16_384
        val re = DoubleArray(n) { x[from + it] * (0.5 - 0.5 * Math.cos(2.0 * PI * it / n)) }
        val im = DoubleArray(n)
        Fft(n).forward(re, im)
        val magnitude = DoubleArray(n / 2) { Math.sqrt(re[it] * re[it] + im[it] * im[it]) }
        var best = 1
        for (k in 1 until n / 2) if (magnitude[k] > magnitude[best]) best = k
        val alpha = Math.log(magnitude[best - 1])
        val beta = Math.log(magnitude[best])
        val gamma = Math.log(magnitude[best + 1])
        val offset = 0.5 * (alpha - gamma) / (alpha - 2 * beta + gamma)
        return (best + offset) * SR / n
    }

    private fun configured(stretch: Double, pool: GrowablePool<PhaseVocoder> = vocoderPool()): StretchStage {
        val stage = StretchStage(SR, pool)
        stage.reset()
        assertTrue(stage.configure(paramSetOf(mapOf("stretch" to stretch)), VoiceTiming(), true))
        return stage
    }

    // ------------------------------------------------------------------ pitch

    @Test
    fun stretchMovesATonePeakByWholeBins() {
        // 440 Hz is 18.77 bins: its peak is bin 19; one octave up moves it to bin 38, 19 bins = 445.3 Hz higher
        val (up, _) = run(configured(1.0), 1.0, left = sine(440.0))
        assertEquals(440.0 + 19 * bin, dominantFrequency(up, 20_000), 1.0)
        // -2 is a factor of max(0, -2 / 4 + 1) = 0.5: bin 19 goes to round(9.5) = 10, 9 bins lower
        val (down, _) = run(configured(-2.0), 1.0, left = sine(440.0))
        assertEquals(440.0 - 9 * bin, dominantFrequency(down, 20_000), 1.0)
        // 0 leaves the pitch alone
        val (same, _) = run(configured(0.0), 1.0, left = sine(440.0))
        assertEquals(440.0, dominantFrequency(same, 20_000), 0.5)
    }

    @Test
    fun aToneThatIsAnExactNumberOfBinsGoesToExactlyTwiceItsFrequency() {
        val tone = 40 * bin // 937.5 Hz
        val (up, _) = run(configured(1.0), 1.0, left = sine(tone))
        assertEquals(2 * tone, estimateFrequency(up, 12_000, 40_000), 0.5)
    }

    @Test
    fun theOutputIsTheInputDelayedByTheStreamingLatencyAtAboutUnityGain() {
        val tone = 40 * bin
        val (out, _) = run(configured(0.0), 0.5, left = sine(tone))
        val delay = PhaseVocoder.STREAM_LATENCY_FRAMES
        assertEquals(2048, delay)
        var worst = 0.0
        for (n in 8192 until 20_000) worst = Math.max(worst, abs(out[n] - 0.98415 * sine(tone)(n - delay)).toDouble())
        assertTrue(worst < 2e-3, "the output is 0.984 times the input of 2048 frames ago: worst difference $worst")
        assertTrue(peak(out, 0, delay - 128) < 1e-3, "nothing comes out before the latency has passed")
    }

    @Test
    fun theChannelsOfAStereoSignalAreShiftedIndependently() {
        val stage = configured(1.0)
        val (left, right) = run(stage, 1.0, channels = 2, left = sine(440.0), right = sine(660.0))
        assertEquals(440.0 + 19 * bin, estimateFrequency(left, 12_000, 40_000), 1.5)
        // 660 Hz is bin 28.2: the peak is bin 28, which moves 28 bins up
        assertEquals(660.0 + 28 * bin, estimateFrequency(right, 12_000, 40_000), 1.5)
    }

    @Test
    fun aMonoBlockStaysMono() {
        val stage = configured(1.0)
        val b = AudioBlock(BLOCK_FRAMES)
        val ctx = BlockContext(SR)
        b.channels = 1
        b.r.fill(7f)
        ctx.n = 256
        stage.process(b, ctx)
        assertEquals(1, b.channels)
        assertEquals(7f, b.r[0], "the right channel is not touched")
    }

    @Test
    fun aMovingStretchIsReadAtTheLastFrameOfEveryQuantum() {
        val stage = configured(0.0)
        stage.pitchFactor.setValueAtTime(1.0, 0.2) // from 0.2 s on the control is 1: an octave up
        assertTrue(!stage.pitchFactor.isConstant)
        val (out, _) = run(stage, 1.0, left = sine(40 * bin))
        assertEquals(40 * bin, estimateFrequency(out, 3000, 9000), 0.5, "before the change (output lags the input by 43 ms)")
        assertEquals(80 * bin, estimateFrequency(out, 16_000, 44_000), 0.5, "after it")
    }

    @Test
    fun theStageRegistersItsParameterForModulators() {
        val registry = NodeRegistry(sampleRate = SR)
        val stage = StretchStage(SR, vocoderPool())
        stage.registerParams(registry)
        assertSame(stage.pitchFactor, registry.find("stretch", "pitchFactor"))
        assertEquals("pitchFactor", ControlTargets.lookup("stretch", null)!!.param)
    }

    // ------------------------------------------------------------------ the pool

    @Test
    fun aStageTakesAVocoderWhenTheVoiceStartsAndGivesItBackWhenItEnds() {
        val pool = vocoderPool()
        val stage = configured(1.0, pool)
        assertEquals(1, pool.inUse)
        stage.release()
        assertEquals(0, pool.inUse)
        stage.release() // harmlessly more often
        assertEquals(0, pool.inUse)
        assertTrue(stage.configure(paramSetOf(mapOf("stretch" to 1.0)), VoiceTiming(), true))
        stage.reset() // a stage that is reset before it was released gives its vocoder back as well
        assertEquals(0, pool.inUse)
    }

    @Test
    fun aStageIsOnlyInTheChainWhenStretchIsSet() {
        val stage = StretchStage(SR, vocoderPool())
        assertTrue(!stage.configure(paramSetOf(mapOf("gain" to 1.0)), VoiceTiming(), true))
    }

    @Test
    fun aStageWithNoVocoderLeftSaysSoAndLeavesTheChain() {
        val pool = GrowablePool(1, 0) { PhaseVocoder() }
        configured(1.0, pool)
        val second = StretchStage(SR, pool)
        assertTrue(!second.configure(paramSetOf(mapOf("stretch" to 1.0)), VoiceTiming(), true))
    }

    @Test
    fun everyVoiceWithStretchGetsAVocoderEvenWhenThePoolStartedEmpty() {
        val mixer = Mixer(SR)
        repeat(12) { assertTrue(mixer.startVoice(synthVp("sine", 1.0, "note" to 69.0, "stretch" to 1.0, "gain" to 0.05))) }
        assertEquals(12, mixer.fxPool.vocoders.inUse)
        assertEquals(12, mixer.fxPool.vocoders.madeOnTheSpot, "nobody prepared the pool, and no voice went without")
        mixer.renderFrames(SR * 3) // all over
        assertEquals(0, mixer.fxPool.vocoders.inUse, "the vocoders came back")
    }

    @Test
    fun aPreparedMixerHasTheVocodersBeforeTheEventsStart() {
        val mixer = Mixer(SR)
        val voice = synthVp("sine", 1.0, "note" to 69.0, "stretch" to 1.0)
        val demand = ChainDemand().also { d -> repeat(10) { d.add(voice) } }
        mixer.prepare(demand)
        assertTrue(mixer.fxPool.vocoders.capacity >= 10)
        repeat(10) { mixer.startVoice(synthVp("sine", 1.0, "note" to 69.0, "stretch" to 1.0)) }
        assertEquals(0, mixer.fxPool.vocoders.madeOnTheSpot)
    }

    @Test
    fun aVoiceWithStretchSoundsAnOctaveHigherAndTheSourceIsTheSameVoiceAsWithout() {
        val plain = renderLeft(synthVp("sine", 1.5, "note" to 69.0, "gain" to 1.0), SR)
        val stretched = renderLeft(synthVp("sine", 1.5, "note" to 69.0, "stretch" to 1.0, "gain" to 1.0), SR)
        assertEquals(440.0, estimateFrequency(plain, 12_000, 40_000), 0.5)
        assertEquals(440.0 + 19 * bin, estimateFrequency(stretched, 12_000, 40_000), 1.5)
        assertTrue(rms(stretched, 12_000, 40_000) > 0.5 * rms(plain, 12_000, 40_000), "and it is not silent")
        // the vocoder delivers (almost) nothing before its latency: the little there is is the spectral smearing of the first windows
        assertTrue(rms(stretched, 0, 1800) < 0.01 * rms(plain, 12_000, 40_000), "before the latency")
        assertTrue(rms(stretched, 2048, 3000) > 0.9 * rms(plain, 12_000, 40_000), "and the full level from frame 2048 on")
    }

    // ------------------------------------------------------------------ the early start

    @Test
    fun anEventWithStretchStartsOneVocoderLatencyEarly() {
        assertEquals(2048, stretchLeadFrames(mapOf("stretch" to 1.0)))
        assertEquals(2048, stretchLeadFrames(mapOf("stretch" to 0.0)), "0 is a value too: superdough only asks whether it is set")
        assertEquals(2048, stretchLeadFrames(mapOf("s" to "sine", "stretch" to -2.0)))
        assertEquals(0, stretchLeadFrames(mapOf("s" to "sine")))
        assertEquals(0, stretchLeadFrames(mapOf("stretch" to null)))
        assertEquals(0, stretchLeadFrames(mapOf("stretch" to Undefined)))
        assertEquals(0, stretchLeadFrames(mapOf("FX" to listOf(mapOf("stretch" to 1.0)))), "only the event's own stretch counts, not that of an FX element")
    }
}
