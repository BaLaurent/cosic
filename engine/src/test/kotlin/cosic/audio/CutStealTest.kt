package cosic.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Cut groups (sampler.mjs:365-373) and the polyphony steal (superdough.mjs:546-555), CHAIN Task B steps 7-9 and SAMPLES §1.4 step 15: the
 * ramps, the keys of a group, who is cut and who is stolen, and the ledger that decides it.
 */
class CutStealTest {
    private val sine = synth(Waveform.SINE)

    /** Voice [a] on the left only, voice [b] on the right only, both full scale, so each channel reads one voice. */
    private fun dc(vararg c: Pair<String, Any?>, seconds: Double = 5.0, pan: Double) =
        vp(dcSource(), seconds, "gain" to 1.0, "pan" to pan, *c)

    private fun frame(seconds: Double) = (seconds * SR).toInt()

    // ------------------------------------------------------------------------------------------------ cut

    @Test
    fun theEarlierSampleOfAGroupFadesLinearlyOverTenMillisecondsFromTheNewStart() {
        val mixer = Mixer(SR)
        mixer.startVoice(dc("cut" to 1.0, pan = 0.0))
        mixer.renderFrames(SR)
        mixer.startVoice(dc("cut" to 1.0, pan = 1.0))
        val left = channel(mixer.renderFrames(SR / 10), 0)
        assertEquals(1.0, left[0].toDouble(), 1e-4, "the fade starts at the new voice's frame")
        assertEquals(0.5, left[frame(0.005)].toDouble(), 0.002, "half way after 5 ms")
        assertEquals(0.0, left[frame(0.01)].toDouble(), 1e-4, "gone at 10 ms")
        assertTrue(peak(left, frame(0.0101), left.size) < 1e-6f)
    }

    @Test
    fun aCutVoiceStillCountsTowardPolyphonyUntilItsChainIsReleased() {
        val mixer = Mixer(SR)
        mixer.startVoice(dc("cut" to 1.0, pan = 0.0, seconds = 3.0))
        mixer.startVoice(dc("cut" to 1.0, pan = 1.0, seconds = 3.0))
        mixer.renderFrames(SR / 2)
        assertEquals(1, mixer.activeVoices, "the faded voice is silent")
        assertEquals(2, mixer.countedVoices, "but its hap goes on")
    }

    @Test
    fun cutGroupsAreIndexedByTheExactValueLikeAJsArrayKey() {
        fun leftAfter(first: Double, second: Double): Float {
            val mixer = Mixer(SR)
            mixer.startVoice(dc("cut" to first, pan = 0.0))
            mixer.renderFrames(SR / 10)
            mixer.startVoice(dc("cut" to second, pan = 1.0))
            return peak(channel(mixer.renderFrames(SR / 10), 0), frame(0.02), SR / 10)
        }
        assertTrue(leftAfter(1.0, 1.0) < 1e-6f)
        assertTrue(leftAfter(1.0, 1.5) > 0.9f, "cut 1.5 is not group 1")
        assertTrue(leftAfter(1.5, 1.5) < 1e-6f, "but it is a group of its own")
        assertTrue(leftAfter(0.0, 0.0) < 1e-6f, "cut 0 is a group: the test is `cut !== undefined`")
        assertTrue(leftAfter(0.0, -0.0) < 1e-6f, "-0 is the key 0")
        assertTrue(leftAfter(-2.0, -2.0) < 1e-6f)
        assertTrue(leftAfter(-2.0, 2.0) > 0.9f)
    }

    @Test
    fun aVoiceWithoutCutAndOtherGroupsAreNotCut() {
        val mixer = Mixer(SR)
        mixer.startVoice(dc(pan = 0.0)) // no group
        mixer.renderFrames(SR / 10)
        mixer.startVoice(dc("cut" to 1.0, pan = 1.0))
        assertTrue(peak(channel(mixer.renderFrames(SR / 10), 0), frame(0.02), SR / 10) > 0.9f, "a voice without cut is nobody's predecessor")
        mixer.startVoice(dc("cut" to 2.0, pan = 1.0))
        mixer.renderFrames(SR / 10)
        assertEquals(3, mixer.activeVoices, "no voice was faded")
    }

    @Test
    fun onlyTheLatestVoiceOfAGroupIsFadedByTheNextOne() {
        val mixer = Mixer(SR)
        mixer.startVoice(dc("cut" to 1.0, pan = 0.0)) // A
        mixer.renderFrames(SR / 10)
        mixer.startVoice(dc("cut" to 1.0, pan = 1.0)) // B cuts A
        mixer.renderFrames(SR / 10)
        mixer.startVoice(dc("cut" to 1.0, pan = 0.5)) // C cuts B
        val out = mixer.renderFrames(SR / 10)
        // after the fade of B only C is left, on both channels at the centre law
        val l = channel(out, 0)
        val r = channel(out, 1)
        assertEquals(0.70711, l[frame(0.05)].toDouble(), 1e-3)
        assertEquals(0.70711, r[frame(0.05)].toDouble(), 1e-3)
    }

    @Test
    fun nudgeDelaysTheCutByTheNewVoicesNudge() {
        val mixer = Mixer(SR)
        mixer.startVoice(dc("cut" to 1.0, pan = 0.0))
        mixer.renderFrames(SR / 10)
        mixer.startVoice(dc("cut" to 1.0, "nudge" to 0.1, pan = 1.0))
        val left = channel(mixer.renderFrames(SR / 4), 0)
        assertEquals(1.0, left[frame(0.09)].toDouble(), 1e-4, "still playing before t + nudge")
        assertEquals(0.5, left[frame(0.105)].toDouble(), 0.002, "half way through the fade")
        assertTrue(peak(left, frame(0.1101), left.size) < 1e-6f)
    }

    @Test
    fun aNegativeOrHugeNudgeStaysUsable() {
        val mixer = Mixer(SR)
        mixer.startVoice(dc("cut" to 1.0, pan = 0.0))
        mixer.renderFrames(SR / 10)
        mixer.startVoice(dc("cut" to 1.0, "nudge" to -5.0, pan = 1.0))
        assertTrue(peak(channel(mixer.renderFrames(SR / 10), 0), frame(0.011), SR / 10) < 1e-6f, "a negative nudge cuts at the start")

        val other = Mixer(SR)
        other.startVoice(dc("cut" to 1.0, pan = 0.0))
        other.startVoice(dc("cut" to 1.0, "nudge" to Double.POSITIVE_INFINITY, pan = 1.0))
        val left = channel(other.renderFrames(SR / 10), 0)
        assertTrue(left.all { it.isFinite() })
    }

    @Test
    fun synthsNeitherCutNorAreCut() {
        val mixer = Mixer(SR)
        mixer.startVoice(vp(sine, 5.0, "note" to 69.0, "gain" to 1.0, "cut" to 1.0, "attack" to 0.001, "decay" to 0.0, "sustain" to 1.0, "pan" to 0.0))
        mixer.renderFrames(SR / 10)
        mixer.startVoice(dc("cut" to 1.0, pan = 1.0)) // a sample of group 1 does not cut the synth
        val left = channel(mixer.renderFrames(SR / 10), 0)
        assertTrue(peak(left, frame(0.05), left.size) > 0.3f)
        mixer.startVoice(vp(sine, 5.0, "note" to 69.0, "gain" to 1.0, "cut" to 1.0, "pan" to 1.0)) // a synth of group 1 does not cut the sample
        val right = channel(mixer.renderFrames(SR / 10), 1)
        assertTrue(right[frame(0.05)] > 0.9f)
        assertEquals(3, mixer.activeVoices)
    }

    @Test
    fun theGroupsAreGlobalAcrossOrbits() {
        val mixer = Mixer(SR)
        mixer.startVoice(dc("cut" to 1.0, "orbit" to 1.0, pan = 0.0))
        mixer.renderFrames(SR / 10)
        mixer.startVoice(dc("cut" to 1.0, "orbit" to 2.0, pan = 1.0))
        assertTrue(peak(channel(mixer.renderFrames(SR / 10), 0), frame(0.011), SR / 10) < 1e-6f)
    }

    // ------------------------------------------------------------------------------------------------ steal

    @Test
    fun theOldestVoiceIsStolenWhenTheNextOneWouldBeBeyondMaxPolyphony() {
        val mixer = Mixer(SR)
        mixer.maxPolyphony = 4
        // oldest on the left, the rest on the right
        mixer.startVoice(dc(pan = 0.0))
        mixer.renderFrames(SR / 20)
        repeat(3) { mixer.startVoice(dc("gain" to 0.01, pan = 1.0)) }
        mixer.renderFrames(SR / 20)
        assertEquals(4, mixer.countedVoices)
        assertEquals(4, mixer.activeVoices, "nothing is stolen with exactly the maximum")
        mixer.startVoice(dc("gain" to 0.01, pan = 1.0)) // the fifth steals the first
        val left = channel(mixer.renderFrames(SR / 2), 0)
        assertEquals(4, mixer.countedVoices)
        assertEquals(0.5, left[frame(0.125)].toDouble(), 0.002, "linear to 0 at tNew + 0.25, from the level at tNew")
        assertEquals(0.0, left[frame(0.25) - 1].toDouble(), 0.001)
        assertTrue(peak(left, frame(0.2501), left.size) < 1e-6f, "silent from the end of the ramp")
    }

    @Test
    fun theStolenVoiceKeepsPlayingNormallyUntilTheStealStarts() {
        val mixer = Mixer(SR)
        mixer.maxPolyphony = 1
        mixer.startVoice(dc(pan = 0.0))
        mixer.renderFrames(SR / 10)
        mixer.startVoice(dc("gain" to 0.001, pan = 1.0))
        val left = channel(mixer.renderFrames(SR / 2), 0)
        assertEquals(1.0, left[0].toDouble(), 1e-3, "the ramp starts at tNew, from the current level")
        assertEquals(0.0, left[frame(0.25) - 1].toDouble(), 0.001)
    }

    @Test
    fun aStolenSynthRampsItsAmplitudeEnvelopeNotItsSourceLevel() {
        val mixer = Mixer(SR)
        mixer.maxPolyphony = 1
        mixer.startVoice(vp(sine, 5.0, "note" to 69.0, "gain" to 1.0, "attack" to 0.001, "decay" to 0.0, "sustain" to 1.0, "pan" to 0.0))
        mixer.renderFrames(SR / 10)
        mixer.startVoice(dc("gain" to 0.001, pan = 1.0))
        val left = channel(mixer.renderFrames(SR / 2), 0)
        // the ramp is inserted into the envelope's timeline (Web Audio rules): it runs from the last event before it, the end of the 1 ms decay at
        // 2 ms, to 0 at tNew + 0.25 = 0.35 s, so the level is 1 - (t - 0.002) / 0.348 where the source level of 0.35 multiplies it
        fun expected(t: Double) = 0.35 * (1.0 - (t - 0.002) / 0.348)
        assertEquals(expected(0.1), peak(left, 0, 100).toDouble(), 0.01)
        assertEquals(expected(0.225), peak(left, frame(0.1245), frame(0.1255)).toDouble(), 0.004)
        assertTrue(peak(left, frame(0.2501), left.size) < 1e-6f)
    }

    @Test
    fun aVoiceWhoseChainIsReleasedIsNotStolenAndFreesItsPlace() {
        val mixer = Mixer(SR)
        mixer.maxPolyphony = 2
        mixer.startVoice(dc(pan = 0.0, seconds = 5.0)) // A: lives long
        mixer.startVoice(vp(sine, 0.1, "note" to 69.0, "gain" to 0.001)) // B: gate 0.1 s, chain over at ~0.12 s
        mixer.renderFrames(SR / 2)
        assertEquals(1, mixer.countedVoices)
        mixer.startVoice(dc("gain" to 0.001, pan = 1.0)) // C: 2 counted, no steal
        val left = channel(mixer.renderFrames(SR / 10), 0)
        assertEquals(1.0, left[frame(0.09)].toDouble(), 1e-3, "A was not touched")
    }

    @Test
    fun theLedgerStealsTheOldestFirstAndStealsSeveralWhenMaxPolyphonyShrank() {
        val ledger = PolyphonyLedger(8)
        val orbits = OrbitSet(SR, IrExecutor.INLINE)
        val buses = AudioBuses(SR)
        val pool = FxGroupPool(SR)
        val v = Array(6) { Voice(SR, orbits, buses, pool) }
        val stolen = ArrayList<Pair<Voice, Double>>()
        val action = StealAction { voice, t -> stolen.add(voice to t) }

        for (i in 0 until 3) ledger.admit(v[i], i.toDouble(), 100.0, 3, action)
        assertEquals(3, ledger.size)
        assertTrue(stolen.isEmpty(), "three of three: nothing yet")

        ledger.admit(v[3], 3.0, 100.0, 3, action) // i = 0 <= 3 - 3: one steal
        assertEquals(1, stolen.size)
        assertSame(v[0], stolen[0].first)
        assertEquals(3.0, stolen[0].second, 0.0)
        assertEquals(3, ledger.size)

        // the loop of the original re-reads the shrinking size: with 3 entries and a maximum of 1 it steals i = 0 (size 3) and i = 1 (size 2)
        ledger.admit(v[4], 4.0, 100.0, 1, action)
        assertEquals(3, stolen.size)
        assertSame(v[1], stolen[1].first)
        assertSame(v[2], stolen[2].first)
        assertEquals(2, ledger.size, "v3 stays, plus the new voice")
    }

    @Test
    fun ledgerEntriesLiveUntilTheirActiveTimeAndTheCountUsesChainsNotSound() {
        val ledger = PolyphonyLedger(4)
        val orbits = OrbitSet(SR, IrExecutor.INLINE)
        val v = Array(3) { Voice(SR, orbits, AudioBuses(SR), FxGroupPool(SR)) }
        val action = StealAction { _, _ -> }
        ledger.admit(v[0], 0.0, 1.0, 10, action)
        ledger.admit(v[1], 0.0, 2.0, 10, action)
        ledger.admit(v[2], 0.0, 3.0, 10, action)
        ledger.expire(0.99)
        assertEquals(3, ledger.size)
        ledger.expire(1.0)
        assertEquals(2, ledger.size, "an entry whose time is `now` is over")
        ledger.expire(2.5)
        assertEquals(1, ledger.size)
        ledger.remove(v[2])
        assertEquals(0, ledger.size)
        ledger.remove(v[2])
        assertEquals(0, ledger.size)
        assertFalse(ledger.size > 0)
    }
}
