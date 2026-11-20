package cosic.script.tidal

/*
 * The parser of the Haskell that `tidal` accepts. Strudel hands the code to tree-sitter-haskell 0.23.1 and runs the tree it
 * gets (hs2js, packages/hs2js/src/hs2js.mjs), so what a program means is a matter of the shape of that tree. This parser
 * builds the same tree for what the evaluator can use, node for node with the type names of the grammar, and a tree of the
 * right kind for what it cannot (so that the evaluator fails with `unhandled branch type tuple` and the like, as in Strudel).
 *
 * What matters about the grammar (grammar/exp.js, precedences.js, scanner.c):
 *
 *  - Every infix operator has the same precedence and groups to the right: `fast 2 . rev $ s "bd"` is
 *    `(fast 2) . (rev $ (s "bd"))`. Application binds tighter than negation, which binds tighter than infix; negation can
 *    only start an operand (after an operand a `-` is an operator).
 *  - A left section `(a # b #)` has the whole chain before its last operator as operand; a right section `(# a # b)` the
 *    whole chain after its operator; `(+)` is a `prefix_id`; `(- 1)` is a negation inside parentheses.
 *  - A new top level declaration starts at a token with indentation 0 after a line break; any larger indentation continues the
 *    declaration, wherever the first token of the program stood. Brackets suspend the rule.
 *  - Almost all words that are keywords somewhere are plain variables in an operand (`x $ where` is a variable); `if`,
 *    `let`, `case`, `do` and `mdo` start an expression, and `where`, `in`, `of`, `then` and `else` end one.
 *
 * Code the parser does not understand does not fail the program as a whole: it becomes an ERROR node of the declaration it
 * is in, which fails when it is evaluated (so the declarations before it have run, as in Strudel). An unclosed bracket or
 * string makes the whole program an ERROR. Comments are extras: they are children of the innermost node they are in.
 */

/**
 * A node of the syntax tree, as web-tree-sitter's SyntaxNode: a [type], a range of the (preprocessed) program and the
 * [children], which include the anonymous tokens (`(`, `=`, `where`, ...) whose type is their text.
 */
internal class HsNode(
    @JvmField val type: String,
    @JvmField val start: Int,
    @JvmField val end: Int,
    @JvmField val children: MutableList<HsNode>,
    private val source: String,
) {
    val text: String get() = source.substring(start, end)

    override fun toString(): String = "$type[$start,$end]"
}

/**
 * The preprocessing hs2js does before it parses (parser.mjs): a single line break becomes a space and a blank line becomes a
 * space and a line break, so only blank lines are left, and they separate the declarations. It keeps every index.
 */
internal fun preprocessHs(code: String): String =
    code.replace("\n\n", "~~~~").replace("\n", " ").replace("~~~~", " \n")

/** Parses [code], which [preprocessHs] has been applied to, into the tree of the grammar. */
internal fun parseHs(code: String): HsNode = HsParser(code, HsLexer(code).lex()).parse()

private class HsSyntaxError(val at: HsToken) : Exception(null, null, false, false)

/** The words that begin a declaration of their own kind (`data X = ...`), with the node type the grammar makes of it. */
private val DECLARATION_KEYWORDS = mapOf(
    "data" to "data_type",
    "newtype" to "newtype",
    "type" to "type_synomym",
    "class" to "class",
    "instance" to "instance",
    "infix" to "fixity",
    "infixl" to "fixity",
    "infixr" to "fixity",
    "default" to "default_types",
    "deriving" to "deriving_declaration",
)

/** The words that end an expression instead of being a variable in it. */
private val EXPRESSION_ENDS = setOf("where", "in", "then", "else", "of")

private val OPENERS = setOf(HsTokenKind.LPAREN, HsTokenKind.LBRACKET, HsTokenKind.LBRACE)
private val CLOSERS = setOf(HsTokenKind.RPAREN, HsTokenKind.RBRACKET, HsTokenKind.RBRACE)

private val GREEDY = setOf("lambda", "lambda_case", "let_in", "conditional", "case", "do")

internal class HsParser(private val src: String, lexed: HsLexed) {
    private val toks: List<HsToken> = lexed.tokens
    private val comments: List<HsToken> = lexed.comments
    private val eof: HsToken = toks.last()
    private var pos = 0
    private var limit = toks.size - 1
    private var depth = 0
    private var stmtStart = 0
    private var patternMode = false

    // ---- the tokens ----------------------------------------------------------------------------------------------------

    /**
     * The token at [index], or the end of the program when there is none or the layout rule ends the declaration there: a
     * token with indentation 0 after a line break, outside of brackets and not the first of the declaration.
     */
    private fun tokenAt(index: Int): HsToken {
        if (index >= limit) return eof
        val t = toks[index]
        if (t.kind == HsTokenKind.EOF) return t
        if (depth == 0 && index != stmtStart && t.indent == 0) return eof
        return t
    }

    private fun cur(): HsToken = tokenAt(pos)

    private fun advance(): HsToken {
        val t = cur()
        if (t.kind == HsTokenKind.EOF) throw HsSyntaxError(t)
        pos++
        return t
    }

    private fun fail(at: HsToken = cur()): Nothing = throw HsSyntaxError(at)

    private fun expect(kind: HsTokenKind): HsToken {
        if (cur().kind != kind) fail()
        return advance()
    }

    private fun isWord(t: HsToken, word: String): Boolean = t.kind == HsTokenKind.VARID && t.text == word

    // ---- making nodes --------------------------------------------------------------------------------------------------

    private fun leaf(type: String, t: HsToken) = HsNode(type, t.start, t.end, ArrayList(0), src)

    private fun anon(t: HsToken) = leaf(t.text, t)

    private fun node(type: String, vararg kids: HsNode): HsNode = HsNode(type, kids.first().start, kids.last().end, kids.toMutableList(), src)

    private fun node(type: String, kids: List<HsNode>): HsNode = HsNode(type, kids.first().start, kids.last().end, kids.toMutableList(), src)

    /** The tokens from [from] up to the current position as one node with one child that stands for them. */
    private fun opaque(type: String, from: Int): HsNode? {
        if (pos <= from) return null
        val start = toks[from].start
        val end = toks[pos - 1].end
        return HsNode(type, start, end, mutableListOf(HsNode("opaque", start, end, ArrayList(0), src)), src)
    }

    private fun errorNode(from: Int, to: Int) = HsNode("ERROR", from, to, ArrayList(0), src)

    // ---- the program ---------------------------------------------------------------------------------------------------

    fun parse(): HsNode {
        val root = ArrayList<HsNode>()
        val real = toks.size - 1
        if (real > 0) {
            if (unclosedBracket() || toks.any { it.kind == HsTokenKind.ERROR }) {
                root.add(errorNode(toks.first().start, toks[real - 1].end))
            } else {
                parseDeclarations(root)
            }
        }
        val tree = HsNode("haskell", if (root.isEmpty()) 0 else root.first().start, if (root.isEmpty()) src.length else root.last().end, root, src)
        for (c in comments) insertComment(tree, HsNode(commentType(c), c.start, c.end, ArrayList(0), src))
        return tree
    }

    private fun commentType(c: HsToken): String = when (c.kind) {
        HsTokenKind.HADDOCK -> "haddock"
        HsTokenKind.PRAGMA -> "pragma"
        else -> "comment"
    }

    /** Whether a bracket stays open at the end of the program. */
    private fun unclosedBracket(): Boolean {
        val stack = ArrayList<HsTokenKind>()
        for (t in toks) {
            if (t.kind in OPENERS) stack.add(t.kind) else if (t.kind in CLOSERS && stack.isNotEmpty()) stack.removeAt(stack.size - 1)
        }
        return stack.isNotEmpty()
    }

    private fun parseDeclarations(root: MutableList<HsNode>) {
        val declarations = ArrayList<HsNode>()
        val imports = ArrayList<HsNode>()
        while (toks[pos].kind != HsTokenKind.EOF) {
            val t = toks[pos]
            if (t.kind == HsTokenKind.SEMI) {
                declarations.add(anon(t))
                pos++
                continue
            }
            stmtStart = pos
            depth = 0
            patternMode = false
            limit = toks.size - 1
            try {
                val decl = parseStatement()
                if (decl.type == "import" && declarations.isEmpty()) imports.add(decl) else declarations.add(decl)
                val rest = cur()
                if (rest.kind != HsTokenKind.EOF && rest.kind != HsTokenKind.SEMI) {
                    // something is left over that no declaration can use, such as a closing bracket
                    declarations.add(errorNode(rest.start, rest.end))
                    skipDeclaration()
                }
            } catch (e: HsSyntaxError) {
                declarations.add(errorFor(e.at))
                skipDeclaration()
            }
        }
        if (imports.isNotEmpty()) root.add(HsNode("imports", imports.first().start, imports.last().end, imports, src))
        if (declarations.isNotEmpty()) root.add(HsNode("declarations", declarations.first().start, declarations.last().end, declarations, src))
    }

    /** The ERROR of a failed declaration: the token it failed at, or the whole declaration when it ended too early. */
    private fun errorFor(at: HsToken): HsNode {
        if (at.kind == HsTokenKind.EOF) {
            val first = toks[stmtStart]
            val last = toks[(pos - 1).coerceIn(stmtStart, toks.size - 1)]
            return errorNode(first.start, maxOf(last.end, first.end))
        }
        return errorNode(at.start, at.end)
    }

    /** Skips to the start of the next declaration after an error (at least the token the declaration started with). */
    private fun skipDeclaration() {
        if (pos == stmtStart) pos++
        var rel = 0
        while (toks[pos].kind != HsTokenKind.EOF) {
            val t = toks[pos]
            if (rel <= 0 && t.indent == 0) break
            if (rel <= 0 && t.kind == HsTokenKind.SEMI) break
            if (t.kind in OPENERS) rel++ else if (t.kind in CLOSERS) rel--
            pos++
        }
    }

    // ---- comments ------------------------------------------------------------------------------------------------------

    /** A comment is a child of the innermost node that has tokens on both sides of it; before the first and after the last it belongs to the root. */
    private fun insertComment(root: HsNode, comment: HsNode) {
        var target = root
        while (true) {
            val inner = target.children.firstOrNull {
                it.children.isNotEmpty() && it.start <= comment.start && comment.end <= it.end
            } ?: break
            target = inner
        }
        var index = target.children.indexOfFirst { it.start > comment.start }
        if (index < 0) index = target.children.size
        target.children.add(index, comment)
    }

    // ---- declarations --------------------------------------------------------------------------------------------------

    private fun parseStatement(): HsNode {
        val first = cur()
        if (first.kind == HsTokenKind.VARID) {
            val next = tokenAt(pos + 1)
            val standsAlone = next.kind == HsTokenKind.EQUALS || next.kind == HsTokenKind.COLON2
            if (first.text == "import" && !standsAlone) return keywordDeclaration("import", first)
            if (first.text == "module" && !standsAlone) return keywordDeclaration("header", first)
            if (first.text == "foreign" && !standsAlone) {
                val kind = if (isWord(next, "export")) "foreign_export" else "foreign_import"
                return keywordDeclaration(kind, first)
            }
            val keywordType = DECLARATION_KEYWORDS[first.text]
            if (keywordType != null && !standsAlone) return keywordDeclaration(keywordType, first)
        }
        val marker = findMarker()
        if (marker >= 0) {
            return when (toks[marker].kind) {
                HsTokenKind.COLON2 -> parseSignature(marker)
                else -> parseBinding(marker)
            }
        }
        return node("top_splice", parseExp())
    }

    private fun keywordDeclaration(type: String, keyword: HsToken): HsNode {
        advance()
        val from = pos
        consumeOpaque()
        val rest = opaque("rest", from)
        return if (rest == null) node(type, anon(keyword)) else node(type, anon(keyword), rest)
    }

    /**
     * The index of the `::`, `=` or `|` that starts what follows the head of a declaration, or -1 when the statement is an
     * expression. A keyword that opens a block of its own ends the search: what comes after it belongs to the block.
     */
    private fun findMarker(): Int {
        var rel = 0
        var index = pos
        while (index < limit) {
            val t = toks[index]
            if (t.kind == HsTokenKind.EOF) return -1
            if (index != stmtStart && rel == 0 && t.indent == 0 && depth == 0) return -1
            if (t.kind in OPENERS) rel++
            else if (t.kind in CLOSERS) {
                rel--
                if (rel < 0) return -1
            } else if (rel == 0) {
                when (t.kind) {
                    HsTokenKind.COLON2, HsTokenKind.EQUALS, HsTokenKind.BAR -> return index
                    HsTokenKind.SEMI, HsTokenKind.BACKSLASH -> return -1
                    HsTokenKind.VARID -> if (t.text in BLOCK_WORDS) return -1
                    else -> {}
                }
            }
            index++
        }
        return -1
    }

    private fun parseSignature(marker: Int): HsNode {
        val names = ArrayList<HsNode>()
        while (pos < marker) {
            val t = cur()
            when {
                t.kind == HsTokenKind.VARID && names.size % 2 == 0 -> names.add(leaf("variable", advance()))
                t.kind == HsTokenKind.COMMA && names.size % 2 == 1 -> names.add(anon(advance()))
                else -> fail()
            }
        }
        if (names.isEmpty() || names.size % 2 == 0) fail()
        val head = if (names.size == 1) names[0] else node("binding_list", names)
        val colons = advance()
        val from = pos
        consumeOpaque()
        val type = opaque("type", from) ?: fail()
        return node("signature", head, anon(colons), type)
    }

    /** A `bind` or a `function`: a head, one or more matches and an optional `where`. */
    private fun parseBinding(marker: Int): HsNode {
        val savedLimit = limit
        limit = marker
        patternMode = true
        val head = try {
            parseExp()
        } finally {
            patternMode = false
        }
        if (pos != marker) fail(tokenAt(pos))
        limit = savedLimit
        val matches = parseMatches()
        val where = parseWhere()
        val headNodes = ArrayList<HsNode>()
        val type: String
        when {
            head.type == "variable" -> {
                type = "bind"
                headNodes.add(head)
            }
            head.type == "apply" && applyHead(head).type == "variable" -> {
                type = "function"
                val arguments = ArrayList<HsNode>()
                val name = flattenApply(head, arguments)
                headNodes.add(name)
                headNodes.add(node("patterns", arguments))
            }
            head.type == "infix" && head.children[1].type == "operator" -> {
                type = "function"
                headNodes.add(head)
            }
            else -> {
                type = "bind"
                headNodes.add(head)
            }
        }
        return node(type, headNodes + matches + where)
    }

    private fun applyHead(n: HsNode): HsNode = if (n.type == "apply") applyHead(n.children[0]) else n

    private fun flattenApply(n: HsNode, into: MutableList<HsNode>): HsNode {
        if (n.type != "apply") return n
        val name = flattenApply(n.children[0], into)
        into.add(n.children[1])
        return name
    }

    private fun parseMatches(): List<HsNode> {
        val matches = ArrayList<HsNode>()
        if (cur().kind == HsTokenKind.EQUALS) {
            val eq = advance()
            matches.add(node("match", anon(eq), parseExp()))
            return matches
        }
        while (cur().kind == HsTokenKind.BAR) {
            val bar = advance()
            val from = pos
            consumeGuard()
            val guards = opaque("guards", from) ?: fail()
            val eq = expect(HsTokenKind.EQUALS)
            matches.add(node("match", anon(bar), guards, anon(eq), parseExp()))
        }
        if (matches.isEmpty()) fail()
        return matches
    }

    /** The condition of a guard: everything up to the `=` that ends it. */
    private fun consumeGuard() {
        var rel = 0
        while (true) {
            val t = cur()
            if (t.kind == HsTokenKind.EOF) return
            if (t.kind in OPENERS) rel++ else if (t.kind in CLOSERS) {
                rel--
                if (rel < 0) return
            } else if (rel == 0 && t.kind == HsTokenKind.EQUALS) return
            pos++
        }
    }

    private fun parseWhere(): List<HsNode> {
        val t = cur()
        if (!isWord(t, "where")) return emptyList()
        advance()
        val from = pos
        consumeOpaque()
        val binds = opaque("local_binds", from)
        return if (binds == null) listOf(anon(t)) else listOf(anon(t), binds)
    }

    /** Takes the tokens of a block whose structure is of no use to the evaluator, up to the end of the construct that holds it. */
    private fun consumeOpaque() {
        var rel = 0
        while (true) {
            val t = cur()
            if (t.kind == HsTokenKind.EOF) return
            if (t.kind in OPENERS) rel++ else if (t.kind in CLOSERS) {
                if (rel == 0) return
                rel--
            } else if (rel == 0 && t.kind == HsTokenKind.COMMA && depth > 0) return
            pos++
        }
    }

    // ---- expressions ---------------------------------------------------------------------------------------------------

    private fun isOperator(t: HsToken): Boolean = t.kind == HsTokenKind.VARSYM || t.kind == HsTokenKind.CONSYM || t.kind == HsTokenKind.BACKTICK

    /** Whether the operator the parser is at is the last thing before a closing parenthesis, which makes it the operator of a left section. */
    private fun operatorEndsSection(): Boolean {
        val t = cur()
        return when (t.kind) {
            HsTokenKind.BACKTICK -> tokenAt(pos + 3).kind == HsTokenKind.RPAREN
            else -> tokenAt(pos + 1).kind == HsTokenKind.RPAREN
        }
    }

    private fun parseOperator(): HsNode {
        val t = advance()
        return when (t.kind) {
            HsTokenKind.VARSYM -> leaf("operator", t)
            HsTokenKind.CONSYM -> leaf("constructor_operator", t)
            HsTokenKind.BACKTICK -> {
                val name = advance()
                val nameNode = when (name.kind) {
                    HsTokenKind.VARID -> leaf("variable", name)
                    HsTokenKind.CONID -> leaf("constructor", name)
                    HsTokenKind.QUALIFIED -> qualifiedNode(name)
                    else -> fail(name)
                }
                val close = expect(HsTokenKind.BACKTICK)
                node("infix_id", anon(t), nameNode, anon(close))
            }
            else -> fail(t)
        }
    }

    /** `_exp`: operands joined by operators, all of one precedence and grouping to the right. */
    private fun parseExp(): HsNode {
        val left = parseOperand()
        val t = cur()
        if (isOperator(t) && !operatorEndsSection()) {
            val op = parseOperator()
            val right = parseExp()
            return node("infix", left, op, right)
        }
        return left
    }

    private fun isMinus(t: HsToken) = t.kind == HsTokenKind.VARSYM && t.text == "-"

    private fun parseOperand(): HsNode {
        val t = cur()
        if (isMinus(t)) {
            val minus = advance()
            return node("negation", anon(minus), parseOperand())
        }
        return parseApplication()
    }

    private fun parseApplication(): HsNode {
        var head = parseArgument()
        if (head.type in GREEDY) return head
        while (canStartArgument(cur())) {
            val arg = parseArgument()
            head = node("apply", head, arg)
            if (arg.type in GREEDY) break
        }
        return head
    }

    private fun canStartArgument(t: HsToken): Boolean = when (t.kind) {
        HsTokenKind.VARID -> t.text !in EXPRESSION_ENDS
        HsTokenKind.CONID, HsTokenKind.QUALIFIED, HsTokenKind.INTEGER, HsTokenKind.FLOAT, HsTokenKind.STRING, HsTokenKind.CHAR,
        HsTokenKind.LABEL, HsTokenKind.IMPLICIT_VARIABLE, HsTokenKind.SPLICE, HsTokenKind.LPAREN, HsTokenKind.LBRACKET,
        HsTokenKind.BACKSLASH,
        -> true
        else -> false
    }

    /** An argument of an application: an atom, or a block that takes everything up to the end (`\x -> ...`, `if`, `let`, `case`, `do`). */
    private fun parseArgument(): HsNode {
        val t = cur()
        when {
            t.kind == HsTokenKind.BACKSLASH -> return parseLambda()
            isWord(t, "if") -> return parseConditional()
            isWord(t, "let") -> return parseLet()
            isWord(t, "case") -> return parseCase()
            isWord(t, "do") || isWord(t, "mdo") -> return parseDo()
        }
        return parseAtom()
    }

    private fun parseAtom(): HsNode {
        val t = cur()
        var e: HsNode = when (t.kind) {
            HsTokenKind.VARID -> {
                advance()
                if (patternMode && t.text == "_") node("wildcard", anon(t)) else leaf("variable", t)
            }
            HsTokenKind.CONID -> leaf("constructor", advance())
            HsTokenKind.QUALIFIED -> qualifiedNode(advance())
            HsTokenKind.INTEGER -> node("literal", leaf("integer", advance()))
            HsTokenKind.FLOAT -> node("literal", leaf("float", advance()))
            HsTokenKind.STRING -> node("literal", leaf("string", advance()))
            HsTokenKind.CHAR -> node("literal", leaf("char", advance()))
            HsTokenKind.LABEL -> leaf("label", advance())
            HsTokenKind.IMPLICIT_VARIABLE -> leaf("implicit_variable", advance())
            HsTokenKind.SPLICE -> parseSplice()
            HsTokenKind.LPAREN -> parseParen()
            HsTokenKind.LBRACKET -> parseBracket()
            else -> fail()
        }
        while (cur().kind == HsTokenKind.TIGHT_DOT) {
            val dot = advance()
            val field = cur()
            if (field.kind != HsTokenKind.VARID) fail()
            advance()
            e = node("projection", e, anon(dot), node("field_name", leaf("variable", field)))
        }
        return e
    }

    /** `Foo.bar` or `Foo.Bar.baz`: a module path and a name. */
    private fun qualifiedNode(t: HsToken): HsNode {
        val parts = t.text.split('.')
        var offset = t.start
        val module = ArrayList<HsNode>()
        for (i in 0 until parts.size - 1) {
            module.add(HsNode("module_id", offset, offset + parts[i].length, ArrayList(0), src))
            offset += parts[i].length
            module.add(HsNode(".", offset, offset + 1, ArrayList(0), src))
            offset++
        }
        val last = parts.last()
        val name = HsNode(if (last.isNotEmpty() && last[0].isUpperCase()) "constructor" else "variable", offset, t.end, ArrayList(0), src)
        return node("qualified", HsNode("module", t.start, offset, module, src), name)
    }

    private fun parseSplice(): HsNode {
        val dollar = advance()
        val t = cur()
        val operand = when (t.kind) {
            HsTokenKind.VARID -> leaf("variable", advance())
            HsTokenKind.LPAREN -> parseParen()
            else -> fail()
        }
        return node("splice", anon(dollar), operand)
    }

    // ---- parentheses, tuples, sections, lists --------------------------------------------------------------------------

    private fun closeParen(): HsToken {
        val close = expect(HsTokenKind.RPAREN)
        depth--
        return close
    }

    private fun parseParen(): HsNode {
        val open = advance()
        depth++
        val t = cur()
        if (t.kind == HsTokenKind.RPAREN) return node("unit", anon(open), anon(closeParen()))
        if (t.kind == HsTokenKind.COMMA) return parseTuple(open, null)
        if (t.kind == HsTokenKind.TIGHT_DOT) return parseProjectionSelector(open)
        val startsWithOperator = isOperator(t) && !(isMinus(t) && !operatorEndsSection())
        if (startsWithOperator) {
            val op = parseOperator()
            if (cur().kind == HsTokenKind.RPAREN) return node("prefix_id", anon(open), op, anon(closeParen()))
            val operand = parseExp()
            return node("right_section", anon(open), op, operand, anon(closeParen()))
        }
        val e = parseExp()
        val next = cur()
        return when {
            next.kind == HsTokenKind.RPAREN -> node("parens", anon(open), e, anon(closeParen()))
            next.kind == HsTokenKind.COMMA -> parseTuple(open, e)
            isOperator(next) -> {
                val op = parseOperator()
                node("left_section", anon(open), e, op, anon(closeParen()))
            }
            else -> fail()
        }
    }

    /** `(a, b)`, `(a,)`, `(,a)` and `(,)`: the members and the commas between them, tuple sections included. */
    private fun parseTuple(open: HsToken, first: HsNode?): HsNode {
        val kids = ArrayList<HsNode>()
        kids.add(anon(open))
        if (first != null) kids.add(first)
        var onlyCommas = first == null
        while (cur().kind == HsTokenKind.COMMA) {
            kids.add(anon(advance()))
            val t = cur()
            if (t.kind != HsTokenKind.COMMA && t.kind != HsTokenKind.RPAREN) {
                kids.add(parseExp())
                onlyCommas = false
            }
        }
        kids.add(anon(closeParen()))
        return node(if (onlyCommas) "prefix_tuple" else "tuple", kids)
    }

    /** `(.field.other)`. */
    private fun parseProjectionSelector(open: HsToken): HsNode {
        val kids = ArrayList<HsNode>()
        kids.add(anon(open))
        while (cur().kind == HsTokenKind.TIGHT_DOT) {
            kids.add(anon(advance()))
            val field = cur()
            if (field.kind != HsTokenKind.VARID) fail()
            advance()
            kids.add(node("field_name", leaf("variable", field)))
        }
        kids.add(anon(closeParen()))
        return node("projection_selector", kids)
    }

    private fun closeBracket(): HsToken {
        val close = expect(HsTokenKind.RBRACKET)
        depth--
        return close
    }

    private fun parseBracket(): HsNode {
        val open = advance()
        depth++
        if (cur().kind == HsTokenKind.RBRACKET) return node("list", anon(open), anon(closeBracket()))
        val kids = ArrayList<HsNode>()
        kids.add(anon(open))
        kids.add(parseExp())
        if (cur().kind == HsTokenKind.BAR) {
            val from = pos
            consumeOpaque()
            kids.add(opaque("qualifiers", from) ?: fail())
            kids.add(anon(closeBracket()))
            return node("list_comprehension", kids)
        }
        var sequence = false
        while (true) {
            val t = cur()
            if (t.kind == HsTokenKind.DOTDOT && kids.size <= 4) {
                kids.add(anon(advance()))
                sequence = true
                if (cur().kind != HsTokenKind.RBRACKET) kids.add(parseExp())
                break
            }
            if (t.kind != HsTokenKind.COMMA) break
            kids.add(anon(advance()))
            if (cur().kind == HsTokenKind.RBRACKET) break
            kids.add(parseExp())
        }
        kids.add(anon(closeBracket()))
        return node(if (sequence) "arithmetic_sequence" else "list", kids)
    }

    // ---- blocks that take everything to their right --------------------------------------------------------------------

    private fun parseLambda(): HsNode {
        val slash = advance()
        if (isWord(cur(), "case")) {
            val kids = ArrayList<HsNode>()
            kids.add(anon(slash))
            kids.add(anon(advance()))
            val from = pos
            consumeOpaque()
            opaque("alternatives", from)?.let { kids.add(it) }
            return node("lambda_case", kids)
        }
        val patterns = ArrayList<HsNode>()
        patternMode = true
        try {
            while (cur().kind != HsTokenKind.RARROW) {
                if (!canStartArgument(cur()) || cur().kind == HsTokenKind.BACKSLASH) fail()
                patterns.add(parseAtom())
            }
        } finally {
            patternMode = false
        }
        if (patterns.isEmpty()) fail()
        val arrow = advance()
        val body = parseExp()
        return node("lambda", anon(slash), node("patterns", patterns), anon(arrow), body)
    }

    private fun parseConditional(): HsNode {
        val kids = ArrayList<HsNode>()
        kids.add(anon(advance()))
        kids.add(parseExp())
        for (word in listOf("then", "else")) {
            while (cur().kind == HsTokenKind.SEMI) kids.add(anon(advance()))
            if (!isWord(cur(), word)) fail()
            kids.add(anon(advance()))
            kids.add(parseExp())
        }
        return node("conditional", kids)
    }

    private fun parseLet(): HsNode {
        val kids = ArrayList<HsNode>()
        kids.add(anon(advance()))
        val from = pos
        var nesting = 1
        while (true) {
            val t = cur()
            if (t.kind == HsTokenKind.EOF) fail()
            if (isWord(t, "let")) nesting++
            if (isWord(t, "in")) {
                nesting--
                if (nesting == 0) break
            }
            pos++
        }
        kids.add(opaque("local_binds", from) ?: fail())
        kids.add(anon(advance()))
        kids.add(parseExp())
        return node("let_in", kids)
    }

    private fun parseCase(): HsNode {
        val kids = ArrayList<HsNode>()
        kids.add(anon(advance()))
        kids.add(parseExp())
        if (!isWord(cur(), "of")) fail()
        kids.add(anon(advance()))
        val from = pos
        consumeOpaque()
        opaque("alternatives", from)?.let { kids.add(it) }
        return node("case", kids)
    }

    private fun parseDo(): HsNode {
        val keyword = advance()
        val from = pos
        consumeOpaque()
        val statements = opaque("statements", from)
        return if (statements == null) node("do", anon(keyword)) else node("do", anon(keyword), statements)
    }
}

/** Words after which the `=` that follows belongs to a block of the construct rather than to the declaration. */
private val BLOCK_WORDS = setOf("let", "where", "of", "do", "mdo", "case", "if", "then", "else", "in")
