package cosic.audio.dsp

/** The five formants of a vowel: the centre frequencies in Hz, the linear gains and the Q (linear, not decibels) of each band-pass. */
internal class Formant(val freqs: DoubleArray, val gains: DoubleArray, val qs: DoubleArray)

/**
 * The vowel table of `vowel.mjs:4-41` (taken there from WebDirt): 15 vowels and the aliases `æ ø ɑ å ö ü ı`. Lookup is exact, as `vowelFormant[letter]`
 * is: `A` is not `a`.
 */
internal object VowelFormants {
    val table: Map<String, Formant>

    init {
        fun f(freqs: DoubleArray, gains: DoubleArray, qs: DoubleArray) = Formant(freqs, gains, qs)
        val a = f(doubleArrayOf(660.0, 1120.0, 2750.0, 3000.0, 3350.0), doubleArrayOf(1.0, 0.5012, 0.0708, 0.0631, 0.0126), doubleArrayOf(80.0, 90.0, 120.0, 130.0, 140.0))
        val e = f(doubleArrayOf(440.0, 1800.0, 2700.0, 3000.0, 3300.0), doubleArrayOf(1.0, 0.1995, 0.1259, 0.1, 0.1), doubleArrayOf(70.0, 80.0, 100.0, 120.0, 120.0))
        val i = f(doubleArrayOf(270.0, 1850.0, 2900.0, 3350.0, 3590.0), doubleArrayOf(1.0, 0.0631, 0.0631, 0.0158, 0.0158), doubleArrayOf(40.0, 90.0, 100.0, 120.0, 120.0))
        val o = f(doubleArrayOf(430.0, 820.0, 2700.0, 3000.0, 3300.0), doubleArrayOf(1.0, 0.3162, 0.0501, 0.0794, 0.01995), doubleArrayOf(40.0, 80.0, 100.0, 120.0, 120.0))
        val u = f(doubleArrayOf(370.0, 630.0, 2750.0, 3000.0, 3400.0), doubleArrayOf(1.0, 0.1, 0.0708, 0.0316, 0.01995), doubleArrayOf(40.0, 60.0, 100.0, 120.0, 120.0))
        val ae = f(doubleArrayOf(650.0, 1515.0, 2400.0, 3000.0, 3350.0), doubleArrayOf(1.0, 0.5, 0.1008, 0.0631, 0.0126), doubleArrayOf(80.0, 90.0, 120.0, 130.0, 140.0))
        val aa = f(doubleArrayOf(560.0, 900.0, 2570.0, 3000.0, 3300.0), doubleArrayOf(1.0, 0.5, 0.0708, 0.0631, 0.0126), doubleArrayOf(80.0, 90.0, 120.0, 130.0, 140.0))
        val oe = f(doubleArrayOf(500.0, 1430.0, 2300.0, 3000.0, 3300.0), doubleArrayOf(1.0, 0.2, 0.0708, 0.0316, 0.01995), doubleArrayOf(40.0, 60.0, 100.0, 120.0, 120.0))
        val ue = f(doubleArrayOf(250.0, 1750.0, 2150.0, 3200.0, 3300.0), doubleArrayOf(1.0, 0.1, 0.0708, 0.0316, 0.01995), doubleArrayOf(40.0, 60.0, 100.0, 120.0, 120.0))
        val y = f(doubleArrayOf(400.0, 1460.0, 2400.0, 3000.0, 3300.0), doubleArrayOf(1.0, 0.2, 0.0708, 0.0316, 0.02995), doubleArrayOf(40.0, 60.0, 100.0, 120.0, 120.0))
        val uh = f(doubleArrayOf(600.0, 1250.0, 2100.0, 3100.0, 3500.0), doubleArrayOf(1.0, 0.3, 0.0608, 0.0316, 0.01995), doubleArrayOf(40.0, 70.0, 100.0, 120.0, 130.0))
        val un = f(doubleArrayOf(500.0, 1240.0, 2280.0, 3000.0, 3500.0), doubleArrayOf(1.0, 0.1, 0.1708, 0.0216, 0.02995), doubleArrayOf(40.0, 60.0, 100.0, 120.0, 120.0))
        val en = f(doubleArrayOf(600.0, 1480.0, 2450.0, 3200.0, 3300.0), doubleArrayOf(1.0, 0.15, 0.0708, 0.0316, 0.02995), doubleArrayOf(40.0, 60.0, 100.0, 120.0, 120.0))
        val an = f(doubleArrayOf(700.0, 1050.0, 2500.0, 3000.0, 3300.0), doubleArrayOf(1.0, 0.1, 0.0708, 0.0316, 0.02995), doubleArrayOf(40.0, 60.0, 100.0, 120.0, 120.0))
        val on = f(doubleArrayOf(500.0, 1080.0, 2350.0, 3000.0, 3300.0), doubleArrayOf(1.0, 0.1, 0.0708, 0.0316, 0.02995), doubleArrayOf(40.0, 60.0, 100.0, 120.0, 120.0))
        table = linkedMapOf(
            "a" to a, "e" to e, "i" to i, "o" to o, "u" to u,
            "ae" to ae, "aa" to aa, "oe" to oe, "ue" to ue, "y" to y,
            "uh" to uh, "un" to un, "en" to en, "an" to an, "on" to on,
            // the getters of vowel.mjs:20-40
            "æ" to ae, "ø" to oe, "ɑ" to aa, "å" to aa, "ö" to oe, "ü" to ue, "ı" to y,
        )
    }

    /** The formants of [name] (a vowel or one of its aliases), or null when `vowel.mjs` would throw `vowel: unknown vowel`. */
    fun resolve(name: String): Formant? = table[name]
}

/**
 * The vowel filter of `vowel.mjs:42-81` (`createVowelFilter`): five parallel band-pass `BiquadFilterNode`s at the formant frequencies, each followed by its
 * own gain, summed and multiplied by a makeup gain of 8.
 *
 * The biquad is Chromium's: design from the Web Audio band-pass formulas on `frequency / (sampleRate / 2)` and the Q as given
 * (`alpha = sin(w0) / (2 Q)`, coefficients normalised by `a0`), run in double precision as Direct Form I with the float32 input and the float32-*rounded* output
 * feeding back (the output buffer is what it reads its past from). Gains, the per-band products, their sum and the makeup gain are float32 operations, like the nodes
 * they stand for. Checked against a real Chromium `OfflineAudioContext` render of superdough's own `VowelNode` to within 3e-8, about one float32 ulp (`tools/strudel-ref/vowel-oracle.mjs`).
 *
 * One instance is about a kilobyte: [configure] sets the vowel for a voice without allocating, [reset] clears the filter memory.
 */
internal class VowelFilter(private val sampleRate: Int) {
    private val b0 = DoubleArray(BANDS)
    private val b2 = DoubleArray(BANDS)
    private val a1 = DoubleArray(BANDS)
    private val a2 = DoubleArray(BANDS)
    private val gains = FloatArray(BANDS)

    // Filter memory per channel: the last two input samples (shared by the bands) and the last two outputs of every band.
    private val x1 = FloatArray(2)
    private val x2 = FloatArray(2)
    private val y1 = Array(2) { FloatArray(BANDS) }
    private val y2 = Array(2) { FloatArray(BANDS) }

    /** True once a vowel is set; an unconfigured filter leaves the signal alone. */
    var isConfigured = false
        private set

    /** Selects the vowel [name] (an alias is fine) and clears the memory. Returns false, leaving the filter unconfigured, for a name `vowel.mjs` would reject. */
    fun configure(name: String): Boolean {
        val formant = VowelFormants.resolve(name)
        if (formant == null) {
            isConfigured = false
            return false
        }
        configure(formant)
        return true
    }

    /** Selects [formant] and clears the memory. */
    fun configure(formant: Formant) {
        val nyquist = sampleRate / 2.0
        for (b in 0 until BANDS) {
            // Chromium's Biquad::SetBandpassParams, on the normalised frequency.
            val frequency = Math.max(0.0, formant.freqs[b] / nyquist)
            val q = Math.max(0.0, formant.qs[b])
            var nb0 = 0.0
            var nb2 = 0.0
            var na1 = 0.0
            var na2 = 0.0
            if (frequency > 0 && frequency < 1) {
                if (q > 0) {
                    val w0 = Math.PI * frequency
                    val alpha = Math.sin(w0) / (2 * q)
                    val k = Math.cos(w0)
                    val inverseA0 = 1 / (1 + alpha)
                    nb0 = alpha * inverseA0
                    nb2 = -alpha * inverseA0
                    na1 = -2 * k * inverseA0
                    na2 = (1 - alpha) * inverseA0
                } else {
                    // The limit of the band-pass for Q -> 0 is the identity.
                    nb0 = 1.0
                }
            }
            b0[b] = nb0
            b2[b] = nb2
            a1[b] = na1
            a2[b] = na2
            gains[b] = formant.gains[b].toFloat()
        }
        isConfigured = true
        reset()
    }

    fun reset() {
        x1.fill(0f)
        x2.fill(0f)
        for (ch in 0 until 2) {
            y1[ch].fill(0f)
            y2[ch].fill(0f)
        }
    }

    /** Filters frames `[from, to)` of [left] (and [right]) in place. */
    fun process(left: FloatArray, right: FloatArray?, from: Int, to: Int) {
        if (!isConfigured || to <= from) return
        channel(left, 0, from, to)
        if (right != null) channel(right, 1, from, to)
    }

    private fun channel(io: FloatArray, ch: Int, from: Int, to: Int) {
        val y1c = y1[ch]
        val y2c = y2[ch]
        var px1 = x1[ch]
        var px2 = x2[ch]
        for (n in from until to) {
            val input = io[n]
            val x = if (input - input == 0f) input else 0f // NaN and infinity would stay in the filter memory
            var sum = 0f
            for (b in 0 until BANDS) {
                val y = (b0[b] * x + b2[b] * px2 - a1[b] * y1c[b] - a2[b] * y2c[b]).toFloat()
                y2c[b] = y1c[b]
                y1c[b] = y
                sum += y * gains[b]
            }
            px2 = px1
            px1 = x
            io[n] = sum * MAKEUP_GAIN
        }
        x1[ch] = px1
        x2[ch] = px2
    }

    private companion object {
        const val BANDS = 5

        /** `makeupGain.gain.value = 8` of the vowel node. */
        const val MAKEUP_GAIN = 8f
    }
}
