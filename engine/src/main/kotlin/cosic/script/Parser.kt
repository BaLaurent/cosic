package cosic.script

import cosic.SourceRange
import cosic.pattern.CosicError

/*
 * The parser follows acorn 8.14 (MIT, https://github.com/acornjs/acorn) function by function, for ecmaVersion 2022 with
 * top-level await, so that it accepts the same programs, builds the same tree with the same offsets and reports the same
 * errors at the same positions. Differences, all deliberate:
 *
 *  - every program is strict code, because Strudel evaluates the transpiled code in strict mode (reserved words,
 *    `with`, legacy octals, duplicate parameters, `delete name`, `eval`/`arguments` as names);
 *  - classes, generators, regular expressions, BigInt, private names, `debugger`, `import()`, `new.target` and `for await`
 *    are errors with a message of their own: Cosic does not run them;
 *  - `const a;` is "Missing initializer in const declaration", on the name, where acorn says "Unexpected token";
 *  - `await` as a name at the top level, where it is an operator, is "Unexpected reserved word" (acorn lets it pass, and
 *    Strudel's async wrapper then rejects it); inside async functions acorn's own message stays;
 *  - nesting deeper than [MAX_DEPTH] is "Code is nested too deeply", so that no input can overflow the stack.
 *
 * The expression, pattern and function parts are in ParserExpressions.kt and ParserPatterns.kt, as extensions of [Parser].
 */

/** A parsed program with the source it came from and its comments. */
internal class ParseResult(val source: String, val program: Program, val comments: List<Comment>)

/** Parses [code] as a program. Throws a [CosicError] with the range of the offending code for a syntax error. */
internal fun parseProgram(code: String): ParseResult = Parser(code).parse()

/**
 * Parses [code] as the body of a function: the same as [parseProgram], but a `return` at the top level is allowed.
 * This is how a program that is run as a plain function (`Realm.runRaw`) is read.
 */
internal fun parseFunctionBody(code: String): ParseResult {
    val parser = Parser(code)
    parser.allowReturnOutsideFunction = true
    return parser.parse()
}

/** How many statements, expressions and unary operators may nest; deeper code is an error. */
internal const val MAX_DEPTH = 200

/** How long a chain of binary operators or of member accesses and calls may be; each link makes the tree one level deeper. */
internal const val MAX_CHAIN = 1000

/** Flags of a parse scope: acorn's SCOPE_*. [VAR] scopes are the ones `var` and function declarations land in. */
internal object ScopeFlag {
    const val TOP = 1
    const val FUNCTION = 2
    const val ASYNC = 4
    const val SIMPLE_CATCH = 32
    const val VAR = TOP or FUNCTION
}

/** What a name is declared as, or [NONE] when a target is only assigned to: acorn's BIND_*. */
internal object BindKind {
    const val NONE = 0
    const val VAR = 1
    const val LEXICAL = 2
    const val SIMPLE_CATCH = 4

    /** The name of a function expression, as seen from inside the function: checked, but not declared. */
    const val OUTSIDE = 5
}

internal val RESERVED_STRICT = setOf("enum", "implements", "interface", "let", "package", "private", "protected", "public", "static", "yield")
internal val RESERVED_STRICT_BIND = RESERVED_STRICT + setOf("eval", "arguments")

/** The names a scope has declared: `var`s and lexical declarations (a function declaration counts as one or the other). */
internal class ParseScope(val flags: Int) {
    val vars = ArrayList<String>()
    val lexical = ArrayList<String>()
}

/** A label in scope. [kind] is `loop` or `switch` when the labelled statement is one, so that `continue` and `break` can find it. */
internal class ParseLabel(val name: String?, var kind: String?, var statementStart: Int)

/**
 * Positions of errors that can only be decided later, when an expression turns out to be a pattern or not: `({ a = 1 })`
 * is an error unless it becomes the left side of an assignment. -1 means none.
 */
internal class DestructuringErrors {
    var shorthandAssign = -1
    var trailingComma = -1
    var parenthesizedAssign = -1
    var parenthesizedBind = -1
    var doubleProto = -1
}

internal class Parser(internal val input: String) {
    private val lexer = Lexer(input)

    /** The current token, and the one consumed before it. */
    internal var tok: Token = Token(Tt.EOF, 0, 0, null, false)
    internal var lastTok: Token = tok

    internal val lastTokStart: Int get() = lastTok.start
    internal val lastTokEnd: Int get() = lastTok.end

    internal var potentialArrowAt = -1
    internal var awaitPos = 0
    internal var awaitIdentPos = 0
    internal var labels = ArrayList<ParseLabel>()
    internal val scopeStack = ArrayList<ParseScope>()
    private var depth = 0

    /** Whether `return` is allowed at the top level (the program is the body of a function). */
    internal var allowReturnOutsideFunction = false

    fun parse(): ParseResult {
        scopeStack.add(ParseScope(ScopeFlag.TOP))
        tok = lexer.next()
        val body = ArrayList<Node>()
        while (tok.type !== Tt.EOF) body.add(parseStatement(null, true))
        return ParseResult(input, Program(0, input.length, body), lexer.comments)
    }

    // ---- tokens ---------------------------------------------------------------------------------------------------

    internal fun next(ignoreEscapeSequenceInKeyword: Boolean = false) {
        val keyword = tok.type.keyword
        if (!ignoreEscapeSequenceInKeyword && keyword != null && tok.containsEsc) raise(tok.start, "Escape sequence in keyword $keyword", tok.end)
        lastTok = tok
        tok = lexer.next()
    }

    internal fun eat(type: Tt): Boolean {
        if (tok.type === type) {
            next()
            return true
        }
        return false
    }

    internal fun isContextual(name: String): Boolean = tok.type === Tt.NAME && tok.value == name && !tok.containsEsc

    /** Whether a semicolon may be inserted before the current token: at `}`, at the end, or after a line break. */
    internal fun canInsertSemicolon(): Boolean = tok.type === Tt.EOF || tok.type === Tt.BRACE_R || tok.nlBefore

    internal fun insertSemicolon(): Boolean = canInsertSemicolon()

    internal fun semicolon() {
        if (!eat(Tt.SEMI) && !insertSemicolon()) unexpected()
    }

    internal fun afterTrailingComma(type: Tt, notNext: Boolean = false): Boolean {
        if (tok.type === type) {
            if (!notNext) next()
            return true
        }
        return false
    }

    internal fun expect(type: Tt) {
        if (!eat(type)) unexpected()
    }

    // ---- errors ---------------------------------------------------------------------------------------------------

    /**
     * Throws a syntax error at [pos], over [end] or, when that is not given, over the token that starts at [pos]. An error
     * at the end of the code is reported on the last character, so that there is something to point at.
     */
    internal fun raise(pos: Int, message: String, end: Int = -1): Nothing {
        val length = input.length
        val range = if (pos >= length) {
            SourceRange(maxOf(length - 1, 0), length)
        } else {
            val to = when {
                end > pos -> end
                pos == tok.start && tok.end > pos -> tok.end
                pos == lastTok.start && lastTok.end > pos -> lastTok.end
                else -> pos + 1
            }
            SourceRange(pos, minOf(to, length))
        }
        throw CosicError(message, range)
    }

    internal fun raiseAt(node: Node, message: String): Nothing = raise(node.start, message, node.end)

    /** The end of the regular expression literal that starts with the `/` at [start], if it has a closing slash on its line. */
    internal fun regexEnd(start: Int): Int? = lexer.regexEnd(start)

    internal fun unexpected(pos: Int = tok.start): Nothing = raise(pos, "Unexpected token")

    internal fun enter() {
        if (++depth > MAX_DEPTH) raise(tok.start, "Code is nested too deeply")
    }

    internal fun leave() {
        depth--
    }

    // ---- lookahead into the source -----------------------------------------------------------------------------------

    /** Whether the code between the end of the current token and [to] holds a line terminator. */
    private fun hasLineBreakBetween(from: Int, to: Int): Boolean {
        for (i in from until to) if (isNewLine(input[i])) return true
        return false
    }

    private fun charCodeAt(i: Int): Int = if (i < input.length) input[i].code else -1

    // ---- scopes -----------------------------------------------------------------------------------------------------

    internal fun enterScope(flags: Int) {
        scopeStack.add(ParseScope(flags))
    }

    internal fun exitScope() {
        scopeStack.removeAt(scopeStack.size - 1)
    }

    internal fun currentScope(): ParseScope = scopeStack[scopeStack.size - 1]

    internal fun currentVarScope(): ParseScope {
        var i = scopeStack.size - 1
        while (true) {
            val scope = scopeStack[i]
            if (scope.flags and ScopeFlag.VAR != 0) return scope
            i--
        }
    }

    internal val inFunction: Boolean get() = currentVarScope().flags and ScopeFlag.FUNCTION != 0
    internal val inAsync: Boolean get() = currentVarScope().flags and ScopeFlag.ASYNC != 0

    /** Whether `await` is an operator here: in an async function, or at the top level (outside of any function). */
    internal val canAwait: Boolean
        get() {
            for (i in scopeStack.size - 1 downTo 0) {
                val scope = scopeStack[i]
                if (scope.flags and ScopeFlag.FUNCTION != 0) return scope.flags and ScopeFlag.ASYNC != 0
            }
            return true
        }

    /** At the top level of a function or script, function declarations behave like `var`; in a block they are lexical (every program is strict). */
    internal val treatFunctionsAsVar: Boolean get() = currentScope().flags and ScopeFlag.VAR != 0

    internal fun declareName(name: String, bindingType: Int, start: Int, end: Int) {
        var redeclared = false
        when (bindingType) {
            BindKind.LEXICAL -> {
                val scope = currentScope()
                redeclared = name in scope.lexical || name in scope.vars
                scope.lexical.add(name)
            }
            BindKind.SIMPLE_CATCH -> currentScope().lexical.add(name)
            else -> {
                for (i in scopeStack.size - 1 downTo 0) {
                    val scope = scopeStack[i]
                    if (name in scope.lexical && !(scope.flags and ScopeFlag.SIMPLE_CATCH != 0 && scope.lexical[0] == name)) {
                        redeclared = true
                        break
                    }
                    scope.vars.add(name)
                    if (scope.flags and ScopeFlag.VAR != 0) break
                }
            }
        }
        if (redeclared) raise(start, "Identifier '$name' has already been declared", end)
    }

    // ---- statements -------------------------------------------------------------------------------------------------

    /**
     * Whether the current `let` starts a declaration. [context] is null where a declaration may stand and names the
     * enclosing statement (`if`, `for`, ...) where only a statement may.
     */
    private fun isLet(context: String? = null): Boolean {
        if (!isContextual("let")) return false
        val next = lexer.skipSpaceFrom(tok.end)
        val nextCh = charCodeAt(next)
        // `let [` is an explicit negative lookahead for an expression statement.
        if (nextCh == '['.code || nextCh == '\\'.code) return true
        if (context != null) return false
        if (nextCh == '{'.code || nextCh in 0xd800..0xdbff) return true
        if (next < input.length && isIdentifierStart(input.codePointAt(next))) {
            var pos = next + Character.charCount(input.codePointAt(next))
            while (pos < input.length && isIdentifierChar(input.codePointAt(pos))) pos += Character.charCount(input.codePointAt(pos))
            val after = charCodeAt(pos)
            if (after == '\\'.code || after in 0xd800..0xdbff) return true
            val ident = input.substring(next, pos)
            if (ident != "in" && ident != "instanceof") return true
        }
        return false
    }

    /** `async` directly followed (on its line) by `function`. */
    private fun isAsyncFunction(): Boolean {
        if (!isContextual("async")) return false
        val next = lexer.skipSpaceFrom(tok.end)
        if (hasLineBreakBetween(tok.end, next)) return false
        if (!input.startsWith("function", next)) return false
        val after = next + 8
        return after >= input.length || !(isIdentifierChar(input.codePointAt(after)) || input[after].code in 0xd800..0xdbff)
    }

    internal fun parseStatement(context: String?, topLevel: Boolean = false): Node {
        enter()
        val start = tok.start
        var startType = tok.type
        var kind: String? = null
        if (isLet(context)) {
            startType = Tt.VAR
            kind = "let"
        }
        val node: Node = when (startType) {
            Tt.BREAK, Tt.CONTINUE -> parseBreakContinueStatement(start, startType.keyword!!)
            Tt.DEBUGGER -> raise(start, "The debugger statement is not supported", tok.end)
            Tt.DO -> parseDoStatement(start)
            Tt.FOR -> parseForStatement(start)
            Tt.FUNCTION -> {
                // A function declaration cannot be the body of another statement in strict code.
                if (context != null) unexpected()
                next()
                parseFunction(start, statement = true, isAsync = false)
            }
            Tt.CLASS -> raise(start, "Classes are not supported", tok.end)
            Tt.IF -> parseIfStatement(start)
            Tt.RETURN -> parseReturnStatement(start)
            Tt.SWITCH -> parseSwitchStatement(start)
            Tt.THROW -> parseThrowStatement(start)
            Tt.TRY -> parseTryStatement(start)
            Tt.CONST, Tt.VAR -> {
                val declared = kind ?: tok.value as String
                if (context != null && declared != "var") unexpected()
                parseVarStatement(start, declKind(declared))
            }
            Tt.WHILE -> parseWhileStatement(start)
            Tt.WITH -> raise(start, "'with' in strict mode", tok.end)
            Tt.BRACE_L -> parseBlock(true)
            Tt.SEMI -> {
                next()
                EmptyStatement(start, lastTokEnd)
            }
            Tt.EXPORT, Tt.IMPORT -> {
                if (startType === Tt.IMPORT) {
                    // `import(...)` and `import.meta` are expressions.
                    val ch = charCodeAt(lexer.skipSpaceFrom(tok.end))
                    if (ch == '('.code || ch == '.'.code) return parseExpressionStatement(start, parseExpression()).also { leave() }
                }
                if (!topLevel) raise(start, "'import' and 'export' may only appear at the top level", tok.end)
                raise(start, "'import' and 'export' may appear only with 'sourceType: module'", tok.end)
            }
            else -> {
                if (isAsyncFunction()) {
                    if (context != null) unexpected()
                    next()
                    next()
                    parseFunction(start, statement = true, isAsync = true)
                } else {
                    val maybeName = tok.value
                    val expr = parseExpression()
                    if (startType === Tt.NAME && expr is Identifier && eat(Tt.COLON)) {
                        parseLabeledStatement(start, maybeName as String, expr, context)
                    } else {
                        parseExpressionStatement(start, expr)
                    }
                }
            }
        }
        leave()
        return node
    }

    private fun declKind(keyword: String): DeclKind = when (keyword) {
        "var" -> DeclKind.VAR
        "let" -> DeclKind.LET
        else -> DeclKind.CONST
    }

    private fun parseBreakContinueStatement(start: Int, keyword: String): Node {
        val isBreak = keyword == "break"
        val keywordEnd = tok.end
        next()
        var label: Identifier? = null
        if (eat(Tt.SEMI) || insertSemicolon()) {
            // no label
        } else if (tok.type !== Tt.NAME) {
            unexpected()
        } else {
            label = parseIdent()
            semicolon()
        }
        // Verify that there is something to break or continue.
        var i = 0
        while (i < labels.size) {
            val lab = labels[i]
            if (label == null || lab.name == label.name) {
                if (lab.kind != null && (isBreak || lab.kind == "loop")) break
                if (label != null && isBreak) break
            }
            i++
        }
        if (i == labels.size) raise(start, "Unsyntactic $keyword", keywordEnd)
        return if (isBreak) BreakStatement(start, lastTokEnd, label) else ContinueStatement(start, lastTokEnd, label)
    }

    private fun parseDoStatement(start: Int): Node {
        next()
        labels.add(ParseLabel(null, "loop", -1))
        val body = parseStatement("do")
        labels.removeAt(labels.size - 1)
        expect(Tt.WHILE)
        val test = parseParenExpression()
        eat(Tt.SEMI)
        return DoWhileStatement(start, lastTokEnd, body, test)
    }

    /**
     * Disambiguating `for`, `for-in` and `for-of`: the head is parsed as a declaration or, with `in` disallowed, as an
     * expression, and the token after it decides.
     */
    private fun parseForStatement(start: Int): Node {
        next()
        if (canAwait && isContextual("await")) raise(tok.start, "for await is not supported", tok.end)
        labels.add(ParseLabel(null, "loop", -1))
        enterScope(0)
        expect(Tt.PAREN_L)
        if (tok.type === Tt.SEMI) return parseFor(start, null)
        val isLet = isLet()
        if (tok.type === Tt.VAR || tok.type === Tt.CONST || isLet) {
            val initStart = tok.start
            val kind = if (isLet) DeclKind.LET else declKind(tok.value as String)
            next()
            val declarations = parseVar(isFor = true, kind)
            val init = VariableDeclaration(initStart, lastTokEnd, kind, declarations)
            if ((tok.type === Tt.IN || isContextual("of")) && declarations.size == 1) return parseForIn(start, init)
            return parseFor(start, init)
        }
        val startsWithLet = isContextual("let")
        val containsEsc = tok.containsEsc
        val refDestructuringErrors = DestructuringErrors()
        val initPos = tok.start
        val init = parseExpression(forInit = true, refDestructuringErrors)
        val isForOf = tok.type !== Tt.IN && isContextual("of")
        if (tok.type === Tt.IN || isForOf) {
            if (isForOf && init.start == initPos && !containsEsc && init is Identifier && init.name == "async") unexpected()
            if (startsWithLet && isForOf) raiseAt(init, "The left-hand side of a for-of loop may not start with 'let'.")
            val target = toAssignable(init, false, refDestructuringErrors)
            checkLValPattern(target)
            return parseForIn(start, target)
        }
        checkExpressionErrors(refDestructuringErrors, true)
        return parseFor(start, init)
    }

    private fun parseFor(start: Int, init: Node?): Node {
        expect(Tt.SEMI)
        val test = if (tok.type === Tt.SEMI) null else parseExpression()
        expect(Tt.SEMI)
        val update = if (tok.type === Tt.PAREN_R) null else parseExpression()
        expect(Tt.PAREN_R)
        val body = parseStatement("for")
        exitScope()
        labels.removeAt(labels.size - 1)
        return ForStatement(start, lastTokEnd, init, test, update, body)
    }

    private fun parseForIn(start: Int, left: Node): Node {
        val isForIn = tok.type === Tt.IN
        next()
        if (left is VariableDeclaration && left.declarations[0].init != null) {
            raiseAt(left, (if (isForIn) "for-in" else "for-of") + " loop variable declaration may not have an initializer")
        }
        val right = if (isForIn) parseExpression() else parseMaybeAssign()
        expect(Tt.PAREN_R)
        val body = parseStatement("for")
        exitScope()
        labels.removeAt(labels.size - 1)
        return if (isForIn) ForInStatement(start, lastTokEnd, left, right, body) else ForOfStatement(start, lastTokEnd, left, right, body)
    }

    private fun parseIfStatement(start: Int): Node {
        next()
        val test = parseParenExpression()
        val consequent = parseStatement("if")
        val alternate = if (eat(Tt.ELSE)) parseStatement("if") else null
        return IfStatement(start, lastTokEnd, test, consequent, alternate)
    }

    private fun parseReturnStatement(start: Int): Node {
        if (!inFunction && !allowReturnOutsideFunction) raise(tok.start, "'return' outside of function", tok.end)
        next()
        // The keywords with an optional argument look for a semicolon eagerly.
        var argument: Node? = null
        if (!(eat(Tt.SEMI) || insertSemicolon())) {
            argument = parseExpression()
            semicolon()
        }
        return ReturnStatement(start, lastTokEnd, argument)
    }

    /** The case of a `switch` that is being filled with statements. */
    private class CaseBuilder(val start: Int, val test: Node?) {
        val consequent = ArrayList<Node>()
    }

    private fun parseSwitchStatement(start: Int): Node {
        next()
        val discriminant = parseParenExpression()
        val cases = ArrayList<SwitchCase>()
        expect(Tt.BRACE_L)
        labels.add(ParseLabel(null, "switch", -1))
        enterScope(0)
        var current: CaseBuilder? = null
        var sawDefault = false
        while (tok.type !== Tt.BRACE_R) {
            if (tok.type === Tt.CASE || tok.type === Tt.DEFAULT) {
                val isCase = tok.type === Tt.CASE
                current?.let { cases.add(SwitchCase(it.start, lastTokEnd, it.test, it.consequent)) }
                val caseStart = tok.start
                next()
                val test: Node?
                if (isCase) {
                    test = parseExpression()
                } else {
                    if (sawDefault) raise(lastTokStart, "Multiple default clauses", lastTokEnd)
                    sawDefault = true
                    test = null
                }
                expect(Tt.COLON)
                current = CaseBuilder(caseStart, test)
            } else {
                val case = current ?: unexpected()
                case.consequent.add(parseStatement(null))
            }
        }
        exitScope()
        current?.let { cases.add(SwitchCase(it.start, lastTokEnd, it.test, it.consequent)) }
        next()
        labels.removeAt(labels.size - 1)
        return SwitchStatement(start, lastTokEnd, discriminant, cases)
    }

    private fun parseThrowStatement(start: Int): Node {
        next()
        if (tok.nlBefore) raise(lastTokEnd, "Illegal newline after throw")
        val argument = parseExpression()
        semicolon()
        return ThrowStatement(start, lastTokEnd, argument)
    }

    private fun parseCatchClauseParam(): Node {
        val param = parseBindingAtom()
        val simple = param is Identifier
        enterScope(if (simple) ScopeFlag.SIMPLE_CATCH else 0)
        checkLValPattern(param, if (simple) BindKind.SIMPLE_CATCH else BindKind.LEXICAL)
        expect(Tt.PAREN_R)
        return param
    }

    private fun parseTryStatement(start: Int): Node {
        val keywordEnd = tok.end
        next()
        val block = parseBlock()
        var handler: CatchClause? = null
        if (tok.type === Tt.CATCH) {
            val clauseStart = tok.start
            next()
            val param: Node?
            if (eat(Tt.PAREN_L)) {
                param = parseCatchClauseParam()
            } else {
                param = null
                enterScope(0)
            }
            val body = parseBlock(false)
            exitScope()
            handler = CatchClause(clauseStart, lastTokEnd, param, body)
        }
        val finalizer = if (eat(Tt.FINALLY)) parseBlock() else null
        if (handler == null && finalizer == null) raise(start, "Missing catch or finally clause", keywordEnd)
        return TryStatement(start, lastTokEnd, block, handler, finalizer)
    }

    private fun parseVarStatement(start: Int, kind: DeclKind): Node {
        next()
        val declarations = parseVar(isFor = false, kind)
        semicolon()
        return VariableDeclaration(start, lastTokEnd, kind, declarations)
    }

    private fun parseWhileStatement(start: Int): Node {
        next()
        val test = parseParenExpression()
        labels.add(ParseLabel(null, "loop", -1))
        val body = parseStatement("while")
        labels.removeAt(labels.size - 1)
        return WhileStatement(start, lastTokEnd, test, body)
    }

    private fun parseLabeledStatement(start: Int, name: String, label: Identifier, context: String?): Node {
        for (existing in labels) if (existing.name == name) raiseAt(label, "Label '$name' is already declared")
        val kind = if (tok.type.isLoop) "loop" else if (tok.type === Tt.SWITCH) "switch" else null
        for (i in labels.indices.reversed()) {
            val other = labels[i]
            if (other.statementStart == start) {
                // Update what is known about the labels that stand on this statement too.
                other.statementStart = tok.start
                other.kind = kind
            } else {
                break
            }
        }
        labels.add(ParseLabel(name, kind, tok.start))
        val bodyContext = when {
            context == null -> "label"
            "label" in context -> context
            else -> context + "label"
        }
        val body = parseStatement(bodyContext)
        labels.removeAt(labels.size - 1)
        return LabeledStatement(start, lastTokEnd, label, body)
    }

    private fun parseExpressionStatement(start: Int, expression: Node): Node {
        semicolon()
        return ExpressionStatement(start, lastTokEnd, expression)
    }

    /** `{ ... }`. A function body or a catch clause has its scope already and passes [createNewLexicalScope] false. */
    internal fun parseBlock(createNewLexicalScope: Boolean = true): BlockStatement {
        val start = tok.start
        expect(Tt.BRACE_L)
        if (createNewLexicalScope) enterScope(0)
        val body = ArrayList<Node>()
        while (tok.type !== Tt.BRACE_R) body.add(parseStatement(null))
        next()
        if (createNewLexicalScope) exitScope()
        return BlockStatement(start, lastTokEnd, body)
    }

    private fun parseVar(isFor: Boolean, kind: DeclKind): List<VariableDeclarator> {
        val declarations = ArrayList<VariableDeclarator>()
        while (true) {
            val start = tok.start
            val id = parseBindingAtom()
            checkLValPattern(id, if (kind == DeclKind.VAR) BindKind.VAR else BindKind.LEXICAL)
            var init: Node? = null
            val inOrOf = tok.type === Tt.IN || isContextual("of")
            if (eat(Tt.EQ)) {
                init = parseMaybeAssign(isFor)
            } else if (kind == DeclKind.CONST && !inOrOf) {
                raiseAt(id, "Missing initializer in const declaration")
            } else if (id !is Identifier && !(isFor && inOrOf)) {
                raise(lastTokEnd, "Complex binding patterns require an initialization value")
            }
            declarations.add(VariableDeclarator(start, lastTokEnd, id, init))
            if (!eat(Tt.COMMA)) break
        }
        return declarations
    }
}
