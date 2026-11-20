package cosic.script.next

/*
 * Binding and assignment targets: acorn's `pp$7` section. An expression that turns out to be a target (`[a, b] = ...`,
 * the parameters in `(a, { b }) => ...`) is converted by [toAssignable]; every target is then checked by
 * [checkLValSimple], [checkLValPattern] and [checkLValInnerPattern], which also declare the names a binding introduces.
 */

/**
 * [node], an expression just parsed, as an assignment target, or as a binding when [isBinding] (arrow parameters). Object
 * and array literals become patterns, `a = b` becomes a default and `...a` a rest element, down to the leaves.
 */
internal fun Parser.toAssignable(node: Node, isBinding: Boolean, refDestructuringErrors: DestructuringErrors? = null): Node = when (node) {
    is Identifier -> {
        if (inAsync && node.name == "await") raiseAt(node, "Cannot use 'await' as identifier inside an async function")
        node
    }
    is ObjectPattern, is ArrayPattern, is AssignmentPattern, is RestElement -> node
    is ObjectExpression -> {
        checkPatternErrors(refDestructuringErrors, true)
        val properties = node.properties.map { property ->
            val converted = toAssignable(property, isBinding)
            // `{ ...{ a } } = x` and `{ ...[a] } = x` are not allowed: the rest of an object is a plain target.
            if (converted is RestElement && (converted.argument is ArrayPattern || converted.argument is ObjectPattern)) {
                raise(converted.argument.start, "Unexpected token")
            }
            converted
        }
        ObjectPattern(node.start, node.end, properties)
    }
    is Property -> {
        if (node.kind != PropertyKind.INIT) raise(node.key.start, "Object pattern can't contain getter or setter")
        val value = toAssignable(node.value, isBinding)
        if (value === node.value) node else Property(node.start, node.end, node.key, value, node.kind, node.computed, node.shorthand, node.method)
    }
    is ArrayExpression -> {
        checkPatternErrors(refDestructuringErrors, true)
        ArrayPattern(node.start, node.end, toAssignableList(node.elements, isBinding))
    }
    is SpreadElement -> {
        val argument = toAssignable(node.argument, isBinding)
        if (argument is AssignmentPattern) raiseAt(argument, "Rest elements cannot have a default value")
        RestElement(node.start, node.end, argument)
    }
    is AssignmentExpression -> {
        if (node.operator != AssignOp.ASSIGN) raise(node.left.end, "Only '=' operator can be used for specifying default value.")
        AssignmentPattern(node.start, node.end, toAssignable(node.left, isBinding), node.right)
    }
    is ChainExpression -> raiseAt(node, "Optional chaining cannot appear in left-hand side")
    is MemberExpression -> if (!isBinding) node else raiseAt(node, "Assigning to rvalue")
    else -> raiseAt(node, "Assigning to rvalue")
}

internal fun Parser.toAssignableList(list: List<Node?>, isBinding: Boolean): List<Node?> =
    list.map { element -> if (element == null) null else toAssignable(element, isBinding) }

/** `...target`: the current token is the ellipsis. */
internal fun Parser.parseRestBinding(): Node {
    val start = tok.start
    next()
    val argument = parseBindingAtom()
    return RestElement(start, lastTokEnd, argument)
}

/** An identifier, or an array or object pattern. */
internal fun Parser.parseBindingAtom(): Node = when (tok.type) {
    Tt.BRACKET_L -> {
        val start = tok.start
        next()
        enter()
        val elements = parseBindingList(Tt.BRACKET_R, allowEmpty = true, allowTrailingComma = true)
        leave()
        ArrayPattern(start, lastTokEnd, elements)
    }
    Tt.BRACE_L -> {
        enter()
        val pattern = parseObj(true, null)
        leave()
        pattern
    }
    else -> parseIdent()
}

/** The targets up to and including [close]: `null` for a hole when [allowEmpty]; a rest element ends the list. */
internal fun Parser.parseBindingList(close: Tt, allowEmpty: Boolean, allowTrailingComma: Boolean): List<Node?> {
    val elements = ArrayList<Node?>()
    var first = true
    while (!eat(close)) {
        if (first) first = false else expect(Tt.COMMA)
        if (allowEmpty && tok.type === Tt.COMMA) {
            elements.add(null)
        } else if (allowTrailingComma && afterTrailingComma(close)) {
            break
        } else if (tok.type === Tt.ELLIPSIS) {
            elements.add(parseRestBinding())
            if (tok.type === Tt.COMMA) raise(tok.start, "Comma is not permitted after the rest element")
            expect(close)
            break
        } else {
            elements.add(parseMaybeDefault(tok.start, null))
        }
    }
    return elements
}

/** [left] (or the next binding atom), and `= default` if it follows. */
internal fun Parser.parseMaybeDefault(startPos: Int, left: Node?): Node {
    val target = left ?: parseBindingAtom()
    if (!eat(Tt.EQ)) return target
    val right = parseMaybeAssign()
    return AssignmentPattern(startPos, lastTokEnd, target, right)
}

/**
 * Checks that [expr] can be bound ([bindingType] is a [BindKind]) or assigned to ([BindKind.NONE]) and, for a binding,
 * declares its name. [checkClashes] collects the names of a parameter list to catch repeats.
 */
internal fun Parser.checkLValSimple(expr: Node, bindingType: Int = BindKind.NONE, checkClashes: HashSet<String>? = null) {
    val isBind = bindingType != BindKind.NONE
    when (expr) {
        is Identifier -> {
            if (expr.name in RESERVED_STRICT_BIND) raiseAt(expr, (if (isBind) "Binding " else "Assigning to ") + expr.name + " in strict mode")
            if (isBind) {
                if (bindingType == BindKind.LEXICAL && expr.name == "let") raiseAt(expr, "let is disallowed as a lexically bound name")
                if (checkClashes != null) {
                    if (expr.name in checkClashes) raiseAt(expr, "Argument name clash")
                    checkClashes.add(expr.name)
                }
                if (bindingType != BindKind.OUTSIDE) declareName(expr.name, bindingType, expr.start, expr.end)
            }
        }
        is ChainExpression -> raiseAt(expr, "Optional chaining cannot appear in left-hand side")
        is MemberExpression -> if (isBind) raiseAt(expr, "Binding member expression")
        else -> raiseAt(expr, if (isBind) "Binding rvalue" else "Assigning to rvalue")
    }
}

/** [checkLValSimple] for the places that also take an object or array pattern. */
internal fun Parser.checkLValPattern(expr: Node, bindingType: Int = BindKind.NONE, checkClashes: HashSet<String>? = null) {
    when (expr) {
        is ObjectPattern -> for (property in expr.properties) checkLValInnerPattern(property, bindingType, checkClashes)
        is ArrayPattern -> for (element in expr.elements) if (element != null) checkLValInnerPattern(element, bindingType, checkClashes)
        else -> checkLValSimple(expr, bindingType, checkClashes)
    }
}

/** [checkLValPattern] for what stands inside a pattern or a parameter list: also defaults and rest elements. */
internal fun Parser.checkLValInnerPattern(expr: Node, bindingType: Int = BindKind.NONE, checkClashes: HashSet<String>? = null) {
    when (expr) {
        is Property -> checkLValInnerPattern(expr.value, bindingType, checkClashes)
        is AssignmentPattern -> checkLValPattern(expr.left, bindingType, checkClashes)
        is RestElement -> checkLValPattern(expr.argument, bindingType, checkClashes)
        else -> checkLValPattern(expr, bindingType, checkClashes)
    }
}
