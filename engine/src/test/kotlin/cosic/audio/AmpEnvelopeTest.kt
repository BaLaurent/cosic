package cosic.audio

import cosic.pattern.Undefined
import cosic.player.hapSeconds
import cosic.pattern.Rational
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The amplitude envelope of a sample voice (CHAIN Task B step 4, sampler.mjs:312-359): the gate (`clip`, `loop`, `release`, `duration`), the shapes of
 * `getParamADSR` and the end of the source. The built-in sounds make their own envelope inside their source (`SourceEnvelope`, checked against superdough's
 * own code in the source tests); one of them goes through a mixer at the end of this file.
 */
class AmpEnvelopeTest {
    private fun env() = AParam(0.0, -3.4e38, 3.4e38)

    /** The gate is the hap's duration unless [clip] says otherwise (it is the length of the [slice] when none of [clip], [loop] and [release] is set). */
    private fun schedule(
        env: AParam, a: Double = Double.NaN, d: Double = Double.NaN, s: Double = Double.NaN, r: Double = Double.NaN,
        t: Double = 2.0, duration: Double = 1.0, slice: Double = Double.NaN, clip: Boolean = true, loop: Boolean = false, release: Boolean = false,
    ): Double = AmpEnvelope.schedule(env, a, d, s, r, t, duration, slice, clip, loop, release)

    // ------------------------------------------------------------------------------------------------ defaults

    @Test
    fun aSampleHasNoDefaultEnvelopeOfItsOwnSoItIsAFlatGate() {
        val e = env()
        val end = schedule(e, slice = 0.1, duration = 1.0, clip = false)
        assertEquals(1.0, e.valueAt(2.001), 1e-9)
        assertEquals(1.0, e.valueAt(2.05), 1e-9)
        assertEquals(1.0, e.valueAt(2.1), 1e-9)
        assertEquals(0.0, e.valueAt(2.11), 1e-9, "release 10 ms after the slice")
        assertEquals(2.1 + 0.01 + 0.01, end, 1e-12)
    }

    // ------------------------------------------------------------------------------------------------ gate

    @Test
    fun aSampleGateIsTheSliceUnlessClipLoopOrReleaseIsSet() {
        fun end(clip: Boolean = false, loop: Boolean = false, release: Boolean = false): Double =
            schedule(env(), slice = 0.1, duration = 1.0, clip = clip, loop = loop, release = release)
        assertEquals(2.12, end(), 1e-12)
        assertEquals(3.02, end(clip = true), 1e-12)
        assertEquals(3.02, end(loop = true), 1e-12)
        assertEquals(3.02, end(release = true), 1e-12)
    }

    @Test
    fun theReleaseEndsTheSourceAtHoldEndPlusReleasePlusTenMilliseconds() {
        val e = env()
        val end = schedule(e, a = 0.0, d = 0.0, s = 1.0, r = 0.5)
        assertEquals(3.0 + 0.5 + 0.01, end, 1e-12)
        assertEquals(1.0, e.valueAt(3.0), 1e-9)
        assertEquals(0.5, e.valueAt(3.25), 1e-9, "linear release")
        assertEquals(0.0, e.valueAt(3.5), 1e-9)
    }

    // ------------------------------------------------------------------------------------------------ colon list / getADSRValues

    @Test
    fun adsrWithOnlyTheAttackSustainsAtOneAndOnlyTheDecayIsAPureDecay() {
        // adsr("0.1") stores attack 0.1 and undefined for the other three
        assertContentEquals(doubleArrayOf(0.1, 0.001, 1.0, 0.01), getADSRValues(0.1, Double.NaN, Double.NaN, Double.NaN))
        // decay alone: sustain 0.001
        assertContentEquals(doubleArrayOf(0.001, 0.3, 0.001, 0.01), getADSRValues(Double.NaN, 0.3, Double.NaN, Double.NaN))
        // a full adsr(".1:.1:.5:.2")
        assertContentEquals(doubleArrayOf(0.1, 0.1, 0.5, 0.2), getADSRValues(0.1, 0.1, 0.5, 0.2))
        // minimums and the sustain cap
        assertContentEquals(doubleArrayOf(0.001, 0.001, 1.0, 0.01), getADSRValues(0.0, 0.0, 7.0, 0.0))
    }

    @Test
    fun adsrOfOneValueThroughTheMixerSustainsAtFullLevel() {
        val level = 0.3 * 0.8
        val left = renderLeft(synthVp("sine", 1.0, "note" to 69.0, "attack" to 0.1, "decay" to Undefined, "sustain" to Undefined, "release" to Undefined))
        assertEquals(level, peak(left, 6000, 12000).toDouble(), 0.004)
        val full = renderLeft(synthVp("sine", 1.0, "note" to 69.0, "attack" to 0.1, "decay" to 0.1, "sustain" to 0.5, "release" to 0.2), SR * 14 / 10)
        assertEquals(0.5 * level, peak(full, 12000, 40000).toDouble(), 0.004, "sustain after the 0.1 s decay")
        assertEquals(0.25 * level, peak(full, (1.1 * SR).toInt() - 200, (1.1 * SR).toInt()).toDouble(), 0.006, "halfway down the release")
        assertEquals(0f, peak(full, (1.22 * SR).toInt(), full.size))
    }

    // ------------------------------------------------------------------------------------------------ shapes of getParamADSR

    @Test
    fun anAttackLongerThanTheGateIsCutOnTheStraightLine() {
        val e = env()
        schedule(e, a = 1.0, duration = 0.5)
        assertEquals(0.25, e.valueAt(2.25), 1e-9)
        assertEquals(0.5, e.valueAt(2.5), 1e-9, "the gate ends at duration / attack")
        assertEquals(0.25, e.valueAt(2.505), 1e-9, "and the release starts from there")
    }

    @Test
    fun anAttackAndDecayLongerThanTheGateAreCutDuringTheDecay() {
        val e = env()
        schedule(e, a = 0.1, d = 0.5, s = 0.0, r = 0.0, duration = 0.3)
        assertEquals(1.0, e.valueAt(2.1), 1e-9)
        assertEquals(0.8, e.valueAt(2.2), 1e-9)
        assertEquals(0.6, e.valueAt(2.3), 1e-9, "1 - (0.3 - 0.1) * 1 / 0.5")
    }

    @Test
    fun aZeroLengthGateStillGetsItsReleaseRamp() {
        val e = env()
        val end = schedule(e, duration = 0.0)
        assertEquals(2.0 + 0.01 + 0.01, end, 1e-12)
        assertTrue(e.valueAt(2.0) <= 1.0)
        assertEquals(0.0, e.valueAt(2.02), 1e-9)
    }

    // ------------------------------------------------------------------------------------------------ the hap's duration

    @Test
    fun theDurationOfAHapIsItsWholeTimesClipOrTheDurationControl() {
        val whole = Rational(1)
        assertEquals(2.0, hapSeconds(emptyMap(), whole, 0.5), 0.0)
        assertEquals(1.0, hapSeconds(mapOf("clip" to 0.5), whole, 0.5), 0.0, "clip and legato scale the hap")
        assertEquals(0.5, hapSeconds(mapOf("duration" to 0.25), whole, 0.5), 0.0, "duration is in cycles and replaces the whole")
        assertEquals(1.0, hapSeconds(mapOf("duration" to 0.25, "clip" to 2.0), whole, 0.5), 0.0)
        assertEquals(2.0, hapSeconds(mapOf("clip" to "x"), whole, 0.5), 0.0, "only a number counts")
        assertEquals(1.0, hapSeconds(emptyMap(), whole, 1.0), 0.0)
    }

    @Test
    fun holdIsNotReadByTheSoundEngine() {
        assertContentEquals(renderLeft(synthVp("sine", 1.0, "note" to 69.0)), renderLeft(synthVp("sine", 1.0, "note" to 69.0, "hold" to 0.5)))
    }

    @Test
    fun theVoiceGateFollowsTheDurationThatTheSchedulerComputedWithClip() {
        // a 5 s DC sample on a hap of 2 s: clip .5 shortens the gate to 1 s (the scheduler applied it), duration is not multiplied again
        val clipped = renderLeft(vp(dcSource(5.0), hapSeconds(mapOf("clip" to 0.5), Rational(1), 0.5), "clip" to 0.5), SR * 14 / 10)
        assertEquals(0.8, clipped[(0.9 * SR).toInt()].toDouble(), 1e-4)
        assertEquals(0f, clipped[(1.05 * SR).toInt()], 1e-4f)
        val whole = renderLeft(vp(dcSource(5.0), 2.0, "clip" to 1.0), SR * 22 / 10)
        assertEquals(0.8, whole[(1.9 * SR).toInt()].toDouble(), 1e-4)
        assertEquals(0f, whole[(2.05 * SR).toInt()], 1e-4f)
    }
}
