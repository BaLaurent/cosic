package cosic.audio

import cosic.SampleData
import cosic.engineOf
import cosic.evaluateOk
import cosic.libraryWithBank
import cosic.renderFrames
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The sampler through the whole voice: the gate and envelope rules of `onTriggerSample` (sampler.mjs:292-376), loops, reversal,
 * `unit`, `nudge`, stereo and pan, and how `note` and the sound lookup repitch a bank (the engine, not the voice, does that).
 * Levels include the default gain of 0.8 and a centred mono voice reaches both channels at gain 1.
 */
class SamplerPlaybackTest {
    /** A sample whose value is `position / length`. */
    private fun ramp(seconds: Double, rate: Int = SR): SampleData {
        val n = Math.round(seconds * rate).toInt()
        return SampleData(FloatArray(n) { it.toFloat() / n }, rate)
    }

    private fun at(x: FloatArray, seconds: Double): Float = x[(seconds * SR).toInt()]

    private val level = 0.8

    // ------------------------------------------------------------------------------------------ the gate

    @Test
    fun theDefaultGateIsTheSliceAndTheReleasePlaysOnPastItsEnd() {
        // begin .25, end .5 of a 1 s ramp: a gate of 0.25 s, then the 10 ms release; the hap lasts 1 s but that does not matter
        val left = renderLeft(vp(plain(ramp(1.0)), 1.0, "begin" to 0.25, "end" to 0.5), SR)
        assertEquals(level * 0.35, at(left, 0.1).toDouble(), 1e-3)
        assertEquals(level * 0.499, at(left, 0.2489).toDouble(), 2e-3, "just before the end of the slice")
        // the 5 ms into the release: half the envelope, and the data of 0.505, which lies after `end`
        assertEquals(level * 0.5 * 0.505, at(left, 0.255).toDouble(), 2e-3)
        assertEquals(0f, peak(left, (0.2605 * SR).toInt()), "the release is over 10 ms after the gate")
    }

    @Test
    fun clipMakesTheGateTheDurationOfTheHapAndIgnoresEnd() {
        // the scheduler has applied the clip to the duration: 0.1 s of a 1 s sample, with an end that would have cut it at 0.05
        val left = renderLeft(vp(plain(ramp(1.0)), 0.1, "clip" to 1.0, "end" to 0.05), SR / 2)
        assertEquals(level * 0.09, at(left, 0.09).toDouble(), 2e-3, "plays on past `end`")
        assertEquals(level * 0.5 * 0.105, at(left, 0.105).toDouble(), 2e-3, "5 ms into the release that follows 0.1 s")
        assertEquals(0f, peak(left, (0.1105 * SR).toInt()))
    }

    @Test
    fun aLoopOrAReleaseAlsoMakeTheGateTheHapsDuration() {
        val looped = renderLeft(vp(plain(ramp(0.1)), 1.0, "loop" to 1.0), SR * 12 / 10)
        assertTrue(at(looped, 0.95) > 0.0f, "a loop plays as long as the hap")
        assertEquals(0f, peak(looped, (1.0105 * SR).toInt()))
        val released = renderLeft(vp(plain(SampleData(FloatArray(SR * 2) { 1f }, SR)), 1.0, "release" to 0.2), SR * 14 / 10)
        assertEquals(level, at(released, 0.9).toDouble(), 1e-4)
        assertEquals(level * 0.5, at(released, 1.1).toDouble(), 4e-3, "half way down the 0.2 s release that starts at the end of the hap")
    }

    @Test
    fun aSamplePlaysAtItsOwnPaceWhateverTheHapSaysWhenNothingSetsTheGate() {
        val short = renderLeft(vp(plain(SampleData(FloatArray(SR / 10) { 1f }, SR)), 5.0), SR)
        assertEquals(level, at(short, 0.09).toDouble(), 1e-4)
        assertEquals(0f, at(short, 0.13), "100 ms of data and a 5 s hap")
    }

    // ------------------------------------------------------------------------------- speed, unit, loop

    @Test
    fun aNegativeSpeedReadsTheReversedSampleSoBeginCountsFromItsEnd() {
        val left = renderLeft(vp(plain(ramp(1.0)), 1.0, "speed" to -1.0, "begin" to 0.25), SR / 2)
        assertEquals(level * (0.75 - 100.0 / SR), left[100].toDouble(), 2e-3)
        assertTrue(left[100] > left[2000] && left[2000] > left[20_000], "and it goes down")
    }

    @Test
    fun speedZeroMakesNoVoiceAtAll() {
        val mixer = Mixer(SR)
        assertFalse(mixer.startVoice(vp(plain(ramp(1.0)), 1.0, "speed" to 0.0)))
        assertEquals(0, mixer.countedVoices)
    }

    @Test
    fun unitCMakesSpeedOneLastOneCycleOfTheSample() {
        // 2 s of constant data at speed .25 with unit c: the rate is .25 * 2 s, so the slice is 4 s long
        val left = renderLeft(vp(plain(constantSample(2.0)), 4.0, "speed" to 0.25, "unit" to "c"), SR * 5)
        assertEquals(level, at(left, 3.99).toDouble(), 1e-4)
        assertEquals(0f, peak(left, (4.0115 * SR).toInt()), "the voice ends 4 s after its start (and the 10 ms release)")
    }

    @Test
    fun theLoopRegionRepeatsAndThePositionWrapsByTheModulo() {
        // loopBegin .25, loopEnd .5 of a 1 s ramp, a hap of 2 s: at 0.6 s the position is 0.25 + (0.6 - 0.25) mod 0.25 = 0.35
        val left = renderLeft(vp(plain(ramp(1.0)), 2.0, "loop" to 1.0, "loopBegin" to 0.25, "loopEnd" to 0.5), SR)
        assertEquals(level * 0.35, at(left, 0.6).toDouble(), 2e-3)
        assertEquals(level * 0.25, at(left, 0.75).toDouble(), 2e-3)
        assertTrue(left.drop((0.5 * SR).toInt()).all { it >= level.toFloat() * 0.249f }, "once in the region it stays in it")
    }

    // ------------------------------------------------------------------------------------------- nudge

    @Test
    fun nudgeDelaysTheSourceButNotTheEnvelope() {
        val params = vp(VoiceSource.Sample(SampleSourceParams(constantSample(1.0), nudgeSeconds = 0.01)), 1.0)
        val left = renderLeft(params, SR / 10)
        assertEquals(0f, peak(left, 0, 480), "the source starts 480 frames late")
        assertEquals(level, left[481].toDouble(), 1e-4, "the attack of 1 ms is over by then")
    }

    // ---------------------------------------------------------------------------------- stereo and pan

    private fun leftOneRightHalf() = VoiceSource.Sample(SampleSourceParams(SampleData(FloatArray(SR) { if (it % 2 == 0) 1f else 0.5f }, SR, channels = 2)))

    private fun panned(vararg controls: Pair<String, Any?>): Pair<Float, Float> {
        val out = renderStereo(vp(leftOneRightHalf(), 0.4, *controls, "gain" to 0.4), SR / 20)
        return out[2 * 1200] to out[2 * 1200 + 1]
    }

    @Test
    fun aStereoSampleKeepsBothChannelsAndNeedsNoPannerWhenPanIsNotSet() {
        val (l, r) = panned()
        assertEquals(0.4f, l, 1e-4f)
        assertEquals(0.2f, r, 1e-4f)
        val (cl, cr) = panned("pan" to 0.5)
        assertEquals(0.4f, cl, 1e-4f)
        assertEquals(0.2f, cr, 1e-4f)
    }

    @Test
    fun aStereoPannerMovesTheLeftIntoTheRightOrTheOtherWayRound() {
        val (hardLeftL, hardLeftR) = panned("pan" to 0.0)
        assertEquals(0.6f, hardLeftL, 1e-4f, "L + R")
        assertEquals(0f, hardLeftR, 1e-4f)
        val (hardRightL, hardRightR) = panned("pan" to 1.0)
        assertEquals(0f, hardRightL, 1e-4f)
        assertEquals(0.6f, hardRightR, 1e-4f, "R + L")
    }

    @Test
    fun theTwoChannelsOfAStereoSampleAreTwoDifferentSignals() {
        val out = renderStereo(vp(VoiceSource.Sample(SampleSourceParams(stereoSample(1.0))), 1.0), SR / 2)
        assertEquals(220.0, estimateFrequency(channel(out, 0), 2400, 20_000), 0.5)
        assertEquals(330.0, estimateFrequency(channel(out, 1), 2400, 20_000), 0.5)
    }

    // ---------------------------------------------------------------------------------- odd begin values

    @Test
    fun aBeginBeyondTheEndOfTheSampleIsASilentVoiceThatStillCountsWhenTheHapSetsTheGate() {
        val mixer = Mixer(SR)
        assertTrue(mixer.startVoice(vp(plain(constantSample(1.0)), 1.0, "clip" to 1.0, "begin" to 2.0)))
        val out = mixer.renderFrames(SR / 10)
        assertEquals(0f, peak(out))
        assertEquals(1, mixer.countedVoices)
    }

    // ------------------------------------------------------------------- the sound lookup and the engine

    private fun played(code: String, library: cosic.SoundLibrary): FloatArray {
        val engine = engineOf(library)
        engine.evaluateOk(code)
        engine.play()
        return channel(engine.renderFrames(SR / 2 + 2400), 0)
    }

    @Test
    fun noteRepitchesAnUnpitchedBankRelativeToC2() {
        val library = libraryWithBank("kit", listOf(sineSample(100.0, 2.0)))
        fun hz(code: String) = estimateFrequency(played(code, library), 4800 + 2400, 4800 + 2400 + 12_000)
        assertEquals(100.0, hz("""s("kit")"""), 0.5)
        assertEquals(100.0, hz("""s("kit").note(36)"""), 0.5)
        assertEquals(200.0, hz("""s("kit").note(48)"""), 0.5, "C3 is an octave over C2")
        assertEquals(200.0, hz("""s("kit").note("c3")"""), 0.5)
        assertEquals(50.0, hz("""s("kit").note(24)"""), 0.5)
    }

    @Test
    fun aPitchedBankPlaysTheNearestFileAtItsOwnPitchWhenTheNoteIsItsKey() {
        val library = libraryWithBank("keys", listOf(sineSample(100.0, 2.0), sineSample(200.0, 2.0)), roots = intArrayOf(36, 48))
        fun hz(code: String) = estimateFrequency(played(code, library), 4800 + 2400, 4800 + 2400 + 12_000)
        assertEquals(100.0, hz("""s("keys")"""), 0.5, "no note: C2, the file keyed 36")
        assertEquals(200.0, hz("""s("keys").note(48)"""), 0.5, "the file keyed 48, played as recorded")
        assertEquals(200.0 * Math.pow(2.0, 2.0 / 12.0), hz("""s("keys").note(50)"""), 1.0, "the file keyed 48, two semitones up")
    }

    @Test
    fun speedAndBeginReachTheSamplerThroughTheEngine() {
        val library = libraryWithBank("kit", listOf(sineSample(100.0, 2.0)))
        fun hz(code: String) = estimateFrequency(played(code, library), 4800 + 2400, 4800 + 2400 + 12_000)
        assertEquals(200.0, hz("""s("kit").speed(2)"""), 0.5)
        assertEquals(100.0, hz("""s("kit").speed(-1)"""), 0.5, "backwards, the same pitch")
        assertEquals(100.0, hz("""s("kit").begin(0.25)"""), 0.5)
    }
}
