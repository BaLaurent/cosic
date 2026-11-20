package cosic.audio

import cosic.SampleData
import cosic.player.voiceParams
import java.util.Random
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Voice resolution, levels, envelopes, polyphony, cut groups and the master output, all through [Mixer], with Strudel's semantics: gain 0.8,
 * no panner unless `pan` is set, the amplitude envelope of `getParamADSR`, no master gain and no limiter. (Filters, effects, orbits' delay
 * and reverb and modulators have their own tests.)
 */
class MixerTest {
    private val sine = synth(Waveform.SINE)

    /** The peak of a sine voice: the source's level of 0.35 times the default gain of 0.8. */
    private val sineLevel = 0.35 * 0.8

    /** A 440 Hz sine with a flat envelope (sustain 1 after 1 ms), so level assertions do not depend on the ADSR shape. */
    private fun flat(vararg extra: Pair<String, Any?>, source: VoiceSource = sine, duration: Double = 2.0) =
        vp(source, duration, "note" to 69.0, "attack" to 0.001, "decay" to 0.0, "sustain" to 1.0, *extra)

    private fun leftPeak(params: VoiceParams) = peak(renderLeft(params), 4800)

    private fun at(x: FloatArray, seconds: Double): Float = x[(seconds * SR).toInt()]

    // ------------------------------------------------------------ level and pan

    @Test
    fun aSineVoiceHasItsBaseLevelTimesTheDefaultGainOnBothChannelsWithoutAPanner() {
        val out = renderStereo(flat())
        val left = channel(out, 0)
        val right = channel(out, 1)
        assertEquals(sineLevel, peak(left, 4800).toDouble(), 0.002)
        for (i in left.indices) assertEquals(left[i], right[i], 1e-6f, "a mono voice with no pan reaches both channels at gain 1")
        assertEquals(440.0, estimateFrequency(left, 4800, left.size), 0.3)
    }

    @Test
    fun noiseVoicesUseTheLowerBaseLevel() {
        val white = renderLeft(flat(source = synth(Waveform.WHITE)))
        assertTrue(peak(white) <= 0.25f * 0.8f + 1e-4f)
        assertTrue(peak(white) > 0.2f * 0.8f)
    }

    @Test
    fun sampleVoicesPlayAtUnityLevelTimesTheDefaultGain() {
        val left = renderLeft(vp(dcSource(1.0), 2.0, "attack" to 0.001, "decay" to 0.0, "sustain" to 1.0))
        assertEquals(0.8, at(left, 0.25).toDouble(), 1e-4)
    }

    @Test
    fun panFollowsTheStereoPannerLaw() {
        val hardLeft = renderStereo(flat("pan" to 0.0))
        assertEquals(sineLevel, peak(channel(hardLeft, 0), 4800).toDouble(), 0.002)
        assertTrue(peak(channel(hardLeft, 1)) < 1e-6f)
        val hardRight = renderStereo(flat("pan" to 1.0))
        assertEquals(sineLevel, peak(channel(hardRight, 1), 4800).toDouble(), 0.002)
        assertTrue(peak(channel(hardRight, 0)) < 1e-6f)
        val centre = renderStereo(flat("pan" to 0.5))
        assertEquals(sineLevel * Math.sqrt(0.5), peak(channel(centre, 0), 4800).toDouble(), 0.002)
        assertEquals(sineLevel * Math.sqrt(0.5), peak(channel(centre, 1), 4800).toDouble(), 0.002)
    }

    @Test
    fun gainVelocityAndPostgainMultiply() {
        assertEquals(0.35 * 0.5, leftPeak(flat("gain" to 0.5)).toDouble(), 0.002)
        assertEquals(0.35 * 0.25, leftPeak(flat("gain" to 0.5, "velocity" to 0.5)).toDouble(), 0.002)
        assertEquals(0.35 * 0.5, leftPeak(flat("gain" to 0.5, "velocity" to 0.5, "postgain" to 2.0)).toDouble(), 0.002)
    }

    @Test
    fun aGainThatIsNoNumberCountsAsOneNotAsTheDefault() {
        assertEquals(0.35, leftPeak(flat("gain" to Double.NaN)).toDouble(), 0.002)
        assertEquals(0.35, leftPeak(flat("gain" to "loud")).toDouble(), 0.002)
    }

    @Test
    fun levelsAreNotClampedBeforeTheMasterOutput() {
        assertEquals(1.0, leftPeak(flat("gain" to 100.0)).toDouble(), 0.0, "35 at the master, hard-clamped to full scale")
        assertEquals(1.0, leftPeak(flat("gain" to -3.0)).toDouble(), 0.0, "an inverted sine of 1.05 is clamped the same way")
        val out = renderStereo(flat("gain" to Double.POSITIVE_INFINITY))
        assertTrue(allFinite(out) && peak(out) <= 1f)
    }

    @Test
    fun controlsWithoutADspStageAreIgnored() {
        // `room("0.9:1")` makes `{room: 0.9, size: 1}` and superdough never reads `size`
        assertContentEquals(renderLeft(flat()), renderLeft(flat("size" to 1.0, "n" to 3.0, "bank" to "x")))
    }

    @Test
    fun aSynthPlaysNote36WhenNoNoteIsSetAndNoteZeroMeansTheSame() {
        fun hz(vararg controls: Pair<String, Any?>) =
            estimateFrequency(renderLeft(vp(sine, 2.0, *controls, "attack" to 0.001, "decay" to 0.0, "sustain" to 1.0)), 4800, 24_000)
        assertEquals(65.406, hz(), 0.2)
        assertEquals(65.406, hz("note" to 0.0), 0.2)
        assertEquals(220.0, hz("note" to "a"), 0.5)
        assertEquals(220.0, hz("freq" to 220.0, "note" to 60.0), 0.5)
        assertEquals(880.0, hz("note" to 69.0, "octave" to 1.0), 1.0)
    }

    // ------------------------------------------------------------ gate and ends

    @Test
    fun aSynthEnvelopeSustainsAtPointSixByDefault() {
        val left = renderLeft(vp(sine, 1.0, "note" to 69.0))
        // the 0.05 s decay is over after 51 ms; sustain 0.6 x level 0.35 x gain 0.8
        assertEquals(0.6 * sineLevel, peak(left, 4800, 9600).toDouble(), 0.004)
    }

    @Test
    fun theSampleEnvelopeIsLinearAndFollowsGetParamADSR() {
        // a = .1, d = .2, s = .5, r = .3 on a gate of 1 s: the level, times the gain of 0.8
        val left = renderLeft(
            vp(dcSource(), 1.0, "attack" to 0.1, "decay" to 0.2, "sustain" to 0.5, "release" to 0.3, "clip" to 1.0),
            SR * 14 / 10,
        )
        assertEquals(0.4, at(left, 0.05).toDouble(), 0.004)
        assertEquals(0.8, at(left, 0.1).toDouble(), 0.004)
        assertEquals(0.6, at(left, 0.2).toDouble(), 0.004)
        assertEquals(0.4, at(left, 0.3).toDouble(), 0.004)
        assertEquals(0.4, at(left, 0.9).toDouble(), 0.004)
        assertEquals(0.2, at(left, 1.15).toDouble(), 0.004)
        assertEquals(0f, at(left, 1.31), 1e-6f)
    }

    @Test
    fun aSynthGateEndsTheVoiceAfterItsReleaseAndTheVoiceStaysCountedUntilTheSourceEnds() {
        val mixer = Mixer(SR)
        mixer.startVoice(vp(sine, 0.1)) // gate 0.1 s, release 0.01 s: silent at 0.11 s, source and chain released at 0.12 s
        mixer.renderFrames(SR * 115 / 1000)
        assertEquals(1, mixer.activeVoices, "the source node stops 10 ms after the envelope ends")
        val tail = channel(mixer.renderFrames(SR / 10), 0)
        assertEquals(0, mixer.activeVoices)
        assertEquals(0f, peak(tail, 1000), "the voice must be silent once its release is over")
    }

    @Test
    fun aSampleGateIsTheSliceUnlessClipLoopOrReleaseAreSet() {
        val short = dcSource(0.1)
        val left = renderLeft(vp(short, 1.0), SR)
        assertEquals(0.8, at(left, 0.05).toDouble(), 1e-4)
        assertEquals(0.8, at(left, 0.09).toDouble(), 1e-4)
        assertEquals(0f, at(left, 0.105), "the 0.1 s of data are over, whatever the 10 ms release after the slice does to nothing")

        // with clip the gate is the hap's duration (the scheduler applies the clip to it): 1 s of a 5 s sample
        val clipped = renderLeft(vp(dcSource(5.0), 1.0, "clip" to 1.0), SR * 12 / 10)
        assertEquals(0.8, at(clipped, 0.9).toDouble(), 1e-4)
        assertEquals(0f, at(clipped, 1.02), 1e-4f)
        // a release makes it follow the hap too
        val released = renderLeft(vp(dcSource(5.0), 1.0, "release" to 0.2), SR * 14 / 10)
        assertEquals(0.8, at(released, 0.9).toDouble(), 1e-4)
        assertEquals(0.4, at(released, 1.1).toDouble(), 0.004)
    }

    @Test
    fun aVoiceCountsTowardPolyphonyUntilItsSourceAndItsReleaseAreOver() {
        val mixer = Mixer(SR)
        mixer.startVoice(vp(dcSource(0.1), 1.0))
        mixer.renderFrames(SR / 2)
        assertEquals(0, mixer.activeVoices, "silent since 0.11 s")
        assertFalse(mixer.isSounding)
        assertEquals(1, mixer.countedVoices, "but the hap lasts 1 s: the chain is released at endWithRelease, 1.01 s")
        mixer.renderFrames(SR * 52 / 100)
        assertEquals(0, mixer.countedVoices, "gone at 1.01 s")
    }

    @Test
    fun theReleaseOfTheEnvelopeOrOfFXreleaseKeepsTheChainUntilItsEnd() {
        // release 0.5 on a gate of 1 s: linear to 0 over 0.5 s
        val left = renderLeft(vp(dcSource(), 1.0, "release" to 0.5, "clip" to 1.0), SR * 16 / 10)
        assertEquals(0.4, at(left, 1.25).toDouble(), 0.004)
        assertEquals(0f, at(left, 1.52), 1e-6f)

        // FXrelease 1.7 after a sample that plays on: a linear fade over [end + release, end + 1.7]
        val faded = renderLeft(vp(dcSource(), 1.0, "FXrelease" to 1.7), SR * 3)
        assertEquals(0.8, at(faded, 0.9).toDouble(), 1e-4)
        assertEquals(0.8, at(faded, 1.01).toDouble(), 1e-4)
        assertEquals(0.4, at(faded, 1.855).toDouble(), 0.004)
        assertEquals(0f, at(faded, 2.72), 1e-6f)
    }

    // ------------------------------------------------------------------ samples

    @Test
    fun aSampleIsRepitchedByTheTransposeItsSourceCarriesNotByNote() {
        val data = sineSample(100.0, 1.0)
        fun left(semitones: Double, vararg controls: Pair<String, Any?>) =
            renderLeft(vp(VoiceSource.Sample(SampleSourceParams(data, semitones)), 1.0, *controls))
        assertEquals(100.0, estimateFrequency(left(0.0), 2400, 20_000), 0.5)
        assertEquals(200.0, estimateFrequency(left(12.0), 2400, 20_000), 0.5)
        assertEquals(50.0, estimateFrequency(left(-12.0), 2400, 20_000), 0.5)
        assertEquals(100.0, estimateFrequency(left(0.0, "note" to 60.0), 2400, 20_000), 0.5, "the sound lookup turns note and freq into the transpose")

        val ends = left(12.0)
        assertTrue(peak(ends, 0, SR * 45 / 100) > 0.5f)
        assertEquals(0f, peak(ends, SR * 53 / 100), "an octave up halves the playing time")
    }

    @Test
    fun fileSampleRateIsConvertedToTheEngineRate() {
        val data = sineSample(100.0, 1.0, rate = 22_050)
        val left = renderLeft(vp(plain(data), 1.0), 54_000)
        assertEquals(100.0, estimateFrequency(left, 4800, 40_000), 0.3)
        assertTrue(peak(left, 0, 46_000) > 0.5f)
        assertEquals(0f, peak(left, SR * 103 / 100), "the second of audio takes a second at any rate")

        val cd = Mixer(44_100)
        cd.startVoice(flat())
        assertEquals(440.0, estimateFrequency(channel(cd.renderFrames(22_050), 0), 4410, 22_050, 44_100), 0.3)
    }

    @Test
    fun speedScalesPlaybackWithoutALimit() {
        val data = sineSample(100.0, 1.0)
        fun left(speed: Double) = renderLeft(vp(plain(data), 1.0, "speed" to speed))
        assertEquals(200.0, estimateFrequency(left(2.0), 2400, 20_000), 0.5)
        assertEquals(50.0, estimateFrequency(left(0.5), 2400, 20_000), 0.5)

        val fast = left(100.0)
        assertTrue(peak(fast, 0, 300) > 0.5f, "speed 100: the second of audio lasts 0.01 s")
        assertEquals(0f, peak(fast, SR * 3 / 100))
    }

    @Test
    fun negativeSpeedPlaysTheSampleBackwards() {
        val ramp = SampleData(FloatArray(24_000) { it / 24_000f }, SR)
        val forward = renderLeft(vp(plain(ramp), 1.0), 12_000)
        val backward = renderLeft(vp(plain(ramp), 1.0, "speed" to -1.0), 12_000)
        assertTrue(forward[3000] < forward[9000])
        assertTrue(backward[3000] > backward[9000])
        assertTrue(forward[100] < 0.05f, "forward playback starts at the quiet end of the ramp")
        assertTrue(backward[100] > 0.9f * 0.8f, "backward playback starts at the loud end")
    }

    @Test
    fun beginAndEndSelectTheRegionOfTheSample() {
        val ramp = SampleData(FloatArray(24_000) { it / 24_000f }, SR) // value = position / length
        val left = renderLeft(vp(plain(ramp), 1.0, "begin" to 0.5), 2000)
        assertEquals((0.5 + 100 / 24_000.0) * 0.8, left[100].toDouble(), 0.004)

        val region = renderLeft(vp(plain(ramp), 1.0, "begin" to 0.125, "end" to 0.25), 6000) // 3000 frames
        assertTrue(peak(region, 0, 2900) > 0.05f)
        assertEquals(0f, peak(region, 3600), "the region and the 10 ms release are over")
    }

    @Test
    fun eventsThatCannotSoundAreSkipped() {
        val data = sineSample(100.0, 0.1)
        val mixer = Mixer(SR)
        fun skipped(params: VoiceParams) = !mixer.startVoice(params)
        assertTrue(skipped(vp(plain(data), 1.0, "begin" to 0.6, "end" to 0.4)))
        assertTrue(skipped(vp(plain(data), 1.0, "begin" to 0.5, "end" to 0.5)))
        assertTrue(skipped(vp(plain(data), 1.0, "begin" to 5.0))) // the slice from 5 to 1 would last no time
        assertTrue(skipped(vp(plain(data), 1.0, "begin" to -0.1))) // the browser refuses a negative offset
        assertTrue(skipped(vp(plain(data), 1.0, "speed" to 0.0)))
        assertTrue(skipped(vp(plain(SampleData(FloatArray(0), SR)), 1.0)))
        assertEquals(0, mixer.activeVoices)

        assertTrue(mixer.startVoice(vp(plain(data), 1.0)))
        // begin, end and speed only describe sample playback; synths ignore them.
        assertTrue(mixer.startVoice(vp(sine, 1.0, "begin" to 0.6, "end" to 0.4, "speed" to 0.0)))
        assertEquals(2, mixer.activeVoices)
    }

    // ------------------------------------------------------- pool and cut groups

    @Test
    fun aSkippedEventDoesNotCutTheVoicesItWouldHaveReplaced() {
        val mixer = Mixer(SR)
        mixer.startVoice(vp(dcSource(2.0), 2.0, "cut" to 1.0))
        assertFalse(mixer.startVoice(vp(dcSource(2.0), 2.0, "cut" to 1.0, "begin" to 0.9, "end" to 0.1)))
        val out = channel(mixer.renderFrames(2400), 0)
        assertEquals(1, mixer.activeVoices)
        assertTrue(out[2399] > 0.5f)
    }

    @Test
    fun aSkippedEventStealsNoVoiceAndChangesNothingEvenWithAFullPool() {
        fun crowd() = Mixer(SR).also { mixer ->
            // 128 voices sounding on the right, and the 128 older ones that they stole their places from still fading on
            // the left: all 256 slots are busy
            repeat(128) { mixer.startVoice(vp(dcSource(), 5.0, "gain" to 0.001, "pan" to 0.0)) }
            repeat(128) { mixer.startVoice(vp(dcSource(), 5.0, "gain" to 0.001, "pan" to 1.0)) }
        }
        val reference = crowd()
        val mixer = crowd()
        assertEquals(128, mixer.activeVoices)

        assertFalse(mixer.startVoice(vp(dcSource(), 5.0, "cut" to 1.0, "delaytime" to 0.5, "begin" to 0.9, "end" to 0.1)))
        assertEquals(128, mixer.activeVoices, "no sounding voice was faded to make room for it")
        assertContentEquals(reference.renderFrames(2400), mixer.renderFrames(2400), "and none of the fading ones was dropped")
    }

    @Test
    fun cutGroupFadesTheEarlierSampleOfTheSameGroupOverTenMilliseconds() {
        val mixer = Mixer(SR)
        mixer.startVoice(vp(dcSource(), 5.0, "pan" to 0.0, "cut" to 1.0)) // sounds on the left only
        mixer.renderFrames(SR)
        mixer.startVoice(vp(dcSource(), 5.0, "pan" to 1.0, "cut" to 1.0)) // right only
        assertEquals(1, mixer.activeVoices, "the faded voice no longer counts as active")
        val out = mixer.renderFrames(2400)
        val left = channel(out, 0)
        assertEquals(0.4, left[240].toDouble(), 0.01) // halfway through the 480-frame fade, from 0.8
        assertTrue(peak(left, 485) < 1e-6f, "silent from 10 ms on")
        assertTrue(channel(out, 1)[2000] > 0.7f)
    }

    @Test
    fun voicesOutsideTheCutGroupAndSynthsKeepPlaying() {
        val mixer = Mixer(SR)
        mixer.startVoice(vp(dcSource(), 2.0, "pan" to 0.0, "cut" to 1.0, "gain" to 0.2))
        mixer.startVoice(vp(dcSource(), 2.0, "pan" to 0.0, "gain" to 0.2)) // no group
        mixer.startVoice(vp(dcSource(), 2.0, "pan" to 0.0, "cut" to 2.0, "gain" to 0.2)) // another group
        mixer.startVoice(vp(sine, 2.0, "cut" to 1.0, "gain" to 0.2)) // synths ignore cut
        mixer.startVoice(vp(sine, 2.0, "cut" to 1.0, "gain" to 0.2))
        val left = channel(mixer.renderFrames(2400), 0)
        assertEquals(5, mixer.activeVoices)
        assertEquals(0.6, left[2399].toDouble(), 0.15) // 3 x 0.2 of DC plus the two sines (0.07 each at most)
    }

    @Test
    fun polyphonyIsCappedAt128AndTheOldestVoicesAreStolen() {
        val mixer = Mixer(SR)
        repeat(40) { assertTrue(mixer.startVoice(vp(dcSource(), 5.0, "gain" to 0.005, "pan" to 0.0))) }
        repeat(128) { assertTrue(mixer.startVoice(vp(dcSource(), 5.0, "gain" to 0.005, "pan" to 1.0))) }
        assertEquals(128, mixer.activeVoices)
        val out = mixer.renderFrames(SR * 4 / 10)
        val left = channel(out, 0)
        val right = channel(out, 1)
        assertTrue(peak(left, SR * 26 / 100) < 1e-6f, "the 40 oldest voices must have been ramped down by 0.25 s")
        assertTrue(left[SR / 10] > 0.001f, "and are still on the way down before that")
        assertEquals(128 * 0.005, right[SR * 4 / 10 - 1].toDouble(), 0.005)
        assertEquals(128, mixer.activeVoices)
    }

    @Test
    fun aStolenVoiceRampsLinearlyToZeroAtTheNewVoicesStartPlusAQuarterSecond() {
        val mixer = Mixer(SR)
        mixer.maxPolyphony = 4
        mixer.startVoice(vp(dcSource(), 5.0, "gain" to 0.5, "pan" to 0.0))
        repeat(4) { mixer.startVoice(vp(dcSource(), 5.0, "gain" to 0.01, "pan" to 1.0)) }
        val left = channel(mixer.renderFrames(SR / 2), 0)
        assertEquals(0.48, at(left, 0.01).toDouble(), 0.005, "0.5 times (1 - 0.01 / 0.25), after the 1 ms attack")
        assertEquals(0.25, at(left, 0.125).toDouble(), 0.002)
        assertEquals(0f, at(left, 0.249), 0.004f)
        assertTrue(peak(left, SR * 26 / 100) < 1e-6f, "silent from 0.26 s on")
    }

    @Test
    fun aSilentVoiceStillCountsTowardPolyphonyUntilItsChainIsReleased() {
        val mixer = Mixer(SR)
        mixer.maxPolyphony = 2
        mixer.startVoice(vp(dcSource(), 5.0, "gain" to 0.5, "pan" to 0.0)) // B: long, on the left
        mixer.startVoice(vp(dcSource(0.3), 8.0, "pan" to 1.0)) //             A: silent after 0.3 s, but its hap lasts 8 s
        mixer.renderFrames(SR)
        mixer.startVoice(vp(dcSource(), 5.0, "pan" to 1.0, "gain" to 0.01)) // C: counts 3, steals the oldest, B
        val left = channel(mixer.renderFrames(SR * 4 / 10), 0)
        assertTrue(peak(left, SR * 26 / 100) < 1e-6f, "B was ramped down although silent A was the lighter load")
    }

    @Test
    fun maxPolyphonyIsClampedToWhatThePoolCanHold() {
        val mixer = Mixer(SR)
        mixer.maxPolyphony = 1000
        assertEquals(192, mixer.maxPolyphony)
        mixer.maxPolyphony = 0
        assertEquals(1, mixer.maxPolyphony)
    }

    @Test
    fun fxElementsRunThroughTheirOwnGainStageBeforeTheMainChain() {
        // every chain starts with its gain stage: gain x velocity, 0.8 by default
        val left = renderLeft(vp(dcSource(), 1.0, "gain" to 0.5, "FX" to listOf(mapOf("gain" to 0.5), mapOf("velocity" to 0.5))))
        assertEquals(0.5 * (0.8 * 0.5) * 0.5, at(left, 0.25).toDouble(), 1e-4)
        val plain = renderLeft(vp(dcSource(), 1.0, "FX" to listOf(emptyMap<String, Any?>())))
        assertEquals(0.8 * 0.8, at(plain, 0.25).toDouble(), 1e-4, "the default gain applies to every element")
    }

    @Test
    fun theChainsOfFxElementsAreSharedAndGivenBackWhenTheVoiceEnds() {
        val mixer = Mixer(SR)
        repeat(100) { // 400 chains over the lifetime of the mixer; the pool holds 32
            mixer.startVoice(vp(dcSource(), 0.01, "clip" to 1.0, "gain" to 0.001, "FX" to List(4) { mapOf("gain" to 1.0) }))
            mixer.renderFrames(2000)
        }
        mixer.startVoice(vp(dcSource(), 1.0, "gain" to 1.0, "FX" to List(4) { mapOf("gain" to 0.5) }))
        val left = channel(mixer.renderFrames(SR / 10), 0)
        assertEquals(0.5 * 0.5 * 0.5 * 0.5, at(left, 0.05).toDouble(), 1e-4, "all four chains are there")
    }

    @Test
    fun aVoiceThatFindsThePoolEmptyGetsFewerChainsAndOthersAreNotAffected() {
        val mixer = Mixer(SR)
        // 8 voices with 4 elements take all 32 chains; the 9th voice gets none
        repeat(8) { mixer.startVoice(vp(dcSource(), 5.0, "gain" to 0.01, "FX" to List(4) { mapOf("gain" to 1.0) }, "pan" to 1.0)) }
        mixer.startVoice(vp(dcSource(), 5.0, "gain" to 0.5, "FX" to List(4) { mapOf("gain" to 0.1) }, "pan" to 0.0))
        val left = channel(mixer.renderFrames(SR / 10), 0)
        assertEquals(0.5, at(left, 0.05).toDouble(), 1e-3, "no chain left for the 9th voice: only its main gain applies")
    }

    @Test
    fun stopAllSilencesEveryVoiceWithinTheRequestedTime() {
        val mixer = Mixer(SR)
        repeat(3) { mixer.startVoice(vp(dcSource(), 5.0, "gain" to 0.1)) }
        mixer.renderFrames(4800)
        mixer.stopAll(20)
        assertEquals(0, mixer.activeVoices)
        val left = channel(mixer.renderFrames(2400), 0)
        // halfway through the 960-frame fade the voices are at half level, and so is the output they are heard through
        assertEquals(3 * 0.1 / 4, left[479].toDouble(), 0.005)
        assertTrue(peak(left, 961) < 1e-6f)
        assertTrue(mixer.startVoice(flat()), "voices can start again after a fade")
    }

    @Test
    fun stopAllFadesTheOutputOutAndEmptiesTheOrbitsWhenTheFadeIsOver() {
        val mixer = Mixer(SR)
        mixer.startVoice(vp(dcSource(), 5.0, "gain" to 0.3, "delay" to 1.0, "delaytime" to 0.3, "delayfeedback" to 0.9, "room" to 1.0))
        mixer.renderFrames(SR / 2)
        mixer.stopAll(20)
        val out = mixer.renderFrames(3 * SR)
        assertTrue(rms(out, 0, 480 * 2) > 0.01, "the voice is heard while it fades")
        assertTrue(peak(out, 961 * 2) < 1e-6f, "silent as soon as the fade is over")
        assertTrue(mixer.startVoice(vp(dcSource(), 0.1, "gain" to 0.3)), "voices can start again")
        assertTrue(peak(mixer.renderFrames(2400)) > 0.1f)
    }

    // --------------------------------------------------------- orbits and output

    @Test
    fun dryScalesOnlyTheDirectPath() {
        assertEquals(0f, peak(renderLeft(vp(dcSource(), 1.0, "dry" to 0.0))), "no direct signal")
        assertEquals(0.4, at(renderLeft(vp(dcSource(), 1.0, "dry" to 0.5)), 0.25).toDouble(), 1e-4)
    }

    @Test
    fun voicesOnDifferentOrbitsAreSummedAtTheOutput() {
        val mixer = Mixer(SR)
        mixer.startVoice(vp(dcSource(), 1.0, "gain" to 0.25, "orbit" to 1.0))
        mixer.startVoice(vp(dcSource(), 1.0, "gain" to 0.25, "orbit" to 2.0))
        assertEquals(0.5, at(channel(mixer.renderFrames(SR / 2), 0), 0.25).toDouble(), 1e-4)
    }

    @Test
    fun channelsRouteAnOrbitToTheDeviceChannels() {
        fun render(channels: List<Double>, mixer: Mixer = Mixer(SR)): FloatArray {
            mixer.startVoice(vp(dcSource(), 1.0, "pan" to 0.0, "channels" to channels)) // left only
            return mixer.renderFrames(SR / 2)
        }
        val swapped = render(listOf(2.0, 3.0))
        assertEquals(0f, peak(channel(swapped, 0)))
        assertEquals(0.8, at(channel(swapped, 1), 0.25).toDouble(), 1e-4, "channels [2, 3] swaps left and right on a stereo device")
        val identity = render(listOf(3.0, 4.0))
        assertEquals(0.8, at(channel(identity, 0), 0.25).toDouble(), 1e-4)
        assertEquals(0f, peak(channel(identity, 1)))
    }

    @Test
    fun outputChannelsFollowTheControlThenTheOrbit() {
        assertContentEquals(intArrayOf(0, 1), mapOutputChannels(null, 1.0, false))
        assertContentEquals(intArrayOf(2, 3), mapOutputChannels(null, 2.0, true))
        assertContentEquals(intArrayOf(0, 1), mapOutputChannels(null, 0.0, true))
        assertContentEquals(intArrayOf(4, 5), mapOutputChannels(intArrayOf(5, 6), 2.0, true), "a channels control overrides the orbit's pair")
    }

    @Test
    fun theMasterOutputHasNoLimiterOnlyAHardClampWhichTurnsNaNIntoSilence() {
        val mixer = Mixer(SR)
        repeat(64) { mixer.startVoice(vp(dcSource(), 1.0, "gain" to 4.0, "postgain" to 4.0)) }
        val out = mixer.renderFrames(SR / 2)
        assertTrue(allFinite(out))
        assertEquals(1.0f, peak(out, 2000), "the sum is far above full scale and sits on the clamp")
        assertEquals(0f, MasterOutputProbe.clamped(Float.NaN))
        assertEquals(-1f, MasterOutputProbe.clamped(-7f))
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

    @Test
    fun theFrameCounterFollowsWhatWasRendered() {
        val mixer = Mixer(SR)
        assertEquals(0L, mixer.frame)
        mixer.renderFrames(1000)
        mixer.renderFrames(24)
        assertEquals(1024L, mixer.frame)
    }

    private fun busyPatch(mixer: Mixer) {
        mixer.startVoice(vp(sine, 0.5, "note" to 60.0, "cutoff" to 2000.0, "resonance" to 4.0, "delay" to 0.5, "delaytime" to 0.05, "delayfeedback" to 0.6, "room" to 0.4, "pan" to 0.2))
        mixer.startVoice(vp(synth(Waveform.WHITE), 0.3, "hcutoff" to 3000.0, "bandf" to 5000.0, "shape" to 0.4, "crush" to 6.0, "coarse" to 3.0, "pan" to 0.8, "room" to 0.3))
        mixer.startVoice(
            vp(VoiceSource.Sample(SampleSourceParams(sineSample(150.0, 0.4), transposeSemitones = 7.0)), 0.4, "speed" to 1.5, "begin" to 0.1, "end" to 0.9, "cut" to 2.0, "orbit" to 2.0),
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
        assertTrue(peak(whole) > 0.05f)
    }

    @Test
    fun stopAllEmptiesTheOrbitsAtTheSameFrameHoweverTheBlocksAreSplit() {
        val playing = 2000
        val stopped = 4000
        fun run(parts: IntArray): FloatArray {
            val mixer = Mixer(SR).also { busyPatch(it) }
            val out = FloatArray((playing + stopped) * 2)
            mixer.render(out, playing)
            mixer.stopAll(20)
            var offset = playing
            for (n in parts) {
                mixer.render(out, n, offset)
                offset += n
            }
            assertEquals(playing + stopped, offset)
            return out
        }
        val whole = run(intArrayOf(stopped))
        val split = run(intArrayOf(1, 700, 258, 3, 1000, 2038))
        assertContentEquals(whole, split)
        assertTrue(peak(whole, playing * 2, (playing + 960) * 2) > 0.01f, "the voices sound while they fade")
        assertTrue(peak(whole, (playing + 961) * 2) < 1e-6f, "silent after the 960 frame fade")
    }

    @Test
    fun everySynthWaveformStaysFiniteForTenSeconds() {
        for (waveform in Waveform.entries) {
            val mixer = Mixer(SR)
            mixer.startVoice(vp(synth(waveform), 10.0, "note" to 55.0, "cutoff" to 1200.0, "delay" to 0.6, "room" to 0.8))
            val out = FloatArray(512)
            var finite = true
            var worst = 0f
            repeat(1875) { // 1875 blocks of 256 frames = 10 s
                mixer.render(out, 256)
                if (!allFinite(out)) finite = false
                worst = max(worst, peak(out))
            }
            assertTrue(finite, "$waveform produced NaN or infinity")
            assertTrue(worst in 0.01f..1.0f, "$waveform peaked at $worst")
        }
    }

    @Test
    fun hostileParametersNeverProduceNaNOrOverloadTheOutput() {
        val rng = Random(1234)
        val nasty = doubleArrayOf(
            Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -1e9, -1.0, 0.0, 1e-9, 0.5, 1.0, 3.0, 1e3, 1e9,
        )
        val keys = Param.entries.map { it.key }.filter { it != "orbit" && it != "bus" } + listOf("orbit", "bus", "channels", "duckorbit")

        fun pick(): Any? = nasty[rng.nextInt(nasty.size)]
        val samples = listOf(sineSample(220.0, 0.3), constantSample(0.001), SampleData(floatArrayOf(0.5f), SR), SampleData(FloatArray(0), SR))
        val mixer = Mixer(SR)
        val out = FloatArray(512)
        repeat(300) { step ->
            val source = if (rng.nextBoolean()) {
                VoiceSource.Synth(Waveform.entries[rng.nextInt(Waveform.entries.size)])
            } else {
                VoiceSource.Sample(SampleSourceParams(samples[rng.nextInt(samples.size)], transposeSemitones = rng.nextInt(-12, 13).toDouble()))
            }
            val controls = LinkedHashMap<String, Any?>()
            for (key in keys) if (rng.nextInt(4) == 0 && key != "orbit" && key != "bus" && key != "channels" && key != "duckorbit") controls[key] = pick()
            if (rng.nextInt(6) == 0) controls["orbit"] = (rng.nextInt(3) + 1).toDouble()
            val duration = if (rng.nextInt(5) == 0) pick() as Double else abs(rng.nextGaussian()) * 0.3
            mixer.startVoice(voiceParams(controls, source, duration, 0.5, 0.0))
            mixer.render(out, 256)
            assertTrue(allFinite(out), "NaN or infinity after step $step")
            assertTrue(peak(out) <= 1.0f, "peak ${peak(out)} after step $step")
        }
    }
}

/** The clamp of the master output, reached through a one-orbit mix. */
private object MasterOutputProbe {
    fun clamped(x: Float): Float {
        val orbit = Orbit(1.0, intArrayOf(0, 1), SR)
        orbit.summing.left[0] = x
        val out = FloatArray(2)
        MasterOutput.mix(arrayOf(orbit), 1, out, 0, 1, floatArrayOf(1f))
        return out[0]
    }
}
