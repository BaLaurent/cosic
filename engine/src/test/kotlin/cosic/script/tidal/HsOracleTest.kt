package cosic.script.tidal

import cosic.pattern.Undefined
import cosic.pattern.jsNumberToString
import cosic.pattern.jsStringToNumber
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.contentOrNull
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Compares the parser and the evaluator of `tidal` with tree-sitter-haskell and hs2js. `tools/strudel-ref/hs-oracle.mjs`
 * ran the real ones over `tidal/cases.txt` and a few thousand generated programs and stored the tree each made (as text) and
 * the value `run` computed over a symbolic scope (every function and operator prints as the call it stands for, so the value
 * tells how the operators were grouped and what was applied to what).
 *
 *  - Where tree-sitter made a tree without errors the Kotlin tree must be the same, node for node, and so must the value.
 *  - Where it made errors, hs2js fails when it reaches one: the Kotlin evaluation must fail too, with `invalid syntax` when
 *    the original does (the text of the error is what tree-sitter's recovery made of the damage, which is not reproduced).
 *
 * [differences] lists the programs where Cosic is deliberately or unavoidably different; everything else is a bug.
 */
class HsOracleTest {
    private class Case(val name: String, val code: String, val tree: String?, val eval: String)

    private val scope: Map<String, Int>
    private val cases: List<Case>

    init {
        val text = javaClass.getResourceAsStream("/tidal/oracle.json")!!.bufferedReader().readText()
        val root = Json.parseToJsonElement(text).jsonObject
        scope = root.getValue("scope").jsonObject.mapValues { it.value.jsonPrimitive.int }
        cases = root.getValue("cases").jsonArray.map { element ->
            val o = element.jsonObject
            Case(
                o.getValue("name").jsonPrimitive.content,
                o.getValue("code").jsonPrimitive.content,
                o["tree"]?.jsonPrimitive?.contentOrNull,
                o.getValue("eval").jsonPrimitive.content,
            )
        }
    }

    /**
     * Case name to the reason Cosic answers otherwise: places where tree-sitter's choice between the readings of the start of
     * a declaration (a pattern, a function head, an expression) cannot be told from the code that follows by a parser that is
     * not GLR. `generated.<n>` are programs of the seeded generator of `hs-oracle.mjs`; its output is the same every time.
     */
    private val differences: Map<String, String> = mapOf(
        "id.wildcard" to "a `_` that starts a declaration is a wildcard pattern for tree-sitter, and a pattern with nothing after it is an error",
        "decl.keyword-as-bind" to "`data` at the start of a declaration is the keyword for tree-sitter even when `=` follows",
        "generated.427" to "`##label`: tree-sitter reads the operator `#` and then the label `#label`",
        "generated.455" to "the where block of a declaration goes on over the indented lines after a blank line",
        "generated.1013" to "a stray `)` after an error in a lambda: tree-sitter makes the whole declaration an ERROR, this parser keeps the lambda",
        "generated.1310" to "`if`, `then` and `else` are variables in the head of a function, which an indented line after a blank line can make of a statement",
        "generated.1862" to "the where block of a declaration goes on over the indented lines after a blank line (`f p = x where y = 1`, a blank line, an indented `d1 $ d2` is an error for tree-sitter)",
    )

    @Test
    fun theKotlinParserAndEvaluatorAgreeWithTreeSitterAndHs2js() {
        val problems = ArrayList<String>()
        var compared = 0
        for (case in cases) {
            if (case.name in differences) continue
            compared++
            val problem = check(case)
            if (problem != null) problems.add("${case.name}: ${JsonText.quote(case.code)}\n    $problem")
        }
        assertTrue(compared > 400, "the oracle has $compared cases")
        if (problems.isNotEmpty()) {
            // the whole list, for working through it
            java.io.File("build/reports").also { it.mkdirs() }.resolve("tidal-oracle.txt").writeText(problems.joinToString("\n"))
            fail("${problems.size} of $compared programs differ from tree-sitter/hs2js:\n" + problems.take(40).joinToString("\n"))
        }
    }

    /** Why the Kotlin result for [case] differs from the original's, or null. */
    private fun check(case: Case): String? {
        val tree = try {
            parseHs(preprocessHs(case.code))
        } catch (e: Throwable) {
            return "the parser failed with $e"
        }
        val mine = SymbolicWorld(scope).evaluate(tree)
        if (case.tree != null) {
            val printed = HsTreePrinter.print(tree)
            if (printed != case.tree) return "tree\n      expected ${case.tree}\n      actual   $printed"
            if (mine != case.eval) return "value\n      expected ${case.eval}\n      actual   $mine"
            return null
        }
        // the original's tree has errors, and how tree-sitter recovers from them is not reproduced. What must hold: a program
        // that fails in hs2js fails here too (a syntax error must not pass unnoticed). A program that runs although its tree has
        // errors (they are in a function or lambda that is not called) may fail here, which is the stricter answer.
        if (case.eval.startsWith("OK")) return null
        if (!mine.startsWith("ERR")) return "the original fails (${case.eval}) but this evaluation gave $mine"
        return null
    }
}

/** JSON string literals the way `JSON.stringify` writes them. */
internal object JsonText {
    fun quote(s: String): String {
        val sb = StringBuilder("\"")
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\b' -> sb.append("\\b")
                c == '\u000c' -> sb.append("\\f")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c.code < 0x20 -> sb.append("\\u%04x".format(c.code))
                Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> {
                    sb.append(c).append(s[i + 1])
                    i++
                }
                Character.isSurrogate(c) -> sb.append("\\u%04x".format(c.code))
                else -> sb.append(c)
            }
            i++
        }
        return sb.append('"').toString()
    }
}

/** The tree as the oracle prints it: see the header of `hs-oracle.mjs`. */
internal object HsTreePrinter {
    private val collapsed = mapOf(
        "local_binds" to 0, "alternatives" to 0, "guards" to 0, "qualifiers" to 0, "statements" to 0, "module" to 0,
        "do" to 1, "signature" to 2, "data_type" to 1, "newtype" to 1, "type_synomym" to 1, "class" to 1, "instance" to 1,
        "fixity" to 1, "default_types" to 1, "deriving_instance" to 1, "type_application" to 1, "th_quoted_name" to 1,
        "import" to 1, "header" to 1, "foreign_import" to 1, "foreign_export" to 1, "list_comprehension" to 2,
        "lambda_case" to 2, "qualified" to 0,
    )

    /** The leaves that are named nodes of the grammar; the others are anonymous tokens, printed as their text. */
    private val namedLeaves = setOf(
        "variable", "integer", "float", "string", "char", "label", "implicit_variable", "constructor", "comment", "pragma",
        "haddock", "module_id", "ERROR", "operator", "constructor_operator", "haskell",
    )

    fun print(node: HsNode): String {
        val keep = collapsed[node.type]
        if (keep == 0) return "(${node.type} …)"
        if (node.type == "operator" || node.type == "constructor_operator") return "${node.type}:${JsonText.quote(node.text)}"
        if (node.children.isEmpty()) {
            return if (node.type in namedLeaves) "${node.type}:${JsonText.quote(node.text)}" else JsonText.quote(node.text)
        }
        val shown = ArrayList<String>()
        for ((i, child) in node.children.withIndex()) {
            if (keep != null && i >= keep) break
            shown.add(print(child))
        }
        if (keep != null) shown.add("…")
        return "(${node.type} ${shown.joinToString(" ")})"
    }
}

/** A value of the symbolic scope: the text of the call it stands for. */
private class Sym(val text: String)

/** A function the symbolic scope defines, collecting its arguments. */
private class SymFn(val name: String, val arity: Int, val have: List<Any?>)

/** A function the evaluator made (an arrow function of hs2js). */
private class SymLambda(val body: (Any?) -> Any?)

private class SymError(message: String) : RuntimeException(message, null, false, false)

/**
 * The scope and operators of `hs-oracle.mjs`: every function is curried and prints as `name(args)`, every operator as the
 * text `(left op right)`, a string as `m"..."`.
 */
private class SymbolicWorld(private val arities: Map<String, Int>) : HsHost {
    private val assigned = HashMap<String, Any?>()

    fun evaluate(tree: HsNode): String = try {
        "OK " + show(HsEval(this).evaluate(tree))
    } catch (e: SymError) {
        "ERR " + e.message
    } catch (e: Throwable) {
        "CRASH $e"
    }

    private fun show(v: Any?): String = when (v) {
        Undefined -> "undefined"
        null -> "null"
        is SymFn, is SymLambda -> "<fn>"
        is Sym -> v.text
        is List<*> -> "[" + v.joinToString(",") { show(it) } + "]"
        is HsNodeSketch -> "<node ${v.node.type}>"
        is String -> JsonText.quote(v)
        is Double -> jsNumberToString(v)
        else -> v.toString()
    }

    override fun lookup(name: String): Any? {
        if (assigned.containsKey(name)) return assigned[name]
        val arity = arities[name] ?: return Undefined
        return if (arity == 0) Sym(name) else SymFn(name, arity, emptyList())
    }

    override fun assign(name: String, value: Any?) {
        assigned[name] = value
    }

    override fun number(text: String): Any? = jsStringToNumber(text)

    override fun string(node: HsNode): Any? = Sym("m" + node.text)

    override fun function(body: (Any?) -> Any?): Any? = SymLambda(body)

    override fun isFunction(value: Any?): Boolean = value is SymFn || value is SymLambda

    override fun call(fn: Any?, arg: Any?, at: HsNode?): Any? = when (fn) {
        is SymLambda -> fn.body(arg)
        is SymFn -> {
            val all = fn.have + listOf(arg)
            if (all.size >= fn.arity) Sym("${fn.name}(${all.joinToString(", ") { show(it) }})") else SymFn(fn.name, fn.arity, all)
        }
        else -> throw SymError("not a function")
    }

    override fun list(items: List<Any?>): Any? = items

    override fun operator(symbol: String): HsOperator? {
        val known = symbol in OPERATORS
        if (!known) return null
        return HsOperator { l, r, _ -> Sym("(${show(l)} ${decorate(symbol)} ${show(r)})") }
    }

    /** The text the oracle prints for an operator: the symbol as it is written. */
    private fun decorate(symbol: String) = symbol

    override fun nodeValue(node: HsNode): Any? = HsNodeSketch(node)

    override fun error(message: String, at: HsNode?): RuntimeException = SymError(message)

    override fun typeError(message: String, at: HsNode?): RuntimeException = SymError(message)

    private companion object {
        val OPERATORS: Set<String> = buildSet {
            for (s in listOf(">", "#", "+", "-", "*", "/")) {
                add(s)
                add("|$s")
                add("$s|")
                add("|$s|")
            }
            addAll(listOf("~>", "<~", "<\$>"))
        }
    }
}

private class HsNodeSketch(val node: HsNode)
