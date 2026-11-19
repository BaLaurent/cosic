package cosic.script

import cosic.SourceRange
import cosic.pattern.CosicError

internal enum class Tok { NUMBER, MINI, STR, IDENT, PUNCT, COMMENT, UNKNOWN }

/**
 * A scanned token. [start] and [end] are offsets into the code; [newlineBefore] tells whether a line break
 * separates it from the token before; [closed] is false for strings and block comments without an end.
 */
internal class Token(
    val kind: Tok,
    val text: String,
    val start: Int,
    val end: Int,
    val newlineBefore: Boolean,
    val closed: Boolean = true,
) {
    val range: SourceRange get() = SourceRange(start, end)

    /** The text between the quotes of a string token. */
    val content: String get() = text.substring(1, text.length - 1)

    fun isPunct(symbol: String): Boolean = kind == Tok.PUNCT && text == symbol
}

private const val PUNCTUATION = "()[],.:;=+-*/"

private fun isIdentStart(c: Char) = c in 'a'..'z' || c in 'A'..'Z' || c == '_' || c == '$'

private fun isIdentPart(c: Char) = isIdentStart(c) || c in '0'..'9'

/**
 * Splits [code] into tokens without ever failing: comments are tokens, and anything that is not valid
 * (unknown characters, strings or block comments without an end) is flagged on the token instead of thrown.
 * A string without an end runs to the end of the code.
 */
internal fun scan(code: String): List<Token> {
    val tokens = ArrayList<Token>()
    var i = 0
    var newline = false
    var previous: Token? = null

    fun emit(kind: Tok, end: Int, closed: Boolean = true) {
        val token = Token(kind, code.substring(i, end), i, end, newline, closed)
        tokens.add(token)
        if (kind != Tok.COMMENT) {
            newline = false
            previous = token
        }
        i = end
    }

    while (i < code.length) {
        val c = code[i]
        when {
            c == '\n' -> {
                newline = true
                i++
            }
            c.isWhitespace() -> i++
            c == '/' && code.startsWith("//", i) -> {
                val lineEnd = code.indexOf('\n', i)
                emit(Tok.COMMENT, if (lineEnd < 0) code.length else lineEnd)
            }
            c == '/' && code.startsWith("/*", i) -> {
                val close = code.indexOf("*/", i + 2)
                val end = if (close < 0) code.length else close + 2
                if (code.substring(i, end).contains('\n')) newline = true
                emit(Tok.COMMENT, end, closed = close >= 0)
            }
            c in '0'..'9' || (c == '.' && i + 1 < code.length && code[i + 1] in '0'..'9' && !endsExpression(previous)) ->
                emit(Tok.NUMBER, numberEnd(code, i))
            c == '"' || c == '\'' || c == '`' -> {
                val close = closingQuote(code, i)
                emit(if (c == '\'') Tok.STR else Tok.MINI, if (close < 0) code.length else close + 1, closed = close >= 0)
            }
            isIdentStart(c) -> {
                var end = i + 1
                while (end < code.length && isIdentPart(code[end])) end++
                emit(Tok.IDENT, end)
            }
            c == '=' && code.startsWith("=>", i) -> emit(Tok.PUNCT, i + 2)
            c in PUNCTUATION -> emit(Tok.PUNCT, i + 1)
            else -> emit(Tok.UNKNOWN, i + 1)
        }
    }
    return tokens
}

private fun endsExpression(token: Token?): Boolean = token != null &&
    (token.kind == Tok.IDENT || token.kind == Tok.NUMBER || token.kind == Tok.MINI || token.kind == Tok.STR ||
        token.isPunct(")") || token.isPunct("]"))

private fun numberEnd(code: String, start: Int): Int {
    var end = start
    while (end < code.length && code[end] in '0'..'9') end++
    if (end < code.length && code[end] == '.' && end + 1 < code.length && code[end + 1] in '0'..'9') {
        end++
        while (end < code.length && code[end] in '0'..'9') end++
    }
    return end
}

/** The index of the quote closing the string opened at [open], or -1. Only backtick strings may span lines. */
private fun closingQuote(code: String, open: Int): Int {
    val quote = code[open]
    var i = open + 1
    while (i < code.length) {
        val c = code[i]
        if (c == quote) return i
        if (c == '\n' && quote != '`') return -1
        i++
    }
    return -1
}

/** The tokens of a program for the parser, without comments. Throws [CosicError] for malformed input. */
internal fun lex(code: String): List<Token> {
    val tokens = ArrayList<Token>()
    for (token in scan(code)) {
        when {
            token.kind == Tok.COMMENT -> if (!token.closed) throw CosicError("Unclosed comment", SourceRange(token.start, code.length))
            (token.kind == Tok.MINI || token.kind == Tok.STR) && !token.closed ->
                throw CosicError("Unclosed string", SourceRange(token.start, code.length))
            token.kind == Tok.UNKNOWN -> throw CosicError("Syntax error: unexpected '${token.text}'", token.range)
            else -> tokens.add(token)
        }
    }
    return tokens
}

/** What a stretch of code is, for syntax highlighting. */
public enum class TokenKind { COMMENT, MINI, STRING, NUMBER, IDENT, CALL, LABEL, PUNCT }

/** A coloured stretch of code: `[start, end)` are character offsets. */
public data class TokenSpan(val start: Int, val end: Int, val kind: TokenKind)

/**
 * Splits code into highlightable spans for the editor. It never fails, whatever the code looks like:
 * an unterminated string simply runs to the end of the code. Whitespace is not reported.
 *
 * A `CALL` is a name directly followed by `(`; a `LABEL` is a name (or `$`) directly followed by `:`
 * at the start of a statement.
 */
public fun tokenize(code: String): List<TokenSpan> {
    val tokens = scan(code)
    val spans = ArrayList<TokenSpan>(tokens.size)
    var previous: Token? = null
    for (token in tokens) {
        val kind = when (token.kind) {
            Tok.COMMENT -> TokenKind.COMMENT
            Tok.MINI -> TokenKind.MINI
            Tok.STR -> TokenKind.STRING
            Tok.NUMBER -> TokenKind.NUMBER
            Tok.IDENT -> when {
                code.startsWith("(", token.end) -> TokenKind.CALL
                code.startsWith(":", token.end) && startsStatement(previous, token) -> TokenKind.LABEL
                else -> TokenKind.IDENT
            }
            Tok.PUNCT, Tok.UNKNOWN -> TokenKind.PUNCT
        }
        spans.add(TokenSpan(token.start, token.end, kind))
        if (token.kind != Tok.COMMENT) previous = token
    }
    return spans
}

private fun startsStatement(previous: Token?, token: Token): Boolean =
    previous == null || token.newlineBefore || previous.isPunct(";")
