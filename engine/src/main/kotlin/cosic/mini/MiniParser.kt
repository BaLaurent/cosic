package cosic.mini

import cosic.SourceRange
import cosic.pattern.CosicError
import cosic.pattern.TOO_DEEP

/*
 * The mini-notation parser. It is Strudel's grammar (packages/mini/krill.pegjs) read the way Peggy 4.2, the generator of
 * Strudel's parser (packages/mini/krill-parser.js), reads it, rule by rule: ordered choice, greedy repetition that does
 * not backtrack into the loop, a rule that fails puts the position back, and the actions of a rule run as soon as the
 * rule has matched. That last point is what numbers the random streams of `?` and `|` (see [KrillParser.seed]): an
 * action that ran for a rule that a later rule threw away still counted.
 *
 * Errors read like Peggy's: `Expected "(", "*", or whitespace but "x" found.`, listing what could have been read at the
 * furthest position that was tried. A named rule (`ws`, `number`, `step_char`) reports its name, not what it is made of.
 */

/** The longest nesting of brackets, groups and operator arguments the parser follows before it gives up. */
private const val MAX_DEPTH = 200

private fun literal(text: String) = "\"" + literalEscape(text) + "\""

private val E_NUMBER = "number"
private val E_WHITESPACE = "whitespace"
private val E_STEP_CHAR = "a letter, a number, \"-\", \"#\", \".\", \"^\", \"_\""
private val E_END = "end of input"
private val E_DOT = literal(".")
private val E_MINUS = literal("-")
private val E_PLUS_MINUS = "[+\\-]"
private val E_ZERO = literal("0")
private val E_ONE_NINE = "[1-9]"
private val E_DIGIT = "[0-9]"
private val E_E = "[eE]"
private val E_COMMA = literal(",")
private val E_PIPE = literal("|")
private val E_QUOTE = "[\"']"
private val E_LBRACKET = literal("[")
private val E_RBRACKET = literal("]")
private val E_LBRACE = literal("{")
private val E_RBRACE = literal("}")
private val E_PERCENT = literal("%")
private val E_LT = literal("<")
private val E_GT = literal(">")
private val E_WEIGHT = "[@_]"
private val E_BANG = literal("!")
private val E_LPAREN = literal("(")
private val E_RPAREN = literal(")")
private val E_SLASH = literal("/")
private val E_STAR = literal("*")
private val E_QUESTION = literal("?")
private val E_COLON = literal(":")
private val E_RANGE = literal("..")
private val E_CARET = literal("^")
private val E_CAT = literal("cat")
private val E_COMMENT = literal("//")
private val E_NOT_NEWLINE = "[^\\n]"

/** What a `slice_op` read; it becomes an [Op] of its element when the element is complete (the `slice_with_ops` action). */
private sealed class Pending {
    class Weight(val amount: Double?) : Pending()
    class Replicate(val amount: Double?) : Pending()
    class Bjorklund(val pulse: ElementNode, val step: ElementNode, val rotation: ElementNode?) : Pending()
    class Stretch(val fast: Boolean, val amount: MiniNode) : Pending()
    class Degrade(val amount: Double?) : Pending()
    class Tail(val element: MiniNode) : Pending()
    class Range(val element: MiniNode) : Pending()
}

/** What `stack_tail`, `choose_tail` and `dot_tail` give: the sequences after the first one, and how they are put together. */
private class Tail(val alignment: Alignment, val list: List<PatternNode>, val seed: Int?)

/** A failed parse: the message, and the position of the furthest failure in the quoted code. */
internal class MiniSyntaxError(message: String, val position: Int) : Exception(message, null, false, false)

internal class KrillParser(private val input: String) {
    private var pos = 0
    private var maxFailPos = 0
    private val maxFailExpected = ArrayList<String>()
    private var silentFails = 0
    private var depth = 0

    /** The number of the next random stream: `?` and `|` draw on `rand.early(0.0003 * seed)`. It starts at 0 for each parse. */
    private var seed = 0

    // ---- the entry ------------------------------------------------------------------------------------------------

    /**
     * `krill.parse(input)` for an input that is a quoted notation: what `statement` makes of it. A statement is
     * `mini_definition / command`; the other forms start with a word that the quote at the start of the input cannot be,
     * so they fail at position 0 and, since the furthest failure is what is reported, are not tried here.
     */
    fun parse(): PatternNode {
        // mini_or_group = cat / mini: `cat` fails on its first literal
        fail(E_CAT)
        val result = mini()
        if (result != null) {
            ws()
            while (comment()) {
                // a comment after the notation is part of the input, and ignored
            }
            if (pos == input.length) return result
            fail(E_END)
        }
        throw MiniSyntaxError(message(), maxFailPos)
    }

    private fun message(): String {
        val found = if (maxFailPos < input.length) input[maxFailPos] else null
        val descriptions = maxFailExpected.sorted().distinct()
        val expected = when (descriptions.size) {
            1 -> descriptions[0]
            2 -> descriptions[0] + " or " + descriptions[1]
            else -> descriptions.dropLast(1).joinToString(", ") + ", or " + descriptions.last()
        }
        return "Expected $expected but ${if (found == null) "end of input" else "\"" + literalEscape(found.toString()) + "\""} found."
    }

    // ---- reporting failures -----------------------------------------------------------------------------------------

    /** `peg$fail`: remembers that [description] was expected at the current position, if that is the furthest one so far. */
    private fun fail(description: String) {
        if (pos < maxFailPos) return
        if (pos > maxFailPos) {
            maxFailPos = pos
            maxFailExpected.clear()
        }
        maxFailExpected.add(description)
    }

    private fun literal(text: String, description: String): Boolean {
        if (input.startsWith(text, pos)) {
            pos += text.length
            return true
        }
        if (silentFails == 0) fail(description)
        return false
    }

    private inline fun charClass(description: String, test: (Char) -> Boolean): Boolean {
        if (pos < input.length && test(input[pos])) {
            pos++
            return true
        }
        if (silentFails == 0) fail(description)
        return false
    }

    // ---- numbers ----------------------------------------------------------------------------------------------------

    /** `number`: `-? int frac? exp?`, read by `parseFloat`; null for no number. */
    private fun number(): Double? {
        silentFails++
        val start = pos
        var result: Double? = null
        literal("-", E_MINUS)
        if (int()) {
            frac()
            exp()
            result = input.substring(start, pos).toDouble()
        } else {
            pos = start
        }
        silentFails--
        if (result == null && silentFails == 0) fail(E_NUMBER)
        return result
    }

    private fun digit(): Boolean = charClass(E_DIGIT) { it in '0'..'9' }

    private fun int(): Boolean {
        if (literal("0", E_ZERO)) return true
        if (charClass(E_ONE_NINE) { it in '1'..'9' }) {
            while (digit()) {
                // digits
            }
            return true
        }
        return false
    }

    private fun frac(): Boolean {
        val start = pos
        if (literal(".", E_DOT) && digit()) {
            while (digit()) {
                // digits
            }
            return true
        }
        pos = start
        return false
    }

    private fun exp(): Boolean {
        val start = pos
        if (charClass(E_E) { it == 'e' || it == 'E' }) {
            charClass(E_PLUS_MINUS) { it == '+' || it == '-' }
            if (digit()) {
                while (digit()) {
                    // digits
                }
                return true
            }
        }
        pos = start
        return false
    }

    // ---- delimiters -------------------------------------------------------------------------------------------------

    /** `ws`: it cannot fail, but a named rule reports its name wherever it stops. */
    private fun ws() {
        silentFails++
        while (pos < input.length && isWhitespace(input[pos])) pos++
        silentFails--
        if (silentFails == 0) fail(E_WHITESPACE)
    }

    private fun isWhitespace(c: Char): Boolean = c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == '\u00A0'

    /** `ws "x" ws` for the delimiter [text]. */
    private fun delimiter(text: String, description: String): Boolean {
        val start = pos
        ws()
        if (literal(text, description)) {
            ws()
            return true
        }
        pos = start
        return false
    }

    private fun comma() = delimiter(",", E_COMMA)

    private fun pipe() = delimiter("|", E_PIPE)

    private fun dot() = delimiter(".", E_DOT)

    private fun quote(): Boolean = charClass(E_QUOTE) { it == '"' || it == '\'' }

    private fun comment(): Boolean {
        val start = pos
        if (!literal("//", E_COMMENT)) {
            pos = start
            return false
        }
        while (charClass(E_NOT_NEWLINE) { it != '\n' }) {
            // the rest of the line
        }
        return true
    }

    // ---- steps and slices -------------------------------------------------------------------------------------------

    /** `step_char`: a letter, a digit, or one of `~ - # . ^ _`. A named rule: the character class inside it is silent. */
    private fun stepChar(): Char? {
        silentFails++
        val c = if (pos < input.length && MiniLetters.isStepChar(input[pos])) input[pos++] else null
        silentFails--
        if (c == null && silentFails == 0) fail(E_STEP_CHAR)
        return c
    }

    /** `step`: the characters of one atom, with the whitespace around them. A lone `.` or `_` is not an atom. */
    private fun step(): AtomNode? {
        val start = pos
        ws()
        val chars = StringBuilder()
        var c = stepChar()
        if (c == null) {
            pos = start
            return null
        }
        while (c != null) {
            chars.append(c)
            c = stepChar()
        }
        ws()
        val text = chars.toString()
        if (text == "." || text == "_") {
            pos = start
            return null
        }
        return AtomNode(text, start, pos)
    }

    private fun subCycle(): MiniNode? {
        val start = pos
        ws()
        if (!literal("[", E_LBRACKET)) {
            pos = start
            return null
        }
        ws()
        val inner = stackOrChoose()
        if (inner == null) {
            pos = start
            return null
        }
        ws()
        if (!literal("]", E_RBRACKET)) {
            pos = start
            return null
        }
        ws()
        return inner
    }

    private fun polymeter(): MiniNode? {
        val start = pos
        ws()
        if (!literal("{", E_LBRACE)) {
            pos = start
            return null
        }
        ws()
        val inner = polymeterStack()
        if (inner == null) {
            pos = start
            return null
        }
        ws()
        if (!literal("}", E_RBRACE)) {
            pos = start
            return null
        }
        val stepsPerCycle = polymeterSteps()
        ws()
        inner.stepsPerCycle = stepsPerCycle
        return inner
    }

    private fun polymeterSteps(): MiniNode? {
        val start = pos
        if (!literal("%", E_PERCENT)) {
            pos = start
            return null
        }
        val slice = slice()
        if (slice == null) {
            pos = start
            return null
        }
        return slice
    }

    private fun slowSequence(): MiniNode? {
        val start = pos
        ws()
        if (!literal("<", E_LT)) {
            pos = start
            return null
        }
        ws()
        val inner = polymeterStack()
        if (inner == null) {
            pos = start
            return null
        }
        ws()
        if (!literal(">", E_GT)) {
            pos = start
            return null
        }
        ws()
        inner.alignment = Alignment.POLYMETER_SLOWCAT
        return inner
    }

    /** `slice`: `step / sub_cycle / polymeter / slow_sequence`. */
    private fun slice(): MiniNode? {
        if (++depth > MAX_DEPTH) throw CosicError(TOO_DEEP)
        try {
            return step() ?: subCycle() ?: polymeter() ?: slowSequence()
        } finally {
            depth--
        }
    }

    // ---- operators --------------------------------------------------------------------------------------------------

    private fun opWeight(): Pending? {
        val start = pos
        ws()
        if (!charClass(E_WEIGHT) { it == '@' || it == '_' }) {
            pos = start
            return null
        }
        return Pending.Weight(number())
    }

    private fun opReplicate(): Pending? {
        val start = pos
        ws()
        if (!literal("!", E_BANG)) {
            pos = start
            return null
        }
        return Pending.Replicate(number())
    }

    private fun opBjorklund(): Pending? {
        val start = pos
        if (!literal("(", E_LPAREN)) {
            pos = start
            return null
        }
        ws()
        val pulse = sliceWithOps()
        if (pulse == null) {
            pos = start
            return null
        }
        ws()
        if (!comma()) {
            pos = start
            return null
        }
        ws()
        val steps = sliceWithOps()
        if (steps == null) {
            pos = start
            return null
        }
        ws()
        comma()
        ws()
        val rotation = sliceWithOps()
        ws()
        if (!literal(")", E_RPAREN)) {
            pos = start
            return null
        }
        return Pending.Bjorklund(pulse, steps, rotation)
    }

    /** `"/" slice` and `"*" slice`, `":" slice` and `".." slice`: an operator with one slice for argument. */
    private inline fun sliceOp(text: String, description: String, make: (MiniNode) -> Pending): Pending? {
        val start = pos
        if (!literal(text, description)) {
            pos = start
            return null
        }
        val slice = slice()
        if (slice == null) {
            pos = start
            return null
        }
        return make(slice)
    }

    private fun opDegrade(): Pending? {
        val start = pos
        if (!literal("?", E_QUESTION)) {
            pos = start
            return null
        }
        return Pending.Degrade(number())
    }

    /** `slice_op`: the first operator that matches. */
    private fun sliceOp(): Pending? =
        opWeight()
            ?: opBjorklund()
            ?: sliceOp("/", E_SLASH) { Pending.Stretch(false, it) }
            ?: sliceOp("*", E_STAR) { Pending.Stretch(true, it) }
            ?: opReplicate()
            ?: opDegrade()
            ?: sliceOp(":", E_COLON) { Pending.Tail(it) }
            ?: sliceOp("..", E_RANGE) { Pending.Range(it) }

    /**
     * `slice_with_ops`: a slice and the operators after it. The operators are applied, in the order they were written,
     * once the last of them has been read; `?` takes the next random stream then.
     */
    private fun sliceWithOps(): ElementNode? {
        val start = pos
        val slice = slice()
        if (slice == null) {
            pos = start
            return null
        }
        val pending = ArrayList<Pending>()
        while (true) pending.add(sliceOp() ?: break)
        val element = ElementNode(slice)
        for (op in pending) apply(op, element)
        return element
    }

    private fun apply(op: Pending, element: ElementNode) {
        when (op) {
            is Pending.Weight -> element.weight = element.weight + (op.amount ?: 2.0) - 1.0
            is Pending.Replicate -> {
                val reps = element.reps + (op.amount ?: 2.0) - 1.0
                element.reps = reps
                element.ops.removeAll { it is Op.Replicate }
                element.ops.add(Op.Replicate(reps))
                element.weight = reps
            }
            is Pending.Bjorklund -> element.ops.add(Op.Bjorklund(op.pulse, op.step, op.rotation))
            is Pending.Stretch -> element.ops.add(Op.Stretch(op.fast, op.amount))
            is Pending.Degrade -> element.ops.add(Op.DegradeBy(op.amount, seed++))
            is Pending.Tail -> element.ops.add(Op.Tail(op.element))
            is Pending.Range -> element.ops.add(Op.Range(op.element))
        }
    }

    // ---- sequences, stacks and choices ------------------------------------------------------------------------------

    private fun sequence(): PatternNode? {
        val start = pos
        val stepsFlag = literal("^", E_CARET)
        val elements = ArrayList<MiniNode>()
        var element = sliceWithOps()
        if (element == null) {
            pos = start
            return null
        }
        while (element != null) {
            elements.add(element)
            element = sliceWithOps()
        }
        return PatternNode(elements, Alignment.FASTCAT, null, stepsFlag)
    }

    /** One or more sequences, each after [delimiter]; null when there is not even one. */
    private fun sequences(delimiter: () -> Boolean): List<PatternNode>? {
        val list = ArrayList<PatternNode>()
        while (true) {
            val start = pos
            if (delimiter()) {
                val sequence = sequence()
                if (sequence != null) {
                    list.add(sequence)
                    continue
                }
            }
            pos = start
            break
        }
        return if (list.isEmpty()) null else list
    }

    private fun stackTail(): Tail? = sequences(::comma)?.let { Tail(Alignment.STACK, it, null) }

    // the seed is taken when the whole tail has been read, so the `?` inside it have the lower numbers
    private fun chooseTail(): Tail? = sequences(::pipe)?.let { Tail(Alignment.RAND, it, seed++) }

    private fun dotTail(): Tail? = sequences(::dot)?.let { Tail(Alignment.FEET, it, seed++) }

    /** `stack_or_choose`: a sequence, alone or followed by a stack, a choice or feet. */
    private fun stackOrChoose(): PatternNode? {
        val start = pos
        val head = sequence()
        if (head == null) {
            pos = start
            return null
        }
        val tail = stackTail() ?: chooseTail() ?: dotTail()
        return if (tail != null && tail.list.isNotEmpty()) {
            PatternNode(listOf(head) + tail.list, tail.alignment, tail.seed, false)
        } else {
            head
        }
    }

    private fun polymeterStack(): PatternNode? {
        val start = pos
        val head = sequence()
        if (head == null) {
            pos = start
            return null
        }
        val tail = stackTail()
        return PatternNode(if (tail != null) listOf(head) + tail.list else listOf(head), Alignment.POLYMETER, null, false)
    }

    /** `mini`: the quoted notation. A quote inside it ends it, as the grammar has it. */
    private fun mini(): PatternNode? {
        val start = pos
        ws()
        if (!quote()) {
            pos = start
            return null
        }
        ws()
        val inner = stackOrChoose()
        if (inner == null) {
            pos = start
            return null
        }
        ws()
        if (!quote()) {
            pos = start
            return null
        }
        return inner
    }
}

/** How Peggy writes a string it expects or found: `"` and `\` escaped, control characters as `\t`, `\x0B` and the like. */
internal fun literalEscape(s: String): String {
    val out = StringBuilder()
    for (ch in s) {
        when {
            ch == '\\' -> out.append("\\\\")
            ch == '"' -> out.append("\\\"")
            ch == '\u0000' -> out.append("\\0")
            ch == '\t' -> out.append("\\t")
            ch == '\n' -> out.append("\\n")
            ch == '\r' -> out.append("\\r")
            ch <= '\u000F' -> out.append("\\x0").append(ch.code.toString(16).uppercase())
            ch in '\u0010'..'\u001F' || ch in '\u007F'..'\u009F' -> out.append("\\x").append(ch.code.toString(16).uppercase())
            else -> out.append(ch)
        }
    }
    return out.toString()
}

/**
 * `mini2ast(code, start, userCode)`: the tree of the quoted mini-notation [code] (the notation with its quotes). An error
 * says on which line of [userCode] (by default [code]) it is, counting from [start], the position of [code] in it. The
 * error also carries the range of the character that could not be read, counted from [rangeStart], the position of the
 * opening quote in the program.
 */
internal fun mini2ast(code: String, start: Int = 0, userCode: String = code, rangeStart: Int = start): PatternNode {
    try {
        return KrillParser(code).parse()
    } catch (e: MiniSyntaxError) {
        val region = e.position + start
        val line = userCode.substring(0, minOf(region, userCode.length)).count { it == '\n' } + 1
        val at = rangeStart + minOf(e.position, code.length - 1)
        throw CosicError("[mini] parse error at line $line: ${e.message}", SourceRange(at, at + 1))
    }
}
