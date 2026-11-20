package cosic.audio.sources

/**
 * Plays a [PeriodicWave] the way Chromium's `OscillatorNode` does (docs/parity/spec/superdough-sources.md section 2.4): a read index walks the wave table,
 * `frequency * rateScale` entries per frame (backwards for a negative frequency), and each frame blends the two tables [PeriodicWave.select] picked for the
 * frequency of that frame. The output is the table's own level (a peak of about 1); superdough scales it afterwards.
 *
 * The oscillator starts at read index 0, where every basic wave is 0 and rising. Nothing about the sound is stored but the read index, so one instance
 * is one voice of one source: create it with the voice, [reset] it to restart, and call [render] block after block. Rendering does not allocate.
 *
 * Chromium computes in float32; this computes in double. The two agree to float precision, except that the steep edge of a sawtooth or a square can
 * differ by up to 4e-4 for a frame or two a period (the tables, their choice and the phase increment match Chromium's, so it is presumably the float32
 * arithmetic of its read position). `OscillatorOracleVectors` holds what Chromium 155 renders for 41 notes; `PeriodicOscillatorTest` compares.
 */
internal class PeriodicOscillator(private val wave: PeriodicWave) {
    private val size = wave.size
    private val sizeD = size.toDouble()
    private val invSize = 1.0 / size
    private val mask = size - 1
    private val rateScale = wave.rateScale
    private val nyquist = 0.5 * wave.sampleRate
    private val selection = TableSelection()
    private var readIndex = 0.0

    /** Back to read index 0, as when the oscillator is started. */
    fun reset() {
        readIndex = 0.0
    }

    /**
     * Writes the next [n] frames to `out[0 until n]`. The frequency of frame `i` is [baseHz] plus `bus.frequencyAddHz[i]` when [bus] has a frequency
     * input, bent by `bus.detuneCents[i]` cents when it has a detune input, and each frame's frequency is clamped to plus or minus the Nyquist frequency
     * (a note above it is silent: its tables have no partial left). NaN counts as the Nyquist frequency.
     */
    fun render(out: FloatArray, n: Int, baseHz: Double, bus: PitchBus?) {
        if (n <= 0) return
        val addHz = if (bus != null && bus.frequencyActive) bus.frequencyAddHz else null
        val cents = if (bus != null && bus.detuneActive) bus.detuneCents else null
        if (addHz == null && cents == null) {
            renderConstant(out, n, clampToNyquist(baseHz))
            return
        }
        // A pitch input that does not move within the block (the sustain of a pitch envelope, say) is one frequency: pick the tables once.
        val addConstant = addHz == null || isConstant(addHz, n)
        val centsConstant = cents == null || isConstant(cents, n)
        if (addConstant && centsConstant) {
            var f = clampToNyquist(if (addHz != null) baseHz + addHz[0] else baseHz)
            if (cents != null) f = clampToNyquist(f * Math.pow(2.0, cents[0] / 1200.0))
            renderConstant(out, n, f)
            return
        }
        renderVarying(out, n, baseHz, addHz, cents)
    }

    private fun renderConstant(out: FloatArray, n: Int, frequency: Double) {
        val incr = frequency * rateScale
        val absIncr = Math.abs(incr)
        wave.select(frequency, selection)
        val higher = wave.table(selection.higher)
        val lower = wave.table(selection.lower)
        val factor = selection.factor
        var index = readIndex
        for (i in 0 until n) {
            out[i] = interpolate(higher, lower, factor, index, absIncr).toFloat()
            index += incr
            index -= Math.floor(index * invSize) * sizeD
        }
        readIndex = index
    }

    private fun renderVarying(out: FloatArray, n: Int, baseHz: Double, addHz: DoubleArray?, cents: DoubleArray?) {
        var index = readIndex
        for (i in 0 until n) {
            var f = if (addHz != null) baseHz + addHz[i] else baseHz
            f = clampToNyquist(f)
            if (cents != null) f = clampToNyquist(f * Math.pow(2.0, cents[i] / 1200.0))
            val incr = f * rateScale
            wave.select(f, selection)
            out[i] = interpolate(wave.table(selection.higher), wave.table(selection.lower), selection.factor, index, Math.abs(incr)).toFloat()
            index += incr
            index -= Math.floor(index * invSize) * sizeD
        }
        readIndex = index
    }

    /**
     * The value of the blend `(1 - factor) * higher + factor * lower` at the fractional table position [index] (in `[0, size]`), for a note that steps
     * [absIncr] entries a frame: Chromium reads linearly once the step is 0.3 entries or more (every audible note), with a 3-point Lagrange polynomial
     * from 0.16 and a 5-point one below that (oscillators slower than about 3.5 Hz).
     */
    private fun interpolate(higher: FloatArray, lower: FloatArray, factor: Double, index: Double, absIncr: Double): Double {
        val i0 = index.toInt()
        val t = index - i0
        var h: Double
        var l: Double
        if (absIncr >= LINEAR_FROM) {
            val a = i0 and mask
            val b = (i0 + 1) and mask
            h = (1.0 - t) * higher[a] + t * higher[b]
            l = (1.0 - t) * lower[a] + t * lower[b]
        } else if (absIncr >= LAGRANGE3_FROM) {
            val w0 = 0.5 * t * (t - 1.0)
            val w1 = 1.0 - t * t
            val w2 = 0.5 * t * (t + 1.0)
            val a = (i0 - 1) and mask
            val b = i0 and mask
            val c = (i0 + 1) and mask
            h = w0 * higher[a] + w1 * higher[b] + w2 * higher[c]
            l = w0 * lower[a] + w1 * lower[b] + w2 * lower[c]
        } else {
            val t2 = t * t
            val w0 = t * (t2 - 1.0) * (t - 2.0) / 24.0
            val w1 = -t * (t - 1.0) * (t2 - 4.0) / 6.0
            val w2 = (t2 - 1.0) * (t2 - 4.0) / 4.0
            val w3 = -t * (t + 1.0) * (t2 - 4.0) / 6.0
            val w4 = t * (t2 - 1.0) * (t + 2.0) / 24.0
            val a = (i0 - 2) and mask
            val b = (i0 - 1) and mask
            val c = i0 and mask
            val d = (i0 + 1) and mask
            val e = (i0 + 2) and mask
            h = w0 * higher[a] + w1 * higher[b] + w2 * higher[c] + w3 * higher[d] + w4 * higher[e]
            l = w0 * lower[a] + w1 * lower[b] + w2 * lower[c] + w3 * lower[d] + w4 * lower[e]
        }
        return (1.0 - factor) * h + factor * l
    }

    private fun clampToNyquist(f: Double): Double =
        if (f != f) nyquist else if (f > nyquist) nyquist else if (f < -nyquist) -nyquist else f

    private fun isConstant(values: DoubleArray, n: Int): Boolean {
        val first = values[0]
        for (i in 1 until n) if (values[i] != first) return false
        return true
    }

    private companion object {
        /** Steps of a table entry or more a frame: linear interpolation. */
        const val LINEAR_FROM = 0.3

        /** Steps from this up to [LINEAR_FROM]: 3-point Lagrange; below it 5-point. */
        const val LAGRANGE3_FROM = 0.16
    }
}
