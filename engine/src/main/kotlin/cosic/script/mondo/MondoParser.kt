package cosic.script.mondo

import cosic.SourceRange
import cosic.pattern.CosicError
import cosic.script.isJsWhitespace
import java.util.regex.Pattern as RegexPattern

/*
 * The parser of mondo notation, a port of `packages/mondo/mondo.mjs` (`MondoParser`, `printAst`) at the pinned commit of
 * Strudel. Mondo is a small lisp: `(s bd hh)` calls `s` with two arguments. Around that lisp the parser knows the
 * sugar of mini-notation: `[a b]` and `<a b>` and `{a b}` are calls of `square`, `angle` and `curly`; infix operators
 * (`a*2`, `bd:3`, `0..7`, `a!2`) are calls of the operator written first (`(* 2 a)`: the right operand comes first, so
 * that a pattern is always the last argument); `,` and `$` stack, `|` chooses; `#` chains (`s bd # fast 2` is
 * `(fast 2 (s bd))`) and, with nothing in front of it, makes a function: `(# fast 2)` is `(fn (_) (fast 2 _))`.
 *
 * The syntax tree is built from [MondoNode]s. The tree the parser returns has the sugar removed. The functions below
 * keep the shape of the original, down to which lists they change in place and which they copy, because the order in
 * which the sugar is removed (all of the operators of the first group, left to right, then those of the second group,
 * then the pipes) is part of what mondo means.
 */

/**
 * One node of a syntax tree, and the token a leaf is made from. [type] is one of `list`, `plain`, `number`,
 * `quotes_double`, `quotes_single`, `comment`, `op`, `pipe`, `stack`, `or` or `open_*`/`close_*`. A leaf has the text it
 * was written as in [value] (quotes included) and no children; a list has no value. [loc] is where the text was in the
 * program, or null when the parser was asked not to track positions or when the node was made by the parser itself (a
 * desugared operator, the name of a function it inserts).
 */
internal class MondoNode(
    val type: String,
    val value: String?,
    val children: MutableList<MondoNode>,
    val loc: IntArray?,
) {
    companion object {
        fun plain(value: String): MondoNode = MondoNode("plain", value, ArrayList(0), null)

        fun list(children: MutableList<MondoNode>, loc: IntArray? = null): MondoNode = MondoNode("list", null, children, loc)
    }
}

internal typealias MondoToken = MondoNode

/** Where the first group of operators (`*` ... `..`) and the second (`&`) bind: the first group completely before the second. */
private val OP_PRECEDENCE: List<List<String>> = listOf(listOf("*", "/", ":", "!", "@", "%", "?", "+", "-", ".."), listOf("&"))

/**
 * The token types in the order they are tried; the first that matches the start of what is left wins. `.` of the
 * original does not match a line terminator of JavaScript, which are all four listed here. The comment ends at a `\n` or
 * at the end of the text (`\z`, because `$` of the original does not stop before a final line break and Java's does).
 */
private val TOKEN_TYPES: List<Pair<String, RegexPattern>> = listOf(
    "comment" to "^//([^\\n\\r\\u2028\\u2029]*?)(?=\\n|\\z)",
    "quotes_double" to "^\"([^\\n\\r\\u2028\\u2029]*?)\"",
    "quotes_single" to "^'([^\\n\\r\\u2028\\u2029]*?)'",
    "open_list" to "^\\(",
    "close_list" to "^\\)",
    "open_angle" to "^<",
    "close_angle" to "^>",
    "open_square" to "^\\[",
    "close_square" to "^\\]",
    "open_curly" to "^\\{",
    "close_curly" to "^\\}",
    "number" to "^-?[0-9]*\\.?[0-9]+", // before the operators: `-3` is a number
    "op" to "^[*/:!@%?+\\-&]|^\\.{2}",
    "pipe" to "^#",
    "stack" to "^[,$]",
    "or" to "^[|]",
    "plain" to "^[a-zA-Z0-9\\-~_^#]+",
).map { (type, regex) -> type to RegexPattern.compile(regex) }

internal class MondoParser {
    /**
     * The tokens of [code], each with its position when [offset] is not -1: [offset] is where the first character of
     * [code] is in the program the code is part of (the position of a token in the program is its position in [code] plus
     * [offset]). Text that no token type matches is an error.
     */
    fun tokenize(code: String, offset: Int = 0): List<MondoToken> {
        val tokens = ArrayList<MondoToken>()
        val locEnabled = offset != -1
        // the original trims both ends of what is left before each token, and adds what it cut at the start to the offset
        var end = code.length
        while (end > 0 && isJsWhitespace(code[end - 1])) end--
        var pos = 0
        while (true) {
            while (pos < end && isJsWhitespace(code[pos])) pos++
            if (pos >= end) break
            val token = nextToken(code, pos, end, if (locEnabled) offset + pos else -1)
            pos += token.value!!.length
            tokens.add(token)
        }
        return tokens
    }

    private fun nextToken(code: String, pos: Int, end: Int, offset: Int): MondoToken {
        for ((type, regex) in TOKEN_TYPES) {
            val matcher = regex.matcher(code).region(pos, end)
            if (matcher.lookingAt()) {
                val text = matcher.group()
                return MondoNode(type, text, ArrayList(0), if (offset != -1) intArrayOf(offset, offset + text.length) else null)
            }
        }
        val range = if (offset != -1) SourceRange(offset, offset + 1) else null
        throw CosicError("mondo: could not match '${code.substring(pos, end)}'", range)
    }

    /**
     * The syntax tree of [code] with its sugar removed. A program with no tokens is an empty list; several expressions
     * or a single one that is not a list are the children of one list (the call `s bd` is the list `(s bd)`); a single
     * list is itself the program. [offset] is as for [tokenize].
     */
    fun parse(code: String, offset: Int): MondoNode {
        val state = ParseState(tokenize(code, offset), if (offset != -1) SourceRange(offset, offset + code.length) else null)
        val expressions = ArrayList<MondoNode>()
        while (state.hasTokens()) expressions.add(state.parseExpr())
        if (expressions.isEmpty()) return MondoNode.list(ArrayList(0))
        if (expressions.size > 1 || expressions[0].type != "list") return MondoNode.list(desugar(expressions, null))
        return expressions[0]
    }

    /** The positions of the atoms of [code], in the order of the program: every leaf of the tree that has one. */
    fun getLocations(code: String, offset: Int = 0): List<IntArray> {
        val locations = ArrayList<IntArray>()
        fun walk(ast: MondoNode) {
            if (ast.type == "list") {
                for (child in ast.children) walk(child)
            } else if (ast.loc != null) {
                locations.add(ast.loc)
            }
        }
        walk(parse(code, offset))
        return locations
    }

    /** What is left to parse; `parse_expr` of the original reads the first token and `consume` takes it. */
    private class ParseState(private val tokens: List<MondoToken>, private val codeRange: SourceRange?) {
        private var next = 0

        fun hasTokens(): Boolean = next < tokens.size

        private fun fail(message: String): Nothing = throw CosicError(message, codeRange)

        fun parseExpr(): MondoNode {
            val token = tokens.getOrNull(next) ?: fail("unexpected end of file")
            return when (token.type) {
                "open_list" -> parseList()
                "open_angle" -> parseTyped("angle", "open_angle", "close_angle")
                "open_square" -> parseTyped("square", "open_square", "close_square")
                "open_curly" -> parseTyped("curly", "open_curly", "close_curly")
                else -> consume(token.type)
            }
        }

        private fun consume(type: String): MondoToken {
            val token = tokens[next++]
            if (token.type != type) fail("expected token type $type, got ${token.type}")
            return token
        }

        /** The children of a bracketed expression and the position from its opening to its closing bracket. */
        private fun parsePair(openType: String, closeType: String): Pair<MutableList<MondoNode>, IntArray?> {
            val begin = tokens[next].loc?.get(0)
            consume(openType)
            val children = ArrayList<MondoNode>()
            while (tokens.getOrNull(next)?.type != closeType) children.add(parseExpr())
            val end = tokens[next].loc?.get(1)
            consume(closeType)
            return children to (if (begin != null && end != null) intArrayOf(begin, end) else null)
        }

        private fun parseList(): MondoNode {
            val (children, loc) = parsePair("open_list", "close_list")
            return MondoNode.list(desugar(children, null), loc)
        }

        /** `[...]`, `<...>` and `{...}`: a call of `type` (`square`, `angle`, `curly`) whose arguments are the children. */
        private fun parseTyped(type: String, openType: String, closeType: String): MondoNode {
            val (children, loc) = parsePair(openType, closeType)
            children.add(0, MondoNode.plain(type))
            return MondoNode.list(desugar(children, type), loc)
        }
    }

    companion object {
        /** `split_children`: the children cut at every child of type [splitType]; the last chunk is what remains, which is [children] itself when there is no such child. */
        private fun splitChildren(children: MutableList<MondoNode>, splitType: String): MutableList<MutableList<MondoNode>> {
            val chunks = ArrayList<MutableList<MondoNode>>()
            var rest = children
            while (true) {
                val splitIndex = rest.indexOfFirst { it.type == splitType }
                if (splitIndex == -1) break
                chunks.add(ArrayList(rest.subList(0, splitIndex)))
                rest = ArrayList(rest.subList(splitIndex + 1, rest.size))
            }
            chunks.add(rest)
            return chunks
        }

        /**
         * `desugar_split`: cuts [children] at every `,`/`$` ([splitType] `stack`) or `|` (`or`). Without such a child
         * [next] decides what the children become; otherwise the result is the call `(<splitType> chunk...)`, in which a
         * chunk of one child stands for itself, a chunk of several is a list that [next] has desugared, and an empty chunk
         * (as in `$ s bd $ s hh`) is dropped.
         */
        private fun desugarSplit(
            children: MutableList<MondoNode>,
            splitType: String,
            next: (MutableList<MondoNode>) -> MutableList<MondoNode>,
        ): MutableList<MondoNode> {
            val chunks = splitChildren(children, splitType)
            if (chunks.size == 1) return next(children)
            val result = ArrayList<MondoNode>()
            result.add(MondoNode.plain(splitType))
            for (chunk in chunks) {
                when (chunk.size) {
                    0 -> {}
                    1 -> result.add(chunk[0])
                    else -> result.add(MondoNode.list(next(chunk)))
                }
            }
            return result
        }

        /** `unwrap_children`: the children of a list that is the only child, so that `((x y))` is `(x y)`. */
        private fun unwrapChildren(children: MutableList<MondoNode>): MutableList<MondoNode> =
            if (children.size == 1) children[0].children else children

        /**
         * `desugar_ops`: turns the infix operators of [types] into calls, the first one in the children first, so that
         * `a*2:3` is `(: 3 (* 2 a))`. An operator with nothing before it or after it, or one that follows a `#`, is a plain
         * name (`(* 2)`, `[c -]`, `x # * 2`). Two operators in a row are an error. The list given may be changed in place.
         */
        private fun desugarOps(input: MutableList<MondoNode>, types: List<String>): MutableList<MondoNode> {
            var children = input
            while (true) {
                val opIndex = children.indexOfFirst { it.type == "op" && it.value in types }
                if (opIndex == -1) break
                val token = children[opIndex]
                val op = MondoNode.plain(token.value!!)
                if (opIndex == children.size - 1 || opIndex == 0) {
                    children[opIndex] = op
                    continue
                }
                val left = children[opIndex - 1]
                val right = children[opIndex + 1]
                if (left.type == "pipe") {
                    children[opIndex] = op
                    continue
                }
                if (left.type == "op") throw CosicError("got 2 ops in a row: \"${left.value}${op.value}\"", span(left, token))
                if (right.type == "op") {
                    var message = "got 2 ops in a row: \"${op.value}${right.value}\""
                    if (op.value == "-") message += ". you probably want a rest, which is \"_\" in mondo!"
                    throw CosicError(message, span(token, right))
                }
                val call = MondoNode.list(arrayListOf(op, right, left))
                val rebuilt = ArrayList<MondoNode>(children.size - 2)
                rebuilt.addAll(children.subList(0, opIndex - 1))
                rebuilt.add(call)
                rebuilt.addAll(children.subList(opIndex + 2, children.size))
                children = unwrapChildren(rebuilt)
            }
            return children
        }

        /** From the start of [first] to the end of [last], when both are known. */
        private fun span(first: MondoNode, last: MondoNode): SourceRange? {
            val a = first.loc ?: return null
            val b = last.loc ?: return null
            return SourceRange(minOf(a[0], b[0]), maxOf(a[1], b[1]))
        }

        /** `(.fast 2)` is `(fn (_) (fast 2 _))`: [args] are the parameters, [children] the body, which is desugared first. */
        private fun getLambda(args: List<MondoNode>, children: List<MondoNode>): MutableList<MondoNode> {
            val body = desugar(ArrayList(children), null)
            return arrayListOf(
                MondoNode.plain("fn"),
                MondoNode.list(ArrayList(args)),
                if (body.size == 1) body[0] else MondoNode.list(body),
            )
        }

        /**
         * `desugar_pipes`: `a b # f c # g` is `(g (f c (a b)))`: what is before a `#` becomes the last argument of the call
         * after it. A `#` with nothing before it makes a function of one parameter, `_`, out of the whole.
         */
        private fun desugarPipes(children: MutableList<MondoNode>): MutableList<MondoNode> {
            var chunks = splitChildren(children, "pipe")
            while (chunks.size > 1) {
                val left = chunks[0]
                val right = chunks[1]
                if (left.isEmpty()) {
                    val arg = MondoNode.plain("_")
                    return getLambda(listOf(arg), listOf(arg) + children)
                }
                val call = if (left.size > 1) MondoNode.list(left) else left[0]
                val joined = ArrayList<MondoNode>(right.size + 1)
                joined.addAll(right)
                joined.add(call)
                val remaining = ArrayList<MutableList<MondoNode>>()
                remaining.add(joined)
                remaining.addAll(chunks.subList(2, chunks.size))
                chunks = remaining
            }
            return chunks[0]
        }

        /**
         * `desugar`: the children of a list with the sugar removed. [type] is `square`, `angle` or `curly` for a bracketed
         * list, whose first child is that name: it is taken off while `,` and `|` split the children and put back in front
         * of every part that is more than one child, so `[bd, hh oh]` is `(stack bd (square hh oh))`.
         */
        private fun desugar(children0: MutableList<MondoNode>, type: String?): MutableList<MondoNode> {
            val children = if (type != null) ArrayList(children0.subList(1, children0.size)) else children0
            return desugarSplit(children, "stack") { byStack ->
                desugarSplit(byStack, "or") { byOr ->
                    var part = byOr
                    // the type that was taken off before splitting is put back
                    if (type != null) {
                        part = ArrayList<MondoNode>(part.size + 1).also { it.add(MondoNode.plain(type)); it.addAll(byOr) }
                    }
                    for (ops in OP_PRECEDENCE) part = desugarOps(part, ops)
                    desugarPipes(part)
                }
            }
        }
    }
}

/**
 * The tree as text: a list is its children in round brackets, a leaf is its text. [compact] writes it on one line;
 * otherwise a list inside another begins on a line of its own, indented one space per level.
 */
internal fun printAst(ast: MondoNode, compact: Boolean = false, lvl: Int = 0): String {
    val br = if (compact) "" else "\n"
    val spaces = if (compact) "" else " ".repeat(lvl)
    if (ast.type != "list") return ast.value ?: "undefined"
    val inner = ast.children.joinToString(" ") { printAst(it, compact, lvl + 1) }
    val close = if (ast.children.any { it.type == "list" }) "$br$spaces)" else ")"
    return "${if (lvl != 0) br else ""}$spaces($inner$close"
}
