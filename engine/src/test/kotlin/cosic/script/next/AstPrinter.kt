package cosic.script.next

import cosic.pattern.jsNumberToString

/*
 * The tree as JavaScript text, laid out the way escodegen 2.1.0 (what Strudel's transpiler prints with) lays it out by
 * default: statements one per line, four spaces of indent, single-quoted strings, a `;` after every statement, objects
 * and arrays of more than one member over several lines. It reads what the transpiler tests need (calls, members,
 * literals, templates, `await`, `return`, arrows, operators, the object and array literals) and fails with an
 * [UnsupportedOperationException] on a node it does not know, so a test never compares text that is not what it says.
 */

internal fun printAst(node: Node): String = AstPrinter().statement(node, 0)

/** An expression on its own, without the `;` of a statement. */
internal fun printExpression(node: Node): String = AstPrinter().expression(node, 0, 0)

private object Precedence {
    const val SEQUENCE = 0
    const val ASSIGNMENT = 1
    const val CONDITIONAL = 2
    const val COALESCE = 3
    const val EXPONENTIATION = 14
    const val UNARY = 15
    const val POSTFIX = 16
    const val CALL = 18
    const val NEW = 19
    const val TAGGED_TEMPLATE = 20
    const val MEMBER = 21
    const val PRIMARY = 22
}

private fun binaryPrecedence(symbol: String): Int = when (symbol) {
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
    "*", "/", "%" -> 13
    "**" -> 14
    else -> throw UnsupportedOperationException("operator $symbol")
}

private class AstPrinter {
    fun statement(node: Node, depth: Int): String {
        val indent = "    ".repeat(depth)
        return when (node) {
            is Program -> node.body.joinToString("\n") { statement(it, depth) }
            is ExpressionStatement -> {
                val text = expression(node.expression, Precedence.SEQUENCE, depth)
                // a statement may not start with `{` or `function`: the original wraps the expression in parentheses
                val wrapped = if (text.startsWith("{") || text.startsWith("function")) "($text)" else text
                "$indent$wrapped;"
            }
            is ReturnStatement -> "${indent}return" + (node.argument?.let { " " + expression(it, Precedence.SEQUENCE, depth) } ?: "") + ";"
            is BlockStatement -> block(node, depth)
            is EmptyStatement -> "$indent;"
            is VariableDeclaration -> "$indent${node.kind.keyword} " + node.declarations.joinToString(", ") { declarator(it, depth) } + ";"
            else -> throw UnsupportedOperationException("statement ${node.type}")
        }
    }

    private fun declarator(node: VariableDeclarator, depth: Int): String =
        pattern(node.id, depth) + (node.init?.let { " = " + expression(it, Precedence.ASSIGNMENT, depth) } ?: "")

    private fun block(node: BlockStatement, depth: Int): String {
        val indent = "    ".repeat(depth)
        if (node.body.isEmpty()) return "$indent{\n$indent}"
        return "$indent{\n" + node.body.joinToString("\n") { statement(it, depth + 1) } + "\n$indent}"
    }

    private fun pattern(node: Node, depth: Int): String = when (node) {
        is Identifier -> node.name
        is AssignmentPattern -> pattern(node.left, depth) + " = " + expression(node.right, Precedence.ASSIGNMENT, depth)
        is RestElement -> "..." + pattern(node.argument, depth)
        else -> throw UnsupportedOperationException("pattern ${node.type}")
    }

    fun expression(node: Node, precedence: Int, depth: Int): String {
        val (text, own) = print(node, depth)
        return if (own < precedence) "($text)" else text
    }

    private fun print(node: Node, depth: Int): Pair<String, Int> = when (node) {
        is Identifier -> node.name to Precedence.PRIMARY
        is ThisExpression -> "this" to Precedence.PRIMARY
        is Literal -> literal(node) to Precedence.PRIMARY
        is TemplateLiteral -> template(node, depth) to Precedence.PRIMARY
        is TaggedTemplateExpression ->
            (expression(node.tag, Precedence.MEMBER, depth) + template(node.quasi, depth)) to Precedence.TAGGED_TEMPLATE
        is CallExpression -> {
            val args = node.arguments.joinToString(", ") { expression(it, Precedence.ASSIGNMENT, depth) }
            (expression(node.callee, Precedence.CALL, depth) + (if (node.optional) "?." else "") + "($args)") to Precedence.CALL
        }
        is NewExpression -> {
            val args = node.arguments.joinToString(", ") { expression(it, Precedence.ASSIGNMENT, depth) }
            ("new " + expression(node.callee, Precedence.CALL, depth) + "($args)") to Precedence.NEW
        }
        is MemberExpression -> {
            val obj = expression(node.obj, Precedence.CALL, depth)
            val text = when {
                node.computed -> obj + (if (node.optional) "?." else "") + "[" + expression(node.property, Precedence.SEQUENCE, depth) + "]"
                else -> obj + (if (node.optional) "?." else ".") + (node.property as Identifier).name
            }
            text to Precedence.MEMBER
        }
        is ChainExpression -> print(node.expression, depth)
        is AwaitExpression -> ("await " + expression(node.argument, Precedence.UNARY, depth)) to Precedence.UNARY
        is SpreadElement -> ("..." + expression(node.argument, Precedence.ASSIGNMENT, depth)) to Precedence.ASSIGNMENT
        is UnaryExpression -> {
            val space = if (node.operator.symbol[0].isLetter()) " " else ""
            (node.operator.symbol + space + expression(node.argument, Precedence.UNARY, depth)) to Precedence.UNARY
        }
        is UpdateExpression -> {
            val symbol = node.operator.symbol
            val argument = expression(node.argument, Precedence.POSTFIX, depth)
            (if (node.prefix) symbol + argument else argument + symbol) to (if (node.prefix) Precedence.UNARY else Precedence.POSTFIX)
        }
        is BinaryExpression -> binary(node.operator.symbol, node.left, node.right, depth)
        is LogicalExpression -> binary(node.operator.symbol, node.left, node.right, depth)
        is AssignmentExpression ->
            (expression(node.left, Precedence.POSTFIX, depth) + " ${node.operator.symbol} " + expression(node.right, Precedence.ASSIGNMENT, depth)) to Precedence.ASSIGNMENT
        is ConditionalExpression ->
            (expression(node.test, Precedence.COALESCE, depth) + " ? " + expression(node.consequent, Precedence.ASSIGNMENT, depth) +
                " : " + expression(node.alternate, Precedence.ASSIGNMENT, depth)) to Precedence.CONDITIONAL
        is SequenceExpression -> node.expressions.joinToString(", ") { expression(it, Precedence.ASSIGNMENT, depth) } to Precedence.SEQUENCE
        is ArrayExpression -> array(node, depth) to Precedence.PRIMARY
        is ObjectExpression -> obj(node, depth) to Precedence.PRIMARY
        is ArrowFunctionExpression -> arrow(node, depth) to Precedence.CONDITIONAL
        is FunctionExpression -> function(node, depth) to Precedence.PRIMARY
        else -> throw UnsupportedOperationException("expression ${node.type}")
    }

    private fun binary(symbol: String, left: Node, right: Node, depth: Int): Pair<String, Int> {
        val own = binaryPrecedence(symbol)
        val (leftPrecedence, rightPrecedence) = if (symbol == "**") (own + 1) to own else own to (own + 1)
        return (expression(left, leftPrecedence, depth) + " $symbol " + expression(right, rightPrecedence, depth)) to own
    }

    private fun literal(node: Literal): String = when (val value = node.value) {
        null -> "null"
        is String -> stringLiteral(value)
        is Double -> jsNumberToString(value)
        else -> value.toString()
    }

    private fun template(node: TemplateLiteral, depth: Int): String {
        val out = StringBuilder("`")
        for (i in node.quasis.indices) {
            out.append(node.quasis[i].raw)
            if (i < node.expressions.size) out.append("\${").append(expression(node.expressions[i], Precedence.SEQUENCE, depth)).append('}')
        }
        return out.append('`').toString()
    }

    private fun array(node: ArrayExpression, depth: Int): String {
        if (node.elements.isEmpty()) return "[]"
        val parts = node.elements.map { if (it == null) "" else expression(it, Precedence.ASSIGNMENT, depth + 1) }
        if (parts.size == 1) return "[${parts[0]}]"
        val indent = "    ".repeat(depth + 1)
        return "[\n" + parts.joinToString(",\n") { indent + it } + "\n" + "    ".repeat(depth) + "]"
    }

    private fun obj(node: ObjectExpression, depth: Int): String {
        if (node.properties.isEmpty()) return "{}"
        val parts = node.properties.map { member ->
            when (member) {
                is SpreadElement -> "..." + expression(member.argument, Precedence.ASSIGNMENT, depth + 1)
                is Property -> {
                    if (member.kind != PropertyKind.INIT || member.method) throw UnsupportedOperationException("method or accessor")
                    val key = if (member.computed) "[" + expression(member.key, Precedence.ASSIGNMENT, depth + 1) + "]" else propertyKey(member.key)
                    if (member.shorthand) key else key + ": " + expression(member.value, Precedence.ASSIGNMENT, depth + 1)
                }
                else -> throw UnsupportedOperationException("member ${member.type}")
            }
        }
        if (parts.size == 1) return "{ ${parts[0]} }"
        val indent = "    ".repeat(depth + 1)
        return "{\n" + parts.joinToString(",\n") { indent + it } + "\n" + "    ".repeat(depth) + "}"
    }

    private fun propertyKey(key: Node): String = when (key) {
        is Identifier -> key.name
        is Literal -> literal(key)
        else -> throw UnsupportedOperationException("key ${key.type}")
    }

    private fun arrow(node: ArrowFunctionExpression, depth: Int): String {
        val params = if (node.params.size == 1 && node.params[0] is Identifier) pattern(node.params[0], depth)
        else node.params.joinToString(", ", "(", ")") { pattern(it, depth) }
        val prefix = (if (node.async) "async " else "") + "$params => "
        return if (node.expression) {
            val body = expression(node.body, Precedence.ASSIGNMENT, depth)
            prefix + if (body.startsWith("{")) "($body)" else body
        } else {
            prefix + block(node.body as BlockStatement, depth).trimStart()
        }
    }

    private fun function(node: FunctionExpression, depth: Int): String {
        val params = node.params.joinToString(", ", "(", ")") { pattern(it, depth) }
        val name = node.id?.name
        return (if (node.async) "async " else "") + "function" + (if (name != null) " $name" else " ") + params + " " + block(node.body as BlockStatement, depth).trimStart()
    }

    /** escodegen's `escapeString` with single quotes: only `'`, `\`, line terminators and characters that are not printable ASCII are escaped. */
    private fun stringLiteral(value: String): String {
        val out = StringBuilder("'")
        for (c in value) {
            when {
                c == '\'' -> out.append("\\'")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\u2028' -> out.append("\\u2028")
                c == '\u2029' -> out.append("\\u2029")
                c == '\t' -> out.append("\\t")
                c == '\b' -> out.append("\\b")
                c == '\u000c' -> out.append("\\f")
                c < ' ' -> out.append("\\x").append("%02X".format(c.code))
                c.code > 0x7e && !c.isLetterOrDigit() ->
                    out.append(if (c.code > 0xff) "\\u" + "%04X".format(c.code) else "\\x" + "%02X".format(c.code))
                else -> out.append(c)
            }
        }
        return out.append('\'').toString()
    }
}
