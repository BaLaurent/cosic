package cosic.audio

import cosic.SampleData
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The orbit buses through the [Mixer]: the per-orbit feedback delay, the generated-IR reverb, DJF, dry, the `FX(...)` delay and reverb. Delay behaviour is Chromium's
 * (see [FeedbackDelayTest]): the first echo is at the delay time, the later ones come every `delaytime + 128` frames.
 */
class OrbitTest {
    /** A sample that is silent except for a 1 at frame [at]. With the flat envelope the impulse reaches the orbit at level 1 (gain 1) at that frame. */
    private fun impulse(at: Int = 100, total: Int = 400): VoiceSource = plain(SampleData(FloatArray(total).also { it[at] = 1f }, SR))

    private fun hit(vararg controls: Pair<String, Any?>, cps: Double = 0.5) =
        vp(impulse(), 1.0, "gain" to 1.0, "attack" to 0.001, "decay" to 0.0, "sustain" to 1.0, *controls, cps = cps)

    private fun render(mixer: Mixer, frames: Int): FloatArray = channel(mixer.renderFrames(frames), 0)

    private fun peaks(x: FloatArray, above: Float = 1e-4f): List<Int> = x.indices.filter { abs(x[it]) > above }

    // ------------------------------------------------------------------------------------------------------------ delay

    @Test
    fun theDelayEchoesAtItsTimeAndEveryDelayTimePlusAQuantumAfter() {
        val mixer = Mixer(SR)
        assertTrue(mixer.startVoice(hit("delay" to 0.5, "delaytime" to 0.25, "delayfeedback" to 0.5, "dry" to 0.0)))
        val out = render(mixer, 40000)
        assertEquals(listOf(12100, 24228, 36356), peaks(out))
        assertEquals(0.5f, out[12100], 1e-3f)
        assertEquals(0.25f, out[24228], 1e-3f)
        assertEquals(0.125f, out[36356], 1e-3f)
    }

    @Test
    fun noFeedbackMeansNoDelayAtAll() {
        val mixer = Mixer(SR)
        mixer.startVoice(hit("delay" to 0.5, "delaytime" to 0.25, "delayfeedback" to 0.0))
        assertEquals(listOf(100), peaks(render(mixer, 30000)), "the direct sound only")
    }

    @Test
    fun aDelayTimeBeyondASecondIsAClampedToASecond() {
        val mixer = Mixer(SR)
        mixer.startVoice(hit("delay" to 0.5, "delaytime" to 3.0, "dry" to 0.0))
        assertEquals(listOf(48100), peaks(render(mixer, 60000)))
    }

    @Test
    fun aVeryShortDelayTimeEchoesRightAwayAndLoopsEveryTimePlusAQuantum() {
        val mixer = Mixer(SR)
        mixer.startVoice(hit("delay" to 0.5, "delaytime" to 0.001, "delayfeedback" to 0.5, "dry" to 0.0))
        val out = render(mixer, 1000)
        assertEquals(listOf(148, 324, 500, 676, 852), peaks(out, 0.01f))
        assertEquals(0.5f, out[148], 1e-3f)
        assertEquals(0.25f, out[324], 1e-3f)
    }

    @Test
    fun withoutADelayTimeItIsDelaysyncOverTheTempo() {
        val mixer = Mixer(SR)
        mixer.startVoice(hit("delay" to 0.5, "dry" to 0.0)) // 3/16 of a cycle at 0.5 cycles per second = 0.375 s
        assertEquals(18100, peaks(render(mixer, 20000)).first())
        val other = Mixer(SR)
        other.startVoice(hit("delay" to 0.5, "delaysync" to 0.25, "dry" to 0.0))
        assertEquals(24100, peaks(render(other, 26000)).first())
    }

    @Test
    fun voicesOnOneOrbitShareTheDelayAndALaterTimeAppliesToBothFromItsStart() {
        val mixer = Mixer(SR)
        mixer.startVoice(hit("delay" to 0.5, "delaytime" to 0.1, "delayfeedback" to 0.1, "dry" to 0.0))
        val first = render(mixer, 2000)
        assertTrue(peaks(first).isEmpty())
        // a silent voice that asks for another delay time: the line reads 9600 frames back from here on, so the first voice's echo is not heard at 4900 but at 9700
        mixer.startVoice(synthVp("sine", 0.01, "gain" to 0.0, "delay" to 0.1, "delaytime" to 0.2, "delayfeedback" to 0.1))
        val rest = render(mixer, 12000)
        val all = peaks(rest, 0.01f).map { it + 2000 }
        assertEquals(listOf(9700), all)
    }

    @Test
    fun eachOrbitHasADelayOfItsOwn() {
        val mixer = Mixer(SR)
        mixer.startVoice(hit("delay" to 0.5, "delaytime" to 0.1, "delayfeedback" to 0.1, "dry" to 0.0, "orbit" to 1.0))
        mixer.startVoice(hit("delay" to 0.5, "delaytime" to 0.2, "delayfeedback" to 0.1, "dry" to 0.0, "orbit" to 2.0))
        assertEquals(listOf(4900, 9700), peaks(render(mixer, 12000), 0.1f), "each orbit's first echo, at the orbit's own time (their feedback echoes are 0.05)")
    }

    @Test
    fun dryScalesOnlyTheDirectPathNotTheSend() {
        val mixer = Mixer(SR)
        mixer.startVoice(hit("delay" to 0.5, "delaytime" to 0.1, "delayfeedback" to 0.1, "dry" to 0.5))
        val out = render(mixer, 6000)
        assertEquals(0.5f, out[100], 1e-4f)
        assertEquals(0.5f, out[4900], 1e-3f)
    }

    @Test
    fun theDelayOfAnOrbitDoesNotLeaveEchoesAfterStop() {
        val mixer = Mixer(SR)
        mixer.startVoice(hit("delay" to 1.0, "delaytime" to 0.5, "delayfeedback" to 0.9, "dry" to 0.0))
        render(mixer, 4000)
        mixer.stopAll(20)
        render(mixer, 2000)
        mixer.startVoice(synthVp("sine", 0.01, "gain" to 0.0))
        assertTrue(peak(render(mixer, 3 * SR)) < 1e-6f, "the echoes that were under way are gone")
    }

    // ----------------------------------------------------------------------------------------------------------- reverb

    private fun noise(seconds: Int, rms: Double, seed: Long = 1): VoiceSource =
        plain(SampleData(lcg(seed, seconds * SR, rms * sqrt(3.0)), SR))

    @Test
    fun theReverbOfWhiteNoiseHasTheLevelBlinkGivesAConvolverNode() {
        val mixer = Mixer(SR)
        val params = vp(
            noise(20, 0.1), 20.0, "gain" to 1.0, "attack" to 0.001, "decay" to 0.0, "sustain" to 1.0, "room" to 1.0, "roomsize" to 2.0, "roomlp" to 0.0, "dry" to 0.0,
        )
        assertTrue(mixer.startVoice(params))
        val out = mixer.renderFrames(20 * SR)
        val from = 10 * SR
        val to = 19 * SR
        // 10^(-58 / 20) * (44100 / 48000) * sqrt(144000) = 0.4389 of the input level
        for (ch in 0..1) assertEquals(0.04389, rms(channel(out, ch), from, to), 0.04389 * 0.06)
    }

    @Test
    fun aVoiceWithoutARoomHasNoReverbAndAnEmptyRoomMakesNoEvent() {
        val mixer = Mixer(SR)
        assertFalse(mixer.startVoice(hit("room" to 0.5, "roomsize" to 0.0)), "createBuffer throws for a room of no length")
        assertFalse(mixer.startVoice(hit("room" to 0.5, "roomsize" to -1.0)))
        assertTrue(mixer.startVoice(hit("room" to 0.0, "roomsize" to 0.0)), "no room, no problem")
        assertTrue(mixer.startVoice(hit("room" to 0.5, "roomsize" to 0.0, "ir" to "hall")), "a room taken from a sample has no such limit")
    }

    @Test
    fun theReverbRoomsizeIsAtMostTenSecondsAndTheSizeKeyIsNeverRead() {
        val orbit = Orbit(1.0, intArrayOf(0, 1), SR)
        orbit.configureReverb(ReverbParams().apply { duration = 20.0 }, 0.0)
        assertEquals(15 * SR, orbit.reverbBus!!.impulseFrames, "a room of 20 s is built as one of 10 s: 1.5 * 10 s of impulse response")
        val other = Orbit(2.0, intArrayOf(0, 1), SR)
        other.configureReverb(ReverbParams(), 0.0)
        assertEquals(3 * SR, other.reverbBus!!.impulseFrames, "the default room is 2 s")
        // `room("0.9:1")` is room 0.9 and size 1, which voiceParams does not read: the room stays 2 s
        val voice = synthVp("sine", 0.1, "room" to 0.9, "size" to 1.0)
        assertTrue(voice.main[Param.roomsize].isNaN())
    }

    @Test
    fun aLaterEventChangingOnlyTheRoomsizeRegeneratesWithTheDefaultsForTheRest() {
        val orbit = Orbit(1.0, intArrayOf(0, 1), SR)
        orbit.configureReverb(ReverbParams().apply { duration = 1.0; fade = 0.05; lp = 5000.0; dim = 500.0 }, 0.0)
        val bus = orbit.reverbBus!!
        assertEquals(1, bus.requests)
        assertEquals(listOf(1.0, 0.05, 5000.0, 500.0), listOf(bus.duration, bus.fade, bus.lp, bus.dim))
        orbit.configureReverb(ReverbParams(), 0.0)
        assertEquals(1, bus.requests, "an event that sets nothing changes nothing")
        orbit.configureReverb(ReverbParams().apply { duration = 3.0 }, 0.0)
        assertEquals(2, bus.requests)
        assertEquals(listOf(3.0, 0.1, 15000.0, 1000.0), listOf(bus.duration, bus.fade, bus.lp, bus.dim), "the arguments that were not set fall back to the defaults, not to what they were")
        orbit.configureReverb(ReverbParams().apply { duration = 3.0 }, 0.0)
        assertEquals(2, bus.requests, "the same room again is not regenerated")
        orbit.configureReverb(ReverbParams().apply { irspeed = 1.0 }, 0.0)
        assertEquals(3, bus.requests, "a set argument counts as a change even if it is the default")
    }

    @Test
    fun aRoomTakenFromASampleNeedsTheSampleToBeLoaded() {
        val mixer = Mixer(SR)
        val loading = hit("room" to 1.0, "ir" to "hall", "dry" to 0.0)
        assertTrue(mixer.startVoice(loading))
        assertTrue(peaks(render(mixer, 20000)).isEmpty(), "the reverb send of an event whose impulse response is not loaded is left out")

        val sample = SampleData(FloatArray(2000) { if (it == 0) 1f else 0f }, SR)
        val ready = loading.let { VoiceParams(it.source, it.durationSec, it.cps, it.cycle, it.main, it.fx, it.mods, sample) }
        val other = Mixer(SR)
        other.startVoice(ready)
        val out = render(other, 20000)
        // a unit impulse as the response is scaled by 1 / rms * 10^(-58 / 20) * (44100 / 48000) with rms = 1 / sqrt(2000): the output is the input at that level
        val scale = sqrt(2000.0) * Math.pow(10.0, -58.0 / 20) * 44100.0 / 48000.0
        assertEquals(scale, out[100].toDouble(), scale * 0.01)
    }

    @Test
    fun theReverbKeepsItsRoomAcrossStopsAndEmptiesItsTail() {
        val mixer = Mixer(SR)
        val params = hit("room" to 1.0, "roomsize" to 1.0, "dry" to 0.0)
        mixer.startVoice(params)
        val first = render(mixer, 6000)
        assertTrue(peak(first, 200) > 0f, "the room rings")
        mixer.stopAll(10)
        render(mixer, 4000)
        mixer.startVoice(synthVp("sine", 0.01, "gain" to 0.0))
        assertTrue(peak(render(mixer, SR)) < 1e-6f, "no tail survives a stop")
    }

    // -------------------------------------------------------------------------------------------------------------- djf

    private fun sineLevel(freq: Double, djf: Double?): Double {
        val mixer = Mixer(SR)
        val wave = plain(SampleData(FloatArray(SR) { sin(2.0 * PI * freq * it / SR).toFloat() }, SR))
        val controls = arrayListOf<Pair<String, Any?>>("gain" to 1.0, "attack" to 0.001, "decay" to 0.0, "sustain" to 1.0)
        if (djf != null) controls.add("djf" to djf)
        mixer.startVoice(vp(wave, 1.0, *controls.toTypedArray()))
        val out = render(mixer, SR)
        return rms(out, SR / 4, SR * 3 / 4) * sqrt(2.0)
    }

    @Test
    fun theDjfKnobIsALowPassBelowAHalfAHighPassAboveAndNothingAtTheMiddle() {
        assertEquals(1.0, sineLevel(100.0, null), 1e-3)
        // 0.25: low-pass at 915 Hz with the worklet's 0.9238 DC gain
        assertEquals(0.92, sineLevel(100.0, 0.25), 0.03)
        assertTrue(sineLevel(8000.0, 0.25) < 0.02)
        // 0.75: high-pass at 915 Hz
        assertTrue(sineLevel(100.0, 0.75) < 0.1)
        assertEquals(1.0, sineLevel(8000.0, 0.75), 0.03)
        // 0.5: the input
        assertEquals(1.0, sineLevel(100.0, 0.5), 1e-3)
        assertEquals(1.0, sineLevel(8000.0, 0.5), 1e-3)
    }

    @Test
    fun theDjfStaysSetUntilAnotherEventChangesIt() {
        val mixer = Mixer(SR)
        val wave = plain(SampleData(FloatArray(4 * SR) { sin(2.0 * PI * 8000.0 * it / SR).toFloat() }, SR))
        mixer.startVoice(vp(wave, 4.0, "gain" to 1.0, "attack" to 0.001, "decay" to 0.0, "sustain" to 1.0))
        val before = render(mixer, SR / 2)
        // a short voice sets the knob to a low-pass: the first voice goes on, filtered
        mixer.startVoice(synthVp("sine", 0.01, "gain" to 0.0, "djf" to 0.2))
        val after = render(mixer, SR)
        assertTrue(rms(before, SR / 4, SR / 2) > 0.5)
        assertTrue(rms(after, SR / 2, SR) < 0.02, "the knob stays where the last voice put it")
    }

    @Test
    fun theDjfIsOffAfterAStopUntilAVoiceSetsItAgain() {
        val mixer = Mixer(SR)
        val wave = plain(SampleData(FloatArray(SR) { sin(2.0 * PI * 8000.0 * it / SR).toFloat() }, SR))
        fun voice(vararg extra: Pair<String, Any?>) = vp(wave, 1.0, "gain" to 1.0, "attack" to 0.001, "decay" to 0.0, "sustain" to 1.0, *extra)
        mixer.startVoice(voice("djf" to 0.2))
        assertTrue(rms(render(mixer, SR / 2), SR / 4, SR / 2) < 0.02, "filtered while the knob is at 0.2")
        mixer.stopAll(10)
        render(mixer, 2000)
        mixer.startVoice(voice())
        assertTrue(rms(render(mixer, SR / 2), SR / 4, SR / 2) > 0.5, "an orbit made anew has no filter")
        mixer.startVoice(voice("djf" to 0.2))
        assertTrue(rms(render(mixer, SR / 2), SR / 4, SR / 2) < 0.02, "and a voice that sets djf brings it back")
    }

    // ------------------------------------------------------------------------------------------------------- in-chain

    @Test
    fun anFxElementsDelayEchoesAndItsDryGainRemovesTheDirectSound() {
        val mixer = Mixer(SR)
        val element = mapOf("delay" to 0.5, "delaytime" to 0.25, "delayfeedback" to 0.5, "dry" to 0.0, "gain" to 1.0)
        assertTrue(mixer.startVoice(hit("FX" to listOf(element))))
        val out = render(mixer, 40000)
        assertEquals(listOf(12100, 24228, 36356), peaks(out), "only the echo: the element's dry gain is 0")
        assertEquals(0.5f, out[12100], 1e-3f)
        assertEquals(0.25f, out[24228], 1e-3f)
    }

    @Test
    fun anFxElementsDelayKeepsTheDirectSoundByDefaultAndIsStereo() {
        val mixer = Mixer(SR)
        val element = mapOf("delay" to 0.5, "delaytime" to 0.1, "delayfeedback" to 0.1, "gain" to 1.0)
        mixer.startVoice(hit("FX" to listOf(element), "pan" to 0.0))
        val out = mixer.renderFrames(6000)
        assertEquals(1f, channel(out, 0)[100] + channel(out, 1)[100], 1e-3f, "a panned mono voice has its energy in the left channel only before the element")
        assertTrue(channel(out, 0)[4900] > 0f)
    }

    @Test
    fun anFxElementsReverbRingsAndMoreThanFourOfThemAtOnceGoWithoutTheEffect() {
        val messages = ArrayList<String>()
        val previous = cosic.pattern.logger
        cosic.pattern.logger = { message, _, _ -> messages.add(message) }
        try {
            val mixer = Mixer(SR)
            val element = mapOf("room" to 0.5, "roomsize" to 0.5, "dry" to 0.0, "gain" to 1.0)
            repeat(5) { mixer.startVoice(hit("FX" to listOf(element), "orbit" to (it + 1).toDouble())) }
            assertTrue(messages.any { "FX(...) reverbs at once" in it }, messages.toString())
            val out = render(mixer, 12000)
            assertTrue(peak(out, 200) > 0f)
        } finally {
            cosic.pattern.logger = previous
        }
    }
}
