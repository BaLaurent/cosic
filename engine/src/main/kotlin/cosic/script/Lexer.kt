package cosic.script

import cosic.SourceRange
import cosic.pattern.CosicError

/*
 * The lexer follows acorn 8.14 (MIT, https://github.com/acornjs/acorn): same token types, same cooking of numbers,
 * strings and templates, same error messages at the same positions. Regular expressions and BigInt are the two things
 * acorn reads that Cosic does not run; they are errors here. Two scanners live in this file:
 *
 *  - [Lexer] reads tokens one at a time for the parser and throws a CosicError at the first malformed token, like
 *    acorn does when the parser advances onto it;
 *  - [tokenize] cuts code into coloured spans for the editor. It never throws and copes with unfinished code.
 */

/**
 * The type of a token; acorn's table. [keyword] is the word of a keyword token and [binop] the precedence of a binary
 * operator (0 if the token is none). `EQ` is `=`, `ASSIGN` every other assignment operator, `INC_DEC` is `++` and `--`,
 * `PREFIX` is `!` and `~`, and `EQUALITY`, `RELATIONAL` and `BIT_SHIFT` stand for several operators each (see [Token.value]).
 */
internal enum class Tt(
    val keyword: String? = null,
    val binop: Int = 0,
    val prefix: Boolean = false,
    val postfix: Boolean = false,
    val isAssign: Boolean = false,
    val isLoop: Boolean = false,
) {
    NUM, STRING, NAME, EOF,

    BRACKET_L, BRACKET_R, BRACE_L, BRACE_R, PAREN_L, PAREN_R, COMMA, SEMI, COLON, DOT, QUESTION, QUESTION_DOT, ARROW,
    TEMPLATE, INVALID_TEMPLATE, ELLIPSIS, BACK_QUOTE, DOLLAR_BRACE_L,

    EQ(isAssign = true),
    ASSIGN(isAssign = true),
    INC_DEC(prefix = true, postfix = true),
    PREFIX(prefix = true),
    LOGICAL_OR(binop = 1),
    LOGICAL_AND(binop = 2),
    BITWISE_OR(binop = 3),
    BITWISE_XOR(binop = 4),
    BITWISE_AND(binop = 5),
    EQUALITY(binop = 6),
    RELATIONAL(binop = 7),
    BIT_SHIFT(binop = 8),
    PLUS_MIN(binop = 9, prefix = true),
    MODULO(binop = 10),
    STAR(binop = 10),
    SLASH(binop = 10),
    STAR_STAR,
    COALESCE(binop = 1),

    BREAK("break"),
    CASE("case"),
    CATCH("catch"),
    CONTINUE("continue"),
    DEBUGGER("debugger"),
    DEFAULT("default"),
    DO("do", isLoop = true),
    ELSE("else"),
    FINALLY("finally"),
    FOR("for", isLoop = true),
    FUNCTION("function"),
    IF("if"),
    RETURN("return"),
    SWITCH("switch"),
    THROW("throw"),
    TRY("try"),
    VAR("var"),
    CONST("const"),
    WHILE("while", isLoop = true),
    WITH("with"),
    NEW("new"),
    THIS("this"),
    SUPER("super"),
    CLASS("class"),
    EXTENDS("extends"),
    EXPORT("export"),
    IMPORT("import"),
    NULL("null"),
    TRUE("true"),
    FALSE("false"),
    IN("in", binop = 7),
    INSTANCEOF("instanceof", binop = 7),
    TYPEOF("typeof", prefix = true),
    VOID("void", prefix = true),
    DELETE("delete", prefix = true);

    companion object {
        val keywords: Map<String, Tt> = entries.filter { it.keyword != null }.associateBy { it.keyword!! }
    }
}

/**
 * A token. [value] depends on the type: the cooked name of a NAME or keyword (what escapes spell), a Double for NUM, the
 * cooked String of a STRING or TEMPLATE, the raw text of an INVALID_TEMPLATE, and the operator text for EQ, ASSIGN,
 * INC_DEC, PREFIX and the binary operators; null otherwise. [nlBefore] tells whether a line terminator separates the token
 * from the one before it (comments included), which is what automatic semicolon insertion looks at.
 */
internal class Token(
    @JvmField val type: Tt,
    @JvmField val start: Int,
    @JvmField val end: Int,
    @JvmField val value: Any?,
    @JvmField val nlBefore: Boolean,
    /** Whether a NAME or keyword was written with a `\u` escape. */
    @JvmField val containsEsc: Boolean = false,
) {
    override fun toString(): String = "${type.name}[$start,$end]${if (value != null) "($value)" else ""}"
}

/** A `//` or `/* */` comment. [value] is its text without the delimiters. */
internal class Comment(val block: Boolean, val value: String, val start: Int, val end: Int)

internal class LexResult(val tokens: List<Token>, val comments: List<Comment>)

/** Every token of [code] except the final EOF, and its comments. Throws a [CosicError] at the first malformed token. */
internal fun lexAll(code: String): LexResult {
    val lexer = Lexer(code)
    val tokens = ArrayList<Token>()
    while (true) {
        val token = lexer.next()
        if (token.type == Tt.EOF) break
        tokens.add(token)
    }
    return LexResult(tokens, lexer.comments)
}

// ---- characters ------------------------------------------------------------------------------------------------

internal fun isNewLine(c: Char): Boolean = c == '\n' || c == '\r' || c == '\u2028' || c == '\u2029'

internal fun isJsWhitespace(c: Char): Boolean =
    c == ' ' || c == '\u00a0' || c in '\u0009'..'\u000d' || c == '\u1680' || c in '\u2000'..'\u200a' ||
        c == '\u2028' || c == '\u2029' || c == '\u202f' || c == '\u205f' || c == '\u3000' || c == '\ufeff'

internal fun isIdentifierStart(cp: Int): Boolean = when {
    cp < 65 -> cp == 36
    cp < 91 -> true
    cp < 97 -> cp == 95
    cp < 123 -> true
    cp < 0xaa -> false
    else -> Character.isUnicodeIdentifierStart(cp)
}

internal fun isIdentifierChar(cp: Int): Boolean = when {
    cp < 48 -> cp == 36
    cp < 58 -> true
    cp < 65 -> false
    cp < 91 -> true
    cp < 97 -> cp == 95
    cp < 123 -> true
    cp < 0xaa -> false
    else -> Character.isUnicodeIdentifierStart(cp) || isIdContinue(cp)
}

private fun isIdContinue(cp: Int): Boolean {
    if (cp == 0x200c || cp == 0x200d || cp == 0xb7 || cp == 0x387 || cp == 0x19da || cp in 0x1369..0x1371) return true
    return when (Character.getType(cp).toByte()) {
        Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.DECIMAL_DIGIT_NUMBER, Character.CONNECTOR_PUNCTUATION -> true
        else -> false
    }
}

private fun codePointToString(code: Int): String = String(Character.toChars(code))

// ---- the strict lexer --------------------------------------------------------------------------------------------

private const val CTX_BRACE = 0
private const val CTX_DOLLAR = 1
private const val CTX_TEMPLATE = 2

/** Thrown inside a template piece for an invalid escape; the piece becomes an INVALID_TEMPLATE token instead of an error. */
private object InvalidTemplateEscape : RuntimeException(null, null, false, false)

/**
 * Reads [input] token by token with [next]. Strings, numbers, templates and comments follow acorn; the one decision acorn
 * leaves to its parser, whether a `/` divides or starts a regular expression, is taken from the token before it. A `/`
 * that does not divide is an error either way, so a wrong guess only changes which error is reported.
 */
internal class Lexer(private val input: String) {
    /** The end of the token read last: the lexer reads one token at a time and never looks further ahead. */
    var pos = 0
        private set

    private val commentList = ArrayList<Comment>()

    /** The comments skipped so far. */
    val comments: List<Comment> get() = commentList

    private val len = input.length
    private val contexts = ArrayList<Int>()
    private var exprAllowed = true
    private var lastType: Tt? = null
    private var containsEsc = false
    private var inTemplateElement = false

    fun next(): Token {
        val inTemplate = contexts.isNotEmpty() && contexts[contexts.size - 1] == CTX_TEMPLATE
        val newline = if (inTemplate) false else skipSpace()
        val start = pos
        return when {
            pos >= len -> finish(Tt.EOF, start, newline)
            inTemplate -> tryReadTemplateToken(start)
            else -> readToken(start, newline)
        }
    }

    /** The position after the white space and comments that start at [from]; comments are not recorded. */
    fun skipSpaceFrom(from: Int): Int {
        var i = from
        while (i < len) {
            val c = input[i]
            when {
                isJsWhitespace(c) -> i++
                c == '/' && at(i + 1) == '/'.code -> {
                    i += 2
                    while (i < len && !isNewLine(input[i])) i++
                }
                c == '/' && at(i + 1) == '*'.code -> {
                    val close = input.indexOf("*/", i + 2)
                    if (close < 0) return i
                    i = close + 2
                }
                else -> return i
            }
        }
        return i
    }

    /** The end of the regular expression literal that starts with the `/` at [start], or null if there is no closing slash on the line. */
    fun regexEnd(start: Int): Int? {
        var i = start + 1
        var escaped = false
        var inClass = false
        while (true) {
            if (i >= len || isNewLine(input[i])) return null
            val ch = input[i]
            if (!escaped) {
                if (ch == '[') {
                    inClass = true
                } else if (ch == ']' && inClass) {
                    inClass = false
                } else if (ch == '/' && !inClass) {
                    break
                }
                escaped = ch == '\\'
            } else {
                escaped = false
            }
            i++
        }
        i++
        while (i < len) {
            val cp = input.codePointAt(i)
            if (!isIdentifierChar(cp)) break
            i += Character.charCount(cp)
        }
        return i
    }

    // ---- errors ---------------------------------------------------------------------------------------------------

    private fun fail(message: String, start: Int, end: Int = start + 1): Nothing {
        val range = if (start >= len) SourceRange(maxOf(len - 1, 0), len) else SourceRange(start, end.coerceIn(start + 1, len))
        throw CosicError(message, range)
    }

    private fun at(i: Int): Int = if (i < len) input[i].code else -1

    // ---- white space and comments ------------------------------------------------------------------------------

    /** Skips white space and comments after the last token; true if a line terminator was among them. */
    private fun skipSpace(): Boolean {
        var newline = false
        while (pos < len) {
            val c = input[pos]
            when {
                c == '\r' -> {
                    pos++
                    if (at(pos) == '\n'.code) pos++
                    newline = true
                }
                c == '\n' || c == '\u2028' || c == '\u2029' -> {
                    pos++
                    newline = true
                }
                isJsWhitespace(c) -> pos++
                c == '/' && at(pos + 1) == '*'.code -> if (skipBlockComment()) newline = true
                c == '/' && at(pos + 1) == '/'.code -> skipLineComment()
                else -> return newline
            }
        }
        return newline
    }

    /** Skips a block comment, recording it; true if it contains a line terminator. */
    private fun skipBlockComment(): Boolean {
        val start = pos
        val close = input.indexOf("*/", pos + 2)
        if (close < 0) fail("Unterminated comment", start, len)
        pos = close + 2
        commentList.add(Comment(true, input.substring(start + 2, close), start, pos))
        for (i in start until pos) if (isNewLine(input[i])) return true
        return false
    }

    private fun skipLineComment() {
        val start = pos
        pos += 2
        while (pos < len && !isNewLine(input[pos])) pos++
        commentList.add(Comment(false, input.substring(start + 2, pos), start, pos))
    }

    // ---- tokens ---------------------------------------------------------------------------------------------------

    private fun finish(type: Tt, start: Int, newline: Boolean, value: Any? = null, esc: Boolean = false): Token {
        when (type) {
            Tt.BRACE_L -> contexts.add(CTX_BRACE)
            Tt.DOLLAR_BRACE_L -> contexts.add(CTX_DOLLAR)
            Tt.BRACE_R -> if (contexts.isNotEmpty()) contexts.removeAt(contexts.size - 1)
            Tt.BACK_QUOTE ->
                if (contexts.isNotEmpty() && contexts[contexts.size - 1] == CTX_TEMPLATE) contexts.removeAt(contexts.size - 1)
                else contexts.add(CTX_TEMPLATE)
            else -> {}
        }
        exprAllowed = when (type) {
            Tt.NAME, Tt.NUM, Tt.STRING, Tt.BRACKET_R, Tt.PAREN_R, Tt.BRACE_R, Tt.THIS, Tt.SUPER, Tt.NULL, Tt.TRUE, Tt.FALSE,
            Tt.TEMPLATE, Tt.INVALID_TEMPLATE, Tt.EOF -> false
            // The pieces of a template are read without looking at this; after the closing backquote a slash divides.
            Tt.BACK_QUOTE -> false
            Tt.INC_DEC -> exprAllowed
            else -> true
        }
        lastType = type
        return Token(type, start, pos, value, newline, esc)
    }

    private fun finishOp(type: Tt, size: Int, start: Int, newline: Boolean): Token {
        val text = input.substring(pos, pos + size)
        pos += size
        return finish(type, start, newline, text)
    }

    private fun readToken(start: Int, newline: Boolean): Token {
        val cp = input.codePointAt(pos)
        if (isIdentifierStart(cp) || cp == '\\'.code) return readWord(start, newline)
        return when (input[pos]) {
            '.' -> readDot(start, newline)
            '(' -> punct(Tt.PAREN_L, start, newline)
            ')' -> punct(Tt.PAREN_R, start, newline)
            ';' -> punct(Tt.SEMI, start, newline)
            ',' -> punct(Tt.COMMA, start, newline)
            '[' -> punct(Tt.BRACKET_L, start, newline)
            ']' -> punct(Tt.BRACKET_R, start, newline)
            '{' -> punct(Tt.BRACE_L, start, newline)
            '}' -> punct(Tt.BRACE_R, start, newline)
            ':' -> punct(Tt.COLON, start, newline)
            '`' -> punct(Tt.BACK_QUOTE, start, newline)
            '0' -> when (at(pos + 1)) {
                'x'.code, 'X'.code -> readRadixNumber(16, start, newline)
                'o'.code, 'O'.code -> readRadixNumber(8, start, newline)
                'b'.code, 'B'.code -> readRadixNumber(2, start, newline)
                else -> readNumber(false, start, newline)
            }
            in '1'..'9' -> readNumber(false, start, newline)
            '"', '\'' -> readString(input[pos], start, newline)
            '/' -> readSlash(start, newline)
            '%', '*' -> readMultModuloExp(start, newline)
            '|', '&' -> readPipeAmp(start, newline)
            '^' -> if (at(pos + 1) == '='.code) finishOp(Tt.ASSIGN, 2, start, newline) else finishOp(Tt.BITWISE_XOR, 1, start, newline)
            '+', '-' -> readPlusMin(start, newline)
            '<', '>' -> readLtGt(start, newline)
            '=', '!' -> readEqExcl(start, newline)
            '?' -> readQuestion(start, newline)
            '~' -> finishOp(Tt.PREFIX, 1, start, newline)
            '#' -> readNumberSign(start)
            else -> fail("Unexpected character '${codePointToString(cp)}'", pos, pos + Character.charCount(cp))
        }
    }

    private fun punct(type: Tt, start: Int, newline: Boolean): Token {
        pos++
        return finish(type, start, newline)
    }

    private fun readDot(start: Int, newline: Boolean): Token {
        val next = at(pos + 1)
        if (next >= '0'.code && next <= '9'.code) return readNumber(true, start, newline)
        if (next == '.'.code && at(pos + 2) == '.'.code) {
            pos += 3
            return finish(Tt.ELLIPSIS, start, newline)
        }
        pos++
        return finish(Tt.DOT, start, newline)
    }

    private fun readSlash(start: Int, newline: Boolean): Token {
        if (exprAllowed) regexError(start)
        if (at(pos + 1) == '='.code) return finishOp(Tt.ASSIGN, 2, start, newline)
        return finishOp(Tt.SLASH, 1, start, newline)
    }

    private fun regexError(start: Int): Nothing {
        fail("Regular expressions are not supported", start, regexEnd(start) ?: (start + 1))
    }

    private fun readMultModuloExp(start: Int, newline: Boolean): Token {
        val code = input[pos]
        var next = at(pos + 1)
        var size = 1
        var type = if (code == '*') Tt.STAR else Tt.MODULO
        if (code == '*' && next == '*'.code) {
            size++
            type = Tt.STAR_STAR
            next = at(pos + 2)
        }
        if (next == '='.code) return finishOp(Tt.ASSIGN, size + 1, start, newline)
        return finishOp(type, size, start, newline)
    }

    private fun readPipeAmp(start: Int, newline: Boolean): Token {
        val code = input[pos]
        val next = at(pos + 1)
        if (next == code.code) {
            if (at(pos + 2) == '='.code) return finishOp(Tt.ASSIGN, 3, start, newline)
            return finishOp(if (code == '|') Tt.LOGICAL_OR else Tt.LOGICAL_AND, 2, start, newline)
        }
        if (next == '='.code) return finishOp(Tt.ASSIGN, 2, start, newline)
        return finishOp(if (code == '|') Tt.BITWISE_OR else Tt.BITWISE_AND, 1, start, newline)
    }

    private fun readPlusMin(start: Int, newline: Boolean): Token {
        val code = input[pos]
        val next = at(pos + 1)
        if (next == code.code) return finishOp(Tt.INC_DEC, 2, start, newline)
        if (next == '='.code) return finishOp(Tt.ASSIGN, 2, start, newline)
        return finishOp(Tt.PLUS_MIN, 1, start, newline)
    }

    private fun readLtGt(start: Int, newline: Boolean): Token {
        val code = input[pos]
        val next = at(pos + 1)
        var size = 1
        if (next == code.code) {
            size = if (code == '>' && at(pos + 2) == '>'.code) 3 else 2
            if (at(pos + size) == '='.code) return finishOp(Tt.ASSIGN, size + 1, start, newline)
            return finishOp(Tt.BIT_SHIFT, size, start, newline)
        }
        if (next == '='.code) size = 2
        return finishOp(Tt.RELATIONAL, size, start, newline)
    }

    private fun readEqExcl(start: Int, newline: Boolean): Token {
        val code = input[pos]
        val next = at(pos + 1)
        if (next == '='.code) return finishOp(Tt.EQUALITY, if (at(pos + 2) == '='.code) 3 else 2, start, newline)
        if (code == '=' && next == '>'.code) {
            pos += 2
            return finish(Tt.ARROW, start, newline)
        }
        return finishOp(if (code == '=') Tt.EQ else Tt.PREFIX, 1, start, newline)
    }

    private fun readQuestion(start: Int, newline: Boolean): Token {
        val next = at(pos + 1)
        if (next == '.'.code) {
            val next2 = at(pos + 2)
            if (next2 < '0'.code || next2 > '9'.code) return finishOp(Tt.QUESTION_DOT, 2, start, newline)
        }
        if (next == '?'.code) {
            if (at(pos + 2) == '='.code) return finishOp(Tt.ASSIGN, 3, start, newline)
            return finishOp(Tt.COALESCE, 2, start, newline)
        }
        return finishOp(Tt.QUESTION, 1, start, newline)
    }

    /** `#name` is a private name, which Cosic has no use for; a lone `#` is an unexpected character as in acorn. */
    private fun readNumberSign(start: Int): Nothing {
        val cp = if (pos + 1 < len) input.codePointAt(pos + 1) else -1
        if (cp >= 0 && (isIdentifierStart(cp) || cp == '\\'.code)) {
            var end = pos + 1
            while (end < len) {
                val c = input.codePointAt(end)
                if (!isIdentifierChar(c)) break
                end += Character.charCount(c)
            }
            fail("Private identifiers are not supported", start, end)
        }
        if (cp < 0) fail("Unexpected character '#'", start)
        fail("Unexpected character '${codePointToString(cp)}'", pos + 1, pos + 1 + Character.charCount(cp))
    }

    // ---- words -----------------------------------------------------------------------------------------------------

    private fun readWord(start: Int, newline: Boolean): Token {
        val word = readWord1()
        return finish(Tt.keywords[word] ?: Tt.NAME, start, newline, word, containsEsc)
    }

    /** Reads an identifier, with `\u` escapes decoded; sets [containsEsc]. */
    private fun readWord1(): String {
        containsEsc = false
        var decoded: StringBuilder? = null
        var first = true
        var chunkStart = pos
        while (pos < len) {
            val cp = input.codePointAt(pos)
            if (isIdentifierChar(cp)) {
                pos += Character.charCount(cp)
            } else if (cp == '\\'.code) {
                containsEsc = true
                val sb = decoded ?: StringBuilder().also { decoded = it }
                sb.append(input, chunkStart, pos)
                val escStart = pos
                if (at(++pos) != 'u'.code) invalidStringToken(pos, "Expecting Unicode escape sequence \\uXXXX")
                ++pos
                val esc = readCodePoint()
                if (!(if (first) isIdentifierStart(esc) else isIdentifierChar(esc))) invalidStringToken(escStart, "Invalid Unicode escape")
                sb.append(codePointToString(esc))
                chunkStart = pos
            } else {
                break
            }
            first = false
        }
        val sb = decoded ?: return input.substring(chunkStart, pos)
        return sb.append(input, chunkStart, pos).toString()
    }

    // ---- numbers ---------------------------------------------------------------------------------------------------

    /**
     * Reads digits of [radix], with `_` separators unless [length] is given (an escape reads an exact number of digits).
     * Null if there is no digit, or not exactly [length] of them. [maybeLegacyOctal] is set for a decimal integer, where a
     * leading zero forbids separators.
     */
    private fun readInt(radix: Int, length: Int? = null, maybeLegacyOctal: Boolean = false): Double? {
        val allowSeparators = length == null
        val isLegacyOctal = maybeLegacyOctal && at(pos) == '0'.code
        val start = pos
        var total = 0.0
        var lastCode = 0
        var i = 0
        val limit = length ?: Int.MAX_VALUE
        while (i < limit) {
            val code = at(pos)
            if (allowSeparators && code == '_'.code) {
                if (isLegacyOctal) fail("Numeric separator is not allowed in legacy octal numeric literals", pos)
                if (lastCode == '_'.code) fail("Numeric separator must be exactly one underscore", pos)
                if (i == 0) fail("Numeric separator is not allowed at the first of digits", pos)
                lastCode = code
                i++
                pos++
                continue
            }
            val digit = when {
                code >= 97 -> code - 97 + 10
                code >= 65 -> code - 65 + 10
                code in 48..57 -> code - 48
                else -> Int.MAX_VALUE
            }
            if (digit >= radix) break
            lastCode = code
            total = total * radix + digit
            i++
            pos++
        }
        if (allowSeparators && lastCode == '_'.code) fail("Numeric separator is not allowed at the last of digits", pos - 1)
        if (pos == start || (length != null && pos - start != length)) return null
        return total
    }

    private fun bigIntError(start: Int): Nothing = fail("BigInt literals are not supported", start, pos + 1)

    private fun readRadixNumber(radix: Int, start: Int, newline: Boolean): Token {
        pos += 2
        val value = readInt(radix) ?: fail("Expected number in radix $radix", start + 2)
        if (at(pos) == 'n'.code) bigIntError(start)
        if (pos < len && isIdentifierStart(input.codePointAt(pos))) fail("Identifier directly after number", pos)
        return finish(Tt.NUM, start, newline, value)
    }

    private fun readNumber(startsWithDot: Boolean, start: Int, newline: Boolean): Token {
        if (!startsWithDot && readInt(10, null, true) == null) fail("Invalid number", start)
        // Every program is strict code: a leading zero (0123, 089) is not a number.
        if (pos - start >= 2 && input[start] == '0') fail("Invalid number", start)
        var next = at(pos)
        if (!startsWithDot && next == 'n'.code) bigIntError(start)
        if (next == '.'.code) {
            pos++
            readInt(10)
            next = at(pos)
        }
        if (next == 'e'.code || next == 'E'.code) {
            next = at(++pos)
            if (next == '+'.code || next == '-'.code) pos++
            if (readInt(10) == null) fail("Invalid number", start)
        }
        if (pos < len && isIdentifierStart(input.codePointAt(pos))) fail("Identifier directly after number", pos)
        return finish(Tt.NUM, start, newline, input.substring(start, pos).replace("_", "").toDouble())
    }

    // ---- strings ---------------------------------------------------------------------------------------------------

    private fun invalidStringToken(position: Int, message: String): Nothing {
        if (inTemplateElement) throw InvalidTemplateEscape
        fail(message, position)
    }

    private fun readHexChar(length: Int): Int {
        val codePos = pos
        val n = readInt(16, length) ?: invalidStringToken(codePos, "Bad character escape sequence")
        return n.toInt()
    }

    private fun readCodePoint(): Int {
        if (at(pos) == '{'.code) {
            val codePos = ++pos
            val digits = input.indexOf('}', pos) - pos
            val value = readInt(16, digits) ?: invalidStringToken(codePos, "Bad character escape sequence")
            ++pos
            if (value > 0x10FFFF) invalidStringToken(codePos, "Code point out of bounds")
            return value.toInt()
        }
        return readHexChar(4)
    }

    private fun readString(quote: Char, start: Int, newline: Boolean): Token {
        val out = StringBuilder()
        var chunkStart = ++pos
        while (true) {
            if (pos >= len) fail("Unterminated string constant", start, pos)
            val ch = input[pos]
            if (ch == quote) break
            if (ch == '\\') {
                out.append(input, chunkStart, pos)
                out.append(readEscapedChar(false))
                chunkStart = pos
            } else if (ch == '\u2028' || ch == '\u2029') {
                pos++
            } else {
                if (isNewLine(ch)) fail("Unterminated string constant", start, pos)
                pos++
            }
        }
        out.append(input, chunkStart, pos)
        pos++
        return finish(Tt.STRING, start, newline, out.toString())
    }

    /** The character after a backslash, cooked. Every program is strict code, so legacy octal escapes are errors. */
    private fun readEscapedChar(inTemplate: Boolean): String {
        val ch = at(++pos)
        ++pos
        when (ch) {
            'n'.code -> return "\n"
            'r'.code -> return "\r"
            'x'.code -> return readHexChar(2).toChar().toString()
            'u'.code -> return codePointToString(readCodePoint())
            't'.code -> return "\t"
            'b'.code -> return "\b"
            'v'.code -> return "\u000b"
            'f'.code -> return "\u000c"
            13 -> {
                if (at(pos) == 10) pos++
                return ""
            }
            10 -> return ""
            '8'.code, '9'.code -> invalidStringToken(pos - 1, "Invalid escape sequence")
        }
        if (ch >= 48 && ch <= 55) {
            var digits = input.substring(pos - 1, minOf(pos + 2, len)).takeWhile { it in '0'..'7' }
            var octal = digits.toInt(8)
            if (octal > 255) {
                digits = digits.dropLast(1)
                octal = digits.toInt(8)
            }
            pos += digits.length - 1
            val following = at(pos)
            if (digits != "0" || following == '8'.code || following == '9'.code) {
                invalidStringToken(pos - 1 - digits.length, if (inTemplate) "Octal literal in template string" else "Octal literal in strict mode")
            }
            return octal.toChar().toString()
        }
        if (ch >= 0 && isNewLine(ch.toChar())) return ""
        return if (ch < 0) "" else ch.toChar().toString()
    }

    // ---- templates -------------------------------------------------------------------------------------------------

    private fun tryReadTemplateToken(start: Int): Token {
        inTemplateElement = true
        try {
            return readTmplToken(start)
        } catch (e: InvalidTemplateEscape) {
            return readInvalidTemplateToken(start)
        } finally {
            inTemplateElement = false
        }
    }

    /** One piece of a template, or the `${` or closing backquote that follows a piece. */
    private fun readTmplToken(start: Int): Token {
        val out = StringBuilder()
        var chunkStart = pos
        while (true) {
            if (pos >= len) fail("Unterminated template", start, len)
            val ch = input[pos]
            if (ch == '`' || ch == '$' && at(pos + 1) == '{'.code) {
                if (pos == start && (lastType == Tt.TEMPLATE || lastType == Tt.INVALID_TEMPLATE)) {
                    if (ch == '$') {
                        pos += 2
                        return finish(Tt.DOLLAR_BRACE_L, start, false)
                    }
                    pos++
                    return finish(Tt.BACK_QUOTE, start, false)
                }
                out.append(input, chunkStart, pos)
                return finish(Tt.TEMPLATE, start, false, out.toString())
            }
            if (ch == '\\') {
                out.append(input, chunkStart, pos)
                out.append(readEscapedChar(true))
                chunkStart = pos
            } else if (isNewLine(ch)) {
                out.append(input, chunkStart, pos)
                pos++
                when (ch) {
                    '\r' -> {
                        if (at(pos) == '\n'.code) pos++
                        out.append('\n')
                    }
                    '\n' -> out.append('\n')
                    else -> out.append(ch)
                }
                chunkStart = pos
            } else {
                pos++
            }
        }
    }

    /** Reads the rest of a piece with an invalid escape, without checking its escapes; its value is its raw text. */
    private fun readInvalidTemplateToken(start: Int): Token {
        while (pos < len) {
            when (input[pos]) {
                '\\' -> pos++
                '$' -> if (at(pos + 1) == '{'.code) return finish(Tt.INVALID_TEMPLATE, start, false, input.substring(start, pos))
                '`' -> return finish(Tt.INVALID_TEMPLATE, start, false, input.substring(start, pos))
            }
            pos++
        }
        fail("Unterminated template", start, len)
    }
}

// ---- tokenize: coloured spans for the editor ------------------------------------------------------------------------

/** What a stretch of code is, for syntax highlighting. */
public enum class TokenKind { COMMENT, MINI, STRING, NUMBER, IDENT, CALL, LABEL, PUNCT }

/** A coloured stretch of code: `[start, end)` are character offsets. */
public data class TokenSpan(val start: Int, val end: Int, val kind: TokenKind)

/**
 * Splits code into highlightable spans for the editor. It never fails, whatever the code looks like: an unterminated
 * string, template or block comment runs to the end of the code. Whitespace is not reported.
 *
 * - `//` and `/* */` comments are `COMMENT`;
 * - a double-quoted or backtick string, with a template's `${}` balanced, is `MINI`; a single-quoted string, and a
 *   regular expression, is `STRING`;
 * - numbers of every form (`0x1F`, `1_000`, `1e3`, `.5`, `5.`) are `NUMBER`, without their sign;
 * - a name directly followed by `(` is a `CALL`, unless it is a keyword;
 * - a name or `$` directly followed by `:` is a `LABEL` when it starts a statement (after `;`, `{`, `}`, a line break
 *   or at the start) outside of parentheses and brackets;
 * - everything else, operators and unknown characters included, is `PUNCT`, an operator like `=>` or `===` as one span.
 */
public fun tokenize(code: String): List<TokenSpan> = Highlighter(code).spans()

private val OPERATORS = listOf(
    ">>>=", "...", "===", "!==", "**=", "<<=", ">>=", ">>>", "&&=", "||=", "??=",
    "=>", "==", "!=", "<=", ">=", "&&", "||", "??", "?.", "++", "--", "+=", "-=", "*=", "/=", "%=", "&=", "|=", "^=", "**", "<<", ">>",
)

private val REGEX_KEYWORDS = setOf("return", "typeof", "delete", "void", "in", "instanceof", "new", "case", "do", "else", "throw", "yield", "await", "of")

/** Words that look like a call when a parenthesis follows (`if (a)`, `typeof(a)`, `await(a)`) but are not one. */
private val NON_CALL_WORDS = Tt.keywords.keys + setOf("await", "yield")

/** How deep the highlighter follows templates inside the `${}` of templates. */
private const val MAX_TEMPLATE_NESTING = 50

private class Highlighter(private val code: String) {
    private val n = code.length
    private val out = ArrayList<TokenSpan>()
    private var i = 0
    private var newline = false
    private var prevKind: TokenKind? = null
    private var prevText = ""

    /** Parenthesis and bracket nesting: a label only starts a statement outside of both. */
    private var depth = 0

    fun spans(): List<TokenSpan> {
        while (i < n) {
            val c = code[i]
            when {
                isNewLine(c) -> {
                    newline = true
                    i++
                }
                isJsWhitespace(c) -> i++
                c == '/' && at(i + 1) == '/' -> comment(lineEnd(i))
                c == '/' && at(i + 1) == '*' -> {
                    val close = code.indexOf("*/", i + 2)
                    val end = if (close < 0) n else close + 2
                    comment(end)
                }
                c in '0'..'9' || (c == '.' && at(i + 1) in '0'..'9' && !endsOperand()) -> significant(TokenKind.NUMBER, numberEnd(i))
                c == '"' || c == '\'' || c == '`' -> {
                    val end = if (c == '`') templateEnd(i, 0) else quotedEnd(i, c)
                    significant(if (c == '\'') TokenKind.STRING else TokenKind.MINI, end)
                }
                c == '/' && !endsOperand() && regexEnd(i) != null -> significant(TokenKind.STRING, regexEnd(i)!!)
                startsIdentifier(i) -> identifier()
                else -> punctuation()
            }
        }
        return out
    }

    private fun at(index: Int): Char = if (index < n) code[index] else '\u0000'

    private fun lineEnd(from: Int): Int {
        var j = from
        while (j < n && !isNewLine(code[j])) j++
        return j
    }

    private fun comment(end: Int) {
        if (code.substring(i, end).any(::isNewLine)) newline = true
        out.add(TokenSpan(i, end, TokenKind.COMMENT))
        i = end
    }

    private fun significant(kind: TokenKind, end: Int) {
        val text = code.substring(i, end)
        out.add(TokenSpan(i, end, kind))
        if (kind == TokenKind.PUNCT) {
            if (text == "(" || text == "[") depth++
            if ((text == ")" || text == "]") && depth > 0) depth--
        }
        prevKind = kind
        prevText = text
        newline = false
        i = end
    }

    /** Whether the token before ends an operand, so that a `/` divides and a `.` is a member access. */
    private fun endsOperand(): Boolean = when (prevKind) {
        null, TokenKind.COMMENT, TokenKind.LABEL -> false
        TokenKind.NUMBER, TokenKind.MINI, TokenKind.STRING -> true
        TokenKind.IDENT, TokenKind.CALL -> prevText !in REGEX_KEYWORDS
        TokenKind.PUNCT -> prevText == ")" || prevText == "]" || prevText == "}" || prevText == "++" || prevText == "--"
    }

    /** Whether the token before is the dot of a member access, after which any word is a member name. */
    private fun afterMemberDot(): Boolean = prevKind == TokenKind.PUNCT && (prevText == "." || prevText == "?.")

    private fun startsIdentifier(index: Int): Boolean {
        if (index >= n) return false
        val cp = code.codePointAt(index)
        return isIdentifierStart(cp) || (cp == '\\'.code && at(index + 1) == 'u')
    }

    private fun identifier() {
        val start = i
        var j = i
        while (j < n) {
            val cp = code.codePointAt(j)
            if (isIdentifierChar(cp)) {
                j += Character.charCount(cp)
            } else if (cp == '\\'.code && at(j + 1) == 'u') {
                j += 2
                if (at(j) == '{') {
                    val close = code.indexOf('}', j)
                    j = if (close < 0) n else close + 1
                } else {
                    var digits = 0
                    while (digits < 4 && j < n && Character.digit(code[j], 16) >= 0) {
                        j++
                        digits++
                    }
                }
            } else {
                break
            }
        }
        val name = code.substring(start, j)
        val kind = when {
            at(j) == '(' && (afterMemberDot() || name !in NON_CALL_WORDS) -> TokenKind.CALL
            at(j) == ':' && (prevKind == null || newline || prevText == ";" || prevText == "{" || prevText == "}") && depth == 0 -> TokenKind.LABEL
            else -> TokenKind.IDENT
        }
        significant(kind, j)
    }

    private fun punctuation() {
        for (operator in OPERATORS) {
            if (code.startsWith(operator, i)) {
                // `?.` before a digit is a conditional followed by a number.
                if (operator == "?." && at(i + 2) in '0'..'9') continue
                significant(TokenKind.PUNCT, i + operator.length)
                return
            }
        }
        val cp = code.codePointAt(i)
        significant(TokenKind.PUNCT, i + Character.charCount(cp))
    }

    private fun numberEnd(start: Int): Int {
        var j = start
        if (code[j] == '0' && at(j + 1) in "xXoObB") {
            j += 2
            while (j < n && (Character.digit(code[j], 16) >= 0 || code[j] == '_')) j++
            return j
        }
        while (j < n && (code[j] in '0'..'9' || code[j] == '_')) j++
        if (at(j) == '.') {
            j++
            while (j < n && (code[j] in '0'..'9' || code[j] == '_')) j++
        }
        if (at(j) == 'e' || at(j) == 'E') {
            var k = j + 1
            if (at(k) == '+' || at(k) == '-') k++
            if (at(k) in '0'..'9') {
                j = k
                while (j < n && (code[j] in '0'..'9' || code[j] == '_')) j++
            }
        }
        return j
    }

    /** The end of the string that opens at [start]; an unterminated one runs to the end of the code. */
    private fun quotedEnd(start: Int, quote: Char): Int {
        var j = start + 1
        while (j < n) {
            val c = code[j]
            when {
                c == quote -> return j + 1
                c == '\\' -> j += if (at(j + 1) == '\r' && at(j + 2) == '\n') 3 else 2
                c == '\n' || c == '\r' -> return n
                else -> j++
            }
        }
        return n
    }

    /**
     * The end of the template that opens at [start], its `${}` expressions skipped as code. A template inside a `${}`
     * of a template inside a `${}` ... deeper than [MAX_TEMPLATE_NESTING] is not followed: the rest of the code is taken to
     * belong to it, which keeps the recursion short for any text.
     */
    private fun templateEnd(start: Int, nesting: Int): Int {
        if (nesting > MAX_TEMPLATE_NESTING) return n
        var j = start + 1
        while (j < n) {
            val c = code[j]
            when {
                c == '`' -> return j + 1
                c == '\\' -> j += 2
                c == '$' && at(j + 1) == '{' -> j = expressionEnd(j + 2, nesting + 1)
                else -> j++
            }
        }
        return n
    }

    /** The position after the `}` that closes a `${` whose expression starts at [from]. */
    private fun expressionEnd(from: Int, nesting: Int): Int {
        var j = from
        var braces = 1
        while (j < n) {
            val c = code[j]
            when {
                c == '{' -> {
                    braces++
                    j++
                }
                c == '}' -> {
                    braces--
                    j++
                    if (braces == 0) return j
                }
                c == '"' || c == '\'' -> j = quotedEnd(j, c)
                c == '`' -> j = templateEnd(j, nesting)
                c == '/' && at(j + 1) == '/' -> j = lineEnd(j)
                c == '/' && at(j + 1) == '*' -> {
                    val close = code.indexOf("*/", j + 2)
                    j = if (close < 0) n else close + 2
                }
                else -> j++
            }
        }
        return n
    }

    /** The end of the regular expression that starts at the `/` at [start], or null if it has no closing slash on its line. */
    private fun regexEnd(start: Int): Int? {
        var j = start + 1
        var escaped = false
        var inClass = false
        while (true) {
            if (j >= n || isNewLine(code[j])) return null
            val c = code[j]
            if (!escaped) {
                if (c == '[') inClass = true else if (c == ']' && inClass) inClass = false else if (c == '/' && !inClass) break
                escaped = c == '\\'
            } else {
                escaped = false
            }
            j++
        }
        j++
        while (j < n) {
            val cp = code.codePointAt(j)
            if (!isIdentifierChar(cp)) break
            j += Character.charCount(cp)
        }
        return j
    }
}
