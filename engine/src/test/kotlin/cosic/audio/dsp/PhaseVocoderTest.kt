package cosic.audio.dsp

import cosic.audio.Fft
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** The `phase-vocoder-processor` worklet against the real processor's output (worklets.mjs:580-748, ola-processor.js, fft.js). */
class PhaseVocoderTest {
    private val golden = DspGolden.load("phasevocoder.json")
    private val cases = DspGolden.cases(golden["cases"] as JsonArray, signals = golden["signals"] as JsonObject)

    private fun stretchOf(case: StreamCase, q: Int) = case.param(q, "pitchFactor")

    private fun runQuanta(case: StreamCase): Array<FloatArray> {
        val input = case.input
        val out = Array(case.channels) { FloatArray(case.frames()) }
        val vocoder = PhaseVocoder()
        val inL = FloatArray(128)
        val inR = FloatArray(128)
        val outL = FloatArray(128)
        val outR = FloatArray(128)
        for (q in 0 until case.quanta) {
            input[0].copyInto(inL, 0, q * 128, q * 128 + 128)
            if (case.channels == 2) input[1].copyInto(inR, 0, q * 128, q * 128 + 128)
            vocoder.processQuantum(inL, if (case.channels == 2) inR else null, outL, if (case.channels == 2) outR else null, stretchOf(case, q))
            outL.copyInto(out[0], q * 128)
            if (case.channels == 2) outR.copyInto(out[1], q * 128)
        }
        return out
    }

    @Test
    fun everyGoldenCaseMatchesTheWorkletQuantumByQuantum() {
        for (case in cases) {
            val out = runQuanta(case)
            for (ch in 0 until case.channels) assertClose("${case.name} ch$ch", case.output[ch], out[ch], atol = 1e-6, rtol = 1e-6)
        }
    }

    /** The streaming form with calls of [block] frames (the last one shorter), the quanta aligned to frame 0. */
    private fun runStreaming(case: StreamCase, block: Int, startFrame: Long = 0L): Array<FloatArray> {
        val data = case.copyOfInput()
        val vocoder = PhaseVocoder()
        // the stretch of the call that completes a quantum is the one used, so give each call the value of the quantum it ends in
        var from = 0
        while (from < case.frames()) {
            val to = minOf(case.frames(), from + block)
            val q = (to - 1) / 128
            vocoder.process(data[0], data.getOrNull(1), from, to, startFrame, stretchOf(case, q))
            from = to
        }
        return data
    }

    @Test
    fun streamingGivesTheWorkletOutputOneQuantumLaterWhateverTheCallSizes() {
        val monoCases = cases.filter { it.channels == 1 }
        for (case in monoCases) {
            // A call is processed with its own stretch value: with a moving stretch only calls that do not mix quanta of different values (64, 128, 256 here) can match the golden.
            val constant = (0 until case.quanta).all { stretchOf(case, it) == stretchOf(case, 0) }
            for (block in intArrayOf(128, 256, 64, 100, 1)) {
                if (!constant && block != 128 && block != 256 && block != 64) continue
                if (block == 1 && case !== monoCases[0]) continue
                val out = runStreaming(case, block)
                for (i in 0 until 128) assertEquals(0f, out[0][i], "${case.name} block $block: nothing before the first quantum is done")
                assertClose("${case.name} block $block", case.output[0], out[0].copyOfRange(128, case.frames()) + FloatArray(128), atol = 1e-6, rtol = 1e-6, from = 0, to = case.frames() - 128)
            }
        }
        // The stereo case changes its stretch at quanta 12 and 20: calls of two quanta keep each quantum with the value of its own call.
        val stereo = cases.first { it.channels == 2 }
        val out = runStreaming(stereo, 256)
        for (ch in 0 until 2) {
            assertClose("${stereo.name} ch$ch", stereo.output[ch], out[ch].copyOfRange(128, stereo.frames()) + FloatArray(128), atol = 1e-6, rtol = 1e-6, from = 0, to = stereo.frames() - 128)
        }
    }

    @Test
    fun aStreamThatStartsInTheMiddleOfAQuantumTreatsTheMissingStartAsSilence() {
        // Start at absolute frame 1000 = quantum 7, position 104: the quantum grid is the engine's, and what precedes the first sample is zero.
        val sine = FloatArray(128 * 40) { (0.5 * sin(2 * PI * 1500 * it / 48000)).toFloat() }
        val shifted = FloatArray(sine.size)
        val offset = 1000 % 128
        for (i in offset until sine.size) shifted[i] = sine[i - offset]
        val reference = PhaseVocoder()
        val expected = FloatArray(sine.size)
        val inQ = FloatArray(128)
        val outQ = FloatArray(128)
        for (q in 0 until 40) {
            for (i in 0 until 128) inQ[i] = shifted[q * 128 + i]
            reference.processQuantum(inQ, null, outQ, null, 1.0)
            outQ.copyInto(expected, q * 128)
        }
        val streamed = sine.copyOf()
        PhaseVocoder().process(streamed, null, 0, sine.size - offset, 1000L, 1.0)
        // the streamed array holds frames 1000 .. 1000 + size - offset; the reference array, the same frames starting at position `offset` of quantum 7... compare by frame
        for (i in 0 until sine.size - offset - 128) {
            val frame = offset + i // the position of streamed[i] in the reference's own quantum grid (it starts at 0 = absolute 896)
            assertEquals(expected[frame].toDouble(), streamed[i + 128].toDouble(), 2e-6 + 2e-6 * abs(expected[frame]), "frame ${1000 + i}")
        }
    }

    @Test
    fun aGapInTheCallsRestartsTheProcessor() {
        val sine = FloatArray(1024) { (0.5 * sin(2 * PI * 1500 * it / 48000)).toFloat() }
        val vocoder = PhaseVocoder()
        vocoder.process(sine.copyOf(), null, 0, 1024, 0L, 0.0)
        val again = sine.copyOf()
        vocoder.process(again, null, 0, 1024, 4096L, 0.0) // not frame 1024: a new stream
        val fresh = sine.copyOf()
        PhaseVocoder().process(fresh, null, 0, 1024, 4096L, 0.0)
        assertContentEquals(fresh, again)
    }

    @Test
    fun resetReturnsToAFreshProcessor() {
        val sine = FloatArray(2048) { (0.5 * sin(2 * PI * 700 * it / 48000)).toFloat() }
        val vocoder = PhaseVocoder()
        val first = sine.copyOf()
        vocoder.process(first, null, 0, 2048, 0L, 0.5)
        vocoder.reset()
        val second = sine.copyOf()
        vocoder.process(second, null, 0, 2048, 0L, 0.5)
        assertContentEquals(first, second)
    }

    // ------------------------------------------------------------------ what the effect does to a tone

    private fun tone(frames: Int, freq: Double, amp: Double = 0.5) = FloatArray(frames) { (amp * sin(2 * PI * freq * it / 48000)).toFloat() }

    private fun render(input: FloatArray, stretch: Double): FloatArray {
        val out = input.copyOf()
        val vocoder = PhaseVocoder()
        for (q in 0 until input.size / 128) vocoder.process(out, null, q * 128, (q + 1) * 128, 0L, stretch)
        return out
    }

    /** The dominant frequency of the last 8192 samples, from a zero-padded Hann-windowed FFT and a parabolic peak. */
    private fun dominant(signal: FloatArray): Double {
        val n = 8192
        val size = 32768
        val re = DoubleArray(size)
        val im = DoubleArray(size)
        for (i in 0 until n) re[i] = signal[signal.size - n + i] * (0.5 - 0.5 * Math.cos(2 * Math.PI * i / n))
        Fft(size).forward(re, im)
        var best = 1
        var bestMag = 0.0
        for (k in 1 until size / 2) {
            val m = Math.hypot(re[k], im[k])
            if (m > bestMag) {
                bestMag = m
                best = k
            }
        }
        val a = Math.hypot(re[best - 1], im[best - 1])
        val c = Math.hypot(re[best + 1], im[best + 1])
        val delta = 0.5 * (a - c) / (a - 2 * bestMag + c)
        return (best + delta) * 48000.0 / size
    }

    @Test
    fun withoutStretchTheToneKeepsItsPitchWithTheWorkletsLatencyAndGain() {
        val input = tone(128 * 100, 1500.0)
        val out = render(input, 0.0)
        // delayed by 1920 frames plus one quantum (the streaming form), at 0.984 times the amplitude
        val delay = PhaseVocoder.STREAM_LATENCY_FRAMES
        var worst = 0.0
        for (i in 6000 until 12000) worst = maxOf(worst, abs(out[i] - input[i - delay]).toDouble())
        assertTrue(worst < 0.5 * 0.03, "output is the input delayed by $delay frames: worst difference $worst")
        val peak = (6000 until 12000).maxOf { abs(out[it]) }
        assertEquals(0.492, peak.toDouble(), 0.01, "amplitude 1.62^2 * 6 / 16 = 0.984 of 0.5")
        assertEquals(1500.0, dominant(out), 5.0)
        assertEquals(1920 + 128, PhaseVocoder.STREAM_LATENCY_FRAMES)
        assertEquals(1920, PhaseVocoder.LATENCY_FRAMES)
    }

    @Test
    fun stretchOneIsAnOctaveUpAndMinusTwoAnOctaveDown() {
        val input = tone(128 * 100, 1500.0)
        assertEquals(3000.0, dominant(render(input, 1.0)), 5.0, "pf = 2")
        assertEquals(750.0, dominant(render(input, -2.0)), 5.0, "pf = max(0, -2 * 0.25 + 1) = 0.5")
        assertEquals(2250.0, dominant(render(input, 0.5)), 6.0, "pf = 1.5")
    }

    @Test
    fun theQuantumExactFormDelaysByTheWorkletsLatencyAndTheStreamingFormByOneQuantumMore() {
        val input = tone(128 * 60, 1500.0)
        val out = FloatArray(input.size)
        val vocoder = PhaseVocoder()
        val inQ = FloatArray(128)
        val outQ = FloatArray(128)
        for (q in 0 until 60) {
            input.copyInto(inQ, 0, q * 128, q * 128 + 128)
            vocoder.processQuantum(inQ, null, outQ, null, 0.0)
            outQ.copyInto(out, q * 128)
        }
        // a pure tone has a single spectral peak, so the vocoder is a delay of 1920 frames with the gain 1.62^2 * 6 / 16 = 0.984
        for (i in 4000 until 7000) assertEquals(0.984 * input[i - PhaseVocoder.LATENCY_FRAMES], out[i].toDouble(), 1.5e-2, "frame $i")
        // one frame of misalignment would be a difference of 0.5 * 2 pi 1500 / 48000 = 0.098
        val wrongByOne = (4000 until 7000).maxOf { abs(0.984 * input[it - PhaseVocoder.LATENCY_FRAMES - 1] - out[it]) }
        assertTrue(wrongByOne > 0.05, "a delay of 1921 frames would not fit: $wrongByOne")
    }

    @Test
    fun nonFiniteInputIsTakenAsSilence() {
        val clean = tone(128 * 40, 1500.0)
        val dirty = clean.copyOf()
        dirty[700] = Float.NaN
        dirty[900] = Float.POSITIVE_INFINITY
        val zeroed = clean.copyOf()
        zeroed[700] = 0f
        zeroed[900] = 0f
        val a = render(dirty, 1.0)
        val b = render(zeroed, 1.0)
        assertContentEquals(b, a)
    }

    @Test
    fun stereoChannelsAreProcessedIndependently() {
        val left = tone(128 * 100, 1500.0)
        val right = tone(128 * 100, 937.5, 0.3) // bin 40 of 2048
        val mono = render(left, 1.0)
        val l = left.copyOf()
        val r = right.copyOf()
        val vocoder = PhaseVocoder()
        for (q in 0 until 100) vocoder.process(l, r, q * 128, (q + 1) * 128, 0L, 1.0)
        assertContentEquals(mono, l)
        assertEquals(1875.0, dominant(r), 5.0)
    }
}
