package cosic.script.next

import cosic.SourceRange
import cosic.pattern.CosicError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the parser promises to the transpiler and the interpreter, one fact per test. The breadth of the grammar and of
 * the errors is covered by [ParserOracleTest], which compares with acorn on 880 snippets, and by [CorpusParseTest].
 */
class ParserTest {
    private fun parse(code: String): Program = parseProgram(code).program

    private fun statement(code: String): Node = parse(code).body.single()

    private fun expression(code: String): Node = (statement(code) as ExpressionStatement).expression

    private fun failure(code: String): CosicError = assertFailsWith<CosicError>("parsing '$code'") { parseProgram(code) }

    private fun assertError(code: String, message: String, start: Int, end: Int? = null) {
        val e = failure(code)
        assertEquals(message, e.message, "message for '$code'")
        assertEquals(start, e.range?.start, "start for '$code'")
        if (end != null) assertEquals(end, e.range?.end, "end for '$code'")
    }

    /** A compact prefix notation, to assert the shape of an expression in one line. */
    private fun s(node: Node?): String = when (node) {
        null -> "_"
        is Program -> node.body.joinToString(" ") { s(it) }
        is ExpressionStatement -> s(node.expression)
        is Identifier -> node.name
        is Literal -> node.raw
        is ThisExpression -> "this"
        is BinaryExpression -> "(${node.operator.symbol} ${s(node.left)} ${s(node.right)})"
        is LogicalExpression -> "(${node.operator.symbol} ${s(node.left)} ${s(node.right)})"
        is UnaryExpression -> "(${node.operator.symbol} ${s(node.argument)})"
        is UpdateExpression -> "(${if (node.prefix) "pre" else "post"}${node.operator.symbol} ${s(node.argument)})"
        is AssignmentExpression -> "(${node.operator.symbol} ${s(node.left)} ${s(node.right)})"
        is ConditionalExpression -> "(? ${s(node.test)} ${s(node.consequent)} ${s(node.alternate)})"
        is CallExpression -> "(call${if (node.optional) "?" else ""} ${s(node.callee)}${node.arguments.joinToString("") { " " + s(it) }})"
        is NewExpression -> "(new ${s(node.callee)}${node.arguments.joinToString("") { " " + s(it) }})"
        is MemberExpression -> "(${if (node.computed) "[]" else "."}${if (node.optional) "?" else ""} ${s(node.obj)} ${s(node.property)})"
        is ChainExpression -> "(chain ${s(node.expression)})"
        is SequenceExpression -> "(, ${node.expressions.joinToString(" ") { s(it) }})"
        is SpreadElement -> "...${s(node.argument)}"
        is AwaitExpression -> "(await ${s(node.argument)})"
        is ArrayExpression -> "[${node.elements.joinToString(" ") { s(it) }}]"
        is ObjectExpression -> "{${node.properties.joinToString(" ") { s(it) }}}"
        is Property -> "${s(node.key)}:${s(node.value)}"
        is ArrowFunctionExpression -> "(${if (node.async) "async " else ""}=> (${node.params.joinToString(" ") { s(it) }}) ${s(node.body)})"
        is AssignmentPattern -> "(= ${s(node.left)} ${s(node.right)})"
        is RestElement -> "...${s(node.argument)}"
        is BlockStatement -> "{${node.body.joinToString(" ") { s(it) }}}"
        is ReturnStatement -> "(return ${s(node.argument)})"
        is LabeledStatement -> "(label ${node.label.name} ${s(node.body)})"
        else -> node.type
    }

    private fun shape(code: String): String = s(parse(code))

    // ---- the tree and its ranges ---------------------------------------------------------------------------------

    @Test
    fun theProgramSpansTheWholeCodeAndKeepsItsStatementsInOrder() {
        for (code in listOf("", "  ", "a", "  a ; b  \n", "// c\n")) {
            assertEquals(SourceRange(0, code.length), parse(code).range, "program range of '$code'")
        }
        assertEquals(listOf("ExpressionStatement", "VariableDeclaration"), parse("a\nconst b = 1").body.map { it.type })
    }

    @Test
    fun aProgramLineLabelIsALabeledStatementOverAnExpressionStatement() {
        val labeled = assertIs<LabeledStatement>(statement("\$: s(\"bd\")"))
        assertEquals("$", labeled.label.name)
        assertEquals(SourceRange(0, 1), labeled.label.range)
        val body = assertIs<ExpressionStatement>(labeled.body)
        assertEquals(SourceRange(3, 10), body.range)
        assertEquals(SourceRange(0, 10), labeled.range)
        for (name in listOf("bass", "_bass", "bass_", "Bass", "_\$", "\$\$")) {
            assertEquals(name, assertIs<LabeledStatement>(statement("$name: a")).label.name)
        }
    }

    @Test
    fun aLabelIsRecognisedOnlyAtTheStartOfAStatement() {
        assertEquals("(? a b c)", shape("a ? b : c"))
        assertEquals("{a:1}", s((expression("x = { a: 1 }") as AssignmentExpression).right))
        assertEquals("(label a (label b c))", shape("a: b: c"))
        assertEquals("(label a {b})", shape("a: { b }"))
        assertEquals("a (label b c)", shape("a; b: c"))
    }

    @Test
    fun aDoubleQuotedStringIsALiteralWhoseRawTextStartsWithTheQuote() {
        val literal = assertIs<Literal>(expression("  \"bd sd\""))
        assertEquals(SourceRange(2, 9), literal.range)
        assertEquals("\"bd sd\"", literal.raw)
        assertEquals("bd sd", literal.value)
        val single = assertIs<Literal>(expression("'bd sd'"))
        assertEquals('\'', single.raw[0])
        assertEquals("bd sd", single.value)
        val escaped = assertIs<Literal>(expression("\"a\\nb\""))
        assertEquals("a\nb", escaped.value)
        assertEquals("\"a\\nb\"", escaped.raw)
    }

    @Test
    fun literalsCarryTheirJavaScriptValue() {
        assertEquals(0.5, assertIs<Literal>(expression(".5")).value)
        assertEquals(16.0, assertIs<Literal>(expression("0x10")).value)
        assertEquals(1000.0, assertIs<Literal>(expression("1_000")).value)
        assertEquals(true, assertIs<Literal>(expression("true")).value)
        assertEquals(false, assertIs<Literal>(expression("false")).value)
        assertNull(assertIs<Literal>(expression("null")).value)
        assertEquals("null", assertIs<Literal>(expression("null")).raw)
        assertEquals("true", assertIs<Literal>(expression("true")).raw)
    }

    @Test
    fun aTemplateKeepsItsPiecesWithTheirRangesCookedAndRaw() {
        val code = "`a\${b}\\n\r\nc`"
        val template = assertIs<TemplateLiteral>(expression(code))
        assertEquals(SourceRange(0, code.length), template.range)
        assertEquals(2, template.quasis.size)
        assertEquals(1, template.expressions.size)
        val (first, second) = template.quasis
        assertEquals(SourceRange(1, 2), first.range)
        assertEquals("a", first.cooked)
        assertEquals("a", first.raw)
        assertFalse(first.tail)
        assertEquals("\n\nc", second.cooked)
        assertEquals("\\n\nc", second.raw) // the raw text keeps the escape and has its line break normalised
        assertTrue(second.tail)
        assertEquals(SourceRange(4, 5), template.expressions.single().range)
    }

    @Test
    fun aTaggedTemplateWithAnInvalidEscapeHasNoCookedValue() {
        val tagged = assertIs<TaggedTemplateExpression>(expression("a`\\xZZ`"))
        assertNull(tagged.quasi.quasis.single().cooked)
        assertEquals("\\xZZ", tagged.quasi.quasis.single().raw)
        assertEquals("a", s(tagged.tag))
        assertError("`\\xZZ`", "Bad escape sequence in untagged template literal", 1)
    }

    @Test
    fun commentsComeBackWithTheirTextAndOffsets() {
        val code = "// mini-off\n\"a b\" /* x */\n// mini-on\n"
        val result = parseProgram(code)
        assertEquals(listOf(false to " mini-off", true to " x ", false to " mini-on"), result.comments.map { it.block to it.value })
        assertEquals(listOf(0, 18, 26), result.comments.map { it.start })
        assertEquals(listOf(11, 25, 36), result.comments.map { it.end })
        assertEquals("// mini-off", code.substring(result.comments[0].start, result.comments[0].end))
        assertEquals(code, result.source)
    }

    @Test
    fun anObjectLiteralKeepsItsKindsOfProperties() {
        val obj = assertIs<ObjectExpression>(expression("({ a: 1, b, [c]: 2, d() {}, get e() { return 1 }, set e(v) {}, ...f, 'g': 3, 4: 5 })"))
        val kinds = obj.properties.map { p ->
            if (p is Property) {
                p.kind.name.lowercase() + (if (p.shorthand) "/shorthand" else "") + (if (p.computed) "/computed" else "") + (if (p.method) "/method" else "")
            } else {
                p.type
            }
        }
        assertEquals(listOf("init", "init/shorthand", "init/computed", "init/method", "get", "set", "SpreadElement", "init", "init"), kinds)
    }

    @Test
    fun aShorthandPropertyHasOneRangeForKeyAndValueButTwoNodes() {
        val property = (expression("({ ab })") as ObjectExpression).properties.single() as Property
        assertEquals(SourceRange(3, 5), property.range)
        assertEquals(SourceRange(3, 5), property.key.range)
        assertEquals(SourceRange(3, 5), property.value.range)
        assertTrue(property.key !== property.value)
    }

    @Test
    fun aMethodIsAFunctionExpressionThatStartsAtItsParenthesis() {
        val property = (expression("({ f(a) {} })") as ObjectExpression).properties.single() as Property
        val function = assertIs<FunctionExpression>(property.value)
        assertEquals(4, function.start)
        assertNull(function.id)
        assertEquals(SourceRange(3, 10), property.range)
    }

    @Test
    fun aKeywordIsAnIdentifierAsAPropertyName() {
        assertEquals("(. (. a if) class)", shape("a.if.class"))
        val key = ((expression("({ if: 1 })") as ObjectExpression).properties.single() as Property).key
        assertEquals("if", assertIs<Identifier>(key).name)
    }

    @Test
    fun anOptionalChainIsWrappedInAChainExpressionThatCoversTheWholeChain() {
        assertEquals("(chain (call (.? a b)))", shape("a?.b()"))
        assertEquals("(chain (. (.? a b) c))", shape("a?.b.c"))
        assertEquals("(. (chain (.? a b)) c)", shape("(a?.b).c"))
        assertEquals("(chain (call? a x))", shape("a?.(x)"))
        assertEquals("(chain ([]? a b))", shape("a?.[b]"))
        assertEquals(SourceRange(0, 6), expression("a?.b.c").range)
    }

    @Test
    fun anArrowFunctionKnowsItsBodyParametersAndLength() {
        val block = assertIs<ArrowFunctionExpression>(expression("(a, b = 1, ...c) => { return a }"))
        assertFalse(block.expression)
        assertIs<BlockStatement>(block.body)
        assertEquals(3, block.params.size)
        assertEquals(1, block.length)
        val simple = assertIs<ArrowFunctionExpression>(expression("x => x * 2"))
        assertTrue(simple.expression)
        assertEquals(1, simple.length)
        assertEquals(SourceRange(0, 10), simple.range)
        val objectBody = assertIs<ArrowFunctionExpression>(expression("() => ({})"))
        assertIs<ObjectExpression>(objectBody.body)
        assertTrue(objectBody.expression)
        assertTrue(assertIs<ArrowFunctionExpression>(expression("async x => x")).async)
        assertTrue(assertIs<ArrowFunctionExpression>(expression("async (x) => x")).async)
        assertFalse(assertIs<ArrowFunctionExpression>(expression("x => x")).async)
    }

    @Test
    fun aFunctionHasTheJavaScriptLengthAndItsParametersAsPatterns() {
        val code = "function f(a, {b}, [c], d = 1, ...e) {}"
        val function = assertIs<FunctionDeclaration>(statement(code))
        assertEquals(3, function.length)
        assertEquals(listOf("Identifier", "ObjectPattern", "ArrayPattern", "AssignmentPattern", "RestElement"), function.params.map { it.type })
        assertEquals("f", function.id!!.name)
        assertEquals(0, assertIs<FunctionDeclaration>(statement("function g(...a) {}")).length)
        assertEquals(SourceRange(0, code.length), function.range)
    }

    @Test
    fun asyncFunctionsAreMarkedAsync() {
        assertTrue(assertIs<FunctionDeclaration>(statement("async function f() {}")).async)
        assertTrue(assertIs<FunctionExpression>(expression("(async function () {})")).async)
        assertFalse(assertIs<FunctionExpression>(expression("(function () {})")).async)
        val method = ((expression("({ async f() {} })") as ObjectExpression).properties.single() as Property).value
        assertTrue(assertIs<FunctionExpression>(method).async)
    }

    @Test
    fun operatorsAreTypedAndAssignmentsKnowTheirOperation() {
        assertEquals(BinaryOp.EXP, assertIs<BinaryExpression>(expression("a ** b")).operator)
        assertEquals(BinaryOp.USHR, assertIs<BinaryExpression>(expression("a >>> b")).operator)
        assertEquals(BinaryOp.STRICT_NE, assertIs<BinaryExpression>(expression("a !== b")).operator)
        assertEquals(BinaryOp.IN, assertIs<BinaryExpression>(expression("a in b")).operator)
        assertEquals(LogicalOp.NULLISH, assertIs<LogicalExpression>(expression("a ?? b")).operator)
        assertEquals(UnaryOp.TYPEOF, assertIs<UnaryExpression>(expression("typeof a")).operator)
        val assign = assertIs<AssignmentExpression>(expression("a ||= b"))
        assertEquals(AssignOp.OR_ASSIGN, assign.operator)
        assertEquals(LogicalOp.OR, assign.operator.logical)
        assertNull(assign.operator.binary)
        assertEquals(BinaryOp.SUB, assertIs<AssignmentExpression>(expression("a -= b")).operator.binary)
        assertEquals(UpdateOp.DECREMENT, assertIs<UpdateExpression>(expression("a--")).operator)
    }

    @Test
    fun theLeftSideOfADestructuringAssignmentIsAPattern() {
        val assign = assertIs<AssignmentExpression>(expression("[a, b] = [b, a]"))
        assertIs<ArrayPattern>(assign.left)
        assertIs<ArrayExpression>(assign.right)
        val objectAssign = assertIs<AssignmentExpression>(expression("({ a, b: [c] = d, ...e } = f)"))
        val pattern = assertIs<ObjectPattern>(objectAssign.left)
        assertEquals(listOf("Property", "Property", "RestElement"), pattern.properties.map { it.type })
        assertIs<AssignmentPattern>((pattern.properties[1] as Property).value)
    }

    @Test
    fun declarationsKnowTheirKindAndPattern() {
        val declaration = assertIs<VariableDeclaration>(statement("const { a, b: [c] } = o, d = 1"))
        assertEquals(DeclKind.CONST, declaration.kind)
        assertEquals(2, declaration.declarations.size)
        assertIs<ObjectPattern>(declaration.declarations[0].id)
        assertEquals("d", (declaration.declarations[1].id as Identifier).name)
        assertEquals(DeclKind.LET, assertIs<VariableDeclaration>(statement("let a")).kind)
        assertNull(assertIs<VariableDeclaration>(statement("var a")).declarations.single().init)
    }

    @Test
    fun forLoopsKeepTheirHeads() {
        assertIs<VariableDeclaration>(assertIs<ForStatement>(statement("for (let i = 0; i < 3; i++) a")).init)
        assertNull(assertIs<ForStatement>(statement("for (;;) a")).test)
        assertIs<VariableDeclaration>(assertIs<ForInStatement>(statement("for (const k in o) a")).left)
        assertIs<ArrayPattern>(assertIs<ForOfStatement>(statement("for ([a, b] of c) d")).left)
        assertIs<MemberExpression>(assertIs<ForInStatement>(statement("for (a.b in o) c")).left)
    }

    @Test
    fun switchCasesAndTryClausesKeepTheirParts() {
        val switch = assertIs<SwitchStatement>(statement("switch (a) { case 1: b; c; default: d }"))
        assertEquals(listOf(2, 1), switch.cases.map { it.consequent.size })
        assertNull(switch.cases[1].test)
        val tryStatement = assertIs<TryStatement>(statement("try { a } catch ({ m }) { b } finally { c }"))
        assertIs<ObjectPattern>(tryStatement.handler!!.param)
        assertNull(assertIs<TryStatement>(statement("try { a } catch { b }")).handler!!.param)
        assertNull(assertIs<TryStatement>(statement("try { a } finally { b }")).handler)
    }

    // ---- precedence and associativity ------------------------------------------------------------------------------

    @Test
    fun operatorsBindLikeInJavaScript() {
        assertEquals("(+ a (* b c))", shape("a + b * c"))
        assertEquals("(- (- a b) c)", shape("a - b - c"))
        assertEquals("(** 2 (** 3 2))", shape("2 ** 3 ** 2"))
        assertEquals("(** (- a) b)", shape("(-a) ** b"))
        assertEquals("(** a (- b))", shape("a ** -b"))
        assertEquals("(|| a (&& b c))", shape("a || b && c"))
        assertEquals("(?? (?? a b) c)", shape("a ?? b ?? c"))
        assertEquals("(|| (?? a b) c)", shape("(a ?? b) || c"))
        assertEquals("(| a (^ b (& c d)))", shape("a | b ^ c & d"))
        assertEquals("(== a (< b c))", shape("a == b < c"))
        assertEquals("(< (<< a b) c)", shape("a << b < c"))
        assertEquals("(? a b (? c d e))", shape("a ? b : c ? d : e"))
        assertEquals("(? a (= b 1) (= c 2))", shape("a ? b = 1 : c = 2"))
        assertEquals("(= a (= b c))", shape("a = b = c"))
        assertEquals("(, a b c)", shape("a, b, c"))
        assertEquals("(=== (typeof a) \"string\")", shape("typeof a === \"string\""))
        assertEquals("(! (! a))", shape("!!a"))
        assertEquals("(- (- a))", shape("- -a"))
        assertEquals("(+ (post++ a) (pre++ b))", shape("a++ + ++b"))
    }

    @Test
    fun logicalAndCoalesceCannotBeMixedWithoutParentheses() {
        val message = "Logical expressions and coalesce expressions cannot be mixed. Wrap either by parentheses"
        assertError("a ?? b || c", message, 7)
        assertError("a || b ?? c", message, 7)
        assertError("a ?? b && c", message, 7)
        assertError("a && b ?? c", message, 7)
    }

    @Test
    fun aUnaryOperatorBeforeExponentiationNeedsParentheses() {
        val message = "Unary operator used immediately before exponentiation expression. Parenthesis must be used to disambiguate operator precedence"
        assertError("-a ** b", message, 3, 5)
        assertError("typeof a ** b", message, 9, 11)
        assertError("- -a ** b", message, 5, 7)
        assertError("async () => await a ** b", message, 20, 22)
        assertError("-2 ** 2", message, 3, 5)
        assertEquals("(** (post++ a) b)", shape("a++ ** b"))
        assertEquals("(** (pre++ a) b)", shape("++a ** b"))
        assertEquals("(- (** a b))", shape("-(a ** b)"))
    }

    @Test
    fun callsMembersAndNewBindLikeInJavaScript() {
        assertEquals("(call (. (call (. a b) c) d) e)", shape("a.b(c).d(e)"))
        assertEquals("(new a)", shape("new a"))
        assertEquals("(new (. a b) c)", shape("new a.b(c)"))
        assertEquals("(. (new a) b)", shape("new a().b"))
        assertEquals("(new (new a))", shape("new new a"))
        assertEquals("(call (new a))", shape("new a()()"))
        assertEquals("(new (call a) b)", shape("new (a())(b)"))
        assertEquals("(call f ...a b ...c)", shape("f(...a, b, ...c)"))
        assertEquals("(call a)", shape("a()"))
        assertEquals("(call a b c)", shape("a(b, c,)"))
    }

    // ---- automatic semicolon insertion ----------------------------------------------------------------------------

    @Test
    fun aLineBreakDoesNotStopAnExpressionThatCanContinue() {
        assertEquals("(= a (call b c))", shape("a = b\n(c)"))
        assertEquals("(call a b)", shape("a\n(b)"))
        assertEquals("([] a b)", shape("a\n[b]"))
        assertEquals("(+ a b)", shape("a\n+ b"))
        assertEquals("(- a b)", shape("a\n- b"))
        assertEquals("(. a b)", shape("a\n.b"))
        val declaration = assertIs<VariableDeclaration>(statement("const a = b\n(c)"))
        assertEquals("(call b c)", s(declaration.declarations.single().init))
    }

    @Test
    fun aLineBreakEndsAStatementThatCannotContinue() {
        assertEquals("a b", shape("a\nb"))
        assertEquals("a b", shape("a; b"))
        assertEquals("a (pre++ b)", shape("a\n++b"))
        assertEquals("(post++ a) b", shape("a++\nb"))
        assertEquals("a (! b)", shape("a\n!b"))
        assertEquals("a (typeof b)", shape("a\ntypeof b"))
        assertEquals("a (new b)", shape("a\nnew b"))
        assertEquals("1 2", shape("1\n2"))
    }

    @Test
    fun returnBreakContinueAndThrowAreRestricted() {
        val function = assertIs<FunctionDeclaration>(statement("function f() { return\n1 }"))
        val body = (function.body as BlockStatement).body
        assertEquals(2, body.size)
        assertNull((body[0] as ReturnStatement).argument)
        assertEquals("1", s(body[1]))
        assertError("throw\nx", "Illegal newline after throw", 5)
        val loop = assertIs<WhileStatement>(statement("while (1) { break\nx }"))
        assertEquals(2, (loop.body as BlockStatement).body.size)
    }

    @Test
    fun anArrowNeedsItsArrowOnTheLineOfItsParameters() {
        assertError("a\n=> b", "Unexpected token", 2)
        assertError("(a)\n=> b", "Unexpected token", 4)
        assertEquals("(=> (a b) a)", shape("(a,\n b) => a"))
        assertEquals("(async => (x) x)", shape("async x => x"))
        assertEquals("async (=> (x) x)", shape("async\nx => x"))
    }

    @Test
    fun theStatementAfterADoWhileNeedsNoSemicolon() {
        assertEquals(listOf("DoWhileStatement", "ExpressionStatement"), parse("do a(); while (b) c()").body.map { it.type })
    }

    @Test
    fun aStatementThatEndsWithoutASemicolonOnTheSameLineIsAnError() {
        assertError("a b", "Unexpected token", 2)
        assertError("a() b()", "Unexpected token", 4)
        assertEquals(2, parse("{ a } b").body.size)
    }

    // ---- the grammar around identifiers ----------------------------------------------------------------------------

    @Test
    fun contextualKeywordsAreOrdinaryNamesOutsideTheirContext() {
        assertEquals("async", shape("async"))
        assertEquals("(call async x)", shape("async(x)"))
        assertEquals("(. async x)", shape("async.x"))
        assertEquals("get set of async", shape("get\nset\nof\nasync"))
        assertEquals("(label async 1)", shape("async: 1"))
        assertEquals("(label of 1)", shape("of: 1"))
        assertEquals(DeclKind.LET, assertIs<VariableDeclaration>(statement("let x")).kind)
    }

    @Test
    fun awaitIsAnOperatorAtTheTopLevelAndInAsyncFunctionsOnly() {
        assertEquals("(await a)", shape("await a"))
        assertEquals("(await (call f x))", shape("await f(x)"))
        assertEquals("(+ a (await b))", shape("a + await b"))
        assertEquals("(await (await a))", shape("await await a"))
        assertEquals("(async => () (await a))", shape("async () => await a"))
        val function = assertIs<FunctionDeclaration>(statement("function f() { await }"))
        assertEquals("await", s((function.body as BlockStatement).body.single()))
        assertError("() => await a", "Unexpected token", 12)
        assertError("function f() { await a }", "Unexpected token", 21)
    }

    @Test
    fun reservedWordsAreNotNames() {
        assertError("let", "The keyword 'let' is reserved", 0)
        assertError("let = 1", "The keyword 'let' is reserved", 0)
        assertError("var static", "The keyword 'static' is reserved", 4)
        assertError("var yield", "The keyword 'yield' is reserved", 4)
        assertError("var enum", "The keyword 'enum' is reserved", 4)
        assertError("var eval", "Binding eval in strict mode", 4)
        assertError("var if = 1", "Unexpected keyword 'if'", 4, 6)
        assertError("yield 1", "The keyword 'yield' is reserved", 0)
        assertError("eval = 1", "Assigning to eval in strict mode", 0)
    }

    @Test
    fun declarationsClashLikeInJavaScript() {
        assertError("let a; let a", "Identifier 'a' has already been declared", 11)
        assertError("const a = 1; var a", "Identifier 'a' has already been declared", 17)
        assertError("let [a, a] = b", "Identifier 'a' has already been declared", 8)
        assertError("function f(a, a) {}", "Argument name clash", 14)
        assertEquals(2, parse("var a; var a").body.size)
        assertEquals(2, parse("let a; { let a }").body.size)
        assertEquals(2, parse("function a() {} function a() {}").body.size)
    }

    @Test
    fun aConstWithoutAnInitializerIsAnErrorAtItsName() {
        assertError("const a", "Missing initializer in const declaration", 6, 7)
        assertError("const a = 1, b", "Missing initializer in const declaration", 13, 14)
        assertError("const [a]", "Missing initializer in const declaration", 6, 9)
        assertEquals(1, parse("for (const a of b) c").body.size)
        assertEquals(1, parse("for (const a in b) c").body.size)
    }

    @Test
    fun labelsAreCheckedLikeInJavaScript() {
        assertError("label: label: 1", "Label 'label' is already declared", 7)
        assertError("break", "Unsyntactic break", 0)
        assertError("continue", "Unsyntactic continue", 0)
        assertError("a: { continue a }", "Unsyntactic continue", 5)
        assertError("a: { function f() { break a } }", "Unsyntactic break", 20)
        assertEquals(2, parse("a: b\na: c").body.size) // the same label twice in a row is fine
    }

    @Test
    fun returnNeedsAFunction() {
        assertError("return 1", "'return' outside of function", 0)
        assertEquals(1, parse("function f() { return 1 }").body.size)
        assertEquals(1, parse("() => { return 1 }").body.size)
    }

    // ---- what Cosic does not run ----------------------------------------------------------------------------------

    @Test
    fun classesAreNotSupported() {
        assertError("class A {}", "Classes are not supported", 0, 5)
        assertError("x = class {}", "Classes are not supported", 4, 9)
        assertError("class A extends B {}", "Classes are not supported", 0, 5)
    }

    @Test
    fun generatorsAreNotSupported() {
        assertError("function* g() {}", "Generators are not supported", 8, 9)
        assertError("x = function* () {}", "Generators are not supported", 12, 13)
        assertError("async function* g() {}", "Generators are not supported", 14, 15)
        assertError("x = { *g() {} }", "Generators are not supported", 6, 7)
        assertError("x = { async *g() {} }", "Generators are not supported", 12, 13)
    }

    @Test
    fun regularExpressionsAreNotSupported() {
        assertError("x = /ab+c/g", "Regular expressions are not supported", 4, 11)
        assertError("f(/x/)", "Regular expressions are not supported", 2, 5)
        assertError("if (a) /x/.test(b)", "Regular expressions are not supported", 7, 10)
        assertEquals("(/ (/ a b) c)", shape("a / b / c"))
    }

    @Test
    fun bigIntLiteralsAreNotSupported() {
        assertError("10n", "BigInt literals are not supported", 0, 3)
    }

    @Test
    fun importAndExportAreNotSupported() {
        val message = "'import' and 'export' may appear only with 'sourceType: module'"
        assertError("import a from 'b'", message, 0, 6)
        assertError("export const a = 1", message, 0, 6)
        assertError("{ export a }", "'import' and 'export' may only appear at the top level", 2, 8)
        assertError("import('a')", "Dynamic import is not supported", 0, 6)
        assertError("import.meta", "Cannot use 'import.meta' outside a module", 0)
    }

    @Test
    fun theRestOfTheUnsupportedSyntaxIsRejectedToo() {
        assertError("with (a) b", "'with' in strict mode", 0, 4)
        assertError("debugger", "The debugger statement is not supported", 0, 8)
        assertError("function f() { new.target }", "new.target is not supported", 15, 25)
        assertError("super.a", "'super' keyword outside a method", 0, 5)
        assertError("#a", "Private identifiers are not supported", 0, 2)
        assertError("for await (x of y) z", "for await is not supported", 4, 9)
    }

    // ---- errors ----------------------------------------------------------------------------------------------------

    @Test
    fun malformedProgramsAreReportedAtTheCharacterAcornPointsAt() {
        data class Case(val code: String, val message: String, val start: Int)
        val cases = listOf(
            Case("s(\"bd\"", "Unexpected token", 6),
            Case("s(\"bd\"))", "Unexpected token", 7),
            Case("a +", "Unexpected token", 3),
            Case("[1, 2", "Unexpected token", 5),
            Case("if (a) {", "Unexpected token", 8),
            Case("a }", "Unexpected token", 2),
            Case("x = { a 1 }", "Unexpected token", 8),
            Case("\$: s(\"@\")@", "Unexpected character '@'", 9),
            Case("let x = ;", "Unexpected token", 8),
            Case("const a", "Missing initializer in const declaration", 6),
            Case("return 1", "'return' outside of function", 0),
            Case("1 = 2", "Assigning to rvalue", 0),
            Case("\"abc", "Unterminated string constant", 0),
            Case("`abc", "Unterminated template", 1),
            Case("/* abc", "Unterminated comment", 0),
            Case("a => {", "Unexpected token", 6),
            Case("f(a b)", "Unexpected token", 4),
            Case("x = { get a(v) {} }", "getter should have no params", 11),
            Case("async () => await", "Unexpected token", 17),
            Case("a.", "Unexpected token", 2),
            Case("a?.b = 1", "Optional chaining cannot appear in left-hand side", 0),
            Case("({ a = 1 })", "Shorthand property assignments are valid only in destructuring patterns", 5),
            Case("x = {a: 1,, b: 2}", "Unexpected token", 10),
            Case("foo(...)", "Unexpected token", 7),
            Case("a++ ++", "Assigning to rvalue", 0),
            Case("if (a) else b", "Unexpected token", 7),
            Case("else", "Unexpected token", 0),
            Case("`a\${b`", "Unterminated template literal", 6),
        )
        for (c in cases) {
            val e = failure(c.code)
            assertEquals(c.message, e.message, "message for '${c.code}'")
            val range = e.range!!
            // At the end of the code the range is the last character; elsewhere it starts where acorn points.
            if (c.start >= c.code.length) assertEquals(c.code.length, range.end, "range end for '${c.code}'") else assertEquals(c.start, range.start, "start for '${c.code}'")
            assertTrue(range.end > range.start, "empty range for '${c.code}'")
        }
    }

    @Test
    fun anErrorAtTheEndOfTheCodeIsReportedOnTheLastCharacter() {
        assertEquals(SourceRange(3, 4), failure("foo(").range)
        assertEquals(SourceRange(0, 1), failure("`").range)
        assertEquals("Unterminated template literal", failure("`").message)
    }

    @Test
    fun anErrorCoversTheTokenItPointsAt() {
        assertEquals(SourceRange(2, 4), failure("a if").range)
        assertEquals(SourceRange(6, 8), failure("a = b cd").range)
        assertEquals(SourceRange(4, 6), failure("var if = 1").range)
    }

    @Test
    fun theLineAndColumnOfAnOffsetFollowAcornsConvention() {
        val code = "a\nbc\r\nd\u2028e"
        fun at(offset: Int) = lineColumn(code, offset).let { it.line to it.column }
        assertEquals(1 to 0, at(0))
        assertEquals(2 to 0, at(2))
        assertEquals(2 to 1, at(3))
        assertEquals(3 to 0, at(6))
        assertEquals(4 to 0, at(8))
        assertEquals(4 to 1, at(9))
    }

    // ---- limits ------------------------------------------------------------------------------------------------------

    @Test
    fun deeplyNestedCodeIsAnErrorNotAStackOverflow() {
        val depth = 100_000
        val nested = listOf(
            "(".repeat(depth), "[".repeat(depth), "f(".repeat(depth), "a ? ".repeat(depth), "a = ".repeat(depth), "!".repeat(depth),
            "- ".repeat(depth), "typeof ".repeat(depth), "await ".repeat(depth), "a ** ".repeat(depth), "new ".repeat(depth),
            "{".repeat(depth), "if (a) ".repeat(depth), (0 until depth).joinToString("") { "l$it: " }, "() => ".repeat(depth),
            "async () => ".repeat(depth), "x = { a: ".repeat(depth), "`\${".repeat(depth), "let " + "[".repeat(depth),
            "let " + "{ a: ".repeat(depth), "x = function (a = ".repeat(depth),
        )
        for (code in nested) assertEquals("Code is nested too deeply", failure(code).message, "for ${code.take(12)}...")
    }

    @Test
    fun reasonableNestingIsFine() {
        val depth = 150
        assertEquals(1, parse("(".repeat(depth) + "a" + ")".repeat(depth)).body.size)
        assertEquals(1, parse("[".repeat(depth) + "]".repeat(depth)).body.size)
        assertEquals(1, parse("f(".repeat(depth) + ")".repeat(depth)).body.size)
        assertEquals(1, parse("{ ".repeat(depth) + "}".repeat(depth)).body.size)
        assertEquals(1, parse("a ? b : ".repeat(depth) + "c").body.size)
        assertEquals(1, parse("a = ".repeat(depth) + "c").body.size)
        assertEquals(1, parse("!".repeat(depth) + "c").body.size)
        assertEquals(1, parse("new ".repeat(depth) + "c").body.size)
        assertEquals(1, parse("x => ".repeat(depth) + "c").body.size)
        assertEquals(1, parse("let ".repeat(1) + "[".repeat(depth) + "]".repeat(depth) + " = a").body.size)
    }

    @Test
    fun aLongSequenceOfStatementsIsNotNesting() {
        assertEquals(100_000, parse("a;\n".repeat(100_000)).body.size)
        assertEquals(100_000, parse("[" + "a, ".repeat(100_000) + "]").let { ((it.body.single() as ExpressionStatement).expression as ArrayExpression).elements.size })
    }

    @Test
    fun longChainsAreParsedWithoutDeepRecursion() {
        val sum = "a" + " + a".repeat(MAX_CHAIN - 1)
        assertIs<BinaryExpression>((parse(sum).body.single() as ExpressionStatement).expression)
        val calls = "a" + ".b()".repeat(MAX_CHAIN / 2 - 1)
        assertIs<CallExpression>((parse(calls).body.single() as ExpressionStatement).expression)
    }

    @Test
    fun anAbsurdlyLongChainIsAnErrorBecauseItsTreeWouldBeDeep() {
        assertEquals("Code is nested too deeply", failure("a" + " + a".repeat(100_000)).message)
        assertEquals("Code is nested too deeply", failure("a" + ".b".repeat(100_000)).message)
        assertEquals("Code is nested too deeply", failure("a" + "()".repeat(100_000)).message)
        assertEquals("Code is nested too deeply", failure("a" + " * a".repeat(100_000)).message)
    }

    @Test
    fun parsingTwiceGivesTwoIndependentEqualTrees() {
        val first = parse("a.b(c)")
        val second = parse("a.b(c)")
        assertTrue(first !== second)
        assertEquals(dumpAst(first), dumpAst(second))
    }
}
