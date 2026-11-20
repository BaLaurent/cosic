package cosic.script.tidal

/*
 * The lexer of the Haskell that `tidal` accepts. Strudel parses that Haskell with tree-sitter-haskell 0.23.1 (hs2js,
 * packages/hs2js), whose tokens are made by the grammar's own lexer and by an external scanner (src/scanner.c) that decides
 * everything that depends on the whitespace around a symbol. This is a port of the part of it that an expression needs:
 *
 *  - an identifier ends in any number of `#` (MagicHash) and a literal in at most two, so `s "bd"#speed 2` has the string
 *    `"bd"#` and `a#b` is `a#` applied to `b`;
 *  - a `#` that starts a token and is followed by a letter is a label (`s "bd" #speed 2`), a `?` followed by a letter an
 *    implicit variable, a `$` or `$$` followed by a letter or `(` a splice (`f $g`, `f$g`);
 *  - a `.` that follows a token without whitespace and is followed by an identifier character is a record projection
 *    (`a.b`), also after a number (`fast 2.rev`); Foo.bar and Foo.Bar are qualified names;
 *  - `--` followed by dashes only (and then no symbol character) starts a comment, and so does `{-`.
 *
 * A token records whether whitespace came before it and, when a line break did, the indentation of its line: a token at
 * indentation 0 after a line break starts a new top level declaration (see [HsParser]).
 */

internal enum class HsTokenKind {
    VARID, CONID, QUALIFIED, INTEGER, FLOAT, STRING, CHAR,

    /** A symbolic operator that is not reserved: `$`, `#`, `|+|`, `<~`, `-`, `.`, ... */
    VARSYM,

    /** An operator that starts with `:`. */
    CONSYM,

    // reserved symbols
    EQUALS, BAR, BACKSLASH, DOTDOT, COLON2, LARROW, RARROW, DARROW,

    /** `$` or `$$` before a letter or `(`: a Template Haskell splice. */
    SPLICE,

    /** `#name`. */
    LABEL,

    /** `?name`. */
    IMPLICIT_VARIABLE,

    /** `@` after whitespace and before an opening token: a type application when it follows an operand, else an operator. */
    PREFIX_AT,

    /** The `'` of a Template Haskell name quote (`'f`), or the `''` of a type quote. */
    TH_QUOTE,

    /** The `.` of `a.b`. */
    TIGHT_DOT,

    LPAREN, RPAREN, LBRACKET, RBRACKET, LBRACE, RBRACE, COMMA, SEMI, BACKTICK,

    COMMENT, HADDOCK, PRAGMA,

    /** A character that is no token of the language, or a literal that is not closed. */
    ERROR,

    EOF,
}

/**
 * One token. [indent] is the indentation of the line the token is on when a line break came before it (the columns before
 * it, a tab counting 8), else -1. [spaceBefore] tells whether whitespace or a comment came before it.
 */
internal class HsToken(
    @JvmField val kind: HsTokenKind,
    @JvmField val start: Int,
    @JvmField val end: Int,
    @JvmField val text: String,
    @JvmField val spaceBefore: Boolean,
    @JvmField val indent: Int,
) {
    override fun toString(): String = "$kind[$start,$end]($text)"
}

/** The result of lexing: the tokens of the program (ending in an EOF token) and its comments, which are extras. */
internal class HsLexed(val tokens: List<HsToken>, val comments: List<HsToken>)

private const val SYMBOLS = "!#$%&*+./<=>?@\\^|-~:"

private fun isSymopChar(cp: Int): Boolean {
    if (cp < 128) return SYMBOLS.indexOf(cp.toChar()) >= 0
    return when (Character.getType(cp).toByte()) {
        Character.MATH_SYMBOL, Character.CURRENCY_SYMBOL, Character.MODIFIER_SYMBOL, Character.OTHER_SYMBOL,
        Character.DASH_PUNCTUATION, Character.OTHER_PUNCTUATION, Character.CONNECTOR_PUNCTUATION,
        -> true
        else -> false
    }
}

private fun isVaridStart(cp: Int): Boolean {
    if (cp == '_'.code) return true
    val type = Character.getType(cp).toByte()
    return type == Character.LOWERCASE_LETTER || type == Character.OTHER_LETTER
}

private fun isConidStart(cp: Int): Boolean {
    val type = Character.getType(cp).toByte()
    return type == Character.UPPERCASE_LETTER || type == Character.TITLECASE_LETTER
}

/** `[\pL\p{Mn}\pN_']` of the grammar, which is what an identifier may go on with. */
private fun isIdChar(cp: Int): Boolean {
    if (cp == '_'.code || cp == '\''.code) return true
    return when (Character.getType(cp).toByte()) {
        Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER, Character.MODIFIER_LETTER,
        Character.OTHER_LETTER, Character.NON_SPACING_MARK, Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER,
        Character.OTHER_NUMBER,
        -> true
        else -> false
    }
}

private fun isNewline(c: Char): Boolean = c == '\n' || c == '\r' || c == '\u000c'

private fun isHorizontalSpace(c: Char): Boolean =
    c == ' ' || c == '\t' || c == '\u000b' || Character.getType(c.code).toByte() == Character.SPACE_SEPARATOR

internal class HsLexer(private val src: String) {
    private val n = src.length
    private var i = 0
    private val tokens = ArrayList<HsToken>()
    private val comments = ArrayList<HsToken>()

    /** Whether whitespace (or a comment) has been skipped since the last token, and the indentation after the last line break. */
    private var space = false
    private var indent = -1

    fun lex(): HsLexed {
        while (true) {
            skipWhitespace()
            if (i >= n) break
            if (!lexExtra()) lexToken()
        }
        tokens.add(HsToken(HsTokenKind.EOF, n, n, "", space, indent))
        return HsLexed(tokens, comments)
    }

    private fun cp(at: Int): Int = if (at < n) src.codePointAt(at) else -1

    private fun cpLength(at: Int): Int = if (at < n) Character.charCount(src.codePointAt(at)) else 0

    private fun add(kind: HsTokenKind, start: Int, end: Int) {
        tokens.add(HsToken(kind, start, end, src.substring(start, end), space, indent))
        space = false
        indent = -1
    }

    // ---- whitespace and comments ---------------------------------------------------------------------------------------

    private fun skipWhitespace() {
        while (i < n) {
            val c = src[i]
            when {
                isNewline(c) -> {
                    indent = 0
                    space = true
                }
                c == '\t' -> {
                    if (indent >= 0) indent += 8
                    space = true
                }
                isHorizontalSpace(c) -> {
                    if (indent >= 0) indent++
                    space = true
                }
                else -> return
            }
            i++
        }
    }

    /** A comment or a pragma, which [skipWhitespace] has left in front of the next token; false when there is none. */
    private fun lexExtra(): Boolean {
        if (src.startsWith("{-", i)) {
            blockComment()
            return true
        }
        if (src.startsWith("--", i) && isLineCommentHerald(i)) {
            lineComment()
            return true
        }
        return false
    }

    /** `--` followed by dashes only, and then no symbol character (`-->` and `--|` are operators). */
    private fun isLineCommentHerald(at: Int): Boolean {
        var j = at + 2
        while (j < n && src[j] == '-') j++
        return j >= n || !isSymopChar(src.codePointAt(j))
    }

    /** `|` or `^` as the first character that follows the dashes (or the opening of a block comment) makes a comment a haddock. */
    private fun commentKind(at: Int): HsTokenKind {
        var j = at + 2
        while (j < n && src[j] == '-') j++
        while (j < n) {
            val c = src[j++]
            if (c == '|' || c == '^') return HsTokenKind.HADDOCK
            if (c != ' ' && c != '\t') break
        }
        return HsTokenKind.COMMENT
    }

    private fun addComment(kind: HsTokenKind, start: Int, end: Int) {
        comments.add(HsToken(kind, start, end, src.substring(start, end), space, indent))
        space = true
    }

    /** A line comment runs to the end of the line and takes the following lines that begin with a comment as well (scanner.c `inline_comment`). */
    private fun lineComment() {
        val start = i
        val kind = commentKind(i)
        while (true) {
            while (i < n && !isNewline(src[i])) i++
            if (i < n) i++ // the line break
            if (!(i < n && src.startsWith("--", i) && isLineCommentHerald(i))) break
        }
        // the line break is whitespace, not part of the comment
        var end = i
        if (end > start && isNewline(src[end - 1])) {
            end--
            i = end
        }
        addComment(kind, start, end)
        // a line comment ends its line: a line break after it is what the next token is indented by
        indent = -1
    }

    private fun blockComment() {
        val start = i
        val kind = if (src.startsWith("{-#", i)) HsTokenKind.PRAGMA else commentKind(i)
        if (kind == HsTokenKind.PRAGMA) {
            val close = src.indexOf("#-}", i + 3)
            i = if (close < 0) n else close + 3
        } else {
            var level = 0
            i += 2
            while (i < n) {
                if (src.startsWith("{-", i)) {
                    level++
                    i += 2
                } else if (src.startsWith("-}", i)) {
                    i += 2
                    if (level == 0) break
                    level--
                } else {
                    i++
                }
            }
        }
        // a token after the comment on the same line is indented by the columns the comment took
        if (indent >= 0) {
            var lastBreak = -1
            for (j in start until i) if (isNewline(src[j])) lastBreak = j
            indent = if (lastBreak < 0) indent + (i - start) else i - lastBreak - 1
        }
        addComment(kind, start, i)
    }

    // ---- tokens --------------------------------------------------------------------------------------------------------

    private fun lexToken() {
        val start = i
        val c = src[i]
        val code = cp(i)
        when {
            isVaridStart(code) -> identifier(start, isConid = false)
            isConidStart(code) -> identifier(start, isConid = true)
            c in '0'..'9' -> number(start)
            c == '"' -> string(start)
            c == '\'' -> char(start)
            isSymopChar(code) -> symbol(start)
            else -> {
                val kind = when (c) {
                    '(' -> HsTokenKind.LPAREN
                    ')' -> HsTokenKind.RPAREN
                    '[' -> HsTokenKind.LBRACKET
                    ']' -> HsTokenKind.RBRACKET
                    '{' -> HsTokenKind.LBRACE
                    '}' -> HsTokenKind.RBRACE
                    ',' -> HsTokenKind.COMMA
                    ';' -> HsTokenKind.SEMI
                    '`' -> HsTokenKind.BACKTICK
                    else -> HsTokenKind.ERROR
                }
                i += cpLength(i)
                add(kind, start, i)
            }
        }
    }

    private fun skipIdChars() {
        while (i < n && isIdChar(src.codePointAt(i))) i += Character.charCount(src.codePointAt(i))
    }

    private fun skipHashes(max: Int) {
        var count = 0
        while (i < n && src[i] == '#' && count < max) {
            i++
            count++
        }
    }

    /**
     * A variable or a constructor, `[start] [\pL\p{Mn}\pN_']* #*`. A constructor that is followed directly by a dot and
     * another name is a qualified name (`Foo.bar`, `Foo.Bar.baz`).
     */
    private fun identifier(start: Int, isConid: Boolean) {
        i += cpLength(i)
        skipIdChars()
        if (isConid) {
            var qualified = false
            while (i + 1 < n && src[i] == '.' && (isConidStart(cp(i + 1)) || isVaridStart(cp(i + 1)))) {
                qualified = true
                val nextIsCon = isConidStart(cp(i + 1))
                i++
                i += cpLength(i)
                skipIdChars()
                if (!nextIsCon) break
            }
            skipHashes(Int.MAX_VALUE)
            add(if (qualified) HsTokenKind.QUALIFIED else HsTokenKind.CONID, start, i)
        } else {
            skipHashes(Int.MAX_VALUE)
            add(HsTokenKind.VARID, start, i)
        }
    }

    private fun isDigit(c: Char, radix: Int): Boolean = Character.digit(c, radix) >= 0 && c.code < 128

    private fun skipWhile(from: Int, accept: (Char) -> Boolean): Int {
        var j = from
        while (j < n && accept(src[j])) j++
        return j
    }

    /** The literals of literal.js: binary, octal and hexadecimal integers, decimals and floats, all with `_` and a MagicHash. */
    private fun number(start: Int) {
        var j = start
        var kind = HsTokenKind.INTEGER
        val radixChar = if (src[start] == '0' && start + 1 < n) src[start + 1] else ' '
        var done = false
        if (radixChar == 'b' || radixChar == 'B') {
            val end = skipWhile(start + 2) { it == '0' || it == '1' || it == '_' }
            if (end > start + 2) {
                j = end
                done = true
            }
        } else if (radixChar == 'o' || radixChar == 'O') {
            val end = skipWhile(start + 2) { it in '0'..'7' || it == '_' }
            if (end > start + 2) {
                j = end
                done = true
            }
        } else if (radixChar == 'x' || radixChar == 'X') {
            val end = skipWhile(start + 2) { isDigit(it, 16) || it == '_' }
            if (end > start + 2) {
                j = end
                if (j + 1 < n && src[j] == '.' && (isDigit(src[j + 1], 16) || src[j + 1] == '_')) {
                    j = skipWhile(j + 1) { isDigit(it, 16) || it == '_' }
                }
                if (j < n && (src[j] == 'p' || src[j] == 'P')) {
                    var k = j + 1
                    if (k < n && (src[k] == '+' || src[k] == '-')) k++
                    val digits = skipWhile(k) { isDigit(it, 16) || it == '_' }
                    if (digits > k) j = digits
                }
                done = true
            }
        }
        if (!done) {
            j = skipWhile(start) { it in '0'..'9' || it == '_' }
            var k = j
            if (k + 1 < n && src[k] == '.' && (src[k + 1] in '0'..'9' || src[k + 1] == '_')) {
                k = skipWhile(k + 1) { it in '0'..'9' || it == '_' }
                kind = HsTokenKind.FLOAT
            }
            if (k < n && (src[k] == 'e' || src[k] == 'E')) {
                var m = k + 1
                if (m < n && (src[m] == '+' || src[m] == '-')) m++
                val digits = skipWhile(m) { it in '0'..'9' || it == '_' }
                if (digits > m) {
                    k = digits
                    kind = HsTokenKind.FLOAT
                }
            }
            j = k
        }
        i = j
        skipHashes(2)
        add(kind, start, i)
    }

    /** `"` then characters, `\` and the character after it, or a string gap (`\`, a line break, white space, `\`); no raw line break. */
    private fun string(start: Int) {
        i++
        while (i < n) {
            val c = src[i]
            if (c == '"') {
                i++
                skipHashes(2)
                add(HsTokenKind.STRING, start, i)
                return
            }
            if (c == '\n') break
            if (c == '\\') {
                if (i + 1 < n && src[i + 1] == '\n') {
                    var j = i + 2
                    while (j < n && (isHorizontalSpace(src[j]) || isNewline(src[j]))) j++
                    if (j < n && src[j] == '\\') {
                        i = j + 1
                        continue
                    }
                    break
                }
                i++
                if (i < n && src[i] == '^') i++
                if (i < n && src[i] != '\n') i += cpLength(i) else break
                continue
            }
            i += cpLength(i)
        }
        // the string is not closed: the rest of the line is an error
        while (i < n && src[i] != '\n') i++
        add(HsTokenKind.ERROR, start, i)
    }

    /** `'x'` or `'\...'` (`'[^']'` and `'\\[^ ]*'` of literal.js); anything else with a quote is a Template Haskell quote, which is not supported. */
    private fun char(start: Int) {
        if (start + 1 < n && src[start + 1] == '\\') {
            val runEnd = skipWhile(start + 2) { it != ' ' && it != '\n' }
            val close = src.lastIndexOf('\'', runEnd - 1)
            if (close >= start + 2) {
                i = close + 1
                skipHashes(2)
                add(HsTokenKind.CHAR, start, i)
                return
            }
        } else if (start + 1 < n && src[start + 1] != '\'') {
            val after = start + 1 + cpLength(start + 1)
            if (after < n && src[after] == '\'') {
                i = after + 1
                skipHashes(2)
                add(HsTokenKind.CHAR, start, i)
                return
            }
        }
        // a quote that is no character literal: a Template Haskell name quote (`'f`, `''T`), or an error
        val quoteLength = if (start + 1 < n && src[start + 1] == '\'') 2 else 1
        val nameStart = start + quoteLength
        if (nameStart < n && (isVaridStart(cp(nameStart)) || isConidStart(cp(nameStart)))) {
            i = nameStart
            add(HsTokenKind.TH_QUOTE, start, nameStart)
            return
        }
        i++
        add(HsTokenKind.ERROR, start, i)
    }

    private fun isOpeningToken(at: Int): Boolean {
        if (at >= n) return false
        val c = src[at]
        return when (c) {
            '(', '[', '"' -> true
            '{' -> !(at + 1 < n && src[at + 1] == '-')
            else -> isIdChar(src.codePointAt(at)) || c == '\u27e6' || c == '\u2987'
        }
    }

    /**
     * A run of symbol characters: one operator, unless it is reserved or one of the symbols whose meaning depends on its
     * neighbours (scanner.c `lex_symop` and `process_token_symop`).
     */
    private fun symbol(start: Int) {
        var end = start
        while (end < n && isSymopChar(src.codePointAt(end))) end += Character.charCount(src.codePointAt(end))
        val run = src.substring(start, end)
        val after = if (end < n) src.codePointAt(end) else -1
        val whitespaceBefore = space
        fun finish(kind: HsTokenKind, to: Int = end) {
            i = to
            add(kind, start, to)
        }
        if (run.length == 1) {
            when (run[0]) {
                '?' -> if (after >= 0 && isVaridStart(after)) {
                    var j = end + Character.charCount(after)
                    while (j < n && isIdChar(src.codePointAt(j))) j += Character.charCount(src.codePointAt(j))
                    return finish(HsTokenKind.IMPLICIT_VARIABLE, j)
                }
                '#' -> if (after >= 0 && isVaridStart(after)) {
                    var j = end + Character.charCount(after)
                    while (j < n && isIdChar(src.codePointAt(j))) j += Character.charCount(src.codePointAt(j))
                    return finish(HsTokenKind.LABEL, j)
                }
                '|' -> return finish(HsTokenKind.BAR)
                '=' -> return finish(HsTokenKind.EQUALS)
                '\\' -> return finish(HsTokenKind.BACKSLASH)
                '$' -> if (after >= 0 && (isVaridStart(after) || after == '('.code)) return finish(HsTokenKind.SPLICE)
                '.' -> if (after >= 0 && isIdChar(after) && !whitespaceBefore) {
                    return finish(HsTokenKind.TIGHT_DOT)
                } else if (whitespaceBefore && after >= 0 && (isIdChar(after) || isOpeningToken(end))) {
                    // a prefix dot (`a .b`): an operator whose text, in the tree, starts where the whitespace before it does
                    i = end
                    add(HsTokenKind.VARSYM, minOf(tokens.lastOrNull()?.end ?: start, start), end)
                    return
                }
                '@' -> if (whitespaceBefore && isOpeningToken(end)) return finish(HsTokenKind.PREFIX_AT)
            }
        } else if (run.length == 2) {
            when (run) {
                "->" -> return finish(HsTokenKind.RARROW)
                "=>" -> return finish(HsTokenKind.DARROW)
                "<-" -> return finish(HsTokenKind.LARROW)
                "::" -> return finish(HsTokenKind.COLON2)
                ".." -> return finish(HsTokenKind.DOTDOT)
                "$$" -> if (after >= 0 && (isVaridStart(after) || after == '('.code)) return finish(HsTokenKind.SPLICE)
            }
        }
        finish(if (run[0] == ':') HsTokenKind.CONSYM else HsTokenKind.VARSYM)
    }
}
