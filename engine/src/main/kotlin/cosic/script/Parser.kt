package cosic.script

import cosic.SourceRange
import cosic.pattern.ArithOp
import cosic.pattern.CosicError

/** Parses a whole program. Throws [CosicError] with the range of the offending token. */
internal fun parseProgram(code: String): List<Stmt> = Parser(code, lex(code)).program()

/**
 * ```
 * program   := statement* EOF                       (';' is optional)
 * statement := ('let'|'const') IDENT '=' expr | IDENT ':' expr | expr
 * expr      := arrow | additive
 * arrow     := IDENT '=>' expr | '(' (IDENT (',' IDENT)*)? ')' '=>' expr
 * additive  := mult (('+'|'-') mult)*      mult := unary (('*'|'/') unary)*      unary := '-' unary | postfix
 * postfix   := primary ('.' IDENT '(' args ')' | '(' args ')')*
 * primary   := NUMBER | MINI | STR | IDENT | '[' args ']' | '(' expr ')' | true | false
 * ```
 * A statement ends where the next token cannot continue it. A `(` on a new line starts a new statement
 * instead of calling the expression before it.
 */
private class Parser(private val code: String, private val tokens: List<Token>) {
    private var pos = 0

    fun program(): List<Stmt> {
        val statements = ArrayList<Stmt>()
        while (true) {
            while (peek()?.isPunct(";") == true) pos++
            if (peek() == null) return statements
            statements.add(statement())
        }
    }

    private fun statement(): Stmt {
        val first = tokens[pos]
        if (first.kind == Tok.IDENT) {
            if (first.text == "let" || first.text == "const") {
                pos++
                val name = expect(Tok.IDENT)
                expectPunct("=")
                return Stmt.Let(name.text, expr())
            }
            if (tokens.getOrNull(pos + 1)?.isPunct(":") == true) {
                pos += 2
                return Stmt.Labeled(first.text, expr())
            }
        }
        return Stmt.Evaluate(expr())
    }

    private fun expr(): Expr {
        val first = peek() ?: unexpected()
        if (first.kind == Tok.IDENT && tokens.getOrNull(pos + 1)?.isPunct("=>") == true) {
            pos += 2
            return lambdaBody(listOf(first.text), first)
        }
        if (first.isPunct("(")) {
            val params = arrowParameters()
            if (params != null) return lambdaBody(params, first)
        }
        return additive()
    }

    /** The parameter names when the tokens at [pos] are `( names ) =>`; consumes them only then. */
    private fun arrowParameters(): List<String>? {
        var i = pos + 1
        val names = ArrayList<String>()
        if (tokens.getOrNull(i)?.isPunct(")") != true) {
            while (true) {
                val name = tokens.getOrNull(i)?.takeIf { it.kind == Tok.IDENT } ?: return null
                names.add(name.text)
                i++
                if (tokens.getOrNull(i)?.isPunct(",") == true) i++ else break
            }
        }
        if (tokens.getOrNull(i)?.isPunct(")") != true || tokens.getOrNull(i + 1)?.isPunct("=>") != true) return null
        pos = i + 2
        return names
    }

    private fun lambdaBody(params: List<String>, first: Token): Expr {
        val body = expr()
        return Expr.Lambda(params, body, SourceRange(first.start, body.range.end))
    }

    private fun additive(): Expr {
        var left = multiplicative()
        while (true) {
            val op = when {
                peek()?.isPunct("+") == true -> ArithOp.ADD
                peek()?.isPunct("-") == true -> ArithOp.SUB
                else -> return left
            }
            pos++
            val right = multiplicative()
            left = Expr.Binary(op, left, right, SourceRange(left.range.start, right.range.end))
        }
    }

    private fun multiplicative(): Expr {
        var left = unary()
        while (true) {
            val op = when {
                peek()?.isPunct("*") == true -> ArithOp.MUL
                peek()?.isPunct("/") == true -> ArithOp.DIV
                else -> return left
            }
            pos++
            val right = unary()
            left = Expr.Binary(op, left, right, SourceRange(left.range.start, right.range.end))
        }
    }

    private fun unary(): Expr {
        val minus = peek()
        if (minus != null && minus.isPunct("-")) {
            pos++
            val operand = unary()
            return Expr.Negate(operand, SourceRange(minus.start, operand.range.end))
        }
        return postfix()
    }

    private fun postfix(): Expr {
        var e = primary()
        while (true) {
            val t = peek() ?: return e
            if (t.isPunct(".")) {
                pos++
                val name = expect(Tok.IDENT)
                expectPunct("(")
                val args = arguments(")")
                val close = tokens[pos - 1]
                e = Expr.MethodCall(e, name.text, args, SourceRange(name.start, close.end))
            } else if (t.isPunct("(") && !t.newlineBefore) {
                pos++
                val args = arguments(")")
                val close = tokens[pos - 1]
                e = Expr.Call(e, args, SourceRange(e.range.start, close.end))
            } else {
                return e
            }
        }
    }

    /** Comma separated expressions up to and including [close]; a trailing comma is allowed. */
    private fun arguments(close: String): List<Expr> {
        val args = ArrayList<Expr>()
        while (peek()?.isPunct(close) != true) {
            args.add(expr())
            if (peek()?.isPunct(",") == true) pos++ else break
        }
        expectPunct(close)
        return args
    }

    private fun primary(): Expr {
        val t = peek() ?: unexpected()
        pos++
        return when (t.kind) {
            Tok.NUMBER -> Expr.Number(t.text.toDouble(), t.range)
            Tok.MINI -> Expr.Mini(t.content, t.start + 1, t.range)
            Tok.STR -> Expr.Text(t.content, t.range)
            Tok.IDENT -> when (t.text) {
                "true" -> Expr.Bool(true, t.range)
                "false" -> Expr.Bool(false, t.range)
                else -> Expr.Ident(t.text, t.range)
            }
            else -> when {
                t.isPunct("[") -> {
                    val items = arguments("]")
                    Expr.ArrayLit(items, SourceRange(t.start, tokens[pos - 1].end))
                }
                t.isPunct("(") -> {
                    val inner = expr()
                    expectPunct(")")
                    inner
                }
                else -> {
                    pos--
                    unexpected()
                }
            }
        }
    }

    // ---- token helpers ------------------------------------------------------------------

    private fun peek(): Token? = tokens.getOrNull(pos)

    private fun expect(kind: Tok): Token {
        val t = peek() ?: unexpected()
        if (t.kind != kind) unexpected()
        pos++
        return t
    }

    private fun expectPunct(symbol: String) {
        val t = peek() ?: unexpected()
        if (!t.isPunct(symbol)) unexpected()
        pos++
    }

    private fun unexpected(): Nothing {
        val t = peek() ?: throw CosicError(
            "Syntax error: unexpected end of code",
            SourceRange(maxOf(code.length - 1, 0), code.length),
        )
        throw CosicError("Syntax error: unexpected '${t.text}'", t.range)
    }
}
