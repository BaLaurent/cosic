package cosic.script.next

import cosic.pattern.jsNumberToString
import cosic.pattern.jsonStringify
import java.security.MessageDigest

/*
 * The tree as text, one node per line, children indented, in source order:
 *
 *     <Type> <start>-<end>[ attribute=value ...]
 *
 * tools/strudel-ref/parser-oracle.mjs prints acorn's tree in exactly this format, so the two can be compared as strings.
 * The traversal below is written out per node type on purpose: it must not depend on Node.forEachChild, which has a test of its own.
 */

internal fun dumpAst(node: Node): String {
    val out = StringBuilder()
    dump(node, 0, out)
    return out.toString().trimEnd('\n')
}

/** The first 16 hex digits of the SHA-256 of the dump: what corpus-digest.json holds. */
internal fun astDigest(node: Node): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(dumpAst(node).toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }.take(16)
}

/** The number of nodes of the tree, holes excluded. */
internal fun countNodes(node: Node): Int = 1 + children(node).sumOf { if (it == null) 0 else countNodes(it) }

private fun dump(node: Node?, depth: Int, out: StringBuilder) {
    out.append("  ".repeat(depth))
    if (node == null) {
        out.append("<hole>\n")
        return
    }
    out.append(node.type).append(' ').append(node.start).append('-').append(node.end).append(attributes(node)).append('\n')
    for (child in children(node)) dump(child, depth + 1, out)
}

private fun json(text: String): String = jsonStringify(text)!!

private fun literalValue(value: Any?): String = when (value) {
    is Double -> jsNumberToString(value)
    is String -> json(value)
    else -> value.toString() // true, false, null
}

private fun attributes(node: Node): String = when (node) {
    is Identifier -> " name=${node.name}"
    is Literal -> " raw=${json(node.raw)} value=${literalValue(node.value)}"
    is TemplateElement -> " cooked=${node.cooked?.let(::json) ?: "null"} raw=${json(node.raw)} tail=${node.tail}"
    is VariableDeclaration -> " kind=${node.kind.keyword}"
    is FunctionDeclaration -> " async=${node.async}"
    is FunctionExpression -> " async=${node.async}"
    is ArrowFunctionExpression -> " async=${node.async} expression=${node.expression}"
    is UnaryExpression -> " operator=${json(node.operator.symbol)}"
    is UpdateExpression -> " operator=${json(node.operator.symbol)} prefix=${node.prefix}"
    is BinaryExpression -> " operator=${json(node.operator.symbol)}"
    is LogicalExpression -> " operator=${json(node.operator.symbol)}"
    is AssignmentExpression -> " operator=${json(node.operator.symbol)}"
    is MemberExpression -> " computed=${node.computed} optional=${node.optional}"
    is CallExpression -> " optional=${node.optional}"
    is Property -> " kind=${node.kind.name.lowercase()} computed=${node.computed} shorthand=${node.shorthand} method=${node.method}"
    else -> ""
}

private fun children(node: Node): List<Node?> = when (node) {
    is Program -> node.body
    is ExpressionStatement -> listOf(node.expression)
    is BlockStatement -> node.body
    is EmptyStatement -> emptyList()
    is VariableDeclaration -> node.declarations
    is VariableDeclarator -> listOfNotNull(node.id, node.init)
    is FunctionNode -> listOfNotNull(node.id) + node.params + node.body
    is ReturnStatement -> listOfNotNull(node.argument)
    is IfStatement -> listOfNotNull(node.test, node.consequent, node.alternate)
    is ForStatement -> listOfNotNull(node.init, node.test, node.update, node.body)
    is ForInStatement -> listOf(node.left, node.right, node.body)
    is ForOfStatement -> listOf(node.left, node.right, node.body)
    is WhileStatement -> listOf(node.test, node.body)
    is DoWhileStatement -> listOf(node.body, node.test)
    is BreakStatement -> listOfNotNull(node.label)
    is ContinueStatement -> listOfNotNull(node.label)
    is ThrowStatement -> listOf(node.argument)
    is TryStatement -> listOfNotNull(node.block, node.handler, node.finalizer)
    is CatchClause -> listOfNotNull(node.param, node.body)
    is SwitchStatement -> listOf(node.discriminant) + node.cases
    is SwitchCase -> listOfNotNull(node.test) + node.consequent
    is LabeledStatement -> listOf(node.label, node.body)
    is Identifier -> emptyList()
    is Literal -> emptyList()
    is TemplateLiteral -> node.quasis.indices.flatMap { i -> listOfNotNull(node.quasis[i], node.expressions.getOrNull(i)) }
    is TemplateElement -> emptyList()
    is TaggedTemplateExpression -> listOf(node.tag, node.quasi)
    is ArrayExpression -> node.elements
    is ObjectExpression -> node.properties
    is Property -> listOf(node.key, node.value)
    is UnaryExpression -> listOf(node.argument)
    is UpdateExpression -> listOf(node.argument)
    is BinaryExpression -> listOf(node.left, node.right)
    is LogicalExpression -> listOf(node.left, node.right)
    is AssignmentExpression -> listOf(node.left, node.right)
    is ConditionalExpression -> listOf(node.test, node.consequent, node.alternate)
    is CallExpression -> listOf(node.callee) + node.arguments
    is NewExpression -> listOf(node.callee) + node.arguments
    is MemberExpression -> listOf(node.obj, node.property)
    is ChainExpression -> listOf(node.expression)
    is SequenceExpression -> node.expressions
    is SpreadElement -> listOf(node.argument)
    is AwaitExpression -> listOf(node.argument)
    is ThisExpression -> emptyList()
    is ObjectPattern -> node.properties
    is ArrayPattern -> node.elements
    is AssignmentPattern -> listOf(node.left, node.right)
    is RestElement -> listOf(node.argument)
}
