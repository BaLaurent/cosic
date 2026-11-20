package cosic.audio

import cosic.audio.dsp.LadderFilter
import cosic.pattern.logger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The filter stages (docs/parity/spec/superdough-chain.md section 1.6.1-1.6.3, task C): the biquad's analytic response, its special cases, the
 * envelope, the LFO, the models (`ftype`), the vowel filter and the stage's registration. Bit-level agreement with Chromium and with
 * superdough's `createFilter` is in [FilterGoldenTest].
 */
class FilterTest {
    private fun biquad(type: BiquadType, f0: Double, q: Double, sr: Int = SR) = Biquad(sr).also { it.setCoefficients(type, f0, q) }

    private fun gainDb(type: BiquadType, f0: Double, q: Double, freq: Double): Double = biquad(type, f0, q).magnitudeDb(freq)

    /** The frequency above (or below, with [rising]) [from] where [response] crosses -3 dB, by bisection. */
    private fun minus3Db(response: (Double) -> Double, from: Double, to: Double): Double {
        var lo = from
        var hi = to
        val fallingAtHi = response(hi) < -3.0
        repeat(80) {
            val mid = (lo + hi) / 2
            val below = response(mid) < -3.0
            if (below == fallingAtHi) hi = mid else lo = mid
        }
        return (lo + hi) / 2
    }

    // ---------------------------------------------------------------------------------------------------------- the biquad

    @Test
    fun lowPassIsFlatAtTheCutoffWithQZeroDecibels() {
        assertEquals(0.0, gainDb(BiquadType.LOWPASS, 1000.0, 0.0, 1000.0), 0.05)
        assertEquals(0.04, gainDb(BiquadType.LOWPASS, 1000.0, 0.0, 100.0), 0.05)
        assertEquals(-37.7, gainDb(BiquadType.LOWPASS, 1000.0, 0.0, 8000.0), 0.5)
    }

    @Test
    fun lowPassQIsInDecibelsSoTheDefaultOfOneGivesOneDecibelAtTheCutoff() {
        assertEquals(1.0, gainDb(BiquadType.LOWPASS, 1000.0, 1.0, 1000.0), 0.05)
        assertEquals(20.0, gainDb(BiquadType.LOWPASS, 1000.0, 20.0, 1000.0), 0.05)
        val minus3 = minus3Db({ gainDb(BiquadType.LOWPASS, 1000.0, 1.0, it) }, 1000.0, 6000.0)
        assertEquals(1329.0, minus3, 1329.0 * 0.005)
    }

    @Test
    fun lowPassWithQMinus3dBIsButterworth() {
        assertEquals(-3.01, gainDb(BiquadType.LOWPASS, 1000.0, -3.0103, 1000.0), 0.05)
    }

    @Test
    fun highPassIsFlatAtTheCutoffAndFallsOffBelow() {
        assertEquals(0.0, gainDb(BiquadType.HIGHPASS, 1000.0, 0.0, 1000.0), 0.05)
        assertEquals(-36.1, gainDb(BiquadType.HIGHPASS, 1000.0, 0.0, 125.0), 0.5)
        assertEquals(1.0, gainDb(BiquadType.HIGHPASS, 1000.0, 1.0, 1000.0), 0.05)
    }

    @Test
    fun bandPassHasUnityGainAtTheCentreWithALinearQ() {
        assertEquals(0.0, gainDb(BiquadType.BANDPASS, 1000.0, 4.0, 1000.0), 0.05)
        val response = { f: Double -> gainDb(BiquadType.BANDPASS, 1000.0, 4.0, f) }
        assertEquals(883.0, minus3Db(response, 500.0, 1000.0), 883.0 * 0.005)
        assertEquals(1132.3, minus3Db(response, 1000.0, 2000.0), 1132.3 * 0.005)
    }

    @Test
    fun theNotchRemovesItsCentreAndLeavesTheRestAlone() {
        assertTrue(gainDb(BiquadType.NOTCH, 1000.0, 4.0, 1000.0) < -60.0)
        assertEquals(0.0, gainDb(BiquadType.NOTCH, 1000.0, 4.0, 100.0), 0.1)
        assertEquals(0.0, gainDb(BiquadType.NOTCH, 1000.0, 4.0, 10000.0), 0.5)
    }

    @Test
    fun theSpecialCasesAreChromiums() {
        val nyquist = SR / 2.0
        val probes = doubleArrayOf(50.0, 1000.0, 9000.0)
        for (f in probes) {
            assertEquals(-1000.0, biquad(BiquadType.LOWPASS, 0.0, 1.0).magnitudeDb(f), "a low-pass at 0 passes nothing")
            assertEquals(0.0, biquad(BiquadType.LOWPASS, nyquist, 1.0).magnitudeDb(f), 1e-9, "a low-pass at Nyquist is the identity")
            assertEquals(0.0, biquad(BiquadType.LOWPASS, 1e9, 1.0).magnitudeDb(f), 1e-9, "the frequency is clamped to Nyquist")
            assertEquals(0.0, biquad(BiquadType.HIGHPASS, 0.0, 1.0).magnitudeDb(f), 1e-9, "a high-pass at 0 is the identity")
            assertEquals(-1000.0, biquad(BiquadType.HIGHPASS, nyquist, 1.0).magnitudeDb(f), "a high-pass at Nyquist passes nothing")
            assertEquals(0.0, biquad(BiquadType.BANDPASS, 1000.0, 0.0).magnitudeDb(f), 1e-9, "a band-pass with Q 0 is the identity")
            assertEquals(0.0, biquad(BiquadType.BANDPASS, 1000.0, -3.0).magnitudeDb(f), 1e-9, "a negative Q counts as 0")
            assertEquals(-1000.0, biquad(BiquadType.BANDPASS, 0.0, 4.0).magnitudeDb(f), "a band-pass at 0 passes nothing")
            assertEquals(-1000.0, biquad(BiquadType.BANDPASS, nyquist, 4.0).magnitudeDb(f), "a band-pass at Nyquist passes nothing")
            assertEquals(-1000.0, biquad(BiquadType.NOTCH, 1000.0, 0.0).magnitudeDb(f), "a notch with Q 0 passes nothing")
            assertEquals(0.0, biquad(BiquadType.NOTCH, 0.0, 4.0).magnitudeDb(f), 1e-9, "a notch at 0 is the identity")
        }
    }

    @Test
    fun theAnalyticResponseIsWhatTheFilterDoesToASine() {
        val cases = listOf(
            Triple(BiquadType.LOWPASS, 1000.0, 1.0), Triple(BiquadType.LOWPASS, 3000.0, 12.0), Triple(BiquadType.HIGHPASS, 500.0, 3.0),
            Triple(BiquadType.BANDPASS, 2000.0, 6.0), Triple(BiquadType.NOTCH, 1500.0, 2.0),
        )
        for ((type, f0, q) in cases) {
            for (freq in doubleArrayOf(300.0, 1200.0, f0, 5000.0)) {
                val filter = biquad(type, f0, q)
                val input = sineWave(freq, 1.0, 0.25)
                val output = input.copyOf()
                filter.process(output, output.size, 0)
                val measured = dbOf(rms(output, 24000, 48000) / rms(input, 24000, 48000))
                val expected = filter.magnitudeDb(freq)
                if (expected > -60.0) assertEquals(expected, measured, 0.05, "$type $f0 q $q at $freq Hz")
            }
        }
    }

    @Test
    fun callsThatSplitTheBlockGiveTheSameOutputAndTheChannelsAreIndependent() {
        val input = noise(1000, 5)
        val whole = input.copyOf()
        biquad(BiquadType.LOWPASS, 900.0, 4.0).process(whole, 1000, 0)
        val pieces = input.copyOf()
        val filter = biquad(BiquadType.LOWPASS, 900.0, 4.0)
        var from = 0
        while (from < 1000) {
            val to = Math.min(1000, from + 37)
            filter.process(pieces, from, to, 0)
            from = to
        }
        assertContentEquals(whole, pieces)

        // channel 1 has its own memory: filtering the same signal on it gives the same result as channel 0 did
        val other = noise(1000, 6)
        val both = Biquad(SR).also { it.setCoefficients(BiquadType.LOWPASS, 900.0, 4.0) }
        val first = input.copyOf()
        val second = other.copyOf()
        both.process(first, 1000, 0)
        both.process(second, 1000, 1)
        val alone = other.copyOf()
        biquad(BiquadType.LOWPASS, 900.0, 4.0).process(alone, 1000, 0)
        assertContentEquals(alone, second)
    }

    @Test
    fun anUnstableSettingRestartsFromSilenceInsteadOfPoisoningTheChain() {
        // a hugely negative Q puts the poles outside the unit circle
        val filter = biquad(BiquadType.LOWPASS, 100.0, -400.0)
        val data = noise(20000, 3)
        filter.process(data, data.size, 0)
        assertTrue(allFinite(data), "the output stays finite")
        val again = noise(256, 3)
        again[10] = Float.NaN
        biquad(BiquadType.LOWPASS, 1000.0, 1.0).process(again, 256, 0)
        assertTrue(allFinite(again), "a NaN in the input does not stay in the filter")
    }

    // ------------------------------------------------------------------------------------------------------ the stage

    private fun lowpass(sr: Int = SR, interval: Int = FILTER_UPDATE_INTERVAL) = FilterStage(sr, BiquadType.LOWPASS, FilterKeys.LOWPASS, interval)

    /** A stage configured as a main chain's from [controls] for a voice that starts at [t]. */
    private fun stage(
        vararg controls: Pair<String, Any?>, t: Double = 0.0, gate: Double = 1.0, cps: Double = 0.5, cycle: Double = 0.0,
        type: BiquadType = BiquadType.LOWPASS, keys: FilterKeys = FilterKeys.LOWPASS,
    ): FilterStage {
        val s = FilterStage(SR, type, keys)
        assertTrue(s.configure(controlsOf(*controls), timingOf(t, gate, cps, cycle), true), "the stage is in the chain")
        return s
    }

    private fun gainOf(stage: ChainStage, freq: Double, seconds: Double = 1.0, amp: Double = 0.25): Double {
        val input = sineWave(freq, seconds, amp)
        val output = runStage(stage, listOf(input))[0]
        val half = input.size / 2
        return dbOf(rms(output, half, input.size) / rms(input, half, input.size))
    }

    @Test
    fun aFilterStageIsInTheChainOnlyWhenItsCutoffIsSet() {
        val timing = timingOf()
        assertFalse(FilterStage(SR, BiquadType.LOWPASS, FilterKeys.LOWPASS).configure(controlsOf("hcutoff" to 500.0, "bandf" to 900.0), timing, true))
        assertFalse(FilterStage(SR, BiquadType.HIGHPASS, FilterKeys.HIGHPASS).configure(controlsOf("cutoff" to 500.0), timing, true))
        assertFalse(FilterStage(SR, BiquadType.BANDPASS, FilterKeys.BANDPASS).configure(controlsOf("cutoff" to 500.0, "hcutoff" to 1.0), timing, true))
        assertTrue(FilterStage(SR, BiquadType.HIGHPASS, FilterKeys.HIGHPASS).configure(controlsOf("hcutoff" to 500.0), timing, true))
        assertTrue(FilterStage(SR, BiquadType.BANDPASS, FilterKeys.BANDPASS).configure(controlsOf("bandf" to 500.0), timing, true))
    }

    @Test
    fun theStageFiltersLikeTheBiquadWithTheControlsOfThePattern() {
        // lpf(1000) with the default Q of 1 dB, hpf(1000, q 0), bpf(1000, q 4)
        assertEquals(1.0, gainOf(stage("cutoff" to 1000.0), 1000.0), 0.1)
        assertEquals(0.0, gainOf(stage("hcutoff" to 1000.0, "hresonance" to 0.0, type = BiquadType.HIGHPASS, keys = FilterKeys.HIGHPASS), 1000.0), 0.1)
        assertEquals(-36.1, gainOf(stage("hcutoff" to 1000.0, "hresonance" to 0.0, type = BiquadType.HIGHPASS, keys = FilterKeys.HIGHPASS), 125.0, amp = 0.5), 0.6)
        assertEquals(0.0, gainOf(stage("bandf" to 1000.0, "bandq" to 4.0, type = BiquadType.BANDPASS, keys = FilterKeys.BANDPASS), 1000.0), 0.1)
        assertEquals(-3.0, gainOf(stage("bandf" to 1000.0, "bandq" to 4.0, type = BiquadType.BANDPASS, keys = FilterKeys.BANDPASS), 883.3), 0.15)
    }

    @Test
    fun cutoffZeroIsSilenceAndACutoffAboveNyquistIsTheIdentity() {
        val input = noise(2000, 9)
        val silent = runStage(stage("cutoff" to 0.0), listOf(input))[0]
        assertEquals(0f, peak(silent))
        val through = runStage(stage("cutoff" to 1e9), listOf(input))[0]
        assertContentEquals(input, through)
    }

    @Test
    fun ftypeTwentyFourDbPutsASecondFilterInSeriesAndDoublesTheAttenuation() {
        val plain = gainOf(stage("cutoff" to 1000.0), 8000.0, amp = 0.5)
        for (ftype in listOf<Any>("24db", 2.0, 5.0, -1.0)) {
            val double = gainOf(stage("cutoff" to 1000.0, "ftype" to ftype), 8000.0, amp = 0.5)
            assertEquals(2 * plain, double, 0.2, "ftype $ftype")
        }
        for (ftype in listOf<Any>("12db", 0.0, 1.0, 3.0)) {
            assertEquals(plain, gainOf(stage("cutoff" to 1000.0, "ftype" to ftype), 8000.0, amp = 0.5), 1e-6, "ftype $ftype is a single filter")
        }
    }

    @Test
    fun ftypeSelectsTheLadderOnlyWhenItIsTheString() {
        val input = noise(4000, 12)
        val biquadOut = runStage(stage("cutoff" to 900.0, "resonance" to 5.0), listOf(input))[0]
        val numberOne = runStage(stage("cutoff" to 900.0, "resonance" to 5.0, "ftype" to 1.0), listOf(input))[0]
        assertContentEquals(biquadOut, numberOne, "ftype(1) is a plain biquad")
        val ladderOut = runStage(stage("cutoff" to 900.0, "resonance" to 5.0, "ftype" to "ladder"), listOf(input))[0]
        assertFalse(biquadOut.contentEquals(ladderOut), "the ladder sounds different")
        val direct = input.copyOf()
        LadderFilter(SR).also { filter ->
            var from = 0
            while (from < direct.size) {
                val to = Math.min(direct.size, from + QUANTUM)
                filter.process(direct, null, from, to, 900.0, 5.0, 0.69)
                from = to
            }
        }
        assertContentEquals(direct, ladderOut, "the ladder gets the raw cutoff, Q and the default drive of 0.69")
    }

    @Test
    fun theLadderIsALowPassWhateverTheChainAskedFor() {
        val input = noise(4000, 13)
        val asLow = runStage(stage("cutoff" to 900.0, "ftype" to "ladder"), listOf(input))[0]
        val asHigh = runStage(stage("hcutoff" to 900.0, "ftype" to "ladder", type = BiquadType.HIGHPASS, keys = FilterKeys.HIGHPASS), listOf(input))[0]
        val asBand = runStage(stage("bandf" to 900.0, "ftype" to "ladder", type = BiquadType.BANDPASS, keys = FilterKeys.BANDPASS), listOf(input))[0]
        assertContentEquals(asLow, asHigh)
        assertContentEquals(asLow, asBand)
    }

    @Test
    fun theLadderPassesDirectCurrentWithUnityGain() {
        val dc = FloatArray(24000) { 0.05f }
        val out = runStage(stage("cutoff" to 1000.0, "ftype" to "ladder"), listOf(dc))[0]
        assertEquals(0.05f, out[23999], 0.05f * 0.02f)
    }

    @Test
    fun theLadderDriveIsAControl() {
        val input = noise(4000, 14, 0.9f)
        val a = runStage(stage("cutoff" to 900.0, "ftype" to "ladder"), listOf(input))[0]
        val b = runStage(stage("cutoff" to 900.0, "ftype" to "ladder", "drive" to 2.0), listOf(input))[0]
        assertFalse(a.contentEquals(b))
    }

    // -------------------------------------------------------------------------------------------------------- envelopes

    @Test
    fun anEnvelopeSweepsTheCutoffUpByEnvOctavesAndBack() {
        // f = 300 Hz, lpenv 4, the defaults [0.005, 0.14, 0, 0.1]: up to 300 * 2^4 in 5 ms, then down to the sustain level (the minimum) in 140 ms
        val s = stage("cutoff" to 300.0, "lpenv" to 4.0)
        assertEquals(300.0, s.frequency.valueAt(0.0), 1e-9)
        assertEquals(4800.0, s.frequency.valueAt(0.005), 1e-6)
        assertEquals(1200.0, s.frequency.valueAt(0.075), 1e-6)
        assertEquals(300.0, s.frequency.valueAt(0.145), 1e-6)
        assertEquals(300.0, s.frequency.valueAt(0.5), 1e-6)
    }

    @Test
    fun theAnchorMovesTheEnvelopeRangeBelowTheCutoff() {
        val one = stage("cutoff" to 300.0, "lpenv" to 4.0, "fanchor" to 1.0)
        assertEquals(18.75, one.frequency.valueAt(0.0), 1e-9)
        assertEquals(300.0, one.frequency.valueAt(0.005), 1e-6)
        val half = stage("cutoff" to 300.0, "lpenv" to 4.0, "fanchor" to 0.5)
        assertEquals(75.0, half.frequency.valueAt(0.0), 1e-9)
        assertEquals(1200.0, half.frequency.valueAt(0.005), 1e-6)
    }

    @Test
    fun aNegativeEnvelopeStartsHighDipsToTheCutoffAndComesBack() {
        val s = stage("cutoff" to 300.0, "lpenv" to -2.0)
        assertEquals(1200.0, s.frequency.valueAt(0.0), 1e-9)
        assertEquals(300.0, s.frequency.valueAt(0.005), 1e-6)
        assertEquals(1200.0, s.frequency.valueAt(0.145), 1e-6)
        assertEquals(1200.0, s.frequency.valueAt(0.5), 1e-6)
    }

    @Test
    fun anEnvelopeWithOnlyAnAttackSustainsAtItsMaximum() {
        // lpattack .1: sustain 1, so the cutoff stays at the top (cutoff * 2 for the default env of 1) for the rest of the gate
        val s = stage("cutoff" to 300.0, "lpattack" to 0.1)
        assertEquals(300.0, s.frequency.valueAt(0.0), 1e-9)
        assertEquals(600.0, s.frequency.valueAt(0.1), 1e-6)
        assertEquals(600.0, s.frequency.valueAt(0.6), 1e-6)
        // after the gate it falls back over the release (0.01 s minimum)
        assertEquals(300.0, s.frequency.valueAt(1.02), 1e-6)
    }

    @Test
    fun withoutAnyEnvelopeControlTheCutoffIsConstant() {
        val s = stage("cutoff" to 777.0)
        assertTrue(s.frequency.isConstant)
        assertEquals(777.0, s.frequency.constantValue)
        assertFalse(s.frequency.hasInputs)
    }

    @Test
    fun theHighAndBandPassHaveTheirOwnEnvelopeControls() {
        val hp = stage("hcutoff" to 200.0, "hpenv" to 3.0, "hpattack" to 0.01, "hpdecay" to 0.1, "hpsustain" to 0.5, type = BiquadType.HIGHPASS, keys = FilterKeys.HIGHPASS)
        assertEquals(200.0, hp.frequency.valueAt(0.0), 1e-9)
        assertEquals(1600.0, hp.frequency.valueAt(0.01), 1e-6)
        assertEquals(200.0 + 0.5 * 1400.0, hp.frequency.valueAt(0.11), 1e-6)
        val bp = stage("bandf" to 500.0, "bpenv" to -1.0, type = BiquadType.BANDPASS, keys = FilterKeys.BANDPASS)
        assertEquals(1000.0, bp.frequency.valueAt(0.0), 1e-9)
        assertEquals(500.0, bp.frequency.valueAt(0.005), 1e-6)
        // a low-pass envelope does not move them
        val untouched = stage("hcutoff" to 200.0, "lpenv" to 4.0, type = BiquadType.HIGHPASS, keys = FilterKeys.HIGHPASS)
        assertTrue(untouched.frequency.isConstant)
    }

    @Test
    fun theEnvelopeLimitsItsRangeTo20kHz() {
        val s = stage("cutoff" to 15000.0, "lpenv" to 4.0)
        assertEquals(20000.0, s.frequency.valueAt(0.005), 1e-6)
    }

    @Test
    fun theEnvelopeStartsAtTheOnsetOfTheVoiceAndEndsWithItsGate() {
        val s = stage("cutoff" to 300.0, "lpenv" to 4.0, "lpattack" to 0.1, "lpdecay" to 0.1, "lpsustain" to 0.0, t = 2.0, gate = 0.15)
        assertEquals(300.0, s.frequency.valueAt(2.0), 1e-9)
        // the gate is shorter than attack + decay: the envelope is cut where it stands at the end of the gate (the straight line of the decay)
        assertTrue(s.frequency.valueAt(2.1) > 4000.0)
        assertTrue(s.frequency.valueAt(2.15) < s.frequency.valueAt(2.1))
        assertEquals(300.0, s.frequency.valueAt(2.15 + 0.1), 1e-6, "back to the minimum after the release")
    }

    // ------------------------------------------------------------------------------------------------------------- LFO

    /** The frequency parameter of [stage] (with its LFO) from frame [startFrame] over [frames] frames, block by block. */
    private fun sweep(stage: FilterStage, frames: Int, startFrame: Long = 0L): FloatArray {
        val out = FloatArray(frames)
        val ctx = BlockContext(SR)
        val buf = FloatArray(BLOCK_FRAMES)
        var done = 0
        while (done < frames) {
            val n = Math.min(BLOCK_FRAMES, frames - done)
            ctx.startFrame = startFrame + done
            ctx.n = n
            stage.frequency.fill(buf, ctx)
            System.arraycopy(buf, 0, out, done, n)
            done += n
        }
        return out
    }

    /** A stage whose gate outlasts the sweeps of the LFO tests (the LFO is gone from the end of the gate). */
    private fun lfoStage(vararg controls: Pair<String, Any?>, cps: Double = 0.5, cycle: Double = 0.0): FilterStage =
        stage(*controls, gate = 3.0, cps = cps, cycle = cycle)

    @Test
    fun theLfoSwingsTheCutoffByItsDepthTimesTheCutoffAroundIt() {
        // lpdepth 1 on 1000 Hz: +-500 Hz, at 2 Hz; the phase starts at frac(cycle / cps * rate): cycle 0.3 at 0.5 cps is 0.6 s, 1.2 turns, phase 0.2
        val s = lfoStage("cutoff" to 1000.0, "lpdepth" to 1.0, "lprate" to 2.0, cps = 0.5, cycle = 0.3)
        val values = sweep(s, SR, 128) // the LFO starts with the first quantum after the onset
        assertEquals(900.0f, values[0], 1.0f, "tri(0.2) = 0.4, minus the dc offset 0.5, times 1000")
        assertEquals(1500.0f, values.max(), 10.0f)
        assertEquals(500.0f, values.min(), 10.0f)
        // two cycles in a second: the cutoff passes 1000 four times (a sample may be exactly 1000, so look at the signs of the others)
        var crossings = 0
        var last = 0f
        for (v in values) {
            val side = Math.signum(v - 1000f)
            if (side != 0f) {
                if (last != 0f && side != last) crossings++
                last = side
            }
        }
        assertEquals(4, crossings)
    }

    @Test
    fun theLfoDepthCanBeAbsoluteAndTheRateSynced() {
        val absolute = sweep(lfoStage("cutoff" to 1000.0, "lpdepthfrequency" to 300.0, "lprate" to 2.0), SR, 128)
        assertEquals(1150.0f, absolute.max(), 5.0f)
        assertEquals(850.0f, absolute.min(), 5.0f)
        // lpsync 4 at 0.5 cps is 2 Hz: the same signal as lprate 2
        val rate = sweep(lfoStage("cutoff" to 1000.0, "lpdepth" to 1.0, "lprate" to 2.0), SR, 128)
        val sync = sweep(lfoStage("cutoff" to 1000.0, "lpdepth" to 1.0, "lpsync" to 4.0), SR, 128)
        assertContentEquals(rate, sync)
        // the rate defaults to cps (here 2 Hz as well)
        val defaultRate = sweep(lfoStage("cutoff" to 1000.0, "lpdepth" to 1.0, cps = 2.0), SR, 128)
        assertContentEquals(rate, defaultRate)
    }

    @Test
    fun anLfoWithoutDepthSwingsByTheCutoffAndADepthOfZeroMakesNone() {
        val shaped = sweep(lfoStage("cutoff" to 800.0, "lprate" to 3.0), SR, 128)
        assertEquals(1200.0f, shaped.max(), 8.0f, "depth 1 of 800 Hz is +-400 Hz")
        val none = lfoStage("cutoff" to 800.0, "lpdepth" to 0.0, "lprate" to 3.0)
        assertFalse(none.frequency.hasInputs)
        assertTrue(none.frequency.isConstant)
    }

    @Test
    fun theLfoShapeIsReadAsANumberOrAName() {
        val square = sweep(lfoStage("cutoff" to 1000.0, "lpdepth" to 1.0, "lprate" to 5.0, "lpshape" to "square"), SR, 128)
        assertEquals(setOf(500.0f, 1500.0f), square.toSet())
        val byNumber = sweep(lfoStage("cutoff" to 1000.0, "lpdepth" to 1.0, "lprate" to 5.0, "lpshape" to 4.0), SR, 128)
        assertContentEquals(square, byNumber)
        val tri = sweep(lfoStage("cutoff" to 1000.0, "lpdepth" to 1.0, "lprate" to 5.0), SR, 128)
        assertContentEquals(tri, sweep(lfoStage("cutoff" to 1000.0, "lpdepth" to 1.0, "lprate" to 5.0, "lpshape" to "tri"), SR, 128))
        assertContentEquals(tri, sweep(lfoStage("cutoff" to 1000.0, "lpdepth" to 1.0, "lprate" to 5.0, "lpshape" to 5.0), SR, 128), "a number is taken modulo 5")
        // skew moves the peak of the triangle; dc offset the middle
        val skewed = sweep(lfoStage("cutoff" to 1000.0, "lpdepth" to 1.0, "lprate" to 5.0, "lpskew" to 0.25, "lpdc" to -0.25), SR, 128)
        assertEquals(1750.0f, skewed.max(), 12.0f)
        assertEquals(750.0f, skewed.min(), 12.0f)
    }

    @Test
    fun theLfoAddsToTheEnvelope() {
        val s = lfoStage("cutoff" to 300.0, "lpenv" to 3.0, "lpattack" to 0.05, "lpdepth" to 0.5, "lprate" to 4.0)
        val values = sweep(s, 24000, 128)
        // at the top of the attack the envelope is at 2400 Hz and the LFO adds up to +-150 Hz of 300
        val around = values[(0.05 * SR).toInt() - 128]
        assertTrue(around in 2200f..2700f, "cutoff at the top of the attack $around")
    }

    @Test
    fun theLfoIsClampedSoTheCutoffStaysAbove30Hz() {
        // depth 5 on 100 Hz would swing to -400 Hz: the LFO's minimum is -f + 30
        val s = lfoStage("cutoff" to 100.0, "lpdepth" to 5.0, "lprate" to 3.0)
        val values = sweep(s, SR, 128)
        assertTrue(values.min() >= 29.9f, "lowest cutoff ${values.min()}")
    }

    // ---------------------------------------------------------------------------------------------- registration

    @Test
    fun theStageRegistersItsFrequencyAndQForModulators() {
        for ((type, keys, node, cutoff) in listOf(
            Quad(BiquadType.LOWPASS, FilterKeys.LOWPASS, "lpf", "cutoff"),
            Quad(BiquadType.HIGHPASS, FilterKeys.HIGHPASS, "hpf", "hcutoff"),
            Quad(BiquadType.BANDPASS, FilterKeys.BANDPASS, "bpf", "bandf"),
        )) {
            val s = stage(cutoff to 500.0, type = type, keys = keys)
            val reg = NodeRegistry()
            s.registerParams(reg)
            assertSame(s.frequency, reg.find(node, "frequency"))
            assertSame(s.q, reg.find(node, "Q"))
            assertNull(reg.find("${node}_lfo", "depth"), "no LFO, no lfo node")
        }
        val withLfo = stage("cutoff" to 500.0, "lpdepth" to 0.3)
        val reg = NodeRegistry()
        withLfo.registerParams(reg)
        assertNotNull(reg.find("lpf_lfo", "depth"))
        assertNotNull(reg.find("lpf_lfo", "skew"))
        assertNotNull(reg.find("lpf_lfo", "dcoffset"))
        assertNull(reg.find("lpf_lfo", "rate"), "superdough's lprate targets a parameter the worklet does not have")
    }

    private data class Quad(val type: BiquadType, val keys: FilterKeys, val node: String, val cutoff: String)

    @Test
    fun aModulatorConnectedToTheFrequencyMovesTheCutoff() {
        val s = stage("cutoff" to 1000.0)
        // a constant +200 Hz connected to the parameter, as an LFO or envelope would
        s.frequency.addInput { out, ctx -> for (i in 0 until ctx.n) out[i] += 200f }
        val ctx = BlockContext(SR)
        ctx.startFrame = 0
        ctx.n = 4
        val values = FloatArray(4)
        s.frequency.fill(values, ctx)
        assertEquals(1200f, values[0])
        val input = noise(3000, 7)
        val moved = runStage(s, listOf(input))[0]
        val reference = runStage(stage("cutoff" to 1200.0), listOf(input))[0]
        assertContentEquals(reference, moved)
    }

    // --------------------------------------------------------------------------------- 24 dB across effect chains

    @Test
    fun theTwentyFourDbTestUsesTheMainValuesFtypeForEveryChain() {
        val input = noise(3000, 21)
        val timing = timingOf()
        // main: ftype 24db and no filter of its own; the FX element has a plain cutoff: it still gets the pair
        val mainOnly = FilterStage(SR, BiquadType.LOWPASS, FilterKeys.LOWPASS)
        val element = FilterStage(SR, BiquadType.LOWPASS, FilterKeys.LOWPASS)
        assertTrue(element.configure(controlsOf("cutoff" to 800.0), timing, false))
        assertFalse(mainOnly.configure(controlsOf("ftype" to "24db"), timing, true))
        val pair = runStage(element, listOf(input))[0]
        val reference = runStage(stage("cutoff" to 800.0, "ftype" to "24db"), listOf(input))[0]
        assertContentEquals(reference, pair)

        // the element's own ftype only matters for the ladder test
        val timing2 = timingOf()
        val element2 = FilterStage(SR, BiquadType.LOWPASS, FilterKeys.LOWPASS)
        assertTrue(element2.configure(controlsOf("cutoff" to 800.0, "ftype" to "24db"), timing2, false))
        FilterStage(SR, BiquadType.LOWPASS, FilterKeys.LOWPASS).configure(controlsOf("s" to "x"), timing2, true)
        val single = runStage(element2, listOf(input))[0]
        assertContentEquals(runStage(stage("cutoff" to 800.0), listOf(input))[0], single)
    }

    @Test
    fun aLadderInAnFxChainMakesTwoLaddersWhenTheMainValueAsksForTwentyFourDb() {
        val input = noise(3000, 22)
        val timing = timingOf()
        val element = FilterStage(SR, BiquadType.LOWPASS, FilterKeys.LOWPASS)
        assertTrue(element.configure(controlsOf("cutoff" to 800.0, "ftype" to "ladder"), timing, false))
        FilterStage(SR, BiquadType.LOWPASS, FilterKeys.LOWPASS).configure(controlsOf("ftype" to "24db"), timing, true)
        val actual = runStage(element, listOf(input))[0]
        val expected = input.copyOf()
        val first = LadderFilter(SR)
        val second = LadderFilter(SR)
        var from = 0
        while (from < expected.size) {
            val to = Math.min(expected.size, from + QUANTUM)
            first.process(expected, null, from, to, 800.0, 1.0, 0.69)
            second.process(expected, null, from, to, 800.0, 1.0, 0.69)
            from = to
        }
        assertContentEquals(expected, actual)
    }

    // ------------------------------------------------------------------------------------ through a voice and a mixer

    @Test
    fun aVoiceWithACutoffLosesItsHighs() {
        val dry = renderLeft(synthVp("sawtooth", 0.5, "note" to 45.0), SR)
        val wet = renderLeft(synthVp("sawtooth", 0.5, "note" to 45.0, "cutoff" to 400.0), SR)
        val highDry = 1.0 - energyBelow(dry, 2000.0)
        val highWet = 1.0 - energyBelow(wet, 2000.0)
        assertTrue(highWet < 0.2 * highDry, "the share of the energy above 2 kHz falls from $highDry to $highWet")
    }

    @Test
    fun aVoiceWithACutoffOfZeroIsSilentAndAVoiceWithAnEnormousOneIsUnchanged() {
        val silent = renderLeft(synthVp("sawtooth", 0.5, "note" to 45.0, "cutoff" to 0.0), SR / 2)
        assertEquals(0f, peak(silent))
        val dry = renderLeft(synthVp("sawtooth", 0.5, "note" to 45.0), SR / 2)
        val open = renderLeft(synthVp("sawtooth", 0.5, "note" to 45.0, "cutoff" to 1e9), SR / 2)
        assertContentEquals(dry, open)
    }

    @Test
    fun anFxElementsFilterBecomesAPairWhenTheEventsFtypeIsTwentyFourDb() {
        /** The RMS of what a voice leaves above 3 kHz (two Butterworth high-passes in series), the part a second 600 Hz low-pass takes much more from. */
        fun highRms(ftype: Any?): Double {
            val controls = ArrayList<Pair<String, Any?>>()
            controls.add("note" to 45.0)
            controls.add("FX" to listOf(mapOf("cutoff" to 600.0)))
            if (ftype != null) controls.add("ftype" to ftype)
            val out = renderLeft(synthVp("sawtooth", 0.5, *controls.toTypedArray()), SR)
            val first = biquad(BiquadType.HIGHPASS, 3000.0, -3.0103)
            val second = biquad(BiquadType.HIGHPASS, 3000.0, -3.0103)
            first.process(out, out.size, 0)
            second.process(out, out.size, 0)
            return rms(out, 4800, 24000)
        }
        val single = highRms(null)
        val pair = highRms("24db")
        assertTrue(pair < single * 0.3, "what is left above 3 kHz falls from $single to $pair with the second filter")
        assertEquals(single, highRms("12db"), 1e-12)
    }

    @Test
    fun everyFilterOfAVoiceTogetherStaysFiniteAndAudible() {
        val params = synthVp(
            "sawtooth", 0.4, "note" to 50.0, "cutoff" to 1500.0, "lpenv" to 2.0, "hcutoff" to 200.0, "hpdepth" to 0.5, "hprate" to 5.0,
            "bandf" to 900.0, "bandq" to 2.0, "vowel" to "a", "ftype" to "24db",
        )
        val out = renderLeft(params, SR)
        assertTrue(allFinite(out))
        assertTrue(peak(out) > 0.001f)
        val ladder = renderLeft(synthVp("sawtooth", 0.4, "note" to 50.0, "cutoff" to 1500.0, "lpenv" to 3.0, "ftype" to "ladder", "lpdepth" to 0.5, "lprate" to 6.0), SR)
        assertTrue(allFinite(ladder))
        assertTrue(peak(ladder) > 0.001f)
    }

    // --------------------------------------------------------------------------------------------------------- vowel

    @Test
    fun theVowelFilterBoostsItsFormantByTheMakeupGainOfEight() {
        val s = VowelStage(SR)
        assertTrue(s.configure(controlsOf("vowel" to "a"), timingOf(), true))
        val input = sineWave(660.0, 1.0, 0.1)
        val output = runStage(s, listOf(input))[0]
        val ratio = rms(output, 24000, 48000) / rms(input, 24000, 48000)
        assertEquals(8.0, ratio, 8.0 * 0.03)
    }

    @Test
    fun aVowelStageIsInTheChainOnlyWhenAVowelIsSet() {
        assertFalse(VowelStage(SR).configure(controlsOf("cutoff" to 500.0), timingOf(), true))
        assertTrue(VowelStage(SR).configure(controlsOf("vowel" to "ö"), timingOf(), true), "aliases work")
    }

    @Test
    fun anUnknownVowelFailsTheEventWithAWarning() {
        val messages = ArrayList<String>()
        val previous = logger
        logger = { message, type, _ -> messages.add("$type: $message") }
        try {
            val s = VowelStage(SR)
            assertTrue(s.configure(controlsOf("vowel" to "zz"), timingOf(), true))
            val output = runStage(s, listOf(noise(1000, 4), noise(1000, 5)))
            assertEquals(0f, peak(output[0]))
            assertEquals(0f, peak(output[1]))
            assertEquals(listOf("warning: vowel: unknown vowel zz"), messages)
            // the stage works again for the next voice
            s.reset()
            assertTrue(s.configure(controlsOf("vowel" to "e"), timingOf(), true))
            assertTrue(peak(runStage(s, listOf(noise(1000, 4)))[0]) > 0.01f)
        } finally {
            logger = previous
        }
    }

    @Test
    fun theVowelStageFiltersBothChannelsOfAStereoBlock() {
        val left = noise(2000, 31)
        val right = noise(2000, 32)
        val s = VowelStage(SR)
        s.configure(controlsOf("vowel" to "i"), timingOf(), true)
        val stereo = runStage(s, listOf(left, right))
        val a = VowelStage(SR).also { it.configure(controlsOf("vowel" to "i"), timingOf(), true) }
        val b = VowelStage(SR).also { it.configure(controlsOf("vowel" to "i"), timingOf(), true) }
        assertContentEquals(runStage(a, listOf(left))[0], stereo[0])
        assertContentEquals(runStage(b, listOf(right))[0], stereo[1])
    }

    @Test
    fun aVoiceWithAnUnknownVowelIsSilent() {
        val previous = logger
        logger = { _, _, _ -> }
        try {
            val out = renderLeft(synthVp("sawtooth", 0.3, "note" to 50.0, "vowel" to "zz"), SR / 2)
            assertEquals(0f, peak(out))
            val vowel = renderLeft(synthVp("sawtooth", 0.3, "note" to 50.0, "vowel" to "a"), SR / 2)
            assertTrue(peak(vowel) > 0.01f)
        } finally {
            logger = previous
        }
    }
}
