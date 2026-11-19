package cosic.mini

import cosic.SourceRange
import cosic.pattern.CosicError
import cosic.pattern.MAX_COUNT
import cosic.pattern.Pattern
import cosic.pattern.Rational
import cosic.pattern.degradeBy
import cosic.pattern.euclid
import cosic.pattern.euclidRot
import cosic.pattern.fast
import cosic.pattern.fastcat
import cosic.pattern.innerJoin
import cosic.pattern.pure
import cosic.pattern.randomChoice
import cosic.pattern.silence
import cosic.pattern.slow
import cosic.pattern.slowcat
import cosic.pattern.stack
import cosic.pattern.timecat
import kotlin.math.roundToInt

/**
 * Parses mini-notation: the rhythm language inside double quotes, e.g. `"bd*2 [sd cp] ~ hh(3,8)"`.
 *
 * ```
 * list     := sequence ((',' sequence)+ | ('|' sequence)+)?     stack | random choice per cycle
 * sequence := step*                                              a step takes a share of the cycle
 * step     := term modifier* | '_' | '!'                         '_' stretches, '!' repeats the previous step
 * term     := atom | '~' | '-' | '[' list ']' | '<' list '>' | '{' list '}' ('%' factor)?
 * modifier := '*' factor | '/' factor | '!' INT? | '@' NUMBER | '?' NUMBER? | '(' sequence ',' sequence (',' sequence)? ')'
 * factor   := number atom | '[' list ']' | '<' list '>'
 * ```
 *
 * Every atom hap carries the [SourceRange] of its text, shifted by [baseOffset] so the ranges point into the
 * program the string came from. Numbers inside modifiers are plain constants and carry no range; patterned
 * modifier arguments (`bd*<2 3>`) add the ranges of their atoms after the step's own.
 * Errors are [CosicError]s with the range of the offending character.
 */
internal fun parseMini(src: String, baseOffset: Int): Pattern<Any> = MiniParser(src, baseOffset).parse()

private const val PIPE_SEED_BASE = 1000
private const val SEQUENCE_END = ",|]>})"
private const val ATOM_SYMBOLS = "#.-_:^'"
private val NUMBER = Regex("-?(\\d+\\.?\\d*|\\.\\d+)")

/** One step of a sequence; [number] is set while the step is still a bare numeric atom. */
private class Step(val pattern: Pattern<Any>, val weight: Rational, val number: Double?)

private class Term(val pattern: Pattern<Any>, val number: Double? = null)

/** The sequences between one pair of brackets; [pipeSeed] is set when they are alternatives (`|`). */
private class Group(val sequences: List<List<Step>>, val pipeSeed: Int?) {
    fun build(sequence: (List<Step>) -> Pattern<Any>): Pattern<Any> {
        val built = sequences.map(sequence)
        return if (pipeSeed != null) randomChoice(built, pipeSeed) else stack(built)
    }
}

/** A number argument of a modifier: a plain number, or a pattern of numbers. */
private sealed interface Arg {
    class Constant(val value: Double) : Arg
    class Patterned(val pattern: Pattern<Any>) : Arg
}

private fun Arg.join(f: (Double) -> Pattern<Any>): Pattern<Any> = when (this) {
    is Arg.Constant -> f(value)
    is Arg.Patterned -> pattern.fmap { f(numberOf(it)) }.innerJoin()
}

private fun numberOf(value: Any): Double =
    value as? Double ?: throw CosicError("Mini-notation: expected a number, got '$value'")

private fun timecatSteps(steps: List<Step>): Pattern<Any> = when (steps.size) {
    0 -> silence
    1 -> steps[0].pattern
    else -> timecat(steps.map { it.weight to it.pattern })
}

private fun isAtomChar(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c in ATOM_SYMBOLS

private class MiniParser(private val src: String, private val base: Int) {
    private var pos = 0
    private var questionMarks = 0
    private var pipeGroups = 0

    fun parse(): Pattern<Any> {
        val group = parseGroup()
        skipSpace()
        if (pos < src.length) unexpected(pos)
        return group.build(::timecatSteps)
    }

    // ---- structure ----------------------------------------------------------------------

    private fun parseGroup(): Group {
        val sequences = ArrayList<List<Step>>()
        sequences.add(parseSequence())
        var separator = ' '
        var pipeSeed: Int? = null
        while (true) {
            skipSpace()
            val c = peek() ?: break
            if (c != ',' && c != '|') break
            if (separator != ' ' && separator != c) unexpected(pos)
            if (c == '|' && pipeSeed == null) pipeSeed = PIPE_SEED_BASE + pipeGroups++
            separator = c
            pos++
            sequences.add(parseSequence())
        }
        return Group(sequences, pipeSeed)
    }

    private fun parseSequence(): List<Step> {
        val steps = ArrayList<Step>()
        while (true) {
            skipSpace()
            val c = peek() ?: return steps
            when {
                c in SEQUENCE_END -> return steps
                c == '!' -> {
                    if (steps.isEmpty()) unexpected(pos)
                    steps.add(steps[steps.lastIndex])
                    pos++
                }
                else -> parseStep(steps)
            }
        }
    }

    private fun parseStep(steps: MutableList<Step>) {
        if (isAtomChar(src[pos])) {
            val end = atomEnd(pos)
            if ((pos until end).all { src[it] == '_' }) {
                stretch(steps, end - pos)
                pos = end
                return
            }
        }
        val term = parseTerm()
        var pattern = term.pattern
        var number = term.number
        var weight = Rational.ONE
        var copies = 1
        while (true) {
            when (peek()) {
                '*' -> {
                    pos++
                    val factor = parseFactor()
                    val inner = pattern
                    pattern = factor.join { inner.fast(Rational.of(it)) }
                    number = null
                }
                '/' -> {
                    pos++
                    val factor = parseFactor()
                    val inner = pattern
                    pattern = factor.join { inner.slow(Rational.of(it)) }
                    number = null
                }
                '!' -> {
                    pos++
                    copies = readInt() ?: (copies + 1)
                }
                '@' -> {
                    pos++
                    weight = parseWeight()
                }
                '?' -> {
                    pos++
                    pattern = pattern.degradeBy(parseProbability(), questionMarks++)
                    number = null
                }
                '(' -> {
                    pattern = parseEuclid(pattern)
                    number = null
                }
                else -> break
            }
        }
        repeat(copies) { steps.add(Step(pattern, weight, number)) }
    }

    /** `_` adds its length to the weight of the step before it. */
    private fun stretch(steps: MutableList<Step>, by: Int) {
        if (steps.isEmpty()) unexpected(pos)
        val last = steps[steps.lastIndex]
        steps[steps.lastIndex] = Step(last.pattern, last.weight + Rational(by.toLong()), last.number)
    }

    // ---- terms --------------------------------------------------------------------------

    private fun parseTerm(): Term {
        val c = peek() ?: unexpected(pos)
        return when {
            c == '~' -> {
                pos++
                Term(silence)
            }
            c == '[' -> bracketed(']') { group -> group.build(::timecatSteps) }
            c == '<' -> bracketed('>') { group -> group.build { steps -> slowcat(steps.map { it.pattern }) } }
            c == '{' -> parsePolymeter()
            isAtomChar(c) -> parseAtom()
            else -> unexpected(pos)
        }
    }

    private fun bracketed(close: Char, build: (Group) -> Pattern<Any>): Term {
        val open = pos
        pos++
        val group = parseGroup()
        expect(close, open)
        return Term(build(group))
    }

    private fun parsePolymeter(): Term {
        val open = pos
        pos++
        val group = parseGroup()
        expect('}', open)
        val steps: Arg = if (peek() == '%') {
            pos++
            parseFactor()
        } else {
            Arg.Constant((group.sequences.firstOrNull()?.size ?: 0).toDouble())
        }
        return Term(group.build { sequence -> polymeter(sequence, steps) })
    }

    /** Plays the steps of one sequence at the pace of a sequence with [base] steps per cycle. */
    private fun polymeter(sequence: List<Step>, base: Arg): Pattern<Any> {
        if (sequence.isEmpty()) return silence
        val steps = fastcat(sequence.map { it.pattern })
        return base.join { count -> steps.fast(Rational.of(count) / Rational(sequence.size.toLong())) }
    }

    private fun parseAtom(): Term {
        val start = pos
        pos = atomEnd(start)
        val text = src.substring(start, pos)
        if (text.all { it == '-' }) return Term(silence)
        val number = if (NUMBER.matches(text)) text.toDouble() else null
        val value: Any = number ?: text
        val at = listOf(SourceRange(base + start, base + pos))
        return Term(pure(value).withLocations(at), number)
    }

    // ---- modifier arguments -------------------------------------------------------------

    private fun parseFactor(): Arg {
        val c = peek() ?: unexpected(pos)
        if (c == '[') return Arg.Patterned(bracketed(']') { group -> group.build(::timecatSteps) }.pattern)
        if (c == '<') return Arg.Patterned(bracketed('>') { group -> group.build { steps -> slowcat(steps.map { it.pattern }) } }.pattern)
        if (!isAtomChar(c)) unexpected(pos)
        val start = pos
        val text = src.substring(start, atomEnd(start))
        if (!NUMBER.matches(text)) fail("unexpected '$text'", start, text.length)
        val value = text.toDouble()
        try {
            Rational.of(value)
        } catch (e: IllegalArgumentException) {
            fail("number out of range", start, text.length)
        }
        pos += text.length
        return Arg.Constant(value)
    }

    private fun parseWeight(): Rational {
        val start = pos
        val text = readNumberText()
        if (text.isEmpty()) unexpected(start)
        return try {
            Rational.of(text.toDoubleOrNull() ?: fail("unexpected '$text'", start, text.length))
        } catch (e: IllegalArgumentException) {
            fail("number out of range", start, text.length)
        }
    }

    private fun parseProbability(): Double {
        val start = pos
        val text = readNumberText()
        if (text.isEmpty()) return 0.5
        return text.toDoubleOrNull() ?: fail("unexpected '$text'", start, text.length)
    }

    private fun parseEuclid(pattern: Pattern<Any>): Pattern<Any> {
        val open = pos
        pos++
        val hits = parseArg()
        expectComma()
        val steps = parseArg()
        skipSpace()
        val rotation = if (peek() == ',') {
            pos++
            parseArg()
        } else {
            null
        }
        expect(')', open)
        return hits.join { k ->
            steps.join { n ->
                if (rotation == null) {
                    pattern.euclid(count(k), count(n))
                } else {
                    rotation.join { r -> pattern.euclidRot(count(k), count(n), count(r)) }
                }
            }
        }
    }

    private fun parseArg(): Arg {
        val steps = parseSequence()
        val number = steps.singleOrNull()?.number
        return if (number != null) Arg.Constant(number) else Arg.Patterned(timecatSteps(steps))
    }

    // ---- scanning -----------------------------------------------------------------------

    private fun peek(): Char? = if (pos < src.length) src[pos] else null

    private fun skipSpace() {
        while (pos < src.length && src[pos].isWhitespace()) pos++
    }

    private fun atomEnd(from: Int): Int {
        var end = from
        while (end < src.length && isAtomChar(src[end])) end++
        return end
    }

    private fun readInt(): Int? {
        val start = pos
        while (pos < src.length && src[pos] in '0'..'9') pos++
        if (pos == start) return null
        return src.substring(start, pos).toIntOrNull()?.takeIf { it <= MAX_COUNT } ?: fail("number out of range", start, pos - start)
    }

    private fun count(value: Double): Int {
        if (!(Math.abs(value) <= MAX_COUNT)) throw CosicError("Mini-notation: number out of range")
        return value.roundToInt()
    }

    private fun readNumberText(): String {
        val start = pos
        while (pos < src.length && (src[pos] in '0'..'9' || src[pos] == '.')) pos++
        return src.substring(start, pos)
    }

    private fun expect(close: Char, open: Int) {
        skipSpace()
        if (pos >= src.length) fail("missing '$close'", open)
        if (src[pos] != close) unexpected(pos)
        pos++
    }

    private fun expectComma() {
        skipSpace()
        if (peek() != ',') unexpected(pos)
        pos++
    }

    private fun unexpected(at: Int): Nothing =
        if (at >= src.length) fail("unexpected end of pattern", maxOf(src.length - 1, 0)) else fail("unexpected '${src[at]}'", at)

    private fun fail(message: String, at: Int, length: Int = 1): Nothing =
        throw CosicError("Mini-notation: $message", SourceRange(base + at, base + at + length))
}
