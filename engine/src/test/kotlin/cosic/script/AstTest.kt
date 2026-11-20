package cosic.script

import cosic.parity.ParityData
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The traversal helpers of [Node], over every program the parser reads in the tests. */
class AstTest {
    private val programs: List<Pair<String, Program>> by lazy {
        val corpus = ParityData.cases.flatMap { c -> c.codes.mapIndexed { i, code -> "${c.id}/$i" to code } }
        val hand = listOf(
            "all of it" to """
                const { a, b: [c, ...d], ...e } = o, f = x => x ?? 1
                label: for (let i = 0, j; i < 3; i++) { if (a) continue label; else break label }
                for (const [k, v] of Object.entries(o)) w(k, v); for (x in y) z; do q(); while (r)
                switch (a) { case 1: b; break; default: c }
                try { t } catch ({ message }) { m } finally { f }
                async function g(p = 1, { q }, [r], ...s) { return await p }
                async () => { throw new Error(`a${'$'}{b}c ${'$'}{`n${'$'}{m}`}`) }
                x = [1, , ...y, { a, b: 2, [c]: 3, d() {}, get e() { return 1 }, set e(v) {}, ...z }]
                a?.b.c?.(d)[e]; new Foo.Bar(1)(2); tag`x${'$'}{y}`; typeof a + (-b) ** c; (a, b); this.x
                ;[p, q] = [q, p]; ({ r, s: t = 1 } = u); a ||= b; i++; --j; void 0; delete a.b
                a ? b : c; a && b || c; (d ?? e) | f; 1 + 2 * 3 % 4 << 1 >>> 2 in x instanceof Y
                while (s) t; ;
            """.trimIndent(),
        )
        (corpus + hand).map { (name, code) -> name to parseProgram(code).program }
    }

    private fun visit(node: Node, action: (Node) -> Unit) {
        action(node)
        node.forEachChild { visit(it, action) }
    }

    private fun countChildren(node: Node): Int {
        var n = 1
        node.forEachChild { n += countChildren(it) }
        return n
    }

    @Test
    fun forEachChildVisitsExactlyTheNodesOfTheTree() {
        for ((name, program) in programs) {
            assertEquals(countNodes(program), countChildren(program), name)
        }
    }

    @Test
    fun childrenLieInsideTheirParentAndComeInSourceOrder() {
        for ((name, program) in programs) {
            visit(program) { parent ->
                var previousStart = parent.start
                parent.forEachChild { child ->
                    assertTrue(child.start >= parent.start && child.end <= parent.end, "$name: $child outside $parent")
                    assertTrue(child.start >= previousStart, "$name: $child before the sibling that precedes it in $parent")
                    previousStart = child.start
                }
            }
        }
    }

    @Test
    fun mapChildrenWithTheIdentityReturnsTheSameNode() {
        for ((name, program) in programs) {
            visit(program) { node -> assertSame(node, node.mapChildren { it }, "$name: $node") }
        }
    }

    private fun renameIdentifiers(node: Node): Node =
        if (node is Identifier) Identifier(node.start, node.end, node.name) else node.mapChildren(::renameIdentifiers)

    @Test
    fun mapChildrenRebuildsTheNodesAboveAChangedChildAndNothingElse() {
        for ((name, program) in programs) {
            val rebuilt = renameIdentifiers(program)
            assertEquals(dumpAst(program), dumpAst(rebuilt), name)
            var hasIdentifier = false
            visit(program) { if (it is Identifier) hasIdentifier = true }
            if (!hasIdentifier) assertSame(program, rebuilt, "$name has no identifier, so nothing changes")
        }
    }

    @Test
    fun aRebuiltNodeKeepsTheKindOfItsParts() {
        // A body replaced by another body of the same kind must still be accepted by every parent that has typed fields.
        val program = parseProgram("function f() { a }\ntry { b } catch { c } finally { d }\nx = { m() { e } }\nfor (;;) { g }").program
        val rebuilt = program.mapChildren { it.mapChildren { child -> child } }
        assertSame(program, rebuilt)
        fun swapBlocks(node: Node): Node =
            if (node is BlockStatement) BlockStatement(node.start, node.end, node.body) else node.mapChildren(::swapBlocks)
        assertEquals(dumpAst(program), dumpAst(swapBlocks(program)))
    }

    @Test
    fun theAnnotationSlotsStartEmptyAndHoldWhatThePassStoresInThem() {
        val program = parseProgram("function f(a) { return a }").program
        assertNull(program.scope)
        val function = program.body.single() as FunctionDeclaration
        assertNull(function.scope)
        val identifier = function.id!!
        assertNull(identifier.resolution)
        identifier.resolution = 7
        function.scope = "scope"
        assertEquals(7, identifier.resolution)
        assertEquals("scope", function.scope)
    }

    @Test
    fun everyNodeNamesItsEstreeType() {
        val types = HashSet<String>()
        for ((_, program) in programs) visit(program) { types.add(it.type) }
        val expected = listOf(
            "Program", "ExpressionStatement", "BlockStatement", "EmptyStatement", "VariableDeclaration", "VariableDeclarator",
            "FunctionDeclaration", "ReturnStatement", "IfStatement", "ForStatement", "ForInStatement", "ForOfStatement", "WhileStatement",
            "DoWhileStatement", "BreakStatement", "ContinueStatement", "ThrowStatement", "TryStatement", "CatchClause", "SwitchStatement",
            "SwitchCase", "LabeledStatement", "Identifier", "Literal", "TemplateLiteral", "TemplateElement", "TaggedTemplateExpression",
            "ArrayExpression", "ObjectExpression", "Property", "FunctionExpression", "ArrowFunctionExpression", "UnaryExpression",
            "UpdateExpression", "BinaryExpression", "LogicalExpression", "AssignmentExpression", "ConditionalExpression", "CallExpression",
            "NewExpression", "MemberExpression", "ChainExpression", "SequenceExpression", "SpreadElement", "AwaitExpression",
            "ThisExpression", "ObjectPattern", "ArrayPattern", "AssignmentPattern", "RestElement",
        )
        val missing = expected.filter { it !in types }
        assertEquals(emptyList(), missing)
    }
}
