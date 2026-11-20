package cosic.audio.sources

import cosic.WavetableFrames
import cosic.audio.Mixer
import cosic.audio.SR
import cosic.audio.VoiceSource
import cosic.audio.channel
import cosic.audio.estimateFrequency
import cosic.audio.peak
import cosic.audio.renderFrames
import cosic.audio.vp
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WavetableSourceTest {
    private val sineFrame = FloatArray(2048) { Math.sin(2 * Math.PI * it / 2048).toFloat() }
    private val sine = WavetableFrames(listOf(sineFrame), 2048, 44100)

    /** Two constant frames, +1 then -1: the sign of the output says where in the table the position is. */
    private val plusMinus = WavetableFrames(listOf(FloatArray(8) { 1f }, FloatArray(8) { -1f }), 8, 44100)

    private fun source(frames: WavetableFrames, duration: Double, vararg controls: Pair<String, Any?>, cps: Double = 0.5, seed: Long = 1, pitch: PitchModulator? = null) =
        WavetableSource(SourceSpec(SR, mapOf(*controls).asSourceControls(), duration, cps, seed), frames, pitch)

    private fun WavetableSource.renderAll(frames: Int, startFrame: Long = 0): Pair<FloatArray, FloatArray> {
        start(startFrame)
        val l = FloatArray(frames)
        val r = FloatArray(frames)
        var done = 0
        val tl = FloatArray(1000)
        val tr = FloatArray(1000)
        while (done < frames) {
            val n = minOf(1000, frames - done)
            val produced = render(tl, tr, n)
            System.arraycopy(tl, 0, l, done, produced)
            System.arraycopy(tr, 0, r, done, produced)
            if (produced < n) break
            done += n
        }
        return l to r
    }

    @Test
    fun aSingleFrameSineTableSoundsAtTheNote() {
        val (l, r) = source(sine, 0.5, "note" to 69.0).renderAll(SR / 2)
        assertEquals(440.0, estimateFrequency(l, 2000, 20000), 0.5)
        // the envelope peaks at 0.3 and the single voice is centred (sqrt(.5) a side)
        val p = peak(l, 2000, 20000)
        assertEquals(0.3 * sqrt(0.5), p.toDouble(), 0.005)
        assertTrue(p < 0.3f)
        for (i in 0 until l.size) assertEquals(l[i], r[i])
    }

    @Test
    fun theFrequencyIsTheNoteOrFreqTimesTheOctave() {
        assertEquals(220.0, estimateFrequency(source(sine, 0.5, "note" to 57.0).renderAll(SR / 2).first, 2000, 20000), 0.5)
        assertEquals(300.0, estimateFrequency(source(sine, 0.5, "freq" to 300.0, "note" to 57.0).renderAll(SR / 2).first, 2000, 20000), 0.5)
        assertEquals(600.0, estimateFrequency(source(sine, 0.5, "freq" to 300.0, "octave" to 1.0).renderAll(SR / 2).first, 2000, 20000), 1.0)
        // a source with no note plays the default synth note, 36
        assertEquals(65.406, estimateFrequency(source(sine, 0.5).renderAll(SR / 2).first, 2000, 20000), 0.5)
    }

    @Test
    fun theSourceEndsTenMillisecondsAfterTheReleaseAndStaysSilentBeforeItsStart() {
        val src = source(sine, 0.2, "note" to 69.0, "release" to 0.1)
        val (l, _) = src.renderAll(SR, startFrame = 4800)
        assertEquals(0.1 + 0.2 + 0.1 + 0.01, src.endSeconds, 1e-9)
        assertTrue(src.isFinished)
        // the source has produced its frames up to its end only: the render quantum that contains it
        val lastNonZero = l.indexOfLast { it != 0f }
        assertTrue(lastNonZero < ((0.41 * SR / 128).toInt() + 1) * 128 - 4800, "ended at frame $lastNonZero")
        assertTrue(lastNonZero > (0.3 * SR).toInt() - 4800, "rings out through the release")
    }

    @Test
    fun renderReturnsFewerFramesWhenTheSourceFinishes() {
        val src = source(sine, 0.05, "note" to 69.0)
        src.start(0)
        val l = FloatArray(4096)
        val r = FloatArray(4096)
        var total = 0
        while (!src.isFinished) {
            val n = src.render(l, r, 4096)
            total += n
            if (n < 4096) break
        }
        assertTrue(src.isFinished)
        assertEquals(0, total % 128)
        assertEquals(0, src.render(l, r, 100))
    }

    @Test
    fun clipShortensTheHoldAgainOnTopOfTheDurationThatAlreadyHasIt() {
        val full = source(sine, 0.4, "note" to 69.0).apply { start(0) }
        val clipped = source(sine, 0.4, "note" to 69.0, "clip" to 0.5).apply { start(0) }
        assertEquals(0.4 + 0.01 + 0.01, full.endSeconds, 1e-9)
        assertEquals(0.2 + 0.01 + 0.01, clipped.endSeconds, 1e-9)
    }

    @Test
    fun theEnvelopeFollowsTheAdsrControlsWithTheirDefaults() {
        val (l, _) = source(sine, 0.5, "note" to 69.0, "attack" to 0.2, "sustain" to 0.5, "decay" to 0.1, "release" to 0.1).renderAll(SR)
        fun level(from: Double) = peak(l, (from * SR).toInt(), ((from + 0.01) * SR).toInt()).toDouble() / sqrt(0.5)
        assertEquals(0.3 * 0.5, level(0.1), 0.02)
        assertEquals(0.3, level(0.195), 0.02)
        assertEquals(0.3 * 0.5, level(0.4), 0.01)
        assertEquals(0.0, level(0.62), 0.001)
    }

    @Test
    fun theEnvelopeOfWtSweepsThePositionThroughTheTable() {
        val (l, _) = source(plusMinus, 1.0, "note" to 69.0, "wtenv" to 1.0, "wtattack" to 0.3, "wtsustain" to 1.0, "wtdecay" to 0.001).renderAll(SR)
        fun at(t: Double) = l[(t * SR).toInt()].toDouble()
        assertTrue(at(0.05) > 0.01, "near the first frame")
        assertTrue(at(0.15) in -0.01..0.01 || Math.abs(at(0.15)) < 0.05, "in the middle the frames cancel (${at(0.15)})")
        assertTrue(at(0.25) < -0.01, "near the last frame")
        assertTrue(at(0.5) < -0.1, "held at the last frame")
    }

    @Test
    fun wtAloneSetsAFixedPosition() {
        val (l, _) = source(plusMinus, 0.5, "note" to 69.0, "wt" to 0.25).renderAll(SR / 2)
        val level = 0.3 * sqrt(0.5)
        assertEquals(0.5 * level, l[(0.2 * SR).toInt()].toDouble(), 0.002)
        val (l2, _) = source(plusMinus, 0.5, "note" to 69.0, "wt" to 1.0).renderAll(SR / 2)
        assertEquals(-level, l2[(0.2 * SR).toInt()].toDouble(), 0.002)
    }

    @Test
    fun anLfoOnThePositionMovesItAroundTheOffset() {
        // position = 0.5 + 0.5 * (wave - 0.5): from 0.25 to 0.75 four times a second
        val (l, _) = source(plusMinus, 1.0, "note" to 69.0, "wt" to 0.5, "wtrate" to 4.0, "wtdepth" to 0.5, "wtdc" to -0.5).renderAll(SR)
        val window = l.copyOfRange(SR / 10, SR)
        assertTrue(window.max() > 0.1f, "reaches the first frame's side (${window.max()})")
        assertTrue(window.min() < -0.1f, "reaches the second frame's side (${window.min()})")
        // no wtdepth and no rate or shape or skew: no LFO
        val (still, _) = source(plusMinus, 1.0, "note" to 69.0, "wt" to 0.5).renderAll(SR)
        assertEquals(0f, peak(still, SR / 10, SR))
        // an LFO control without a depth uses the default depth 0.5
        val (defaulted, _) = source(plusMinus, 1.0, "note" to 69.0, "wt" to 0.5, "wtrate" to 4.0, "wtdc" to -0.5).renderAll(SR)
        for (i in l.indices) assertEquals(l[i], defaulted[i])
    }

    @Test
    fun wtsyncTiesTheLfoRateToTheTempo() {
        val a = source(plusMinus, 1.0, "note" to 69.0, "wt" to 0.5, "wtsync" to 2.0, "wtdc" to -0.5, cps = 1.0).renderAll(SR).first
        val b = source(plusMinus, 1.0, "note" to 69.0, "wt" to 0.5, "wtrate" to 2.0, "wtdc" to -0.5, cps = 1.0).renderAll(SR).first
        for (i in a.indices) assertEquals(a[i], b[i])
    }

    @Test
    fun warpAndItsModeShapeTheWave() {
        val ramp = WavetableFrames(listOf(FloatArray(64) { it / 64f }), 64, 44100)
        val plain = source(ramp, 0.2, "note" to 57.0).renderAll(SR / 5).first
        val warped = source(ramp, 0.2, "note" to 57.0, "warp" to 0.8, "warpmode" to "bendp").renderAll(SR / 5).first
        var diff = 0.0
        for (i in plain.indices) diff += Math.abs(plain[i] - warped[i])
        assertTrue(diff > 1.0, "bending the phase changes the wave ($diff)")
        // an unknown mode name warps nothing
        val unknown = source(ramp, 0.2, "note" to 57.0, "warp" to 0.8, "warpmode" to "nope").renderAll(SR / 5).first
        for (i in plain.indices) assertEquals(plain[i], unknown[i])
    }

    @Test
    fun unisonSpreadsTheVoicesAcrossTheStereoFieldAndRandomisesTheirPhases() {
        val (l, r) = source(sine, 0.3, "note" to 57.0, "unison" to 4.0, "spread" to 1.0, "detune" to 0.5).renderAll(SR / 3)
        var diff = 0.0
        for (i in l.indices) diff += Math.abs(l[i] - r[i])
        assertTrue(diff > 1.0, "the channels differ ($diff)")
        // the phases are random by default with unison, and the same seed gives the same sound
        val a = source(sine, 0.3, "note" to 57.0, "unison" to 4.0, seed = 5).renderAll(2000).first
        val b = source(sine, 0.3, "note" to 57.0, "unison" to 4.0, seed = 5).renderAll(2000).first
        val c = source(sine, 0.3, "note" to 57.0, "unison" to 4.0, seed = 6).renderAll(2000).first
        for (i in a.indices) assertEquals(a[i], b[i])
        assertFalse(a.contentEquals(c))
        // wtphaserand 0 starts them all in phase: a start at 0 for a sine table
        val inPhase = source(sine, 0.3, "note" to 57.0, "unison" to 4.0, "wtphaserand" to 0.0, seed = 5).renderAll(2000).first
        assertEquals(0f, inPhase[0])
    }

    @Test
    fun aPitchModulatorBendsTheNote() {
        val octaveUp = object : PitchModulator {
            override fun add(bus: PitchBus, n: Int) {
                java.util.Arrays.fill(bus.detuneCents, 0, n, 1200.0)
                bus.detuneActive = true
            }
        }
        val (l, _) = source(sine, 0.5, "note" to 57.0, pitch = octaveUp).renderAll(SR / 2)
        assertEquals(440.0, estimateFrequency(l, 2000, 20000), 0.5)
    }

    @Test
    fun theSourceCanBeRestartedForTheNextNote() {
        val src = source(sine, 0.3, "note" to 69.0)
        val first = src.renderAll(2000).first
        val second = src.renderAll(2000, startFrame = 9600).first
        for (i in first.indices) assertEquals(first[i], second[i], 1e-6f)
    }

    @Test
    fun aVoiceInTheMixerPlaysTheTableThroughTheChain() {
        val controls = arrayOf<Pair<String, Any?>>("note" to 69.0, "pan" to 0.5)
        val params = vp(VoiceSource.Synth("wavetable", source(sine, 0.5, *controls)), 0.5, *controls)
        val mixer = Mixer(SR)
        assertTrue(mixer.startVoice(params))
        val out = mixer.renderFrames(SR / 2)
        val l = channel(out, 0)
        val r = channel(out, 1)
        assertEquals(440.0, estimateFrequency(l, 3000, 20000), 0.6)
        assertTrue(peak(l, 3000, 20000) > 0.05f)
        assertTrue(peak(r, 3000, 20000) > 0.05f)
        assertTrue(out.all { it.isFinite() })
    }

    @Test
    fun renderingDoesNotAllocate() {
        val src = source(sine, 5.0, "note" to 57.0, "unison" to 3.0, "wtenv" to 0.5, "wtrate" to 3.0, "warpenv" to 0.5, "warpmode" to "fold")
        src.start(0)
        val l = FloatArray(256)
        val r = FloatArray(256)
        src.render(l, r, 256)
        assertEquals(0L, steadyAllocation { src.render(l, r, 256) })
    }
}
