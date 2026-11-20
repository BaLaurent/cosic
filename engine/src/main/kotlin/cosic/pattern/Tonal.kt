package cosic.pattern

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/*
 * Scales, transposition and arpeggios: `packages/tonal/tonal.mjs` of Strudel and the part of the `@tonaljs` libraries it
 * stands on (tonal 4.10.0 with note 4.12.0, interval 4.8.2, scale 4.13.1, scale-type 4.9.1, range 4.9.1, midi 4.10.1 and the
 * pitch packages under them), ported from the published sources. `arp` and `arpWith` of pattern.mjs are here too.
 *
 * Notes and intervals are values of the pitch algebra of tonal: a pitch is a point in the circle of fifths and an octave
 * ([TNote.fifths], [TNote.octs]). Every function works like its original, odd corners included: a name that tonal does not read
 * as a note (`cs3`, which Strudel's `isNote` accepts) is not a note, transposing it fails the way the original does, and a
 * transposition by an interval that is not one gives the empty string.
 *
 * What the original does that this port does not: `transpose` of tonal.mjs changes the cached note object of tonal (setting its
 * octave), so the result for a note without an octave depends on what was transposed before. Here the decision is made on a copy.
 */

/** What `Note.get` of tonal tells about a note name; [empty] names are not notes and have no other usable property. */
internal class TNote(
    val empty: Boolean,
    /** The name as tonal spells it: the letter in upper case, `x` written as `##`. */
    val name: String,
    val letter: String,
    val acc: String,
    /** The pitch class: the letter and the accidentals. */
    val pc: String,
    val step: Int,
    val alt: Int,
    /** The pitch class as 0 to 11 (C is 0), null for what is not a note. */
    val chroma: Int?,
    val height: Double,
    /** The octave, null (`undefined`) for a note that has none. */
    val oct: Double?,
    /** The position on the circle of fifths. */
    val fifths: Double,
    /** The octaves of the coordinate, null for a note without an octave. */
    val octs: Double?,
) {
    /** The MIDI number, null (as for `Note.get(x).midi`) outside of 0 to 127 and for names that are no notes. */
    val midi: Int? get() = if (!empty && height >= 0 && height <= 127) height.toInt() else null

    companion object {
        val NONE = TNote(true, "", "", "", "", 0, 0, null, Double.NaN, null, Double.NaN, null)
    }
}

/** What `Interval.get` of tonal tells about an interval name such as `3M` or `-2m`; [empty] names are not intervals. */
internal class TInterval(
    val empty: Boolean,
    val name: String,
    val num: Double,
    val q: String,
    val step: Double,
    val alt: Int,
    val dir: Int,
    val type: String,
    val semitones: Double,
    val chroma: Int?,
    val fifths: Double,
    val octs: Double,
    val oct: Double,
) {
    companion object {
        val NONE = TInterval(true, "", Double.NaN, "", Double.NaN, 0, 0, "", Double.NaN, null, Double.NaN, Double.NaN, Double.NaN)
    }
}

/** A scale type of `@tonaljs/scale-type`: its names, its intervals and the two ways tonal numbers its pitch set. */
internal class TScaleType(val name: String, val aliases: List<String>, val intervals: List<String>, val setNum: Int, val chroma: String)

/** What `Scale.get` answers: [empty] for a name that is no scale, else the [tonic] (empty when the name has none) and its [notes]. */
internal class TScale(
    val empty: Boolean,
    val name: String,
    val type: String,
    val tonic: String,
    val intervals: List<String>,
    val notes: List<String>,
) {
    companion object {
        val NONE = TScale(true, "", "", "", emptyList(), emptyList())
    }
}

private const val JS_SPACE = "[\\t\\n\\u000B\\u000C\\r \\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF]"
private const val JS_ANY = "[^\\n\\r\\u2028\\u2029]"

/** `tokenizeNote` of pitch-note: the regular expression is the one of the library, `$` being the end of the text, as in JavaScript. */
private val NOTE_REGEX = Regex("^([a-gA-G]?)(#{1,}|b{1,}|x{1,}|)(-?\\d*)$JS_SPACE*($JS_ANY*)\\z")

/** `REGEX` of pitch-interval: the two alternatives are anchored at the start and at the end respectively, as the library has it. */
private val INTERVAL_REGEX = Regex("^([-+]?\\d+)(d{1,4}|m|M|P|A{1,4})|(AA|A|P|M|m|d|dd)([-+]?\\d+)\\z")

private val STEP_SIZES = intArrayOf(0, 2, 4, 5, 7, 9, 11)
private val FIFTHS = intArrayOf(0, 2, 4, -1, 1, 3, 5)
private val STEPS_TO_OCTS = IntArray(7) { floor(FIFTHS[it] * 7 / 12.0).toInt() }
private val FIFTHS_TO_STEPS = intArrayOf(3, 0, 4, 1, 5, 2, 6)
private const val INTERVAL_TYPES = "PMMPPMM"
private val SEMITONE_NUMBERS = intArrayOf(1, 2, 2, 3, 3, 4, 5, 5, 6, 6, 7, 7)
private val SEMITONE_QUALITIES = arrayOf("P", "m", "M", "m", "M", "P", "d", "P", "m", "M", "m", "M")
private val FLATS = arrayOf("C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Bb", "B")
private val SHARPS = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

/** The most entries each cache keeps; a program that uses more distinct names only loses the speed. */
private const val CACHE_LIMIT = 4096

/** The most semitones a range may span, and accidentals a name written from a coordinate may have (the libraries build them without limit). */
private const val MAX_SPAN = 100_000.0

private fun <V : Any> ConcurrentHashMap<String, V>.cached(key: String, make: () -> V): V {
    get(key)?.let { return it }
    val value = make()
    if (size >= CACHE_LIMIT) clear()
    put(key, value)
    return value
}

private fun unaltered(f: Double): Double {
    val i = (f + 1) % 7
    return if (i < 0) 7 + i else i
}

private fun fill(s: String, n: Double): String {
    val count = abs(n)
    if (count > MAX_SPAN) throw CosicError("Invalid array length")
    return s.repeat(count.toInt())
}

private fun altToAcc(alt: Double): String = if (alt < 0) fill("b", -alt) else fill("#", alt)

private fun accToAlt(acc: String): Int = if (acc.isNotEmpty() && acc[0] == 'b') -acc.length else acc.length

private fun altToQuality(majorable: Boolean, alt: Double): String = when {
    alt == 0.0 -> if (majorable) "M" else "P"
    alt == -1.0 && majorable -> "m"
    alt > 0 -> fill("A", alt)
    else -> fill("d", if (majorable) alt + 1 else alt)
}

private fun qualityToAlt(majorable: Boolean, q: String): Int = when {
    (q == "M" && majorable) || (q == "P" && !majorable) -> 0
    q == "m" && majorable -> -1
    q.isNotEmpty() && q.all { it == 'A' } -> q.length
    q.isNotEmpty() && q.all { it == 'd' } -> -(if (majorable) q.length + 1 else q.length)
    else -> 0
}

/** `@tonaljs`: the notes, intervals, scales and ranges that Strudel's tonal functions use. */
internal object Tonal {
    private val notes = ConcurrentHashMap<String, TNote>()
    private val intervals = ConcurrentHashMap<String, TInterval>()
    private val scales = ConcurrentHashMap<String, TScale>()

    // ---- notes (pitch-note) ----------------------------------------------------------------------------------------------

    /** `Note.get(name)`. */
    fun note(src: String): TNote = notes.cached(src) { parseNote(src) }

    private fun parseNote(src: String): TNote {
        val m = NOTE_REGEX.find(src) ?: return TNote.NONE
        val letter = m.groupValues[1].uppercase()
        val acc = m.groupValues[2].replace("x", "##")
        val octText = m.groupValues[3]
        if (letter.isEmpty() || m.groupValues[4].isNotEmpty()) return TNote.NONE
        val step = (letter[0].code + 3) % 7
        val alt = accToAlt(acc)
        val oct: Double? = if (octText.isNotEmpty()) jsStringToNumber(octText) else null
        val fifths = (FIFTHS[step] + 7 * alt).toDouble()
        val octs = oct?.let { it - STEPS_TO_OCTS[step] - 4 * alt }
        val semi = STEP_SIZES[step] + alt
        val height = if (oct == null) jsMod(semi.toDouble(), 12.0) - 12 * 99 else semi + 12 * (oct + 1)
        return TNote(false, letter + acc + octText, letter, acc, letter + acc, step, alt, (semi + 120) % 12, height, oct, fifths, octs)
    }

    /** `note(pitch(coord))` of pitch-note: the note at the coordinate (fifths, octaves), without octave when [o] is null. */
    private fun noteAt(f: Double, o: Double?): TNote {
        val u = unaltered(f)
        if (u.isNaN() || u != floor(u)) return TNote.NONE
        val step = FIFTHS_TO_STEPS[u.toInt()]
        val alt = floor((f + 1) / 7)
        val pc = "CDEFGAB"[step] + altToAcc(alt)
        val octave = o?.let { it + 4 * alt + STEPS_TO_OCTS[step] } ?: return note(pc)
        return note(if (octave.isNaN()) pc else pc + jsNumberToString(octave))
    }

    /** `Note.transpose(note, interval)`: the name of the note [intervalName] above (or below) [noteName]; empty for what is not a note or an interval. */
    fun transpose(noteName: String, intervalName: String): String = transpose(note(noteName), intervalName)

    fun transpose(n: TNote, intervalName: String): String {
        val iv = interval(intervalName)
        if (n.empty || iv.empty) return ""
        return noteAt(n.fifths + iv.fifths, n.octs?.let { it + iv.octs }).name
    }

    /** `Note.chroma(name)`, null for what is not a note. */
    fun noteChroma(name: String): Int? = note(name).chroma

    /** `Note.midi(name)`: null for what is not a note, has no octave or lies outside of 0 to 127. */
    fun noteMidi(name: String): Int? = note(name).midi

    /**
     * `Note.enharmonic(name, dest)`: the note with the spelling of [dest] (`C#4` and `Db` give `Db4`), or, when [dest] is empty, with
     * the other spelling of the same pitch (flats for a sharp note, sharps for a flat one); empty when either is not a note or they are
     * not the same pitch class.
     */
    fun enharmonic(name: String, dest: String): String {
        val src = note(name)
        if (src.empty) return ""
        val d = note(dest.ifEmpty { midiToNoteName((src.midi ?: src.chroma)!!.let { if (it == 0) src.chroma!! else it }.toDouble(), sharps = src.alt < 0, pitchClass = true) })
        if (d.empty || d.chroma != src.chroma) return ""
        val srcOct = src.oct ?: return d.pc
        val srcChroma = src.chroma!! - src.alt
        val destChroma = d.chroma!! - d.alt
        val offset = if (srcChroma > 11 || destChroma < 0) -1 else if (srcChroma < 0 || destChroma > 11) 1 else 0
        return d.pc + jsNumberToString(srcOct + offset)
    }

    /** `midiToNoteName(midi, {sharps, pitchClass})` of tonal-midi: the name with flats (or sharps), `C4` for 60, `C` as a pitch class; empty for what is not a number. */
    fun midiToNoteName(midi: Double, sharps: Boolean = false, pitchClass: Boolean = false): String {
        if (midi.isNaN() || midi.isInfinite()) return ""
        val rounded = jsMathRound(midi)
        val index = rounded % 12
        val pc = if (index >= 0 && index == floor(index)) (if (sharps) SHARPS else FLATS)[index.toInt()] else "undefined"
        if (pitchClass) return pc
        return pc + jsNumberToString(floor(rounded / 12) - 1)
    }

    /**
     * `Range.chromatic([from, to])`: every semitone from one note to the other (descending when [to] is lower), as names with flats.
     * Entries may be names, MIDI numbers or numeric strings; anything else makes the result empty.
     */
    fun rangeChromatic(range: List<Any?>): List<String> {
        val midis = ArrayList<Double>()
        for (entry in range) {
            val midi: Double? = when (entry) {
                is Double -> entry
                is String -> jsStringToNumber(entry).let { n -> if (n >= 0 && n <= 127) n else note(entry).midi?.toDouble() }
                else -> null
            }
            if (midi != null && !midi.isNaN()) midis.add(midi)
        }
        if (range.isEmpty() || midis.size != range.size) return emptyList()
        val out = ArrayList<Double>()
        out.add(midis[0])
        for (target in midis) {
            val last = out.last()
            val span = floor(abs(target - last))
            if (span > MAX_SPAN) throw CosicError("Invalid array length")
            val count = span.toInt() + 1
            for (i in 1 until count) out.add(if (last < target) last + i else last - i)
        }
        return out.map { midiToNoteName(it) }
    }

    // ---- intervals (pitch-interval, interval) -------------------------------------------------------------------------------

    /** `Interval.get(name)`. */
    fun interval(src: String): TInterval = intervals.cached(src) { parseInterval(src) }

    private fun parseInterval(src: String): TInterval {
        val m = INTERVAL_REGEX.find(src) ?: return TInterval.NONE
        val first = m.groups[1]?.value
        val numText = if (!first.isNullOrEmpty()) first else m.groups[4]?.value ?: ""
        val q = if (!first.isNullOrEmpty()) m.groups[2]?.value ?: "" else m.groups[3]?.value ?: ""
        if (numText.isEmpty()) return TInterval.NONE
        val num = jsStringToNumber(numText)
        val step = (abs(num) - 1) % 7
        val known = step >= 0 && step <= 6 && step == floor(step)
        val type = if (known) INTERVAL_TYPES[step.toInt()] else ' '
        if (type == 'M' && q == "P") return TInterval.NONE
        val majorable = type == 'M'
        val dir = if (num < 0) -1 else 1
        val alt = qualityToAlt(majorable, q)
        val oct = floor((abs(num) - 1) / 7)
        val size = if (known) STEP_SIZES[step.toInt()].toDouble() else Double.NaN
        val semitones = dir * (size + alt + 12 * oct)
        val chroma = if (known) ((((dir * (size + alt)) % 12) + 12) % 12).toInt() else null
        val fifths = if (known) dir * (FIFTHS[step.toInt()] + 7.0 * alt) else Double.NaN
        val octs = if (known) dir * (oct - STEPS_TO_OCTS[step.toInt()] - 4.0 * alt) else Double.NaN
        return TInterval(
            false, jsNumberToString(num) + q, num, q, step, alt, dir, if (majorable) "majorable" else "perfectable",
            semitones, chroma, fifths, octs, oct,
        )
    }

    /** `coordToInterval([f, o])` of pitch-interval: the interval at the coordinate, descending when the coordinate lies below zero. */
    private fun intervalAt(f: Double, o: Double): TInterval {
        val descending = f * 7 + o * 12 < 0
        val ff = if (descending) -f else f
        val oo = if (descending) -o else o
        val u = unaltered(ff)
        if (u.isNaN() || u != floor(u)) return TInterval.NONE
        val step = FIFTHS_TO_STEPS[u.toInt()]
        val alt = floor((ff + 1) / 7)
        val calc = step + 1 + 7 * (oo + 4 * alt + STEPS_TO_OCTS[step])
        val num = if (calc == 0.0) (step + 1).toDouble() else calc
        val majorable = INTERVAL_TYPES[step] == 'M'
        return interval((if (descending) "-" else "") + jsNumberToString(num) + altToQuality(majorable, alt))
    }

    /** `Interval.fromSemitones(n)`: `3m` for 3, `-8P` for -12. A number that is not a whole number of semitones has no interval: the text `NaN`. */
    fun intervalFromSemitones(semitones: Double): String {
        val d = if (semitones < 0) -1 else 1
        val n = abs(semitones)
        val c = n % 12
        val o = floor(n / 12)
        if (c.isNaN() || c != floor(c)) return "NaN"
        val index = c.toInt()
        return jsNumberToString(d * (SEMITONE_NUMBERS[index] + 7 * o)) + SEMITONE_QUALITIES[index]
    }

    /** `Interval.semitones(name)`: null for what is not an interval. */
    fun intervalSemitones(name: String): Double? = interval(name).takeIf { !it.empty }?.semitones

    private fun combine(a: String?, b: String?, op: (Double, Double) -> Double): String? {
        if (a == null || b == null) return null
        val x = interval(a)
        val y = interval(b)
        if (x.empty || y.empty) return null
        return intervalAt(op(x.fifths, y.fifths), op(x.octs, y.octs)).name
    }

    /** `Interval.add(a, b)`: the interval that is [a] and [b] after one another; null when one is not an interval. */
    fun intervalAdd(a: String?, b: String?): String? = combine(a, b) { x, y -> x + y }

    /** `Interval.substract(a, b)`: [a] without [b], so `Interval.substract("5P", "3M")` is `3m`; null when one is not an interval. */
    fun intervalSubstract(a: String?, b: String?): String? = combine(a, b) { x, y -> x - y }

    // ---- scale types and scales (scale-type, scale, pcset) --------------------------------------------------------------

    /** The scale types of tonal in the order of its data, with the pitch sets (see [TScaleType]). */
    fun scaleTypes(): List<TScaleType> = ScaleTypes.all

    /** `ScaleType.keys()`: every name, alias, set number and chroma a scale type can be found by. */
    fun scaleTypeKeys(): Set<String> = ScaleTypes.index.keys

    /**
     * What `Scale.tokenize(name)` makes of a name: the tonic with its octave when it has one, and the scale type in lower case
     * (not lowered for a name without tonic).
     */
    fun scaleTokenize(name: String): Pair<String, String> {
        val i = name.indexOf(' ')
        val tonic = note(if (i < 0) "" else name.substring(0, i))
        if (tonic.empty) {
            val n = note(name)
            return if (n.empty) "" to name else n.name to ""
        }
        val type = name.substring(minOf(name.length, tonic.name.length + 1)).lowercase()
        return tonic.name to type
    }

    /** `Scale.get(name)`: the scale named like `C major`, `Bb4 minor pentatonic` or `major` (no tonic); [TScale.NONE] when there is none. */
    fun scaleGet(name: String): TScale = scales.cached(name) {
        val (tonicName, typeName) = scaleTokenize(name)
        val tonic = note(tonicName).name
        val type = ScaleTypes.index[typeName]
        if (type == null) {
            TScale.NONE
        } else {
            val names = if (tonic.isNotEmpty()) type.intervals.map { transpose(tonic, it) } else emptyList()
            TScale(false, if (tonic.isNotEmpty()) "$tonic ${type.name}" else type.name, type.name, tonic, type.intervals, names)
        }
    }

    /**
     * The names a scale can be called after its tonic in a program, with the spaces written as colons (`melodic:minor`), which is
     * how a scale reads in mini-notation: every name and alias of every scale type that has only characters a step of the notation can hold.
     */
    fun scaleModeNames(): List<String> {
        val allowed = Regex("[\\p{L}0-9\\-#.^_]+")
        val names = LinkedHashSet<String>()
        for (type in ScaleTypes.all) {
            for (n in listOf(type.name) + type.aliases) {
                if (allowed.matches(n.replace(" ", ""))) names.add(n.replace(' ', ':'))
            }
        }
        return names.toList()
    }

    private object ScaleTypes {
        val all: List<TScaleType>
        val index: Map<String, TScaleType>

        init {
            val types = ArrayList<TScaleType>()
            val keys = HashMap<String, TScaleType>()
            for (line in tonalResource("scale-types.txt")) {
                val (names, intervalText) = line.split('\t')
                val name = names.substringBefore('|')
                val aliases = if ('|' in names) names.substringAfter('|').split('|') else emptyList()
                val intervals = intervalText.split(' ')
                val chroma = chromaOf(intervals)
                val type = TScaleType(name, aliases, intervals, chroma.toInt(2), chroma)
                types.add(type)
                keys[name] = type
                keys[type.setNum.toString()] = type
                keys[chroma] = type
                for (alias in aliases) keys[alias] = type
            }
            all = types
            index = keys
        }

        /** `listToChroma` of tonal-pcset: the pitch classes of the intervals as twelve 0s and 1s. */
        private fun chromaOf(intervals: List<String>): String {
            val bits = CharArray(12) { '0' }
            for (i in intervals) {
                val n = note(i)
                val chroma = if (!n.empty) n.chroma else interval(i).takeIf { !it.empty }?.chroma
                if (chroma != null) bits[chroma] = '1'
            }
            return String(bits)
        }
    }
}

/** The lines of a data file of `resources/cosic/tonal`, without the comments, in their order. Lines are not trimmed: a symbol may be empty. */
internal fun tonalResource(file: String): List<String> {
    val stream = Tonal::class.java.getResourceAsStream("/cosic/tonal/$file") ?: throw IllegalStateException("missing resource cosic/tonal/$file")
    return stream.use { it.readBytes().toString(Charsets.UTF_8) }.lines().filter { it.isNotEmpty() && !it.startsWith("#") }
}

// ---- the functions of tonal.mjs ---------------------------------------------------------------------------------------------

/** `getScale(name)`: the named scale, with colons read as spaces; throws the errors of the original for a name that is no scale. */
private fun getScale(scaleName: Any?): TScale {
    if (scaleName !is String) throw CosicError("scaleName.replaceAll is not a function")
    val name = scaleName.replace(':', ' ')
    val scale = Tonal.scaleGet(name)
    if ((scale.empty && isNote(name)) || (scale.empty && scale.tonic.isEmpty())) {
        throw CosicError("Scale name $name is incomplete. Make sure to use \":\" instead of spaces, example: .scale(\"C:major\")")
    } else if (scale.empty) {
        throw CosicError("Invalid scale name \"$name\"")
    }
    return scale
}

private fun octavesInterval(octaves: Double): String = jsNumberToString((if (octaves <= 0) -1 else 1) + octaves * 7) + "P"

/** `scaleStep(step, scale)` of tonal.mjs: the note name of degree [step] (rounded up) of [scale], in octave 3 for a scale without octave. */
private fun scaleStepName(step0: Double, scale: Any?): String {
    val step = ceil(step0)
    val s = getScale(scale)
    val tonic = Tonal.note(s.tonic.ifEmpty { "C" })
    val len = s.intervals.size
    val octaveOffset = floor(step / len)
    val index = jsMod(step, len.toDouble())
    val interval = Tonal.intervalAdd(
        if (index == floor(index) && index >= 0 && index < len) s.intervals[index.toInt()] else null,
        octavesInterval(octaveOffset),
    )
    return Tonal.transpose(tonic.pc + jsNumberToString(tonic.oct ?: 3.0), interval ?: "")
}

/** `scaleOffset(scale, offset, note)` of tonal.mjs: [note] moved by [offset] steps of the scale, an octave boundary being each note that starts with C. */
private fun scaleOffset(scale: Any?, offset: Double, note: Any?): String {
    val names = getScale(scale).notes.map { Tonal.note(it).pc }
    if (offset.isNaN()) throw CosicError("scale offset \"${jsNumberToString(offset)}\" not a number")
    val from = Tonal.note(note as? String ?: "")
    val noteIndex = names.indexOf(from.pc)
    if (noteIndex == -1) throw CosicError("note \"${jsToString(note)}\" is not in scale \"${jsToString(scale)}\"")
    var i = noteIndex.toDouble()
    var o = from.oct ?: 3.0
    var n = from.pc
    val direction = Math.signum(offset)
    val steps = ceil(abs(offset))
    if (steps > MAX_COUNT * 1000.0) throw CosicError(TOO_DENSE)
    if (steps > 0) chargeQuery(steps.toLong())
    while (abs(i - noteIndex) < abs(offset)) {
        i += direction
        val index = jsMod(i, names.size.toDouble()).toInt()
        if (direction < 0 && n.firstOrNull() == 'C') o += direction
        n = names[index]
        if (direction > 0 && n.firstOrNull() == 'C') o += direction
    }
    return n + jsNumberToString(o)
}

private val STEP_REGEX = Regex("(-?\\d+)([#bsf]*)")

/** `[number, offset]` of `_convertStepToNumberAndOffset`: a step such as `3`, `-2` or `4##`, as its number and the semitones its accidentals add. */
private fun convertStepToNumberAndOffset(step0: Any?): Pair<Double, Int> {
    var asNumber = jsToNumber(step0)
    var offset = 0
    if (asNumber.isNaN()) {
        val step = jsToString(step0)
        val match = STEP_REGEX.matchEntire(step)
            ?: throw CosicError("invalid scale step \"$step\", expected number or integer with optional # b suffixes")
        asNumber = jsStringToNumber(match.groupValues[1])
        offset = getAccidentalsOffset(match.groupValues[2])
    }
    return asNumber to offset
}

private class ScaleMidis(val midis: List<Double>, val names: List<String>)

private val scaleMidisCache = ConcurrentHashMap<String, ScaleMidis>()

/** The notes of a scale at octave 0 and one octave above its root, as names and MIDI numbers (`scaleToMidisAndNotes`). */
private fun scaleMidisAndNotes(scaleName: Any?): ScaleMidis {
    val key = scaleName as? String
    if (key != null) scaleMidisCache[key]?.let { return it }
    val scale = getScale(scaleName)
    val pc = Tonal.note(scale.tonic).pc
    val names = (scale.intervals + "8P").map { Tonal.transpose(pc + "0", it) }
    val result = ScaleMidis(names.map { noteToMidi(it) }, names)
    if (key != null) {
        if (scaleMidisCache.size >= CACHE_LIMIT) scaleMidisCache.clear()
        scaleMidisCache[key] = result
    }
    return result
}

/** `_getNearestScaleNote(scaleName, note)`: the note of the scale that is nearest to [note], the higher of two. */
private fun nearestScaleNote(scaleName: Any?, note: Any?): String {
    val noteMidi = if (note is String) noteToMidi(note) else jsToNumber(note)
    val scale = scaleMidisAndNotes(scaleName)
    val octaveDiff = floor((noteMidi - scale.midis[0]) / 12)
    val index = nearestNumberIndex(noteMidi, scale.midis.map { it + 12 * octaveDiff }, true)
    return Tonal.transpose(scale.names[index], Tonal.intervalFromSemitones(12 * octaveDiff))
}

/** `typeof value === 'object'` for a hap value: a control map, a list, `null`, a fraction. */
private fun isObjectValue(v: Any?): Boolean = jsTypeof(v) == "object"

/** The own enumerable keys of an object value, as `Object.keys` lists them. */
private fun objectKeys(v: Any?): List<String> = when (v) {
    is Map<*, *> -> v.keys.map { it.toString() }
    is List<*> -> v.indices.map { it.toString() }
    else -> emptyList()
}

@Suppress("UNCHECKED_CAST")
private fun asMap(v: Any?): Map<String, Any?> = v as? Map<String, Any?> ?: emptyMap()

/** `null` or `undefined`, what `??` looks through. */
private fun isNullish(v: Any?): Boolean = v == null || v === Undefined

/** The property [key] of a control map as JavaScript reads it: `undefined` when it is not there. */
private fun property(map: Map<String, Any?>, key: String): Any? = if (map.containsKey(key)) map[key] else Undefined

/** `scale.flat().join(' ')` for a scale given as a list (the `C:major` of mini-notation); any other value is the scale. */
private fun scaleName(scale: Any?): Any? {
    if (scale !is List<*>) return scale
    val flat = scale.flatMap { if (it is List<*>) it else listOf(it) }
    return flat.joinToString(" ") { if (isNullish(it)) "" else jsToString(it) }
}

/**
 * `scale(name)` (tonal.mjs:278-329): turns the numbers of the pattern into notes of the scale, or the notes into the nearest
 * notes of the scale, and remembers the scale in the context of the haps for `scaleTranspose`. A value is a note name or a
 * number (or a number with `#`, `b`, `s` or `f` after it), or a control map holding one in `note`, `n` or `value`. A scale
 * degree that cannot be turned into a note is logged and dropped; a note that cannot be quantized fails the query.
 */
internal fun Pattern.scale(scale: Any?): Pattern {
    val scaleText = scaleName(scale)
    return withHaps { haps, _ -> haps.mapNotNull { scaleHap(it, scaleText) } }
}

private fun scaleHap(hap: Hap, scale: Any?): Hap? {
    val value = hap.value
    val isObject = isObjectValue(value)
    if (isObject && value == null) throw CosicError("Cannot destructure property 'note' of 'hVal' as it is null.")
    val values: Map<String, Any?> = if (isObject) asMap(value) else linkedMapOf("n" to value)
    val note = property(values, "note")
    val n = property(values, "n")
    val noteOrStep = if (!isNullish(note)) note else if (!isNullish(n)) n else property(values, "value")
    if (noteOrStep === Undefined) {
        val keys = if (isObject) objectKeys(value) else listOf("n")
        logger("[tonal] Invalid value format for 'scale'. Value must contain n, note, or value but received keys [${keys.joinToString(", ")}]", "error", null)
        return hap
    }
    val others = LinkedHashMap(values)
    others.remove("note")
    others.remove("n")
    others.remove("value")
    val scaleNote: Any?
    if (isNote(noteOrStep)) {
        scaleNote = nearestScaleNote(scale, noteOrStep)
    } else {
        try {
            val (number, offset) = convertStepToNumberAndOffset(noteOrStep)
            val named: Any? = if (jsTruthy(others["anchor"])) stepInNamedScale(number, scale, others["anchor"]) else scaleStepName(number, scale)
            // Note.transpose of a MIDI number (the anchored form) is not a note, and gives the empty name
            scaleNote = if (offset != 0) Tonal.transpose(named as? String ?: "", Tonal.intervalFromSemitones(offset.toDouble())) else named
        } catch (e: CosicError) {
            errorLogger(e)
            return null
        }
    }
    val result = if (isObject) jsMerge(others, mapOf("note" to scaleNote)) else scaleNote
    return Hap(hap.whole, hap.part, result, hap.context.copy(extra = hap.context.extra + ("scale" to scale)))
}

/**
 * `transpose(amount)` (tonal.mjs:115-153): moves the notes by a number of semitones or an interval (`3m`, `5P`, `-2M`); a number
 * stays a number, a note name stays a name and, given an interval, is spelled to fit it. A value that is no note is left alone, with a warning.
 */
internal fun Pattern.transpose(amount: Any?): Pattern = withHap { hap -> transposeHap(hap, amount) }

private fun transposeHap(hap: Hap, amount: Any?): Hap {
    val value = hap.value
    if (isNullish(value)) throw CosicError("Cannot read properties of ${jsToString(value)} (reading 'note')")
    val noteProperty = (value as? Map<*, *>)?.get("note")
    val note = if (isNullish(noteProperty)) value else noteProperty
    val isObject = isObjectValue(value)
    fun result(target: Any?): Hap = hap.withValue { if (isObject) jsMerge(asMap(value), mapOf("note" to target)) else target }
    if (note is Double) {
        val semitones: Double = when (amount) {
            is Double -> amount
            is String -> Tonal.intervalSemitones(amount)?.takeIf { it != 0.0 && !it.isNaN() } ?: 0.0
            else -> Double.NaN
        }
        return result(note + semitones)
    }
    if (note !is String || !isNote(note)) {
        logger("[tonal] transpose: not a note \"${jsToString(note)}\"", "warning", null)
        return hap
    }
    val asNumber = jsToNumber(amount)
    val interval = if (!asNumber.isNaN()) Tonal.intervalFromSemitones(asNumber) else jsToString(amount)
    val n = Tonal.note(note)
    // `n.oct = 3` fails on what tonal does not read as a note: the shared empty note is frozen
    if (n.empty) throw CosicError("Cannot add property oct, object is not extensible")
    val target = if (n.oct == null) {
        // a note without octave keeps none when the result stays in octave 3
        val withOctave = Tonal.note(n.pc + "3")
        val shifted = Tonal.note(Tonal.transpose(withOctave, interval))
        if (shifted.oct == 3.0) Tonal.transpose(n, interval) else Tonal.transpose(withOctave, interval)
    } else {
        Tonal.transpose(n, interval)
    }
    return result(target)
}

/**
 * `scaleTranspose(offset)` (tonal.mjs:176-193): moves the notes by [offset] steps of the scale an earlier `scale` set. Fails
 * on a hap that has no scale or that is not a note, and on a note that is not in the scale.
 */
internal fun Pattern.scaleTranspose(offset: Any?): Pattern = withHap { hap ->
    val scale = hap.context.extra["scale"]
    if (!jsTruthy(scale)) throw CosicError("can only use scaleTranspose after .scale")
    val value = hap.value
    if (isObjectValue(value)) {
        if (value == null) throw CosicError("Cannot read properties of null (reading 'note')")
        val map = asMap(value)
        hap.withValue { jsMerge(map, mapOf("note" to scaleOffset(scale, jsToNumber(offset), map["note"]))) }
    } else {
        if (value !is String) throw CosicError("can only use scaleTranspose with notes")
        hap.withValue { scaleOffset(scale, jsToNumber(offset), value) }
    }
}

// ---- arpeggios (pattern.mjs:1055-1074) ----------------------------------------------------------------------------------------

/**
 * `arpWith(func)`: groups the haps that start and end together (a chord) and plays what [func] makes of each group, a list of
 * haps: `func` returns a hap, or a pattern of them, whose values then replace the group's, at the times of the pattern.
 * The function of `haps => haps[2]` plays the third note of every chord.
 */
internal fun Pattern.arpWith(func: (List<Hap>) -> Any?): Pattern =
    collect()
        .fmap { group ->
            @Suppress("UNCHECKED_CAST")
            reify(func(group as List<Hap>))
        }
        .innerJoin()
        .withHap { h ->
            val chosen = h.value as? Hap ?: throw CosicError("Cannot read properties of undefined (reading 'locations')")
            Hap(h.whole, h.part, chosen.value, h.combineContext(chosen))
        }

/** `arp(indices)`: plays the notes of each chord one after another, in the order [indices] gives (a pattern of numbers, counted modulo the number of notes). */
internal fun Pattern.arp(indices: Any?): Pattern = arpWith { haps ->
    reify(indices).fmap { i ->
        val index = jsMod(jsToNumber(i), haps.size.toDouble())
        if (index == floor(index) && index >= 0 && index < haps.size) haps[index.toInt()] else Undefined
    }
}
