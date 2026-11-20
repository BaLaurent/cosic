package cosic.script.kabel

import cosic.pattern.CosicError
import cosic.pattern.jsNumberToString
import cosic.script.ArrayExpression
import cosic.script.ArrayPattern
import cosic.script.ArrowFunctionExpression
import cosic.script.AssignmentExpression
import cosic.script.AssignmentPattern
import cosic.script.AwaitExpression
import cosic.script.BinaryExpression
import cosic.script.BlockStatement
import cosic.script.BreakStatement
import cosic.script.CallExpression
import cosic.script.ChainExpression
import cosic.script.ConditionalExpression
import cosic.script.ContinueStatement
import cosic.script.DoWhileStatement
import cosic.script.EmptyStatement
import cosic.script.ExpressionStatement
import cosic.script.ForInStatement
import cosic.script.ForOfStatement
import cosic.script.ForStatement
import cosic.script.FunctionDeclaration
import cosic.script.FunctionExpression
import cosic.script.FunctionNode
import cosic.script.Identifier
import cosic.script.IfStatement
import cosic.script.LabeledStatement
import cosic.script.Literal
import cosic.script.LogicalExpression
import cosic.script.LogicalOp
import cosic.script.MemberExpression
import cosic.script.NewExpression
import cosic.script.Node
import cosic.script.ObjectExpression
import cosic.script.ObjectPattern
import cosic.script.Property
import cosic.script.PropertyKind
import cosic.script.RestElement
import cosic.script.ReturnStatement
import cosic.script.SequenceExpression
import cosic.script.SpreadElement
import cosic.script.TaggedTemplateExpression
import cosic.script.TemplateElement
import cosic.script.TemplateLiteral
import cosic.script.ThisExpression
import cosic.script.ThrowStatement
import cosic.script.UnaryExpression
import cosic.script.UpdateExpression
import cosic.script.VariableDeclaration
import cosic.script.VariableDeclarator
import cosic.script.WhileStatement

/*
 * escodegen 2.1.0 as the kabelsalat plugin calls it, `escodegen.generate(expr, { format: { semicolons: false } })`
 * (plugin-kabelsalat.mjs:10-12), ported for the node kinds a program can contain that this interpreter parses, except
 * `try` and `switch`, which are reported as unsupported. The text it prints is what
 * the audio side evaluates as the update function of the graph (`worklet`), so spacing, parentheses and the escaping
 * of strings follow escodegen exactly: indent 4 spaces from base 0, newline `\n`, one space around binary operators,
 * `', '` between arguments, a `;` after every statement except the last of a block, no comments or blank lines.
 *
 * The functions keep escodegen's shape (`generateExpression(expr, precedence, flags)`, `join`, `parenthesize`,
 * `withIndent`) and the line numbers of escodegen.js are cited where the rule is not obvious.
 */

/** Prints [node], an expression (or a statement) as escodegen's `generate` would with `{ format: { semicolons: false } }`. */
internal fun printJs(node: Node): String = JsPrinter().generate(node)

private object Precedence {
    const val SEQUENCE = 0
    const val ASSIGNMENT = 1
    const val ARROW_FUNCTION = 2
    const val CONDITIONAL = 2
    const val COALESCE = 3
    const val EXPONENTIATION = 14
    const val AWAIT = 15
    const val UNARY = 15
    const val POSTFIX = 16
    const val OPTIONAL_CHAINING = 17
    const val CALL = 18
    const val NEW = 19
    const val TAGGED_TEMPLATE = 20
    const val MEMBER = 21
    const val PRIMARY = 22
}

private fun binaryPrecedence(operator: String): Int = when (operator) {
    "??" -> 3
    "||" -> 4
    "&&" -> 5
    "|" -> 6
    "^" -> 7
    "&" -> 8
    "==", "!=", "===", "!==" -> 9
    "<", ">", "<=", ">=", "in", "instanceof" -> 10
    "<<", ">>", ">>>" -> 11
    "+", "-" -> 12
    "*", "%", "/" -> 13
    "**" -> Precedence.EXPONENTIATION
    else -> throw CosicError("K(...): unsupported operator $operator")
}

// escodegen.js:146-167
private const val ALLOW_IN = 1
private const val ALLOW_CALL = 2
private const val ALLOW_UNPARATH_NEW = 4
private const val FUNC_BODY = 8
private const val SEMICOLON_OPT = 32
private const val FOUND_COALESCE = 64

private const val E_FTT = ALLOW_CALL or ALLOW_UNPARATH_NEW
private const val E_TTF = ALLOW_IN or ALLOW_CALL
private const val E_TTT = ALLOW_IN or ALLOW_CALL or ALLOW_UNPARATH_NEW
private const val E_TFF = ALLOW_IN
private const val E_TFT = ALLOW_IN or ALLOW_UNPARATH_NEW

private const val S_TFFF = ALLOW_IN
private const val S_TFFT = ALLOW_IN or SEMICOLON_OPT
private const val S_FFFF = 0
private const val S_TTFF = ALLOW_IN or FUNC_BODY

private const val INDENT = "    "

/** esutils `code.isIdentifierPartES5`: the characters a name may contain (letters, digits, `_`, `$`, marks, joiners). */
private fun isIdentifierPart(c: Char): Boolean {
    if (c == '$' || c == '_' || c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9') return true
    if (c.code < 0x80) return false
    if (c.code == 0x200C || c.code == 0x200D) return true
    return when (Character.getType(c).toByte()) {
        Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER, Character.MODIFIER_LETTER,
        Character.OTHER_LETTER, Character.LETTER_NUMBER, Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK,
        Character.DECIMAL_DIGIT_NUMBER, Character.CONNECTOR_PUNCTUATION,
        -> true
        else -> false
    }
}

private fun isLineTerminator(c: Char): Boolean = c == '\n' || c == '\r' || c == '\u2028' || c == '\u2029'

/** esutils `code.isWhiteSpace`. */
private fun isWhiteSpace(c: Char): Boolean = when (c.code) {
    0x20, 0x09, 0x0B, 0x0C, 0xA0, 0xFEFF -> true
    else -> c.code >= 0x1680 && (c.code == 0x1680 || c.code in 0x2000..0x200A || c.code == 0x202F || c.code == 0x205F || c.code == 0x3000)
}

private fun endsWithLineTerminator(s: String): Boolean = s.isNotEmpty() && isLineTerminator(s.last())

/** escodegen `escapeString` with `quotes: 'single'` (escodegen.js:457-496): the string literal for [value]. */
internal fun jsSingleQuoted(value: String): String {
    val out = StringBuilder("'")
    for (i in value.indices) {
        val c = value[i]
        val code = c.code
        when {
            c == '\'' -> out.append("\\'")
            c == '"' -> out.append(c)
            isLineTerminator(c) || c == '\\' -> out.append(
                when (c) {
                    '\\' -> "\\\\"
                    '\n' -> "\\n"
                    '\r' -> "\\r"
                    '\u2028' -> "\\u2028"
                    else -> "\\u2029"
                },
            )
            !isIdentifierPart(c) && (code < 0x20 || code > 0x7E) -> out.append(escapeAllowed(code, value.getOrNull(i + 1)))
            else -> out.append(c)
        }
    }
    return out.append('\'').toString()
}

/** escodegen `escapeAllowedCharacter` (escodegen.js:386-411). */
private fun escapeAllowed(code: Int, next: Char?): String {
    when (code) {
        0x08 -> return "\\b"
        0x0C -> return "\\f"
        0x09 -> return "\\t"
    }
    val hex = code.toString(16).uppercase()
    return when {
        code > 0xFF -> "\\u" + "0000".substring(hex.length) + hex
        code == 0 && !(next != null && next in '0'..'9') -> "\\0"
        code == 0x0B -> "\\x0B"
        else -> "\\x" + "00".substring(hex.length) + hex
    }
}

private class JsPrinter {
    private var base = ""

    fun generate(node: Node): String = when (node) {
        is BlockStatement, is ExpressionStatement, is VariableDeclaration, is ReturnStatement, is IfStatement,
        is ForStatement, is ForInStatement, is ForOfStatement, is WhileStatement, is DoWhileStatement, is BreakStatement,
        is ContinueStatement, is ThrowStatement, is EmptyStatement, is FunctionDeclaration, is LabeledStatement,
        -> statement(node, S_TFFF)
        else -> expression(node, Precedence.SEQUENCE, E_TTT)
    }

    private inline fun <T> withIndent(body: () -> T): T {
        val previous = base
        base += INDENT
        try {
            return body()
        } finally {
            base = previous
        }
    }

    // ---- helpers of escodegen.js -------------------------------------------------------------------------------

    /** escodegen.js:545-571 `join`: puts a space between two fragments when running them together would change the meaning. */
    private fun join(left: String, right: String): String {
        if (left.isEmpty()) return right
        if (right.isEmpty()) return left
        val l = left.last()
        val r = right[0]
        return if ((l == '+' || l == '-') && l == r || isIdentifierPart(l) && isIdentifierPart(r) || l == '/' && r == 'i') {
            "$left $right"
        } else if (isWhiteSpace(l) || isLineTerminator(l) || isWhiteSpace(r) || isLineTerminator(r)) {
            left + right
        } else {
            "$left $right"
        }
    }

    private fun parenthesize(text: String, current: Int, should: Int): String = if (current < should) "($text)" else text

    private fun semicolon(flags: Int): String = if (flags and SEMICOLON_OPT != 0) "" else ";"

    private fun unsupported(node: Node): Nothing = throw CosicError("K(...): unsupported syntax ${node.type}", node.range)

    // ---- statements --------------------------------------------------------------------------------------------

    private fun statement(node: Node, flags: Int): String = when (node) {
        is BlockStatement -> block(node)
        is ExpressionStatement -> expressionStatement(node, flags)
        is VariableDeclaration -> variableDeclaration(node, flags)
        is ReturnStatement -> {
            val argument = node.argument
            if (argument != null) join("return", expression(argument, Precedence.SEQUENCE, E_TTT)) + semicolon(flags)
            else "return" + semicolon(flags)
        }
        is ThrowStatement -> join("throw", expression(node.argument, Precedence.SEQUENCE, E_TTT)) + semicolon(flags)
        is IfStatement -> ifStatement(node, flags)
        is ForStatement -> forStatement(node, flags)
        is ForInStatement -> iterationFor("in", node.left, node.right, node.body, flags)
        is ForOfStatement -> iterationFor("of", node.left, node.right, node.body, flags)
        is WhileStatement -> {
            val head = withIndent { "while (" + expression(node.test, Precedence.SEQUENCE, E_TTT) + ")" }
            head + maybeBlock(node.body, if (flags and SEMICOLON_OPT != 0) S_TFFT else S_TFFF)
        }
        is DoWhileStatement -> {
            var result = join("do", maybeBlock(node.body, S_TFFF))
            result = maybeBlockSuffix(node.body, result)
            join(result, "while (" + expression(node.test, Precedence.SEQUENCE, E_TTT) + ")" + semicolon(flags))
        }
        is BreakStatement -> (node.label?.let { "break ${it.name}" } ?: "break") + semicolon(flags)
        is ContinueStatement -> (node.label?.let { "continue ${it.name}" } ?: "continue") + semicolon(flags)
        is EmptyStatement -> ";"
        is LabeledStatement -> node.label.name + ":" + maybeBlock(node.body, if (flags and SEMICOLON_OPT != 0) S_TFFT else S_TFFF)
        is FunctionDeclaration -> (if (node.async) "async " else "") + "function " + node.id!!.name + functionBody(node)
        else -> unsupported(node)
    }

    /** escodegen.js:1027-1100: the statements one to a line, each indented one level deeper than the braces. */
    private fun block(node: BlockStatement): String {
        val out = StringBuilder("{\n")
        withIndent {
            var bodyFlags = S_TFFF
            val last = node.body.lastIndex
            for ((i, child) in node.body.withIndex()) {
                if (i == last) bodyFlags = bodyFlags or SEMICOLON_OPT
                val fragment = base + statement(child, bodyFlags)
                out.append(fragment)
                if (!endsWithLineTerminator(fragment)) out.append('\n')
            }
        }
        out.append(base).append('}')
        return out.toString()
    }

    private fun expressionStatement(node: ExpressionStatement, flags: Int): String {
        val fragment = expression(node.expression, Precedence.SEQUENCE, E_TTT)
        // 12.4: `{`, `function`, `class` and `async function` can not start an expression statement (escodegen.js:1337-1346)
        return if (fragment.startsWith("{") || isKeywordPrefixed(fragment, "class") || isKeywordPrefixed(fragment, "function") || isAsyncFunctionPrefixed(fragment)) {
            "($fragment)" + semicolon(flags)
        } else {
            fragment + semicolon(flags)
        }
    }

    private fun isKeywordPrefixed(fragment: String, keyword: String): Boolean {
        if (!fragment.startsWith(keyword)) return false
        val c = fragment.getOrNull(keyword.length) ?: return false
        return c == '{' && keyword == "class" || c == '(' && keyword == "function" || c == '*' && keyword == "function" || isWhiteSpace(c) || isLineTerminator(c)
    }

    private fun isAsyncFunctionPrefixed(fragment: String): Boolean {
        if (!fragment.startsWith("async")) return false
        if (!isWhiteSpace(fragment.getOrNull(5) ?: return false)) return false
        var i = 6
        while (i < fragment.length && isWhiteSpace(fragment[i])) i++
        if (i == fragment.length) return false
        if (!fragment.startsWith("function", i)) return false
        val c = fragment.getOrNull(i + 8) ?: return false
        return c == '(' || isWhiteSpace(c) || c == '*' || isLineTerminator(c)
    }

    private fun variableDeclaration(node: VariableDeclaration, flags: Int): String {
        val itemFlags = if (flags and ALLOW_IN != 0) E_TTT else E_FTT
        val out = StringBuilder(node.kind.keyword).append(' ')
        for ((i, declarator) in node.declarations.withIndex()) {
            if (i > 0) out.append(", ")
            out.append(declarator(declarator, itemFlags))
        }
        return out.append(semicolon(flags)).toString()
    }

    private fun declarator(node: VariableDeclarator, flags: Int): String {
        val init = node.init ?: return pattern(node.id, Precedence.ASSIGNMENT, flags)
        return expression(node.id, Precedence.ASSIGNMENT, flags) + " = " + expression(init, Precedence.ASSIGNMENT, flags)
    }

    private fun ifStatement(node: IfStatement, flags: Int): String {
        var result = withIndent { "if (" + expression(node.test, Precedence.SEQUENCE, E_TTT) + ")" }
        val bodyFlags = if (flags and SEMICOLON_OPT != 0) S_TFFF or SEMICOLON_OPT else S_TFFF
        val alternate = node.alternate
        if (alternate != null) {
            result += maybeBlock(node.consequent, S_TFFF)
            result = maybeBlockSuffix(node.consequent, result)
            result = if (alternate is IfStatement) join(result, "else " + statement(alternate, bodyFlags))
            else join(result, join("else", maybeBlock(alternate, bodyFlags)))
        } else {
            result += maybeBlock(node.consequent, bodyFlags)
        }
        return result
    }

    private fun forStatement(node: ForStatement, flags: Int): String {
        val head = withIndent {
            val out = StringBuilder("for (")
            val init = node.init
            if (init != null) {
                if (init is VariableDeclaration) out.append(statement(init, S_FFFF))
                else out.append(expression(init, Precedence.SEQUENCE, E_FTT)).append(';')
            } else {
                out.append(';')
            }
            val test = node.test
            if (test != null) out.append(' ').append(expression(test, Precedence.SEQUENCE, E_TTT)).append(';') else out.append(';')
            val update = node.update
            if (update != null) out.append(' ').append(expression(update, Precedence.SEQUENCE, E_TTT)).append(')') else out.append(')')
            out.toString()
        }
        return head + maybeBlock(node.body, if (flags and SEMICOLON_OPT != 0) S_TFFT else S_TFFF)
    }

    /** escodegen.js:926-949 */
    private fun iterationFor(operator: String, left: Node, right: Node, body: Node, flags: Int): String {
        val head = withIndent {
            var result = "for ("
            if (left is VariableDeclaration) {
                withIndent {
                    result += left.kind.keyword + " " + declarator(left.declarations[0], E_FTT)
                }
            } else {
                result += expression(left, Precedence.CALL, E_TTT)
            }
            result = join(result, operator)
            join(result, expression(right, Precedence.ASSIGNMENT, E_TTT)) + ")"
        }
        return head + maybeBlock(body, if (flags and SEMICOLON_OPT != 0) S_TFFT else S_TFFF)
    }

    /** escodegen.js:830-851 */
    private fun maybeBlock(node: Node, flags: Int): String {
        if (node is BlockStatement) return " " + statement(node, flags)
        if (node is EmptyStatement) return ";"
        return withIndent { "\n" + base + statement(node, flags) }
    }

    /** escodegen.js:853-862 */
    private fun maybeBlockSuffix(node: Node, result: String): String {
        val ends = endsWithLineTerminator(result)
        if (node is BlockStatement && !ends) return "$result "
        if (ends) return result + base
        return "$result\n$base"
    }

    // ---- functions ---------------------------------------------------------------------------------------------

    private fun pattern(node: Node, precedence: Int, flags: Int): String =
        if (node is Identifier) node.name else expression(node, precedence, flags)

    /** escodegen.js:889-936 `generateFunctionParams` and `generateFunctionBody` */
    private fun functionBody(node: FunctionNode): String {
        val out = StringBuilder()
        val single = node is ArrowFunctionExpression && node.params.size == 1 && node.params[0] is Identifier
        if (single) {
            out.append(if (node.async) "async " else "").append((node.params[0] as Identifier).name)
        } else {
            if (node is ArrowFunctionExpression && node.async) out.append("async ")
            out.append('(')
            for ((i, param) in node.params.withIndex()) {
                if (i > 0) out.append(", ")
                out.append(pattern(param, Precedence.ASSIGNMENT, E_TTT))
            }
            out.append(')')
        }
        if (node is ArrowFunctionExpression) out.append(" =>")
        if (node.expression) {
            out.append(' ')
            var body = expression(node.body, Precedence.ASSIGNMENT, E_TTT)
            if (body.startsWith("{")) body = "($body)"
            out.append(body)
        } else {
            out.append(maybeBlock(node.body, S_TTFF))
        }
        return out.toString()
    }

    // ---- expressions -------------------------------------------------------------------------------------------

    private fun expression(node: Node, precedence: Int, flags0: Int): String {
        var flags = flags0
        return when (node) {
            is Identifier -> node.name
            is Literal -> literal(node)
            is ThisExpression -> "this"
            is BinaryExpression -> binary(node.operator.symbol, node.left, node.right, precedence, flags)
            is LogicalExpression -> {
                if (node.operator == LogicalOp.NULLISH) flags = flags or FOUND_COALESCE
                binary(node.operator.symbol, node.left, node.right, precedence, flags)
            }
            is UnaryExpression -> unary(node, precedence)
            is UpdateExpression -> {
                if (node.prefix) {
                    parenthesize(node.operator.symbol + expression(node.argument, Precedence.UNARY, E_TTT), Precedence.UNARY, precedence)
                } else {
                    parenthesize(expression(node.argument, Precedence.POSTFIX, E_TTT) + node.operator.symbol, Precedence.POSTFIX, precedence)
                }
            }
            is AwaitExpression ->
                parenthesize(join("await", expression(node.argument, Precedence.AWAIT, E_TTT)), Precedence.AWAIT, precedence)
            is AssignmentExpression -> assignment(node.left, node.right, node.operator.symbol, precedence, flags)
            is AssignmentPattern -> assignment(node.left, node.right, "=", precedence, flags)
            is ConditionalExpression -> {
                if (Precedence.CONDITIONAL < precedence) flags = flags or ALLOW_IN
                parenthesize(
                    expression(node.test, Precedence.COALESCE, flags) + " ? " +
                        expression(node.consequent, Precedence.ASSIGNMENT, flags) + " : " +
                        expression(node.alternate, Precedence.ASSIGNMENT, flags),
                    Precedence.CONDITIONAL,
                    precedence,
                )
            }
            is SequenceExpression -> {
                if (Precedence.SEQUENCE < precedence) flags = flags or ALLOW_IN
                parenthesize(node.expressions.joinToString(", ") { expression(it, Precedence.ASSIGNMENT, flags) }, Precedence.SEQUENCE, precedence)
            }
            is ArrowFunctionExpression -> parenthesize(functionBody(node), Precedence.ARROW_FUNCTION, precedence)
            is FunctionExpression ->
                (if (node.async) "async " else "") + "function" + (node.id?.let { " ${it.name}" } ?: " ") + functionBody(node)
            is CallExpression -> call(node, flags, precedence)
            is NewExpression -> {
                var result = join("new", expression(node.callee, Precedence.NEW, E_TFF))
                result += "(" + node.arguments.joinToString(", ") { expression(it, Precedence.ASSIGNMENT, E_TTT) } + ")"
                parenthesize(result, Precedence.NEW, precedence)
            }
            is ChainExpression -> {
                if (Precedence.OPTIONAL_CHAINING < precedence) flags = flags or ALLOW_CALL
                parenthesize(expression(node.expression, Precedence.OPTIONAL_CHAINING, flags), Precedence.OPTIONAL_CHAINING, precedence)
            }
            is MemberExpression -> member(node, flags, precedence)
            is ArrayExpression -> array(node.elements, multilineAllowed = true)
            is ArrayPattern -> array(node.elements, multilineAllowed = false)
            is ObjectExpression -> objectExpression(node)
            is ObjectPattern -> objectPattern(node)
            is Property -> property(node)
            is SpreadElement -> "..." + expression(node.argument, Precedence.ASSIGNMENT, E_TTT)
            is RestElement -> "..." + pattern(node.argument, Precedence.SEQUENCE, 0)
            is TemplateLiteral -> template(node)
            is TemplateElement -> node.raw
            is TaggedTemplateExpression -> {
                val itemFlags = if (flags and ALLOW_CALL != 0) E_TTF else E_TFF
                parenthesize(
                    expression(node.tag, Precedence.CALL, itemFlags) + expression(node.quasi, Precedence.PRIMARY, ALLOW_UNPARATH_NEW),
                    Precedence.TAGGED_TEMPLATE,
                    precedence,
                )
            }
            else -> unsupported(node)
        }
    }

    /** escodegen.js:2342-2400: the value of the literal, not its source: `.13` is `0.13`, `"a"` is `'a'`. */
    private fun literal(node: Literal): String = when (val v = node.value) {
        null -> "null"
        is String -> jsSingleQuoted(v)
        is Double -> {
            if (v.isNaN()) throw CosicError("Numeric literal whose value is NaN", node.range)
            if (v.isInfinite()) "1e+400" else jsNumberToString(v)
        }
        is Boolean -> if (v) "true" else "false"
        else -> unsupported(node)
    }

    private fun binary(operator: String, left: Node, right: Node, precedence: Int, flags0: Int): String {
        var flags = flags0
        val current = binaryPrecedence(operator)
        val leftPrecedence = if (operator == "**") Precedence.POSTFIX else current
        val rightPrecedence = if (operator == "**") current else current + 1
        if (current < precedence) flags = flags or ALLOW_IN
        val leftFragment = expression(left, leftPrecedence, flags)
        var result = if (leftFragment.last() == '/' && isIdentifierPart(operator[0])) "$leftFragment $operator" else join(leftFragment, operator)
        val rightFragment = expression(right, rightPrecedence, flags)
        result = if (operator == "/" && rightFragment.startsWith("/") || operator.last() == '<' && rightFragment.startsWith("!--")) {
            "$result $rightFragment"
        } else {
            join(result, rightFragment)
        }
        if (operator == "in" && flags and ALLOW_IN == 0) return "($result)"
        if ((operator == "||" || operator == "&&") && flags and FOUND_COALESCE != 0) return "($result)"
        return parenthesize(result, current, precedence)
    }

    /** escodegen.js:1994-2025 */
    private fun unary(node: UnaryExpression, precedence: Int): String {
        val fragment = expression(node.argument, Precedence.UNARY, E_TTT)
        val operator = node.operator.symbol
        val result = if (operator.length > 2) {
            join(operator, fragment)
        } else {
            val l = operator.last()
            val r = fragment[0]
            if ((l == '+' || l == '-') && l == r || isIdentifierPart(l) && isIdentifierPart(r)) "$operator $fragment" else operator + fragment
        }
        return parenthesize(result, Precedence.UNARY, precedence)
    }

    private fun assignment(left: Node, right: Node, operator: String, precedence: Int, flags0: Int): String {
        var flags = flags0
        if (Precedence.ASSIGNMENT < precedence) flags = flags or ALLOW_IN
        return parenthesize(
            expression(left, Precedence.CALL, flags) + " $operator " + expression(right, Precedence.ASSIGNMENT, flags),
            Precedence.ASSIGNMENT,
            precedence,
        )
    }

    private fun call(node: CallExpression, flags: Int, precedence: Int): String {
        val result = expression(node.callee, Precedence.CALL, E_TTF) + (if (node.optional) "?." else "") +
            "(" + node.arguments.joinToString(", ") { expression(it, Precedence.ASSIGNMENT, E_TTT) } + ")"
        if (flags and ALLOW_CALL == 0) return "($result)"
        return parenthesize(result, Precedence.CALL, precedence)
    }

    private fun member(node: MemberExpression, flags: Int, precedence: Int): String {
        val out = StringBuilder(expression(node.obj, Precedence.CALL, if (flags and ALLOW_CALL != 0) E_TTF else E_TFF))
        if (node.computed) {
            if (node.optional) out.append("?.")
            out.append('[')
            out.append(expression(node.property, Precedence.SEQUENCE, if (flags and ALLOW_CALL != 0) E_TTT else E_TFT))
            out.append(']')
        } else {
            val obj = node.obj
            if (!node.optional && obj is Literal && obj.value is Double) {
                // `1.toString()` is a syntax error: a whole number followed by the dot needs a space (escodegen.js:1962-1976)
                val fragment = out.toString()
                if ('.' !in fragment && fragment.none { it == 'e' || it == 'E' || it == 'x' || it == 'X' } &&
                    fragment.last() in '0'..'9' && !(fragment.length >= 2 && fragment[0] == '0')
                ) {
                    out.append(' ')
                }
            }
            out.append(if (node.optional) "?." else ".")
            out.append((node.property as Identifier).name)
        }
        return parenthesize(out.toString(), Precedence.MEMBER, precedence)
    }

    /** escodegen.js:2089-2125: an array of more than one element goes one element per line. */
    private fun array(elements: List<Node?>, multilineAllowed: Boolean): String {
        if (elements.isEmpty()) return "[]"
        val multiline = multilineAllowed && elements.size > 1
        val out = StringBuilder("[").append(if (multiline) "\n" else "")
        withIndent {
            for ((i, element) in elements.withIndex()) {
                if (element == null) {
                    if (multiline) out.append(base)
                    if (i + 1 == elements.size) out.append(',')
                } else {
                    out.append(if (multiline) base else "")
                    out.append(expression(element, Precedence.ASSIGNMENT, E_TTT))
                }
                if (i + 1 < elements.size) out.append(if (multiline) ",\n" else ", ")
            }
        }
        if (multiline && !endsWithLineTerminator(out.toString())) out.append('\n')
        out.append(if (multiline) base else "")
        out.append(']')
        return out.toString()
    }

    /** escodegen.js:2195-2235 */
    private fun objectExpression(node: ObjectExpression): String {
        if (node.properties.isEmpty()) return "{}"
        val multiline = node.properties.size > 1
        val first = withIndent { expression(node.properties[0], Precedence.SEQUENCE, E_TTT) }
        if (!multiline && first.none { it == '\n' || it == '\r' }) return "{ $first }"
        val out = StringBuilder()
        withIndent {
            out.append("{\n").append(base).append(first)
            if (multiline) {
                out.append(",\n")
                for (i in 1 until node.properties.size) {
                    out.append(base)
                    out.append(expression(node.properties[i], Precedence.SEQUENCE, E_TTT))
                    if (i + 1 < node.properties.size) out.append(",\n")
                }
            }
        }
        if (!endsWithLineTerminator(out.toString())) out.append('\n')
        out.append(base).append('}')
        return out.toString()
    }

    /** escodegen.js:2237-2290 */
    private fun objectPattern(node: ObjectPattern): String {
        if (node.properties.isEmpty()) return "{}"
        var multiline = false
        if (node.properties.size == 1) {
            val only = node.properties[0]
            if (only is Property && only.value !is Identifier) multiline = true
        } else {
            for (p in node.properties) {
                if (p is Property && !p.shorthand) {
                    multiline = true
                    break
                }
            }
        }
        val out = StringBuilder("{").append(if (multiline) "\n" else "")
        withIndent {
            for ((i, p) in node.properties.withIndex()) {
                out.append(if (multiline) base else "")
                out.append(expression(p, Precedence.SEQUENCE, E_TTT))
                if (i + 1 < node.properties.size) out.append(if (multiline) ",\n" else ", ")
            }
        }
        if (multiline && !endsWithLineTerminator(out.toString())) out.append('\n')
        out.append(if (multiline) base else "")
        out.append('}')
        return out.toString()
    }

    /** escodegen.js:2164-2193 */
    private fun property(node: Property): String {
        if (node.kind == PropertyKind.GET || node.kind == PropertyKind.SET) {
            val keyword = if (node.kind == PropertyKind.GET) "get" else "set"
            return "$keyword ${propertyKey(node)}${functionBody(node.value as FunctionNode)}"
        }
        if (node.shorthand) {
            val value = node.value
            if (value is AssignmentPattern) return assignment(value.left, value.right, "=", Precedence.SEQUENCE, E_TTT)
            return propertyKey(node)
        }
        if (node.method) {
            val function = node.value as FunctionNode
            return (if (function.async) "async " else "") + propertyKey(node) + functionBody(function)
        }
        return propertyKey(node) + ": " + expression(node.value, Precedence.ASSIGNMENT, E_TTT)
    }

    private fun propertyKey(node: Property): String {
        val key = expression(node.key, Precedence.ASSIGNMENT, E_TTT)
        return if (node.computed) "[$key]" else key
    }

    private fun template(node: TemplateLiteral): String {
        val out = StringBuilder("`")
        for ((i, quasi) in node.quasis.withIndex()) {
            out.append(quasi.raw)
            if (i + 1 < node.quasis.size) {
                out.append("\${ ")
                out.append(expression(node.expressions[i], Precedence.SEQUENCE, E_TTT))
                out.append(" }")
            }
        }
        return out.append('`').toString()
    }
}
