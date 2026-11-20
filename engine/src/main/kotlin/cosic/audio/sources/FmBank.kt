package cosic.audio.sources

import cosic.audio.AParam
import cosic.audio.QUANTUM
import cosic.audio.adsrComponent
import cosic.audio.getParamADSR
import cosic.pattern.Undefined

/**
 * The frequency modulation of one voice: superdough's `applyFM` (packages/superdough/helpers.mjs:407-507) as a [FrequencyModulator]. It is the whole
 * modulation graph of an oscillator-like source (`sine`, `sawtooth`, `supersaw`, `pulse`, wavetables ...): up to eight modulator operators that feed the
 * frequency of the carrier or of one another, as the `fm`, `fmh`, `fmwave`, `fmattack` ... controls and the `fmi12`-style matrix ask for
 * (docs/parity/spec/superdough-sources.md section 2.12; [FmRouting] has the naming).
 *
 * **What it computes.** Operator `k` is an oscillator of its own wave (`fmwave`: a native wave name, or a noise name for a looped noise buffer without a
 * frequency input) at the frequency `carrierHz * fmh` (all in float32, as the AudioParam and GainNode values are), times its envelope when one of
 * `fmattack`, `fmdecay`, `fmsustain`, `fmrelease` is set. A connection adds `output * amount * frequency of its source operator` Hz to the frequency of its
 * target, so a sine operator gives the carrier a modulation index of `amount`. An operator's own frequency is `fmh * carrierHz` plus the connections into it.
 * [add] adds the sum of the connections into the carrier to [PitchBus.frequencyAddHz]; the oscillator then plays `baseHz + frequencyAddHz[i]` as usual.
 *
 * **Envelope.** `getParamADSR(gain, a, d, s, r, 0, 1, begin = 0, end = holdSec, type)` with `getADSRValues` and no defaults, exponential unless the
 * operator's `fmenv` is set to something other than `"exp"` (then linear). The gain is 0.001 at the start of an exponential envelope and does not go lower
 * than that after the release, so an operator with an envelope never falls silent.
 *
 * **Time.** Every operator starts at frame 0 with phase 0 when the voice starts (superdough starts them when the event is scheduled, which leaves their
 * phase at the note's start to chance). The bank counts the frames it is asked for, so [add] must be called for every block the oscillator renders, from
 * its first frame on, and [reset] when the voice starts again.
 *
 * **Cycles.** Web Audio says a cycle of nodes without a delay is muted; Chromium (the browser Strudel is written for) does not mute it but pulls the graph
 * from the output backwards, and a node that is asked for its output while it is still working out its own input hands over the output of the
 * *previous render quantum* (128 frames earlier). `fmi11` (an operator into itself) is such a cycle, as is `fmi12` together with `fmi2`. [create] orders the
 * operators the way that pull finishes them (depth first from the carrier, the connections into a target in the order `applyFM` made them), so an
 * operator that closes a cycle is always rendered *after* the operator that asks it for its output; and since an operator's output array is only overwritten
 * when it is rendered, what the asking operator reads there, at those frames of the quantum, is what was rendered in the quantum before. No extra
 * buffer, no special case: exactly Chromium's 128 frames, counted here from the start of the voice. Chromium takes the inputs of a node in an order
 * that varies between runs, so this is the order only for nodes with a single input; where a node on a cycle has several inputs, the cycle can break at
 * another connection there. Verified frame by frame against Chromium ([FmGoldenTest]); the first 128 frames of a cycle are those of the same graph
 * without the connection that closes it.
 *
 * **Not modelled.** `.lfo()`/`.env()` on the operators (superdough exposes their nodes as `fm_1`, `fm_1_gain`, ... to the modulators); a `fmwave` of
 * `"custom"` (Chromium throws and superdough drops the event: here it is a sine); a non-finite `fmh` or amount (the `AudioParam` setter throws and the
 * event is dropped: here the operator or the connection does not exist, see [warnings]).
 *
 * The state of a bank is allocated by [create]; [add] and [reset] do not allocate.
 *
 * ```
 * val fm = FmBank.create(spec, carrierHz = frequency)          // null: no FM, nothing to do
 * // per block, after bus.clear(n):  pitch?.add(bus, n); fm?.add(bus, n); oscillator.render(out, n, frequency, bus)
 * ```
 */
internal class FmBank private constructor(
    private val sampleRate: Int,
    private val operators: Array<Operator>,
    private val carrierInputs: Array<Connection>,
    /** Connections that close a cycle (their source is rendered after their target, so they carry the quantum before). */
    val delayedConnectionCount: Int,
    /** What superdough would have logged for the connections that could not be made (and for the values that cannot be used). */
    val warnings: List<String>,
) : FrequencyModulator {

    /** `output of source * amount`, then `* frequency of source`: the two GainNodes of a connection. */
    private class Connection(val source: Operator, val amount: Float)

    private class Operator(
        /** `osc.frequency.value` and the gain of every connection leaving the operator: the carrier frequency times `fmh`, in float32. */
        val frequency: Float,
        val oscillator: PeriodicOscillator?,
        val noise: LoopedNoise?,
        val envelope: AParam?,
    ) {
        /** The connections into this operator's frequency (none for noise, which has no frequency input). */
        var inputs: Array<Connection> = NO_CONNECTIONS

        /**
         * What the operator rendered in the quantum so far, frame by frame: the frames not rendered yet in this quantum still hold those of the quantum
         * before, which is what a connection that closes a cycle reads.
         */
        val output = FloatArray(QUANTUM)
    }

    /** Operators that sound: those the carrier is connected to, directly or through others. */
    val operatorCount: Int get() = operators.size

    /** Frames asked for so far (voice time in frames). */
    private var position = 0L

    /** The shared scratch of the quantum being worked on. */
    private val raw = FloatArray(QUANTUM)
    private val envelopeValues = FloatArray(QUANTUM)
    private val sum = FloatArray(QUANTUM)

    /** The frequency inputs of the operator being worked on, as the oscillator reads them. */
    private val frequencyInput = PitchBus(QUANTUM).also { it.frequencyActive = true }

    /** Back to the start of the voice: time 0, every operator at phase 0 and every output silent (which is what a connection that closes a cycle reads in the first quantum). */
    fun reset() {
        position = 0
        for (op in operators) {
            op.oscillator?.reset()
            op.noise?.reset()
            java.util.Arrays.fill(op.output, 0f)
        }
    }

    override fun add(bus: PitchBus, n: Int) {
        if (n <= 0) return
        val target = bus.frequencyAddHz
        var done = 0
        while (done < n) {
            // the quantum grid of the voice: a connection that closes a cycle reads the same frames of the quantum before
            val start = (position % QUANTUM).toInt()
            val length = minOf(n - done, QUANTUM - start)
            for (op in operators) renderOperator(op, start, length)
            sumConnections(carrierInputs, start, length)
            for (i in 0 until length) target[done + i] += finite(sum[i])
            position += length
            done += length
        }
        bus.frequencyActive = true
    }

    /** Renders frames `[start, start + length)` of the quantum of [op] into its output. */
    private fun renderOperator(op: Operator, start: Int, length: Int) {
        val oscillator = op.oscillator
        if (oscillator != null) {
            if (op.inputs.isEmpty()) {
                oscillator.render(raw, length, op.frequency.toDouble(), null)
            } else {
                // read before this operator writes: whatever feeds back from an operator rendered later is still the previous quantum's output
                sumConnections(op.inputs, start, length)
                val add = frequencyInput.frequencyAddHz
                for (i in 0 until length) add[i] = finite(sum[i])
                oscillator.render(raw, length, op.frequency.toDouble(), frequencyInput)
            }
        } else {
            op.noise!!.render(raw, length)
        }
        val envelope = op.envelope
        val output = op.output
        if (envelope != null) {
            envelope.timeline.fillBlock(envelopeValues, position, sampleRate, length, envelope.base)
            for (i in 0 until length) output[start + i] = raw[i] * envelopeValues[i]
        } else {
            System.arraycopy(raw, 0, output, start, length)
        }
    }

    /** `sum[0 until length]` = the connections' signals added in float32 (`GainNode`s into an `AudioParam`), for the frames `[start, start + length)`. */
    private fun sumConnections(connections: Array<Connection>, start: Int, length: Int) {
        java.util.Arrays.fill(sum, 0, length, 0f)
        for (c in connections) {
            val source = c.source.output
            val amount = c.amount
            val frequency = c.source.frequency
            for (i in 0 until length) sum[i] += (source[start + i] * amount) * frequency
        }
    }

    /**
     * [v] as a frequency offset the oscillators can read: a number beyond float32 (or a NaN, from `0 * Infinity`) becomes the largest float of its sign, which
     * the oscillator clamps to plus or minus the Nyquist frequency, as Chromium does for such a frequency, and the bus stays finite for the sources that
     * read it in other ways. Only absurd controls get here: an amount of 1e38 or a sustain of -1e300.
     */
    private fun finite(v: Float): Double = if (v - v == 0f) v.toDouble() else if (v < 0f) -FLOAT_MAX else FLOAT_MAX

    companion object {
        private val NO_CONNECTIONS = emptyArray<Connection>()

        /** The nominal range of a GainNode's gain: any float32. */
        private val FLOAT_MAX = Float.MAX_VALUE.toDouble()

        /**
         * The FM graph [spec]'s controls ask for, for a carrier whose frequency parameter holds [carrierHz] (`param.value`: the operators' frequencies are
         * fixed multiples of it, whatever vibrato or the pitch envelope do to the carrier afterwards). [holdSec] is `value.duration`, the length of the
         * note in seconds: the FM envelopes release there (for every source, wavetables included, whose own hold is shorter).
         *
         * Returns null when nothing is connected to the carrier: no amount is set (or all are 0), or the operators with an amount feed only each other.
         * Crackle operator `k` is built from the seed `spec.seed + k`; the other noises are the shared, cached buffers.
         */
        fun create(spec: SourceSpec, carrierHz: Double, holdSec: Double = spec.durationSec): FmBank? {
            val controls = spec.controls
            val warnings = ArrayList<String>()
            val carrier = carrierHz.toFloat().toDouble()
            val definitions = arrayOfNulls<Definition>(FmRouting.OPERATORS + 1)
            val checked = BooleanArray(FmRouting.OPERATORS + 1)

            /** The operator [index] as the controls describe it, or null when its frequency is not a number the oscillator could hold. */
            fun definitionOf(index: Int): Definition? {
                if (!checked[index]) {
                    checked[index] = true
                    val suffix = FmRouting.suffix(index)
                    val ratio = controls.number("fmh$suffix") ?: 1.0
                    val frequency = (carrier * ratio).toFloat()
                    if (frequency.isFinite()) {
                        val wave = controls.text("fmwave$suffix")
                        definitions[index] = Definition(frequency, noiseOf(wave), if (wave == null) BasicWave.SINE else basicWaveOf(wave))
                    } else {
                        warnings.add("[superdough] fmh$suffix is not usable with a carrier of $carrierHz Hz: operator $index is not created")
                    }
                }
                return definitions[index]
            }

            // the connections into each target (0: the carrier), in the order of applyFM's loops
            val incoming = Array(FmRouting.OPERATORS + 1) { ArrayList<EdgeDefinition>() }
            for (edge in FmRouting.edges(controls)) {
                val source = definitionOf(edge.source)
                val target = if (edge.target == 0) null else definitionOf(edge.target)
                if (source == null || (edge.target != 0 && target == null)) continue
                if (target != null && target.noise != null) {
                    warnings.add(
                        "[superdough] control ${edge.control} failed to connect FM ${edge.source} to target ${edge.target} " +
                            "due to missing frequency parameter (likely because fm${edge.target} is noise)",
                    )
                    continue
                }
                val amount = edge.amount.toFloat()
                if (!amount.isFinite()) {
                    warnings.add("[superdough] control ${edge.control} (${edge.amount}) is not usable as an FM amount: not connected")
                    continue
                }
                incoming[edge.target].add(EdgeDefinition(edge.source, amount))
            }
            if (incoming[0].isEmpty()) return null

            // The order a browser pulls the graph in, from the carrier backwards: an operator is done when everything that feeds it is, so the operators come
            // out in the order they are rendered in. A connection whose source is still being worked on closes a cycle.
            val state = IntArray(FmRouting.OPERATORS + 1)
            val order = ArrayList<Int>()
            var delayed = 0
            fun visit(node: Int) {
                state[node] = 1
                for (e in incoming[node]) {
                    when (state[e.source]) {
                        0 -> visit(e.source)
                        1 -> delayed++
                    }
                }
                state[node] = 2
                if (node != 0) order.add(node)
            }
            visit(0)

            val sampleRate = spec.sampleRate
            val built = arrayOfNulls<Operator>(FmRouting.OPERATORS + 1)
            val operators = Array(order.size) { k ->
                val index = order[k]
                val d = checkNotNull(definitions[index])
                val noise = d.noise
                val operator = Operator(
                    frequency = d.frequency,
                    oscillator = if (noise == null) PeriodicOscillator(PeriodicWave.basic(d.wave, sampleRate)) else null,
                    noise = if (noise == null) null else LoopedNoise(NoiseBuffers.get(noise, sampleRate, FM_CRACKLE_DENSITY, spec.seed + index)),
                    envelope = envelopeOf(controls, index, holdSec),
                )
                built[index] = operator
                operator
            }
            fun connections(edges: List<EdgeDefinition>) = Array(edges.size) { Connection(checkNotNull(built[edges[it].source]), edges[it].amount) }
            for (k in order.indices) operators[k].inputs = connections(incoming[order[k]])
            return FmBank(sampleRate, operators, connections(incoming[0]), delayed, warnings)
        }

        /** An operator, as the controls describe it: its frequency, and its wave (a noise buffer when [noise] is set). */
        private class Definition(val frequency: Float, val noise: NoiseType?, val wave: BasicWave)

        /** A connection from operator [source], before the operators exist. */
        private class EdgeDefinition(val source: Int, val amount: Float)

        /** `noises.includes(type)`: exactly these names. */
        private fun noiseOf(wave: String?): NoiseType? = when (wave) {
            "pink" -> NoiseType.PINK
            "white" -> NoiseType.WHITE
            "brown" -> NoiseType.BROWN
            "crackle" -> NoiseType.CRACKLE
            else -> null
        }

        /**
         * `osc.type = type` for a name that is not one of the four leaves the oscillator a sine, as does a wave that is not a string; `"custom"`, which
         * Chromium refuses to set directly, is a sine here too.
         */
        private fun basicWaveOf(wave: String): BasicWave = when (wave) {
            "square" -> BasicWave.SQUARE
            "sawtooth" -> BasicWave.SAWTOOTH
            "triangle" -> BasicWave.TRIANGLE
            else -> BasicWave.SINE
        }

        /** The `density` superdough passes to `getNoiseBuffer` for a noise operator (only crackle uses it). */
        private const val FM_CRACKLE_DENSITY = 2.0

        /**
         * The gain envelope of operator [index], or null when none of its four envelope controls is set: `getADSRValues` without defaults, then
         * `getParamADSR(0, 1, begin 0, end holdSec)`, exponential unless `fmenv` is set to anything but `"exp"`.
         */
        private fun envelopeOf(controls: SourceControls, index: Int, holdSec: Double): AParam? {
            val suffix = FmRouting.suffix(index)
            if (ENVELOPE_KEYS.none { controls.isSet(it + suffix) }) return null
            val a = controls.number(ENVELOPE_KEYS[0] + suffix) ?: Double.NaN
            val d = controls.number(ENVELOPE_KEYS[1] + suffix) ?: Double.NaN
            val s = controls.number(ENVELOPE_KEYS[2] + suffix) ?: Double.NaN
            val r = controls.number(ENVELOPE_KEYS[3] + suffix) ?: Double.NaN
            val type = controls["fmenv$suffix"]
            val exponential = type == null || type === Undefined || type == "exp"
            val gain = AParam(1.0, -FLOAT_MAX, FLOAT_MAX, 8)
            getParamADSR(
                gain,
                adsrComponent(0, a, d, s, r), adsrComponent(1, a, d, s, r), adsrComponent(2, a, d, s, r), adsrComponent(3, a, d, s, r),
                0.0, 1.0, 0.0, holdSec, exponential,
            )
            return gain
        }

        private val ENVELOPE_KEYS = arrayOf("fmattack", "fmdecay", "fmsustain", "fmrelease")
    }
}
