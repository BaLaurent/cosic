package cosic.pattern

private val NOTE_NAME = Regex("^([a-gA-G])([#b]?)(-?\\d+)?$")

private val SEMITONES = mapOf('c' to 0, 'd' to 2, 'e' to 4, 'f' to 5, 'g' to 7, 'a' to 9, 'b' to 11)

private const val DEFAULT_OCTAVE = 3

private val SCALES: Map<String, IntArray> = mapOf(
    "major" to intArrayOf(0, 2, 4, 5, 7, 9, 11),
    "ionian" to intArrayOf(0, 2, 4, 5, 7, 9, 11),
    "minor" to intArrayOf(0, 2, 3, 5, 7, 8, 10),
    "aeolian" to intArrayOf(0, 2, 3, 5, 7, 8, 10),
    "dorian" to intArrayOf(0, 2, 3, 5, 7, 9, 10),
    "phrygian" to intArrayOf(0, 1, 3, 5, 7, 8, 10),
    "lydian" to intArrayOf(0, 2, 4, 6, 7, 9, 11),
    "mixolydian" to intArrayOf(0, 2, 4, 5, 7, 9, 10),
    "locrian" to intArrayOf(0, 1, 3, 5, 6, 8, 10),
    "harmonicminor" to intArrayOf(0, 2, 3, 5, 7, 8, 11),
    "melodicminor" to intArrayOf(0, 2, 3, 5, 7, 9, 11),
    "majorpentatonic" to intArrayOf(0, 2, 4, 7, 9),
    "minorpentatonic" to intArrayOf(0, 3, 5, 7, 10),
    "blues" to intArrayOf(0, 3, 5, 6, 7, 10),
    "chromatic" to IntArray(12) { it },
    "wholetone" to intArrayOf(0, 2, 4, 6, 8, 10),
)

/**
 * The MIDI number of a note name such as `c4`, `eb3` or `F#2`, or null when [name] is not one.
 * `c4` is 60; a missing octave means octave 3, so `c` is 48.
 */
internal fun noteToMidi(name: String): Int? {
    val match = NOTE_NAME.matchEntire(name) ?: return null
    val semitone = SEMITONES.getValue(match.groupValues[1].lowercase()[0])
    val accidental = when (match.groupValues[2]) {
        "#" -> 1
        "b" -> -1
        else -> 0
    }
    val octaveText = match.groupValues[3]
    val octave = if (octaveText.isEmpty()) DEFAULT_OCTAVE else octaveText.toIntOrNull() ?: return null
    return (octave + 1) * 12 + semitone + accidental
}

/**
 * The MIDI note of scale [degree] (0 is the root, negative degrees go below it). [name] is `Root:mode`,
 * for example `C:major` or `eb2:minor:pentatonic`; the root is a note name, octave 3 when it has none.
 * Throws [CosicError] for an unknown scale.
 */
internal fun scaleNote(degree: Int, name: String): Double {
    val parts = name.split(':')
    val root = noteToMidi(parts[0].trim())
    val mode = parts.drop(1).joinToString("").lowercase().replace(" ", "").replace("_", "")
    val intervals = SCALES[mode]
    if (root == null || intervals == null) throw CosicError("Unknown scale '$name'")
    val size = intervals.size
    return (root + intervals[Math.floorMod(degree, size)] + 12 * Math.floorDiv(degree, size)).toDouble()
}
