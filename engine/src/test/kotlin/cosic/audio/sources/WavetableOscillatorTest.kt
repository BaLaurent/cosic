package cosic.audio.sources

import cosic.audio.estimateFrequency
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WavetableOscillatorTest {
    private val sr = 48000

    private fun oscillator(frames: List<FloatArray>, voices: Int = 1, seed: Long = 1) = WavetableOscillator(sr, frames, SourceRng(seed), voices)

    private fun render(osc: WavetableOscillator, p: WavetableParams, n: Int, bus: PitchBus? = null): Pair<FloatArray, FloatArray> {
        val l = FloatArray(n)
        val r = FloatArray(n)
        osc.render(l, r, n, p, bus)
        return l to r
    }

    private fun params(configure: WavetableParams.() -> Unit) = WavetableParams().apply(configure)

    @Test
    fun rendersWhatTheWorkletRendersForTheOracleCases() {
        for (case in WavetableOracleVectors.blocks) {
            val osc = oscillator(WavetableOracleVectors.tableFrames, case.voices.toInt())
            val p = params {
                frequency = case.frequency
                freqspread = case.freqspread
                position = case.position
                warp = case.warp
                warpMode = case.warpMode
                voices = case.voices
                panspread = case.panspread
            }
            val bus = PitchBus(128)
            val left = FloatArray(384)
            val right = FloatArray(384)
            val l = FloatArray(128)
            val r = FloatArray(128)
            for (q in 0 until 3) {
                bus.clear(128)
                if (case.detune != 0.0) {
                    java.util.Arrays.fill(bus.detuneCents, 0, 128, case.detune)
                    bus.detuneActive = true
                }
                osc.render(l, r, 128, p, bus)
                System.arraycopy(l, 0, left, q * 128, 128)
                System.arraycopy(r, 0, right, q * 128, 128)
            }
            for (i in 0 until 384) {
                assertEquals(case.left[i], left[i].toDouble(), 2e-6, "${case.name} left[$i]")
                assertEquals(case.right[i], right[i].toDouble(), 2e-6, "${case.name} right[$i]")
            }
        }
    }

    @Test
    fun aFrameIsReadLinearlyAndWrapsFromTheLastValueToTheFirst() {
        val frame = floatArrayOf(0f, 1f, 0f, -1f)
        val centred = sqrt(0.5)
        for ((phase, expected) in listOf(0.125 to 0.5, 0.875 to -0.5, 0.25 to 1.0, 0.5 to 0.0)) {
            // frame 0 is read at phase 0, frame 1 at the phase one increment later
            val (l, _) = render(oscillator(listOf(frame)), params { frequency = phase * sr }, 2)
            assertEquals(expected * centred, l[1].toDouble(), 1e-6, "phase $phase")
        }
    }

    @Test
    fun positionBlendsNeighbouringFramesAndEndsOnTheLastOne() {
        val frames = listOf(0.2f, 0.6f, -0.4f).map { v -> FloatArray(4) { v } }
        val centred = sqrt(0.5)
        for ((position, expected) in listOf(0.0 to 0.2, 0.5 to 0.6, 0.25 to 0.4, 0.75 to 0.1, 1.0 to -0.4, 2.0 to -0.4, -1.0 to 0.2)) {
            val (l, _) = render(oscillator(frames), params { frequency = 0.0; this.position = position }, 4)
            assertEquals(expected * centred, l[3].toDouble(), 1e-6, "position $position")
        }
    }

    @Test
    fun positionAndWarpFollowTheirBuffersPerFrame() {
        val frames = listOf(1f, -1f).map { v -> FloatArray(4) { v } }
        val posBuf = FloatArray(4) { it / 3f }
        val (l, _) = render(oscillator(frames), params { frequency = 0.0; positionBuf = posBuf; position = 0.9 }, 4)
        for (i in 0 until 4) assertEquals((1.0 - 2.0 * posBuf[i]) * sqrt(0.5), l[i].toDouble(), 1e-6, "frame $i")
    }

    @Test
    fun aSingleVoiceIsCentredAndSeveralAlternateSides() {
        val frame = floatArrayOf(0.5f, -0.25f, 0.75f, 0.1f, -0.9f, 0.3f)
        val (l, r) = render(oscillator(listOf(frame)), params { frequency = 700.0; voices = 1.0; panspread = 1.0 }, 64)
        for (i in 0 until 64) assertEquals(l[i], r[i])
        // two voices with no detune spread and full pan spread: voice 0 goes left with gain sqrt(0), voice 1 right with sqrt(1)... both at 1/sqrt(2)
        val sine = FloatArray(64) { Math.sin(2 * Math.PI * it / 64).toFloat() }
        val (l2, r2) = render(oscillator(listOf(sine), 2), params { frequency = 300.0; voices = 2.0; panspread = 1.0; freqspread = 4.0 }, 400)
        var diff = 0.0
        for (i in 0 until 400) diff += Math.abs(l2[i] - r2[i])
        assertTrue(diff > 1.0, "the two voices are heard on different sides ($diff)")
    }

    @Test
    fun voicesStartAtRandomPhasesOnlyWithPhaserand() {
        val ramp = FloatArray(4) { it / 4f }
        var seed = 0L
        while (SourceRng(seed).nextDouble() >= 0.7) seed++
        val expected = SourceRng(seed).nextDouble()
        val (l, _) = render(oscillator(listOf(ramp), 1, seed), params { frequency = 0.0; phaserand = 1.0 }, 1)
        assertEquals(expected * sqrt(0.5), l[0].toDouble(), 1e-6)
        val (l0, _) = render(oscillator(listOf(ramp), 1, seed), params { frequency = 0.0; phaserand = 0.0 }, 1)
        assertEquals(0.0, l0[0].toDouble(), 0.0)
        val (l5, _) = render(oscillator(listOf(ramp), 1, seed), params { frequency = 0.0; phaserand = 0.5 }, 1)
        assertEquals(expected * 0.5 * sqrt(0.5), l5[0].toDouble(), 1e-6)
    }

    @Test
    fun flipNegatesTheSampleWhileThePhaseIsBelowTheWarpAmount() {
        val frame = FloatArray(8) { 1f }
        val osc = oscillator(listOf(frame))
        val p = params { frequency = 0.25 * sr; warp = 0.5; warpMode = WarpModes.FLIP }
        val (l, _) = render(osc, p, 4)
        // phases 0, .25, .5, .75: the first two are below .5
        assertEquals(listOf(-1, -1, 1, 1), l.map { Math.signum(it).toInt() })
    }

    @Test
    fun frequencyModulationAndDetuneBendThePitch() {
        val sine = FloatArray(2048) { Math.sin(2 * Math.PI * it / 2048).toFloat() }
        fun hz(configure: (PitchBus) -> Unit): Double {
            val osc = oscillator(listOf(sine))
            val bus = PitchBus(4800)
            bus.clear(4800)
            configure(bus)
            val (l, _) = render(osc, params { frequency = 440.0 }, 4800, bus)
            return estimateFrequency(l, 0, 4800, sr)
        }
        assertEquals(440.0, hz { }, 1.0)
        assertEquals(540.0, hz { b -> java.util.Arrays.fill(b.frequencyAddHz, 100.0); b.frequencyActive = true }, 1.5)
        assertEquals(880.0, hz { b -> java.util.Arrays.fill(b.detuneCents, 1200.0); b.detuneActive = true }, 2.0)
    }

    @Test
    fun renderingDoesNotAllocate() {
        val osc = oscillator(WavetableOracleVectors.tableFrames, 5)
        val p = params { frequency = 440.0; voices = 5.0; warpMode = 11.0; warp = 0.4; position = 0.3; phaserand = 1.0 }
        val bus = PitchBus(256)
        val l = FloatArray(256)
        val r = FloatArray(256)
        osc.render(l, r, 256, p, bus)
        assertEquals(0L, steadyAllocation { osc.render(l, r, 256, p, bus) })
    }
}
