package cosic.pattern

/*
 * `drawLine` of Strudel (packages/core/drawLine.mjs): a debugging aid that draws a pattern as text.
 */

/**
 * Draws [pat] as a string, one cycle after the other until the first line is at least [chars] long, with a character for
 * every slot of time that the shortest event divides a cycle into: the first character of the value of an event at
 * its onset, `-` while it is held, `.` where nothing plays and `|` between cycles. Events that overlap are drawn on
 * lines of their own. Only meant for single-character values, otherwise the slots are out of line.
 *
 * `drawLine("0 [1 2 3]", 10)` is `|0--123|0--123`. A pattern with no events with an onset has no shortest event, and a
 * continuous pattern has no wholes to draw; both are errors, as they are in Strudel. Cosic limits the line to
 * [MAX_COUNT] characters and a cycle to as many slots, which is far more than anyone can read.
 */
internal fun drawLine(pat: Pattern, chars: Double = 60.0): String {
    if (chars > MAX_COUNT) throw CosicError("drawLine: at most $MAX_COUNT characters, got ${jsNumberToString(chars)}")
    var cycle = 0L
    var pos = Rational.ZERO
    var lines = mutableListOf("")
    // the "reference" empty line, which is copied into the extra lines
    var emptyLine = ""
    while (lines[0].length < chars) {
        val haps = pat.queryArcLogged(Rational(cycle), Rational(cycle + 1))
        val durations = haps.filter { it.hasOnset() }.map { it.duration }
        val charFraction = Rational.gcdOf(durations)
            ?: throw CosicError("Cannot read properties of undefined (reading 'inverse')")
        // the number of character slots in this cycle
        val totalSlots = Rational.ONE / charFraction
        if (totalSlots.toDouble() > MAX_COUNT) throw CosicError("drawLine: a cycle of more than $MAX_COUNT slots")
        lines = lines.mapTo(ArrayList()) { it + "|" }
        emptyLine += "|"
        var i = 0
        while (i < totalSlots.toDouble()) {
            val begin = pos
            val end = pos + charFraction
            val matches = haps.filter { hap ->
                val whole = hap.whole ?: throw CosicError("Cannot read properties of undefined (reading 'begin')")
                whole.begin <= begin && whole.end >= end
            }
            val missingLines = matches.size - lines.size
            if (missingLines > 0) repeat(missingLines) { lines.add(emptyLine) }
            lines = lines.mapIndexedTo(ArrayList()) { n, line ->
                val hap = matches.getOrNull(n)
                when {
                    hap == null -> "$line."
                    hap.whole!!.begin == begin -> line + jsToString(hap.value)
                    else -> "$line-"
                }
            }
            emptyLine += "."
            pos = end
            i++
        }
        cycle++
    }
    return lines.joinToString("\n")
}
