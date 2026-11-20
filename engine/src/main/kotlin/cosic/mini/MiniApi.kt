package cosic.mini

import cosic.pattern.CosicError
import cosic.pattern.Pattern
import cosic.pattern.sequence

/*
 * The functions of Strudel's mini package that the transpiler's output and the interpreter call
 * (packages/mini/mini.mjs): `m`, `mini` and `getLeafLocations`.
 */

/** The pattern of the mini-notation [src] (without its quotes), whose first character is at [baseOffset] in the program: every atom hap carries the range of its text. */
internal fun parseMini(src: String, baseOffset: Int): Pattern {
    val code = "\"" + src + "\""
    return patternifyLocated(mini2ast(code, rangeStart = baseOffset - 1), code, baseOffset - 1)
}

/**
 * `m(str, offset)`: [str] is the mini-notation without its quotes, [offset] the position of its opening quote in the
 * program. Every atom hap carries the source range of its text.
 */
internal fun m(str: String, offset: Int): Pattern = parseMini(str, offset + 1)

/**
 * `mini(...strings)`: the strings as mini-notation, one after the other in a cycle. The haps carry no source ranges: the
 * strings are not part of the program text, so a range in one would point at the wrong code (Strudel highlights it all
 * the same), and an error does not point at the notation either, only at the call.
 */
internal fun mini(vararg s: String): Pattern = sequence(s.map { parseWithoutLocations(it) })

private fun parseWithoutLocations(src: String): Pattern {
    val code = "\"" + src + "\""
    try {
        return patternifyAST(mini2ast(code), code, null, -1)
    } catch (e: CosicError) {
        throw CosicError(e.message)
    }
}

/**
 * `getLeafLocations(quoted, start)`: the `[from, to]` offsets of every atom of [quoted], which is the notation with its
 * quotes, in the order Strudel visits them; [start] is the position of the opening quote in the program. A parse error
 * is thrown; [userCode], the program, is only what the line number of that error is counted in.
 */
internal fun getLeafLocations(quoted: String, start: Int, userCode: String? = null): List<IntArray> {
    val ast = mini2ast(quoted, start, userCode ?: quoted)
    return collectLeaves(ast).map { getLeafLocation(quoted, it, start) }
}
