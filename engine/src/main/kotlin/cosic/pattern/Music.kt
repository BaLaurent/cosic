package cosic.pattern

/*
 * Note and frequency utilities ported from `packages/core/util.mjs` of Strudel (K9 of the parity plan: this file is the
 * one owner of them). Notes are strings such as `c3`, `Eb4` or `fs`; MIDI numbers and frequencies are doubles. Unlike
 * the old Cosic code, a name that is not a note throws, as it does in Strudel.
 */

private val NOTE_WITH_OCTAVE = Regex("[a-gA-G][#bsf]*[0-9]*")
private val NOTE = Regex("[a-gA-G][#bsf]*-?[0-9]*")
private val NOTE_TOKENS = Regex("([a-gA-G])([#bsf]*)(-?[0-9]*)")

private val CHROMAS = mapOf('c' to 0, 'd' to 2, 'e' to 4, 'f' to 5, 'g' to 7, 'a' to 9, 'b' to 11)
private val ACCIDENTALS = mapOf('#' to 1, 'b' to -1, 's' to 1, 'f' to -1)

private const val LN2 = 0.6931471805599453

/** Whether [v] reads as a note with an optional octave ahead of the digits: `c`, `Bb4`, `fs`. Anything but a string is read as its string (`isNote(1)` is false). */
internal fun isNoteWithOctave(v: Any?): Boolean = NOTE_WITH_OCTAVE.matches(jsToString(v))

/** Whether [v] is a note name: a letter `a` to `g`, any number of `#`, `b`, `s` and `f`, and an optional octave that may be negative (`c-1`). */
internal fun isNote(v: Any?): Boolean = NOTE.matches(jsToString(v))

/**
 * Splits a note name into its letter, its accidentals and its octave, null for anything that is not a note. The octave
 * is null when the name has none; `c-` has the octave NaN (`Number("-")`).
 */
internal fun tokenizeNote(note: Any?): Triple<String, String, Double?>? {
    if (note !is String) return null
    val m = NOTE_TOKENS.matchEntire(note) ?: return null
    val (pc, acc, oct) = m.destructured
    return Triple(pc, acc, if (oct.isEmpty()) null else jsStringToNumber(oct))
}

/** The semitones the accidentals of a note add: `#` and `s` raise by one, `b` and `f` lower by one. */
internal fun getAccidentalsOffset(accidentals: String?): Int = accidentals?.sumOf { ACCIDENTALS.getValue(it) } ?: 0

/**
 * The MIDI number of a note name: `c4` is 60, `a4` is 69, and a name without an octave is in [defaultOctave], so `c` is 48.
 * Throws `not a note: "x"` for anything that is not a note.
 */
internal fun noteToMidi(note: Any?, defaultOctave: Int = 3): Double {
    val (pc, acc, oct) = tokenizeNote(note) ?: throw CosicError("not a note: \"${jsToString(note)}\"")
    val chroma = CHROMAS.getValue(pc.lowercase()[0])
    return ((oct ?: defaultOctave.toDouble()) + 1) * 12 + chroma + getAccidentalsOffset(acc)
}

/** The frequency in Hz of MIDI note [n]: 69 is 440 Hz. */
internal fun midiToFreq(n: Double): Double = StrictMath.pow(2.0, (n - 69) / 12) * 440

/** The MIDI note of a frequency in Hz; 440 is 69. */
internal fun freqToMidi(freq: Double): Double = (12 * StrictMath.log(freq / 440)) / LN2 + 69

/**
 * The MIDI note a control map asks for: its `freq` if that is a number, else its `note` (a name or a number). Throws when
 * [value] is not a map; with neither key set it answers [fallback], and throws if that is missing or 0.
 */
internal fun valueToMidi(value: Any?, fallback: Double? = null): Double {
    if (jsTypeof(value) != "object") throw CosicError("valueToMidi: expected object value")
    val map = value as? Map<*, *>
    val freq = map?.get("freq")
    val note = map?.get("note")
    if (freq is Double) return freqToMidi(freq)
    if (note is String) return noteToMidi(note)
    if (note is Double) return note
    if (fallback == null || fallback == 0.0 || fallback.isNaN()) throw CosicError("valueToMidi: expected freq or note to be set")
    return fallback
}

private val PITCH_CLASSES = listOf("C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Bb", "B")

/** The name of MIDI note [n] with flats (`60` is `C4`). Strudel's `midi2note`; only meant for whole numbers from 0 up. */
internal fun midi2note(n: Double): String {
    val octave = Math.floor(n / 12) - 1
    val index = n % 12
    val pc = if (index >= 0 && index == Math.floor(index)) PITCH_CLASSES[index.toInt()] else "undefined"
    return pc + jsNumberToString(octave)
}

/** A modulo that stays positive for negative [n]: `_mod(-1, 3)` is 2. */
internal fun _mod(n: Double, m: Double): Double = jsMod(n, m)

/** Which of [numSounds] samples a sample number picks: rounded, wrapped around, and negative numbers count from the end. A number that is not one counts as 0, with a warning. */
internal fun getSoundIndex(n: Any?, numSounds: Int): Int {
    val checked = nanFallback(if (n == null || n === Undefined) 0.0 else n, 0.0)
    return _mod(jsMathRound(jsToNumber(checked)), numSounds.toDouble()).toInt()
}

/**
 * The frequency in Hz of a hap, a control map's `freq`, a note name or a MIDI number. A [Hap] is read by its value and its
 * context's `type`; any other value is read as a note (a number is a MIDI note unless [contextType] is `frequency`).
 * A map without `freq` is read by its `note`, `n` or `value`. Anything else throws `not a note or frequency: x`.
 */
internal fun getFrequency(hap: Any?, contextType: String? = null): Double {
    var value: Any?
    var type: Any? = contextType ?: "note"
    when (hap) {
        is Hap -> {
            value = hap.value
            type = hap.context.extra["type"]
        }
        null -> throw CosicError("Cannot destructure property 'value' of 'hap' as it is null.")
        is Map<*, *> -> {
            value = hap["value"] ?: Undefined
            type = (hap["context"] as? Map<*, *>)?.get("type")
        }
        else -> value = hap
    }
    if (jsTypeof(value) == "object") {
        if (value == null) throw CosicError("Cannot read properties of null (reading 'freq')")
        val map = value as? Map<*, *>
        val freq = map?.get("freq")
        if (jsTruthy(freq)) return jsToNumber(freq)
        val inner = listOf(map?.get("note"), map?.get("n"), map?.get("value")).firstOrNull { it != null && it !== Undefined }
        return getFrequency(inner ?: Undefined)
    }
    if (value is Number && type != "frequency") return midiToFreq(value.toDouble())
    if (value is String && isNote(value)) return midiToFreq(noteToMidi(value))
    if (value !is Number) throw CosicError("not a note or frequency: ${jsToString(value)}")
    return value.toDouble()
}

/** `getFreq`: [getFrequency] of a value that is read as a note. */
internal fun getFreq(hap: Any?): Double = getFrequency(hap)

/**
 * A number from a number, a numeric string or a note name (`"c4"` is 60): `Number(v)` if that is a number, else the note's
 * MIDI number. Throws `cannot parse as numeral: "x"` for anything else.
 */
internal fun parseNumeral(v: Any?): Double {
    val asNumber = jsToNumber(v)
    if (!asNumber.isNaN()) return asNumber
    if (isNote(v)) return noteToMidi(v)
    throw CosicError("cannot parse as numeral: \"${jsToString(v)}\"")
}

private val FRACTIONALS = mapOf(
    "pi" to Math.PI, "w" to 1.0, "h" to 0.5, "q" to 0.25, "e" to 0.125, "s" to 0.0625, "t" to 1.0 / 3, "f" to 0.2, "x" to 1.0 / 6,
)

/** Like [parseNumeral], for a length of time: a number, or one of the names `pi`, `w`, `h`, `q`, `e`, `s`, `t`, `f`, `x`. Throws `cannot parse as fractional: "x"`. */
internal fun parseFractional(v: Any?): Double {
    val asNumber = jsToNumber(v)
    if (!asNumber.isNaN()) return asNumber
    FRACTIONALS[jsToString(v)]?.let { return it }
    throw CosicError("cannot parse as fractional: \"${jsToString(v)}\"")
}
