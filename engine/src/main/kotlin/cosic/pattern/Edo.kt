package cosic.pattern

import java.util.concurrent.ConcurrentHashMap

/*
 * `edoScale` of @strudel/edo (packages/edo/{edo,edoscale,intervals,pitches,ratios}.mjs): a scale of an equal division of the
 * octave described by a root note, a sequence of large and small steps and the sizes of those steps (`C:LLsLLLs:2:1` is the
 * major scale of 12 equal divisions), and the pitches of its degrees. Only what `edoScale` reads is here, not the editing
 * functions of the original classes.
 */

/** The just intervals an edo step is named after: ratio and short name (ratios.mjs:12-57). */
private val JUST_INTERVALS: List<Pair<Double, String>> = listOf(
    1.0 to "P1", 16.0 / 15 to "m2", 15.0 / 14 to "A1", 13.0 / 12 to "t2", 12.0 / 11 to "N2", 11.0 / 10 to "n2", 10.0 / 9 to "T2",
    9.0 / 8 to "M2", 8.0 / 7 to "S2", 7.0 / 6 to "s3", 19.0 / 16 to "o3", 6.0 / 5 to "m3", 17.0 / 14 to "t3", 11.0 / 9 to "n3",
    5.0 / 4 to "M3", 9.0 / 7 to "S3", 13.0 / 10 to "d4", 4.0 / 3 to "P4", 19.0 / 14 to "N4", 11.0 / 8 to "n4", 25.0 / 18 to "a4",
    7.0 / 5 to "sT", 45.0 / 32 to "A4", 17.0 / 12 to "d5", 10.0 / 7 to "ST", 13.0 / 9 to "t5", 3.0 / 2 to "P5", 14.0 / 9 to "s6",
    25.0 / 16 to "a5", 11.0 / 7 to "A5", 8.0 / 5 to "m6", 13.0 / 8 to "N6", 18.0 / 11 to "n6", 5.0 / 3 to "M6", 128.0 / 75 to "d7",
    17.0 / 10 to "T6", 12.0 / 7 to "S6", 7.0 / 4 to "s7", 16.0 / 9 to "m7", 9.0 / 5 to "g7", 11.0 / 6 to "n7", 13.0 / 7 to "N7",
    15.0 / 8 to "M7", 17.0 / 9 to "T7", 19.0 / 10 to "d8", 2.0 to "P8",
)

/** The name of the just interval nearest to [v] if it is within 1%, else the empty string (`key(nearestInterval(v)[1])`). */
private fun nearestIntervalKey(v: Double): String {
    var min = 1.0
    var match = ""
    for ((ratio, key) in JUST_INTERVALS) {
        val diff = Math.abs((ratio - v) / ratio)
        if (diff < min) {
            min = diff
            match = key
        }
    }
    return if (min < 0.01) match else ""
}

/** `division === 0 ? 1 : 2^(division / edivisions)`. */
private fun stepRatio(division: Double, edivisions: Double): Double = if (division == 0.0) 1.0 else StrictMath.pow(2.0, division / edivisions)

/** A pattern of large and small steps (edoscale.mjs): [divisions] are the step sums before each step, [edivisions] the size of the octave. */
internal class EdoScale(large: Double, small: Double, sequence: String) {
    private val steps = IntArray(sequence.length) { if (sequence[it] == 'L') 1 else if (sequence[it] == 'M') 2 else 0 }
    private val sizes = DoubleArray(3).also {
        it[0] = small
        it[1] = large
        it[2] = large // the medium step, which is as large as the large one when it has no size of its own
    }
    val length: Int get() = steps.size
    val divisions: List<Double>
    val edivisions: Double

    init {
        val d = ArrayList<Double>(steps.size)
        var sum = 0.0
        for (s in steps) {
            d.add(sum)
            sum += sizes[s]
        }
        divisions = d
        edivisions = sum
    }

    fun stepValue(i: Int): Double = sizes[steps[i]]
}

/** The ratios of the degrees of an [EdoScale] to its root and the names of the just intervals they come near (intervals.mjs). */
internal class Intervals(scale: EdoScale) {
    /** The ratio of degree `i` (0 for the root) to the root. */
    val ratios = DoubleArray(scale.length + 1)

    /** The interval names of the degrees; index 0 has none, which a list shows as `null`. */
    val intLabels: List<Any?>

    init {
        ratios[0] = 1.0
        val labels = ArrayList<Any?>(scale.length + 1)
        labels.add(null)
        var division = 0.0
        for (i in 0 until scale.length) {
            division += scale.stepValue(i)
            ratios[i + 1] = stepRatio(division, scale.edivisions)
            labels.add(nearestIntervalKey(ratios[i + 1]))
        }
        intLabels = labels
    }
}

/** The frequency and the MIDI note of every degree of every octave of a scale (pitches.mjs), for a root note at a reference pitch of 440 Hz. */
internal class Pitches(val scale: EdoScale, val intervals: Intervals, tuning: Double, midiStart: Double, val rootOctave: Double) {
    private val freqs = HashMap<Int, DoubleArray>()
    private val midis = HashMap<Int, DoubleArray>()

    /** The frequency of the root, as the string `toFixed(4)` makes of it. */
    val baseFreq: String

    init {
        val base = tuning * StrictMath.pow(2.0, (midiStart - 69) / 12.0)
        for (oct in 0..8) {
            var f = base
            if (oct < rootOctave) f /= StrictMath.pow(2.0, rootOctave - oct)
            else if (oct > rootOctave) f *= StrictMath.pow(2.0, oct - rootOctave)
            val fs = DoubleArray(scale.length)
            val ms = DoubleArray(scale.length)
            for (deg in 0 until scale.length) {
                val hz = f * intervals.ratios[deg]
                fs[deg] = jsToFixed(hz, 3).toDouble()
                ms[deg] = jsToFixed(12.0 * (StrictMath.log(hz / tuning) / LN2) + 69, 4).toDouble()
            }
            freqs[oct] = fs
            midis[oct] = ms
        }
        baseFreq = jsToFixed(base, 4)
    }

    /** The octave and degree (counted from 1, going on into the next octaves) a position [deg] of the scale is in. */
    fun octdeg(deg: Double): Pair<Double, Double> {
        val n = scale.length.toDouble()
        val higher = deg > n
        val octave = rootOctave + (if (higher) Math.floor((deg - 1) / n) else 0.0)
        val degree = if (higher) (if (deg % n == 0.0) n else deg % n) else deg
        return octave to degree
    }

    private fun lookup(table: Map<Int, DoubleArray>, oct: Double, deg: Double): Any? {
        if (oct != Math.floor(oct) || oct < 0 || oct > 8) return null
        if (deg != Math.floor(deg) || deg < 1 || deg > scale.length) return Undefined
        return table.getValue(oct.toInt())[deg.toInt() - 1]
    }

    /** The frequency of a degree, `undefined` when it is out of the table and `null` when the octave is. */
    fun octdegfreq(oct: Double, deg: Double): Any? = lookup(freqs, oct, deg)

    fun octdegmidi(oct: Double, deg: Double): Any? = lookup(midis, oct, deg)

    private companion object {
        val LN2 = StrictMath.log(2.0)
    }
}

private val pitchesCache = ConcurrentHashMap<String, Pitches>()
private const val MAX_CACHED_SCALES = 512

private fun pitchesOf(definition: List<*>): Pitches {
    val key = flatKey(definition)
    pitchesCache[key]?.let { return it }
    val baseNote = definition.at(0)
    val sequence = definition.at(1)
    val large = definition.at(2)
    val small = definition.at(3)
    val octave = tokenizeNote(baseNote)?.third
    val rootOctave = if (octave == null || octave.isNaN() || octave == 0.0) 3.0 else octave
    val scale = EdoScale(jsToNumber(large), jsToNumber(small), jsToString(sequence))
    val intervals = Intervals(scale)
    val pitches = Pitches(scale, intervals, 440.0, noteToMidi(baseNote), rootOctave)
    if (pitchesCache.size >= MAX_CACHED_SCALES) pitchesCache.clear()
    pitchesCache[key] = pitches
    return pitches
}

/** `definition.flat().join(':')`: nested lists are flattened by one level, `null` and `undefined` become empty strings. */
private fun flatKey(definition: List<*>): String =
    definition.flatMap { if (it is List<*>) it else listOf(it) }.joinToString(":") { if (isNullish(it)) "" else jsToString(it) }

private fun List<*>.at(i: Int): Any? = if (i < size) this[i] else Undefined

private fun isNullish(v: Any?): Boolean = v == null || v === Undefined

/**
 * `pat.edoScale(definition)` (edo.mjs:53-114): numbers become the notes of an equal-division scale given as
 * `[root, steps, large, small]` (`C:LLsLLLs:2:1`): a control map with `n` gets the frequency of that degree as `freq`
 * (with `degree`, `degreeIndexes`, `intLabels`, `root` and `edo` next to it) and loses its `n`; a plain number becomes the
 * MIDI note of the degree. A note name is left as it is.
 */
internal fun Pattern.edoScale(definition: Any?): Pattern {
    if (definition !is List<*>) throw CosicError("scaleDefinition.flat is not a function")
    val pitches = pitchesOf(definition)
    return fmap { value ->
        val isObject = jsTypeof(value) == "object"
        if (isObject && value == null) throw CosicError("Cannot read properties of null (reading 'n')")
        @Suppress("UNCHECKED_CAST")
        val map = if (isObject) (value as? Map<String, Any?>) ?: emptyMap() else emptyMap()
        val n = if (isObject) (if (map.containsKey("n")) map["n"] else Undefined) else value
        if (isNote(n)) return@fmap pure(n)
        val deg = (when {
            n is String -> jsParseInt(n, 10)
            n is Double && n.isFinite() && n == Math.floor(n) -> n
            else -> jsMathRound(jsToNumber(n))
        }) + 1
        val (oct, degree) = pitches.octdeg(deg)
        val freq = pitches.octdegfreq(oct, degree)
        val note = pitches.octdegmidi(oct, degree)
        pure(
            if (isObject) {
                val rest = LinkedHashMap(map).also { it.remove("n") }
                jsMerge(
                    rest,
                    linkedMapOf(
                        "degree" to degree,
                        "degreeIndexes" to pitches.scale.divisions,
                        "intLabels" to pitches.intervals.intLabels,
                        "root" to pitches.baseFreq,
                        "freq" to freq,
                        "edo" to pitches.scale.edivisions,
                    ),
                )
            } else note,
        )
    }.outerJoin().withHap { hap -> hap.setContext(hap.context.copy(extra = hap.context.extra + ("scaleDefinition" to definition))) }
}
