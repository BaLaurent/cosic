package cosic.script

import cosic.SourceRange
import cosic.pattern.CosicError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LexerTest {
    private fun kinds(code: String): List<TokenKind> = tokenize(code).map { it.kind }

    private fun texts(code: String): List<String> = tokenize(code).map { code.substring(it.start, it.end) }

    private fun lexError(code: String): CosicError = assertFailsWith<CosicError> { lex(code) }

    // ---- tokenize (public, tolerant) ---------------------------------------------------

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
        assertEquals(
            listOf("// hi", "$", ":", "s", "(", "\"bd sd\"", ")", ".", "fast", "(", "2", ")", "// x"),
            texts(code),
        )
    }

    @Test
    fun numbers() {
        assertEquals(listOf("12", "0.5", "1", "."), texts("12 0.5 1."))
        assertEquals(listOf(TokenKind.NUMBER, TokenKind.NUMBER, TokenKind.NUMBER, TokenKind.PUNCT), kinds("12 0.5 1."))
        assertEquals(listOf("x", "*", ".5"), texts("x*.5"))
        assertEquals(listOf(TokenKind.IDENT, TokenKind.PUNCT, TokenKind.NUMBER), kinds("x*.5"))
        assertEquals(listOf("fast", "(", ".5", ")"), texts("fast(.5)"))
        assertEquals(listOf("[", "1", ",", ".5", "]"), texts("[1, .5]"))
    }

    @Test
    fun callNeedsTheParenthesisRightAfterTheName() {
        assertEquals(listOf(TokenKind.CALL, TokenKind.PUNCT, TokenKind.NUMBER, TokenKind.PUNCT), kinds("foo(1)"))
        assertEquals(listOf(TokenKind.IDENT, TokenKind.PUNCT, TokenKind.NUMBER, TokenKind.PUNCT), kinds("foo (1)"))
        assertEquals(TokenKind.CALL, tokenize("x.fast(2)")[2].kind)
    }

    @Test
    fun labelsAreIdentifiersBeforeAColonAtTheStartOfAStatement() {
        assertEquals(TokenKind.LABEL, tokenize("\$: s(\"a\")")[0].kind)
        assertEquals(TokenKind.LABEL, tokenize("bass: s(\"a\")")[0].kind)
        assertEquals(TokenKind.LABEL, tokenize("_bass: s(\"a\")")[0].kind)
        assertEquals(TokenKind.LABEL, tokenize("_\$: s(\"a\")")[0].kind)
        assertEquals(TokenKind.LABEL, tokenize("x = 1\nbass: s(\"a\")").first { it.start == 6 }.kind)
        assertEquals(TokenKind.LABEL, tokenize("x = 1; bass: s(\"a\")").first { it.start == 7 }.kind)
    }

    @Test
    fun aColonInTheMiddleOfAnExpressionIsNotALabel() {
        assertEquals(TokenKind.IDENT, tokenize("foo(x: 1)")[2].kind)
        assertEquals(TokenKind.IDENT, tokenize("\$ : s(\"a\")")[0].kind)
    }

    @Test
    fun stringKinds() {
        assertEquals(listOf(TokenKind.MINI), kinds("\"a b\""))
        assertEquals(listOf(TokenKind.MINI), kinds("`a\nb`"))
        assertEquals(listOf(TokenKind.STRING), kinds("'a b'"))
        assertEquals(listOf("`a\nb`"), texts("`a\nb`"))
    }

    @Test
    fun blockAndLineCommentsAreTokens() {
        assertEquals(listOf(TokenKind.COMMENT, TokenKind.COMMENT), kinds("// c\n/* d\ne */"))
        assertEquals(listOf("// c", "/* d\ne */"), texts("// c\n/* d\ne */"))
    }

    @Test
    fun unterminatedStringsRunToTheEndOfTheCode() {
        assertEquals(listOf(SourceRange(0, 1), SourceRange(1, 2), SourceRange(2, 5)), tokenize("s(\"bd").map { SourceRange(it.start, it.end) })
        assertEquals(TokenKind.MINI, tokenize("s(\"bd").last().kind)
        assertEquals(TokenKind.STRING, tokenize("s('x").last().kind)
        assertEquals(SourceRange(0, 5), tokenize("\"a\nb\"").first().let { SourceRange(it.start, it.end) })
        assertEquals(TokenKind.COMMENT, tokenize("a /* b").last().kind)
        assertEquals(SourceRange(2, 6), tokenize("a /* b").last().let { SourceRange(it.start, it.end) })
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
            val spans = tokenize(code)
            var previousEnd = 0
            for (span in spans) {
                assertTrue(span.start >= previousEnd && span.end > span.start && span.end <= code.length, "bad span $span in '$code'")
                previousEnd = span.end
            }
        }
    }

    // ---- lex (strict) -------------------------------------------------------------------

    @Test
    fun lexDropsCommentsAndKeepsTokens() {
        val tokens = lex("s(\"bd\") // c\n.fast(2) /* d */")
        assertEquals(listOf("s", "(", "\"bd\"", ")", ".", "fast", "(", "2", ")"), tokens.map { it.text })
    }

    @Test
    fun lexKeepsTheContentOfStrings() {
        val tokens = lex("\"a b\" 'c' `d\ne`")
        assertEquals(listOf("a b", "c", "d\ne"), tokens.map { it.content })
        assertEquals(listOf(Tok.MINI, Tok.STR, Tok.MINI), tokens.map { it.kind })
    }

    @Test
    fun lexRecordsNewlinesBeforeTokens() {
        assertEquals(listOf(false, false, false, false, true, false, false), lex("a (b)\n(c)").map { it.newlineBefore })
        assertEquals(false, lex("a (b)")[1].newlineBefore)
        assertEquals(true, lex("a\n(b)")[1].newlineBefore)
        assertEquals(true, lex("a // c\n(b)")[1].newlineBefore)
        assertEquals(true, lex("a /* \n */ (b)")[1].newlineBefore)
        assertEquals(false, lex("a /* x */ (b)")[1].newlineBefore)
    }

    @Test
    fun lexAcceptsMultiLineBackticks() {
        assertEquals("a\nb", lex("`a\nb`").single().content)
    }

    @Test
    fun lexReportsUnclosedStrings() {
        val e = lexError("s(\"bd")
        assertEquals("Unclosed string", e.message)
        assertEquals(SourceRange(2, 5), e.range)
        assertEquals(SourceRange(0, 5), lexError("'a\nb'").range)
        assertEquals("Unclosed string", lexError("`abc").message)
    }

    @Test
    fun lexReportsUnclosedBlockComments() {
        val e = lexError("a /* b")
        assertEquals("Unclosed comment", e.message)
        assertEquals(SourceRange(2, 6), e.range)
    }

    @Test
    fun lexReportsUnexpectedCharacters() {
        val e = lexError("a @ b")
        assertEquals("Syntax error: unexpected '@'", e.message)
        assertEquals(SourceRange(2, 3), e.range)
        assertEquals("Syntax error: unexpected '{'", lexError("{").message)
    }

    @Test
    fun lexKnowsTheArrowAndOtherPunctuation() {
        assertEquals(listOf("x", "=>", "x", "=", "+", "-", "*", "/", ";", ":", ",", "[", "]"), lex("x => x = + - * / ; : , [ ]").map { it.text })
    }
}
