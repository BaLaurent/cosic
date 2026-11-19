package cosic.audio

import cosic.BankInfo
import cosic.SampleData
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Voice resolution, clamping, pool management and the shared buses, all through [Mixer]. */
class MixerTest {
    private val sine = VoiceSource.Synth(Waveform.SINE)

    /** A sine with a flat envelope (sustain 1 after 1 ms), so level assertions do not depend on the ADSR shape. */
    private fun flat(source: VoiceSource = sine, duration: Double = 2.0, note: Double? = 69.0) =
        VoiceParams(source, duration, note = note, attack = 0.001, decay = 0.0, sustain = 1.0)

    private fun render(params: VoiceParams, frames: Int = SR / 2): FloatArray {
        val mixer = Mixer(SR)
        assertTrue(mixer.startVoice(params), "voice was skipped")
        return mixer.renderFrames(frames)
    }

    private fun leftOf(params: VoiceParams, frames: Int = SR / 2) = channel(render(params, frames), 0)

    private fun leftPeak(params: VoiceParams) = peak(leftOf(params), 4800)

    private fun leftRms(params: VoiceParams) = rms(leftOf(params), 4800)

    private fun meanAbs(x: FloatArray, from: Int, to: Int): Double {
        var sum = 0.0
        for (i in from until to) sum += abs(x[i])
        return sum / (to - from)
    }

    private val centerGain = cos(PI / 4) * 0.8 // equal-power pan at 0.5 times the master gain

    // ------------------------------------------------------------ level and pan

    @Test
    fun sineVoiceHasBaseLevelCenterPanAndMasterGain() {
        val out = render(flat())
        val left = channel(out, 0)
        val right = channel(out, 1)
        assertEquals(0.35 * centerGain, peak(left, 4800).toDouble(), 0.002)
        for (i in left.indices) assertEquals(left[i], right[i], 1e-6f)
        assertEquals(440.0, estimateFrequency(left, 4800, left.size), 0.3)
    }

    @Test
    fun noiseVoicesUseTheLowerBaseLevel() {
        val white = leftOf(flat(VoiceSource.Synth(Waveform.WHITE)))
        assertTrue(peak(white) <= 0.25f * centerGain.toFloat() + 1e-4f)
        assertTrue(peak(white) > 0.2f * centerGain.toFloat())
    }

    @Test
    fun sampleVoicesPlayAtUnityLevel() {
        val left = leftOf(flat(VoiceSource.Sample(constantSample(1.0), null), note = null))
        assertEquals(centerGain, left[SR / 4].toDouble(), 1e-4)
    }

    @Test
    fun hardPanSendsTheVoiceToOneChannel() {
        val hardLeft = render(flat().copy(pan = 0.0))
        assertEquals(0.28, peak(channel(hardLeft, 0), 4800).toDouble(), 0.002)
        assertTrue(peak(channel(hardLeft, 1)) < 1e-6f)
        val hardRight = render(flat().copy(pan = 1.0))
        assertEquals(0.28, peak(channel(hardRight, 1), 4800).toDouble(), 0.002)
        assertTrue(peak(channel(hardRight, 0)) < 1e-6f)
    }

    @Test
    fun gainVelocityAndPostgainMultiply() {
        val base = leftPeak(flat())
        assertEquals(base * 0.25f, leftPeak(flat().copy(gain = 0.5, velocity = 0.5)), 0.002f)
        assertEquals(base * 0.5f, leftPeak(flat().copy(gain = 0.5, velocity = 0.5, postgain = 2.0)), 0.002f)
    }

    @Test
    fun controlsAreClampedWhenTheVoiceStarts() {
        val base = flat()
        assertEquals(leftPeak(base.copy(gain = 4.0)), leftPeak(base.copy(gain = 100.0)), 1e-6f)
        assertEquals(leftPeak(base.copy(gain = 4.0)), leftPeak(base.copy(gain = Double.POSITIVE_INFINITY)), 1e-6f)
        assertEquals(0f, leftPeak(base.copy(gain = -3.0)), 1e-7f)
        assertEquals(leftPeak(base), leftPeak(base.copy(velocity = 7.0)), 1e-6f)
        assertEquals(0f, leftPeak(base.copy(velocity = -1.0)), 1e-7f)
        assertEquals(leftPeak(base.copy(postgain = 4.0)), leftPeak(base.copy(postgain = 99.0)), 1e-6f)
        assertEquals(leftPeak(base.copy(pan = 0.0)), leftPeak(base.copy(pan = -2.0)), 1e-6f)
        assertEquals(leftPeak(base.copy(pan = 1.0)), leftPeak(base.copy(pan = 9.0)), 1e-6f)
        assertEquals(leftPeak(base.copy(shape = 0.99)), leftPeak(base.copy(shape = 5.0)), 1e-6f)
    }

    private fun allNaN(source: VoiceSource, duration: Double): VoiceParams {
        val n = Double.NaN
        return VoiceParams(
            source, duration,
            note = n, gain = n, velocity = n, postgain = n, pan = n, speed = n, begin = n, end = n, cut = n, clip = n,
            attack = n, decay = n, sustain = n, release = n, lpf = n, lpq = n, hpf = n, hpq = n, bpf = n, bpq = n,
            shape = n, crush = n, coarse = n, delay = n, delaytime = n, delayfeedback = n, room = n, size = n,
        )
    }

    @Test
    fun notANumberControlsFallBackToTheirDefaults() {
        val synth = VoiceSource.Synth(Waveform.SAWTOOTH)
        assertContentEquals(render(VoiceParams(synth, 0.3)), render(allNaN(synth, 0.3)))
        val sample = VoiceSource.Sample(sineSample(150.0, 0.2), 48)
        assertContentEquals(render(VoiceParams(sample, 0.3)), render(allNaN(sample, 0.3)))
    }

    @Test
    fun shapeFollowsTheWaveshaperFormula() {
        // x = 0.35, s = 0.9 -> k = 18 -> y = x (1 + k) / (1 + k |x|)
        val expected = 0.35 * 19 / (1 + 18 * 0.35) * centerGain
        assertEquals(expected, leftPeak(flat().copy(shape = 0.9)).toDouble(), 0.005)
        assertEquals(leftPeak(flat()), leftPeak(flat().copy(shape = 0.0)), 1e-6f)
    }

    @Test
    fun crushQuantisesTheVoiceToFewLevels() {
        val left = leftOf(flat().copy(crush = 2.0))
        val levels = HashSet<Int>()
        for (i in 4800 until left.size) levels.add((left[i] + 0f).toBits())
        assertEquals(3, levels.size, "2 bits keep the levels -x, 0 and +x")
        assertEquals(0.5 * centerGain, peak(left, 4800).toDouble(), 0.001)
    }

    @Test
    fun coarseHoldsEveryNthSample() {
        // 6 does not divide the 256-frame block, so a counter that restarted with every block would misalign the groups.
        val left = leftOf(flat().copy(coarse = 6.0))
        for (i in 4800 until left.size) assertEquals(left[i - i % 6], left[i], "frame $i")
        assertTrue(left[4800] != left[4806], "the held value must change between groups")
    }

    @Test
    fun lowPassParameterAttenuatesTheVoice() {
        val base = flat(note = noteForFrequency(5000.0))
        assertTrue(dbOf(leftRms(base.copy(lpf = 500.0)) / leftRms(base)) < -20.0)
        // 1e9 Hz clamps to 0.45 x sample rate, which still passes 5 kHz.
        assertEquals(0.0, dbOf(leftRms(base.copy(lpf = 1e9)) / leftRms(base)), 0.5)
    }

    @Test
    fun highPassAndBandPassParametersShapeTheVoice() {
        val low = flat(note = noteForFrequency(100.0))
        assertTrue(dbOf(leftRms(low.copy(hpf = 2000.0)) / leftRms(low)) < -20.0)
        val mid = flat(note = noteForFrequency(1000.0))
        assertEquals(0.0, dbOf(leftRms(mid.copy(bpf = 1000.0)) / leftRms(mid)), 0.5) // bpq defaults to 1: unity gain
        assertTrue(dbOf(leftRms(low.copy(bpf = 1000.0, bpq = 4.0)) / leftRms(low)) < -20.0)
    }

    // ----------------------------------------------------------- gate and ends

    @Test
    fun synthGateEndsTheVoiceAfterItsRelease() {
        val mixer = Mixer(SR)
        mixer.startVoice(VoiceParams(sine, 0.1)) // default synth ADSR: release 0.05 s after the 0.1 s gate
        mixer.renderFrames(SR * 12 / 100)
        assertEquals(1, mixer.activeVoices)
        val tail = channel(mixer.renderFrames(SR / 10), 0)
        assertEquals(0, mixer.activeVoices)
        assertEquals(0f, peak(tail, 1500), "the voice must be silent once its release is over")
    }

    @Test
    fun clipScalesTheGateOfASynthVoice() {
        fun activeAfter(params: VoiceParams, seconds: Double): Int {
            val mixer = Mixer(SR)
            mixer.startVoice(params)
            mixer.renderFrames((seconds * SR).toInt())
            return mixer.activeVoices
        }
        val halfGate = VoiceParams(sine, 0.2, clip = 0.5) // gate 0.1 s + release 0.05 s
        assertEquals(1, activeAfter(halfGate, 0.14))
        assertEquals(0, activeAfter(halfGate, 0.16))
        val doubleGate = VoiceParams(sine, 0.1, clip = 2.0) // gate 0.2 s
        assertEquals(1, activeAfter(doubleGate, 0.22))
        assertEquals(0, activeAfter(doubleGate, 0.26))
    }

    @Test
    fun sampleVoiceOutlivesItsDurationUnlessClipped() {
        val data = constantSample(0.3)
        val free = Mixer(SR).also { it.startVoice(VoiceParams(VoiceSource.Sample(data, null), 0.05)) }
        free.renderFrames(SR / 4)
        assertEquals(1, free.activeVoices, "without clip the gate is unlimited")
        free.renderFrames(SR / 10)
        assertEquals(0, free.activeVoices, "the sample ends after 0.3 s")

        val clipped = Mixer(SR).also { it.startVoice(VoiceParams(VoiceSource.Sample(data, null), 0.05, clip = 1.0)) }
        val out = channel(clipped.renderFrames(SR * 7 / 100), 0) // gate 0.05 s + release 0.01 s
        assertEquals(0, clipped.activeVoices)
        assertTrue(out[SR * 4 / 100] > 0.5f, "still sounding inside the gate")
        assertEquals(0f, peak(out, SR * 65 / 1000), "silent after the release")
    }

    // ------------------------------------------------------------------ samples

    @Test
    fun pitchedSampleIsTransposedByTheDistanceFromItsRootNote() {
        val data = sineSample(100.0, 1.0)
        fun left(note: Double?) = leftOf(VoiceParams(VoiceSource.Sample(data, 48), 1.0, note = note))
        assertEquals(200.0, estimateFrequency(left(60.0), 2400, 20_000), 0.5)
        assertEquals(100.0, estimateFrequency(left(null), 2400, 20_000), 0.5) // note defaults to 48, the root
        assertEquals(50.0, estimateFrequency(left(36.0), 2400, 20_000), 0.5)

        val mixer = Mixer(SR)
        mixer.startVoice(VoiceParams(VoiceSource.Sample(data, 48), 1.0, note = 60.0))
        mixer.renderFrames(SR * 45 / 100)
        assertEquals(1, mixer.activeVoices)
        mixer.renderFrames(SR / 10)
        assertEquals(0, mixer.activeVoices, "an octave up halves the playing time")
    }

    @Test
    fun unpitchedSampleKeepsItsRateUnlessNoteIsSet() {
        val data = sineSample(100.0, 1.0)
        fun left(note: Double?) = leftOf(VoiceParams(VoiceSource.Sample(data, null), 1.0, note = note))
        assertEquals(100.0, estimateFrequency(left(null), 2400, 20_000), 0.5)
        assertEquals(200.0, estimateFrequency(left(60.0), 2400, 20_000), 0.5)
        assertEquals(50.0, estimateFrequency(left(36.0), 2400, 20_000), 0.5)
    }

    @Test
    fun fileSampleRateIsConvertedToTheEngineRate() {
        val data = sineSample(100.0, 1.0, rate = 22_050)
        val left = leftOf(VoiceParams(VoiceSource.Sample(data, null), 1.0), 45_000)
        assertEquals(100.0, estimateFrequency(left, 4800, 40_000), 0.3)

        val mixer = Mixer(SR)
        mixer.startVoice(VoiceParams(VoiceSource.Sample(data, null), 1.0))
        mixer.renderFrames(SR * 95 / 100)
        assertEquals(1, mixer.activeVoices)
        mixer.renderFrames(SR / 10)
        assertEquals(0, mixer.activeVoices)

        val cd = Mixer(44_100)
        cd.startVoice(flat())
        assertEquals(440.0, estimateFrequency(channel(cd.renderFrames(22_050), 0), 4410, 22_050, 44_100), 0.3)
    }

    @Test
    fun speedScalesPlaybackAndIsClampedToEight() {
        val data = sineSample(100.0, 1.0)
        fun left(speed: Double) = leftOf(VoiceParams(VoiceSource.Sample(data, null), 1.0, speed = speed))
        assertEquals(200.0, estimateFrequency(left(2.0), 2400, 20_000), 0.5)
        assertEquals(50.0, estimateFrequency(left(0.5), 2400, 20_000), 0.5)

        val mixer = Mixer(SR)
        mixer.startVoice(VoiceParams(VoiceSource.Sample(data, null), 1.0, speed = 100.0))
        mixer.renderFrames(SR / 10)
        assertEquals(1, mixer.activeVoices, "speed 100 clamps to 8: 1 s of audio lasts 0.125 s")
        mixer.renderFrames(SR / 20)
        assertEquals(0, mixer.activeVoices)
    }

    @Test
    fun negativeSpeedPlaysTheSampleBackwards() {
        val ramp = SampleData(FloatArray(24_000) { it / 24_000f }, SR)
        val forward = leftOf(VoiceParams(VoiceSource.Sample(ramp, null), 1.0), 12_000)
        val backward = leftOf(VoiceParams(VoiceSource.Sample(ramp, null), 1.0, speed = -1.0), 12_000)
        assertTrue(forward[3000] < forward[9000])
        assertTrue(backward[3000] > backward[9000])
        assertTrue(forward[100] < 0.05f, "forward playback starts at the quiet end of the ramp")
        assertTrue(backward[100] > 0.9f * centerGain.toFloat(), "backward playback starts at the loud end")
    }

    @Test
    fun beginAndEndSelectTheRegionOfTheSample() {
        val ramp = SampleData(FloatArray(24_000) { it / 24_000f }, SR) // value = position / length
        val left = leftOf(VoiceParams(VoiceSource.Sample(ramp, null), 1.0, begin = 0.5), 2000)
        assertEquals((0.5 + 100 / 24_000.0) * centerGain, left[100].toDouble(), 0.004)

        val mixer = Mixer(SR)
        mixer.startVoice(VoiceParams(VoiceSource.Sample(ramp, null), 1.0, begin = 0.125, end = 0.25)) // 3000 frames
        mixer.renderFrames(2900)
        assertEquals(1, mixer.activeVoices)
        mixer.renderFrames(200)
        assertEquals(0, mixer.activeVoices)
    }

    @Test
    fun eventsThatCannotSoundAreSkipped() {
        val data = sineSample(100.0, 0.1)
        val mixer = Mixer(SR)
        fun skipped(params: VoiceParams) = !mixer.startVoice(params)
        assertTrue(skipped(VoiceParams(VoiceSource.Sample(data, null), 1.0, begin = 0.6, end = 0.4)))
        assertTrue(skipped(VoiceParams(VoiceSource.Sample(data, null), 1.0, begin = 0.5, end = 0.5)))
        assertTrue(skipped(VoiceParams(VoiceSource.Sample(data, null), 1.0, begin = 5.0))) // clamps to 1.0 == end
        assertTrue(skipped(VoiceParams(VoiceSource.Sample(data, null), 1.0, speed = 0.0)))
        assertTrue(skipped(VoiceParams(VoiceSource.Sample(SampleData(FloatArray(0), SR), null), 1.0)))
        assertEquals(0, mixer.activeVoices)

        assertTrue(mixer.startVoice(VoiceParams(VoiceSource.Sample(data, null), 1.0)))
        // begin, end and speed only describe sample playback; synths ignore them.
        assertTrue(mixer.startVoice(VoiceParams(sine, 1.0, begin = 0.6, end = 0.4, speed = 0.0)))
        assertEquals(2, mixer.activeVoices)
    }

    @Test
    fun aSkippedEventDoesNotCutTheVoicesItWouldHaveReplaced() {
        val mixer = Mixer(SR)
        val dc = VoiceSource.Sample(constantSample(2.0), null)
        mixer.startVoice(VoiceParams(dc, 2.0, cut = 1.0))
        assertFalse(mixer.startVoice(VoiceParams(dc, 2.0, cut = 1.0, begin = 0.9, end = 0.1)))
        val out = channel(mixer.renderFrames(2400), 0)
        assertEquals(1, mixer.activeVoices)
        assertTrue(out[2399] > 0.5f)
    }

    // ------------------------------------------------------- pool and cut groups

    @Test
    fun cutGroupFadesEarlierVoicesOfTheSameGroupOverFiveMilliseconds() {
        val mixer = Mixer(SR)
        val dc = VoiceSource.Sample(constantSample(2.0), null)
        mixer.startVoice(VoiceParams(dc, 2.0, pan = 0.0, cut = 1.0)) // sounds on the left only
        mixer.renderFrames(4800)
        mixer.startVoice(VoiceParams(dc, 2.0, pan = 1.0, cut = 1.0)) // right only
        assertEquals(1, mixer.activeVoices, "the faded voice no longer counts as active")
        val out = mixer.renderFrames(2400)
        val left = channel(out, 0)
        assertEquals(0.4, left[119].toDouble(), 0.02) // halfway through the 240-frame fade
        assertTrue(peak(left, 245) < 1e-5f)
        assertTrue(channel(out, 1)[2000] > 0.7f)
    }

    @Test
    fun voicesOutsideTheCutGroupKeepPlaying() {
        val mixer = Mixer(SR)
        val dc = VoiceSource.Sample(constantSample(2.0), null)
        mixer.startVoice(VoiceParams(dc, 2.0, pan = 0.0, cut = 1.0))
        mixer.startVoice(VoiceParams(dc, 2.0, pan = 0.0)) // no group
        mixer.startVoice(VoiceParams(dc, 2.0, pan = 0.0, cut = 2.0)) // another group
        val left = channel(mixer.renderFrames(2400), 0)
        assertEquals(3, mixer.activeVoices)
        assertEquals(0.95, left[2399].toDouble(), 0.05) // 3 x 0.8 summed, then limited just below full scale
    }

    @Test
    fun polyphonyIsCappedAtSixtyFourAndTheOldestVoicesAreStolen() {
        val mixer = Mixer(SR)
        val dc = VoiceSource.Sample(constantSample(5.0), null)
        repeat(36) { assertTrue(mixer.startVoice(VoiceParams(dc, 5.0, gain = 0.01, pan = 0.0))) }
        repeat(64) { assertTrue(mixer.startVoice(VoiceParams(dc, 5.0, gain = 0.01, pan = 1.0))) }
        assertEquals(64, mixer.activeVoices)
        val out = mixer.renderFrames(SR / 20)
        val left = channel(out, 0)
        val right = channel(out, 1)
        assertTrue(peak(left, 480) < 1e-5f, "the 36 oldest voices must have been stolen")
        assertEquals(64 * 0.01 * 0.8, right[SR / 20 - 1].toDouble(), 0.005)
        assertEquals(64, mixer.activeVoices)
    }

    @Test
    fun fadeAllSilencesEveryVoiceWithinTheRequestedTime() {
        val mixer = Mixer(SR)
        val dc = VoiceSource.Sample(constantSample(5.0), null)
        repeat(3) { mixer.startVoice(VoiceParams(dc, 5.0, gain = 0.1)) }
        mixer.renderFrames(4800)
        mixer.fadeAll(20)
        assertEquals(0, mixer.activeVoices)
        val left = channel(mixer.renderFrames(2400), 0)
        assertEquals(3 * 0.1 * centerGain / 2, left[479].toDouble(), 0.01) // halfway through the 960-frame fade
        assertTrue(peak(left, 961) < 1e-6f)
        assertTrue(mixer.startVoice(flat()), "voices can start again after a fade")
    }

    // -------------------------------------------------------------------- buses

    private val burst = VoiceSource.Sample(constantSample(0.02), null) // 20 ms of full-scale DC

    @Test
    fun delayBusEchoesAtTheDelayTimeAndDecaysByTheFeedback() {
        val left = leftOf(VoiceParams(burst, 0.02, delay = 1.0, delaytime = 0.1, delayfeedback = 0.5), SR / 2)
        assertEquals(centerGain, meanAbs(left, 100, 800), 0.02) // the dry burst
        assertTrue(meanAbs(left, 1100, 4700) < 1e-4, "silence between burst and first echo")
        assertEquals(centerGain, meanAbs(left, 4900, 5600), 0.02)
        assertEquals(centerGain * 0.5, meanAbs(left, 9700, 10_400), 0.02)
        assertEquals(centerGain * 0.25, meanAbs(left, 14_500, 15_200), 0.02)
    }

    @Test
    fun delaySendLevelAndPostgainScaleTheEcho() {
        fun echo(delay: Double, postgain: Double) =
            meanAbs(leftOf(VoiceParams(burst, 0.02, delay = delay, delaytime = 0.1, postgain = postgain), SR / 4), 4900, 5600)
        assertEquals(centerGain * 0.5, echo(0.5, 1.0), 0.02)
        assertEquals(centerGain * 0.25, echo(0.5, 0.5), 0.02)
        assertTrue(echo(0.0, 1.0) < 1e-4)
        assertEquals(echo(1.0, 1.0), echo(7.0, 1.0), 1e-6) // clamped to 1
    }

    @Test
    fun delayBusSettingsAreGlobalAndTheLatestVoiceThatSetsThemWins() {
        val mixer = Mixer(SR)
        mixer.startVoice(VoiceParams(burst, 0.02, delay = 1.0, delaytime = 0.3, delayfeedback = 0.0))
        // A silent voice with no send still moves the shared delay time.
        mixer.startVoice(VoiceParams(sine, 0.0, delaytime = 0.1))
        val left = channel(mixer.renderFrames(SR / 2), 0)
        assertEquals(centerGain, meanAbs(left, 4900, 5600), 0.02)
        assertTrue(meanAbs(left, 14_500, 15_200) < 1e-3, "nothing should echo at the first voice's 0.3 s")
    }

    @Test
    fun changingTheDelayTimeDoesNotClick() {
        val mixer = Mixer(SR)
        val held = flat(note = 57.0, duration = 3.0).copy(gain = 0.5, delay = 1.0, delaytime = 0.2, delayfeedback = 0.0)
        mixer.startVoice(held)
        mixer.renderFrames(SR)
        mixer.startVoice(VoiceParams(sine, 0.0, delaytime = 0.137))
        val after = channel(mixer.renderFrames(SR / 2), 0)
        var largestStep = 0f
        for (i in 1 until after.size) largestStep = max(largestStep, abs(after[i] - after[i - 1]))
        assertTrue(largestStep < 0.03f, "delay-time change stepped by $largestStep")
    }

    private val noiseBurst = VoiceSource.Sample(
        Random(1).let { rng -> SampleData(FloatArray(2400) { rng.nextFloat() * 2f - 1f }, SR) },
        null,
    )

    @Test
    fun reverbBusAddsATailAfterTheVoiceHasEnded() {
        val wet = leftOf(VoiceParams(noiseBurst, 0.05, room = 1.0), SR * 3 / 2)
        val dry = leftOf(VoiceParams(noiseBurst, 0.05), SR * 3 / 2)
        assertTrue(rms(wet, SR / 5, SR * 2 / 5) > 0.003, "reverb tail rms ${rms(wet, SR / 5, SR * 2 / 5)}")
        assertEquals(0f, peak(dry, SR / 5), "no tail without a send")
    }

    @Test
    fun reverbSizeLengthensTheTail() {
        fun lateTail(size: Double) = rms(leftOf(VoiceParams(noiseBurst, 0.05, room = 1.0, size = size), SR * 3 / 2), SR, SR * 3 / 2)
        assertTrue(lateTail(1.0) > 1e-4, "large room tail ${lateTail(1.0)}")
        assertTrue(lateTail(1.0) > 20 * lateTail(0.0), "size 1 ${lateTail(1.0)} vs size 0 ${lateTail(0.0)}")
    }

    @Test
    fun clearBusesSilencesTheEffectTails() {
        val mixer = Mixer(SR)
        mixer.startVoice(VoiceParams(noiseBurst, 0.05, room = 1.0, delay = 1.0, delaytime = 0.3))
        mixer.renderFrames(SR / 2)
        assertTrue(rms(channel(mixer.renderFrames(SR / 10), 0)) > 1e-4, "tail should still be ringing")
        mixer.clearBuses()
        assertEquals(0f, peak(mixer.renderFrames(SR / 2)))
    }

    // ------------------------------------------------------------ render contract

    @Test
    fun renderOverwritesOnlyTheRequestedFrameRange() {
        val mixer = Mixer(SR)
        mixer.startVoice(flat())
        val out = FloatArray(1000) { 7f }
        mixer.render(out, 100, 50)
        for (i in 0 until 100) assertEquals(7f, out[i])
        for (i in 300 until 1000) assertEquals(7f, out[i])
        assertTrue((100 until 300).any { out[it] != 7f && out[it] != 0f })

        val buffer = FloatArray(64) { 7f }
        Mixer(SR).render(buffer, 32)
        assertTrue(buffer.all { it == 0f }, "silence must be written, not left as garbage")
    }

    private fun busyPatch(mixer: Mixer) {
        mixer.startVoice(
            VoiceParams(sine, 0.5, note = 60.0, lpf = 2000.0, lpq = 4.0, delay = 0.5, delaytime = 0.05, delayfeedback = 0.6, room = 0.4, size = 0.8, pan = 0.2),
        )
        mixer.startVoice(
            VoiceParams(VoiceSource.Synth(Waveform.WHITE), 0.3, hpf = 3000.0, bpf = 5000.0, shape = 0.4, crush = 6.0, coarse = 3.0, pan = 0.8, room = 0.3),
        )
        mixer.startVoice(
            VoiceParams(VoiceSource.Sample(sineSample(150.0, 0.4), 48), 0.4, note = 55.0, speed = 1.5, begin = 0.1, end = 0.9, cut = 2.0),
        )
    }

    @Test
    fun renderingIsDeterministicAndIndependentOfHowBlocksAreSplit() {
        val frames = 6000
        val whole = FloatArray(frames * 2)
        Mixer(SR).also { busyPatch(it) }.render(whole, frames)

        val split = FloatArray(frames * 2)
        val mixer = Mixer(SR).also { busyPatch(it) }
        var offset = 0
        for (n in intArrayOf(1, 255, 256, 300, 999, 1, 4188)) {
            mixer.render(split, n, offset)
            offset += n
        }
        assertEquals(frames, offset)
        assertContentEquals(whole, split)

        val again = FloatArray(frames * 2)
        Mixer(SR).also { busyPatch(it) }.render(again, frames)
        assertContentEquals(whole, again)
    }

    @Test
    fun masterOutputNeverExceedsFullScale() {
        val mixer = Mixer(SR)
        val dc = VoiceSource.Sample(constantSample(1.0), null)
        repeat(64) { mixer.startVoice(VoiceParams(dc, 1.0, gain = 4.0, postgain = 4.0)) }
        val out = mixer.renderFrames(SR / 2)
        assertTrue(allFinite(out))
        assertTrue(peak(out) <= 1.0f, "peak ${peak(out)}")
        assertTrue(peak(out, 2000) > 0.9f, "the limiter should hold the level near full scale")
    }

    @Test
    fun everySynthWaveformStaysFiniteForTenSecondsWithAndWithoutEffects() {
        for (waveform in Waveform.entries) {
            for (effects in listOf(false, true)) {
                var params = VoiceParams(VoiceSource.Synth(waveform), 10.0, note = 55.0)
                if (effects) {
                    params = params.copy(
                        lpf = 1200.0, lpq = 8.0, hpf = 80.0, bpf = 2000.0, bpq = 2.0, shape = 0.5, crush = 8.0, coarse = 2.0,
                        delay = 0.6, delayfeedback = 0.9, room = 0.8, size = 1.0,
                    )
                }
                val mixer = Mixer(SR)
                mixer.startVoice(params)
                val out = FloatArray(512)
                var finite = true
                var worst = 0f
                repeat(1875) { // 1875 blocks of 256 frames = 10 s
                    mixer.render(out, 256)
                    if (!allFinite(out)) finite = false
                    worst = max(worst, peak(out))
                }
                assertTrue(finite, "$waveform effects=$effects produced NaN or infinity")
                assertTrue(worst in 0.01f..1.0f, "$waveform effects=$effects peaked at $worst")
            }
        }
    }

    @Test
    fun hostileParametersNeverProduceNaNOrOverloadTheOutput() {
        val rng = Random(1234)
        val nasty = doubleArrayOf(
            Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1e9, -1.0, 0.0, 1e-9, 0.5, 1.0, 3.0, 1e3, 1e9,
        )

        fun pick(): Double? = if (rng.nextInt(4) == 0) null else nasty[rng.nextInt(nasty.size)]
        val samples = listOf(
            sineSample(220.0, 0.3),
            constantSample(0.001),
            SampleData(floatArrayOf(0.5f), SR),
            SampleData(FloatArray(0), SR),
        )
        val mixer = Mixer(SR)
        val out = FloatArray(512)
        repeat(400) { step ->
            val source = if (rng.nextBoolean()) {
                VoiceSource.Synth(Waveform.entries[rng.nextInt(Waveform.entries.size)])
            } else {
                VoiceSource.Sample(samples[rng.nextInt(samples.size)], if (rng.nextBoolean()) null else rng.nextInt(24, 90))
            }
            val duration = if (rng.nextInt(5) == 0) pick() ?: 0.0 else abs(rng.nextGaussian()) * 0.3
            mixer.startVoice(
                VoiceParams(
                    source, duration,
                    note = pick(), gain = pick(), velocity = pick(), postgain = pick(), pan = pick(), speed = pick(),
                    begin = pick(), end = pick(), cut = pick(), clip = pick(), attack = pick(), decay = pick(),
                    sustain = pick(), release = pick(), lpf = pick(), lpq = pick(), hpf = pick(), hpq = pick(),
                    bpf = pick(), bpq = pick(), shape = pick(), crush = pick(), coarse = pick(), delay = pick(),
                    delaytime = pick(), delayfeedback = pick(), room = pick(), size = pick(),
                ),
            )
            mixer.render(out, 256)
            assertTrue(allFinite(out), "NaN or infinity after step $step")
            assertTrue(peak(out) <= 1.0f, "peak ${peak(out)} after step $step")
        }
    }

    // -------------------------------------------------------------------- banks

    @Test
    fun pitchedBankPicksTheNearestRootAndTiesGoToTheLowerRoot() {
        val bank = BankInfo("piano", 4, intArrayOf(36, 48, 52, 72))
        assertEquals(0, bankEntry(bank, 0.0, 30.0))
        assertEquals(1, bankEntry(bank, 0.0, 48.0))
        assertEquals(1, bankEntry(bank, 0.0, 50.0))
        assertEquals(2, bankEntry(bank, 0.0, 50.1))
        assertEquals(3, bankEntry(bank, 0.0, 100.0))
        assertEquals(1, bankEntry(bank, 9.0, null), "n is ignored and note defaults to 48")
    }

    @Test
    fun unpitchedBankIndexesByFlooredNWrappedByCount() {
        val bank = BankInfo("kick", 5, null)
        assertEquals(0, bankEntry(bank, 0.0, null))
        assertEquals(3, bankEntry(bank, 3.7, null))
        assertEquals(0, bankEntry(bank, 5.0, null))
        assertEquals(4, bankEntry(bank, -1.0, null))
        assertEquals(4, bankEntry(bank, -0.5, null))
        assertEquals(0, bankEntry(bank, Double.NaN, null))
        assertEquals(2, bankEntry(bank, 2.0, 60.0), "note changes pitch, not the entry")
    }
}
