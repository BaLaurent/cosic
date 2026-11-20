package cosic.audio.sources

/**
 * The controls of the wavetable oscillator for one block (the `AudioParam`s of `wavetable-oscillator-processor`, worklets.mjs:1161-1176).
 * [position] and [warp] are a-rate in the worklet: when [positionBuf] or [warpBuf] is set, it holds their value per frame (for the frames of the
 * block) and the scalar is ignored.
 */
internal class WavetableParams {
    /** The base frequency in Hz. [PitchBus.frequencyAddHz] (FM) is added to it per frame. */
    var frequency = 440.0

    /** The spread of the unison voices in semitones from the first to the last (the `detune` control). */
    var freqspread = 0.18

    /** The position in the table, 0 to 1 (clamped), when [positionBuf] is null. */
    var position = 0.0

    /** The warp amount, 0 to 1 (clamped), when [warpBuf] is null. */
    var warp = 0.0

    /** A [WarpModes] number: a value that is no mode warps nothing. */
    var warpMode = WarpModes.NONE

    /** The number of unison voices; a fraction counts the voices `0 until voices`, and 1 is the minimum the source gives. */
    var voices = 1.0

    /** The stereo spread of the voices, 0 to 1 (clamped); a single voice is centred. */
    var panspread = 0.7

    /** How much of a random start phase the voices get, 0 to 1 (clamped). */
    var phaserand = 0.0

    var positionBuf: FloatArray? = null
    var warpBuf: FloatArray? = null
}

/**
 * The most unison voices a source runs. A voice costs a table lookup per frame; this keeps a mistyped `unison(1e9)` from allocating gigabytes (the
 * browser would hang, too).
 */
internal const val MAX_WAVETABLE_VOICES = 256

/**
 * The wavetable oscillator of superdough (`wavetable-oscillator-processor`, worklets.mjs:1346-1412) without its begin/end gating, which the
 * [WavetableSource] does. [frames] are the single-cycle frames of the table. For each frame of output:
 *
 * ```
 * idx = clamp(position, 0, 1) * (numFrames - 1); the output blends frame floor(idx) and the next one by the fraction of idx
 * each voice n < voices: phase p = warp(phase[n], clamp(warp, 0, 1), mode); a sample of both frames read at p (linear, wrapping)
 *   at the frequency f * 2^(detune cents / 1200) * 2^(spread offset of the voice / 12)
 * ```
 *
 * Voice `n` is panned left or right (alternating, by [WavetableParams.panspread]) and every voice is scaled by `1 / sqrt(voices)`. A voice starts at
 * phase `random * phaserand`. In [WarpModes.FLIP] the sign of the sample flips while the phase is below the warp amount. Rendering does not allocate.
 */
internal class WavetableOscillator(private val sampleRate: Int, frames: List<FloatArray>, private val rng: SourceRng, voiceCapacity: Int = 1) {
    private val table: Array<FloatArray> = Array(frames.size) { frames[it] }
    private val numFrames = table.size
    private val invSr = 1.0 / sampleRate
    private var phase = DoubleArray(Math.max(1, Math.min(voiceCapacity, MAX_WAVETABLE_VOICES)))
    private var voiceRatio = DoubleArray(phase.size)
    private var started = BooleanArray(phase.size)

    init {
        require(numFrames > 0) { "a wavetable needs at least one frame" }
    }

    /** Back to the state of a new oscillator: every voice picks its start phase again. */
    fun reset() {
        java.util.Arrays.fill(started, false)
    }

    /**
     * Writes the next [n] frames to `left[0 until n]` and `right[0 until n]`. The frequency of frame `i` is `p.frequency` plus
     * `bus.frequencyAddHz[i]` (when [bus] has a frequency input) bent by `bus.detuneCents[i]` cents (when it has a detune input).
     */
    fun render(left: FloatArray, right: FloatArray, n: Int, p: WavetableParams, bus: PitchBus?) {
        if (n <= 0) return
        java.util.Arrays.fill(left, 0, n, 0f)
        java.util.Arrays.fill(right, 0, n, 0f)
        val voices = p.voices
        val voiceCount = voiceCount(voices)
        ensureCapacity(voiceCount)
        val panspread = if (voices > 1.0) clamp01(p.panspread) else 0.0
        val gain1 = Math.sqrt(0.5 - 0.5 * panspread)
        val gain2 = Math.sqrt(0.5 + 0.5 * panspread)
        val normalizer = 1.0 / Math.sqrt(voices)
        val phaseRand = clamp01(p.phaserand)
        val scale = if (voices < 2.0) 0.0 else p.freqspread / (voices - 1.0)
        val center = p.freqspread * 0.5
        for (v in 0 until voiceCount) {
            voiceRatio[v] = if (voices < 2.0) 1.0 else Math.pow(2.0, (v * scale - center) / 12.0)
            if (!started[v]) {
                phase[v] = rng.nextDouble() * phaseRand
                started[v] = true
            }
        }
        val addHz = if (bus != null && bus.frequencyActive) bus.frequencyAddHz else null
        val cents = if (bus != null && bus.detuneActive) bus.detuneCents else null
        val posBuf = p.positionBuf
        val warpBuf = p.warpBuf
        val mode = p.warpMode
        val flip = mode == WarpModes.FLIP
        val last = numFrames - 1
        val lastD = last.toDouble()
        for (i in 0 until n) {
            var f = if (addHz != null) p.frequency + addHz[i] else p.frequency
            if (cents != null) f *= Math.pow(2.0, (cents[i] / 100.0) / 12.0)
            val position = clamp01(if (posBuf != null) posBuf[i].toDouble() else p.position)
            val idx = position * lastD
            val fIdx = idx.toInt()
            val interpT = idx - fIdx
            val amount = clamp01(if (warpBuf != null) warpBuf[i].toDouble() else p.warp)
            val frameA = table[fIdx]
            val frameB = table[if (fIdx + 1 < last) fIdx + 1 else last]
            var outL = 0.0
            var outR = 0.0
            for (v in 0 until voiceCount) {
                val even = (v and 1) == 0
                val ph = phase[v]
                val warped = if (mode == WarpModes.NONE) ph else WarpModes.warp(ph, amount, mode)
                val s0 = sampleFrame(frameA, warped)
                val s1 = sampleFrame(frameB, warped)
                var s = s0 + (s1 - s0) * interpT
                if (flip && ph < amount) s = -s
                outL += s * (if (even) gain1 else gain2) * normalizer
                outR += s * (if (even) gain2 else gain1) * normalizer
                val next = ph + f * voiceRatio[v] * invSr
                phase[v] = next - Math.floor(next)
            }
            left[i] = outL.toFloat()
            right[i] = outR.toFloat()
        }
    }

    private fun voiceCount(voices: Double): Int {
        if (!(voices > 0.0)) return 0
        val count = Math.ceil(voices)
        return if (count > MAX_WAVETABLE_VOICES) MAX_WAVETABLE_VOICES else count.toInt()
    }

    private fun ensureCapacity(count: Int) {
        if (count <= phase.size) return
        phase = phase.copyOf(count)
        voiceRatio = voiceRatio.copyOf(count)
        started = started.copyOf(count)
    }

    private fun clamp01(x: Double): Double = if (x < 0.0) 0.0 else if (x > 1.0) 1.0 else x

    /** `_sampleFrame` (worklets.mjs:1332): linear interpolation in the frame at phase [p], wrapping from the last value to the first. */
    private fun sampleFrame(frame: FloatArray, p: Double): Double {
        val len = frame.size
        val pos = p * len
        var i = pos.toInt()
        if (i >= len) i = 0
        val frac = pos - i
        val a = frame[i].toDouble()
        val i1 = if (i + 1 >= len) 0 else i + 1
        val b = frame[i1].toDouble()
        return a + (b - a) * frac
    }
}
