package cosic.pattern

import java.util.Collections
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/*
 * Chords and voicings: `packages/tonal/tonleiter.mjs` and `voicings.mjs` of Strudel with the `chord-voicings` package the
 * deprecated `voicings` stands on, and the dictionaries of `ireal.mjs`. A chord symbol (`Am7`, `C^7`, `Bb7b9/F`) is turned into
 * the notes of a voicing near an anchor note: `voicing()` makes MIDI numbers or note names for the `chord`, `dictionary`,
 * `anchor`, `mode`, `offset`, `n` and `octaves` controls of a hap, `voicings(dictionary)` the notes of the smoothest voice leading.
 */

// ---- tonleiter.mjs --------------------------------------------------------------------------------------------------------

private val PITCH_CLASSES = "c db d eb e f gb g ab a bb b".split(' ')
private val FLATS = arrayOf("C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Bb", "B")
private val SHARPS = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
private val CHORD_REGEX = Regex("([A-G][b#]*)([^/]*)/?([A-G][b#]*)?")

/**
 * `tokenizeChord(chord)`: the root, the symbol and the bass of `C^7`, `Bb7b9/F` (the bass is null when there is none), or nothing at all
 * for what is not a chord. A chord that is not a string fails; a falsy one is not a chord.
 */
internal fun tokenizeChord(chord: Any?): List<String?> {
    if (!jsTruthy(chord)) return emptyList()
    if (chord !is String) throw CosicError("chord.match is not a function")
    val match = CHORD_REGEX.matchEntire(chord) ?: return emptyList()
    return listOf(match.groups[1]?.value, match.groups[2]?.value, match.groups[3]?.value)
}

/** The root and the symbol of a chord, null for both when it is none (`const [root, symbol] = tokenizeChord(chord)`). */
private fun rootAndSymbol(chord: Any?): Pair<String?, String?> = tokenizeChord(chord).let { it.getOrNull(0) to it.getOrNull(1) }

/** `pc2chroma(pc)`: the pitch class of a note name without octave, 0 to 11 for the usual spellings (`Cb` is -1, `B#` is 12). A root that is missing fails. */
internal fun pc2chroma(pc: String?): Double {
    if (pc == null) throw CosicError("Cannot read properties of undefined (reading 'split')")
    var chroma = PITCH_CLASSES.indexOf(pc.substring(0, 1).lowercase()).toDouble()
    for (sign in pc.drop(1)) chroma += if (sign == 'b') -1.0 else if (sign == '#') 1.0 else Double.NaN
    return chroma
}

/** `x2midi(x, defaultOctave)`: a number as it is, a note name as its MIDI number; anything else is `undefined`, which computes as NaN. */
internal fun x2midi(x: Any?, defaultOctave: Int): Double = when (x) {
    is Double -> x
    is String -> noteToMidi(x, defaultOctave)
    else -> Double.NaN
}

/** `step2semitones(x)`: a number as it is, an interval name as its semitones (NaN for what is neither). */
internal fun step2semitones(x: Any?): Double {
    val n = jsToNumber(x)
    if (!n.isNaN()) return n
    return Tonal.intervalSemitones(jsToString(x)) ?: Double.NaN
}

/**
 * `midi2note(midi, sharp)` of tonleiter.mjs: the name of a MIDI note with flats, or with sharps when [sharp]: 61 is `Db4` or `C#4`. A
 * number that is not a whole number from 0 up has no pitch class, and `undefined + octave` is NaN, so it is NaN.
 */
internal fun midi2note(midi: Double, sharp: Boolean): Any {
    val index = midi % 12
    if (!(index >= 0 && index == floor(index))) return Double.NaN
    return (if (sharp) SHARPS else FLATS)[index.toInt()] + jsNumberToString(floor(midi / 12) - 1)
}

/**
 * `nearestNumberIndex(target, numbers, preferHigher)`: the index of the number nearest to [target]; a tie goes to the first,
 * or to the last when [preferHigher] (which only picks the higher number if they are sorted).
 */
internal fun nearestNumberIndex(target: Double, numbers: List<Double>, preferHigher: Boolean): Int {
    var bestIndex = 0
    var bestDiff = Double.POSITIVE_INFINITY
    for ((i, s) in numbers.withIndex()) {
        val diff = abs(s - target)
        if ((!preferHigher && diff < bestDiff) || (preferHigher && diff <= bestDiff)) {
            bestIndex = i
            bestDiff = diff
        }
    }
    return bestIndex
}

/**
 * `scaleStep(notes, offset, octaves)` of tonleiter.mjs: the note [offset] steps along [notes] (names or numbers, as a MIDI number),
 * wrapping around with [octaves] octaves for each time round; negative offsets count from the end.
 */
internal fun scaleStep(notes: List<Any?>, offset: Double, octaves: Double = 1.0): Double {
    val midis = notes.map { if (it is String) noteToMidi(it) else jsToNumber(it) }
    val octOffset = floor(offset / midis.size) * octaves * 12
    val index = jsMod(offset, midis.size.toDouble())
    return (if (index == floor(index) && index >= 0 && index < midis.size) midis[index.toInt()] else Double.NaN) + octOffset
}

private val scaleStepsCache = java.util.concurrent.ConcurrentHashMap<String, List<Double>>()

/** The semitones of the intervals of a scale type, counted from C (`scaleSteps`); empty for a name that is no scale. */
private fun stepsOf(scaleName: String): List<Double> =
    scaleStepsCache.getOrPut(scaleName) { Tonal.scaleGet("C $scaleName").intervals.map { step2semitones(it) } }

/**
 * `stepInNamedScale(step, scale, anchor, preferHigher)` of tonleiter.mjs: the MIDI number of degree [step] of the scale (`D major`, with
 * spaces), counted from the degree nearest to the [anchor] note when there is one. The scale needs a tonic, and the result is
 * NaN for a name that is no scale.
 */
internal fun stepInNamedScale(step0: Double, scale: Any?, anchor: Any?, preferHigher: Boolean = false): Double {
    var step = step0
    val (root, scaleName) = if (scale is String) Tonal.scaleTokenize(scale) else "" to ""
    val rootMidi = x2midi(root, 3)
    val rootChroma = rootMidi % 12
    val steps = stepsOf(scaleName)
    var transpose = rootMidi
    if (jsTruthy(anchor)) {
        val a = x2midi(anchor, 3)
        val anchorDiff = jsMod((a % 12) - rootChroma, 12.0)
        step += nearestNumberIndex(anchorDiff, steps, preferHigher)
        transpose = a - anchorDiff
    }
    val octOffset = floor(step / steps.size) * 12
    val index = jsMod(step, steps.size.toDouble())
    return (if (index == floor(index) && index >= 0 && index < steps.size) steps[index.toInt()] else Double.NaN) + transpose + octOffset
}

private val MODE_TARGETS = mapOf(
    "below" to false, "duck" to false, "above" to true, "root" to true, "oldabove" to true, "oldroot" to true,
)
private val MODE_MULTIPLIERS = mapOf(
    "below" to 1.0, "duck" to 1.0, "above" to -1.0, "root" to -1.0, "oldabove" to 1.0, "oldroot" to 1.0,
)

/** The voicings of [symbol] in a dictionary as lists of semitones (`'3M 7m'` and `'0 3 7'` alike). */
private fun voicingsOf(dictionary: Any?, symbol: String?): List<List<Double>> {
    if (dictionary !is Map<*, *>) throw CosicError("Cannot read properties of undefined (reading '${symbol}')")
    val entry = dictionary[symbol] as? List<*> ?: throw CosicError("dictionary[symbol].map is not a function")
    return entry.map { voicing ->
        val steps: List<Any?> = if (voicing is String) voicing.split(' ') else voicing as? List<*> ?: throw CosicError("voicing.map is not a function")
        steps.map { step2semitones(it) }
    }
}

/**
 * `renderVoicing({chord, dictionary, offset, n, mode, anchor, octaves})` of tonleiter.mjs: the notes (names with flats, or a single
 * MIDI number when [n] is set) of the voicing of [chord] that [mode] aligns to [anchor] (`below`: its top note is at or under it;
 * `above`, `root`, `duck`; the `old...` ones keep the behaviour of an earlier version); [offset] moves to the next voicing of the
 * dictionary, [n] picks a note of it as if it were a scale, [octaves] octaves for each time round. [dictionary] maps a chord symbol to
 * its voicings. A chord that is not in the dictionary, or an unknown mode, is an error. An argument that is `undefined` has its default.
 */
internal fun renderVoicing(
    chord: Any?,
    dictionary: Any?,
    offset: Any? = Undefined,
    n: Any? = Undefined,
    mode: Any? = Undefined,
    anchor: Any? = Undefined,
    octaves: Any? = Undefined,
): List<Any?> {
    val offsetValue = if (offset === Undefined) 0.0 else jsToNumber(offset)
    val modeName = if (mode === Undefined) "below" else mode
    val octavesValue = if (octaves === Undefined) 1.0 else jsToNumber(octaves)
    val (root, symbol) = rootAndSymbol(chord)
    val rootChroma = pc2chroma(root)
    val anchorSource = if (anchor === Undefined) "c5" else anchor
    val anchorNote = (anchorSource as? Map<*, *>)?.get("note")
    val anchorMidi = x2midi(if (jsTruthy(anchorNote)) anchorNote else anchorSource, 4)
    val anchorChroma = anchorMidi % 12
    val voicings = voicingsOf(dictionary, symbol)
    if (modeName !is String || modeName !in MODE_TARGETS) throw CosicError("modeTarget[mode] is not a function")
    val multiplier = MODE_MULTIPLIERS.getValue(modeName)
    val firstTarget = MODE_TARGETS.getValue(modeName)
    fun targetStep(v: List<Double>): Double = (if (firstTarget) v.firstOrNull() else v.lastOrNull()) ?: Double.NaN

    var minDistance: Double? = null
    var bestIndex = 0
    val chromaDiffs = voicings.mapIndexed { i, v ->
        val diff = jsMod((anchorChroma - targetStep(v) - rootChroma) * multiplier, 12.0)
        if (minDistance == null || diff < minDistance!!) {
            minDistance = diff
            bestIndex = i
        }
        diff * multiplier
    }
    if (modeName == "root" || modeName == "oldroot") bestIndex = 0

    val octDiff = ceil(offsetValue / voicings.size) * 12
    val indexWithOffset = jsMod(bestIndex + offsetValue, voicings.size.toDouble())
    if (!(indexWithOffset == floor(indexWithOffset) && indexWithOffset >= 0 && indexWithOffset < voicings.size)) {
        throw CosicError("Cannot read properties of undefined (reading 'slice')")
    }
    val voicing = voicings[indexWithOffset.toInt()]
    val anchorBase = anchorMidi - chromaDiffs[indexWithOffset.toInt()] + octDiff
    val target = targetStep(voicing)
    val voicingMidi = voicing.map { anchorBase - target + it }
    var notes: List<Any?> = voicingMidi.map { midi2note(it, false) }
    if (modeName == "duck") notes = notes.filterIndexed { i, _ -> voicingMidi[i] != anchorMidi }
    if (n !== Undefined) return listOf(scaleStep(notes, jsToNumber(n), octavesValue))
    return notes
}

// ---- chord-voicings -------------------------------------------------------------------------------------------------------

/**
 * `voicingsInRange(chord, dictionary, range)` of chord-voicings: every voicing of the chord whose notes lie in [range] (the
 * lowest and the highest note, `D3` and `A4` when it is null), the intervals of each voicing of [dictionary] being moved up from
 * every start note that has the right pitch class. Each voicing is its note names, spelled to fit the chord.
 */
internal fun voicingsInRange(chord: Any?, dictionary: Any?, range: List<Any?>?): List<List<String>> {
    val (tonic, symbol) = rootAndSymbol(chord)
    val entry = (dictionary as? Map<*, *>)?.get(symbol)
    if (!jsTruthy(entry)) return emptyList()
    val voicings = (entry as? List<*> ?: throw CosicError("dictionary[symbol].map is not a function")).map {
        (it as? String ?: throw CosicError("intervals.split is not a function")).split(' ')
    }
    val limits = range ?: listOf("D3", "A4")
    val notesInRange = Tonal.rangeChromatic(limits)
    val top = (limits.getOrNull(1) as? String)?.let { Tonal.noteMidi(it) }
    val result = ArrayList<List<String>>()
    for (voicing in voicings) {
        // transpose the intervals relative to the first one (3m 5P becomes 1P 3M)
        val relative = voicing.map { Tonal.intervalSubstract(it, voicing[0]) }
        val bottom = Tonal.transpose(tonic ?: "", voicing[0])
        val bottomChroma = Tonal.noteChroma(bottom)
        val starts = notesInRange
            .filter { bottomChroma != null && Tonal.noteChroma(it) == bottomChroma }
            .filter { (Tonal.noteMidi(Tonal.transpose(it, relative.last() ?: "")) ?: 0) <= (top ?: 0) }
            .map { Tonal.enharmonic(it, bottom) }
        for (start in starts) result.add(relative.map { Tonal.transpose(start, it ?: "") })
    }
    return result
}

/**
 * `dictionaryVoicing({chord, dictionary, range, picker: minTopNoteDiff, lastVoicing})` of chord-voicings: of the voicings of [chord]
 * in [range] the one whose top note is nearest to that of [last] (the first when there is no last voicing); empty when there is none.
 */
internal fun bestVoicing(chord: Any?, dictionary: Any?, range: List<Any?>?, last: List<String>?): List<String> {
    val found = voicingsInRange(chord, dictionary, range)
    return if (found.isEmpty()) emptyList() else minTopNoteDiff(found, last)
}

/** `minTopNoteDiff(voicings, lastVoicing)`: the voicing whose top note is nearest to the top note of the last one (the first when there is none); the earlier of two equals. */
private fun minTopNoteDiff(voicings: List<List<String>>, last: List<String>?): List<String> {
    if (last == null) return voicings[0]
    val lastTop = (last.lastOrNull()?.let { Tonal.noteMidi(it) } ?: 0).toDouble()
    fun diff(v: List<String>) = abs(lastTop - (v.lastOrNull()?.let { Tonal.noteMidi(it) } ?: 0))
    var best = voicings[0]
    for (current in voicings) if (diff(current) < diff(best)) best = current
    return best
}

// ---- voicings.mjs -----------------------------------------------------------------------------------------------------------

/**
 * The voicing dictionaries a program can use by name and what the voicing functions remember (`voicingRegistry`, the default
 * dictionary and the last voicing of `voicings`). Like the originals they are global to the program that uses them: a
 * dictionary added by one evaluation is there for the next, and `setDefaultVoicings` stays, until [reset].
 *
 * The last voicing is the exception: `voicings` picks the voicing nearest to the one it picked before, and that only means something
 * along one run of queries, so each thread keeps its own (the audio thread plays a program from the start, whatever a check of the
 * program on another thread did before) and [resetLast] makes every thread start again (a new program, `resetVoicings()`).
 */
internal object Voicings {
    private val lock = Any()
    private val entries = LinkedHashMap<String, MutableMap<String, Any?>>()
    private var defaultName: Any? = "ireal"

    /** The last voicing `voicings` picked on one thread, with the [generation] of [resetLast] it belongs to. */
    private class Last(var generation: Long, var voicing: List<String>?)

    private val generation = java.util.concurrent.atomic.AtomicLong()
    private val last = ThreadLocal.withInitial { Last(generation.get(), null) }

    init {
        loadBuiltIns()
    }

    /** Puts the six dictionaries of Strudel in the registry, with the aliases `voicings.mjs` adds to the two of ireal. */
    private fun loadBuiltIns() {
        val simple = parseSections("ireal.txt")
        val ireal = simple.getValue("simple")
        val complex = simple.getValue("complex")
        // no symbol is a major chord; `-` is `m`, `^` is `M` and `+` is `aug` (only the first of a symbol is replaced)
        alias("^", "", ireal, complex)
        for (symbol in jsKeyOrder(ireal.keys)) {
            if ('-' in symbol) alias(symbol, symbol.replaceFirst("-", "m"), complex, ireal)
            if ('^' in symbol) alias(symbol, symbol.replaceFirst("^", "M"), complex, ireal)
            if ('+' in symbol) alias(symbol, symbol.replaceFirst("+", "aug"), complex, ireal)
        }
        val classic = parseSections("voicing-dictionaries.txt")
        put("lefthand", linkedMapOf("dictionary" to classic.getValue("lefthand"), "range" to listOf("F3", "A4"), "mode" to "below", "anchor" to "a4"))
        put("triads", linkedMapOf("dictionary" to classic.getValue("triads"), "mode" to "below", "anchor" to "a4"))
        put("guidetones", linkedMapOf("dictionary" to classic.getValue("guidetones"), "mode" to "above", "anchor" to "a4"))
        put("legacy", linkedMapOf("dictionary" to classic.getValue("legacy"), "mode" to "below", "anchor" to "a4"))
        put("ireal", linkedMapOf("dictionary" to ireal))
        put("ireal-ext", linkedMapOf("dictionary" to complex))
    }

    /** Back to what Strudel starts with: only its six dictionaries, as they were, the `ireal` default and no last voicing. */
    fun restore() {
        synchronized(lock) { entries.clear() }
        loadBuiltIns()
        reset()
    }

    private fun parseSections(file: String): Map<String, LinkedHashMap<String, Any?>> {
        val sections = LinkedHashMap<String, LinkedHashMap<String, Any?>>()
        var current: LinkedHashMap<String, Any?>? = null
        for (line in tonalResource(file)) {
            if (line.startsWith("[") && line.endsWith("]")) {
                current = LinkedHashMap<String, Any?>().also { sections[line.substring(1, line.length - 1)] = it }
            } else {
                val tab = line.indexOf('\t')
                current!![line.substring(0, tab)] = line.substring(tab + 1).split('|')
            }
        }
        for (section in sections.values) {
            val ordered = jsOrdered(section)
            if (ordered !== section) {
                val copy = LinkedHashMap(ordered)
                section.clear()
                section.putAll(copy)
            }
        }
        return sections
    }

    /** `voicingAlias(symbol, alias, sets)` for the dictionaries of this file. */
    private fun alias(symbol: String, alias: String, vararg sets: MutableMap<String, Any?>) {
        for (set in sets) set[alias] = set[symbol]
    }

    /**
     * `voicingRegistry[name]`: the entry (`dictionary`, `range`, `mode`, `anchor`) of a dictionary, null when there is none. The entry
     * and its dictionary are the registry's own maps, which may be changed (they are synchronized: the scheduler reads them while a program changes them).
     */
    fun entry(name: Any?): MutableMap<String, Any?>? = synchronized(lock) { entries[jsToString(name)] }

    /** The names of the dictionaries in the order they were added. */
    fun names(): List<String> = synchronized(lock) { entries.keys.toList() }

    /** `Object.assign(voicingRegistry, {[name]: entry})`: the registry keeps copies of [entry] and of its dictionary. */
    fun put(name: Any?, entry: Map<String, Any?>) {
        val copy = synchronizedCopy(entry)
        (copy["dictionary"] as? Map<*, *>)?.let { dictionary ->
            @Suppress("UNCHECKED_CAST")
            copy["dictionary"] = synchronizedCopy(dictionary as Map<String, Any?>)
        }
        synchronized(lock) { entries[jsToString(name)] = copy }
    }

    /** A copy of [map] that may be read and written from any thread (every call is one atomic step). */
    fun synchronizedCopy(map: Map<String, Any?>): MutableMap<String, Any?> = Collections.synchronizedMap(LinkedHashMap(map))

    /** Removes the entry [name] (`delete voicingRegistry[name]`). */
    fun remove(name: String): Boolean = synchronized(lock) { entries.remove(name) != null }

    /** The default dictionary of `voicing` (`ireal` unless `setDefaultVoicings` changed it). */
    val defaultDictionary: Any? get() = synchronized(lock) { defaultName }

    /** `setDefaultVoicings(dict)`: returns [dict]. */
    fun setDefault(dict: Any?): Any? {
        synchronized(lock) { defaultName = dict }
        return dict
    }

    /** `addVoicings(name, dictionary, range = ['F3', 'A4'])`: a dictionary with the lowest and highest note its voicings may have (for `voicings`). */
    fun add(name: Any?, dictionary: Any?, range: Any? = Undefined) {
        put(name, linkedMapOf("dictionary" to dictionary, "range" to if (range === Undefined) listOf("F3", "A4") else range))
    }

    /** `registerVoicings(name, dictionary, options = {})`: a dictionary with options (`range`, `mode`, `anchor`). */
    fun register(name: Any?, dictionary: Any?, options: Any? = Undefined) {
        val entry = LinkedHashMap<String, Any?>()
        entry["dictionary"] = dictionary
        @Suppress("UNCHECKED_CAST")
        (options as? Map<String, Any?>)?.let { entry.putAll(it) }
        put(name, jsOrdered(entry))
    }

    /** `setVoicingRange(name, range)`: the range of an existing dictionary (the mode and anchor it had are gone, as in the original). */
    fun setRange(name: Any?, range: Any?) {
        val existing = entry(name) ?: throw CosicError("Cannot read properties of undefined (reading 'dictionary')")
        add(name, existing["dictionary"], range)
    }

    /** `resetVoicings()`: forgets the last voicing and goes back to the `ireal` dictionary. */
    fun reset() {
        resetLast()
        setDefault("ireal")
    }

    /** Makes `voicings` start from the first voicing again, on every thread: what a program evaluation does. */
    fun resetLast() {
        generation.incrementAndGet()
    }

    /**
     * `getVoicing(chord, dictionaryName, lastVoicing)`, then remembers what it found: the voicing of the chord in the dictionary
     * [dictionaryName] that has the smoothest voice leading from the voicing before it (empty when there is none).
     */
    fun nextVoicing(chord: Any?, dictionaryName: Any?): List<String> {
        val entry = entry(dictionaryName)
            ?: throw CosicError("Cannot destructure property 'dictionary' of 'voicingRegistry[dictionaryName]' as it is undefined.")
        @Suppress("UNCHECKED_CAST")
        val range = entry["range"] as? List<Any?>
        val state = last.get()
        val now = generation.get()
        if (state.generation != now) {
            state.generation = now
            state.voicing = null
        }
        val picked = bestVoicing(chord, entry["dictionary"], range, state.voicing)
        state.voicing = picked
        return picked
    }
}

/**
 * `voicings(dictionary)` (voicings.mjs:149-156, deprecated): turns chord symbols into the notes of the smoothest voice leading,
 * as a stack of note names. The last voicing is remembered by the program, not by the pattern: every `voicings` call and every
 * cycle continues from the one before it.
 */
internal fun Pattern.voicings(dictionary: Any?): Pattern =
    fmap { chord -> stack(Voicings.nextVoicing(chord, dictionary)) }.outerJoin()

/**
 * `rootNotes(octave)` (voicings.mjs:169-176): the root of each chord as a note in [octave] (`C^7` in 2 is `C2`); a control map with a
 * `chord` gives a map of just that `note`.
 */
internal fun Pattern.rootNotes(octave: Any?): Pattern = fmap { value ->
    val chordProperty = (value as? Map<*, *>)?.get("chord")
    val chord = if (jsTruthy(chordProperty)) chordProperty else value
    if (chord !is String) throw CosicError("chord.match is not a function")
    val root = ROOT_REGEX.matchEntire(chord)?.groupValues?.get(1) ?: throw CosicError("Cannot read properties of null (reading '1')")
    val note = root + jsToString(octave)
    if (jsTruthy(chordProperty)) linkedMapOf("note" to note) else note
}

private val ROOT_REGEX = Regex("([a-gA-G][b#]?)[^\\n\\r\\u2028\\u2029]*")

/**
 * `voicing()` (voicings.mjs:203-222): turns the chords of the pattern (a chord symbol, or a control map with `chord` and perhaps
 * `dictionary`, `anchor`, `mode`, `offset`, `n`, `octaves`) into stacks of notes: names with flats, or a single MIDI number when the
 * map has `n`; the other controls of the map stay. A chord that cannot be voiced is logged and plays nothing.
 */
internal fun Pattern.voicing(): Pattern = fmap { value -> voicingOf(value) }.outerJoin()

private val VOICING_KEYS = listOf("dictionary", "chord", "anchor", "offset", "mode", "n", "octaves")

private fun voicingOf(input: Any?): Pattern {
    val value: Any? = if (input is String) linkedMapOf("chord" to input) else input
    if (value == null || value === Undefined) {
        throw CosicError("Cannot destructure property 'dictionary' of 'value' as it is ${jsToString(value)}.")
    }
    @Suppress("UNCHECKED_CAST")
    val map: Map<String, Any?> = value as? Map<String, Any?> ?: emptyMap()
    fun property(key: String): Any? = if (map.containsKey(key)) map[key] else Undefined
    val dictionary = property("dictionary").let { if (it === Undefined) Voicings.defaultDictionary else it }
    val chord = property("chord")
    val rest = LinkedHashMap(map)
    for (key in VOICING_KEYS) rest.remove(key)
    try {
        // the mode and anchor of the dictionary's entry are overridden by the (possibly undefined) ones of the hap: only its dictionary is used
        val dictionaryOfEntry = if (dictionary is String) Voicings.entry(dictionary)?.get("dictionary") else dictionary
        val notes = renderVoicing(chord, dictionaryOfEntry, property("offset"), property("n"), property("mode"), property("anchor"), property("octaves"))
        return stack(notes).setNote().setWith(pure(rest))
    } catch (e: Exception) {
        // whatever goes wrong with a chord (an unknown symbol, a chord that is no string, a mode that does not exist) makes it a chord that plays nothing
        logger("[voicing]: unknown chord \"${jsToString(chord)}\"", null, null)
        return silence
    }
}

/** `pat.note()`: the values of the pattern as the `note` control. */
private fun Pattern.setNote(): Pattern = Controls.applyControl(checkNotNull(Controls.method("note")), Undefined, this)
