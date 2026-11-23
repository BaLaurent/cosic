package com.octopusman.cosic.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/** Where the lines the selection touches start. */
private fun TextFieldValue.blockStart() = text.lastIndexOf('\n', selection.min - 1) + 1

/** Where they end (before the line break). A selection that ends right after a line break stops at the line before it. */
private fun TextFieldValue.blockEnd(): Int {
    val last = if (!selection.collapsed && text[selection.max - 1] == '\n') selection.max - 1 else selection.max
    return text.indexOf('\n', last).let { if (it < 0) text.length else it }
}

private fun TextRange.shift(by: Int) = TextRange(start + by, end + by)

/** The lines the selection touches, repeated right under themselves; the selection goes to the copy. */
fun TextFieldValue.duplicateLines(): TextFieldValue {
    val end = blockEnd()
    val block = text.substring(blockStart(), end)
    return TextFieldValue(text.substring(0, end) + "\n" + block + text.substring(end), selection.shift(block.length + 1))
}

/** The lines the selection touches, swapped with the line above them; the selection follows. Nothing on the first line. */
fun TextFieldValue.moveLinesUp(): TextFieldValue {
    val start = blockStart()
    val end = blockEnd()
    if (start == 0) return this
    val aboveStart = text.lastIndexOf('\n', start - 2) + 1
    val above = text.substring(aboveStart, start - 1)
    val moved = text.substring(0, aboveStart) + text.substring(start, end) + "\n" + above + text.substring(end)
    return TextFieldValue(moved, selection.shift(-(above.length + 1)))
}

/** The lines the selection touches, swapped with the line below them; the selection follows. Nothing on the last line. */
fun TextFieldValue.moveLinesDown(): TextFieldValue {
    val start = blockStart()
    val end = blockEnd()
    if (end == text.length) return this
    val belowEnd = text.indexOf('\n', end + 1).let { if (it < 0) text.length else it }
    val below = text.substring(end + 1, belowEnd)
    val moved = text.substring(0, start) + below + "\n" + text.substring(start, end) + text.substring(belowEnd)
    return TextFieldValue(moved, selection.shift(below.length + 1))
}

/** The lines the selection touches, and one line break with them; the cursor is where they were. */
fun TextFieldValue.deleteLines(): TextFieldValue {
    val start = blockStart()
    val end = blockEnd()
    return when {
        end < text.length -> TextFieldValue(text.removeRange(start, end + 1), TextRange(start))
        start > 0 -> TextFieldValue(text.removeRange(start - 1, end), TextRange(start - 1))
        else -> TextFieldValue("", TextRange(0))
    }
}

/** A marker put into (delta > 0) or taken out of (delta < 0) the text at [at]. */
private class MarkerEdit(val at: Int, val delta: Int)

private fun Int.through(edits: List<MarkerEdit>): Int = this + edits.sumOf { edit ->
    if (edit.delta > 0) {
        if (this >= edit.at) edit.delta else 0
    } else {
        -minOf(-edit.delta, maxOf(0, this - edit.at))
    }
}

/**
 * The lines the selection touches commented with `// ` at their shared indentation (blank lines are left alone), or, when every
 * non-blank line is a comment already, uncommented (the marker and one space after it go). The selection keeps its text.
 */
fun TextFieldValue.toggleLineComment(): TextFieldValue {
    val start = blockStart()
    val lines = text.substring(start, blockEnd()).split('\n')
    val code = lines.filter { it.isNotBlank() }
    if (code.isEmpty()) return this
    val uncomment = code.all { it.trimStart().startsWith("//") }
    val shared = code.minOf { it.length - it.trimStart().length }
    val edits = mutableListOf<MarkerEdit>()
    var lineStart = start
    val changed = lines.map { line ->
        val own = line.length - line.trimStart().length
        val result = when {
            line.isBlank() -> line
            uncomment -> {
                val removed = if (line.startsWith("// ", own)) 3 else 2
                edits += MarkerEdit(lineStart + own, -removed)
                line.substring(0, own) + line.substring(own + removed)
            }
            else -> {
                edits += MarkerEdit(lineStart + shared, 3)
                line.substring(0, shared) + "// " + line.substring(shared)
            }
        }
        lineStart += line.length + 1
        result
    }
    val end = start + lines.sumOf { it.length + 1 } - 1
    val newText = text.substring(0, start) + changed.joinToString("\n") + text.substring(end)
    return TextFieldValue(newText, TextRange(selection.start.through(edits), selection.end.through(edits)))
}
