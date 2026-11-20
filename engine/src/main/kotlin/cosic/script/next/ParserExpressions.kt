package cosic.script.next

/*
 * Expressions, functions and literals: acorn's `pp$5` section, as extensions of [Parser]. Cover-grammar trouble (is `(a, b)`
 * the start of an arrow function, is `{ a = 1 }` a pattern) is settled like acorn does: parse as an expression, remember
 * the positions that would be errors in [DestructuringErrors], and convert with toAssignable when `=` or `=>` follows.
 */

private const val MIXED_LOGICAL = "Logical expressions and coalesce expressions cannot be mixed. Wrap either by parentheses"
private const val UNARY_BEFORE_EXPONENT =
    "Unary operator used immediately before exponentiation expression. Parenthesis must be used to disambiguate operator precedence"

internal fun Parser.parseExpression(forInit: Boolean = false, refDestructuringErrors: DestructuringErrors? = null): Node {
    val startPos = tok.start
    val expr = parseMaybeAssign(forInit, refDestructuringErrors)
    if (tok.type === Tt.COMMA) {
        val expressions = arrayListOf(expr)
        while (eat(Tt.COMMA)) expressions.add(parseMaybeAssign(forInit, refDestructuringErrors))
        return SequenceExpression(startPos, lastTokEnd, expressions)
    }
    return expr
}

internal fun Parser.parseParenExpression(): Node {
    expect(Tt.PAREN_L)
    val value = parseExpression()
    expect(Tt.PAREN_R)
    return value
}

/** An assignment expression, including the compound operators; [forInit] disallows `in` (the head of a `for`). */
internal fun Parser.parseMaybeAssign(forInit: Boolean = false, refDestructuringErrors: DestructuringErrors? = null): Node {
    enter()
    var ownDestructuringErrors = false
    var oldParenAssign = -1
    var oldTrailingComma = -1
    var oldDoubleProto = -1
    val errors: DestructuringErrors
    if (refDestructuringErrors != null) {
        errors = refDestructuringErrors
        oldParenAssign = errors.parenthesizedAssign
        oldTrailingComma = errors.trailingComma
        oldDoubleProto = errors.doubleProto
        errors.parenthesizedAssign = -1
        errors.trailingComma = -1
    } else {
        errors = DestructuringErrors()
        ownDestructuringErrors = true
    }
    val startPos = tok.start
    if (tok.type === Tt.PAREN_L || tok.type === Tt.NAME) potentialArrowAt = tok.start
    var left = parseMaybeConditional(forInit, errors)
    if (tok.type.isAssign) {
        val operator = AssignOp.of(tok.value as String)
        if (tok.type === Tt.EQ) left = toAssignable(left, false, errors)
        if (!ownDestructuringErrors) {
            errors.parenthesizedAssign = -1
            errors.trailingComma = -1
            errors.doubleProto = -1
        }
        // A shorthand default (`{ a = 1 }`) is fine now that the left side is known to be a pattern.
        if (errors.shorthandAssign >= left.start) errors.shorthandAssign = -1
        if (tok.type === Tt.EQ) checkLValPattern(left) else checkLValSimple(left)
        next()
        val right = parseMaybeAssign(forInit)
        if (oldDoubleProto > -1) errors.doubleProto = oldDoubleProto
        leave()
        return AssignmentExpression(startPos, lastTokEnd, operator, left, right)
    }
    if (ownDestructuringErrors) checkExpressionErrors(errors, true)
    if (oldParenAssign > -1) errors.parenthesizedAssign = oldParenAssign
    if (oldTrailingComma > -1) errors.trailingComma = oldTrailingComma
    leave()
    return left
}

private fun Parser.parseMaybeConditional(forInit: Boolean, refDestructuringErrors: DestructuringErrors?): Node {
    val startPos = tok.start
    val expr = parseExprOps(forInit, refDestructuringErrors)
    if (checkExpressionErrors(refDestructuringErrors)) return expr
    if (eat(Tt.QUESTION)) {
        val consequent = parseMaybeAssign()
        expect(Tt.COLON)
        val alternate = parseMaybeAssign(forInit)
        return ConditionalExpression(startPos, lastTokEnd, expr, consequent, alternate)
    }
    return expr
}

private fun Parser.parseExprOps(forInit: Boolean, refDestructuringErrors: DestructuringErrors?): Node {
    val startPos = tok.start
    val expr = parseMaybeUnary(refDestructuringErrors, sawUnaryIn = false, incDec = false, forInit)
    if (checkExpressionErrors(refDestructuringErrors)) return expr
    return if (expr.start == startPos && expr is ArrowFunctionExpression) expr else parseExprOp(expr, startPos, -1, forInit)
}

/**
 * Binary operators by precedence climbing: [left] is the operand to the left of the operator at the current token, and only
 * operators that bind tighter than [minPrec] belong to it. Operators of equal precedence are collected in a loop, so a long
 * chain of `a + b + c ...` does not nest calls.
 */
private fun Parser.parseExprOp(leftIn: Node, leftStartPos: Int, minPrec: Int, forInit: Boolean): Node {
    var left = leftIn
    var chain = 0
    while (true) {
        var prec = tok.type.binop
        if (prec == 0 || (forInit && tok.type === Tt.IN) || prec <= minPrec) return left
        if (++chain > MAX_CHAIN) raise(tok.start, "Code is nested too deeply")
        val logical = tok.type === Tt.LOGICAL_OR || tok.type === Tt.LOGICAL_AND
        val coalesce = tok.type === Tt.COALESCE
        // `??` binds like the logical operators, so that its operands cannot hold one (checked below).
        if (coalesce) prec = Tt.LOGICAL_AND.binop
        val operator = tok.value as String
        next()
        val startPos = tok.start
        val right = parseExprOp(parseMaybeUnary(null, sawUnaryIn = false, incDec = false, forInit), startPos, prec, forInit)
        val node = buildBinary(leftStartPos, left, right, operator, logical || coalesce)
        if ((logical && tok.type === Tt.COALESCE) || (coalesce && (tok.type === Tt.LOGICAL_OR || tok.type === Tt.LOGICAL_AND))) {
            raise(tok.start, MIXED_LOGICAL)
        }
        left = node
    }
}

private fun Parser.buildBinary(startPos: Int, left: Node, right: Node, operator: String, logical: Boolean): Node =
    if (logical) LogicalExpression(startPos, lastTokEnd, LogicalOp.of(operator), left, right)
    else BinaryExpression(startPos, lastTokEnd, BinaryOp.of(operator), left, right)

/** Prefix and postfix operators, `await`, and `**` (which binds tighter than the unary operators on its left, hence [sawUnaryIn]). */
internal fun Parser.parseMaybeUnary(
    refDestructuringErrors: DestructuringErrors?,
    sawUnaryIn: Boolean,
    incDec: Boolean,
    forInit: Boolean,
): Node {
    val startPos = tok.start
    var sawUnary = sawUnaryIn
    var expr: Node
    if (isContextual("await") && canAwait) {
        expr = parseAwait(forInit)
        sawUnary = true
    } else if (tok.type.prefix) {
        val operator = tok.value as String
        val update = tok.type === Tt.INC_DEC
        next()
        enter()
        val argument = parseMaybeUnary(null, true, update, forInit)
        leave()
        checkExpressionErrors(refDestructuringErrors, true)
        if (update) {
            checkLValSimple(argument)
        } else if (operator == "delete" && argument is Identifier) {
            raise(startPos, "Deleting local variable in strict mode", lastTokEnd)
        } else {
            sawUnary = true
        }
        expr = if (update) UpdateExpression(startPos, lastTokEnd, UpdateOp.of(operator), true, argument)
        else UnaryExpression(startPos, lastTokEnd, UnaryOp.of(operator), argument)
    } else {
        expr = parseExprSubscripts(refDestructuringErrors, forInit)
        if (checkExpressionErrors(refDestructuringErrors)) return expr
        while (tok.type.postfix && !canInsertSemicolon()) {
            val operator = tok.value as String
            checkLValSimple(expr)
            next()
            expr = UpdateExpression(startPos, lastTokEnd, UpdateOp.of(operator), false, expr)
        }
    }
    if (!incDec && eat(Tt.STAR_STAR)) {
        if (sawUnary) raise(lastTokStart, UNARY_BEFORE_EXPONENT, lastTokEnd)
        enter()
        val right = parseMaybeUnary(null, false, false, forInit)
        leave()
        return buildBinary(startPos, expr, right, "**", false)
    }
    return expr
}

private fun Parser.parseAwait(forInit: Boolean): Node {
    if (awaitPos == 0) awaitPos = tok.start
    val start = tok.start
    next()
    enter()
    val argument = parseMaybeUnary(null, true, false, forInit)
    leave()
    return AwaitExpression(start, lastTokEnd, argument)
}

// ---- calls, members, templates ---------------------------------------------------------------------------------------

private fun Parser.parseExprSubscripts(refDestructuringErrors: DestructuringErrors?, forInit: Boolean): Node {
    val startPos = tok.start
    val expr = parseExprAtom(refDestructuringErrors, forInit)
    // An arrow function whose body is not parenthesised takes no subscripts: `x => y (z)` calls inside the body.
    if (expr is ArrowFunctionExpression && lastTok.type !== Tt.PAREN_R) return expr
    val result = parseSubscripts(expr, startPos, false, forInit)
    if (refDestructuringErrors != null && result is MemberExpression) {
        if (refDestructuringErrors.parenthesizedAssign >= result.start) refDestructuringErrors.parenthesizedAssign = -1
        if (refDestructuringErrors.parenthesizedBind >= result.start) refDestructuringErrors.parenthesizedBind = -1
        if (refDestructuringErrors.trailingComma >= result.start) refDestructuringErrors.trailingComma = -1
    }
    return result
}

private fun isOptional(node: Node): Boolean = (node is MemberExpression && node.optional) || (node is CallExpression && node.optional)

internal fun Parser.parseSubscripts(base: Node, startPos: Int, noCalls: Boolean, forInit: Boolean): Node {
    val maybeAsyncArrow = base is Identifier && base.name == "async" && lastTokEnd == base.end && !canInsertSemicolon() &&
        base.end - base.start == 5 && potentialArrowAt == base.start
    var optionalChained = false
    var current = base
    var chain = 0
    while (true) {
        val element = parseSubscript(current, startPos, noCalls, maybeAsyncArrow, optionalChained, forInit)
        if (isOptional(element)) optionalChained = true
        if (element === current || element is ArrowFunctionExpression) {
            return if (optionalChained) ChainExpression(startPos, lastTokEnd, element) else element
        }
        if (++chain > MAX_CHAIN) raise(tok.start, "Code is nested too deeply")
        current = element
    }
}

private fun Parser.parseSubscript(
    base: Node,
    startPos: Int,
    noCalls: Boolean,
    maybeAsyncArrow: Boolean,
    optionalChained: Boolean,
    forInit: Boolean,
): Node {
    val optional = eat(Tt.QUESTION_DOT)
    if (noCalls && optional) raise(lastTokStart, "Optional chaining cannot appear in the callee of new expressions", lastTokEnd)
    val computed = eat(Tt.BRACKET_L)
    if (computed || (optional && tok.type !== Tt.PAREN_L && tok.type !== Tt.BACK_QUOTE) || eat(Tt.DOT)) {
        val property: Node
        if (computed) {
            property = parseExpression()
            expect(Tt.BRACKET_R)
        } else {
            property = parseIdent(true)
        }
        return MemberExpression(startPos, lastTokEnd, base, property, computed, optional)
    } else if (!noCalls && eat(Tt.PAREN_L)) {
        val refDestructuringErrors = DestructuringErrors()
        val oldAwaitPos = awaitPos
        val oldAwaitIdentPos = awaitIdentPos
        awaitPos = 0
        awaitIdentPos = 0
        val arguments = parseExprList(Tt.PAREN_R, allowTrailingComma = true, allowEmpty = false, refDestructuringErrors)
        if (maybeAsyncArrow && !optional && !canInsertSemicolon() && eat(Tt.ARROW)) {
            checkPatternErrors(refDestructuringErrors, false)
            checkYieldAwaitInDefaultParams()
            if (awaitIdentPos > 0) raise(awaitIdentPos, "Cannot use 'await' as identifier inside an async function")
            awaitPos = oldAwaitPos
            awaitIdentPos = oldAwaitIdentPos
            return parseArrowExpression(startPos, arguments, true, forInit)
        }
        checkExpressionErrors(refDestructuringErrors, true)
        if (oldAwaitPos != 0) awaitPos = oldAwaitPos
        if (oldAwaitIdentPos != 0) awaitIdentPos = oldAwaitIdentPos
        return CallExpression(startPos, lastTokEnd, base, arguments.map { it!! }, optional)
    } else if (tok.type === Tt.BACK_QUOTE) {
        if (optional || optionalChained) raise(tok.start, "Optional chaining cannot appear in the tag of tagged template expressions", tok.end)
        val quasi = parseTemplate(isTagged = true)
        return TaggedTemplateExpression(startPos, lastTokEnd, base, quasi)
    }
    return base
}

/** The elements up to and including [close]; null stands for a hole when [allowEmpty]. Spread elements are kept. */
internal fun Parser.parseExprList(
    close: Tt,
    allowTrailingComma: Boolean,
    allowEmpty: Boolean,
    refDestructuringErrors: DestructuringErrors?,
): List<Node?> {
    val elements = ArrayList<Node?>()
    var first = true
    while (!eat(close)) {
        if (!first) {
            expect(Tt.COMMA)
            if (allowTrailingComma && afterTrailingComma(close)) break
        } else {
            first = false
        }
        val element: Node?
        if (allowEmpty && tok.type === Tt.COMMA) {
            element = null
        } else if (tok.type === Tt.ELLIPSIS) {
            element = parseSpread(refDestructuringErrors)
            if (refDestructuringErrors != null && tok.type === Tt.COMMA && refDestructuringErrors.trailingComma < 0) {
                refDestructuringErrors.trailingComma = tok.start
            }
        } else {
            element = parseMaybeAssign(false, refDestructuringErrors)
        }
        elements.add(element)
    }
    return elements
}

internal fun Parser.parseSpread(refDestructuringErrors: DestructuringErrors?): Node {
    val start = tok.start
    next()
    val argument = parseMaybeAssign(false, refDestructuringErrors)
    return SpreadElement(start, lastTokEnd, argument)
}

private fun Parser.parseNew(): Node {
    val start = tok.start
    next()
    if (tok.type === Tt.DOT) {
        next()
        val property = parseIdent(true)
        if (property.name != "target") raiseAt(property, "The only valid meta property for new is 'new.target'")
        raise(start, "new.target is not supported", lastTokEnd)
    }
    val calleeStart = tok.start
    enter()
    val callee = parseSubscripts(parseExprAtom(null, false, forNew = true), calleeStart, true, false)
    leave()
    val arguments = if (eat(Tt.PAREN_L)) parseExprList(Tt.PAREN_R, allowTrailingComma = true, allowEmpty = false, null).map { it!! } else emptyList()
    return NewExpression(start, lastTokEnd, callee, arguments)
}

private fun normalizeLineBreaks(text: String): String = text.replace("\r\n", "\n").replace('\r', '\n')

private fun Parser.parseTemplateElement(isTagged: Boolean): TemplateElement {
    val start = tok.start
    val cooked: String?
    val raw: String
    if (tok.type === Tt.INVALID_TEMPLATE) {
        if (!isTagged) raise(tok.start, "Bad escape sequence in untagged template literal", tok.end)
        cooked = null
        raw = normalizeLineBreaks(tok.value as String)
    } else {
        cooked = tok.value as String?
        raw = normalizeLineBreaks(input.substring(tok.start, tok.end))
    }
    next()
    val tail = tok.type === Tt.BACK_QUOTE
    return TemplateElement(start, lastTokEnd, cooked, raw, tail)
}

/** A template literal; the current token is its opening backquote. */
internal fun Parser.parseTemplate(isTagged: Boolean): TemplateLiteral {
    val start = tok.start
    next()
    val expressions = ArrayList<Node>()
    var current = parseTemplateElement(isTagged)
    val quasis = arrayListOf(current)
    while (!current.tail) {
        if (tok.type === Tt.EOF) raise(input.length, "Unterminated template literal")
        expect(Tt.DOLLAR_BRACE_L)
        expressions.add(parseExpression())
        expect(Tt.BRACE_R)
        current = parseTemplateElement(isTagged)
        quasis.add(current)
    }
    next()
    return TemplateLiteral(start, lastTokEnd, quasis, expressions)
}

// ---- atoms -----------------------------------------------------------------------------------------------------------

private fun Parser.parseLiteral(value: Any?): Node {
    val t = tok
    next()
    return Literal(t.start, t.end, value, input.substring(t.start, t.end))
}

/** A single token that is an expression, or an expression started by a keyword or enclosed in `()`, `[]` or `{}`. */
internal fun Parser.parseExprAtom(refDestructuringErrors: DestructuringErrors?, forInit: Boolean, forNew: Boolean = false): Node {
    // A division where an expression is due: the lexer guessed wrong; it is a regular expression.
    if (tok.type === Tt.SLASH) raise(tok.start, "Regular expressions are not supported", regexEnd(tok.start) ?: (tok.start + 1))
    val canBeArrow = potentialArrowAt == tok.start
    when (tok.type) {
        Tt.SUPER -> raise(tok.start, "'super' keyword outside a method", tok.end)
        Tt.THIS -> {
            val t = tok
            next()
            return ThisExpression(t.start, t.end)
        }
        Tt.NAME -> {
            val startPos = tok.start
            val containsEsc = tok.containsEsc
            var id = parseIdent(false)
            if (!containsEsc && id.name == "async" && !canInsertSemicolon() && eat(Tt.FUNCTION)) {
                return parseFunction(startPos, statement = false, isAsync = true, forInit)
            }
            if (canBeArrow && !canInsertSemicolon()) {
                if (eat(Tt.ARROW)) return parseArrowExpression(startPos, listOf(id), false, forInit)
                if (id.name == "async" && tok.type === Tt.NAME && !containsEsc) {
                    id = parseIdent(false)
                    if (canInsertSemicolon() || !eat(Tt.ARROW)) unexpected()
                    return parseArrowExpression(startPos, listOf(id), true, forInit)
                }
            }
            return id
        }
        Tt.NUM, Tt.STRING -> return parseLiteral(tok.value)
        Tt.NULL, Tt.TRUE, Tt.FALSE -> {
            val t = tok
            val value: Any? = if (t.type === Tt.NULL) null else t.type === Tt.TRUE
            next()
            return Literal(t.start, t.end, value, t.type.keyword!!)
        }
        Tt.PAREN_L -> {
            val start = tok.start
            val expr = parseParenAndDistinguishExpression(canBeArrow, forInit)
            if (refDestructuringErrors != null) {
                if (refDestructuringErrors.parenthesizedAssign < 0 && !(expr is Identifier || expr is MemberExpression)) {
                    refDestructuringErrors.parenthesizedAssign = start
                }
                if (refDestructuringErrors.parenthesizedBind < 0) refDestructuringErrors.parenthesizedBind = start
            }
            return expr
        }
        Tt.BRACKET_L -> {
            val start = tok.start
            next()
            val elements = parseExprList(Tt.BRACKET_R, allowTrailingComma = true, allowEmpty = true, refDestructuringErrors)
            return ArrayExpression(start, lastTokEnd, elements)
        }
        Tt.BRACE_L -> return parseObj(false, refDestructuringErrors)
        Tt.FUNCTION -> {
            val start = tok.start
            next()
            return parseFunction(start, statement = false, isAsync = false)
        }
        Tt.CLASS -> raise(tok.start, "Classes are not supported", tok.end)
        Tt.NEW -> return parseNew()
        Tt.BACK_QUOTE -> return parseTemplate(false)
        Tt.IMPORT -> return parseExprImport(forNew)
        else -> unexpected()
    }
}

/** `import(...)` and `import.meta`, the two ways `import` can start an expression. Neither is supported. */
private fun Parser.parseExprImport(forNew: Boolean): Node {
    val start = tok.start
    val keywordEnd = tok.end
    next()
    if (tok.type === Tt.PAREN_L && !forNew) raise(start, "Dynamic import is not supported", keywordEnd)
    if (tok.type === Tt.DOT) {
        next()
        val property = parseIdent(true)
        if (property.name != "meta") raiseAt(property, "The only valid meta property for import is 'import.meta'")
        raise(start, "Cannot use 'import.meta' outside a module", lastTokEnd)
    }
    unexpected()
}

/**
 * `(`: a parenthesised expression, or the parameters of an arrow function when [canBeArrow] and `=>` follows the
 * closing parenthesis. Parenthesised expressions leave no node behind.
 */
private fun Parser.parseParenAndDistinguishExpression(canBeArrow: Boolean, forInit: Boolean): Node {
    val startPos = tok.start
    next()
    val innerStartPos = tok.start
    val exprList = ArrayList<Node>()
    var first = true
    var lastIsComma = false
    val refDestructuringErrors = DestructuringErrors()
    val oldAwaitPos = awaitPos
    var spreadStart = -1
    awaitPos = 0
    while (tok.type !== Tt.PAREN_R) {
        if (first) first = false else expect(Tt.COMMA)
        if (afterTrailingComma(Tt.PAREN_R, notNext = true)) {
            lastIsComma = true
            break
        } else if (tok.type === Tt.ELLIPSIS) {
            spreadStart = tok.start
            exprList.add(parseRestBinding())
            if (tok.type === Tt.COMMA) raise(tok.start, "Comma is not permitted after the rest element")
            break
        } else {
            exprList.add(parseMaybeAssign(false, refDestructuringErrors))
        }
    }
    val innerEndPos = lastTokEnd
    expect(Tt.PAREN_R)

    if (canBeArrow && !canInsertSemicolon() && eat(Tt.ARROW)) {
        checkPatternErrors(refDestructuringErrors, false)
        checkYieldAwaitInDefaultParams()
        awaitPos = oldAwaitPos
        return parseArrowExpression(startPos, exprList, false, forInit)
    }

    if (exprList.isEmpty() || lastIsComma) unexpected(lastTokStart)
    if (spreadStart >= 0) unexpected(spreadStart)
    checkExpressionErrors(refDestructuringErrors, true)
    if (oldAwaitPos != 0) awaitPos = oldAwaitPos
    return if (exprList.size > 1) SequenceExpression(innerStartPos, innerEndPos, exprList) else exprList[0]
}

// ---- functions -------------------------------------------------------------------------------------------------------

private fun functionFlags(isAsync: Boolean): Int = ScopeFlag.FUNCTION or (if (isAsync) ScopeFlag.ASYNC else 0)

/**
 * A function declaration or expression whose `function` keyword (or `async`) was consumed and started at [start]. A
 * declaration binds its name in the enclosing scope; an expression keeps its optional name to itself.
 */
internal fun Parser.parseFunction(start: Int, statement: Boolean, isAsync: Boolean, forInit: Boolean = false): FunctionNode {
    if (tok.type === Tt.STAR) raise(tok.start, "Generators are not supported", tok.end)
    var id: Identifier? = null
    if (statement) {
        val name = parseIdent()
        checkLValSimple(name, if (treatFunctionsAsVar) BindKind.VAR else BindKind.LEXICAL)
        id = name
    }
    val oldAwaitPos = awaitPos
    val oldAwaitIdentPos = awaitIdentPos
    awaitPos = 0
    awaitIdentPos = 0
    enterScope(functionFlags(isAsync))
    if (!statement) id = if (tok.type === Tt.NAME) parseIdent() else null
    val params = parseFunctionParams()
    val body = parseFunctionBody(id, params, isArrowFunction = false, isMethod = false, forInit)
    awaitPos = oldAwaitPos
    awaitIdentPos = oldAwaitIdentPos
    return if (statement) FunctionDeclaration(start, lastTokEnd, id!!, params, body as BlockStatement, isAsync)
    else FunctionExpression(start, lastTokEnd, id, params, body as BlockStatement, isAsync)
}

private fun Parser.parseFunctionParams(): List<Node> {
    expect(Tt.PAREN_L)
    val params = parseBindingList(Tt.PAREN_R, allowEmpty = false, allowTrailingComma = true).map { it!! }
    checkYieldAwaitInDefaultParams()
    return params
}

/**
 * The body of a function and the check of its parameters; leaves the function's scope, which the caller entered. [id] is the
 * name of a function expression, which must not be `eval` or `arguments`.
 */
internal fun Parser.parseFunctionBody(id: Identifier?, params: List<Node>, isArrowFunction: Boolean, isMethod: Boolean, forInit: Boolean): Node {
    val isExpression = isArrowFunction && tok.type !== Tt.BRACE_L
    val body: Node
    if (isExpression) {
        body = parseMaybeAssign(forInit)
        checkParams(params)
    } else {
        // A new scope for labels: `break` and `continue` do not cross a function boundary.
        val oldLabels = labels
        labels = ArrayList()
        checkParams(params)
        if (id != null) checkLValSimple(id, BindKind.OUTSIDE)
        body = parseBlock(false)
        labels = oldLabels
    }
    exitScope()
    return body
}

/** The parameters are bound as `var`s of the function, and may not repeat (every program is strict). */
private fun Parser.checkParams(params: List<Node>) {
    val names = HashSet<String>()
    for (param in params) checkLValInnerPattern(param, BindKind.VAR, names)
}

/** A method, getter or setter of an object literal: a function expression whose range starts at its `(`. */
private fun Parser.parseMethod(isAsync: Boolean): FunctionExpression {
    val start = tok.start
    val oldAwaitPos = awaitPos
    val oldAwaitIdentPos = awaitIdentPos
    awaitPos = 0
    awaitIdentPos = 0
    enterScope(functionFlags(isAsync))
    expect(Tt.PAREN_L)
    val params = parseBindingList(Tt.PAREN_R, allowEmpty = false, allowTrailingComma = true).map { it!! }
    checkYieldAwaitInDefaultParams()
    val body = parseFunctionBody(null, params, isArrowFunction = false, isMethod = true, forInit = false)
    awaitPos = oldAwaitPos
    awaitIdentPos = oldAwaitIdentPos
    return FunctionExpression(start, lastTokEnd, null, params, body as BlockStatement, isAsync)
}

/** An arrow function over [params], expressions that were parsed before the `=>` was seen and become patterns now. */
private fun Parser.parseArrowExpression(start: Int, params: List<Node?>, isAsync: Boolean, forInit: Boolean): Node {
    val oldAwaitPos = awaitPos
    val oldAwaitIdentPos = awaitIdentPos
    enterScope(functionFlags(isAsync))
    awaitPos = 0
    awaitIdentPos = 0
    val patterns = toAssignableList(params, true).filterNotNull()
    val body = parseFunctionBody(null, patterns, isArrowFunction = true, isMethod = false, forInit)
    awaitPos = oldAwaitPos
    awaitIdentPos = oldAwaitIdentPos
    return ArrowFunctionExpression(start, lastTokEnd, patterns, body, isAsync)
}

// ---- object literals -------------------------------------------------------------------------------------------------

/** Whether the object has a `__proto__: value` property yet. */
private class PropertyHash(var proto: Boolean = false)

/** `{ ... }` as an object literal, or as an object pattern when [isPattern]. */
internal fun Parser.parseObj(isPattern: Boolean, refDestructuringErrors: DestructuringErrors?): Node {
    val start = tok.start
    var first = true
    val properties = ArrayList<Node>()
    val propertyHash = PropertyHash()
    next()
    while (!eat(Tt.BRACE_R)) {
        if (!first) {
            expect(Tt.COMMA)
            if (afterTrailingComma(Tt.BRACE_R)) break
        } else {
            first = false
        }
        val property = parseProperty(isPattern, refDestructuringErrors)
        if (!isPattern) checkPropClash(property, propertyHash, refDestructuringErrors)
        properties.add(property)
    }
    return if (isPattern) ObjectPattern(start, lastTokEnd, properties) else ObjectExpression(start, lastTokEnd, properties)
}

/** An object literal defines `__proto__` once at most. */
private fun Parser.checkPropClash(property: Node, hash: PropertyHash, refDestructuringErrors: DestructuringErrors?) {
    if (property !is Property || property.computed || property.method || property.shorthand) return
    val key = property.key
    val name = when {
        key is Identifier -> key.name
        key is Literal -> key.value.let { v -> if (v is Double) jsKeyText(v) else v.toString() }
        else -> return
    }
    if (name == "__proto__" && property.kind == PropertyKind.INIT) {
        if (hash.proto) {
            if (refDestructuringErrors != null) {
                if (refDestructuringErrors.doubleProto < 0) refDestructuringErrors.doubleProto = key.start
            } else {
                raiseAt(key, "Redefinition of __proto__ property")
            }
        }
        hash.proto = true
    }
}

private fun jsKeyText(value: Double): String = cosic.pattern.jsNumberToString(value)

private fun Parser.parseProperty(isPattern: Boolean, refDestructuringErrors: DestructuringErrors?): Node {
    val start = tok.start
    if (eat(Tt.ELLIPSIS)) {
        if (isPattern) {
            val argument = parseIdent(false)
            if (tok.type === Tt.COMMA) raise(tok.start, "Comma is not permitted after the rest element")
            return RestElement(start, lastTokEnd, argument)
        }
        val argument = parseMaybeAssign(false, refDestructuringErrors)
        // A trailing comma after a spread keeps the object from becoming a pattern (checked in toAssignable).
        if (tok.type === Tt.COMMA && refDestructuringErrors != null && refDestructuringErrors.trailingComma < 0) {
            refDestructuringErrors.trailingComma = tok.start
        }
        return SpreadElement(start, lastTokEnd, argument)
    }
    val startPos = if (isPattern || refDestructuringErrors != null) start else 0
    if (!isPattern && tok.type === Tt.STAR) raise(tok.start, "Generators are not supported", tok.end)
    val containsEsc = tok.containsEsc
    var (key, computed) = parsePropertyName()
    var isAsync = false
    if (!isPattern && !containsEsc && isAsyncProp(key, computed)) {
        isAsync = true
        if (tok.type === Tt.STAR) raise(tok.start, "Generators are not supported", tok.end)
        val (asyncKey, asyncComputed) = parsePropertyName()
        key = asyncKey
        computed = asyncComputed
    }
    return parsePropertyValue(start, key, computed, isPattern, isAsync, startPos, refDestructuringErrors, containsEsc)
}

private fun Parser.isAsyncProp(key: Node, computed: Boolean): Boolean =
    !computed && key is Identifier && key.name == "async" &&
        (tok.type === Tt.NAME || tok.type === Tt.NUM || tok.type === Tt.STRING || tok.type === Tt.BRACKET_L || tok.type.keyword != null || tok.type === Tt.STAR) &&
        !tok.nlBefore

private fun Parser.parsePropertyName(): Pair<Node, Boolean> {
    if (eat(Tt.BRACKET_L)) {
        val key = parseMaybeAssign()
        expect(Tt.BRACKET_R)
        return key to true
    }
    val key = if (tok.type === Tt.NUM || tok.type === Tt.STRING) parseExprAtom(null, false) else parseIdent(true)
    return key to false
}

private fun Parser.parsePropertyValue(
    start: Int,
    key: Node,
    computed: Boolean,
    isPattern: Boolean,
    isAsync: Boolean,
    startPos: Int,
    refDestructuringErrors: DestructuringErrors?,
    containsEsc: Boolean,
): Property {
    if (isAsync && tok.type === Tt.COLON) unexpected()
    if (eat(Tt.COLON)) {
        val value = if (isPattern) parseMaybeDefault(tok.start, null) else parseMaybeAssign(false, refDestructuringErrors)
        return Property(start, lastTokEnd, key, value, PropertyKind.INIT, computed, shorthand = false, method = false)
    }
    if (tok.type === Tt.PAREN_L) {
        if (isPattern) unexpected()
        val value = parseMethod(isAsync)
        return Property(start, lastTokEnd, key, value, PropertyKind.INIT, computed, shorthand = false, method = true)
    }
    if (!isPattern && !containsEsc && !computed && key is Identifier && (key.name == "get" || key.name == "set") &&
        tok.type !== Tt.COMMA && tok.type !== Tt.BRACE_R && tok.type !== Tt.EQ
    ) {
        if (isAsync) unexpected()
        val kind = if (key.name == "get") PropertyKind.GET else PropertyKind.SET
        val (accessorKey, accessorComputed) = parsePropertyName()
        val value = parseMethod(false)
        val paramCount = if (kind == PropertyKind.GET) 0 else 1
        if (value.params.size != paramCount) {
            raise(value.start, if (kind == PropertyKind.GET) "getter should have no params" else "setter should have exactly one param")
        } else if (kind == PropertyKind.SET && value.params[0] is RestElement) {
            raiseAt(value.params[0], "Setter cannot use rest params")
        }
        return Property(start, lastTokEnd, accessorKey, value, kind, accessorComputed, shorthand = false, method = false)
    }
    if (!computed && key is Identifier) {
        if (isAsync) unexpected()
        checkUnreserved(key.start, key.end, key.name)
        if (key.name == "await" && awaitIdentPos == 0) awaitIdentPos = startPos
        val value: Node = when {
            isPattern -> parseMaybeDefault(startPos, Identifier(key.start, key.end, key.name))
            tok.type === Tt.EQ && refDestructuringErrors != null -> {
                if (refDestructuringErrors.shorthandAssign < 0) refDestructuringErrors.shorthandAssign = tok.start
                parseMaybeDefault(startPos, Identifier(key.start, key.end, key.name))
            }
            else -> Identifier(key.start, key.end, key.name)
        }
        return Property(start, lastTokEnd, key, value, PropertyKind.INIT, computed = false, shorthand = true, method = false)
    }
    unexpected()
}

// ---- names -----------------------------------------------------------------------------------------------------------

private val KEYWORDS: Set<String> = Tt.keywords.keys

internal fun Parser.checkUnreserved(start: Int, end: Int, name: String) {
    if (inAsync && name == "await") raise(start, "Cannot use 'await' as identifier inside an async function", end)
    if (name in KEYWORDS) raise(start, "Unexpected keyword '$name'", end)
    if (name in RESERVED_STRICT) raise(start, "The keyword '$name' is reserved", end)
    // At the top level `await` is an operator, and Strudel runs the code in an async function, where it is reserved.
    if (name == "await" && canAwait) raise(start, "Unexpected reserved word", end)
}

/**
 * An identifier. [liberal] (property names after a dot, object keys) also takes a keyword for a name, without checking
 * that the name is allowed as a variable.
 */
internal fun Parser.parseIdent(liberal: Boolean = false): Identifier {
    val t = tok
    val name = when {
        t.type === Tt.NAME -> t.value as String
        t.type.keyword != null -> t.type.keyword
        else -> unexpected()
    }
    next(liberal)
    val node = Identifier(t.start, t.end, name)
    if (!liberal) {
        checkUnreserved(node.start, node.end, name)
        if (name == "await" && awaitIdentPos == 0) awaitIdentPos = node.start
    }
    return node
}

internal fun Parser.checkYieldAwaitInDefaultParams() {
    if (awaitPos != 0) raise(awaitPos, "Await expression cannot be a default value")
}

/** Errors that are due only if the expression just parsed is not going to be a pattern. */
internal fun Parser.checkExpressionErrors(refDestructuringErrors: DestructuringErrors?, andThrow: Boolean = false): Boolean {
    if (refDestructuringErrors == null) return false
    val shorthandAssign = refDestructuringErrors.shorthandAssign
    val doubleProto = refDestructuringErrors.doubleProto
    if (!andThrow) return shorthandAssign >= 0 || doubleProto >= 0
    if (shorthandAssign >= 0) raise(shorthandAssign, "Shorthand property assignments are valid only in destructuring patterns")
    if (doubleProto >= 0) raise(doubleProto, "Redefinition of __proto__ property")
    return false
}

/** Errors that are due only if the expression just parsed is going to be a pattern. */
internal fun Parser.checkPatternErrors(refDestructuringErrors: DestructuringErrors?, isAssign: Boolean) {
    if (refDestructuringErrors == null) return
    if (refDestructuringErrors.trailingComma > -1) raise(refDestructuringErrors.trailingComma, "Comma is not permitted after the rest element")
    val parens = if (isAssign) refDestructuringErrors.parenthesizedAssign else refDestructuringErrors.parenthesizedBind
    if (parens > -1) raise(parens, if (isAssign) "Assigning to rvalue" else "Parenthesized pattern")
}
