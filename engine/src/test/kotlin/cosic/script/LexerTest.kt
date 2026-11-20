package cosic.script

import cosic.SourceRange
import cosic.parity.ParityData
import cosic.pattern.CosicError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LexerTest {
    private fun tokens(code: String): List<Token> = lexAll(code).tokens

    private fun types(code: String): List<Tt> = tokens(code).map { it.type }

    private fun values(code: String): List<Any?> = tokens(code).map { it.value }

    private fun lexError(code: String): CosicError = assertFailsWith<CosicError>("lexing '$code'") { lexAll(code) }

    private fun assertLexError(code: String, message: String, start: Int, end: Int? = null) {
        val e = lexError(code)
        assertEquals(message, e.message, "message for '$code'")
        assertEquals(start, e.range?.start, "position for '$code'")
        if (end != null) assertEquals(end, e.range?.end, "range end for '$code'")
    }

    // ---- words -----------------------------------------------------------------------------------------------------

    @Test
    fun wordsAreNamesAndKeywordsHaveTheirOwnTokenType() {
        assertEquals(listOf(Tt.NAME, Tt.IF, Tt.NAME, Tt.NAME, Tt.NAME, Tt.NAME), types("a if \$x _y é 𠮷"))
        assertEquals(listOf("a", "if", "\$x", "_y", "é", "𠮷"), values("a if \$x _y é 𠮷"))
        val keywords = "break case catch continue debugger default do else finally for function if return switch throw try var const " +
            "while with new this super class extends export import null true false in instanceof typeof void delete"
        assertTrue(types(keywords).none { it == Tt.NAME }, "every keyword has a token type of its own")
        assertEquals(keywords.split(' '), values(keywords))
    }

    @Test
    fun contextualAndStrictReservedWordsAreNames() {
        val words = "let async of get set static yield await enum implements interface package private protected public"
        assertTrue(types(words).all { it == Tt.NAME })
    }

    @Test
    fun unicodeEscapesInNamesAreDecoded() {
        val tokens = tokens("\\u0061bc a\\u{62}c")
        assertEquals(listOf("abc", "abc"), tokens.map { it.value })
        assertEquals(listOf(true, true), tokens.map { it.containsEsc })
        assertEquals(listOf(SourceRange(0, 8), SourceRange(9, 17)), tokens.map { SourceRange(it.start, it.end) })
        assertEquals(false, tokens("abc").single().containsEsc)
    }

    @Test
    fun aKeywordWrittenWithAnEscapeKeepsTheKeywordTypeAndIsFlagged() {
        val token = tokens("v\\u0061r").single()
        assertEquals(Tt.VAR, token.type)
        assertEquals(true, token.containsEsc)
    }

    @Test
    fun badEscapesInNamesAreErrors() {
        assertLexError("\\u0031a", "Invalid Unicode escape", 0)
        assertLexError("a\\u00", "Bad character escape sequence", 3)
        assertLexError("a\\x41", "Expecting Unicode escape sequence \\uXXXX", 2)
    }

    // ---- numbers ---------------------------------------------------------------------------------------------------

    @Test
    fun numbersHaveTheirJavaScriptValue() {
        val cases = listOf(
            "0" to 0.0, "7" to 7.0, "1.5" to 1.5, ".5" to 0.5, "5." to 5.0, "0." to 0.0, "1e3" to 1000.0, "1E+3" to 1000.0,
            "1e-3" to 0.001, ".5e2" to 50.0, "0x1F" to 31.0, "0XFF" to 255.0, "0o17" to 15.0, "0b101" to 5.0,
            "1_000_000" to 1_000_000.0, "1_0.0_1" to 10.01, "1e1_0" to 1e10, "0xFF_FF" to 65535.0,
            "9007199254740993" to 9007199254740992.0, "0xFFFFFFFFFFFFF800" to 18446744073709549568.0, "1e999" to Double.POSITIVE_INFINITY,
        )
        for ((code, value) in cases) {
            val token = tokens(code).single()
            assertEquals(Tt.NUM, token.type, code)
            assertEquals(value, token.value, "value of $code")
            assertEquals(SourceRange(0, code.length), SourceRange(token.start, token.end), "range of $code")
        }
    }

    @Test
    fun aNumberEndsBeforeTheDotOfAMemberAccess() {
        val code = "1..toString()"
        assertEquals(listOf("1.", ".", "toString", "(", ")"), tokens(code).map { code.substring(it.start, it.end) })
        assertEquals(listOf(Tt.NUM, Tt.DOT, Tt.NAME, Tt.PAREN_L, Tt.PAREN_R), types("1 .toString()"))
        assertEquals(listOf(Tt.PAREN_L, Tt.NUM, Tt.PAREN_R, Tt.DOT, Tt.NAME, Tt.PAREN_L, Tt.PAREN_R), types("(1).toString()"))
    }

    @Test
    fun malformedNumbersAreSyntaxErrorsAtAcornsPosition() {
        assertLexError("1e", "Invalid number", 0)
        assertLexError("1e+", "Invalid number", 0)
        assertLexError("0x;", "Expected number in radix 16", 2)
        assertLexError("0b2", "Expected number in radix 2", 2)
        assertLexError("017", "Invalid number", 0)
        assertLexError("089", "Invalid number", 0)
        assertLexError("1__0", "Numeric separator must be exactly one underscore", 2)
        assertLexError("1_", "Numeric separator is not allowed at the last of digits", 1)
        assertLexError("1._5", "Numeric separator is not allowed at the first of digits", 2)
        assertLexError("0_1", "Numeric separator is not allowed in legacy octal numeric literals", 1)
        assertLexError("1a", "Identifier directly after number", 1)
        assertLexError("0x1g", "Identifier directly after number", 3)
        assertLexError("1.5x", "Identifier directly after number", 3)
    }

    @Test
    fun bigIntLiteralsAreNotSupported() {
        assertLexError("10n", "BigInt literals are not supported", 0, 3)
        assertLexError("0x1n", "BigInt literals are not supported", 0, 4)
        assertLexError("a = 1_0n", "BigInt literals are not supported", 4, 8)
    }

    // ---- strings ---------------------------------------------------------------------------------------------------

    @Test
    fun stringsAreCookedAndKeepTheirQuotesInTheirRange() {
        val cases = listOf(
            "\"abc\"" to "abc",
            "'abc'" to "abc",
            "\"\"" to "",
            "\"a\\nb\\tc\\rd\\be\\ff\\vg\\0h\\\\i\\\"j\\'k\"" to "a\nb\tc\rd\be\u000cf\u000bg\u0000h\\i\"j'k",
            "\"\\x41\\x7a\"" to "Az",
            "\"\\u0041\\u00e9\"" to "A\u00e9",
            "\"\\u{41}\\u{1F600}\"" to "A\uD83D\uDE00",
            "'\\q\\a\\z'" to "qaz",
            "\"it's\"" to "it's",
            "'say \"x\"'" to "say \"x\"",
            "\"\uD83D\uDE00\"" to "\uD83D\uDE00",
            "'a\\\nb'" to "ab",
            "'a\\\r\nb'" to "ab",
            "'a\\\u2028b'" to "ab",
            "'a\u2028b'" to "a\u2028b",
        )
        for ((code, value) in cases) {
            val token = tokens(code).single()
            assertEquals(Tt.STRING, token.type, code)
            assertEquals(value, token.value, "value of ${code.replace("\n", "\\n")}")
            assertEquals(SourceRange(0, code.length), SourceRange(token.start, token.end))
        }
    }

    @Test
    fun malformedStringsAreSyntaxErrors() {
        assertLexError("\"abc", "Unterminated string constant", 0)
        assertLexError("'abc", "Unterminated string constant", 0)
        assertLexError("\"a\nb\"", "Unterminated string constant", 0)
        assertLexError("\"abc\\", "Unterminated string constant", 0)
        assertLexError("\"\\xZZ\"", "Bad character escape sequence", 3)
        assertLexError("\"\\x4\"", "Bad character escape sequence", 3)
        assertLexError("\"\\u12\"", "Bad character escape sequence", 3)
        assertLexError("\"\\u{41\"", "Bad character escape sequence", 4)
        assertLexError("\"\\u{110000}\"", "Code point out of bounds", 4)
        assertLexError("\"\\01\"", "Octal literal in strict mode", 1)
        assertLexError("\"\\377\"", "Octal literal in strict mode", 1)
        assertLexError("\"\\08\"", "Octal literal in strict mode", 1)
        assertLexError("\"\\8\"", "Invalid escape sequence", 2)
    }

    // ---- templates -------------------------------------------------------------------------------------------------

    @Test
    fun aTemplateIsTokenisedPieceByPiece() {
        val code = "`a\${b}c`"
        val tokens = tokens(code)
        assertEquals(
            listOf(Tt.BACK_QUOTE, Tt.TEMPLATE, Tt.DOLLAR_BRACE_L, Tt.NAME, Tt.BRACE_R, Tt.TEMPLATE, Tt.BACK_QUOTE),
            tokens.map { it.type },
        )
        assertEquals(listOf("`", "a", "\${", "b", "}", "c", "`"), tokens.map { code.substring(it.start, it.end) })
        assertEquals("a", tokens[1].value)
        assertEquals("c", tokens[5].value)
    }

    @Test
    fun emptyPiecesAreStillTokens() {
        assertEquals(listOf(Tt.BACK_QUOTE, Tt.TEMPLATE, Tt.BACK_QUOTE), types("``"))
        assertEquals(
            listOf(Tt.BACK_QUOTE, Tt.TEMPLATE, Tt.DOLLAR_BRACE_L, Tt.NAME, Tt.BRACE_R, Tt.TEMPLATE, Tt.BACK_QUOTE),
            types("`\${a}`"),
        )
        assertEquals(
            listOf(
                Tt.BACK_QUOTE, Tt.TEMPLATE, Tt.DOLLAR_BRACE_L, Tt.NAME, Tt.BRACE_R, Tt.TEMPLATE, Tt.DOLLAR_BRACE_L, Tt.NAME, Tt.BRACE_R,
                Tt.TEMPLATE, Tt.BACK_QUOTE,
            ),
            types("`\${a}\${b}`"),
        )
    }

    @Test
    fun templatesNestAndBracesInsideAnExpressionDoNotEndIt() {
        val code = "`a\${{ b: 1 }.b}c\${`d\${e}f`}g`"
        val pieces = tokens(code).filter { it.type == Tt.TEMPLATE }.map { it.value }
        assertEquals(listOf("a", "c", "d", "f", "g"), pieces)
        assertEquals(Tt.BACK_QUOTE, tokens(code).last().type)
        assertEquals(code.length, tokens(code).last().end)
    }

    @Test
    fun templatePiecesAreCookedWithNormalisedLineBreaks() {
        assertEquals(listOf("a\nb\nc\u2028d"), tokens("`a\r\nb\rc\u2028d`").filter { it.type == Tt.TEMPLATE }.map { it.value })
        assertEquals(listOf("a\nb`c\${d}\\"), tokens("`a\\nb\\`c\\\${d}\\\\`").filter { it.type == Tt.TEMPLATE }.map { it.value })
        assertEquals(listOf("AB"), tokens("`\\u{41}\\x42`").filter { it.type == Tt.TEMPLATE }.map { it.value })
        assertEquals(listOf("ab"), tokens("`a\\\nb`").filter { it.type == Tt.TEMPLATE }.map { it.value })
        assertEquals(listOf("\$ {a} \$"), tokens("`\$ {a} \$`").filter { it.type == Tt.TEMPLATE }.map { it.value })
        assertEquals(listOf("\u0000"), tokens("`\\0`").filter { it.type == Tt.TEMPLATE }.map { it.value })
    }

    @Test
    fun anInvalidEscapeMakesTheWholePieceAnInvalidTemplateToken() {
        for (code in listOf("`\\xZZ`", "`\\01`", "`\\08`", "`\\8`", "`\\u{`")) {
            val piece = tokens(code)[1]
            assertEquals(Tt.INVALID_TEMPLATE, piece.type, code)
            assertEquals(1, piece.start)
            assertEquals(code.length - 1, piece.end)
        }
        // The piece after an invalid one is read normally.
        assertEquals(
            listOf(Tt.BACK_QUOTE, Tt.INVALID_TEMPLATE, Tt.DOLLAR_BRACE_L, Tt.NAME, Tt.BRACE_R, Tt.TEMPLATE, Tt.BACK_QUOTE),
            types("`\\xZZ\${a}b`"),
        )
    }

    @Test
    fun unterminatedTemplatesAreSyntaxErrors() {
        assertLexError("`abc", "Unterminated template", 1)
        assertLexError("`a\${b}c", "Unterminated template", 6)
        assertLexError("`\\xZZ", "Unterminated template", 1)
    }

    // ---- punctuators -----------------------------------------------------------------------------------------------

    @Test
    fun everyPunctuatorIsOneTokenOfTheRightType() {
        val cases = listOf(
            "{" to Tt.BRACE_L, "}" to Tt.BRACE_R, "(" to Tt.PAREN_L, ")" to Tt.PAREN_R, "[" to Tt.BRACKET_L, "]" to Tt.BRACKET_R,
            ";" to Tt.SEMI, "," to Tt.COMMA, ":" to Tt.COLON, "." to Tt.DOT, "?" to Tt.QUESTION, "?." to Tt.QUESTION_DOT,
            "=>" to Tt.ARROW, "..." to Tt.ELLIPSIS, "=" to Tt.EQ, "++" to Tt.INC_DEC, "--" to Tt.INC_DEC, "!" to Tt.PREFIX, "~" to Tt.PREFIX,
            "||" to Tt.LOGICAL_OR, "&&" to Tt.LOGICAL_AND, "??" to Tt.COALESCE, "|" to Tt.BITWISE_OR, "^" to Tt.BITWISE_XOR,
            "&" to Tt.BITWISE_AND, "==" to Tt.EQUALITY, "!=" to Tt.EQUALITY, "===" to Tt.EQUALITY, "!==" to Tt.EQUALITY,
            "<" to Tt.RELATIONAL, ">" to Tt.RELATIONAL, "<=" to Tt.RELATIONAL, ">=" to Tt.RELATIONAL,
            "<<" to Tt.BIT_SHIFT, ">>" to Tt.BIT_SHIFT, ">>>" to Tt.BIT_SHIFT, "+" to Tt.PLUS_MIN, "-" to Tt.PLUS_MIN,
            "%" to Tt.MODULO, "*" to Tt.STAR, "**" to Tt.STAR_STAR,
            "+=" to Tt.ASSIGN, "-=" to Tt.ASSIGN, "*=" to Tt.ASSIGN, "/=" to Tt.ASSIGN, "%=" to Tt.ASSIGN, "**=" to Tt.ASSIGN,
            "<<=" to Tt.ASSIGN, ">>=" to Tt.ASSIGN, ">>>=" to Tt.ASSIGN, "&=" to Tt.ASSIGN, "|=" to Tt.ASSIGN, "^=" to Tt.ASSIGN,
            "&&=" to Tt.ASSIGN, "||=" to Tt.ASSIGN, "??=" to Tt.ASSIGN,
        )
        // Between two names, so that the slash is a division and none of the others starts something else.
        for ((text, type) in cases) {
            val token = tokens("a $text b")[1]
            assertEquals(type, token.type, "type of '$text'")
            assertEquals(SourceRange(2, 2 + text.length), SourceRange(token.start, token.end), "range of '$text'")
            // The operator text is the value of an operator token.
            if (type in setOf(Tt.EQ, Tt.ASSIGN, Tt.INC_DEC, Tt.PREFIX, Tt.LOGICAL_OR, Tt.LOGICAL_AND, Tt.COALESCE, Tt.BITWISE_OR, Tt.BITWISE_XOR,
                    Tt.BITWISE_AND, Tt.EQUALITY, Tt.RELATIONAL, Tt.BIT_SHIFT, Tt.PLUS_MIN, Tt.MODULO, Tt.STAR, Tt.STAR_STAR)
            ) {
                assertEquals(text, token.value, "value of '$text'")
            }
        }
        assertEquals(Tt.SLASH, tokens("a / b")[1].type)
        assertEquals("/", tokens("a / b")[1].value)
    }

    @Test
    fun theSlashAfterAnOperandIsADivision() {
        val code = "a / b / c"
        assertEquals(listOf(Tt.NAME, Tt.SLASH, Tt.NAME, Tt.SLASH, Tt.NAME), types(code))
        assertEquals(listOf(Tt.PAREN_L, Tt.NAME, Tt.PLUS_MIN, Tt.NAME, Tt.PAREN_R, Tt.SLASH, Tt.NUM), types("(a + b) / 2"))
        assertEquals(listOf(Tt.NAME, Tt.BRACKET_L, Tt.NUM, Tt.BRACKET_R, Tt.SLASH, Tt.NUM), types("a[0] / 2"))
        assertEquals(listOf(Tt.NUM, Tt.SLASH, Tt.NUM), types("4 / 2"))
        assertEquals(listOf(Tt.STRING, Tt.SLASH, Tt.NUM), types("'a' / 2"))
        assertEquals(listOf(Tt.THIS, Tt.SLASH, Tt.NUM), types("this / 2"))
        assertEquals(listOf(Tt.NAME, Tt.INC_DEC, Tt.SLASH, Tt.NUM), types("a++ / 2"))
        assertEquals(listOf(Tt.NAME, Tt.ASSIGN, Tt.NUM), types("a /= 2"))
        assertEquals(listOf(Tt.BACK_QUOTE, Tt.TEMPLATE, Tt.BACK_QUOTE, Tt.SLASH, Tt.NUM), types("`a` / 2"))
    }

    @Test
    fun theDotBeforeADigitStartsANumberAndTheOptionalChainingDotDoesNot() {
        assertEquals(listOf(Tt.NAME, Tt.QUESTION_DOT, Tt.NAME), types("a?.b"))
        assertEquals(listOf(Tt.NAME, Tt.QUESTION, Tt.NUM, Tt.COLON, Tt.NUM), types("a?.5:1"))
        assertEquals(listOf(Tt.DOT, Tt.NAME), types(".b"))
        assertEquals(listOf(Tt.NUM), types(".5"))
    }

    // ---- regular expressions ---------------------------------------------------------------------------------------

    @Test
    fun aRegularExpressionIsAnErrorThatCoversTheWholeLiteral() {
        assertLexError("/ab+c/g", "Regular expressions are not supported", 0, 7)
        assertLexError("f(/x/)", "Regular expressions are not supported", 2, 5)
        assertLexError("a = /[/]/i", "Regular expressions are not supported", 4, 10)
        assertLexError("x = /a\\/b/", "Regular expressions are not supported", 4, 10)
        assertLexError("typeof /x/", "Regular expressions are not supported", 7, 10)
        assertLexError("return /x/", "Regular expressions are not supported", 7, 10)
    }

    @Test
    fun anUnterminatedRegularExpressionIsReportedAtItsSlash() {
        assertLexError("a = /ab", "Regular expressions are not supported", 4, 5)
        assertLexError("a = /ab\ncd/", "Regular expressions are not supported", 4, 5)
    }

    // ---- errors ----------------------------------------------------------------------------------------------------

    @Test
    fun unexpectedCharactersAreReportedWithTheCharacter() {
        assertLexError("@a", "Unexpected character '@'", 0, 1)
        assertLexError("a \\x", "Expecting Unicode escape sequence \\uXXXX", 3)
        assertLexError("a \\", "Expecting Unicode escape sequence \\uXXXX", 2, 3) // an error at the very end sits on the last character
        assertLexError("# a", "Unexpected character ' '", 1)
        assertLexError("a \u00bf", "Unexpected character '\u00bf'", 2, 3)
        assertLexError("a \uD83D\uDE00", "Unexpected character '\uD83D\uDE00'", 2, 4)
    }

    @Test
    fun privateNamesAreNotSupported() {
        assertLexError("#a", "Private identifiers are not supported", 0, 2)
        assertLexError("a.#bc", "Private identifiers are not supported", 2, 5)
    }

    // ---- comments and white space --------------------------------------------------------------------------------

    @Test
    fun commentsAreCollectedWithTheirTextAndRange() {
        val code = "a // line\n/* block\nmore */ b"
        val result = lexAll(code)
        assertEquals(listOf("a", "b"), result.tokens.map { it.value })
        assertEquals(2, result.comments.size)
        val (line, block) = result.comments
        assertEquals(false, line.block)
        assertEquals(" line", line.value)
        assertEquals(SourceRange(2, 9), SourceRange(line.start, line.end))
        assertEquals(true, block.block)
        assertEquals(" block\nmore ", block.value)
        assertEquals(SourceRange(10, 26), SourceRange(block.start, block.end))
        assertEquals("/* block\nmore */", code.substring(block.start, block.end))
    }

    @Test
    fun aLineCommentEndsAtAnyLineTerminator() {
        for (terminator in listOf("\n", "\r", "\r\n", "\u2028", "\u2029")) {
            val result = lexAll("a // c$terminator" + "b")
            assertEquals(listOf(" c"), result.comments.map { it.value })
            assertEquals(listOf("a", "b"), result.tokens.map { it.value })
            assertEquals(true, result.tokens[1].nlBefore, "line break $terminator")
        }
    }

    @Test
    fun theNewlineBeforeFlagSeesLineBreaksInWhiteSpaceAndInComments() {
        fun flags(code: String) = tokens(code).map { it.nlBefore }
        assertEquals(listOf(false, true), flags("a\nb"))
        assertEquals(listOf(false, true), flags("a\r\nb"))
        assertEquals(listOf(false, true), flags("a\rb"))
        assertEquals(listOf(false, true), flags("a\u2028b"))
        assertEquals(listOf(false, false), flags("a b"))
        assertEquals(listOf(false, false), flags("a /* x */ b"))
        assertEquals(listOf(false, true), flags("a /* x\ny */ b"))
        assertEquals(listOf(false, true), flags("a // x\nb"))
        assertEquals(listOf(false, true, false), flags("a\nb c"))
        assertEquals(true, tokens("\na")[0].nlBefore)
    }

    @Test
    fun whiteSpaceIncludesTheUnicodeSpacesAndTheByteOrderMark() {
        assertEquals(listOf("a", "b"), values("a\u00a0\ufeff\u2003\u3000\t\u000b\u000cb"))
        assertEquals(3, tokens("a\u00a0+\u00a0b").size)
    }

    @Test
    fun anUnterminatedBlockCommentIsAnErrorAtItsStart() {
        assertLexError("a /* never closed", "Unterminated comment", 2, 17)
        assertLexError("/* never closed\n", "Unterminated comment", 0)
    }

    @Test
    fun anEmptyProgramHasNoTokens() {
        assertTrue(tokens("").isEmpty())
        assertTrue(tokens("  \n\t ").isEmpty())
        assertTrue(lexAll("// only a comment").tokens.isEmpty())
    }

    @Test
    fun theEofTokenSitsAtTheEndOfTheCode() {
        val lexer = Lexer("a ")
        assertEquals(Tt.NAME, lexer.next().type)
        val eof = lexer.next()
        assertEquals(Tt.EOF, eof.type)
        assertEquals(2, eof.start)
        assertEquals(2, eof.end)
    }

    @Test
    fun theLexerReportsTheFirstErrorOnlyWhenItReachesIt() {
        val lexer = Lexer("a @ b")
        assertEquals("a", lexer.next().value)
        assertFailsWith<CosicError> { lexer.next() }
    }

    // ---- tokenize (public, tolerant, for highlighting) ------------------------------------------------------------

    private fun kinds(code: String): List<TokenKind> = tokenize(code).map { it.kind }

    private fun texts(code: String): List<String> = tokenize(code).map { code.substring(it.start, it.end) }

    @Test
    fun snippetKinds() {
        val code = "// hi\n\$: s(\"bd sd\").fast(2) // x"
        assertEquals(
            listOf(
                TokenKind.COMMENT, TokenKind.LABEL, TokenKind.PUNCT, TokenKind.CALL, TokenKind.PUNCT, TokenKind.MINI,
                TokenKind.PUNCT, TokenKind.PUNCT, TokenKind.CALL, TokenKind.PUNCT, TokenKind.NUMBER, TokenKind.PUNCT,
                TokenKind.COMMENT,
            ),
            kinds(code),
        )
        assertEquals(listOf("// hi", "$", ":", "s", "(", "\"bd sd\"", ")", ".", "fast", "(", "2", ")", "// x"), texts(code))
    }

    @Test
    fun numbersOfEveryFormAreOneNumberTokenWithoutTheirSign() {
        assertEquals(listOf("12", "0.5", "1."), texts("12 0.5 1."))
        assertEquals(listOf(TokenKind.NUMBER, TokenKind.NUMBER, TokenKind.NUMBER), kinds("12 0.5 1."))
        assertEquals(listOf("x", "*", ".5"), texts("x*.5"))
        assertEquals(listOf(TokenKind.IDENT, TokenKind.PUNCT, TokenKind.NUMBER), kinds("x*.5"))
        assertEquals(listOf("fast", "(", ".5", ")"), texts("fast(.5)"))
        assertEquals(listOf("[", "1", ",", ".5", "]"), texts("[1, .5]"))
        for (number in listOf("0x1F", "0XFF", "0b101", "0o17", "1_000", "1e3", "1E-3", ".5e2", "5.", "1_0.0_1", "0xFF_FF")) {
            assertEquals(listOf(number), texts(number), number)
            assertEquals(listOf(TokenKind.NUMBER), kinds(number), number)
        }
        assertEquals(listOf("-", "1"), texts("-1"))
        assertEquals(listOf(TokenKind.PUNCT, TokenKind.NUMBER), kinds("-1"))
        assertEquals(listOf("1.", ".", "toString", "(", ")"), texts("1..toString()"))
    }

    @Test
    fun callNeedsTheParenthesisRightAfterTheName() {
        assertEquals(listOf(TokenKind.CALL, TokenKind.PUNCT, TokenKind.NUMBER, TokenKind.PUNCT), kinds("foo(1)"))
        assertEquals(listOf(TokenKind.IDENT, TokenKind.PUNCT, TokenKind.NUMBER, TokenKind.PUNCT), kinds("foo (1)"))
        assertEquals(TokenKind.CALL, tokenize("x.fast(2)")[2].kind)
    }

    @Test
    fun keywordsAreNeverCalls() {
        for (keyword in listOf("if", "for", "while", "switch", "catch", "function", "return", "typeof", "await", "yield")) {
            assertEquals(TokenKind.IDENT, tokenize("$keyword(a)")[0].kind, keyword)
        }
        assertEquals(TokenKind.CALL, tokenize("async(a)")[0].kind)
        assertEquals(TokenKind.CALL, tokenize("a.if(b)")[2].kind)
    }

    @Test
    fun labelsAreIdentifiersBeforeAColonAtTheStartOfAStatement() {
        assertEquals(TokenKind.LABEL, tokenize("\$: s(\"a\")")[0].kind)
        assertEquals(TokenKind.LABEL, tokenize("bass: s(\"a\")")[0].kind)
        assertEquals(TokenKind.LABEL, tokenize("_bass: s(\"a\")")[0].kind)
        assertEquals(TokenKind.LABEL, tokenize("_\$: s(\"a\")")[0].kind)
        assertEquals(TokenKind.LABEL, tokenize("x = 1\nbass: s(\"a\")").first { it.start == 6 }.kind)
        assertEquals(TokenKind.LABEL, tokenize("x = 1; bass: s(\"a\")").first { it.start == 7 }.kind)
        assertEquals(TokenKind.LABEL, tokenize("{ bass: s(\"a\") }").first { it.start == 2 }.kind)
        assertEquals(TokenKind.LABEL, tokenize("if (a) { b }\nc: d").first { it.start == 13 }.kind)
        assertEquals(TokenKind.LABEL, tokenize("a() // c\nb: c").first { it.start == 9 }.kind)
    }

    @Test
    fun aColonInTheMiddleOfAnExpressionIsNotALabel() {
        assertEquals(TokenKind.IDENT, tokenize("foo(x: 1)")[2].kind)
        assertEquals(TokenKind.IDENT, tokenize("\$ : s(\"a\")")[0].kind)
        assertEquals(TokenKind.IDENT, tokenize("a ? b: c")[2].kind)
        assertEquals(TokenKind.IDENT, tokenize("f({ a: 1 })").first { it.start == 4 }.kind)
        assertEquals(TokenKind.IDENT, tokenize("f([ a: 1 ])").first { it.start == 4 }.kind)
        assertEquals(TokenKind.IDENT, tokenize("x = a.b: 1").first { it.start == 6 }.kind)
    }

    @Test
    fun stringKinds() {
        assertEquals(listOf(TokenKind.MINI), kinds("\"a b\""))
        assertEquals(listOf(TokenKind.MINI), kinds("`a\nb`"))
        assertEquals(listOf(TokenKind.STRING), kinds("'a b'"))
        assertEquals(listOf("`a\nb`"), texts("`a\nb`"))
        assertTrue(TokenKind.STRING in tokenize("s('x')").map { it.kind })
        assertEquals(listOf("'a\u2028b'"), texts("'a\u2028b'"))
    }

    @Test
    fun aStringEndsAtItsClosingQuoteNotAtAnEscapedOne() {
        assertEquals(listOf("\"a\\\"b\"", "c"), texts("\"a\\\"b\" c"))
        assertEquals(listOf("'it\\'s'", "c"), texts("'it\\'s' c"))
        assertEquals(listOf(TokenKind.MINI, TokenKind.IDENT), kinds("\"a\\\\\" c"))
    }

    @Test
    fun aTemplateWithExpressionsIsOneMiniSpan() {
        val code = "`a\${b + `c\${d}`}e` f"
        assertEquals(listOf("`a\${b + `c\${d}`}e`", "f"), texts(code))
        assertEquals(listOf(TokenKind.MINI, TokenKind.IDENT), kinds(code))
        val braces = "`a\${ {x: \"}\"} }b` c"
        assertEquals(listOf("`a\${ {x: \"}\"} }b`", "c"), texts(braces))
    }

    @Test
    fun blockAndLineCommentsAreTokens() {
        assertEquals(listOf(TokenKind.COMMENT, TokenKind.COMMENT), kinds("// c\n/* d\ne */"))
        assertEquals(listOf("// c", "/* d\ne */"), texts("// c\n/* d\ne */"))
        assertEquals(listOf("// c", "a"), texts("// c\r\na"))
    }

    @Test
    fun unterminatedStringsAndCommentsRunToTheEndOfTheCode() {
        assertEquals(listOf(SourceRange(0, 1), SourceRange(1, 2), SourceRange(2, 5)), tokenize("s(\"bd").map { SourceRange(it.start, it.end) })
        assertEquals(TokenKind.MINI, tokenize("s(\"bd").last().kind)
        assertEquals(TokenKind.STRING, tokenize("s('x").last().kind)
        assertEquals(SourceRange(0, 5), tokenize("\"a\nb\"").first().let { SourceRange(it.start, it.end) })
        assertEquals(TokenKind.COMMENT, tokenize("a /* b").last().kind)
        assertEquals(SourceRange(2, 6), tokenize("a /* b").last().let { SourceRange(it.start, it.end) })
        assertEquals(TokenKind.MINI, tokenize("`a\${b").last().kind)
        assertEquals(SourceRange(0, 5), tokenize("`a\${b").last().let { SourceRange(it.start, it.end) })
    }

    @Test
    fun aRegularExpressionIsOneSpanSoItsQuotesDoNotRunAway() {
        val code = "x = /a\"b/g; y"
        assertEquals(listOf("x", "=", "/a\"b/g", ";", "y"), texts(code))
        assertEquals(TokenKind.STRING, kinds(code)[2])
        assertEquals(listOf("a", "/", "b", "/", "c"), texts("a / b / c"))
        assertEquals(listOf("(", "a", "+", "b", ")", "/", "2"), texts("(a + b) / 2"))
    }

    @Test
    fun operatorsPunctuationAndUnknownCharactersArePunctuation() {
        assertEquals(listOf("=>", "===", "...", "?.", "**="), texts("=> === ... ?. **="))
        assertEquals(listOf(TokenKind.PUNCT, TokenKind.PUNCT, TokenKind.PUNCT, TokenKind.PUNCT, TokenKind.PUNCT), kinds("=> === ... ?. **="))
        assertEquals(listOf(TokenKind.IDENT, TokenKind.PUNCT, TokenKind.IDENT), kinds("a @ b"))
        assertEquals(listOf("a", "#", "b"), texts("a # b"))
        assertEquals(listOf("\u00bf"), texts("\u00bf"))
    }

    @Test
    fun unicodeWordsAreIdentifiers() {
        assertEquals(listOf(TokenKind.IDENT, TokenKind.IDENT, TokenKind.IDENT), kinds("é 𠮷 \\u0061bc"))
    }

    @Test
    fun noNestingOfTemplatesBrokenCommentsOrBracketsOverflowsTheHighlighter() {
        val depth = 200_000
        for (code in listOf("`\${".repeat(depth), "`\${`".repeat(depth), "`\${{".repeat(depth), "(".repeat(depth), "{".repeat(depth) + "`", "\"".repeat(depth), "/*".repeat(depth))) {
            val spans = assertSpansAreSound(code)
            assertTrue(spans.isNotEmpty())
        }
        // Within the limit, nested templates are followed to their end.
        val nested = "`a\${`b\${`c\${d}`}`}` e"
        assertEquals(listOf(nested.substringBefore(" e"), "e"), texts(nested))
    }

    @Test
    fun neverThrowsAndSpansNeverOverlap() {
        val pool = "ab1 .\n\t\"'`\$:;()[],=>+-*/@#%^&{}|\\~?!\u00e9/*"
        var seed = 12345L
        repeat(500) {
            val code = buildString {
                repeat(1 + Math.floorMod(seed, 40L).toInt()) {
                    seed = seed * 6364136223846793005L + 1442695040888963407L
                    append(pool[((seed ushr 33) % pool.length).toInt()])
                }
            }
            assertSpansAreSound(code)
        }
    }

    @Test
    fun neverThrowsOnTruncatedPrograms() {
        val programs = ParityData.cases.flatMap { it.codes }
        var seed = 99L
        repeat(200) {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            val program = programs[((seed ushr 33) % programs.size).toInt()]
            seed = seed * 6364136223846793005L + 1442695040888963407L
            val cut = ((seed ushr 33) % (program.length + 1)).toInt()
            assertSpansAreSound(program.substring(0, cut))
        }
    }

    @Test
    fun everyProgramOfTheCorpusTokenisesToSoundSpansThatCoverAllNonWhiteSpace() {
        for (case in ParityData.cases) for (code in case.codes) {
            val spans = assertSpansAreSound(code)
            val covered = BooleanArray(code.length)
            for (s in spans) for (i in s.start until s.end) covered[i] = true
            for (i in code.indices) {
                if (!code[i].isWhitespace() && !covered[i]) throw AssertionError("${case.id}: character ${code[i]} at $i is not in any span")
            }
        }
    }

    private fun assertSpansAreSound(code: String): List<TokenSpan> {
        val spans = tokenize(code)
        var previousEnd = 0
        for (span in spans) {
            assertTrue(span.start >= previousEnd && span.end > span.start && span.end <= code.length, "bad span $span in '$code'")
            previousEnd = span.end
        }
        return spans
    }

    @Test
    fun anEmptyProgramHasNoSpans() {
        assertEquals(emptyList(), tokenize(""))
        assertEquals(emptyList(), tokenize(" \n\t "))
    }
}
