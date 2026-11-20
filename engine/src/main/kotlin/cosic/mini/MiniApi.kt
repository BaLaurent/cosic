package cosic.mini

import cosic.pattern.Pattern
import cosic.pattern.sequence

/*
 * The functions of Strudel's mini package that the transpiler's output and the interpreter call, for the language
 * before Strudel's mini-notation (Task 2.6 rewrites this package and keeps these signatures).
 */

/**
 * `m(str, offset)`: [str] is the mini-notation without its quotes, [offset] the position of its opening quote in the
 * program. Every atom hap carries the source range of its text.
 */
internal fun m(str: String, offset: Int): Pattern = parseMini(str, offset + 1)

/** `mini(...strings)`: the strings as mini-notation, one after the other in a cycle; the haps carry no source ranges. */
internal fun mini(vararg s: String): Pattern =
    sequence(s.map { parseMini(it, 0).stripContext() })

/**
 * `getLeafLocations(quoted, start)`: the `[from, to]` offsets of every atom of [quoted], which is the notation with its
 * quotes; [start] is the position of the opening quote in the program.
 */
internal fun getLeafLocations(quoted: String, start: Int): List<IntArray> {
    val inner = if (quoted.length >= 2) quoted.substring(1, quoted.length - 1) else quoted
    return parseMiniLeaves(inner, start + 1)
}
